package io.github.lishijiedev.wanqimap.ble

import org.json.JSONObject

/**
 * 从手机推送到手表的一个导航目的地。
 *
 * 只保留手表真正用得到的：算路要坐标、HUD 要名字，还有**用户自己的高德 Key**
 * —— 手表端没有 Key 输入界面，全靠这条报文带过来，在启动导航引擎之前注入
 * （见 BaseNaviActivity.onCreate）。手机端 Destination 里多出的 address / poiId
 * 在手表上没有任何消费方，故不解析、不保存。
 *
 * 坐标系说明：高德分享链接里的坐标本身就是 GCJ-02（火星坐标），
 * 与高德 SDK 使用的坐标系一致，**不需要纠偏**，直接交给高德即可。
 */
data class Destination(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val apiKey: String
) {

    companion object {
        fun fromJson(raw: String): Destination {
            val json = JSONObject(raw)
            return Destination(
                name = json.optString("n"),
                latitude = json.getDouble("lat"),
                longitude = json.getDouble("lon"),
                apiKey = json.optString("k")
            )
        }
    }
}
