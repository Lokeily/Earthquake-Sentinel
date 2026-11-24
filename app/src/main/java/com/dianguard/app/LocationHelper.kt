package com.dianguard.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/**
 * 定位与逆地理编码工具（v1.4.3 精度增强版）。
 *
 * ## 取定位（v1.4.3 精度门控）
 * 旧逻辑「首个定位回调立即采用」会导致拿到精度很差的首个网路定位（±数公里），
 * 直接影响震中距与倒计时准确性。现改为**精度门控**：
 *  - 收集窗口（GPS 10s）内持续跟踪最优解；
 *  - 任一解精度 ≤ 35m → 立即交付；
 *  - 窗口到期 → 交付窗口内精度最好的解（没有就按旧超时逻辑走回退 provider）。
 * 回调新增 accuracyM（定位精度，米；-1 表示未知），设置页据此展示「±X 米」。
 *
 * ## 逆地理编码（v1.4.3 精度增强）
 * 三级降级链路（大多数大陆设备无 GMS，Geocoder 常返回 null）：
 *  1) 系统 Geocoder（isPresent 守卫；有 GMS 机型可到街道级）
 *  2) BigDataCloud 免鉴权接口——**新增 localityInfo.administrative 行政区层级解析**，
 *     可提取「省 / 市 / 区县 / 街道」完整链（旧实现只用三个顶层字段，常退化到省市级）
 *  3) 内置省级边界表（最后兜底，至少给出省份）
 *
 * 注意：逆地理编码走网络，全部在后台线程执行，避免阻塞主线程 ANR。
 */
object LocationHelper {

    /** 逆地理编码后台线程（复用单线程池，避免频繁创建/销毁） */
    private val geocodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dianguard-Geocode").also { it.isDaemon = true }
    }

    /** requestSingleUpdate 超时（毫秒），部分手机 GPS 冷启动可能 > 20s */
    private const val LOCATION_TIMEOUT_MS = 30_000L

    /** 精度门控：收集窗口（毫秒）。窗口内持续跟踪最优解，到期交付 */
    private const val BEST_FIX_WINDOW_MS = 10_000L

    /** 精度门控：好解阈值（米）。拿到 ≤35m 的解立即交付，不再等 */
    private const val ACCURACY_GOOD_M = 35f

    interface LocationResult {
        /** 成功取到位置；address 为逆编码得到的中文地址（可能为 null）；accuracyM 为定位精度（米，-1 未知） */
        fun onResult(lat: Double, lon: Double, address: String?, accuracyM: Float = -1f)

        /** 定位失败或不可用 */
        fun onError(msg: String)
    }

    @SuppressLint("MissingPermission")
    fun fetchLocation(context: Context, cb: LocationResult, forceFresh: Boolean = false) {
        val handler = Handler(Looper.getMainLooper())
        // 防止重复回调（Network 先回调再超时触发 GPS 回退导致双重通知）
        var delivered = false
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // 第一步：尝试从所有可用 provider 读取最后一次已知位置（取最新的）。
            // 仅当非强制刷新时启用——用户点「重新获取位置」应跳过缓存、取实时定位。
            if (!forceFresh) {
                val allProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { lm.isProviderEnabled(it) }
                if (allProviders.isNotEmpty()) {
                    var best: Location? = null
                    for (p in allProviders) {
                        val loc = lm.getLastKnownLocation(p)
                        if (loc != null && (best == null || loc.time > best.time)) {
                            best = loc
                        }
                    }
                    if (best != null && System.currentTimeMillis() - best.time < 10 * 60 * 1000L) {
                        delivered = true
                        deliver(context, best.latitude, best.longitude, best.accuracy, cb, handler)
                        return
                    }
                }
            }

            // 第二步：请求实时定位更新
            val gpsAvailable = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val netAvailable = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!gpsAvailable && !netAvailable) {
                cb.onError("未检测到定位信号，请确认已开启 GPS 或网络定位")
                return
            }

            // 优先 GPS（精度高，地震烈度估算依赖震中距，网络定位偏差可达数公里）
            val primaryProvider = if (gpsAvailable) LocationManager.GPS_PROVIDER
            else LocationManager.NETWORK_PROVIDER

            // 精度门控状态：窗口内持续跟踪最优解
            var bestFix: Location? = null

            fun deliverBestIfAny() {
                if (delivered) return
                val b = bestFix ?: return
                delivered = true
                deliver(context, b.latitude, b.longitude, b.accuracy, cb, handler)
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (delivered) return
                    // 跟踪窗口内精度最好的解
                    if (bestFix == null || loc.accuracy < bestFix!!.accuracy) {
                        bestFix = loc
                    }
                    // 好解（≤35m）立即交付，不再等待
                    if (loc.accuracy <= ACCURACY_GOOD_M) {
                        delivered = true
                        deliver(context, loc.latitude, loc.longitude, loc.accuracy, cb, handler)
                        try { lm.removeUpdates(this) } catch (_: Exception) { }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) { }
                override fun onProviderEnabled(p0: String) { }
                override fun onProviderDisabled(p0: String) { }
            }

            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(primaryProvider, listener, Looper.getMainLooper())

            // 精度门控窗口：到期交付窗口内最优解（若有），否则进入回退 provider
            handler.postDelayed({
                try { lm.removeUpdates(listener) } catch (_: Exception) { }
                if (delivered) return@postDelayed
                if (bestFix != null) {
                    deliverBestIfAny()
                    return@postDelayed
                }
                // 主 provider 窗口内无任何解：回退到另一个可用 provider
                val fallbackProvider = if (primaryProvider == LocationManager.GPS_PROVIDER) {
                    if (netAvailable) LocationManager.NETWORK_PROVIDER else null
                } else {
                    if (gpsAvailable) LocationManager.GPS_PROVIDER else null
                }
                if (fallbackProvider == null) {
                    if (!delivered) cb.onError("定位超时，请确保在室外开阔地带并已开启 GPS 或网络定位")
                    return@postDelayed
                }
                val fbListener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (delivered) return
                        delivered = true
                        deliver(context, loc.latitude, loc.longitude, loc.accuracy, cb, handler)
                        try { lm.removeUpdates(this) } catch (_: Exception) { }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) { }
                    override fun onProviderEnabled(p0: String) { }
                    override fun onProviderDisabled(p0: String) { }
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(fallbackProvider, fbListener, Looper.getMainLooper())
                handler.postDelayed({
                    try { lm.removeUpdates(fbListener) } catch (_: Exception) { }
                    if (!delivered) cb.onError("定位超时，请确保在室外开阔地带并已开启 GPS 或网络定位")
                }, LOCATION_TIMEOUT_MS)
            }, BEST_FIX_WINDOW_MS)
        } catch (e: Exception) {
            if (!delivered) cb.onError(e.message ?: "定位失败")
        }
    }

    private fun deliver(
        context: Context,
        lat: Double,
        lon: Double,
        accuracyM: Float,
        cb: LocationResult,
        handler: Handler
    ) {
        geocodeExecutor.execute {
            val addr = geocode(context, lat, lon)
            handler.post { cb.onResult(lat, lon, addr, accuracyM) }
        }
    }

    /** 逆地理编码：返回尽量精确的中文地址（省/市/区/街道），失败返回 null。 */
    fun geocode(context: Context, lat: Double, lon: Double): String? {
        // 1) 系统 Geocoder（含 GMS 的机型，可到街道级）
        val viaSystem = if (Geocoder.isPresent()) try {
            val geocoder = Geocoder(context, Locale.CHINA)
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lon, 1)
            if (!list.isNullOrEmpty()) {
                val a = list[0]
                val parts = mutableListOf<String>()
                val admin = a.adminArea      // 省 / 直辖市
                val city = a.locality        // 市
                val district = a.subLocality // 区 / 县
                val street = a.thoroughfare  // 街道
                val feature = a.featureName  // 具体地点
                admin?.let { if (it.isNotBlank()) parts.add(it) }
                city?.let { if (it.isNotBlank() && it != admin) parts.add(it) }
                district?.let { if (it.isNotBlank() && !parts.contains(it)) parts.add(it) }
                street?.let { if (it.isNotBlank() && !parts.contains(it)) parts.add(it) }
                feature?.let { if (it.isNotBlank() && it != street) parts.add(it) }
                if (parts.isEmpty()) a.getAddressLine(0) else parts.joinToString("")
            } else null
        } catch (_: Exception) {
            null
        } else null

        // 2) BigDataCloud（免鉴权，含行政区层级）→ 3) 省级兜底
        val candidate = viaSystem ?: geocodeNetwork(lat, lon) ?: GeoUtils.provinceOf(lat, lon)
        return candidate?.let { ZhConvert.toSimplified(it) }
    }

    /**
     * BigDataCloud 免鉴权反向地理编码（localityLanguage=zh）。
     * v1.4.3 增强：除顶层三字段外，解析 localityInfo.administrative 行政区层级数组
     * （从省级到街道级依序排列），拼出「省 + 市 + 区县 + 街道/乡镇」完整链——
     * 旧实现常只拿到省市级，用户看到「只有一个省」。
     */
    private fun geocodeNetwork(lat: Double, lon: Double): String? {
        return try {
            val url = URL(
                "https://api.bigdatacloud.net/data/reverse-geocode-client" +
                    "?latitude=$lat&longitude=$lon&localityLanguage=zh"
            )
            val conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(text)

            // 路径 A（增强）：localityInfo.administrative 行政区层级链
            val chain = mutableListOf<String>()
            try {
                val adminArr = obj.getJSONObject("localityInfo").getJSONArray("administrative")
                for (i in 0 until adminArr.length()) {
                    val o = adminArr.optJSONObject(i) ?: continue
                    val name = o.optString("name", "").trim()
                    // administrative[0] 通常是国家级（"中国"），跳过；同名去重
                    if (name.isNotBlank() && name != "中国" && !chain.contains(name)) chain.add(name)
                }
            } catch (_: Exception) { }

            // 路径 B（原有）：顶层字段
            if (chain.isEmpty()) {
                val prov = obj.optString("principalSubdivision", "")
                val city = obj.optString("city", "")
                val locality = obj.optString("locality", "")
                if (prov.isNotBlank()) chain.add(prov)
                if (city.isNotBlank() && city != prov) chain.add(city)
                if (locality.isNotBlank() && locality != city) chain.add(locality)
            }

            // 层级链最多取 4 级（省/市/区/街道），避免拼接出过长地址
            if (chain.isNotEmpty()) chain.takeLast(4).joinToString("") else null
        } catch (_: Exception) {
            null
        }
    }

    /** 公开：离线省级兜底，供 UI 在详细逆编码失败时仍显示省/直辖市（绝不直接显示经纬度） */
    fun provinceFallback(lat: Double, lon: Double): String? = GeoUtils.provinceOf(lat, lon)
}