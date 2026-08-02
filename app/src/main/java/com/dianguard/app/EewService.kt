package com.dianguard.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
    val failCount: Long
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
        const val DISTANT_NOTIFY_ID_BASE = 1000

        // ===== 广播 Action / Extra =====
        const val ACTION_STATUS = "com.dianguard.app.STATUS"
        const val ACTION_REFRESH = "com.dianguard.app.REFRESH"
        const val ACTION_FRESHNESS = "com.dianguard.app.FRESHNESS"
        const val EXTRA_LAST_DATA_MS = "last_data_ms"

        const val DISTANT_NOTIFY_GROUP = "dianguard_distant_group"
        const val DISTANT_NOTIFY_SUMMARY_ID = 999

        const val ACTION_ALERT_DISMISSED = "com.dianguard.app.ALERT_DISMISSED"

        const val EXTRA_STATUS = "status"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_MAG = "mag"
        const val EXTRA_PLACE = "place"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_ETA = "eta"
        const val EXTRA_INTENSITY = "intensity"
        const val EXTRA_DEPTH = "depth"
        const val EXTRA_REPORT_NUM = "report_num"

        internal const val TAG = "EewService"

        const val CONN_FAIL_WARN_THRESHOLD = 10L

        // ===== 去重 / 兜底常量 =====
        internal const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
        private const val DEDUP_CLEANUP_INTERVAL_MS = 5 * 60 * 1000L
        internal const val FALLBACK_ALERT_NOTIFY_ID = 2

        // ===== WakeLock =====
        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val WAKELOCK_REFRESH_INTERVAL_MS = 5 * 60 * 60 * 1000L

        // ===== 自检用只读状态 =====
        @Volatile var connectedSourceCount: Int = 0
            internal set
        @Volatile var wakeLockHeld: Boolean = false
            internal set
        @Volatile var lastStatusText: String = "监听未开启"
            internal set
        @Volatile var headlineState: String = "监听未开启"
            internal set

        // ===== 数据源状态快照 =====
        @Volatile var sourceStatuses: List<SourceUiState> = EEW_SOURCES.map {
            SourceUiState(it.id, it.name, false, 0L, 0L)
        }
            internal set

        // ===== 备用探测源状态 =====
        @Volatile var backupActive: Boolean = false
            internal set
        @Volatile var backupNote: String = "待命中"
            internal set

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
                sourceStatuses = _sourceStates.values.toList()
            }
        }

        /** 服务停止时把所有源重置为"未连接" */
        internal fun resetSourceStates() {
            synchronized(sourceStateLock) {
                _sourceStates.clear()
                EEW_SOURCES.forEach { _sourceStates[it.id] = SourceUiState(it.id, it.name, false, 0L, 0L) }
                sourceStatuses = _sourceStates.values.toList()
            }
        }
    }

    // ===== 实例字段 =====

    @JvmField val mainHandler = Handler(Looper.getMainLooper())

    // 委托对象（包内可见，供 ConnectionManager/AlertManager 互访）
    internal lateinit var connMgr: EewConnectionManager
    internal lateinit var alertMgr: EewAlertManager

    // ===== 生命周期管理 =====

    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRefreshRunnable: Runnable? = null
    private var locRefreshRunnable: Runnable? = null
    private var dedupCleanupRunnable: Runnable? = null
    private var freshnessRunnable: Runnable? = null

    private val FRESHNESS_CHECK_INTERVAL_MS = 30_000L
    private val DATA_STALE_MS = 5 * 60 * 1000L
    private val LOC_MOVE_SKIP_METERS = 200.0

    private val alertDismissedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ALERT_DISMISSED) {
                alertMgr.activeEventId = null
                alertMgr.activeQuakeKey = null
            }
        }
    }

    // ===================== Service 生命周期 =====================

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
        connMgr = EewConnectionManager(this)
        alertMgr = EewAlertManager(this)
        createNotificationChannels()
        acquireWakeLock()
        startWakeLockRefresh()
        startDedupCleanup()
        startFreshnessMonitor()
        connMgr.startNetworkMonitor()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(alertDismissedReceiver, IntentFilter(ACTION_ALERT_DISMISSED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFY_ID,
            buildForegroundNotification("预警监听中…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (connMgr.connections.isEmpty()) {
            connMgr.connectAll()
        } else {
            Log.i(TAG, "服务已在运行，跳过重复连接 (${connMgr.connections.size} 个源)")
        }
        startLocationRefresh()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        connMgr.stopNetworkMonitor()
        connMgr.destroy()
        alertMgr.destroy()
        stopLocationRefresh()
        stopWakeLockRefresh()
        stopDedupCleanup()
        stopFreshnessMonitor()
        connectedSourceCount = 0
        wakeLockHeld = false
        lastStatusText = "监听未开启"
        headlineState = "监听未开启"
        resetSourceStates()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(alertDismissedReceiver)
        } catch (_: Exception) { }
        releaseWakeLock()
        AppConfig.serviceEnabled = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉任务卡片不停止服务
    }

    override fun onTimeout(startId: Int) {
        // Android 14+ 前台服务超时处理
    }

    // ===================== 周期任务 =====================

    private fun startDedupCleanup() {
        stopDedupCleanup()
        dedupCleanupRunnable = object : Runnable {
            override fun run() {
                alertMgr.cleanupOldQuakes(System.currentTimeMillis())
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
        if (alertMgr.lastDataReceivedMs != 0L) {
            val since = System.currentTimeMillis() - alertMgr.lastDataReceivedMs
            if (since > DATA_STALE_MS && !alertMgr.dataStaleNotified) {
                alertMgr.dataStaleNotified = true
                // 网络中断降级提示：更新前台通知告知用户
                updateForegroundNotify("网络异常，预警监听临时中断")
            }
        }
        postFreshness()
    }

    private fun postFreshness() {
        val i = Intent(ACTION_FRESHNESS).apply { putExtra(EXTRA_LAST_DATA_MS, alertMgr.lastDataReceivedMs) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
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
        lastStatusText = status
        val i = Intent(ACTION_STATUS).apply { putExtra(EXTRA_STATUS, status) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
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

    // ===================== WakeLock =====================

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dianguard:EewService")
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        wakeLockHeld = (wakeLock?.isHeld == true)
    }

    private fun startWakeLockRefresh() {
        stopWakeLockRefresh()
        wakeLockRefreshRunnable = object : Runnable {
            override fun run() {
                wakeLock?.let {
                    if (!it.isHeld) {
                        it.acquire(WAKELOCK_TIMEOUT_MS)
                        wakeLockHeld = it.isHeld
                    }
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
        wakeLockHeld = false
    }
}
