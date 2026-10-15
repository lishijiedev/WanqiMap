package io.github.lishijiedev.wanqimap.ble

/**
 * 手表端 BLE 协议常量。
 *
 * ⚠️ 三个 UUID 必须和手机端的
 *    phone/src/main/java/io/github/lishijiedev/wanqimap/phone/BleProtocol.kt
 *    逐字符保持一致，任何一边改了 UUID 都必须同步改另一边。
 *
 * 角色分工：
 *   手机 = BLE 外围设备 / GATT Server（广播 + 向前来订阅的手表推送目的地）
 *   手表 = BLE 中心设备 / GATT Client（扫描 + 连接 + 订阅通知 + 回写回执）
 */
object BleProtocol {

    val SERVICE_UUID = java.util.UUID.fromString("6b1d1f00-9e2a-4c31-8b7d-2f4a6c8e0a11")

    val CHARACTERISTIC_DESTINATION =
        java.util.UUID.fromString("6b1d1f01-9e2a-4c31-8b7d-2f4a6c8e0a11")

    val CHARACTERISTIC_ACK =
        java.util.UUID.fromString("6b1d1f02-9e2a-4c31-8b7d-2f4a6c8e0a11")

    /** 客户端配置描述符（CCCD），手表靠写它来打开目的地通知。 */
    val DESCRIPTOR_CCC =
        java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val ACK_PAYLOAD = "OK"
}
