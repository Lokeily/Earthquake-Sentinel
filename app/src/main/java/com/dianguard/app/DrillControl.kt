package com.dianguard.app

import android.util.Log
import org.json.JSONObject

/**
 * 演练控制台的「控制帧 / 状态帧」编解码（v1.4.6，仅 debug 演练包生效）。
 *
 * 与 EEW 报文严格分离：
 *  - 下行 `sentinel_control/set_location`：后台远程修改演示机参考位置；
 *  - 上行 `device_status`：周期性回报参考位置、监听状态、是否正在告警、版本。
 *
 * 这两类帧都不会进入 EewAlertManager.handleRaw，因此不会写历史、不会触发告警。
 * 控制帧带明确 type 标记；旧版 App 即便误收，parseExternalEew 也因缺少
 * 合法经纬度+震级而返回 null，行为安全。
 */
object DrillControl {

    private const val TAG = "DrillControl"

    /**
     * 尝试处理控制帧。
     * @return true = 已被消费，调用方不得再交给 EEW 解析链；false = 普通报文。
     */
    fun handle(raw: String): Boolean {
        if (!raw.contains("sentinel_control")) return false
        return try {
            val o = JSONObject(raw)
            if (o.optString("type") != "sentinel_control") return false
            when (o.optString("cmd")) {
                "set_location" -> {
                    val lat = o.optDouble("lat", Double.NaN)
                    val lon = o.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN() &&
                        lat in -90.0..90.0 && lon in -180.0..180.0) {
                        val name = o.optString("name", "后台参考位置")
                        AppConfig.setDrillLocation(lat, lon, "console", name)
                        Log.i(TAG, "后台下发参考位置: ($lat, $lon) $name")
                    } else {
                        Log.w(TAG, "后台下发坐标非法，已忽略: $lat, $lon")
                    }
                }
                // 预留扩展指令位：已识别但不动作，避免落入 EEW 解析链
                else -> Log.d(TAG, "忽略未知控制指令: ${o.optString("cmd")}")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "控制帧解析失败，已吞掉", e)
            true // 带标记但格式异常也必须吞掉，绝不进入 EEW 解析链
        }
    }

    /** 构造一条设备状态上报帧（由 EewConnectionManager 每 5 秒发送） */
    fun buildStatusJson(): String {
        val activeId = try {
            EewService.instance?.alertMgr?.activeEventId
        } catch (_: Exception) {
            null // lateinit 尚未初始化的极端竞态，退化为「未告警」
        }
        return JSONObject().apply {
            put("type", "device_status")
            put("lat", AppConfig.homeLat)
            put("lon", AppConfig.homeLon)
            put("hasLocation", AppConfig.hasLocation)
            put("serviceEnabled", AppConfig.serviceEnabled)
            put("locationSource", if (AppConfig.drillLocationActive) AppConfig.drillLocationSource else "gps")
            put("alertActive", activeId != null)
            put("lastEventId", activeId ?: "")
            put("version", BuildConfig.VERSION_NAME)
            put("ts", System.currentTimeMillis())
        }.toString()
    }
}
