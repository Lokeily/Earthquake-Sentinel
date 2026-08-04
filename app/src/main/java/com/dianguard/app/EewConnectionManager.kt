package com.dianguard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * 多源 WebSocket 连接管理（从 EewService 拆分，v1.0.17+）。
 *
 * 职责：订阅/重连/源状态跟踪/头条文案计算。
 * 不涉及 EEW 解析、去重、告警触发——这些属于 EewAlertManager。
 *
 * @param service  父 EewService，用于访问 mainHandler、alertMgr、postStatus 等
 */
class EewConnectionManager(val service: EewService) {

    private val client = HttpClient.instance
    val connections = LinkedHashMap<String, SourceConn>()
    val connectedSources = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val maxReconnectDelayMs = 60_000L

    // 头条状态机变量
    @Volatile private var lastAnyConnectedMs = 0L
    private val ALL_DOWN_GRACE_MS = 15_000L
    @Volatile private var allDownRunnable: Runnable? = null

    // 防止 onDestroy 期间注册的网络回调还在尝试重连
    @Volatile var destroyed = false

    // 网络变化自动重连
    private val connectivityManager by lazy {
        service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (destroyed) return
            Log.i(EewService.TAG, "网络恢复，主动重连全部数据源")
            reconnectAll()
        }
    }

    // ===================== 公开 API =====================

    /** 为所有 EEW_SOURCES 启动 WebSocket 连接 */
    fun connectAll() {
        for (src in EEW_SOURCES) {
            if (src.wsUrl.isBlank()) {
                Log.i(EewService.TAG, "跳过未配置的主机: ${src.id}")
                continue
            }
            val conn = SourceConn(src)
            connections[src.id] = conn
            conn.connect()
        }
        refreshHeadline()
    }

    /** 注册网络变化监听：一旦恢复网络（WiFi/移动数据切换、出电梯等），立即重连全部 WebSocket */
    fun startNetworkMonitor() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.w(EewService.TAG, "注册网络监听失败: ${e.message}")
        }
    }

    /** 注销网络监听 */
    fun stopNetworkMonitor() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) { }
    }

    /** 关闭全部现有连接后立即重建（网络恢复时调用） */
    private fun reconnectAll() {
        for (conn in connections.values) {
            try { conn.ws?.cancel() } catch (_: Exception) { }
        }
        connections.clear()
        connectedSources.clear()
        connectAll()
    }

    /** 关闭所有连接 */
    fun destroy() {
        destroyed = true
        cancelAllDownCheck()
        for (conn in connections.values) {
            conn.cancelPending()
            try { conn.ws?.cancel() } catch (_: Exception) { }
        }
        connections.clear()
        connectedSources.clear()
    }

    /** 重建头条状态并广播给主页/前台通知 */
    fun refreshHeadline(force: Boolean = false) {
        val n = connectedSources.size
        val text = if (n > 0) {
            lastAnyConnectedMs = System.currentTimeMillis()
            cancelAllDownCheck()
            service.alertMgr.stopBackupSource()
            service.getString(R.string.status_guard)
        } else {
            val gap = System.currentTimeMillis() - lastAnyConnectedMs
            if (lastAnyConnectedMs == 0L || gap <= ALL_DOWN_GRACE_MS) {
                service.getString(R.string.status_connecting)
            } else {
                service.alertMgr.startBackupSource()
                service.getString(R.string.status_down)
            }
        }
        if (force || text != EewService.headlineState) {
            EewService.updateState { copy(headlineState = text) }
            service.postStatus(text)
        }
    }

    // ===================== SourceConn =====================

    inner class SourceConn(val source: EewSource) {
        var ws: WebSocket? = null
        var delay = 3_000L
        var failCount = 0L
        // 待执行的重连任务；destroy() 时据此撤销，避免已销毁后仍被 postDelayed 拉起新连接（泄漏）
        var pendingReconnect: Runnable? = null

        fun connect() {
            val request = Request.Builder().url(source.wsUrl).build()
            ws = client.newWebSocket(request, makeListener(source.id))
        }

        fun scheduleReconnect() {
            cancelPending()
            val r = Runnable { connect() }
            pendingReconnect = r
            service.mainHandler.postDelayed(r, delay)
            delay = (delay * 2).coerceAtMost(maxReconnectDelayMs)
        }

        fun cancelPending() {
            pendingReconnect?.let { service.mainHandler.removeCallbacks(it) }
            pendingReconnect = null
        }
    }

    private fun makeListener(sourceId: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 防守：服务已销毁，连接成功也应立即关闭，并撤销可能残留的重连任务
            if (destroyed) {
                try { webSocket.cancel() } catch (_: Exception) { }
                return
            }
            connectedSources.add(sourceId)
            val c = connections[sourceId]
            if (c != null) {
                c.cancelPending()
                c.delay = 3_000L
                c.failCount = 0L
            }
            EewService.updateState { copy(connectedSourceCount = connectedSources.size) }
            patchSourceState(sourceId) { copy(connected = true, failCount = 0L) }
            if (service.alertMgr.dataStaleNotified) {
                service.alertMgr.dataStaleNotified = false
            }
            refreshHeadline()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 任何报文（含心跳等非 EEW）都视为“链路活跃”，刷新新鲜度与源状态
            service.alertMgr.lastDataReceivedMs = System.currentTimeMillis()
            EewService.patchSourceState(sourceId) { copy(lastDataMs = System.currentTimeMillis()) }
            try {
                service.alertMgr.handleRaw(text, sourceId)
            } catch (e: Exception) {
                Log.w(EewService.TAG, "解析消息失败($sourceId): $text", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 防守：服务已销毁，或此回调来自已被 reconnectAll() 废弃的旧连接
            if (destroyed) return
            val cur = connections[sourceId]
            if (cur == null || cur.ws !== webSocket) {
                Log.d(EewService.TAG, "忽略过期连接的回调($sourceId)")
                return
            }
            Log.w(EewService.TAG, "WebSocket 断开($sourceId): ${t.message}")
            connectedSources.remove(sourceId)
            EewService.updateState { copy(connectedSourceCount = connectedSources.size) }
            cur.failCount++
            patchSourceState(sourceId) { copy(connected = false, failCount = cur.failCount) }
            if (cur.failCount == EewService.CONN_FAIL_WARN_THRESHOLD) {
                Log.w(EewService.TAG, "数据源 ${cur.source.name} 连续失败 ${cur.failCount} 次，可能已不可用，将持续退避重试")
            }
            cur.scheduleReconnect()
            refreshHeadline()
            if (connectedSources.size == 0) scheduleAllDownCheck()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // 防守：服务已销毁，或此回调来自已被 reconnectAll() 废弃的旧连接
            if (destroyed) return
            val cur = connections[sourceId]
            if (cur == null || cur.ws !== webSocket) {
                Log.d(EewService.TAG, "忽略过期连接的回调($sourceId)")
                return
            }
            Log.i(EewService.TAG, "WebSocket 关闭($sourceId): $code $reason")
            connectedSources.remove(sourceId)
            EewService.updateState { copy(connectedSourceCount = connectedSources.size) }
            patchSourceState(sourceId) { copy(connected = false) }
            cur.scheduleReconnect()
            refreshHeadline()
            if (connectedSources.size == 0) scheduleAllDownCheck()
        }
    }

    private fun scheduleAllDownCheck() {
        cancelAllDownCheck()
        allDownRunnable = Runnable { if (connectedSources.size == 0) refreshHeadline() }
        service.mainHandler.postDelayed(allDownRunnable!!, ALL_DOWN_GRACE_MS)
    }

    private fun cancelAllDownCheck() {
        allDownRunnable?.let { service.mainHandler.removeCallbacks(it) }
        allDownRunnable = null
    }

    /** 原子更新数据源状态（委托 EewService.companion 方法） */
    private fun patchSourceState(id: String, patch: SourceUiState.() -> SourceUiState) {
        EewService.patchSourceState(id, patch)
    }
}
