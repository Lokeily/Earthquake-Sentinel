package com.dianguard.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.LinkedHashMap

/**
 * 预警解析与告警分发（从 EewService 拆分，v1.0.17+）。
 *
 * 职责：EEW 解析、烈度计算、跨源去重、多源融合、全屏告警/通知栏分发、备用探测源管理。
 * 不涉及 WebSocket 连接——连接管理由 EewConnectionManager 负责。
 *
 * v1.3.0 新增多源融合：EewFusion 将不同数据源视为独立"专家"，
 * 在 1.5s 窗口内收集多源报文后加权融合震级，降低单源误报风险。
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

    // 防止 destroy 后 backupSource 被重新启动
    @Volatile private var destroyed = false

    /** 启动后至少已成功触发过一次告警时置 true；用于冷静期判断 */
    private var triggeredAtLeastOnce = false

    /**
     * 开启监听基准时刻的容差（毫秒）：允许发震时刻比基准早最多 1 分钟仍推送，
     * 避免“用户刚点开监听、真实当前震的 EEW 因网络延迟晚到几秒”被误判为旧震而漏报。
     * 历史旧震（数分钟/数小时前）仍会被可靠过滤。
     */
    private val MONITOR_GRACE_MS = 60_000L

    /**
     * 是否应抑制地震相关通知（远震小震提醒 / 备用源速报 / 全屏强震告警）。
     * 满足任一即抑制：
     *  ① 未开启预警监听（serviceEnabled=false）→ 绝不推送任何地震通知；
     *  ② 已开启，但地震发震时刻（originMs）早于「开启监听基准时刻 − 容差」
     *     → 视为开启前已发生的旧震/历史震，不再推送，避免用户被旧消息吓到。
     * 仅发震时刻可解析（originMs>0）且基准已记录时才启用时间过滤；
     * 发震时刻未知时退化为「仅按 serviceEnabled 判定」，不误杀真实当前震。
     */
    private fun suppressQuakeNotification(eew: Eew): Boolean {
        if (!AppConfig.serviceEnabled) return true
        val startMs = AppConfig.monitorStartMs
        if (startMs > 0 && eew.originMs > 0 && eew.originMs < startMs - MONITOR_GRACE_MS) return true
        return false
    }

    // ===== 多源融合临时状态 =====
    private val fusionLock = Any()
    /** 首报元数据缓存：key=quakeKey，融合完成时用于决策 */
    private data class PendingFusionMeta(
        val eew: Eew, val distKm: Double, val etaSec: Double,
        val siteIntensity: Double, val intensityStr: String
    )
    private val pendingFusionMeta = LinkedHashMap<String, PendingFusionMeta>()

    // ===================== EEW 解析 =====================
    // 解析/去重相关纯函数已抽至 Eew.kt 顶层（parseEew / makeQuakeKey），此处仅做告警分发。

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
        // 解析回退链：CENC 标准格式（含 CENC/四川/重庆/中国台湾 Wolfx 镜像）→ 第三方聚合
        //   （FAN/BeeCLD 信封）→ Project Podris。各解析器按各自字段特征返回 null 或 Eew，
        // 互不干扰；对既有 Wolfx/ICL 报文无任何影响。
        val parsed = parseEew(raw) ?: parseExternalEew(raw) ?: parsePodrisEew(raw) ?: return
        handleRawEew(parsed, sourceId)
    }

    /** ICL HTTP 轮询入口（eew 已解析好） */
    fun handleRawIcl(eew: Eew, sourceId: String) {
        handleRawEew(eew, sourceId)
    }

    /** 统一处理 */
    private fun handleRawEew(rawEew: Eew, sourceId: String) {
        val eew = rawEew.copy(hypoCenter = ZhConvert.toSimplified(rawEew.hypoCenter))

        val startupGrace = triggeredAtLeastOnce || System.currentTimeMillis() - EewService.serviceStartedMs > 15_000L

        val quakeKey = makeQuakeKey(eew)
        val now = System.currentTimeMillis()
        cleanupOldQuakes(now)

        // R5 修复：未设定参考位置时（hasLocation=false），无法计算用户所在地烈度，
        // 一律不触发全屏/远震告警（避免用 0,0 坐标产生错误估算与误报），仅记录历史。
        // 首页已引导用户在开启服务前设置位置；此处作为服务端兜底防御。
        if (!AppConfig.hasLocation) {
            Log.w(EewService.TAG, "[$sourceId] 未设置参考位置，跳过告警判定仅记录: ${eew.hypoCenter} M${eew.magnitude}")
            QuakeHistory.record(
                QuakeRecord(
                    key = quakeKey, timeMs = now, originTime = eew.originTime,
                    place = eew.hypoCenter, magnitude = eew.magnitude, depthKm = eew.depthKm,
                    intensity = "-", distanceKm = 0.0, etaSec = 0.0,
                    sourceName = EEW_SOURCES.firstOrNull { it.id == sourceId }?.name
                        ?: SOURCE_DISPLAY_NAMES[sourceId] ?: sourceId,
                    reportNum = eew.reportNum, triggered = false, backup = false
                )
            )
            notifyHistoryChanged()
            return
        }

        val distKm = haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        val etaSec = AppConfig.estimateSWaveEtaSeconds(distKm, eew.depthKm)
        val siteIntensity = estimateSiteIntensity(eew.magnitude, distKm, eew.depthKm,
            eew.latitude, eew.longitude)
        val intensityStr = "%.1f".format(siteIntensity)
        val alertOk = siteIntensity >= AppConfig.minIntensity

        Log.i(
            EewService.TAG,
            "[$sourceId] 收到 EEW: ${eew.hypoCenter} M${eew.magnitude} 本地烈度$intensityStr " +
                "距参考点${distKm.toInt()}km ETA${etaSec.toInt()}s | 烈度阈值=$alertOk key=$quakeKey"
        )

        val seenBefore = synchronized(dedupLock) {
            val seen = recentQuakes.containsKey(quakeKey)
            recentQuakes[quakeKey] = now
            seen
        }

        if (alertOk) {
            if (activeEventId == eew.eventId || activeQuakeKey == quakeKey) {
                // 已触发告警的同事件后续报：直接刷新
                postRefresh(eew, distKm, etaSec, intensityStr)
            } else if (!seenBefore && startupGrace) {
                // 新事件首报：送入多源融合引擎
                // 缓存首报元数据，等融合完成后再决策
                pendingFusionMeta[quakeKey] = PendingFusionMeta(eew, distKm, etaSec, siteIntensity, intensityStr)
                val fusionResult = EewFusion.ingest(eew, sourceId, quakeKey)
                if (fusionResult != null) {
                    // 已有足够源数据，立即融合决策
                    onFusionComplete(quakeKey, fusionResult, seenBefore)
                } else {
                    // 融合窗口内等待更多源，调度定时强制输出
                    service.mainHandler.postDelayed({ forceFlushFusion(quakeKey) }, 1600L)
                    Log.d(EewService.TAG, "融合等待: $quakeKey 第1源=$sourceId")
                }
            } else {
                // 后续到达的其他源同事件报文 → 也在融合窗口内输入
                if (pendingFusionMeta.containsKey(quakeKey)) {
                    val fusionResult = EewFusion.ingest(eew, sourceId, quakeKey)
                    if (fusionResult != null) {
                        onFusionComplete(quakeKey, fusionResult, seenBefore)
                    }
                } else {
                    Log.i(EewService.TAG, "跨源重复事件，跳过新告警: $quakeKey")
                }
            }
        } else if (!seenBefore) {
            postDistantNotify(eew, distKm, etaSec, intensityStr)
        }

        // 无论是否触发告警，都记录到历史（震级用原始值，非融合值）
        QuakeHistory.record(
            QuakeRecord(
                key = quakeKey, timeMs = now, originTime = eew.originTime,
                place = eew.hypoCenter, magnitude = eew.magnitude, depthKm = eew.depthKm,
                intensity = intensityStr, distanceKm = distKm, etaSec = etaSec,
                sourceName = EEW_SOURCES.firstOrNull { it.id == sourceId }?.name
                    ?: SOURCE_DISPLAY_NAMES[sourceId] ?: sourceId,
                reportNum = eew.reportNum, triggered = alertOk, backup = false
            )
        )
        notifyHistoryChanged()
    }

    /** 融合窗口到期后强制输出结果（由 EewService.mainHandler 定时调用） */
    private fun forceFlushFusion(quakeKey: String) {
        val meta = pendingFusionMeta[quakeKey] ?: return
        if (activeEventId == meta.eew.eventId || activeQuakeKey == quakeKey) {
            // 已处理过（例如窗口内已融合触发）：清理缓存后返回，避免条目残留
            pendingFusionMeta.remove(quakeKey)
            return
        }
        val result = EewFusion.forceFlush(quakeKey)
        if (result == null) {
            // 防御：pending 已被 cleanupStale 清理或已融合输出——移除本地元数据，防止泄漏
            Log.w(EewService.TAG, "融合强制输出无结果，清理等待项: $quakeKey")
            pendingFusionMeta.remove(quakeKey)
            return
        }
        onFusionComplete(quakeKey, result, false)
    }

    /** 融合完成 → 用融合后震级 + 最优震中坐标重新判定并决策触发 */
    private fun onFusionComplete(quakeKey: String, result: FusionResult, seenBefore: Boolean) {
        val meta = pendingFusionMeta.remove(quakeKey) ?: return

        // 使用融合引擎选优的震中坐标（非平均！），确保分区查表物理正确
        val fusedSiteIntensity = estimateSiteIntensity(result.magnitude, meta.distKm, meta.eew.depthKm,
            result.fusedLat, result.fusedLon)
        // 无效烈度直接跳过（M<3 或 R<0 等非法输入）
        if (fusedSiteIntensity < 0) {
            Log.w(EewService.TAG, "融合决策无效: M${"%.1f".format(result.magnitude)} R${meta.distKm.toInt()}km → 跳过")
            return
        }
        val fusedIntensityStr = "%.1f".format(fusedSiteIntensity)
        val fusedAlertOk = fusedSiteIntensity >= AppConfig.minIntensity

        // 首报限制：仅1源初报时记录警告日志
        if (result.isPreliminary && fusedAlertOk) {
            Log.w(EewService.TAG, "初报告警（单源）：M${"%.1f".format(result.magnitude)} 待后续修正")
        }

        Log.i(EewService.TAG, "融合决策 v${result.version}: $quakeKey M${"%.1f".format(result.magnitude)} " +
                "融合烈度=$fusedIntensityStr 震中(${"%.2f".format(result.fusedLat)},${"%.2f".format(result.fusedLon)}) " +
                "置信度=${"%.0f".format(result.confidence * 100)}% 首报=${result.isPreliminary} 判定=$fusedAlertOk")

        if (fusedAlertOk && !seenBefore) {
            triggeredAtLeastOnce = true
            synchronized(this) {
                activeEventId = meta.eew.eventId
                activeQuakeKey = quakeKey
            }
            val fusedEew = meta.eew.copy(
                magnitude = result.magnitude,
                latitude = result.fusedLat,
                longitude = result.fusedLon
            )
            triggerAlert(fusedEew, meta.distKm, meta.etaSec, fusedIntensityStr, "多源融合(${result.sourceCount}源)")
        } else if (!fusedAlertOk && !seenBefore) {
            postDistantNotify(meta.eew, meta.distKm, meta.etaSec, fusedIntensityStr)
        }

        // 更新历史记录中的震级为融合值（如果融合震级与原始不同）
        if (kotlin.math.abs(result.magnitude - meta.eew.magnitude) > 0.1) {
            QuakeHistory.record(
                QuakeRecord(
                    key = quakeKey, timeMs = System.currentTimeMillis(),
                    originTime = meta.eew.originTime,
                    place = meta.eew.hypoCenter, magnitude = result.magnitude,
                    depthKm = meta.eew.depthKm, intensity = fusedIntensityStr,
                    distanceKm = meta.distKm, etaSec = meta.etaSec,
                    sourceName = "多源融合(${result.sourceCount}源)",
                    reportNum = meta.eew.reportNum, triggered = fusedAlertOk, backup = false
                )
            )
        }
    }

    // ===================== 告警触发 =====================

    private fun triggerAlert(eew: Eew, distKm: Double, etaSec: Double, intensityStr: String, sourceName: String = "") {
        // 未开启监听 / 开启前旧震 → 不拉起全屏告警（仍会写入历史，见 handleRawEew）
        if (suppressQuakeNotification(eew)) {
            Log.i(EewService.TAG, "[告警抑制] 跳过全屏告警: ${eew.hypoCenter} M${eew.magnitude} " +
                "serviceEnabled=${AppConfig.serviceEnabled} originMs=${eew.originMs} startMs=${AppConfig.monitorStartMs}")
            return
        }
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
            putExtra(EewService.EXTRA_SOURCE, sourceName)
            putExtra(EewService.EXTRA_TIME, eew.originMs)
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
        val text = "${eew.hypoCenter} ${"%.1f级地震".format(eew.magnitude)} 预估烈度$intensityStr，" +
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
        // 未开启监听 / 开启前旧震 → 不推送远震小震提醒
        if (suppressQuakeNotification(eew)) {
            Log.i(EewService.TAG, "[告警抑制] 跳过远震小震提醒: ${eew.hypoCenter} M${eew.magnitude} " +
                "serviceEnabled=${AppConfig.serviceEnabled} originMs=${eew.originMs} startMs=${AppConfig.monitorStartMs}")
            return
        }
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "${eew.hypoCenter} ${"%.1f级地震".format(eew.magnitude)} 预估烈度$intensityStr，" +
                "距参考点约 ${distKm.toInt()}km，S波约 ${etaSec.toInt()}s 后到达"
        val pi = PendingIntent.getActivity(service, 2,
            Intent(service, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
        // 用固定通知 ID：新地震自动覆盖旧通知，始终只显示最新一条
        val n = NotificationCompat.Builder(service, EewService.CHANNEL_DISTANT_ID)
            .setContentTitle("地震哨兵 · 远震小震提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_warning)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        nm.notify(EewService.DISTANT_NOTIFY_ID, n)
    }

    // ===================== 备用探测源 =====================

    fun startBackupSource() {
        if (destroyed || backupSource != null) return
        val bs = BackupSource(
            onEvent = { eew, sourceName -> handleBackupEew(eew, sourceName) },
            onStatus = { note -> EewService.updateState { copy(backupNote = note) } }
        )
        backupSource = bs
        EewService.updateState { copy(backupActive = true) }
        bs.start()
    }

    fun stopBackupSource() {
        backupSource?.stop()
        backupSource = null
        EewService.updateState { copy(backupActive = false, backupNote = "待命中") }
    }

    private fun handleBackupEew(eew: Eew, sourceName: String) {
        val now = System.currentTimeMillis()
        val quakeKey = makeQuakeKey(eew)
        // R5：未设参考位置时备用源同样不计算距离/烈度，仅记录（防御性，正常流程首页已阻断未设位置开启服务）
        if (!AppConfig.hasLocation) {
            QuakeHistory.record(
                QuakeRecord(
                    key = quakeKey, timeMs = now, originTime = eew.originTime,
                    place = eew.hypoCenter, magnitude = eew.magnitude, depthKm = eew.depthKm,
                    intensity = "-", distanceKm = 0.0, etaSec = 0.0,
                    sourceName = sourceName, reportNum = eew.reportNum,
                    triggered = false, backup = true
                )
            )
            notifyHistoryChanged()
            return
        }
        val distKm = haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        val etaSec = AppConfig.estimateSWaveEtaSeconds(distKm, eew.depthKm)
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
        notifyHistoryChanged()
        postBackupNotify(eew, distKm, intensityStr, sourceName)
    }

    private fun postBackupNotify(eew: Eew, distKm: Double, intensityStr: String, sourceName: String) {
        // 未开启监听 / 开启前旧震 → 不推送备用源速报
        if (suppressQuakeNotification(eew)) {
            Log.i(EewService.TAG, "[告警抑制] 跳过备用源速报: ${eew.hypoCenter} M${eew.magnitude} ($sourceName) " +
                "serviceEnabled=${AppConfig.serviceEnabled} originMs=${eew.originMs} startMs=${AppConfig.monitorStartMs}")
            return
        }
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
            .build()
        nm.notify(EewService.DISTANT_NOTIFY_ID, n)
    }

    /** 服务停止时的清理 */
    fun destroy() {
        destroyed = true
        stopBackupSource()
    }

    /** 通知 HistoryFragment 刷新列表 */
    private fun notifyHistoryChanged() {
        try {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(service)
                .sendBroadcast(Intent(EewService.ACTION_HISTORY_CHANGED))
        } catch (_: Exception) { }
    }
}
