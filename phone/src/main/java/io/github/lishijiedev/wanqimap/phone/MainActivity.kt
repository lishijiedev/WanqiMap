package io.github.lishijiedev.wanqimap.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

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
 *   3. 手动粘贴到输入框 —— 落框即自动解析，不需要再点任何按钮
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
 *
 * 「当前在干什么」只有一个出口：操作按钮下方那行 [textStatus]。
 * 唯一的例外是蓝牙权限被「拒绝且不再询问」—— 系统此后不再弹框，只能请用户去设置里开，
 * 那是必须打断他的一次弹窗。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "WanqimapPhone"
        private const val REQUEST_BLE_PERMISSIONS = 101

        /** 保存「未完成的推送任务」，供界面被系统回收后重建时恢复。 */
        private const val KEY_ACTIVE_PUSH = "key_active_push"

        /** 输入停手多久才认为用户粘完了 —— 避免边打字边往短链发请求。 */
        private const val AUTO_PARSE_DELAY_MS = 500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // 高德 Key 区：已设置时收成一行，展开态才是输入框
    private lateinit var keyCollapsed: View
    private lateinit var keyExpanded: View
    private lateinit var editApiKey: EditText
    private lateinit var btnSaveKey: Button
    private lateinit var btnCancelKey: Button
    private lateinit var textKeyState: TextView

    // 链接、解析结果与主操作
    private lateinit var editLink: EditText
    private lateinit var resultCard: View
    private lateinit var textResultName: TextView
    private lateinit var textResultAddress: TextView
    private lateinit var btnPush: Button
    private lateinit var btnStop: Button
    private lateinit var textStatus: TextView

    // 推送进度：三步逐条点亮
    private lateinit var pushSteps: View
    private val stepViews = LinkedHashMap<PushStep, TextView>()

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

    /** 当前这条目的地是否已经成功送达过 —— 决定主按钮是「推送到手表」还是「重新推送到手表」。 */
    private var pushDone = false

    /** 上次送去解析的原文，用来避免自动解析对同一段文本重复发请求。 */
    private var lastParsedInput: String? = null

    private val autoParse = Runnable { startParsing(editLink.text.toString(), notifyIfNoKey = false) }

    private val blePermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        keyCollapsed = findViewById(R.id.keyCollapsed)
        keyExpanded = findViewById(R.id.keyExpanded)
        editApiKey = findViewById(R.id.editApiKey)
        btnSaveKey = findViewById(R.id.btnSaveKey)
        btnCancelKey = findViewById(R.id.btnCancelKey)
        textKeyState = findViewById(R.id.textKeyState)

        editLink = findViewById(R.id.editLink)
        resultCard = findViewById(R.id.resultCard)
        textResultName = findViewById(R.id.textResultName)
        textResultAddress = findViewById(R.id.textResultAddress)
        btnPush = findViewById(R.id.btnPush)
        btnStop = findViewById(R.id.btnStop)
        textStatus = findViewById(R.id.textStatus)

        pushSteps = findViewById(R.id.pushSteps)
        stepViews[PushStep.ADVERTISING] = findViewById(R.id.stepAdvertising)
        stepViews[PushStep.SENDING] = findViewById(R.id.stepSending)
        stepViews[PushStep.CONFIRMING] = findViewById(R.id.stepConfirming)

        // 回填已保存的 Key，方便核对与修改
        editApiKey.setText(AmapKeyStore.load(this))
        // 改了框里的内容就要如实反映（见 refreshKeyState）
        editApiKey.addTextChangedListener(onApiKeyChanged)
        keyCollapsed.setOnClickListener { expandKeySection() }
        btnSaveKey.setOnClickListener { saveApiKey() }
        btnCancelKey.setOnClickListener { cancelKeyEdit() }

        // 粘贴/输入链接后自动解析，不用再点按钮
        editLink.addTextChangedListener(onLinkChanged)
        btnPush.setOnClickListener { onPushClicked() }
        btnStop.setOnClickListener { stopWaiting() }

        // 界面被系统回收后重建：恢复还没送达的推送任务，onStart 会接着开广播
        savedInstanceState?.getString(KEY_ACTIVE_PUSH)?.let { json ->
            activePush = runCatching { Destination.fromJson(json) }.getOrNull()
        }

        // 界面初始态由这里统一给，不依赖布局文件上的初始可见性 ——
        // 否则「停止等待」显不显示就有两个来源，出问题时无从判断是谁生效的。
        setWaiting(false)
        refreshKeyState()
        handleIntent(intent)
    }

    private val onApiKeyChanged = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = refreshKeyState()
    }

    /** 链接框变化：停手 [AUTO_PARSE_DELAY_MS] 后自动解析一次。 */
    private val onLinkChanged = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            mainHandler.removeCallbacks(autoParse)
            mainHandler.postDelayed(autoParse, AUTO_PARSE_DELAY_MS)
        }
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
            status(getString(R.string.key_state_invalid))
            return
        }
        AmapKeyStore.save(this, key)
        editApiKey.setText(key)
        status(getString(R.string.status_key_saved))
        refreshKeyState()
        // 之前因为没 Key 而没解析成的链接，这时补解析一次（内容没变过，所以只有这一处能救回来）
        startParsing(editLink.text.toString(), notifyIfNoKey = false)
    }

    /** 展开 Key 输入区：点折叠态那一行，或缺少 Key 时自动展开。 */
    private fun expandKeySection() {
        keyCollapsed.visibility = View.GONE
        keyExpanded.visibility = View.VISIBLE
    }

    /** 放弃本次编辑，退回已保存的 Key（随后 [refreshKeyState] 会把它收成一行）。 */
    private fun cancelKeyEdit() {
        editApiKey.setText(AmapKeyStore.load(this))
        refreshKeyState()
    }

    private fun hasApiKey(): Boolean = AmapKeyStore.isValidFormat(AmapKeyStore.load(this))

    /**
     * 按「输入框内容 vs 已保存的值」决定 Key 区的形态与提示。
     *
     * 关键在于**不能只看已保存的 Key**：用户改了框里的内容却没点保存时，若状态行还写着
     * 「已设置」，他会以为新 Key 已经生效 —— 而实际推送给手表的仍是旧 Key，手表鉴权失败，
     * 手机端却一切正常。改过就如实说「与已保存的 Key 不一致」。
     *
     * 形态上：已保存且没改动 ⇒ 收成一行；其余情况一律展开，让提示和输入框都在眼前。
     */
    private fun refreshKeyState() {
        val saved = AmapKeyStore.load(this)
        val typed = editApiKey.text.toString().trim()
        val keyReady = AmapKeyStore.isValidFormat(saved)

        btnPush.isEnabled = keyReady && parsed != null && activePush == null

        if (keyReady && typed == saved) {
            keyCollapsed.visibility = View.VISIBLE
            keyExpanded.visibility = View.GONE
            return
        }
        expandKeySection()

        val stateText: Int
        val stateColor: Int
        when {
            typed.isNotEmpty() && !AmapKeyStore.isValidFormat(typed) -> {
                stateText = R.string.key_state_invalid
                stateColor = R.color.key_state_missing
            }
            keyReady -> {
                stateText = R.string.key_state_dirty
                stateColor = R.color.key_state_dirty
            }
            else -> {
                stateText = R.string.key_state_missing
                stateColor = R.color.key_state_missing
            }
        }
        textKeyState.setText(stateText)
        textKeyState.setTextColor(getColor(stateColor))
        btnSaveKey.isEnabled = AmapKeyStore.isValidFormat(typed) && typed != saved
        btnCancelKey.visibility = if (keyReady) View.VISIBLE else View.GONE
    }

    /**
     * 没有 Key 就不允许解析/推送。所有「必须有 Key 才能做」的入口统一走这里，
     * 保证提示只有一份 —— 而且只写状态行，不弹 Toast：状态行就在操作按钮下方。
     */
    private fun requireApiKey(): Boolean {
        if (hasApiKey()) return true
        refreshKeyState()   // 展开 Key 区，把那行红字提示摆出来
        status(getString(R.string.status_need_api_key))
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
        // setText 会触发输入框的防抖解析，但用户刚从高德分享进来，立刻解析、不再等那半秒
        mainHandler.removeCallbacks(autoParse)
        startParsing(shared, notifyIfNoKey = true)
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析并展示目的地。
     *
     * [notifyIfNoKey] 区分两种触发：用户主动（从高德分享进来）没 Key 要明确告诉他被挡住了；
     * 输入框自动触发时则安静跳过 —— Key 区本来就摆着红字，没必要边打字边提示。
     */
    private fun startParsing(rawText: String, notifyIfNoKey: Boolean) {
        val raw = rawText.trim()
        if (raw.isEmpty()) {
            clearParsed()
            status(getString(R.string.status_idle))
            return
        }
        if (raw == lastParsedInput) return
        if (!hasApiKey()) {
            if (notifyIfNoKey) requireApiKey()
            return
        }
        lastParsedInput = raw

        clearParsed()
        status(getString(R.string.status_parsing))

        Thread {
            val outcome = runCatching { AmapShareLinkParser.parse(raw) }
            mainHandler.post {
                outcome
                    .onSuccess { result -> showParsed(result) }
                    .onFailure { error ->
                        Log.w(TAG, "parse failed", error)
                        status(getString(R.string.status_parse_failed, error.message.orEmpty()))
                    }
            }
        }.start()
    }

    /** 上一次的解析结果与进度一并作废：开始新一次解析时它们就该翻篇。 */
    private fun clearParsed() {
        parsed = null
        pushDone = false
        btnPush.isEnabled = false
        resultCard.visibility = View.GONE
        pushSteps.visibility = View.GONE
    }

    private fun showParsed(result: AmapShareLinkParser.Result) {
        val destination = result.destination
        parsed = destination
        // 新目的地一律回到「推送到手表」：按钮可用性、文案、「停止等待」的隐藏都在这一个出口
        setWaiting(false)

        // 只展示用户要确认的两项：是不是这个地方。坐标与 POI 对他没有意义
        textResultName.text = destination.name.ifBlank { getString(R.string.result_name_missing) }
        textResultAddress.text = destination.address
        textResultAddress.visibility = if (destination.address.isBlank()) View.GONE else View.VISIBLE
        resultCard.visibility = View.VISIBLE

        // 命中第几跳只对排查有意义，留在日志里，不占用户视线
        Log.d(TAG, "parsed at ${result.trace}: $destination")
        status(getString(R.string.status_parsed))
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
            handleBlePermissionDenied()
        }
    }

    /**
     * 蓝牙权限没拿到。
     *
     * 必须区分「刚拒绝、下次还会弹框」与「拒绝且不再询问」：后者系统**不会再弹框**，
     * 再点推送只会看到一个没有原因的失败 —— 用户除了卸载重装没有别的出路。
     * 所以这种时候要把系统设置页送到他面前。
     */
    private fun handleBlePermissionDenied() {
        val willNotAskAgain = blePermissions.any {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED &&
                    !shouldShowRequestPermissionRationale(it)
        }
        if (!willNotAskAgain) {
            status(getString(R.string.status_ble_permission_denied))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_ble_permission_title)
            .setMessage(R.string.dialog_ble_permission_message)
            .setPositiveButton(R.string.action_open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    private fun pushToWatch(destination: Destination) {
        // Key 与目的地走同一条报文：手表端没有输入界面，只有在收到推送的那一刻
        // 才知道该用哪个 Key —— 它在启动导航引擎之前把 Key 注入给高德 SDK。
        val payload = destination.toJson(AmapKeyStore.load(this)).toByteArray(Charsets.UTF_8)
        if (payload.size > BleProtocol.MAX_PAYLOAD_BYTES) {
            status(getString(R.string.status_payload_too_long, payload.size))
            return
        }

        gattServer?.close()
        activePush = destination
        pushDone = false
        setWaiting(true)
        showSteps()
        status(getString(R.string.status_opening_ble))

        val server = DestinationGattServer(
            context = this,
            payload = payload,
            // 三个回调都回主线程再动界面：GATT 的回调线程不由我们决定，
            // 直接在里面改 View 有可能整段更新被丢掉，界面就会停在上一次任务的状态上。
            onStatus = { message -> onMain { status(message) } },
            onStep = { step, state -> onMain { showStep(step, state) } },
            onFinished = { success -> onMain { onPushFinished(success) } }
        )
        gattServer = server
        if (!server.open()) {
            gattServer = null
            onPushFinished(false)
        }
    }

    /** 用户主动放弃本次任务：清状态 + 关广播，之后回到前台也不会再自动广播。 */
    private fun stopWaiting() {
        activePush = null
        pushDone = false
        gattServer?.close()
        gattServer = null
        setWaiting(false)
        pushSteps.visibility = View.GONE
        status(getString(R.string.status_stopped))
    }

    /**
     * 任务终结（送达或失败）唯一的收尾入口。
     *
     * 收尾与「显示哪句提示」是两件事：无论哪种结局，界面都要立刻回到"没有等待中的任务"，
     * 所以「停止等待」不可能留在送达之后的界面上。送达过的目的地，主按钮改叫「重新推送」——
     * 那时它确实只是「再推一次」，而不是「开始推送」。
     */
    private fun onPushFinished(success: Boolean) {
        activePush = null
        if (success) pushDone = true
        setWaiting(false)
    }

    /**
     * 把界面更新挪回主线程再执行。
     *
     * [DestinationGattServer] 的回调跑在蓝牙栈交给我们的线程上，是不是主线程不由本项目决定；
     * 在非主线程直接改 View 会抛异常，那段更新（可能正好是任务收尾）就会被整段丢掉。
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun setWaiting(waiting: Boolean) {
        btnPush.isEnabled = !waiting && parsed != null
        btnPush.setText(if (pushDone) R.string.action_push_again else R.string.action_push)
        btnStop.isEnabled = waiting
        btnStop.visibility = if (waiting) View.VISIBLE else View.GONE
    }

    private fun hasBlePermissions(): Boolean =
        blePermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // ------------------------------------------------------------------ 推送进度

    /** 点推送时把进度列表摆出来，三步全部回到「待办」。 */
    private fun showSteps() {
        stepViews.keys.forEach { showStep(it, StepState.TODO) }
        pushSteps.visibility = View.VISIBLE
    }

    /**
     * 更新某一步的状态。
     *
     * 用「符号 + 颜色」同时表达，是为了让用户一眼看出**现在卡在哪一步** ——
     * 例如第三步迟迟不亮，说明手表没回执，而不是手机没发出去。
     */
    private fun showStep(step: PushStep, state: StepState) {
        val view = stepViews[step] ?: return
        val mark = if (state == StepState.TODO) "○" else "●"
        val colorRes = when (state) {
            StepState.TODO -> R.color.step_todo
            StepState.ACTIVE -> R.color.step_active
            StepState.DONE -> R.color.step_done
        }
        view.text = "$mark ${getString(step.labelRes)}"
        view.setTextColor(getColor(colorRes))
    }

    private fun status(message: String) {
        textStatus.text = message
        Log.d(TAG, message)
    }
}
