package io.github.lishijiedev.wanqimap.navi

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.Nullable
import com.amap.api.navi.enums.NaviTTSType
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.RouteOverlayOptions
import com.amap.api.navi.view.NextTurnTipView
import io.github.lishijiedev.wanqimap.R
import java.util.Locale

/**
 * 骑行导航 HUD。
 *
 * 两套控件互斥显示，由 [HudState] 驱动：
 *
 * ```
 * 导航中（默认）  转弯指引（下方居中）+ 返回按钮（左上）
 *                 ↓ 点击地图任意位置 / 拖动地图
 * 浏览地图中      视角切换（左下）+ 缩放胶囊（右下）+ 返回按钮（左上）
 *                 ↓ 点返回按钮 / 手表返回键
 * 复位到默认导航状态（镜头归位、箭头回到锁定点、转弯指引恢复），不出确认框
 *                 ↓ 反之，7 秒无操作时 SDK 自己走同一条复位路径
 * 导航中 → 再点返回 → 弹出「是否退出导航」→ 退出 / 取消
 * ```
 *
 * 另外负责接近路口时的震动提醒。
 */
class WanqiMapNaviActivity : BaseNaviActivity() {

    companion object {
        const val DEBUG_TAG = "WanqimapHud"

        /**
         * `AMapNaviView.setShowMode(int)` 的取值。
         *
         * 反汇编 `ie.updateMapShowMode()` 得到它只认 1 / 2 / 3 三个值，其中
         * `isNavi = (mode == 1)` 这个布尔量决定「车标是否锁定跟随」：
         *
         *   · **1 = 导航**：锁定跟随（`id.c(true)` → `ic.a(true)`），渲染帧率压到 10fps；
         *   · **2 = 全览**：`id.c(false)` 停用车标对准，并执行 `id.b()` →
         *     `BaseRouteOverLay.zoomToSpan(留白, naviPath)` 算出全览镜头；
         *   · **3 = 导航中解锁浏览**：**SDK 自己在地图被触摸时就会切到这个模式**
         *     （`ie.onTouch()` 里直接 `updateMapShowMode(3)`），车标不锁定、帧率放开。
         *
         * 内置全览按钮走的就是「1 ↔ 2 互切」这一条路（`ie.j()`，异常表里名为
         * `doOverViewClientEvent`），它自己一行镜头代码都没有。
         */
        private const val SHOW_MODE_NAVIGATING = 1
        private const val SHOW_MODE_OVERVIEW = 2

        /**
         * 全览用的**四边留白比例**（相对视图宽/高）。
         *
         * 这个值会通过 `AMapNaviViewOptions.setRouteOverlayOptions(...)` → `RouteOverlayOptions.rect`
         * 交给 SDK —— 因为 `ib.D()` 算留白时**优先取** `RouteOverlayOptions.getRect()`。
         * 不设的话它会用两套按**手机屏幕**写死的默认值（竖屏那套是
         * `Rect(dp(65), dp(114)+dp(68), dp(65), dp(65)+dp(120))`，在 227dp 的表盘上
         * 可用宽高都是负数），那才是「缩小过头 / 看到半个中国」的真正原因。
         *
         * **取值必须按圆表算，不能按矩形屏算**：`newLatLngBoundsRect` 是把路线等比缩放进
         * 「去掉留白之后的那块矩形」，路线斜着走时，起终点会落在**矩形的四个角**上。
         * 设留白比例为 p，则矩形半宽 = (0.5 − p) × 表盘宽，角点到圆心的距离
         * = √2 × (0.5 − p) × 表盘宽 = 1.414 × (0.5 − p) × R（R 为表盘半径 0.5×宽）。
         * 要让角点落在圆内并留出余量（取 0.8R）：
         *     1.414 × (0.5 − p) ≤ 0.8 × 0.5  ⇒  **p ≥ 0.22**
         *
         * 所以取 25%：角点距圆心 ≈ 0.71R，起终点不会顶到表盘边缘。
         * 想让路线显大就调小，但**不要低于 0.22**，否则斜向路线的端点会出圆。
         */
        private const val OVERVIEW_PADDING_FRACTION = 0.25f

        // ---------------------------------------------------------------- 高德播报驱动的震动
        //
        // 思路：**沿用高德的提醒时机，把输出通道从扬声器换成马达**（不出声）。
        // `setUseInnerVoice(false)` 的语义本就是「你别出声，把文本给我，我自己处理」——
        // 我们把"处理"定义成震动，于是白拿到高德那套被验证过的提醒时机：
        //   · 它内部会判断"这个转向值不值得提醒"（沿路继续/微转通常不播报）→ 自动帮我们过滤；
        //   · 接近路口时会重复播报（实测 30 秒内 3 次）→ 每次都震一下，触感自然由疏到密。
        // 下面按 NaviTTSType 给不同语义配不同节奏（类型编号见 BaseNaviActivity 的日志映射）。

        /** 引导播报（转弯/路口）：单短震。多次播报之间的间隔本身就在表达"越来越近"。 */
        private val BROADCAST_GUIDE_PATTERN = longArrayOf(0, 130)

        /** 开始导航：双短震。 */
        private val BROADCAST_START_PATTERN = longArrayOf(0, 150, 120, 150)

        /** 到达目的地：三短震。 */
        private val BROADCAST_ARRIVE_PATTERN = longArrayOf(0, 150, 120, 150, 120, 150)

        /** 偏航（骑错路）：一次长震，和转弯明显区分开。 */
        private val BROADCAST_YAW_PATTERN = longArrayOf(0, 450)

        /** 两次震动的最小间隔：引擎可能在同一瞬间连发多条播报，没有这个会连成一串。 */
        private const val VIBRATE_MIN_GAP_MS = 2_000L

        /** 距离格式化：短距离精确到米，长距离折算成公里。 */
        fun formatDistance(meters: Int): String = when {
            meters <= 0 -> "0 m"
            meters < 1_000 -> "$meters m"
            meters < 10_000 -> String.format(Locale.CHINA, "%.1f km", meters / 1000.0)
            else -> String.format(Locale.CHINA, "%.0f km", meters / 1000.0)
        }

        /** 时间格式化：秒 → 「N 分钟」或「N 小时 M 分」。 */
        fun formatDuration(seconds: Int): String {
            if (seconds <= 0) return "0 分钟"
            val minutes = seconds / 60
            return if (minutes < 60) {
                "$minutes 分钟"
            } else {
                "${minutes / 60} 小时 ${minutes % 60} 分"
            }
        }
    }

    /** HUD 的两种状态。 */
    private enum class HudState {
        /** 默认：只显示转弯指引。 */
        NAVIGATING,

        /** 用户点了或拖了地图：隐藏指引，露出视角切换与缩放。 */
        BROWSING_MAP
    }

    private var hudState = HudState.NAVIGATING

    private var hudPanel: LinearLayout? = null
    private var turnPanel: LinearLayout? = null
    private var textNextRoadDistance: TextView? = null
    private var textCurrentRoad: TextView? = null
    private var textRemaining: TextView? = null
    private var btnBack: ImageButton? = null
    private var btnViewMode: ImageButton? = null
    private var zoomPill: LinearLayout? = null
    private var nextTurnTipView: NextTurnTipView? = null

    private val vibrator: Vibrator by lazy {
        val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    }

    /** 上一次震动的时间（`elapsedRealtime`），给播报驱动的震动限流。 */
    private var lastVibrateAtMs = 0L

    /**
     * 当前是否处于全览视角（决定左下按钮的图标）。
     *
     * 由 SDK 的模式变化回调驱动（[onViewShowModeChanged]）——因为它保留了原厂
     * 「7 秒无操作自动收回」，会自己切回导航模式，光靠自己记会与实际视角脱节。
     */
    private var overviewNow = false

    /** 复用一个 Rect 做命中判断，避免每次触摸都新建对象。 */
    private val hitRect = Rect()

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nextTurnTipView = findViewById(R.id.nextTurnView)
        // 把转向图标控件交给 SDK 自绘（SDK 会按 iconType 自动切换箭头）
        mAMapNaviView?.setLazyNextTurnTipView(nextTurnTipView)

        hudPanel = findViewById(R.id.hud_panel)
        turnPanel = findViewById(R.id.turn_panel)
        textNextRoadDistance = findViewById(R.id.text_next_road_distance)
        textCurrentRoad = findViewById(R.id.text_current_road)
        textRemaining = findViewById(R.id.text_remaining)

        btnBack = findViewById(R.id.btn_back)
        btnBack?.setOnClickListener { handleBackKey() }

        btnViewMode = findViewById(R.id.btn_view_mode)
        btnViewMode?.setOnClickListener { toggleViewMode() }
        refreshViewModeIcon()

        zoomPill = findViewById(R.id.zoom_pill)
        findViewById<View>(R.id.btn_zoom_in).setOnClickListener {
            Log.d(DEBUG_TAG, "放大")
            mAMapNaviView?.zoomIn()
        }
        findViewById<View>(R.id.btn_zoom_out).setOnClickListener {
            Log.d(DEBUG_TAG, "缩小")
            mAMapNaviView?.zoomOut()
        }

        applyHudState()
    }

    // ------------------------------------------------------------------ 状态机

    /**
     * 监听触摸来判断「用户是不是在操作地图」。
     *
     * 这里**只观察、不消费**事件（照常交给 super），所以地图的拖动、缩放完全不受影响。
     * 之所以不用 `setOnCameraChangeListener`：导航过程中 SDK 自己会持续移动镜头，
     * 那个回调会被自己触发，指引会一直闪没了。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            !isTouchOnControls(ev.rawX, ev.rawY)
        ) {
            enterMapBrowsing()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 触摸点是否落在 HUD 自己的控件上（落在控件上不算「操作地图」）。 */
    private fun isTouchOnControls(x: Float, y: Float): Boolean {
        val controls = arrayOf<View?>(hudPanel, btnBack, btnViewMode, zoomPill)
        for (view in controls) {
            if (view == null || view.visibility != View.VISIBLE) continue
            if (!view.getGlobalVisibleRect(hitRect)) continue
            if (hitRect.contains(x.toInt(), y.toInt())) return true
        }
        return false
    }

    private fun enterMapBrowsing() {
        // 还没算出路线时不让进：此时地图上没有路线，露出这些按钮没有意义，
        // 而且会盖住「正在规划路线…」的提示。
        if (!isNavigationStarted()) return
        if (hudState == HudState.BROWSING_MAP) return

        hudState = HudState.BROWSING_MAP
        applyHudState()
        Log.d(DEBUG_TAG, "进入浏览地图状态：隐藏转弯指引，显示视角切换与缩放")
    }

    private fun enterNavigating() {
        if (hudState == HudState.NAVIGATING) return
        hudState = HudState.NAVIGATING
        applyHudState()
        Log.d(DEBUG_TAG, "回到导航指引状态")
    }

    private fun applyHudState() {
        when (hudState) {
            HudState.NAVIGATING -> {
                hudPanel?.visibility = View.VISIBLE
                btnViewMode?.visibility = View.GONE
                zoomPill?.visibility = View.GONE
            }
            HudState.BROWSING_MAP -> {
                hudPanel?.visibility = View.GONE
                btnViewMode?.visibility = View.VISIBLE
                zoomPill?.visibility = View.VISIBLE
            }
        }
    }

    // ------------------------------------------------------------------ 返回 / 退出

    /**
     * 返回按钮与手表返回键走同一套逻辑：
     *   浏览地图中 → 复位到默认导航状态（不出确认框）
     *   导航中     → 弹框确认是否退出
     *   还没开始导航 → 直接退出
     */
    private fun handleBackKey() {
        when {
            hudState == HudState.BROWSING_MAP -> {
                // 不管是「拖/点过地图」还是「点过全览」，返回都统一复位 —— 只把 HUD 切回
                // 导航指引是不够的：相机还停在用户拖走的位置（或全览的缩小视角）上，
                // 会变成「转向指引回来了、箭头却不在该在的地方」。
                Log.d(DEBUG_TAG, "返回：复位到默认导航状态")
                resetToNaviView()
                enterNavigating()
            }
            isNavigationStarted() -> confirmExit()
            else -> finish()
        }
    }

    private fun confirmExit() {
        if (isFinishing) return
        Log.d(DEBUG_TAG, "返回：弹出退出确认框")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(R.string.hud_exit_confirm)
            .setPositiveButton(R.string.hud_exit_yes) { _, _ ->
                Log.d(DEBUG_TAG, "确认退出导航")
                finish()
            }
            .setNegativeButton(R.string.hud_exit_no) { _, _ ->
                Log.d(DEBUG_TAG, "取消退出，继续导航")
            }
            .show()
    }

    override fun onBackPressed() {
        handleBackKey()
    }

    // ------------------------------------------------------------------ 导航数据刷新

    /**
     * 导航信息刷新回调——HUD 的数据来源。
     * 每秒回调一次，注意可能不在主线程，统一 post 到 UI 线程。
     */
    override fun onNaviInfoUpdate(naviInfo: NaviInfo?) {
        val info = naviInfo ?: return
        runOnUiThread {
            showTurnPanelIfNeeded()

            textNextRoadDistance?.text = formatDistance(info.curStepRetainDistance)
            // 转向图标：NextTurnTipView 只有 setIconType 没有 getter，不能写成属性赋值
            nextTurnTipView?.setIconType(info.iconType)
            textCurrentRoad?.text = info.currentRoadName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.hud_unknown_road)
            textRemaining?.text = getString(
                R.string.hud_remaining,
                formatDistance(info.pathRetainDistance),
                formatDuration(info.pathRetainTime)
            )
        }
    }

    /** 第一条导航信息到达，说明已经在导航了，把面板从「状态提示」切成「转向指引」。 */
    private fun showTurnPanelIfNeeded() {
        if (turnPanel?.visibility == View.VISIBLE) return
        setStatusText(null)
        turnPanel?.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ 转弯震动提醒

    /** 按波形震动。 */
    private fun vibrate(pattern: LongArray) {
        if (!vibrator.hasVibrator()) {
            Log.w(DEBUG_TAG, "这台设备没有振动马达")
            return
        }
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }.onFailure { Log.w(DEBUG_TAG, "震动失败", it) }
    }

    // ---------------------------------------------------------------- 用高德的播报时机驱动震动

    /**
     * 高德准备播报时被调用 —— 我们**不出声，只震动**。
     *
     * 为什么干脆用这条：**时机是高德自己算的**，而且它内部已经做过「这个转向值不值得提醒」的
     * 判断（沿路继续、微转通常不播报），等于白拿一层语义过滤；接近路口时它会重复播报，
     * 我们每次都震一下，触感自然由疏到密 —— 不需要解析文本，也不需要自己定距离阈值。
     *
     * `NaviTTSType`：1=引导 2=开始导航 3=结束 4=自定义 5=打断 6=偏航。只处理 1/2/3/6。
     */
    override fun onGetNavigationText(type: Int, text: String?) {
        super.onGetNavigationText(type, text) // 保留基类的日志（含类型名与原文）
        vibrateForBroadcast(type)
    }

    private fun vibrateForBroadcast(type: Int) {
        val pattern = when (type) {
            NaviTTSType.NAVIINFO_TEXT -> BROADCAST_GUIDE_PATTERN
            NaviTTSType.NAVI_YAW -> BROADCAST_YAW_PATTERN
            NaviTTSType.NAVI_START_TEXT -> BROADCAST_START_PATTERN
            NaviTTSType.NAVI_END_TEXT -> BROADCAST_ARRIVE_PATTERN
            // 自定义 / 打断 / 未知类型：不震，免得被无关内容打扰
            else -> return
        }
        if (!vibrateThrottled(pattern)) {
            Log.d(DEBUG_TAG, "播报震动被限流（距上次不足 ${VIBRATE_MIN_GAP_MS}ms）")
        }
    }

    /** 带最小间隔的震动，返回是否真的震了。 */
    private fun vibrateThrottled(pattern: LongArray): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVibrateAtMs < VIBRATE_MIN_GAP_MS) return false
        lastVibrateAtMs = now
        vibrate(pattern)
        return true
    }

    // ------------------------------------------------------------------ 总览 / 导航视角切换

    private fun toggleViewMode() {
        val view = mAMapNaviView
        if (view == null) {
            Log.w(DEBUG_TAG, "导航视图还没准备好")
            return
        }
        // 还没算出路线时切视角会把视图拉到很广，所以导航真正启动前不允许切。
        if (!isNavigationStarted()) {
            Log.d(DEBUG_TAG, "导航尚未启动，忽略视角切换")
            return
        }

        val goOverview = !overviewNow
        if (goOverview) showRouteOverview() else resetToNaviView()
        Log.d(DEBUG_TAG, "切换视角 -> ${if (goOverview) "总览" else "导航视角"}")
    }

    /**
     * 把视角复位到默认导航状态。
     *
     * 只调 `setShowMode(1)` 就够，且**「拖过地图」与「全览」两种偏离状态一次都能复位**
     * （反汇编 `ib.d(1)` → `updateMapShowMode(1)` 得到）：
     *
     * 1. 内部 `id.c(true)` → `ic.a(true)`：以**车标位置**重建 CameraPosition
     *    （`zoom = mLockZoom`、`tilt = mLockTilt`）并 `moveCamera`，随后
     *    `marker.setPositionByPixels(宽 × mAnchorX, 高 × mAnchorY)` 把车标摆回锁定锚点
     *    —— 也就是我们设的 (0.5, 0.55)。**箭头复位就是这一步。**
     * 2. 末尾 `changeLockCamera()` 再把 zoom/tilt 打回锁定值。
     *    用缩放胶囊改过 zoom 也不影响：它走的是地图层 `aMap.animateCamera(zoomIn)`，
     *    **不写 `options.getZoom()`**，所以 `mLockZoom` 仍是 18，一并回到 18。
     * 3. 它**等于** `recoverLockMode()`（`ib.m()` 只有一行 `updateMapShowMode(1)`）。
     *
     * 早退路径也都安全：模式本来就是 1 时 `updateMapShowMode` 直接 return，不会有多余动作；
     * 那时 `ic.a(true)` 也因「锁定标记没变」而跳过，本来也没动过镜头。
     *
     * 幂等兜底再设一次 [setOverviewState]：SDK 在「值与当前一致」时不回调
     * [onViewShowModeChanged]，光靠回调图标会漏更新。
     */
    private fun resetToNaviView() {
        val view = mAMapNaviView ?: return
        view.setShowMode(SHOW_MODE_NAVIGATING)
        setOverviewState(false)
        Log.d(DEBUG_TAG, "视角复位到默认导航状态，相机交还 SDK")
    }

    /** 更新「当前是不是全览」这个界面状态（幂等）。 */
    private fun setOverviewState(inOverview: Boolean) {
        if (overviewNow == inOverview) return
        overviewNow = inOverview
        refreshViewModeIcon()
    }

    /**
     * SDK 自己切换了显示模式 —— 这条链路必须跟上。
     *
     * 因为我们对 `autoLockCar` 不动（保留原厂行为），SDK 会在**全览 / 浏览地图 7 秒无操作后
     * 自动切回导航模式**。不跟就会出现「相机已经回到跟车、视角切换按钮却还显示着『回定位』」
     * 这种脱节。
     *
     * 取值：1 = 导航 / 2 = 全览 / 3 = 导航中解锁浏览（地图被触摸时 SDK 自己会切到 3）。
     *
     * **mode 3 刻意不动我们的状态**：那时相机仍停在原处、视觉上还是全览，
     * 按钮该继续显示「回定位」；真正决定"收回"的是随后的 mode 1。
     */
    override fun onViewShowModeChanged(showMode: Int) {
        when (showMode) {
            SHOW_MODE_OVERVIEW -> setOverviewState(true)

            SHOW_MODE_NAVIGATING -> {
                setOverviewState(false)
                // 相机已经回到跟车视角，指引也跟着回来 —— 否则「自动收回」只收了一半。
                if (hudState == HudState.BROWSING_MAP && isNavigationStarted()) {
                    Log.d(DEBUG_TAG, "SDK 自动收回视角 -> 回到导航指引")
                    enterNavigating()
                }
            }
        }
    }

    /**
     * 进入全览。
     *
     * **下面每一条都来自反汇编高德 jar，不是猜的**：
     *
     * 1. `displayOverview()` 与 `setShowMode(2)` **是同一件事** —— `ib.l()` 只有一行
     *    `q.updateMapShowMode(2)`。
     * 2. 内置全览最终走 `BaseRouteOverLay.zoomToSpan()` →
     *    `CameraUpdateFactory.newLatLngBoundsRect(path.getBoundsForPath(), 四边留白)`
     *    + `animateCamera(…, 1000ms)`，和我们自己写的是**同一个 API**。
     * 3. **「缩小过头」的根因是留白**：`ib.D()` 算它时**优先取**
     *    `AMapNaviViewOptions.getRouteOverlayOptions().getRect()`，取不到才退回两套
     *    按手机屏幕写死的值。**所以留白是能改的** —— 见 [OVERVIEW_PADDING_FRACTION]
     *    与下面第 1 步。
     * 4. `setShowMode(2)` 还有个**我们要的副作用**：内部 `id.c(false)`
     *    （异常表里名为 `setCarOverlayLocked`）会调 `ic.a(false)`，**停用「把镜头
     *    对准车标」那套逻辑**。那套不读 `autoLockCar`，所以光关跟车对它无效 ——
     *    这是前面几版压不住镜头的原因。
     * 5. 顺便纠正一个常见误解：**「全览停一会儿自己跳回导航」不是 bug**。
     *    `updateMapShowMode(2)` 会主动装载消息 0（`lockMapDelayed`，默认 7000ms），
     *    而消息 0 就是「切回导航模式」；每次触摸地图还会把倒计时清零重来。
     *    这个原厂行为**我们保留**，界面状态由 [onViewShowModeChanged] 跟着走。
     *
     * 流程：① 把圆表留白通过 `RouteOverlayOptions.rect` 交给 SDK →
     * ② `setShowMode(2)` —— SDK 用它自己那套、配我们给的留白算全览，同时停用车标对准；
     * 7 秒无操作后它会自己切回导航模式。
     * **相机完全交给 SDK，我们只提供留白、并跟随它的模式变化。**
     */
    private fun showRouteOverview() {
        val view = mAMapNaviView ?: return

        // 1) ★ 把圆表留白交给 SDK —— 这一步才让**高德自己的全览**真正适配手表。
        //
        //    反汇编 `ib.D()` 可知，它算留白时**优先取** `RouteOverlayOptions.getRect()`：
        //      · roo != null && roo.getRect() != null  → 用它的；
        //      · 否则横屏 → Rect(dp(180)+dp(68), dp(58), dp(40), dp(65))；
        //      · 否则（手机竖屏）→ Rect(dp(65), dp(114)+dp(68), dp(65), dp(65)+dp(120))。
        //    后两个默认值是按**手机屏幕**算的，在 227dp 的表盘上可用宽高都是负数
        //    → `newLatLngBoundsRect` 只能把镜头推到极远（「缩小过头」的真正原因）。
        //    而 `RouteOverlayOptions.setRect()` 是**公开 API**，所以这个留白改得了。
        //
        //    注意复用已有的 routeOverlayOptions、只覆盖 rect：new 一个空对象塞进去，
        //    会把路线绘制的其它参数（线宽、配色等）一并冲成默认值。
        if (view.width > 0 && view.height > 0 && isNaviViewOptionsReady()) {
            val padX = (view.width * OVERVIEW_PADDING_FRACTION).toInt()
            val padY = (view.height * OVERVIEW_PADDING_FRACTION).toInt()
            val overlayOptions = naviViewOptions.routeOverlayOptions ?: RouteOverlayOptions()
            overlayOptions.rect = Rect(padX, padY, padX, padY)
            naviViewOptions.setRouteOverlayOptions(overlayOptions)
            view.viewOptions = naviViewOptions
            Log.d(
                DEBUG_TAG,
                "全览：圆表留白已交给 SDK —— 四边 ${padX}px（视图 ${view.width}×${view.height}）"
            )
        } else {
            Log.w(DEBUG_TAG, "全览：视图尺寸未就绪，无法设置留白")
        }

        // 2) 进入全览。这一步还有个**我们要的副作用**：内部 `id.c(false)`
        //    （异常表里方法名为 `setCarOverlayLocked`）会调 `ic.a(false)`，
        //    **把「把镜头对准车标」那套逻辑停掉** —— 那套不读 autoLockCar，
        //    所以光关跟车对它无效。
        //
        //    ⚠️ **刻意不动 `autoLockCar`** —— 也就是保留 SDK 的原厂行为：
        //    `updateMapShowMode(2)` 会主动装载一个定时器
        //      mEventHandler.sendEmptyMessageDelayed(0, options.getLockMapDelayed());  // 默认 7000ms
        //    消息 0 的唯一含义就是 `updateMapShowMode(1)`（切回导航）。
        //    所以「全览 7 秒无操作后自动回到导航视角」是**既定行为**，我们照它来；
        //    每次触摸地图（`ie.onTouch` → `updateMapShowMode(3)`）还会把这个倒计时清零重来。
        //    由此也定了两条：① 不要再去改 `lockMapDelayed`（早期版本把它推到 24 小时，属多余）；
        //    ② 既然它会自己切回来，界面状态必须跟着走 —— 见 [onViewShowModeChanged]。
        //
        //    想让全览停住不被收回的话，只需在 `setShowMode` 之前加一句
        //    `naviViewOptions.setAutoLockCar(false)` + 重新赋值 options。
        view.setShowMode(SHOW_MODE_OVERVIEW)
        setOverviewState(true)
    }

    /** 图标表示「点一下会切到哪」：导航中显示总览图标，总览中显示回定位图标。 */
    private fun refreshViewModeIcon() {
        val icon = if (overviewNow) R.drawable.ic_recenter else R.drawable.ic_overview
        btnViewMode?.setImageResource(icon)
        btnViewMode?.contentDescription = getString(
            if (overviewNow) R.string.hud_cd_recenter else R.string.hud_cd_overview
        )
    }

    // 注意：AMapNaviView 的销毁由父类 BaseNaviActivity.onDestroy() 统一负责，
    // 子类不要再调一次，否则是重复释放。
}
