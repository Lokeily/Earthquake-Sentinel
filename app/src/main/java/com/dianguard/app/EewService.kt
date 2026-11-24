package com.dianguard.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/**
 * 单路数据源的展示状态：连接与否、最近一次收到报文的时间、连续失败次数。
 * 由 EewConnectionManager 写入，主页从主线程周期性读取快照。
 */
data class SourceUiState(
    val id: String,
    val name: String,
    val connected: Boolean,
    val lastDataMs: Long,
    val failCount: Long,
    /**
     * 数据源侧错误码（如 "503"）：non-null 表示握手/升级被数据源服务器以 5xx 拒绝
     * （区别于移动网络抖动/断网）。主页据此显示「源异常·503」，让用户知道是服务端问题
     * 而非本机 App 故障。默认 null（构造处与 .copy() 均兼容）。
     */
    val serverError: String? = null,
    /**
     * HTTP 降级状态文案（v1.4.3）：non-null 表示该源 WS 不可用、正在以同源 HTTP 端点
     * 轮询兜底（如「HTTP降级·接收中」）。主页优先显示此状态（橙色）而非「源异常」，
     * 让用户知道预警接收并未中断。默认 null（构造处与 .copy() 均兼容）。
     */
    val fallbackNote: String? = null
)

/**
 * 地震哨兵核心：常驻前台服务（v1.0.17 重构版）。
 *
 * 连接/重连/源状态管理 → EewConnectionManager
 * 解析/去重/告警分发/备用源 → EewAlertManager
 * 生命周期/WakeLock/位置/新鲜度/通知通道 → 本 class
 */
class EewService : Service() {

    companion object {
        // ===== 通知通道 =====
        const val CHANNEL_ID = "dianguard_foreground"
        const val CHANNEL_DISTANT_ID = "dianguard_distant"
        const val CHANNEL_ALERT_ID = "dianguard_alert"
        const val NOTIFY_ID = 1
        const val DISTANT_NOTIFY_ID = 1000      // 远震通知固定 ID（新地震覆盖旧通知）

        // ===== Intent Extra（首报启动 AlertActivity 用） =====
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_MAG = "mag"
        const val EXTRA_PLACE = "place"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_ETA = "eta"
        const val EXTRA_INTENSITY = "intensity"
        const val EXTRA_DEPTH = "depth"
        const val EXTRA_REPORT_NUM = "report_num"
        // 数据来源展示名（如"中国地震预警网""多源融合(2源)""模拟预警"），用于告警页底部"来自X"
        const val EXTRA_SOURCE = "source_name"
        // 发震时刻 epoch 毫秒，用于告警页底部时间显示；0 表示未知
        const val EXTRA_TIME = "origin_time_ms"
        // 回放标记（测试版「预警回放」页传入）：true 表示本次告警是历史回放而非真实预警。
        // AlertActivity 据此在解除时跳过「清除服务 activeEventId / 余震提醒」，避免干扰真实预警链路。
        const val EXTRA_REPLAY = "is_replay"
        // 震中坐标（v1.4.3）：供告警页计算「震中位于您的XX方向」（ICL 式方位提示）
        const val EXTRA_EPI_LAT = "epi_lat"
        const val EXTRA_EPI_LON = "epi_lon"

        internal const val TAG = "EewService"

        const val CONN_FAIL_WARN_THRESHOLD = 10L

        // ===== 去重 / 兜底常量 =====
        internal const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
        private const val DEDUP_CLEANUP_INTERVAL_MS = 5 * 60 * 1000L
        internal const val FALLBACK_ALERT_NOTIFY_ID = 2

        // ===== WakeLock =====
        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val WAKELOCK_REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000L

        // ===== 服务全局状态（v1.2.0 集中管理） =====
        @Volatile var state: ServiceState = ServiceState()
            internal set

        /** 服务启动时间戳，供 EewAlertManager 做启动冷静期判断 */
        @Volatile var serviceStartedMs: Long = 0L

        /**
         * 当前运行中的服务实例（同一进程内引用，供设置页等调用运行时配置，如 applyBeeCLDConfig）。
         * 服务未运行时为 null，调用方务必判空；onCreate 赋值、onDestroy 清空。
         */
        @Volatile var instance: EewService? = null

        private val stateLock = Any()

        /** 原子更新状态（线程安全） */
        internal fun updateState(transform: ServiceState.() -> ServiceState) {
            synchronized(stateLock) { state = state.transform() }
        }

        // 向后兼容的快捷访问器（避免大范围修改现有代码）
        /**
         * 已连接源数量：统一由 sourceStatuses（含 WebSocket 源与 ICL HTTP 轮询源）推导，
         * 使 ICL 的连通性也计入“在线源数”，避免 Wolfx 断开但 ICL 存活时被误判为全断。
         */
        val connectedSourceCount: Int get() = state.sourceStatuses.count { it.connected }
        /** 是否存在任一在线数据源（WebSocket 或 ICL HTTP 轮询），用于头条文案与新鲜度判定 */
        val anySourceConnected: Boolean get() = state.sourceStatuses.any { it.connected }
        val wakeLockHeld: Boolean get() = state.wakeLockHeld
        val lastStatusText: String get() = state.lastStatusText
        val headlineState: String get() = state.headlineState
        val sourceStatuses: List<SourceUiState> get() = state.sourceStatuses
        val backupActive: Boolean get() = state.backupActive
        val backupNote: String get() = state.backupNote

        // ===== 源状态内部管理 =====
        private val sourceStateLock = Any()
        private val _sourceStates = LinkedHashMap<String, SourceUiState>().apply {
            EEW_SOURCES.forEach { put(it.id, SourceUiState(it.id, it.name, false, 0L, 0L)) }
        }

        /** 原子地更新某一源的状态并刷新对外快照（由 EewConnectionManager 调用） */
        internal fun patchSourceState(id: String, patch: SourceUiState.() -> SourceUiState) {
            synchronized(sourceStateLock) {
                val cur = _sourceStates[id] ?: return
                _sourceStates[id] = cur.patch()
                val snap = _sourceStates.values.toList()
                updateState { copy(sourceStatuses = snap) }
            }
        }

        /** 服务停止时把所有源重置为"未连接" */
        internal fun resetSourceStates() {
            synchronized(sourceStateLock) {
                _sourceStates.clear()
                EEW_SOURCES.forEach { _sourceStates[it.id] = SourceUiState(it.id, it.name, false, 0L, 0L) }
                val snap = _sourceStates.values.toList()
                updateState { copy(sourceStatuses = snap) }
            }
        }
    }

    // ===== 实例字段 =====

    @JvmField val mainHandler = Handler(Looper.getMainLooper())

    // 服务级协程作用域（P1-2 迁移：用于订阅 AppEvents 事件流，随服务销毁取消）
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 委托对象（包内可见，供 ConnectionManager/AlertManager 互访）
    internal lateinit var connMgr: EewConnectionManager
    internal lateinit var alertMgr: EewAlertManager
    internal lateinit var iclPoller: IclPoller
    /** Wolfx WS→HTTP 自动降级轮询器（WS 连续失败时按源启用，恢复后自动停止） */
    internal lateinit var httpFallback: EewHttpFallbackPoller

    // ===== 生命周期管理 =====

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRefreshRunnable: Runnable? = null
    private var locRefreshRunnable: Runnable? = null
    private var dedupCleanupRunnable: Runnable? = null
    private var freshnessRunnable: Runnable? = null

    private val FRESHNESS_CHECK_INTERVAL_MS = 30_000L
    private val LOC_MOVE_SKIP_METERS = 200.0
    // 是否已至少成功连接过一次；用于区分“启动尚未连上”与“运行中断连”
    private var everConnected = false

    // P1-2 迁移：告警解除由 AlertActivity 通过 AppEvents.alertDismissed 事件流通知，
    // 服务在 onCreate 订阅（见 subscribeAppEvents），不再使用 LocalBroadcastManager。
    private var alertDismissedJob: kotlinx.coroutines.Job? = null

    // ===================== Service 生命周期 =====================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceStartedMs = System.currentTimeMillis()
        instance = this
        AppConfig.init(this)
        connMgr = EewConnectionManager(this)
        alertMgr = EewAlertManager(this)
        iclPoller = IclPoller(
            onEew = { eew, src -> alertMgr.handleRawIcl(eew, src) },
            // ICL 连通性上报：写入 sourceStatuses，使主页出现独立 ICL 状态行，
            // 并让 connectedSourceCount / anySourceConnected 计入 ICL，
            // 防止 Wolfx 断开时误报“监听中断”与误激活备用源。
            onStatus = { connected, lastDataMs, failCount ->
                patchSourceState("icl") { copy(connected = connected, lastDataMs = lastDataMs, failCount = failCount) }
                connMgr.refreshHeadline()
            }
        )
        // Wolfx WS→HTTP 自动降级轮询器：WS 连续失败时按源启用同机构 HTTP 端点兜底，
        // 成功时回报 alive=true 保持头条「守护中」并抑制备用探测源（备用源是更慢的最后层）。
        httpFallback = EewHttpFallbackPoller(
            onEew = { eew, src -> alertMgr.handleRawHttp(eew, src) },
            onStatus = { srcId, note, dataMs, alive ->
                patchSourceState(srcId) {
                    copy(
                        connected = alive,
                        fallbackNote = note,
                        lastDataMs = if (dataMs > 0) dataMs else lastDataMs
                    )
                }
                connMgr.refreshHeadline()
                updateIclPollingMode()
            }
        )
        createNotificationChannels()
        acquireWakeLock()
        startWakeLockRefresh()
        startDedupCleanup()
        startFreshnessMonitor()
        connMgr.startNetworkMonitor()
        // R6（P1 证书固定逃生舱）：当某数据源证书固定连续失败被降级时，强制提醒用户（预警仍可用，但防伪减弱）
        HttpClient.onDegrade = { host -> postPinDegradedNotify(host) }
        subscribeAppEvents()
    }

    /** P1-2 迁移：订阅应用级事件流（替代 LocalBroadcastManager 的 alertDismissed 广播） */
    private fun subscribeAppEvents() {
        alertDismissedJob?.cancel()
        alertDismissedJob = serviceScope.launch {
            AppEvents.alertDismissed.collect {
                alertMgr.activeEventId = null
                alertMgr.activeQuakeKey = null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIFY_ID,
                buildForegroundNotification("预警监听中…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            // Android 14+ 若缺少对应前台服务类型声明/权限或调用时机不当会抛异常；
            // 此时无法合规常驻，主动停止自启，避免崩溃与反复拉起。
            Log.e(TAG, "启动前台服务失败（可能缺少前台服务类型声明/权限），停止自启", e)
            AppConfig.serviceEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        // 防僵尸态：系统杀进程后 START_STICKY 可能拉起本服务，但用户早已关闭监听
        // （serviceEnabled=false）。若继续运行，主页显示“守护中”而所有告警被
        // suppressQuakeNotification 静默抑制——看似正常实则完全不工作。此时主动自停。
        if (!AppConfig.serviceEnabled) {
            Log.i(TAG, "serviceEnabled=false（系统重启拉起或已关闭监听），停止服务")
            stopSelf()
            return START_NOT_STICKY
        }
        if (connMgr.connections.isEmpty()) {
            connMgr.connectAll()
        } else {
            Log.i(TAG, "服务已在运行，跳过重复连接 (${connMgr.connections.size} 个源)")
        }
        // R7 修复：旧版本升级后 serviceEnabled 可能为 true 但从未记录 monitorStartMs（=0），
        // 此处补记“当前时刻”为基准，避免升级后立刻把历史旧震当作“开启后”的地震推送。
        // 正常新开启流程中 monitorStartMs 已由 AppConfig.serviceEnabled setter 写入，这里不会覆盖。
        if (AppConfig.serviceEnabled && AppConfig.monitorStartMs == 0L) {
            AppConfig.monitorStartMs = System.currentTimeMillis()
        }
        startLocationRefresh()
        iclPoller.start()
        return START_STICKY
    }

    /**
     * 运行时应用 BeeCLD 用户源配置：启用且 token 有效 → 动态起连；否则断开。
     * 供设置页保存后调用。服务启动 / 网络恢复时 connectAll 已按当前配置自动处理 beecld，
     * 本方法主要用于用户在设置页运行时切换开关或更换 token。
     */
    fun applyBeeCLDConfig() {
        if (AppConfig.beecldEnabled && AppConfig.beecldWsUrl().isNotBlank()) {
            connMgr.connectSourceDynamically("beecld")
        } else {
            connMgr.disconnectSource("beecld")
        }
    }

    /**
     * 运行时应用「演练控制台」配置（v1.4.5，仅 debug 演练包）：
     * 地址有效且已启用 → 动态起连 drill 源；否则立即断开。
     * 与真实数据源完全独立：断开演练不影响 CENC / 四川 / 重庆 / CWA / ICL 任一链路。
     */
    fun applyDrillConfig() {
        if (AppConfig.drillWsUrl().isNotBlank()) {
            connMgr.connectSourceDynamically("drill")
        } else {
            connMgr.disconnectSource("drill")
        }
    }

    /** 手动修改演练参考位置后立即上报后台，实现手机 ⇄ 后台即时同步 */
    fun reportDrillStatusNow() {
        connMgr.reportDrillStatusNow()
    }

    /**
     * ICL 轮询自适应：任一 wolfx WS 存活 → 常态 3s；wolfx 全部降级/断开 → 2s。
     * wolfx 全断时 ICL 是唯一实时权威源，收紧间隔把平均接收延迟从 1.5s 压到 1.0s。
     * 由 EewConnectionManager 在 WS 状态变化时调用。
     */
    internal fun updateIclPollingMode() {
        val wolfxIds = EewHttpFallbackPoller.FALLBACK_URLS.keys
        val anyWolfxAlive = connMgr.connectedSources.any { it in wolfxIds } ||
            wolfxIds.any { httpFallback.isReceiving(it) }
        iclPoller.setPollInterval(
            if (anyWolfxAlive) IclPoller.NORMAL_INTERVAL_MS else IclPoller.FAST_INTERVAL_MS
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        connMgr.stopNetworkMonitor()
        connMgr.destroy()
        alertMgr.destroy()
        iclPoller.stop()
        httpFallback.stopAll()
        HttpClient.onDegrade = null
        stopLocationRefresh()
        stopWakeLockRefresh()
        stopDedupCleanup()
        stopFreshnessMonitor()
        updateState { ServiceState() }  // 重置全部状态
        resetSourceStates()
        alertDismissedJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        AppConfig.serviceEnabled = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉任务卡片不停止服务
    }

    override fun onTimeout(startId: Int) {
        // Android 15+ 对 dataSync 类型前台服务强制 6 小时限时，超时回调本方法。
        // 地震哨兵必须 7x24 常驻——若按旧逻辑直接 stopSelf，插电常驻的老手机会
        // 每 6 小时静默失联一次（错过深夜地震=致命）。此处改为以 specialUse 类型
        // 续期前台服务继续监听；仅当续期失败时才放弃并自停。
        Log.w(TAG, "dataSync 前台服务超时（6h），尝试以 specialUse 类型续期")
        val renewed = try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            ServiceCompat.startForeground(this, NOTIFY_ID, buildForegroundNotification("预警监听中…"), type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "specialUse 续期失败，无法继续常驻监听", e)
            false
        }
        if (!renewed) {
            AppConfig.serviceEnabled = false
            stopSelf()
        }
    }

    // ===================== 周期任务 =====================

    private fun startDedupCleanup() {
        stopDedupCleanup()
        dedupCleanupRunnable = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                alertMgr.cleanupOldQuakes(now)
                mainHandler.postDelayed(this, DEDUP_CLEANUP_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(dedupCleanupRunnable!!, DEDUP_CLEANUP_INTERVAL_MS)
    }

    private fun stopDedupCleanup() {
        dedupCleanupRunnable?.let { mainHandler.removeCallbacks(it) }
        dedupCleanupRunnable = null
    }

    private fun startFreshnessMonitor() {
        stopFreshnessMonitor()
        freshnessRunnable = object : Runnable {
            override fun run() {
                checkDataFreshness()
                mainHandler.postDelayed(this, FRESHNESS_CHECK_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(freshnessRunnable!!, FRESHNESS_CHECK_INTERVAL_MS)
    }

    private fun stopFreshnessMonitor() {
        freshnessRunnable?.let { mainHandler.removeCallbacks(it) }
        freshnessRunnable = null
    }

    private fun checkDataFreshness() {
        // 以“是否有源处于连接状态”判定新鲜度，而非“是否收到过地震报文”——
        // 地震报文天然稀疏，平静期不应误报“网络异常”。
        // 注：统一用 anySourceConnected（含 ICL HTTP 轮询源），避免 Wolfx 断开但 ICL 存活时误报“网络异常”。
        val connected = EewService.anySourceConnected
        if (connected) everConnected = true
        if (!connected && everConnected && !alertMgr.dataStaleNotified) {
            alertMgr.dataStaleNotified = true
            updateForegroundNotify("网络异常，预警监听临时中断")
        } else if (connected && alertMgr.dataStaleNotified) {
            // 恢复：连接已恢复，强刷状态机与常驻通知（force 绕过相等性检查）
            alertMgr.dataStaleNotified = false
            connMgr.refreshHeadline(force = true)
        }
        postFreshness()
    }

    private fun postFreshness() {
        AppEvents.postFreshness(alertMgr.lastDataReceivedMs)
    }

    private fun startLocationRefresh() {
        stopLocationRefresh()
        locRefreshRunnable = object : Runnable {
            override fun run() {
                refreshLocation()
                mainHandler.postDelayed(this, AppConfig.LOCATION_REFRESH_INTERVAL_MS)
            }
        }
        refreshLocation()
        mainHandler.postDelayed(locRefreshRunnable!!, AppConfig.LOCATION_REFRESH_INTERVAL_MS)
    }

    private fun stopLocationRefresh() {
        locRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        locRefreshRunnable = null
    }

    private fun refreshLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            var best: android.location.Location? = null
            for (p in providers) {
                if (!lm.isProviderEnabled(p)) continue
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.time > best.time) best = loc
            }
            if (best != null) {
                val newLat = best.latitude
                val newLon = best.longitude
                val moveM = haversineKm(AppConfig.homeLat, AppConfig.homeLon, newLat, newLon) * 1000.0
                if (moveM >= LOC_MOVE_SKIP_METERS) {
                    AppConfig.homeLat = newLat
                    AppConfig.homeLon = newLon
                    AppConfig.hasLocation = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "位置刷新失败（可忽略）: ${e.message}")
        }
    }

    // ===================== 通知 / 广播 =====================

    internal fun postStatus(status: String) {
        updateState { copy(lastStatusText = status) }
        AppEvents.postStatus(status)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildForegroundNotification(status))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val foreground = NotificationChannel(CHANNEL_ID, "地震哨兵预警监听", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "常驻后台实时接收地震预警"; setShowBadge(false) }
            val distant = NotificationChannel(CHANNEL_DISTANT_ID, "远震小震提醒", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "未达阈值的地震，仅通知栏轻提示" }
            val alert = NotificationChannel(CHANNEL_ALERT_ID, "地震预警强提醒", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "全屏告警页被系统拦截时的兜底强提醒"
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            nm.createNotificationChannel(foreground)
            nm.createNotificationChannel(distant)
            nm.createNotificationChannel(alert)
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("地震哨兵")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_warning)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** 更新前台通知文本（网络中断降级提示等） */
    private fun updateForegroundNotify(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, buildForegroundNotification(text))
        } catch (_: Exception) { }
    }

    /**
     * R6（P1 证书固定逃生舱）：当某数据源证书固定连续失败被降级为系统信任时，
     * 弹出一次性强提醒。预警仍可用，但传输层防伪中间人保护暂时减弱，建议更新版本恢复。
     */
    private fun postPinDegradedNotify(host: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val text = "数据源 $host 的证书固定校验连续失败，已临时改用系统信任链。\n" +
                    "地震预警仍可正常接收，但传输层防伪中间人保护暂时减弱，建议更新到最新版本以恢复。"
            val n = NotificationCompat.Builder(this, CHANNEL_DISTANT_ID)
                .setContentTitle("地震哨兵 · 证书安全降级提醒")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_warning)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            nm.notify(9000, n) // 固定 ID：重复降级只覆盖同一条，不刷屏
        } catch (_: Exception) { }
    }

    // ===================== WakeLock =====================

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dianguard:EewService")
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        EewService.updateState { copy(wakeLockHeld = (wakeLock?.isHeld == true)) }
    }

    private fun startWakeLockRefresh() {
        stopWakeLockRefresh()
        wakeLockRefreshRunnable = object : Runnable {
            override fun run() {
                wakeLock?.let {
                    // 无条件续期：对已持有的锁调用 acquire(timeout) 属重入，会刷新到期时间，
                    // 避免“仅未持有时才续期”造成的死窗（锁在两次判定之间已过期却不再续，
                    // 导致后台被系统回收、预警链路中断）。
                    it.acquire(WAKELOCK_TIMEOUT_MS)
                    updateState { copy(wakeLockHeld = it.isHeld) }
                }
                mainHandler.postDelayed(this, WAKELOCK_REFRESH_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(wakeLockRefreshRunnable!!, WAKELOCK_REFRESH_INTERVAL_MS)
    }

    private fun stopWakeLockRefresh() {
        wakeLockRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        wakeLockRefreshRunnable = null
    }

    private fun releaseWakeLock() {
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        updateState { copy(wakeLockHeld = false) }
    }
}
