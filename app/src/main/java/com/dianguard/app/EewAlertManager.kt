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
 * 职责：EEW 解析、烈度计算、跨源去重、全屏告警/通知栏分发、备用探测源管理。
 * 不涉及 WebSocket 连接——连接管理由 EewConnectionManager 负责。
 *
 * ===== 第一报研判策略 =====
 * 第一报抢时间，不搞复杂融合：
 *   1. 谁先发第一报（reportNum=1），震级 ≥ 3.0，立即可告警——不等待其他源，不搞融合窗口；
 *   2. 其他源的「第一报」随后到达的，视为对已触发告警的修正刷新（postRefresh）；
 *   3. 第一报低于阈值 → 只记录历史，等待后续修正报。
 *
 * ===== 第二/三报去重策略 =====
 *   1. 第二报（reportNum=2）：谁先推过来且内容权威，就接谁的"第二报"；
 *   2. 阻止其他源的"第二报"——同一地震同一报数，只接受第一个到达的源，其余丢弃；
 *   3. 第三报同上。
 *
 * ===== 后续报刷新抑制 =====
 * 全屏已触发后，后续修正报必须满足以下任一条件才广播给 AlertActivity：
 *   - 震级变化 ≥ 0.3 级
 *   - 本地烈度变化 ≥ 0.5 度
 *   - ETA 变化 > 3 秒
 * 否则仅记录历史，不打断倒计时和语音播报。
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
    /** 同一官方来源的 eventId 永久映射到首次 canonical key，修正震级/坐标后仍归同一事件。 */
    private val officialEventKeys = LinkedHashMap<String, String>()
    /** 同一官方事件已见的最高报数，拒绝网络重连带回的旧报倒灌。 */
    private val highestReportNums = LinkedHashMap<String, Int>()
    private val latestReportSignatures = LinkedHashMap<String, String>()
    /** 最近一次广播给 AlertActivity 的刷新参数，用于过滤无意义微调。 */
    private val lastRefreshParams = LinkedHashMap<String, String>()
    private data class PhysicalEvent(
        val key: String,
        val originMs: Long,
        val lat: Double,
        val lon: Double,
        var lastSeenMs: Long
    )
    /** 跨机构相关事件归并：发震时刻相近且震中相距不超过 40km 视为同一物理地震（用于去重与修正报联动） */
    private val physicalEvents = LinkedHashMap<String, PhysicalEvent>()

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
     * 第二报/第三报源去重：同一地震同一报数只接受第一个到达的源。
     * key = "quakeKey|reportNum", value = "sourceId"（被接受的源）。
     * 忽略第一报（reportNum=1）——所有源的第一报都应被接受，第一个触发告警，其余视为更新。
     */
    private val acceptedReportSources = LinkedHashMap<String, String>()

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
            val physicalIt = physicalEvents.entries.iterator()
            while (physicalIt.hasNext()) {
                if (now - physicalIt.next().value.lastSeenMs > EewService.DEDUP_WINDOW_MS) physicalIt.remove()
            }
            val liveKeys = physicalEvents.keys
            val officialIt = officialEventKeys.entries.iterator()
            while (officialIt.hasNext()) {
                val entry = officialIt.next()
                if (entry.value !in liveKeys) {
                    highestReportNums.remove(entry.key)
                    latestReportSignatures.remove(entry.key)
                    officialIt.remove()
                }
            }
            // 清理已过期事件的“已接受报数来源”记录
            val acceptedIt = acceptedReportSources.entries.iterator()
            while (acceptedIt.hasNext()) {
                val (key, _) = acceptedIt.next()
                val quakeKey = key.substringBefore('|')
                if (quakeKey !in liveKeys) acceptedIt.remove()
            }
        }
    }

    private fun acceptReportOrder(eew: Eew, sourceId: String): Boolean {
        if (eew.eventId.isBlank() || eew.reportNum <= 0) return true
        val id = "$sourceId|${eew.eventId}"
        synchronized(dedupLock) {
            val highest = highestReportNums[id] ?: 0
            if (eew.reportNum < highest) {
                Log.w(EewService.TAG, "忽略倒序旧报: $id #${eew.reportNum} < 已见#$highest")
                return false
            }
            val signature = "${eew.reportNum}|${eew.magnitude}|${eew.depthKm}|${eew.latitude}|${eew.longitude}|${eew.maxIntensity}"
            if (eew.reportNum == highest && latestReportSignatures[id] == signature) {
                Log.d(EewService.TAG, "忽略完全重复报文: $id #${eew.reportNum}")
                return false
            }
            if (eew.reportNum > highest) highestReportNums[id] = eew.reportNum
            latestReportSignatures[id] = signature
            return true
        }
    }

    /** 记录某事件第N报已被某源接受。 */
    private fun markReportAccepted(quakeKey: String, reportNum: Int, sourceId: String) {
        synchronized(dedupLock) {
            acceptedReportSources["$quakeKey|$reportNum"] = sourceId
        }
    }

    /** 是否已被接受过（同报数其他源应被阻止）。 */
    private fun isReportAccepted(quakeKey: String, reportNum: Int, sourceId: String): Boolean {
        if (reportNum <= 1) return false
        synchronized(dedupLock) {
            val accepted = acceptedReportSources["$quakeKey|$reportNum"]
            return accepted != null && accepted != sourceId
        }
    }

    /** 返回某报数已被哪个源接受（无则 null）。 */
    private fun acceptedSourceFor(quakeKey: String, reportNum: Int): String? {
        synchronized(dedupLock) {
            return acceptedReportSources["$quakeKey|$reportNum"]
        }
    }

    private fun cleanUpReportAcceptance() {
        synchronized(dedupLock) {
            val it = acceptedReportSources.entries.iterator()
            while (it.hasNext()) {
                val (key, _) = it.next()
                // key = "quakeKey|reportNum"，去掉报数后缀后即为 quakeKey
                val quakeKey = key.substringBefore('|')
                if (quakeKey !in physicalEvents.keys) it.remove()
            }
        }
    }

    private fun canonicalQuakeKey(eew: Eew, sourceId: String, now: Long): String {
        synchronized(dedupLock) {
            val officialId = if (eew.eventId.isNotBlank()) "$sourceId|${eew.eventId}" else ""
            if (officialId.isNotBlank()) {
                officialEventKeys[officialId]?.let { key ->
                    physicalEvents[key]?.lastSeenMs = now
                    return key
                }
            }
            val matched = physicalEvents.values
                .filter { event ->
                    val timeClose = if (event.originMs > 0L && eew.originMs > 0L) {
                        kotlin.math.abs(event.originMs - eew.originMs) <= 90_000L
                    } else {
                        now - event.lastSeenMs <= 90_000L
                    }
                    timeClose && haversineKm(event.lat, event.lon, eew.latitude, eew.longitude) <= 40.0
                }
                .minByOrNull { haversineKm(it.lat, it.lon, eew.latitude, eew.longitude) }
            val key = matched?.key ?: makeQuakeKey(eew)
            if (matched == null) {
                physicalEvents[key] = PhysicalEvent(key, eew.originMs, eew.latitude, eew.longitude, now)
            } else {
                matched.lastSeenMs = now
            }
            if (officialId.isNotBlank()) officialEventKeys[officialId] = key
            return key
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

    /**
     * Wolfx HTTP 降级轮询入口（eew 已解析好）。
     * sourceId 与 WS 相同（cenc/sc/cq/cwa）：同源同格式，共用全部去重、
     * 事件聚类、逐报快照与研判逻辑，回放/历史记录天然一致。
     */
    fun handleRawHttp(eew: Eew, sourceId: String) {
        handleRawEew(eew, sourceId)
    }

    /** 统一处理 */
    private fun handleRawEew(rawEew: Eew, sourceId: String) {
        val eew = rawEew.copy(hypoCenter = ZhConvert.toSimplified(rawEew.hypoCenter))
        if (!acceptReportOrder(eew, sourceId)) return

        // 启动冷静期：服务刚启动的 15s 内，ICL 轮询会带回一批历史事件，
        // 冷静期用于拦截这批旧震。但 START_STICKY 自动重启后 15s 内完全可能
        // 收到「正在发生」的地震首报——一律丢弃会漏报。
        // 故增加新鲜度豁免：只要发震时刻距服务启动不足 2 分钟（或时刻未知，
        // WebSocket 源为纯推送、收到即新事件），就放行进入告警判定；
        // 真正的“开启监听前的旧震”仍由 suppressQuakeNotification 按基准时刻过滤。
        val eventFresh = eew.originMs <= 0L ||
            eew.originMs >= EewService.serviceStartedMs - 120_000L
        val startupGrace = triggeredAtLeastOnce ||
            System.currentTimeMillis() - EewService.serviceStartedMs > 15_000L ||
            eventFresh

        val now = System.currentTimeMillis()
        val quakeKey = canonicalQuakeKey(eew, sourceId, now)
        val sourceName = EEW_SOURCES.firstOrNull { it.id == sourceId }?.name
            ?: SOURCE_DISPLAY_NAMES[sourceId] ?: sourceId
        val reportSnapshot = QuakeReportSnapshot(
            sourceId = sourceId,
            eventId = eew.eventId,
            reportNum = eew.reportNum,
            receivedMs = now,
            originTime = eew.originTime,
            place = eew.hypoCenter,
            magnitude = eew.magnitude,
            depthKm = eew.depthKm,
            epiLat = eew.latitude,
            epiLon = eew.longitude,
            sourceName = sourceName
        )
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
                    sourceName = sourceName,
                    reportNum = eew.reportNum, triggered = false, backup = false,
                    epiLat = eew.latitude, epiLon = eew.longitude,
                    eventId = eew.eventId, sourceId = sourceId,
                    reports = listOf(reportSnapshot)
                )
            )
            notifyHistoryChanged()
            return
        }

        val distKm = haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        // 时钟校准（v1.4.10）：后台帧带 serverTime 时用「手机时钟 + offset」= 服务端时间轴
        // 计算已流逝时间，与控制台大屏的倒计时同值（手机时钟漂移不再造成偏差）。
        // 官方源 clockOffsetMs=0，保持原行为。
        val etaSec = remainingSWaveEtaSeconds(
            distKm, eew.depthKm, eew.originMs, now + eew.clockOffsetMs
        )
        val rawSiteIntensity = estimateSiteIntensity(eew.magnitude, distKm, eew.depthKm,
            eew.latitude, eew.longitude)
        // 展示值与判定值同源：烈度四舍五入到 0.1 后再参与阈值判定与等级划分，
        // 杜绝"显示 7.0 度却按 6.95 判为橙色"的展示/等级矛盾（对齐 ICL / GB/T 标准）。
        // 负值（M<3 无效哨兵）钳制为 0.0，避免历史记录出现"-1.0 度"。
        val siteIntensity = Math.round(rawSiteIntensity.coerceAtLeast(0.0) * 10.0) / 10.0
        val intensityStr = "%.1f".format(siteIntensity)
        val alertTriggered = AppConfig.alertTriggered(siteIntensity)

        Log.i(
            EewService.TAG,
            "[$sourceId] 收到 EEW: ${eew.hypoCenter} M${eew.magnitude} 本地烈度$intensityStr " +
                "距参考点${distKm.toInt()}km ETA${etaSec.toInt()}s | 烈度触发判定(本地烈度≥${AppConfig.currentMinIntensity()})=$alertTriggered key=$quakeKey"
        )

        val seenBefore = synchronized(dedupLock) {
            val seen = recentQuakes.containsKey(quakeKey)
            recentQuakes[quakeKey] = now
            seen
        }

        // ===== 统一研判（v1.4.3 简化，行为与原四分支等价 + 修复两处缺陷） =====
        // 原实现问题：
        //  ① 分支3（reportNum>1 && !seenBefore && startupGrace）与分支2 条件重叠，
        //     !seenBefore && startupGrace 时分支2 必然先命中 → 分支3 永不执行（死代码）；
        //  ② 分支5 的「reportNum>1 && !isReportAccepted → return」条件写反：
        //     isReportAccepted 语义是「被其他源接受」，取反后「未被任何源接受」反而直接
        //     return，导致「低于阈值但报数是新的」修正报被静默丢弃——既不触发也不写历史。
        // 收敛为 4 个互斥分支（各词义见注释），消灭死代码与反向去重，修正报无论阈值
        // 高低都可靠写入历史。
        if (activeEventId == eew.eventId || activeQuakeKey == quakeKey) {
            // 已触发告警后，所有后续修正报都必须刷新，即便震级/烈度被下修到阈值以下。
            // 否则页面会永久停留在旧的高震级。跨机构 eventId 统一改写为当前告警 ID。
            postRefresh(eew.copy(eventId = activeEventId ?: eew.eventId), distKm, etaSec, intensityStr)
        } else if (eew.reportNum > 1 && isReportAccepted(quakeKey, eew.reportNum, sourceId)) {
            // 第二/三报且该报数已被其他源接受 → 跨源同报数去重：不重复触发、不重复记录历史
            val acceptedSource = acceptedSourceFor(quakeKey, eew.reportNum)
            Log.i(EewService.TAG, "[去重] 阻止第${eew.reportNum}报再次触发: 已接受来自「$acceptedSource」的同报数，忽略「$sourceId」的${eew.reportNum}报")
            return
        } else if (alertTriggered) {
            // 烈度达标：首报永不拦截；修正报仅当该报数尚未被其他源接受（已在上一支排除）。
            // 一网打尽原「首报快通道 / 修正报升格 / 跨源首报 / 防漏报兜底」四类场景，
            // 由 triggerAlert 内部的 suppressQuakeNotification 统一拦截「未开启/开启前旧震」。
            val reason = if (eew.reportNum <= 1) "首报" else "修正报"
            Log.i(
                EewService.TAG,
                "[${reason}触发] ${eew.hypoCenter} M${"%.1f".format(eew.magnitude)} 烈度$intensityStr 达标 " +
                    "→ 立即告警（source=$sourceId report=${eew.reportNum}）"
            )
            val triggered = triggerAlert(eew, distKm, etaSec, intensityStr, sourceName)
            if (triggered) {
                triggeredAtLeastOnce = true
                synchronized(this) {
                    activeEventId = eew.eventId
                    activeQuakeKey = quakeKey
                }
                markReportAccepted(quakeKey, eew.reportNum, sourceId)
            }
        } else if (!seenBefore && startupGrace) {
            // 首次见 + 启动放行 + 烈度不达标 → 仅发「远震小震提醒」通知，不触发全屏告警
            Log.i(EewService.TAG, "[首报] 烈度$intensityStr < ${AppConfig.currentMinIntensity()}，低于阈值，仅记录")
            postDistantNotify(eew, distKm, etaSec, intensityStr)
        } else {
            // 已处理过的事件 / 启动冷静期拦截的旧震 / 未达标重复 → 仅记录历史，不再告警
            Log.i(EewService.TAG, "跨源重复事件，跳过新告警: $quakeKey")
        }

        // 无论是否触发告警，都记录到历史（震级用原始值，非融合值）
        QuakeHistory.record(
            QuakeRecord(
                key = quakeKey, timeMs = now, originTime = eew.originTime,
                place = eew.hypoCenter, magnitude = eew.magnitude, depthKm = eew.depthKm,
                intensity = intensityStr, distanceKm = distKm, etaSec = etaSec,
                sourceName = sourceName,
                // 只有实际已拉起全屏告警才标记 triggered；单报达阈值但融合后被否决不算已告警。
                reportNum = eew.reportNum, triggered = activeQuakeKey == quakeKey, backup = false,
                epiLat = eew.latitude, epiLon = eew.longitude,
                eventId = eew.eventId, sourceId = sourceId,
                reports = listOf(reportSnapshot)
            )
        )
        notifyHistoryChanged()
    }

    // ===================== 告警触发 =====================

    private fun triggerAlert(eew: Eew, distKm: Double, etaSec: Double, intensityStr: String, sourceName: String = ""): Boolean {
        // 未开启监听 / 开启前旧震 → 不拉起全屏告警（仍会写入历史，见 handleRawEew）
        if (suppressQuakeNotification(eew)) {
            Log.i(EewService.TAG, "[告警抑制] 跳过全屏告警: ${eew.hypoCenter} M${eew.magnitude} " +
                "serviceEnabled=${AppConfig.serviceEnabled} originMs=${eew.originMs} startMs=${AppConfig.monitorStartMs}")
            return false
        }
        // v1.4.4 有效增强：发震已超过 3 分钟 且 S 波已通过用户所在地（ETA≤0.5）→ 事后告知场景，
        // 此时只剩「地震波已抵达」警报声，对用户纯惊吓、无避险价值。仅拦全屏，仍写历史与通知栏。
        // 注意：不能用固定时间窗一刀切——距震中很远的用户 S 波尚未到达（ETA>0.5）时，
        // 即使发震已过数分钟仍是真实可用的倒计时（如 1000km/3.5 ≈ 4.8min 传播），必须保留全屏。
        if (isLateAlert(eew.originMs, System.currentTimeMillis()) && etaSec <= 0.5) {
            Log.i(EewService.TAG, "[事后警报] 发震${(System.currentTimeMillis() - eew.originMs) / 1000}s前且S波已通过，跳过全屏告警仅通知: ${eew.hypoCenter} M${eew.magnitude}")
            return false
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
            putExtra(EewService.EXTRA_EPI_LAT, eew.latitude)
            putExtra(EewService.EXTRA_EPI_LON, eew.longitude)
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
        return true
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
        // 后续报刷新过滤：仅当震级/烈度/ETA 有显著变化时才广播给 AlertActivity。
        // 轻微变化（如震级 ±0.1、烈度 ±0.2、ETA ±1s）只记录历史，不打断倒计时和语音。
        // 否则多源交叉修正可能每几秒就重启一次倒计时，产生严重体验问题。
        val etaKey = "eta|${eew.eventId}"
        val lastEta = lastRefreshParams[etaKey]
        val intensity = intensityStr.toDoubleOrNull() ?: 0.0
        val lastIntensity = lastRefreshParams["intensity|${eew.eventId}"]
        val lastMag = lastRefreshParams["mag|${eew.eventId}"]
        if (lastEta != null && lastMag != null && lastIntensity != null) {
            val magDelta = Math.abs(eew.magnitude - (lastMag.toDoubleOrNull() ?: 0.0))
            val intDelta = Math.abs(intensity - (lastIntensity.toDoubleOrNull() ?: 0.0))
            val etaDelta = Math.abs(etaSec - (lastEta.toDoubleOrNull() ?: 0.0))
            // 同时满足：震级变化 < 0.3 且 烈度变化 < 0.5 且 ETA 变化 < 3 秒 → 视为无意义刷新
            if (magDelta < 0.3 && intDelta < 0.5 && etaDelta < 3.0) {
                Log.d(EewService.TAG, "忽略无意义刷新: M${"%.1f".format(eew.magnitude)} 烈度$intensityStr ETA${"%.0f".format(etaSec)}s (ΔM=${"%.1f".format(magDelta)} ΔI=${"%.1f".format(intDelta)} ΔE=${"%.0f".format(etaDelta)})")
                return
            }
        }
        lastRefreshParams["eta|${eew.eventId}"] = etaSec.toString()
        lastRefreshParams["intensity|${eew.eventId}"] = intensityStr
        lastRefreshParams["mag|${eew.eventId}"] = eew.magnitude.toString()
        AppEvents.postRefresh(
            eventId = eew.eventId,
            mag = eew.magnitude,
            place = eew.hypoCenter,
            distance = distKm,
            eta = etaSec,
            intensity = intensityStr,
            depth = eew.depthKm,
            reportNum = eew.reportNum
        )
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
                    triggered = false, backup = true,
                    epiLat = eew.latitude, epiLon = eew.longitude,
                    eventId = eew.eventId, sourceId = "backup"
                )
            )
            notifyHistoryChanged()
            return
        }
        val distKm = haversineKm(AppConfig.homeLat, AppConfig.homeLon, eew.latitude, eew.longitude)
        val etaSec = AppConfig.estimateSWaveEtaSeconds(distKm, eew.depthKm)
        // v1.4.4 数据准确增强：备用源命中后的「预估烈度」改为计算【用户所在地烈度】，
        // 与主链路（WS/HTTP 降级/ICL）历史页展示语义完全一致——此前用
        // resolveIntensityStr（近震中烈度≈震级），对 USGS/EMSC 这类远离用户的地震会
        // 显示「约5」等震中烈度，与同列表其他条目的「用户脚下烈度」口径混杂，用户难以理解。
        // 四舍五入到 0.1（与主链路 siteIntensity 同精度）；弱震/无坐标兜底失败时回退原逻辑。
        val rawSite = estimateSiteIntensity(eew.magnitude, distKm, eew.depthKm, eew.latitude, eew.longitude)
        val intensityStr = if (rawSite > 0) {
            "%.1f".format(Math.round(rawSite.coerceAtLeast(0.0) * 10.0) / 10.0)
        } else {
            resolveIntensityStr(eew)
        }

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
                triggered = false, backup = true,
                epiLat = eew.latitude, epiLon = eew.longitude,
                eventId = eew.eventId, sourceId = "backup"
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

    /** 通知 HistoryFragment 刷新列表（P1-2 迁移：SharedFlow 事件流替代广播） */
    private fun notifyHistoryChanged() {
        AppEvents.postHistoryChanged()
    }
}
