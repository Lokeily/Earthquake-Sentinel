package com.dianguard.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 应用级事件总线（v1.3.4 重构：替代已废弃的 LocalBroadcastManager）。
 *
 * 所有跨组件通信（Service ↔ Activity/Fragment）统一走 Kotlin SharedFlow，
 * 不再依赖 Android BroadcastReceiver 机制。收端通过 lifecycleScope.launch
 * 订阅，自动跟随生命周期取消，无需手动 register/unregister。
 *
 * 设计原则：
 *  - 无粘性：SharedFlow（replay=0），避免旧事件被新订阅者重新消费。
 *  - 线程安全：MutableSharedFlow 内部使用锁，多线程 emit 安全。
 *  - 轻量级：单例 object，不引入额外的 DI 框架。
 */
object AppEvents {

    /** 状态更新：主页头条文案变化（EewService → HomeFragment） */
    data class StatusEvent(val text: String)
    private val _status = MutableSharedFlow<StatusEvent>(extraBufferCapacity = 1)
    val status: SharedFlow<StatusEvent> = _status.asSharedFlow()
    fun postStatus(text: String) { _status.tryEmit(StatusEvent(text)) }

    /** 新鲜度更新：最近一次收到数据的时间（EewService → HomeFragment） */
    data class FreshnessEvent(val lastDataMs: Long)
    private val _freshness = MutableSharedFlow<FreshnessEvent>(extraBufferCapacity = 1)
    val freshness: SharedFlow<FreshnessEvent> = _freshness.asSharedFlow()
    fun postFreshness(ms: Long) { _freshness.tryEmit(FreshnessEvent(ms)) }

    /** 告警刷新：修正报广播给 AlertActivity */
    data class RefreshEvent(
        val eventId: String?,
        val mag: Double,
        val place: String,
        val distance: Double,
        val eta: Double,
        val intensity: String,
        val depth: Double,
        val reportNum: Int
    )
    private val _refresh = MutableSharedFlow<RefreshEvent>(extraBufferCapacity = 1)
    val refresh: SharedFlow<RefreshEvent> = _refresh.asSharedFlow()
    fun postRefresh(
        eventId: String?, mag: Double, place: String,
        distance: Double, eta: Double, intensity: String,
        depth: Double, reportNum: Int
    ) { _refresh.tryEmit(RefreshEvent(eventId, mag, place, distance, eta, intensity, depth, reportNum)) }

    /** 告警解除：AlertActivity → EewService 清除活动事件 */
    object AlertDismissedEvent
    private val _alertDismissed = MutableSharedFlow<AlertDismissedEvent>(extraBufferCapacity = 1)
    val alertDismissed: SharedFlow<AlertDismissedEvent> = _alertDismissed.asSharedFlow()
    fun postAlertDismissed() { _alertDismissed.tryEmit(AlertDismissedEvent) }

    /** 历史记录变更：EewAlertManager → HistoryFragment 刷新列表 */
    object HistoryChangedEvent
    private val _historyChanged = MutableSharedFlow<HistoryChangedEvent>(extraBufferCapacity = 1)
    val historyChanged: SharedFlow<HistoryChangedEvent> = _historyChanged.asSharedFlow()
    fun postHistoryChanged() { _historyChanged.tryEmit(HistoryChangedEvent) }

    /** 数据源连接成功：EewConnectionManager → SettingsFragment 悬浮提示 */
    data class SourceConnectedEvent(val sourceId: String)
    private val _sourceConnected = MutableSharedFlow<SourceConnectedEvent>(extraBufferCapacity = 1)
    val sourceConnected: SharedFlow<SourceConnectedEvent> = _sourceConnected.asSharedFlow()
    fun postSourceConnected(id: String) { _sourceConnected.tryEmit(SourceConnectedEvent(id)) }

    /** 演练参考位置变更（v1.4.6）：后台控制帧或手机手动输入 → 设置页即时刷新 */
    data class DrillLocationEvent(
        val lat: Double, val lon: Double, val source: String, val label: String
    )
    private val _drillLocation = MutableSharedFlow<DrillLocationEvent>(extraBufferCapacity = 1)
    val drillLocation: SharedFlow<DrillLocationEvent> = _drillLocation.asSharedFlow()
    fun postDrillLocation(lat: Double, lon: Double, source: String, label: String) {
        _drillLocation.tryEmit(DrillLocationEvent(lat, lon, source, label))
    }
}