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
 * 定位与逆地理编码工具。
 *
 * - [fetchLocation]：优先用 GPS、回落到网络定位，取到当前经纬度后回调；
 * - [geocode]：把经纬度逆编码为简洁的中文地址（省 + 市 + 区 + 街道 + 具体地标），
 *   用于设置页"只读"地展示当前位置（如"云南省普洱市思茅区…"）。
 *
 * 注意：逆地理编码走网络，放到后台线程执行，避免阻塞主线程 ANR。
 *
 * v1.1.x 修复（问题2）：
 * - getLastKnownLocation 同时尝试 GPS 和 Network 两个 provider，取最新的一条；
 * - requestSingleUpdate 优先使用 Network（出结果快），GPS 作为兜底；
 * - 超时从 15 秒延长到 30 秒（部分手机 GPS 冷启动 > 20 秒）；
 * - 超时后检查是否回退到 Network provider 重试，而非静默丢弃；
 * - 所有路径最终都会回调 onResult 或 onError，确保用户看到反馈。
 */
object LocationHelper {

    /**
     * 修复 #18：逆地理编码原本每次定位都 new 一个 Thread，无线程池。
     * 改为复用的单线程池（守护线程），避免频繁定位时线程无谓创建/销毁。
     */
    private val geocodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Dianguard-Geocode").also { it.isDaemon = true }
    }

    /** requestSingleUpdate 超时（毫秒），部分手机 GPS 冷启动可能 > 20s */
    private const val LOCATION_TIMEOUT_MS = 30_000L

    interface LocationResult {
        /** 成功取到位置；address 为逆编码得到的中文地址，可能为 null（如离线） */
        fun onResult(lat: Double, lon: Double, address: String?)

        /** 定位失败或不可用 */
        fun onError(msg: String)
    }

    @SuppressLint("MissingPermission")
    fun fetchLocation(context: Context, cb: LocationResult, forceFresh: Boolean = false) {
        val handler = Handler(Looper.getMainLooper())
        // 防止重复回调（v1.1.1 修复：Network 先回调再超时触发 GPS 回退导致双重通知）
        var delivered = false
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // 第一步：尝试从所有可用 provider 读取最后一次已知位置（取最新的）。
            // 仅当非强制刷新时启用——用户点“重新获取位置”应跳过缓存、取实时定位，
            // 否则永远返回同一缓存点，表现为“点了按钮却没更新”。
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
                        deliver(context, best.latitude, best.longitude, cb, handler)
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

            // 优先 GPS（精度高，地震烈度估算依赖震中距，网络定位偏差可达数公里）；
            // 仅在 GPS 不可用时回退网络。超时后再反向回退另一个可用 provider。
            val primaryProvider = if (gpsAvailable) LocationManager.GPS_PROVIDER
            else LocationManager.NETWORK_PROVIDER

            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (delivered) return
                    delivered = true
                    deliver(context, loc.latitude, loc.longitude, cb, handler)
                    try { lm.removeUpdates(this) } catch (_: Exception) { }
                }

                override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) { }
                override fun onProviderEnabled(p0: String) { }
                override fun onProviderDisabled(p0: String) { }
            }

            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(primaryProvider, listener, Looper.getMainLooper())

            handler.postDelayed({
                try { lm.removeUpdates(listener) } catch (_: Exception) { }
                if (delivered) return@postDelayed
                // 主 provider 超时：回退到另一个可用 provider（GPS 主用则回退网络，反之亦然）
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
                        deliver(context, loc.latitude, loc.longitude, cb, handler)
                        try { lm.removeUpdates(this) } catch (_: Exception) { }
                    }

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
            }, LOCATION_TIMEOUT_MS)
        } catch (e: Exception) {
            if (!delivered) cb.onError(e.message ?: "定位失败")
        }
    }

    private fun deliver(
        context: Context,
        lat: Double,
        lon: Double,
        cb: LocationResult,
        handler: Handler
    ) {
        geocodeExecutor.execute {
            val addr = geocode(context, lat, lon)
            handler.post { cb.onResult(lat, lon, addr) }
        }
    }

    /** 逆地理编码：返回简洁的中文地址，失败返回 null。
     *
     * 降级链路（在中国大陆大多数设备没有 GMS、系统 Geocoder 必然返回 null 的情况下仍能给出地名）：
     * 1) 系统 Geocoder（含 GMS 的机型）。
     * 2) 免密钥的网络逆地理编码 BigDataCloud（中文）。
     * 3) 内置省级边界表（纯离线兜底，至少给出省份）。
     *
     * 这样「参考位置」与「模拟震中」才能显示「四川省宜宾市」而不是经纬度。
     */
    fun geocode(context: Context, lat: Double, lon: Double): String? {
        // 1) 系统 Geocoder
        val viaSystem = try {
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
                district?.let { if (it.isNotBlank()) parts.add(it) }
                street?.let { if (it.isNotBlank()) parts.add(it) }
                feature?.let { if (it.isNotBlank() && it != street) parts.add(it) }
                if (parts.isEmpty()) a.getAddressLine(0) else parts.joinToString("")
            } else null
        } catch (e: Exception) {
            null
        }
        // 繁体 → 简体（BigDataCloud 对港澳台地名返回繁体，需统一为简体中文）
        val candidate = viaSystem ?: geocodeNetwork(lat, lon) ?: GeoUtils.provinceOf(lat, lon)
        return candidate?.let { ZhConvert.toSimplified(it) }
    }

    /** 免密钥反向地理编码：BigDataCloud（localityLanguage=zh） */
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
            val prov = obj.optString("principalSubdivision", "")
            val city = obj.optString("city", "")
            val locality = obj.optString("locality", "")
            val sb = StringBuilder()
            if (prov.isNotBlank()) sb.append(prov)
            if (city.isNotBlank() && city != prov) sb.append(city)
            if (locality.isNotBlank() && locality != city) sb.append(locality)
            if (sb.isNotEmpty()) sb.toString() else null
        } catch (_: Exception) {
            null
        }
    }

    /** 公开：离线省级兜底，供 UI 在详细逆编码失败时仍显示省/直辖市（绝不直接显示经纬度） */
    fun provinceFallback(lat: Double, lon: Double): String? = GeoUtils.provinceOf(lat, lon)
}
