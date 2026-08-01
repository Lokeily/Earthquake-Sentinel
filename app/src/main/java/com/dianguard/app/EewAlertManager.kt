package com.dianguard.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * 预警解析与告警分发（从 EewService 拆分，v1.0.17+）。
 *
 * 职责：EEW 解析、烈度计算、跨源去重、全屏告警/通知栏分发、备用探测源管理。
 * 不涉及 WebSocket 连接——连接管理由 EewConnectionManager 负责。
 *
 * @param service 父 EewService，用于获取 Context 发起 Activity/通知/Broadcast
 */
class EewAlertManager(val service: EewService) {

    // 当前正在全屏告警的物理事件 Key
    @Volatile var activeEventId: String? = null
    @Volatile var activeQuakeKey: String? = null

    // 跨源去重表
    private val recentQuakes = LinkedHashMap<String, Long>()
    private val dedupLock = Any()

    // 数据新鲜度（OkHttp 线程写，主线程读）
    @Volatile var lastDataReceivedMs: Long = 0L
    @Volatile var dataStaleNotified: Boolean = false

    // 备用探测源
    var backupSource: BackupSource? = null
        private set

    // ===================== EEW 解析 =====================

    fun parseEew(raw: String): Eew? {
        val obj = JSONObject(raw)
        if (!obj.has("Latitude") || !obj.has("Longitude")) return null
        val lat = obj.optDouble("Latitude", Double.NaN)
        val lon = obj.optDouble("Longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null

        val mag = obj.optDouble("Magnitude", obj.optDouble("Magunitude", 0.0))
        val depth = obj.optDouble("Depth", 0.0)
        val maxInt = obj.optString("MaxIntensity", "")
        val hypo = obj.optString("HypoCenter", "未知地区")
        val id = obj.optString("ID", "")
        val eventId = obj.optString("EventID", id)
        val reportNum = obj.optInt("ReportNum", 1)
        val originTime = obj.optString("OriginTime", "")
        return Eew(id, eventId, reportNum, originTime, hypo, lat, lon, mag, depth, maxInt)
    }

    fun resolveIntensity(eew: Eew): Double {
        val fromField = parseIntensity(eew.maxIntensity)
        if (fromField > 0) return fromField
        return estimateIntensityFromMagnitude(eew.magnitude)
    }

    fun resolveIntensityStr(eew: Eew): String {
        if (eew.maxIntensity.isNotBlank() && parseIntensity(eew.maxIntensity) > 0) {
            return eew.maxIntensity
        }
        val est = estimateIntensityFromMagnitude(eew.magnitude)
        return "约${"%.0f".format(est)}"
    }

    fun makeQuakeKey(e: Eew): String {
        val lat = (e.latitude * 1000).toLong()
        val lon = (e.longitude * 1000).toLong()
        val mag = (e.magnitude * 10).toLong()
        val ot = e.originTime.ifBlank { e.eventId }
        return "$ot|$lat|$lon|$mag"
    }

    fun cleanupOldQuakes(now: Long) {
        synchronized(dedupLock) {
            val it = recentQuakes.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value > EewService.DEDUP_WINDOW_MS) it.remove()
            }
        }
    }

    // ===================== 核心消息分发 =====================

    fun handleRaw(raw: String, sourceId: String) {
        val eew = parseEew(raw) ?: return

        lastDataReceivedMs = System.currentTimeMillis()
        EewService.patchSourceState(sourceId) { copy(lastDataMs = System.currentTimeMillis()) }
        if (dataStaleNotified) {
            dataStaleNotified = false
            service.connMgr.refreshHeadline()
        }

        val quakeKey = makeQuakeKey(eew)
        val now = System.currentTimeMillis()
        cleanupOldQuakes(now)

        val distKm = haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        val etaSec = AppConfig.estimateSWaveEtaSeconds(distKm)
        val intensityVal = resolveIntensity(eew)
        val intensityStr = resolveIntensityStr(eew)
        val intOk = intensityVal >= AppConfig.minIntensity

        Log.i(
            EewService.TAG,
            "[$sourceId] 收到 EEW: ${eew.hypoCenter} M${eew.magnitude} 烈度$intensityStr " +
                "距参考点${distKm.toInt()}km ETA${etaSec.toInt()}s | 烈度阈值=$intOk key=$quakeKey"
        )

        val seenBefore = synchronized(dedupLock) {
            val seen = recentQuakes.containsKey(quakeKey)
            recentQuakes[quakeKey] = now
            seen
        }

        if (intOk) {
            if (activeEventId == eew.eventId || activeQuakeKey == quakeKey) {
                postRefresh(eew, distKm, etaSec, intensityStr)
            } else if (!seenBefore) {
                activeEventId = eew.eventId
                activeQuakeKey = quakeKey
                triggerAlert(eew, distKm, etaSec, intensityStr)
            } else {
                Log.i(EewService.TAG, "跨源重复事件，跳过新告警: $quakeKey")
            }
        } else if (AppConfig.distantNotify && !seenBefore) {
            postDistantNotify(eew, distKm, etaSec, intensityStr)
        }

        QuakeHistory.record(
            QuakeRecord(
                key = quakeKey, timeMs = now, originTime = eew.originTime,
                place = eew.hypoCenter, magnitude = eew.magnitude, depthKm = eew.depthKm,
                intensity = intensityStr, distanceKm = distKm, etaSec = etaSec,
                sourceName = EEW_SOURCES.firstOrNull { it.id == sourceId }?.name ?: sourceId,
                reportNum = eew.reportNum, triggered = intOk, backup = false
            )
        )
    }

    // ===================== 告警触发 =====================

    private fun triggerAlert(eew: Eew, distKm: Double, etaSec: Double, intensityStr: String) {
        val intent = Intent(service, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EewService.EXTRA_EVENT_ID, eew.eventId)
            putExtra(EewService.EXTRA_MAG, eew.magnitude)
            putExtra(EewService.EXTRA_PLACE, eew.hypoCenter)
            putExtra(EewService.EXTRA_DISTANCE, distKm)
            putExtra(EewService.EXTRA_ETA, etaSec)
            putExtra(EewService.EXTRA_INTENSITY, intensityStr)
            putExtra(EewService.EXTRA_DEPTH, eew.depthKm)
            putExtra(EewService.EXTRA_REPORT_NUM, eew.reportNum)
        }
        service.mainHandler.post {
            try {
                service.startActivity(intent)
            } catch (e: Exception) {
                Log.e(EewService.TAG, "拉起告警页失败，退化为全屏 Intent 通知", e)
                try { postFullScreenAlertNotification(eew, distKm, etaSec, intensityStr, intent) }
                catch (e2: Exception) { Log.e(EewService.TAG, "全屏 Intent 兜底通知同样失败", e2) }
            }
        }
    }

    private fun postFullScreenAlertNotification(
        eew: Eew, distKm: Double, etaSec: Double, intensityStr: String, alertIntent: Intent
    ) {
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(service, 1, alertIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "${eew.hypoCenter} M${eew.magnitude} 预估烈度$intensityStr，" +
                "距参考点约 ${distKm.toInt()}km，S波约 ${etaSec.toInt()}s 后到达"
        val n = NotificationCompat.Builder(service, EewService.CHANNEL_ALERT_ID)
            .setContentTitle("地震预警！请立即避险")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_warning)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        nm.notify(EewService.FALLBACK_ALERT_NOTIFY_ID, n)
    }

    fun postRefresh(eew: Eew, distKm: Double, etaSec: Double, intensityStr: String) {
        val i = Intent(EewService.ACTION_REFRESH).apply {
            putExtra(EewService.EXTRA_EVENT_ID, eew.eventId)
            putExtra(EewService.EXTRA_MAG, eew.magnitude)
            putExtra(EewService.EXTRA_PLACE, eew.hypoCenter)
            putExtra(EewService.EXTRA_DISTANCE, distKm)
            putExtra(EewService.EXTRA_ETA, etaSec)
            putExtra(EewService.EXTRA_INTENSITY, intensityStr)
            putExtra(EewService.EXTRA_DEPTH, eew.depthKm)
            putExtra(EewService.EXTRA_REPORT_NUM, eew.reportNum)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(service).sendBroadcast(i)
    }

    private fun postDistantNotify(eew: Eew, distKm: Double, etaSec: Double, intensityStr: String) {
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "远震/小震：${eew.hypoCenter} M${eew.magnitude} 烈度$intensityStr，" +
                "距参考点约 ${distKm.toInt()}km，S波约 ${etaSec.toInt()}s 后到达"
        val pi = PendingIntent.getActivity(service, 2,
            Intent(service, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(service, EewService.CHANNEL_DISTANT_ID)
            .setContentTitle("地震哨兵 · 远震小震提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_warning)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(EewService.DISTANT_NOTIFY_GROUP)
            .build()
        nm.notify(EewService.DISTANT_NOTIFY_ID_BASE + (eew.eventId.hashCode() and 0xFFFF), n)

        val summary = NotificationCompat.Builder(service, EewService.CHANNEL_DISTANT_ID)
            .setContentTitle("地震哨兵 · 远震小震提醒")
            .setContentText("有新的远震/小震提醒")
            .setSmallIcon(R.drawable.ic_stat_warning)
            .setGroup(EewService.DISTANT_NOTIFY_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        nm.notify(EewService.DISTANT_NOTIFY_SUMMARY_ID, summary)
    }

    // ===================== 备用探测源 =====================

    fun startBackupSource() {
        if (backupSource != null) return
        val bs = BackupSource(
            onEvent = { eew, sourceName -> handleBackupEew(eew, sourceName) },
            onStatus = { note -> EewService.backupNote = note }
        )
        backupSource = bs
        EewService.backupActive = true
        bs.start()
    }

    fun stopBackupSource() {
        backupSource?.stop()
        backupSource = null
        EewService.backupActive = false
        EewService.backupNote = "待命中"
    }

    private fun handleBackupEew(eew: Eew, sourceName: String) {
        val now = System.currentTimeMillis()
        val quakeKey = makeQuakeKey(eew)
        val distKm = haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        val etaSec = AppConfig.estimateSWaveEtaSeconds(distKm)
        val intensityStr = resolveIntensityStr(eew)

        val seenBefore = synchronized(dedupLock) {
            val seen = recentQuakes.containsKey(quakeKey)
            recentQuakes[quakeKey] = now
            seen
        }
        if (seenBefore) return

        Log.w(EewService.TAG, "[备用源:$sourceName] 探测到地震: ${eew.hypoCenter} M${eew.magnitude} 距${distKm.toInt()}km")

        QuakeHistory.record(
            QuakeRecord(
                key = quakeKey, timeMs = now, originTime = eew.originTime,
                place = eew.hypoCenter, magnitude = eew.magnitude, depthKm = eew.depthKm,
                intensity = intensityStr, distanceKm = distKm, etaSec = etaSec,
                sourceName = sourceName, reportNum = eew.reportNum,
                triggered = false, backup = true
            )
        )
        postBackupNotify(eew, distKm, intensityStr, sourceName)
    }

    private fun postBackupNotify(eew: Eew, distKm: Double, intensityStr: String, sourceName: String) {
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "${eew.hypoCenter} M${eew.magnitude} 预估烈度$intensityStr，距参考点约 ${distKm.toInt()}km。\n" +
                "（主预警链路中断期间，由「$sourceName」兜底探测，属震后速报，非实时预警）"
        val pi = PendingIntent.getActivity(service, 3,
            Intent(service, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(service, EewService.CHANNEL_DISTANT_ID)
            .setContentTitle("地震哨兵 · 备用源速报")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_warning)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(EewService.DISTANT_NOTIFY_GROUP)
            .build()
        nm.notify(EewService.DISTANT_NOTIFY_ID_BASE + (eew.eventId.hashCode() and 0xFFFF), n)
    }

    /** 服务停止时的清理 */
    fun destroy() {
        stopBackupSource()
    }
}
