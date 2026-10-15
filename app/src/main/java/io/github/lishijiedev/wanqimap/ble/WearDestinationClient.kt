package io.github.lishijiedev.wanqimap.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 手表侧：BLE 中心设备，等待手机把目的地推过来。
 *
 * 只按服务 UUID 过滤扫描，配合 Manifest 里 BLUETOOTH_SCAN 的 neverForLocation 标志，
 * 因此**不需要定位权限**。
 *
 * 时序：扫描 → 连接 → 请求 MTU → 发现服务 → 订阅目的地通知 → 收齐分片 → 回执 → 断开。
 *
 * 为什么用「订阅通知」而不是「读取特征」：
 *   GATT 单次 ATT 报文的容量由 MTU 决定，而 `readCharacteristic(char, offset)` 这个
 *   按偏移续读的重载要到 API 34 才有，本项目 compileSdk 是 33 用不了。
 *   通知则由发送方按 MTU 主动分片，收到一段拼一段，直到能解析成完整 JSON，
 *   完全不依赖 MTU 协商结果，因此更稳。
 *
 * **每一步都是回调驱动，不含任何延时任务**：
 *   · 连接 / 服务发现 / 订阅失败 ⇒ 请求断开，等断开回调再重新扫描；
 *   · 回执写完（[BluetoothGattCallback.onCharacteristicWrite]）⇒ 请求断开，然后才把目的地
 *     交给上层去启动导航（顺序很重要：上层一启动导航，本页面就会 onPause 并关闭本客户端，
 *     先跳转的话回执会被那一刀掐掉）；
 *   · 界面不可见 ⇒ 调用方 [close]，停扫描并请求断开连接。
 *
 * 断开的收尾统一是「先 disconnect()，等 onConnectionStateChange 收到 DISCONNECTED 再 close()」，
 * 见 [requestDisconnect] —— `disconnect()` 紧接 `close()` 是官方文档不建议的连招。
 *
 * 为什么不需要「连接超时看门狗」：断开只可能来自本端主动请求，或链路真的断了，
 * 两种情况 controller 都会回调 onConnectionStateChange，所以「断开回调」本身就是
 * 收尾点与失败点，不必再另外掐表。
 *
 * 本类所有 BLE 调用都需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT 运行时权限，二者由 Activity
 * 在进入等待界面之前申请完毕并保证已授予，因此统一在类上抑制 MissingPermission 静态检查。
 */
@SuppressLint("MissingPermission")
class WearDestinationClient(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "WanqimapBle"

        /** 请求的 MTU。协商成功时整条目的地一个包就发完了。 */
        private const val REQUESTED_MTU = 517

        /** 拼接收到的字节上限，防止异常数据无限增长。 */
        private const val MAX_PAYLOAD_BYTES = 1024
    }

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var received = false

    /** [close] 之后置 true：页面已不可见，此后任何断开回调都不再开始扫描。 */
    @Volatile
    private var stopped = false

    private var onDestination: ((Destination) -> Unit)? = null

    /** 已解析完成、等回执送达之后再交给上层的目的地（见 [onCharacteristicWrite]）。 */
    private var pendingDestination: Destination? = null

    /**
     * 累积的是**字节**而不是字符串：分片边界可能正好切断一个多字节 UTF-8 字符，
     * 每片单独解码会产生替换字符；每次都对完整字节数组解码就不会有这个污染。
     */
    private val payloadBytes = ByteArrayOutputStream()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "found phone ${device.address}")
            onStatus("找到手机，正在连接…")
            stopScan()
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            onStatus("蓝牙扫描失败（错误码 $errorCode），正在重新开始…")
            beginScan()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection state=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onStatus("已连接手机，正在协商连接参数…")
                    gatt.requestMtu(REQUESTED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // 连接已经真正结束，此时才释放 client：既不泄漏，也不会让对端漏掉断开事件
                    //（系统上限约 32 个 client，泄漏后会反复报 133，直到重启蓝牙）
                    releaseGatt()
                    // 只有「仍在等待且页面可见」时才重新扫描：收到目的地后的断开是本次收尾，
                    // 页面离开后的断开更不该在后台开始扫描
                    if (!received && !stopped) beginScan()
                }
            }
        }

        /** 无论 MTU 协商是否成功都必须继续发现服务，不能卡在这里。 */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "mtu=$mtu status=$status")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("服务发现失败（$status），正在重新搜索…")
                // 断开回调里会释放连接并重新开始扫描，这里不再重复调用 beginScan
                requestDisconnect()
                return
            }
            val characteristic = gatt.getService(BleProtocol.SERVICE_UUID)
                ?.getCharacteristic(BleProtocol.CHARACTERISTIC_DESTINATION)
            if (characteristic == null) {
                onStatus("手机端没有提供目的地服务，正在重新搜索…")
                // 断开回调里会释放连接并重新开始扫描，这里不再重复调用 beginScan
                requestDisconnect()
                return
            }

            val descriptor = characteristic.getDescriptor(BleProtocol.DESCRIPTOR_CCC)
            if (descriptor == null) {
                onStatus("订阅失败：目的地特征缺少配置描述符")
                // 断开回调里会释放连接并重新开始扫描，这里不再重复调用 beginScan
                requestDisconnect()
                return
            }

            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                onStatus("订阅失败：无法开启通知")
                // 断开回调里会释放连接并重新开始扫描，这里不再重复调用 beginScan
                requestDisconnect()
                return
            }
            payloadBytes.reset()
            // 写 CCCD 打开通知，手机端收到后就会开始推送目的地
            gatt.writeDescriptor(descriptor, CCCD_ENABLE_NOTIFICATION)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != BleProtocol.DESCRIPTOR_CCC) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onStatus("已订阅，等待手机发送目的地…")
            } else {
                onStatus("订阅失败（$status），正在重新搜索…")
                requestDisconnect()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != BleProtocol.CHARACTERISTIC_DESTINATION) return
            if (value.isEmpty()) return

            if (payloadBytes.size() + value.size > MAX_PAYLOAD_BYTES) {
                onStatus("目的地数据异常（超出 ${MAX_PAYLOAD_BYTES} 字节）")
                return
            }
            payloadBytes.write(value)
            Log.d(TAG, "chunk ${value.size} bytes, total ${payloadBytes.size()}")
            tryFinish(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != BleProtocol.CHARACTERISTIC_ACK) return
            // 这个回调意味着回执已经真正送达手机（写响应回来了），此时断开是安全的 ——
            // 不必再等一个固定时长来「给协议栈留时间」。
            Log.d(TAG, "ack written status=$status, disconnecting")
            // 只 disconnect、不 close：手机端要靠这个断开事件释放它的 GATT Server，
            // 立刻 close 有可能把事件掐掉。释放交给随后的断开回调。
            stopScan()
            requestDisconnect()
            // 断开之后才把目的地交给上层：上层收到就会启动导航，而本页面一跳转就会 onPause
            // 并关闭本客户端 —— 若先跳转，回执就可能被那一刀掐掉。
            pendingDestination?.let { destination ->
                pendingDestination = null
                onDestination?.invoke(destination)
            }
        }
    }

    /** 收到的字节能解析成完整 JSON 就算收齐了；否则继续等下一片。 */
    private fun tryFinish(gatt: BluetoothGatt) {
        if (received) return
        val text = String(payloadBytes.toByteArray(), Charsets.UTF_8)
        val destination = runCatching { Destination.fromJson(text) }.getOrNull() ?: return

        received = true
        pendingDestination = destination
        sendAck(gatt)
        Log.d(TAG, "destination received: ${destination.name}")
    }

    private fun sendAck(gatt: BluetoothGatt) {
        val ack = gatt.getService(BleProtocol.SERVICE_UUID)
            ?.getCharacteristic(BleProtocol.CHARACTERISTIC_ACK)
        if (ack == null) {
            Log.w(TAG, "ack characteristic missing")
            return
        }
        val result = gatt.writeCharacteristic(
            ack,
            BleProtocol.ACK_PAYLOAD.toByteArray(Charsets.UTF_8),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        Log.d(TAG, "ack write submitted result=$result")
    }

    /**
     * 开始等待手机推送。应当在 Activity onResume 时调用，
     * onPause 时调用 [close] —— 这样扫描只在用户真正看着这个界面时进行。
     */
    fun start(onDestination: (Destination) -> Unit) {
        this.onDestination = onDestination
        received = false
        stopped = false
        pendingDestination = null
        payloadBytes.reset()
        beginScan()
    }

    /** 开始（或重新开始）扫描。失败后的重试就是把它再叫一次，因此不需要任何延时。 */
    private fun beginScan() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            // 永久性错误（手表根本没有蓝牙适配器），重试也没有意义
            onStatus("这台手表没有蓝牙适配器")
            return
        }
        if (!adapter.isEnabled) {
            // 用户去打开蓝牙会离开本页面，回来时 onResume 会重新开始扫描
            onStatus("请先打开手表蓝牙")
            return
        }

        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            onStatus("蓝牙扫描不可用")
            return
        }
        scanner = leScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            // 用 BALANCED（周期扫描）而不是 LOW_LATENCY（连续扫描）：
            // 手机端是持续广播的，周期扫描几秒内就能发现它，但功耗差一个量级。
            // 等待界面开着的时候这个差别很明显。
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        Log.d(TAG, "start scanning")
        leScanner.startScan(listOf(filter), settings, scanCallback)
        onStatus("正在搜索手机端广播…")
    }

    private fun stopScan() {
        runCatching { scanner?.stopScan(scanCallback) }
        scanner = null
    }

    /**
     * 请求断开连接。**只 disconnect()，不立刻 close()。**
     *
     * `disconnect()` 紧接 `close()` 是官方文档不建议的连招：`close()` 会立即释放 GATT client，
     * 对端有收不到干净断开事件的余地 —— 手机端正是靠这个事件释放它的 GATT Server。
     * 释放交给 [gattCallback] 收到 DISCONNECTED 时再做，那时连接已真正结束、双方都已知情。
     */
    private fun requestDisconnect() {
        runCatching { gatt?.disconnect() }
    }

    /** 连接已断开，释放 client（不释放会泄漏：系统上限约 32 个，满了会持续报 133）。 */
    private fun releaseGatt() {
        runCatching { gatt?.close() }
        gatt = null
    }

    /**
     * 停止等待：停扫描并请求断开连接。界面不可见时调用，
     * 保证后台不留下任何活动的 BLE；重新可见时用 [start] 从头开始。
     */
    fun close() {
        // 置 stopped 后，断开回调不会再在页面不可见时开始扫描
        stopped = true
        stopScan()
        requestDisconnect()
    }
}

/** CCCD 开启通知的值（0x0001 小端），等价于 BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE。 */
private val CCCD_ENABLE_NOTIFICATION = byteArrayOf(0x01, 0x00)
