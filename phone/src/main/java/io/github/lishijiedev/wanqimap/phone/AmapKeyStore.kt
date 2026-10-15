package io.github.lishijiedev.wanqimap.phone

import android.content.Context

/**
 * 用户自己的高德 Android 平台 Key 的本地存取。
 *
 * 存进 MODE_PRIVATE 的 SharedPreferences —— 文件落在应用私有目录
 * （`/data/data/<包名>/shared_prefs/`），**卸载应用时随私有目录一并被系统删除**，
 * 不会在设备上留下残留。
 *
 * 为什么不把 Key 编译进 APK：Key 绑定「包名 + 签名 SHA1」，编译进去等于把
 * 作者自己的 Key 分发给所有人，调用会全部记在作者账号下。让每个使用者填自己的
 * Key，既避免这个问题，也省去他装 Android Studio 改 local.properties。
 *
 * Key 随每次推送一起经 BLE 交给手表（见 [MainActivity.pushToWatch]）——
 * 手表端没有输入界面，也不该硬编码任何 Key。
 */
object AmapKeyStore {

    private const val PREF_NAME = "wanqimap"
    private const val KEY_AMAP_API_KEY = "amap_api_key"

    /** 高德 Android 平台 Key 是 32 位十六进制字符串。 */
    private val KEY_PATTERN = Regex("^[0-9a-fA-F]{32}$")

    /** 读取已保存的 Key；没有时返回空串。 */
    fun load(context: Context): String =
        prefs(context).getString(KEY_AMAP_API_KEY, "").orEmpty()

    fun save(context: Context, key: String) {
        prefs(context).edit().putString(KEY_AMAP_API_KEY, key).apply()
    }

    /**
     * 格式校验：只挡明显的错填（长度、字符集）。
     * 真实有效性只有高德鉴权才知道，这里不做联网检查。
     */
    fun isValidFormat(key: String): Boolean = KEY_PATTERN.matches(key.trim())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
