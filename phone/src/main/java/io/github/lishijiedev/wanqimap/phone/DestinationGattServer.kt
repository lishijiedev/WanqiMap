package io.github.lishijiedev.wanqimap.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

/**
 * 手机侧：BLE 外围设备（GATT Server）+ 广播。
 *
 * 生命周期**完全由事件驱动，没有任何延时关闭**：
 *   · 开 —— 用户点了「推送到手表」
 *   · 关 —— 收到手表回执（任务完成，当刻停广播），或调用方在页面离开前台时主动 [close]
 *
 * 为什么不用「N 秒后自动关」这种定时关闭：广播由蓝牙控制器硬件维持，而 App 进入后台会被
 * 系统冻结，进程里的延时任务随之停摆 —— 那一刻广播还在播，却已经没有任何代码能把它关掉，
 * 表现就是「退出重进后又收到一次推送」。把「关」绑在协议事件与生命周期上，这个状态就不存在。
 *
 * 同理，**收尾也不依赖手表的断开事件**：对端不一定发出干净的断开，那个信号可能永远不来。
 * 收到回执即停广播，见 [gattCallback] 里 ACK 分支的说明。
 *
 * 流程：
 *   1. 开启 GATT Server，注册「目的地（NOTIFY）」与「回执（WRITE）」两个特征
 *   2. 开始广播本服务的 UUID
 *   3. 手表扫描到 → 连接 → 订阅目的地通知
 *   4. 收到订阅后把目的地按 MTU 分片推送（两次通知之间等 onNotificationSent，避免丢包）
 *   5. 收到回执 ⇒ 停广播；GATT Server 随后的断开事件 / 页面生命周期释放
 *
 * 为什么不用「手表主动读」：GATT 单个 ATT 报文容量受 MTU 限制，而客户端按 offset 续读的
 * `readCharacteristic(char, offset)` 要 API 34 才有；改成发送方主动分片则完全不依赖 MTU。
 */
class DestinationGattServer(
    private val context: Context,
    private val payload: ByteArray,
    private val onStatus: (String) -> Unit,
    private val onDelivered: () -> Unit
) {

    companion object {
        private const val TAG = "WanqimapGatt"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var destinationCharacteristic: BluetoothGattCharacteristic? = null
    private var closed = false

    /**
     * BLE 的最小 MTU 是 23。手表连接后会请求更大的 MTU，这里跟着更新，
     * 分片大小按它算，保证每个通知都不超长。
     */
    @Volatile
    private var negotiatedMtu = 23

    @Volatile
    private var delivered = false

    private var subscribedDevice: BluetoothDevice? = null
    private var notifyOffset = 0
    private var lastChunkEnd = 0
    private var awaitingSendCallback = false

    /**
     * 分片发送时的解卡：部分机型在 confirm=false 的普通通知上不回调 onNotificationSent，
     * 只靠那个回调会在需要分片时卡在第一片。这里 300ms 没等到回调就自己往下发。
     *
     * 注意它与「延时关闭」性质不同——到点了只是继续发送，不改变任何生命周期；
     * 而且 MTU 协商成功时整条目的地只有一片，走不到这个分支。
     */
    private val chunkFallback = Runnable {
        if (awaitingSendCallback && notifyOffset == lastChunkEnd) {
            Log.d(TAG, "onNotificationSent 未回调，兜底继续发送下一片")
            awaitingSendCallback = false
            sendNextChunk()
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "advertising started")
            onStatus("正在等待手表连接…（请保持本页面在前台）")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed: $errorCode")
            onStatus("蓝牙广播开启失败（错误码 $errorCode），请确认蓝牙已打开且未开启飞行模式")
            close()
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            negotiatedMtu = mtu
            Log.d(TAG, "mtu negotiated: $mtu with ${device.address}")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "connection state=$newState status=$status device=${device.address}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> onStatus("手表已连接，等待订阅…")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (delivered) {
                        // 推送已完成，手表断开了：释放 GATT Server 收尾
                        //（"已送达"的提示在收到回执时就已经给出，这里不再重复）
                        close()
                    } else {
                        // 还没送到就断了：广播继续留着，手表失败后会立刻重扫、重连
                        onStatus("连接中断，等待手表重连…")
                    }
                }
            }
        }

        /** 手表写好 CCCD 表示已订阅通知，这时才开始推送目的地。 */
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
            if (descriptor.uuid == BleProtocol.DESCRIPTOR_CCC && enabled) {
                Log.d(TAG, "subscribed by ${device.address}")
                subscribedDevice = device
                notifyOffset = 0
                awaitingSendCallback = false
                sendNextChunk()
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset, byteArrayOf(0x00, 0x00)
            )
        }

        /**
         * 一个通知发出去之后才发下一个。BLE 协议栈对未确认的通知数量有限制，
         * 连着发很容易被丢弃，所以必须等这个回调。
         */
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            awaitingSendCallback = false
            handler.removeCallbacks(chunkFallback)
            Log.d(TAG, "notification sent status=$status offset=$notifyOffset/${payload.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus("发送目的地失败（$status）")
                close()
                return
            }
            sendNextChunk()
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid == BleProtocol.CHARACTERISTIC_ACK) {
                Log.d(TAG, "ack received: ${value?.toString(Charsets.UTF_8)}")
                delivered = true
                onStatus("已送达手表，导航已开启")
                onDelivered()
                // 回执到手 = 本次推送的任务已经完成 ⇒ 立刻停广播，之后任何设备都扫不到本机。
                //
                // 为什么不把「关」交给手表的断开事件：那是一个无法保证一定到达的信号 ——
                // 对端 `disconnect()` 紧接 `close()` 时不一定会发出干净的断开，App 在后台时
                // 异步回调也可能唤不醒进程。它一旦不到，广播就留在系统里，表现正是
                // 「手表退出重进又被重推一次」。发送方自己最清楚任务完成没有，不该把判断外包。
                //
                // 但这里只停广播、**不关 GATT Server**：刚交给协议栈的写响应还在发送队列里，
                // 立即关闭 Server 有可能把它掐掉，手表就永远等不到 onCharacteristicWrite、
                // 连断都不主动断。Server 本身不广播，留着不会被任何设备发现，
                // 它的释放在下面的断开事件或页面生命周期里完成。
                stopAdvertising()
            }
        }
    }

    /** 按 MTU 切一片发一片，直到发完。 */
    private fun sendNextChunk() {
        val server = gattServer ?: return
        val device = subscribedDevice ?: return
        val characteristic = destinationCharacteristic ?: return

        if (notifyOffset >= payload.size) {
            onStatus("目的地已发送，等待手表回执…")
            return
        }

        // 通知的 ATT 头比普通响应多 1 字节，留 MTU-3 的余量最保险
        val maxChunk = (negotiatedMtu - 3).coerceAtLeast(1)
        val end = minOf(notifyOffset + maxChunk, payload.size)
        val chunk = payload.copyOfRange(notifyOffset, end)

        val result = server.notifyCharacteristicChanged(device, characteristic, false, chunk)
        if (result != BluetoothStatusCodes.SUCCESS) {
            onStatus("发送目的地失败（$result）")
            close()
            return
        }
        Log.d(TAG, "notified [$notifyOffset,$end) mtu=$negotiatedMtu")
        notifyOffset = end
        lastChunkEnd = end
        awaitingSendCallback = true
        handler.removeCallbacks(chunkFallback)
        handler.postDelayed(chunkFallback, 300)
        onStatus("正在发送目的地…")
    }

    /** 开启 GATT Server 与广播。返回是否成功启动。 */
    @SuppressLint("MissingPermission")
    fun open(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            onStatus("这台设备没有蓝牙适配器")
            return false
        }
        if (!adapter.isEnabled) {
            onStatus("请先打开手机蓝牙")
            return false
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            onStatus("这台手机不支持 BLE 广播，无法推送")
            return false
        }

        val service = BluetoothGattService(
            BleProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // 目的地只走通知，属性里不需要 READ（不对外可读）；权限字段是本机侧的访问要求，保持原样。
        val destination = BluetoothGattCharacteristic(
            BleProtocol.CHARACTERISTIC_DESTINATION,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        destination.addDescriptor(
            BluetoothGattDescriptor(
                BleProtocol.DESCRIPTOR_CCC,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        destinationCharacteristic = destination

        val ack = BluetoothGattCharacteristic(
            BleProtocol.CHARACTERISTIC_ACK,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(destination)
        service.addCharacteristic(ack)

        val server = manager.openGattServer(context, gattCallback)
        if (server == null) {
            onStatus("打开 GATT Server 失败")
            return false
        }
        gattServer = server
        if (!server.addService(service)) {
            onStatus("注册 BLE 服务失败")
            close()
            return false
        }

        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            onStatus("这台手机不支持 BLE 广播，无法推送")
            close()
            return false
        }
        advertiser = leAdvertiser

        // setTimeout(0) = 不设系统层超时：广播的开启与关闭都由本对象显式控制
        // （开 = 点推送，关 = 送达成功 / 页面离开前台），不需要蓝牙栈替我们掐表。
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()

        Log.d(TAG, "starting advertising, payload=${payload.size} bytes")
        leAdvertiser.startAdvertising(settings, data, advertiseCallback)

        return true
    }

    /** 关闭广播与 GATT Server，可重复调用。 */
    @SuppressLint("MissingPermission")
    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        stopAdvertising()
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
        destinationCharacteristic = null
        subscribedDevice = null
        Log.d(TAG, "gatt server closed")
    }

    /**
     * 只停广播，与 GATT Server 的释放分开。
     *
     * 两者分开是因为时机不同：广播要**在收到回执的当下**就停（任务完成，不能再被扫到），
     * 而 Server 得等写响应真正发出去之后才关。可重复调用。
     */
    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
    }
}
