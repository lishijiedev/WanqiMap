package io.github.lishijiedev.wanqimap.phone

import org.json.JSONObject

/**
 * 一个要推送给手表的导航目的地。
 *
 * 坐标系说明：高德分享链接里的坐标本身就是 GCJ-02（火星坐标），
 * 与高德 SDK 使用的坐标系一致，因此**不需要做任何纠偏**，可以原样传给手表。
 */
data class Destination(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String
) {

    /**
     * 序列化成 BLE 传输用的紧凑 JSON。字段名刻意取短，控制报文体积。
     *
     * [apiKey] 非空时附带一个 `k` 字段：手表端没有 Key 输入界面，只能靠这条报文
     * 把它带过去（手表在启动导航引擎之前注入给高德 SDK）。传 null 时不带该字段 ——
     * 存进 Bundle 用于界面重建的那份是纯目的地，不需要也不应该带上 Key。
     */
    fun toJson(apiKey: String? = null): String {
        val json = JSONObject()
        json.put("v", 1)
        json.put("lat", latitude)
        json.put("lon", longitude)
        json.put("n", name.take(MAX_NAME_CHARS))
        json.put("a", address.take(MAX_ADDRESS_CHARS))
        json.put("p", poiId)
        if (!apiKey.isNullOrBlank()) json.put("k", apiKey)
        return json.toString()
    }

    companion object {
        /** 控制长度是为了让整条 JSON 落在单次 GATT 读的容量内（见 BleProtocol.MAX_PAYLOAD_BYTES）。 */
        private const val MAX_NAME_CHARS = 20
        private const val MAX_ADDRESS_CHARS = 28

        fun fromJson(raw: String): Destination {
            val json = JSONObject(raw)
            return Destination(
                name = json.optString("n"),
                address = json.optString("a"),
                latitude = json.getDouble("lat"),
                longitude = json.getDouble("lon"),
                poiId = json.optString("p")
            )
        }
    }
}
