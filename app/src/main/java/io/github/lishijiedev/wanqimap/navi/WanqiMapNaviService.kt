package io.github.lishijiedev.wanqimap.navi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.annotation.Nullable
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import io.github.lishijiedev.wanqimap.R

/**
 * 导航期间的前台服务，唯一职责是**保活**。
 *
 * 手表熄屏后系统会回收后台进程，骑行中途被回收就等于丢掉导航；
 * 这个前台服务 + OngoingActivity 用来把进程钉住，也是 Wear OS 的标准做法。
 *
 * 这里只管保活，不碰导航引擎：`AMapNavi` 是单例，监听器与导航状态全部由
 * [BaseNaviActivity] 统一持有。若这里也实现 `AMapNaviListener` 并持有自己的引擎实例，
 * 回调是空实现、起不到作用，还要和 HUD 抢同一个单例；它的 `onDestroy` 一旦
 * `stopNavi()`，就会把正在跑的导航打断。
 */
class WanqiMapNaviService : Service() {

    companion object {
        const val DEBUG_TAG = "WanqimapService"
        const val NOTIFICATION_CHANNEL_ID = "wanqimap_navi"
        private const val NOTIFICATION_ID = 1001
    }

    @Nullable
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        Log.d(DEBUG_TAG, "导航保活服务已启动")
        return Service.START_STICKY_COMPATIBILITY
    }

    private fun startForeground() {
        val title = getString(R.string.navi_notification_title)
        val text = getString(R.string.navi_notification_text)

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            title,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // 点通知、或点表盘上那个「导航进行中」图标，直接回到**导航页**（不是 MainActivity
        // 的「等待推送」页）。WanqiMapNaviActivity 是 singleTask，按 Home 后实例仍在返回栈里，
        // 这里只会把它带回前台（走 onNewIntent），导航与 HUD 状态都不受影响。
        val launchIntent = Intent(applicationContext, WanqiMapNaviActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder =
            NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_walk)
                .setOngoing(true)
                .addAction(NotificationCompat.Action(R.drawable.ic_walk, title, activityPendingIntent))
                // 逐向导航对应 CATEGORY_NAVIGATION（category 决定 Ongoing Activity 的优先级）
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val ongoingActivityStatus: Status = Status.Builder()
            .addTemplate(title)
            .build()

        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notificationBuilder)
            // 表盘上显示的动态图标 / 环境模式下的静态图标
            .setAnimatedIcon(R.drawable.animated_walk)
            .setStaticIcon(R.drawable.ic_walk)
            .setTouchIntent(activityPendingIntent)
            .setStatus(ongoingActivityStatus)
            .build()
            .apply(applicationContext)

        startForeground(
            NOTIFICATION_ID,
            notificationBuilder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }
}
