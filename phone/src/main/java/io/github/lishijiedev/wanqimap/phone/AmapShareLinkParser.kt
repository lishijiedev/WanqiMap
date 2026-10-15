package io.github.lishijiedev.wanqimap.phone

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.math.abs

/**
 * 高德地点分享链接解析器。
 *
 * 真实样例（实测）：
 *   输入：https://surl.amap.com/2hlYUHHo1vaw5
 *   响应：HTTP/1.1 302
 *         Location: https://wb.amap.com/?p=B0KG9S1SZY,29.288972182477277,120.05338355898856,
 *                   艺东1家,承德街与城店路交叉口西40米&commonBizInfo=...
 *   结果：名称=艺东1家  地址=承德街与城店路交叉口西40米  POI=B0KG9S1SZY
 *         纬度=29.288972182477277  经度=120.05338355898856
 *
 * ⚠️ 顺序陷阱：`p` 参数是「纬度在前」，而 `lnglat=` / `position=` 是「经度在前」。
 *    搞反会导航到地球另一端，所以两者分别处理，并且最后都会过一遍合法性校验。
 *
 * 本类只依赖 android 内置的 org.json 与 JDK 的网络 API，不引入第三方库。
 */
object AmapShareLinkParser {

    private const val MAX_HOPS = 6
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private val URL_IN_TEXT = Regex("""https?://[^\s，,、）)】\]"']+""")

    /** 解析成功的结果，trace 记录了在第几跳命中，便于排查。 */
    class Result(val destination: Destination, val trace: String)

    /**
     * 从一段可能夹带其它文字的分享内容里解析出目的地。
     * 高德的「复制链接」只给 URL，但微信等场景里常常是「地点名 + 地址 + 链接」多行文本。
     */
    fun parse(sharedText: String): Result {
        val startUrl = URL_IN_TEXT.find(sharedText)?.value
            ?: throw IllegalArgumentException("这段内容里没有找到 http/https 链接")

        var current = startUrl
        var hops = 0
        val trace = StringBuilder()

        while (hops < MAX_HOPS) {
            hops++

            parseFromUrl(current)?.let { return Result(it, "第 $hops 跳命中") }

            val step = fetchOnce(current)
            step.body?.let { body ->
                val fromBody = parseFromBody(body)
                if (fromBody != null) return Result(fromBody, "第 $hops 跳响应体命中")
            }

            val next = step.nextUrl ?: break
            trace.append("第 $hops 跳无坐标 → ")
            current = next
        }

        throw IllegalArgumentException("跳转链里没有解析出坐标（${trace}原始链接：$startUrl）")
    }

    // ------------------------------------------------------------------ URL 解析

    /** 从单个 URL 里尝试解析出坐标，解析不到返回 null。 */
    private fun parseFromUrl(rawUrl: String): Destination? {
        val params = queryParams(rawUrl)
        val fallbackName = params["name"]?.takeIf { it.isNotBlank() }

        // 形式 1：wb.amap.com/?p=POI_ID,纬度,经度,名称,地址   ← 纬度在前
        params["p"]?.let { p ->
            // 名称和地址本身可能含逗号，所以最多切成 4 段
            val parts = p.split(",", limit = 4)
            if (parts.size >= 3) {
                val lat = parts[1].trim().toDoubleOrNull()
                val lon = parts[2].trim().toDoubleOrNull()
                if (lat != null && lon != null) {
                    val coord = sanitize(lat, lon) ?: return@let
                    val tail = parts.getOrNull(3)?.split(",", limit = 2).orEmpty()
                    return Destination(
                        name = tail.getOrNull(0)?.trim().orEmpty().ifBlank { fallbackName.orEmpty() },
                        address = tail.getOrNull(1)?.trim().orEmpty(),
                        latitude = coord.first,
                        longitude = coord.second,
                        poiId = parts[0].trim()
                    )
                }
            }
        }

        // 形式 2：m.amap.com/share/index/__q=纬度,经度,名称&src=...（参数在路径里，没有 ? ）
        Q_PARAM.find(rawUrl)?.let { match ->
            val parts = match.groupValues[1].split(",", limit = 3)
            val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (lat != null && lon != null) {
                sanitize(lat, lon)?.let { coord ->
                    return Destination(
                        name = parts.getOrNull(2)?.trim().orEmpty().ifBlank { fallbackName.orEmpty() },
                        address = "",
                        latitude = coord.first,
                        longitude = coord.second,
                        poiId = ""
                    )
                }
            }
        }

        // 形式 3：lnglat=经度,纬度 / position=经度,纬度 / location= / center=   ← 经度在前
        for (key in LONGITUDE_FIRST_KEYS) {
            val value = params[key] ?: continue
            val parts = value.replace(" ", "").split(",", limit = 3)
            val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: continue
            val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: continue
            val coord = sanitize(lat, lon) ?: continue
            return Destination(
                name = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: fallbackName.orEmpty(),
                address = "",
                latitude = coord.first,
                longitude = coord.second,
                poiId = ""
            )
        }

        // 形式 4：lat= / lon= 或 lng= 分开给（androidamap://viewMap 等）
        val latParam = params["lat"]?.toDoubleOrNull()
        val lonParam = (params["lon"] ?: params["lng"])?.toDoubleOrNull()
        if (latParam != null && lonParam != null) {
            sanitize(latParam, lonParam)?.let { coord ->
                return Destination(
                    name = fallbackName.orEmpty(),
                    address = "",
                    latitude = coord.first,
                    longitude = coord.second,
                    poiId = params["poiid"].orEmpty()
                )
            }
        }

        return null
    }

    private val LONGITUDE_FIRST_KEYS = listOf("lnglat", "position", "location", "center", "dest")

    /** __q 参数出现在路径里而不是 query 里，因此单独用正则在整个 URL 上找。 */
    private val Q_PARAM = Regex("""[?&/]__q=([^&]+)""")

    private fun queryParams(rawUrl: String): Map<String, String> {
        val query = rawUrl.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val rawValue = pair.substring(idx + 1)
            val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
            if (!result.containsKey(key)) result[key] = value
        }
        return result
    }

    /**
     * 坐标合法性校验。中国境内经度普遍大于 90（东经 73~135），
     * 所以一旦「纬度」超过 90 就说明顺序反了，直接交换即可纠正。
     */
    private fun sanitize(lat: Double, lon: Double): Pair<Double, Double>? {
        if (isPlausible(lat, lon)) return lat to lon
        if (isPlausible(lon, lat)) return lon to lat
        return null
    }

    private fun isPlausible(lat: Double, lon: Double): Boolean =
        abs(lat) <= 90.0 && abs(lon) <= 180.0 && !(lat == 0.0 && lon == 0.0)

    // ------------------------------------------------------------------ 网络

    private class Step(val nextUrl: String?, val body: String?)

    /** 只走一跳：不自动跟随重定向，手动读取 Location，这样才能拿到中间跳里带的坐标。 */
    private fun fetchOnce(url: String): Step {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        }
        return try {
            when (val code = connection.responseCode) {
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    Step(
                        nextUrl = location?.let { runCatching { URL(URL(url), it).toString() }.getOrNull() },
                        body = null
                    )
                }
                200 -> Step(nextUrl = null, body = runCatching { connection.readBody() }.getOrNull())
                else -> throw IllegalArgumentException("请求 $url 返回 HTTP $code")
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun HttpURLConnection.readBody(): String =
        (inputStream as InputStream).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 兜底：有些链接的坐标只出现在页面源码里。 */
    private fun parseFromBody(body: String): Destination? {
        val match = LONGLAT_IN_BODY.find(body) ?: return null
        val lon = match.groupValues[1].toDoubleOrNull() ?: return null
        val lat = match.groupValues[2].toDoubleOrNull() ?: return null
        val coord = sanitize(lat, lon) ?: return null
        // 页面源码里只有坐标，没有名称，HUD 会用「目的地」兜底显示
        return Destination(
            name = "",
            address = "",
            latitude = coord.first,
            longitude = coord.second,
            poiId = ""
        )
    }

    private val LONGLAT_IN_BODY = Regex("""lnglat=([\d.]+)%?(?:2C|,)([\d.]+)""")
}
