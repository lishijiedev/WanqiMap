package io.github.lishijiedev.wanqimap.phone

/**
 * 手机端与手表端共用的 BLE 协议常量。
 *
 * ⚠️ 三个 UUID 必须和手表端的
 *    app/src/main/java/io/github/lishijiedev/wanqimap/ble/BleProtocol.kt
 *    逐字符保持一致，任何一边改了 UUID 都必须同步改另一边。
 *
 * 角色分工（重要）：
 *   手机 = BLE 外围设备 / GATT Server（广播 + 向前来订阅的手表推送目的地）
 *   手表 = BLE 中心设备 / GATT Client（扫描 + 连接 + 订阅通知 + 回写回执）
 * 这样安排是因为手表的 central 能力必定支持，而 peripheral 在部分手表上不被支持。
 */
object BleProtocol {

    /** 主服务 UUID，手表按它做扫描过滤，因此不需要定位权限。 */
    val SERVICE_UUID = java.util.UUID.fromString("6b1d1f00-9e2a-4c31-8b7d-2f4a6c8e0a11")

    /** 目的地特征：手机 → 手表，NOTIFY（手表写 CCCD 订阅，手机按 MTU 分片推送）。 */
    val CHARACTERISTIC_DESTINATION =
        java.util.UUID.fromString("6b1d1f01-9e2a-4c31-8b7d-2f4a6c8e0a11")

    /** 回执特征：手表 → 手机，WRITE。手表收齐目的地后写入 "OK"。 */
    val CHARACTERISTIC_ACK =
        java.util.UUID.fromString("6b1d1f02-9e2a-4c31-8b7d-2f4a6c8e0a11")

    /** 客户端配置描述符（CCCD）：手表写它来打开目的地通知。 */
    val DESCRIPTOR_CCC =
        java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 回执内容。 */
    const val ACK_PAYLOAD = "OK"

    /**
     * 单条报文允许的总长度上限。
     *
     * 发送端会按协商到的 MTU 自动分片（见 DestinationGattServer.sendNextChunk），
     * 所以这个值不是「单包容量」；接收端的拼装上限是 1024（手表端
     * WearDestinationClient.MAX_PAYLOAD_BYTES），这里取 512 留出余量。
     *
     * 之所以不能再小：报文除名称/地址/坐标外还要带一个 32 位的用户高德 Key，
     * 最坏情况（20 字中文名称 + 28 字中文地址 + 长 POI ID + Key）已接近原来的 256 字节。
     */
    const val MAX_PAYLOAD_BYTES = 512
}
