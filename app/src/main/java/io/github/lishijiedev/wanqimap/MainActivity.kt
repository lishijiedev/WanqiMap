package io.github.lishijiedev.wanqimap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.amap.api.maps.MapsInitializer
import io.github.lishijiedev.wanqimap.ble.Destination
import io.github.lishijiedev.wanqimap.ble.WearDestinationClient
import io.github.lishijiedev.wanqimap.navi.BaseNaviActivity
import io.github.lishijiedev.wanqimap.navi.WanqiMapNaviService
import io.github.lishijiedev.wanqimap.navi.WanqiMapNaviActivity

/**
 * 手表端入口。
 *
 * 现在只有一条路径：**等手机推送目的地**。
 * 收到后直接把终点交给自研 HUD（[WanqiMapNaviActivity]），由它算路并启动导航。
 *
 * 「在地图上选点」那条路已经移除——手表屏幕上选点本身就难用，
 * 目的地统一从手机端的高德分享链接推过来。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "WanqimapMain"
        private const val REQUEST_PERMISSIONS = 301
    }

    private var client: WearDestinationClient? = null
    private var statusText: TextView? = null
    private var waiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val context = applicationContext
        // 设置隐私权政策
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)

        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.text_status)
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台就继续等推送：扫描只在用户看着这个界面时进行，避免后台耗电
        if (statusText != null) startWaiting()
    }

    override fun onPause() {
        super.onPause()
        // 界面一不可见就彻底停下：停扫描并断开连接，后台不留任何活动的 BLE。
        // 同时放开 waiting，否则熄屏一次之后 startWaiting() 会被 `if (waiting) return`
        // 直接挡掉，扫描再也不会恢复 —— 表现就是「必须退出 App 重进」。
        client?.close()
        waiting = false
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.close()
        client = null
    }

    // ------------------------------------------------------------------ 等待推送

    private fun requestPermissionsIfNeeded() {
        // minSdk 33，蓝牙运行时权限（API 31+）必然适用。
        //
        // POST_NOTIFICATIONS 一起申请，但两者作用完全不同：蓝牙两项是推送链路必需；
        // 通知权限只决定表盘底部那个「导航进行中」指示器能不能显示 —— OngoingActivity
        // 的数据是写在通知 extras 里的（键 android.wearable.ongoingactivities.EXTENSIONS），
        // 没有通知系统就读不到它。targetSdk 33 时系统不会自动弹这个权限框，只能自己申请。
        val missing = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (missing.isEmpty()) {
            startWaiting()
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        // 只按蓝牙两项判定能否开始扫描：通知权限被拒绝不应该拦住推送链路
        val bleGranted = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        if (bleGranted) {
            startWaiting()
        } else {
            status(getString(R.string.status_ble_denied))
        }
    }

    private fun startWaiting() {
        if (waiting) return
        waiting = true
        status(getString(R.string.status_waiting))

        client?.close()
        client = WearDestinationClient(this) { message -> runOnUiThread { status(message) } }
            .also { bleClient ->
                bleClient.start { destination ->
                    runOnUiThread { onDestinationReceived(destination) }
                }
            }
    }

    private fun onDestinationReceived(destination: Destination) {
        Log.d(TAG, "received destination: ${destination.name} " +
                "${destination.latitude},${destination.longitude}")
        launchNavigation(destination)
    }

    // ------------------------------------------------------------------ 导航

    /**
     * 进入自研 HUD 导航。
     *
     * 为什么不用 `AmapNaviPage.showRouteActivity`：那是高德的组件化路线页，导航由官方 UI 驱动；
     * 而本项目的 HUD 是自研的（[BaseNaviActivity] 自己算路 + startNavi）。
     * 两者叠在一起会互相覆盖，表现出来就是「只有一张地图、下面灰着、导航没启动」。
     */
    private fun launchNavigation(destination: Destination) {
        val context = applicationContext

        // 前台服务负责骑行过程中的保活，避免手表熄屏后进程被系统回收
        runCatching {
            startForegroundService(Intent(context, WanqiMapNaviService::class.java))
        }.onFailure { Log.w(TAG, "启动导航前台服务失败", it) }

        startActivity(
            Intent(context, WanqiMapNaviActivity::class.java).apply {
                putExtra(BaseNaviActivity.EXTRA_DEST_LATITUDE, destination.latitude)
                putExtra(BaseNaviActivity.EXTRA_DEST_LONGITUDE, destination.longitude)
                putExtra(BaseNaviActivity.EXTRA_DEST_NAME, destination.name)
                putExtra(BaseNaviActivity.EXTRA_AMAP_KEY, destination.apiKey)
            }
        )
        finish()
    }

    private fun status(message: String) {
        statusText?.text = message
        Log.d(TAG, message)
    }
}
