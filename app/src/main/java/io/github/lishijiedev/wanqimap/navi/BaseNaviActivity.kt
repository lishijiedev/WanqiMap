package io.github.lishijiedev.wanqimap.navi

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.TextView
import com.amap.api.maps.AMapException
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviListener
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.enums.NaviTTSType
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import io.github.lishijiedev.wanqimap.R

/**
 * 自研导航 HUD 的基类。
 *
 * 导航引擎在本类里初始化，整条链路缺一不可：
 *   初始化 AMapNavi → 注册监听 → 申请定位权限 → startGPS → calculateRideRoute →
 *   onCalculateRouteSuccess → startNavi(GPS) → onNaviInfoUpdate 驱动 HUD。
 *
 * 任何一环漏掉，症状都一样：AMapNaviView 只有一张空地图、底部转向卡片是空的——
 * 引擎没跑起来就不会产生转向播报。所以 `mAMapNavi` 声明后必须赋值，
 * `calculateRideRoute` / `startNavi` 也不能只留注释。
 */
abstract class BaseNaviActivity : Activity(), AMapNaviViewListener, AMapNaviListener {

    companion object {
        const val DEBUG_TAG = "WanqimapNavi"

        /** 手机推送目的地时由 MainActivity 传入。 */
        const val EXTRA_DEST_LATITUDE = "extra_dest_latitude"
        const val EXTRA_DEST_LONGITUDE = "extra_dest_longitude"
        const val EXTRA_DEST_NAME = "extra_dest_name"

        /** 用户自己的高德 Key，与目的地一起推过来。 */
        const val EXTRA_AMAP_KEY = "extra_amap_key"

        private const val REQUEST_LOCATION = 401

        /**
         * 算路以「当前定位」为起点，而手表刚进页面时 GPS 往往还没出首定位，
         * 直接算路会失败。所以失败后延时重试几次，等定位就绪。
         */
        private const val MAX_ROUTE_RETRY = 4
        private const val ROUTE_RETRY_DELAY_MS = 1_500L
    }

    protected var mAMapNaviView: AMapNaviView? = null
    protected var mAMapNavi: AMapNavi? = null

    /**
     * 导航视图选项。保留一份引用，是为了在运行期改**全览留白**
     * （`RouteOverlayOptions.rect` —— 它是让 SDK 自带的全览适配圆表的关键）。
     *
     * 改完必须整份重新赋给 `view.viewOptions`：`getViewOptions()` 返回的确实是 SDK 内部那个
     * 实例（`ib.g()` 直接返回字段），但**重新赋值**才是它把选项同步给内部各持有者、
     * 并重算留白的触发点。
     */
    protected lateinit var naviViewOptions: AMapNaviViewOptions

    /** 外部指定的终点（唯一入口：手机推送）。 */
    protected var destination: NaviLatLng? = null
    protected var destinationName: String = ""

    /** 用户自己的高德 Key，由手机端随目的地推过来；为空时无法通过高德鉴权。 */
    protected var apiKey: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private var naviStarted = false
    private var routeRetryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        readDestination(intent)
        readApiKey(intent)

        // 必须在 getInstance() 之前注入：引擎、以及随后的 AMapNaviView 一创建就会
        // 拿 Key 去高德鉴权。Key 由手机端随目的地推送过来 —— 手表上没有输入界面，
        // 也不该把某个人的 Key 编译进包（Key 绑定「包名 + 签名 SHA1」，人人不同）。
        if (apiKey.isNotBlank()) {
            AMapNavi.setApiKey(applicationContext, apiKey)
        }

        // 先拿到导航引擎实例并注册监听，再让 AMapNaviView 接管地图
        try {
            mAMapNavi = AMapNavi.getInstance(applicationContext)
            mAMapNavi?.addAMapNaviListener(this)
            mAMapNavi?.setUseInnerVoice(false)
        } catch (e: AMapException) {
            Log.e(DEBUG_TAG, "初始化高德导航引擎失败", e)
        }

        setContentView(R.layout.navi)
        mAMapNaviView = findViewById(R.id.naviView)

        // 关掉 SDK 自带布局（转向卡片、底部按钮那套），地图之上才轮得到自绘 HUD
        val options: AMapNaviViewOptions = mAMapNaviView!!.viewOptions

        // —— 必需品 ——
        options.setLayoutVisible(false)
        options.setAutoDrawRoute(true)
        options.setAfterRouteAutoGray(true)
        options.setNaviArrowVisible(true)
        options.setAutoLockCar(true)
        options.setSensorEnable(true)
        options.setAutoDisplayOverview(false)

        // 自车图标（"我当前位置"那个箭头）锁在屏幕的哪个点，参数是 **0~1 的屏幕比例**。
        //
        // 不设的话 SDK 用默认锚点：竖屏 + 车头朝上下是 (0.5, 0.75)。反汇编 `ib.V()` 可见
        // 纵轴分支是 `getNaviMode() == 0 ? 0.75 : …`，而 AMapNaviView.CAR_UP_MODE 正好 = 0；
        // 真正落实的是 `ie.setCustomizedLockCenter()` → `aMap.setPointToCenter(宽×X, 高×Y)`。
        // 0.75 意味着箭头落在表盘 75% 高度处，正好被下方转向面板（占 68.5%~91%）压住。
        //   横轴 0.5  = 居中（竖屏本来就是 0.5，写出来只为明确）；
        //   纵轴 0.55 = 略低于正中：让开面板，同时比正中间多留一点前方道路。
        // 判定条件是「值 > 0 就用调用方给的」，所以设一次即长期生效，与模式切换、设置时机无关。
        options.setPointToCenter(0.5, 0.55)

        // —— 关掉「开着也看不见 / 骑行用不到」的重功能 ——
        //
        // 这一组在 SDK 里默认全是 true（javap -c 反汇编 AMapNaviViewOptions 构造函数确认），
        // 但对我们都是纯浪费：内置控件已被 setLayoutVisible(false) 隐藏，
        // 而路口放大图三项还会在接近路口时**联网下载图片**再画在看不见的地方。
        options.setRealCrossDisplayShow(false)   // 实景路口放大图（要联网下图，最重的一项）
        options.setModeCrossDisplayShow(false)   // 示意路口放大图
        options.setEyrieCrossDisplay(false)      // 鹰眼式路口（3D 放大）
        options.setLaneInfoShow(false)           // 车道信息：骑行不看，我们也没有车道控件
        options.setCameraBubbleShow(false)       // 电子眼/违章摄像头提醒：机动车向的功能

        // 路况那套：我们没有路况控件（开着也看不见），骑行也不看拥堵程度。
        // 注意「路口红绿灯倒计时」是另一个 API（setTrafficLightsVisible），下面保留不动。
        options.setTrafficLayerEnabled(false)    // 路况图层（额外的瓦片请求）
        options.setTrafficBarEnabled(false)      // 路况条
        options.setTrafficLine(false)            // 路况染色路线

        // 刻意保持默认的几项，别误关：
        //   setScreenAlwaysBright  → 导航时屏幕常亮。骑行双手在把上，息屏后没法唤醒，必须留着。
        //   setCompassEnabled      → 车头朝上时地图是旋转的，需要「北」标记判断方位。
        //   setTrafficInfoUpdateEnabled → 可能给红绿灯倒计时供数，不动。
        //   setTilt / mLockTilt    → 保留 35° 倾斜的 3D 观感。

        mAMapNaviView!!.viewOptions = options
        // 留住引用：全览时要靠它把「圆表留白」传给 SDK
        naviViewOptions = options

        mAMapNaviView!!.onCreate(savedInstanceState)
        // 车头朝上：地图跟着行进方向旋转，转弯时不用在脑子里做坐标变换。
        // 另一个选项 NORTH_UP_MODE 是正北固定朝上、只转车标，骑行时每次转弯
        // 都要自己换算方向，很难受。AMapNaviView 只有这两个模式常量，没有第三个。
        mAMapNaviView!!.setNaviMode(AMapNaviView.CAR_UP_MODE)
        mAMapNaviView!!.setTrafficLightsVisible(true)
        mAMapNaviView!!.setAMapNaviViewListener(this)

        startRouteCalculationIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        mAMapNaviView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mAMapNaviView?.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mAMapNavi?.removeAMapNaviListener(this)
        mAMapNavi?.stopNavi()
        mAMapNaviView?.onDestroy()

        // 销毁导航引擎单例。`stopNavi()` 只停导航流程，引擎本身、以及它内部由
        // `startGPS()` 拉起的定位客户端（高精度、1s 一次）都还活着 —— 后者会
        // `bindService` 拉住定位 Service，进程退不干净，定位还在后台继续跑。
        // `destroy()` 内部的 `release()` 会把引擎、定位客户端、NaviSetting 一并拆掉
        // 并清空单例（下次 `getInstance()` 会重建）；单例为空时它会自己跳过，
        // 所以重复调用、或引擎初始化失败时调用都无害。
        AMapNavi.destroy()

        // 顺带收掉保活用的前台服务，避免导航结束后通知栏一直挂着
        runCatching { stopService(Intent(this, WanqiMapNaviService::class.java)) }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) startRouteCalculationIfNeeded()
    }

    // ------------------------------------------------------------------ 算路与启动

    private fun readDestination(intent: Intent?) {
        val latitude = intent?.getDoubleExtra(EXTRA_DEST_LATITUDE, Double.NaN) ?: Double.NaN
        val longitude = intent?.getDoubleExtra(EXTRA_DEST_LONGITUDE, Double.NaN) ?: Double.NaN
        if (latitude.isNaN() || longitude.isNaN()) return

        destination = NaviLatLng(latitude, longitude)
        destinationName = intent?.getStringExtra(EXTRA_DEST_NAME).orEmpty()
        Log.d(DEBUG_TAG, "终点来自 Intent：$destinationName ($latitude, $longitude)")
    }

    /** 手机端随目的地一起推来的高德 Key；旧版手机端可能不带，此时留空。 */
    private fun readApiKey(intent: Intent?) {
        apiKey = intent?.getStringExtra(EXTRA_AMAP_KEY).orEmpty()
    }

    private fun startRouteCalculationIfNeeded() {
        val navi = mAMapNavi
        val end = destination

        if (navi == null) {
            setStatusText(getString(R.string.hud_engine_failed))
            return
        }
        if (apiKey.isBlank()) {
            // 手机端在没填 Key 时不会放行推送，正常流程到不了这里；
            // 这条兜底是为了「旧版手机端」或报文被改动时不至于只看到一张空白地图。
            setStatusText(getString(R.string.hud_no_api_key))
            return
        }
        if (end == null) {
            Log.d(DEBUG_TAG, "没有外部终点，交给高德组件页面处理")
            setStatusText(null)
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setStatusText(getString(R.string.hud_need_location))
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
            return
        }

        setStatusText(
            getString(R.string.hud_calculating, destinationName.ifBlank {
                getString(R.string.hud_destination_fallback)
            })
        )
        navi.startGPS()
        val accepted = navi.calculateRideRoute(end)
        Log.d(DEBUG_TAG, "calculateRideRoute 提交结果=$accepted")
        if (!accepted) scheduleRouteRetry()
    }

    private fun scheduleRouteRetry() {
        if (routeRetryCount >= MAX_ROUTE_RETRY) {
            setStatusText(getString(R.string.hud_route_failed))
            return
        }
        routeRetryCount++
        Log.d(DEBUG_TAG, "等定位就绪，第 $routeRetryCount 次重试算路")
        setStatusText(getString(R.string.hud_waiting_gps, routeRetryCount, MAX_ROUTE_RETRY))
        handler.postDelayed({
            val navi = mAMapNavi ?: return@postDelayed
            val end = destination ?: return@postDelayed
            navi.calculateRideRoute(end)
        }, ROUTE_RETRY_DELAY_MS)
    }

    /**
     * 真正启动导航。用 GPS 模式（实骑），不是模拟导航。
     * 两个 onCalculateRouteSuccess 重载会被不同 SDK 版本回调，这里做幂等保护。
     */
    private fun startNavigation() {
        if (naviStarted) return
        val navi = mAMapNavi ?: return

        naviStarted = true
        routeRetryCount = 0
        setStatusText(null)

        val started = navi.startNavi(NaviType.GPS)
        Log.d(DEBUG_TAG, "startNavi(GPS) -> $started")
        if (!started) setStatusText(getString(R.string.hud_start_failed))
    }

    // ------------------------------------------------------------------ 导航视图选项

    /**
     * `naviViewOptions` 是否已就绪。
     *
     * 子类不能直接写 `::naviViewOptions.isInitialized` —— Kotlin 不允许在子类里引用
     * protected lateinit 属性的 backing field，所以由基类提供这个查询。
     */
    protected fun isNaviViewOptionsReady(): Boolean = ::naviViewOptions.isInitialized

    // ------------------------------------------------------------------ 给子类用的 HUD 钩子

    /** 顶部状态提示。传 null 隐藏。 */
    protected fun setStatusText(message: String?) {
        val view = findViewById<TextView>(R.id.text_status) ?: return
        if (message.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            view.text = message
            view.visibility = View.VISIBLE
        }
    }

    /**
     * 导航是否已经真正启动。
     *
     * 「总览」按钮需要它：还没算出路线时切全览会把视图拉到很广
     * （看起来就是「全览到了半个中国」），所以那种状态下直接不允许切。
     */
    protected fun isNavigationStarted(): Boolean = naviStarted

    /** 算路成功回调（新版 SDK）。 */
    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
        Log.d(DEBUG_TAG, "算路成功 errorCode=${result?.errorCode}")
        startNavigation()
    }

    /** 算路成功回调（旧签名，兜底）。 */
    override fun onCalculateRouteSuccess(ints: IntArray?) {
        startNavigation()
    }

    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
        Log.e(DEBUG_TAG, "算路失败 errorCode=${result?.errorCode}")
        scheduleRouteRetry()
    }

    override fun onCalculateRouteFailure(i: Int) {
        Log.e(DEBUG_TAG, "算路失败 code=$i")
        scheduleRouteRetry()
    }

    override fun onGpsOpenStatus(enabled: Boolean) {
        Log.d(DEBUG_TAG, "GPS 开关状态：$enabled")
        if (!enabled) setStatusText(getString(R.string.hud_gps_off))
    }

    override fun onGpsSignalWeak(isWeak: Boolean) {
        Log.d(DEBUG_TAG, "GPS 信号弱：$isWeak")
    }

    override fun onInitNaviSuccess() {
        Log.d(DEBUG_TAG, "导航引擎初始化成功")
    }

    override fun onInitNaviFailure() {
        Log.e(DEBUG_TAG, "导航引擎初始化失败")
        setStatusText(getString(R.string.hud_engine_failed))
    }

    override fun onStartNavi(type: Int) {
        Log.d(DEBUG_TAG, "导航已启动 type=$type")
        setStatusText(null)
    }

    override fun onArriveDestination() {
        Log.d(DEBUG_TAG, "已到达目的地")
        setStatusText(getString(R.string.hud_arrived))
    }

    // ------------------------------------------------------------------ AMapNaviViewListener

    override fun onNaviSetting() {
        // 底部导航设置点击回调
    }

    override fun onNaviMapMode(naviMode: Int) {
        Log.d(DEBUG_TAG, "地图模式变化：$naviMode")
    }

    override fun onNaviCancel() {
        finish()
    }

    override fun onNaviTurnClick() {
        // 转弯view的点击回调
    }

    override fun onNextRoadClick() {
        // 下一个道路View点击回调
    }

    override fun onScanViewButtonClick() {
        // 全览按钮点击回调
    }

    override fun onLockMap(isLock: Boolean) {
        Log.d(DEBUG_TAG, "锁图状态：$isLock")
    }

    override fun onNaviViewLoaded() {
        Log.d(DEBUG_TAG, "导航页面加载成功")
    }

    override fun onMapTypeChanged(i: Int) {
        Log.d(DEBUG_TAG, "地图类型变化：$i")
    }

    /**
     * 导航视图显示模式变化。**这个回调很重要**：SDK 会自己切换显示模式
     * （内置全览按钮、以及「全览/浏览地图后 7 秒自动切回导航」那个原厂定时器），
     * 子类靠它把界面状态跟上，否则会出现「地图已回到跟车、按钮却还显示着」的脱节。
     *
     * 取值（反汇编 `ie.updateMapShowMode`）：
     * 1 = 导航、2 = 全览、3 = 导航中解锁浏览（触摸地图时 SDK 自己会切到 3）。
     */
    override fun onNaviViewShowMode(i: Int) {
        Log.d(DEBUG_TAG, "导航视图显示模式：$i")
        onViewShowModeChanged(i)
    }

    /** SDK 自己切换显示模式时的通知。默认忽略，子类按需覆写。 */
    protected open fun onViewShowModeChanged(showMode: Int) {}

    override fun onNaviBackClick(): Boolean = false

    // ------------------------------------------------------------------ AMapNaviListener 其余回调
    // 这些事件当前不需要处理，但接口要求必须全部实现。留空而不是删掉。

    override fun onTrafficStatusUpdate() {}

    override fun onLocationChange(aMapNaviLocation: AMapNaviLocation?) {}

    /**
     * 引擎准备播报的**原文**。
     *
     * 两个重载都要实现：不同 SDK 版本走其中一条。
     *
     * 这条回调是「用高德的提醒时机、但换成我们自己的输出方式」的关键：
     *  · 我们保持 `setUseInnerVoice(false)`（**不出声**），引擎便把本该念出来的文本交给应用 ——
     *    反汇编可见 `isUseInnerVoice` 只被 `AmapNaviParams`（官方组件页参数）引用，
     *    内部播报管线并不读它，所以关语音不影响本回调；
     *  · 第一个参数是 `NaviTTSType`：1=引导 2=开始导航 3=结束 4=自定义 5=打断 6=偏航，
     *    子类据此把文本翻译成**不同节奏的震动**（见 WanqiMapNaviActivity）；
     *  · 原文里自带距离（如「前方200米左转」），也是核对「高德从多少米开始提醒」最直接的窗口。
     */
    override fun onGetNavigationText(i: Int, s: String?) {
        Log.d(DEBUG_TAG, "播报原文[${broadcastTypeName(i)}]：$s")
    }

    override fun onGetNavigationText(s: String?) {
        Log.d(DEBUG_TAG, "播报原文：$s")
    }

    /** `NaviTTSType` 的可读名，让日志一眼能读懂。 */
    private fun broadcastTypeName(type: Int): String = when (type) {
        NaviTTSType.NAVIINFO_TEXT -> "引导"
        NaviTTSType.NAVI_START_TEXT -> "开始导航"
        NaviTTSType.NAVI_END_TEXT -> "结束"
        NaviTTSType.CUSTOM_TTS_TEXT -> "自定义"
        NaviTTSType.INTERRUPT_CURRENT -> "打断"
        NaviTTSType.NAVI_YAW -> "偏航"
        else -> "未知($type)"
    }

    override fun onEndEmulatorNavi() {}

    override fun onReCalculateRouteForYaw() {}

    override fun onReCalculateRouteForTrafficJam() {}

    override fun onArrivedWayPoint(i: Int) {}

    override fun onNaviInfoUpdate(naviInfo: NaviInfo?) {}

    override fun updateCameraInfo(aMapNaviCameraInfos: Array<AMapNaviCameraInfo?>?) {}

    override fun updateIntervalCameraInfo(
        aMapNaviCameraInfo: AMapNaviCameraInfo?,
        aMapNaviCameraInfo1: AMapNaviCameraInfo?,
        i: Int
    ) {
    }

    override fun onServiceAreaUpdate(aMapServiceAreaInfos: Array<AMapServiceAreaInfo?>?) {}

    override fun showCross(aMapNaviCross: AMapNaviCross?) {}

    override fun hideCross() {}

    override fun showModeCross(aMapModelCross: AMapModelCross?) {}

    override fun hideModeCross() {}

    override fun showLaneInfo(aMapLaneInfos: Array<AMapLaneInfo?>?, bytes: ByteArray?, bytes1: ByteArray?) {}

    override fun showLaneInfo(aMapLaneInfo: AMapLaneInfo?) {}

    override fun hideLaneInfo() {}

    override fun notifyParallelRoad(i: Int) {}

    override fun OnUpdateTrafficFacility(aMapNaviTrafficFacilityInfos: Array<AMapNaviTrafficFacilityInfo?>?) {}

    override fun OnUpdateTrafficFacility(aMapNaviTrafficFacilityInfo: AMapNaviTrafficFacilityInfo?) {}

    override fun updateAimlessModeStatistics(aimLessModeStat: AimLessModeStat?) {}

    override fun updateAimlessModeCongestionInfo(aimLessModeCongestionInfo: AimLessModeCongestionInfo?) {}

    override fun onPlayRing(i: Int) {}

    override fun onNaviRouteNotify(aMapNaviRouteNotifyData: AMapNaviRouteNotifyData?) {}
}
