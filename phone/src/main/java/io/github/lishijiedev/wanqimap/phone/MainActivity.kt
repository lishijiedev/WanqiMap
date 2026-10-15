package io.github.lishijiedev.wanqimap.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * 手机端唯一界面：填高德 Key → 粘贴/接收高德分享链接 → 解析出目的地 → 通过 BLE 推送给手表。
 *
 * 使用前提：先填入并保存**自己的**高德 Key。手表端导航要靠它通过高德鉴权，
 * 所以没有 Key 时本页面不允许解析与推送 —— 否则推过去手表也只是一张空白地图。
 * Key 绑定「包名 + 签名 SHA1」，每个人都要到高德开放平台申请自己的。
 *
 * 三种进入方式：
 *   1. 在高德里「分享」到本应用（ACTION_SEND）
 *   2. 直接点开 surl.amap.com 分享链接（ACTION_VIEW）
 *   3. 手动粘贴到输入框
 *
 * 推送流程（全程由 [activePush] 这一个状态驱动，不依赖任何定时器）：
 *
 *   点「推送到手表」 → 开广播，等手表来连
 *   锁屏 / 切后台     → 关广播，任务状态保留
 *   回到前台          → 接着开广播，不用重新点
 *   手表回执到达      → 清任务状态、停广播 ⇒ 任务结束，手机端不再有任何蓝牙活动
 *
 * 所以只有「确实有一个没走完的任务」时才会有广播：没点过推送、或已经推完，
 * 怎么来回切前后台都不会广播。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "WanqimapPhone"
        private const val REQUEST_BLE_PERMISSIONS = 101

        /** 保存「未完成的推送任务」，供界面被系统回收后重建时恢复。 */
        private const val KEY_ACTIVE_PUSH = "key_active_push"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var editApiKey: EditText
    private lateinit var textKeyState: TextView
    private lateinit var editLink: EditText
    private lateinit var textResult: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnParse: Button
    private lateinit var btnPush: Button
    private lateinit var btnStop: Button
    private lateinit var btnSaveKey: Button

    private var parsed: Destination? = null
    private var gattServer: DestinationGattServer? = null

    /**
     * 本次推送任务：点了「推送到手表」之后、手表还没回执之前，这里存着待推送的目的地。
     *
     * 它是**整个流程的唯一状态**：
     *   · null    ⇒ 没有任务（还没点过推送 / 已送达 / 用户手动停止）—— 页面回到前台也不广播；
     *   · 非 null ⇒ 任务进行中 —— 页面回到前台接着广播。
     * 任务一旦结束就立刻清空，所以不会留下任何后台活动。
     */
    private var activePush: Destination? = null

    /** 等权限结果时临时存一下，授权后立刻转入 [activePush]。 */
    private var pendingPush: Destination? = null

    private val blePermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editApiKey = findViewById(R.id.editApiKey)
        textKeyState = findViewById(R.id.textKeyState)
        editLink = findViewById(R.id.editLink)
        textResult = findViewById(R.id.textResult)
        textStatus = findViewById(R.id.textStatus)
        btnParse = findViewById(R.id.btnParse)
        btnPush = findViewById(R.id.btnPush)
        btnStop = findViewById(R.id.btnStop)
        btnSaveKey = findViewById(R.id.btnSaveKey)

        // 回填已保存的 Key，方便核对与修改
        editApiKey.setText(AmapKeyStore.load(this))
        btnSaveKey.setOnClickListener { saveApiKey() }

        btnParse.setOnClickListener { startParsing(editLink.text.toString()) }
        btnPush.setOnClickListener { onPushClicked() }
        btnStop.setOnClickListener {
            // 用户主动放弃本次任务：清状态 + 关广播，之后回到前台也不会再自动广播
            activePush = null
            gattServer?.close()
            gattServer = null
            status("已停止等待")
            setWaiting(false)
        }

        // 界面被系统回收后重建：恢复还没送达的推送任务，onStart 会接着开广播
        savedInstanceState?.getString(KEY_ACTIVE_PUSH)?.let { json ->
            activePush = runCatching { Destination.fromJson(json) }.getOrNull()
        }

        refreshKeyState()
        // 一进应用就把「必须先设 Key」讲清楚；提示文案只有 requireApiKey() 那一份，各入口复用
        requireApiKey()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * 回到前台：还有没送达的推送任务就接着开广播。
     *
     * 任务在「收到回执（已送达）」「用户点停止等待」「广播根本开不起来」时就被清空了，
     * 所以没点过推送、或已经推完的页面回到前台**什么都不会发生** —— 这正是要的：
     * 只有真的有一个没走完的任务时，才会有广播存在。
     */
    override fun onStart() {
        super.onStart()
        activePush?.let { pushToWatch(it) }
    }

    /**
     * 离开前台就关掉广播与 GATT Server，但**保留任务状态**。
     *
     * 广播由蓝牙控制器硬件维持，而 App 进入后台会被系统冻结、进程里的延时任务随之停摆，
     * 所以「关」必须绑在生命周期上 —— 这是唯一能保证不留残余广播的时机，
     * 否则就会出现「手表退出重进又被重推一次」。
     */
    override fun onStop() {
        super.onStop()
        gattServer?.close()
        gattServer = null
    }

    /** 把未完成的任务写进 Bundle，界面被回收重建后还能接着推。 */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        activePush?.let { outState.putString(KEY_ACTIVE_PUSH, it.toJson()) }
    }

    override fun onDestroy() {
        super.onDestroy()
        gattServer?.close()
        gattServer = null
    }

    // ------------------------------------------------------------------ 高德 Key

    /**
     * 保存用户填的 Key。
     *
     * 没有 Key 手表端就无法通过高德鉴权（表现为地图空白、算路失败），所以这一步是
     * **使用 App 的前提**：保存成功之前解析与推送都是禁用的。
     */
    private fun saveApiKey() {
        val key = editApiKey.text.toString().trim()
        if (!AmapKeyStore.isValidFormat(key)) {
            status(getString(R.string.status_key_invalid))
            return
        }
        AmapKeyStore.save(this, key)
        editApiKey.setText(key)
        status(getString(R.string.status_key_saved))
        refreshKeyState()
    }

    private fun hasApiKey(): Boolean = AmapKeyStore.isValidFormat(AmapKeyStore.load(this))

    /**
     * 没 Key 就不允许解析/推送 —— 否则推过去手表也只会是一张空白地图。
     *
     * 状态直接写在 Key 输入框下面（红/绿），比按钮置灰更容易被看见。
     */
    private fun refreshKeyState() {
        val ready = hasApiKey()
        btnParse.isEnabled = ready
        if (!ready) btnPush.isEnabled = false
        textKeyState.setText(if (ready) R.string.key_state_set else R.string.key_state_missing)
        textKeyState.setTextColor(
            getColor(if (ready) R.color.key_state_set else R.color.key_state_missing)
        )
    }

    /** 所有「必须有 Key 才能做」的入口统一走这里，保证提示只有一份。 */
    private fun requireApiKey(): Boolean {
        if (hasApiKey()) return true
        Toast.makeText(this, R.string.toast_need_api_key, Toast.LENGTH_LONG).show()
        return false
    }

    // ------------------------------------------------------------------ 入参处理

    /** 从 ACTION_SEND / ACTION_VIEW 里取出分享内容并自动解析。 */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val shared: String? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (shared.isNullOrBlank()) return

        Log.d(TAG, "received share: $shared")
        editLink.setText(shared)
        startParsing(shared)
    }

    // ------------------------------------------------------------------ 解析

    private fun startParsing(rawText: String) {
        if (!requireApiKey()) return
        if (rawText.isBlank()) {
            status("请先粘贴高德分享链接")
            return
        }
        parsed = null
        btnPush.isEnabled = false
        textResult.text = ""
        status("正在解析…（短链需要联网跟随跳转）")
        btnParse.isEnabled = false

        Thread {
            val outcome = runCatching { AmapShareLinkParser.parse(rawText) }
            mainHandler.post {
                btnParse.isEnabled = hasApiKey()
                outcome
                    .onSuccess { result -> showParsed(result) }
                    .onFailure { error ->
                        Log.w(TAG, "parse failed", error)
                        status("解析失败：${error.message}")
                    }
            }
        }.start()
    }

    private fun showParsed(result: AmapShareLinkParser.Result) {
        val destination = result.destination
        parsed = destination
        btnPush.isEnabled = true
        textResult.text = buildString {
            append("名称：").append(destination.name.ifBlank { "（未提供）" }).append('\n')
            if (destination.address.isNotBlank()) {
                append("地址：").append(destination.address).append('\n')
            }
            append("纬度：").append(destination.latitude).append('\n')
            append("经度：").append(destination.longitude).append('\n')
            if (destination.poiId.isNotBlank()) {
                append("POI：").append(destination.poiId)
            }
        }
        status("解析成功（${result.trace}），可以推送到手表了")
    }

    // ------------------------------------------------------------------ 推送

    private fun onPushClicked() {
        if (!requireApiKey()) return
        val destination = parsed ?: return
        if (blePermissions.isNotEmpty() && !hasBlePermissions()) {
            pendingPush = destination
            requestPermissions(blePermissions, REQUEST_BLE_PERMISSIONS)
            return
        }
        pushToWatch(destination)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLE_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val destination = pendingPush
        pendingPush = null
        if (granted && destination != null) {
            pushToWatch(destination)
        } else {
            status("缺少蓝牙权限，无法推送")
        }
    }

    private fun pushToWatch(destination: Destination) {
        // Key 与目的地走同一条报文：手表端没有输入界面，只有在收到推送的那一刻
        // 才知道该用哪个 Key —— 它在启动导航引擎之前把 Key 注入给高德 SDK。
        val payload = destination.toJson(AmapKeyStore.load(this)).toByteArray(Charsets.UTF_8)
        if (payload.size > BleProtocol.MAX_PAYLOAD_BYTES) {
            status("目的地信息过长（${payload.size} 字节），无法通过蓝牙传输")
            return
        }

        gattServer?.close()
        activePush = destination
        setWaiting(true)
        status("正在开启蓝牙广播…")

        val server = DestinationGattServer(
            context = this,
            payload = payload,
            onStatus = { message ->
                status(message)
                if (message.startsWith("已送达") || message.startsWith("蓝牙广播开启失败")) {
                    // 任务到此结束：已送达；或广播根本开不起来（重试也没有意义）。
                    // 清掉任务状态后，页面再回到前台就不会重新广播。
                    activePush = null
                    setWaiting(false)
                }
            },
            onDelivered = {
                Toast.makeText(this, "已推送到手表", Toast.LENGTH_SHORT).show()
            }
        )
        gattServer = server
        if (!server.open()) {
            gattServer = null
            activePush = null
            setWaiting(false)
        }
    }

    private fun setWaiting(waiting: Boolean) {
        btnPush.isEnabled = !waiting && parsed != null
        btnStop.isEnabled = waiting
        btnStop.visibility = if (waiting) View.VISIBLE else View.GONE
    }

    private fun hasBlePermissions(): Boolean =
        blePermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun status(message: String) {
        textStatus.text = message
        Log.d(TAG, message)
    }
}
