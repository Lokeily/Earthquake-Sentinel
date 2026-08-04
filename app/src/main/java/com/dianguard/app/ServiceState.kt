package com.dianguard.app

/**
 * 服务全局状态快照（v1.2.0：从 companion 分散字段集中为不可变 data class）。
 *
 * 所有字段均只读；外部通过 EewService.state 获取最新快照。
 * 写入仅由 EewService 内部通过 updateState { copy(...) } 完成，
 * 保证状态变更的原子性和可追溯性。
 */
data class ServiceState(
    val connectedSourceCount: Int = 0,
    val wakeLockHeld: Boolean = false,
    val lastStatusText: String = "监听未开启",
    val headlineState: String = "监听未开启",
    val sourceStatuses: List<SourceUiState> = emptyList(),
    val backupActive: Boolean = false,
    val backupNote: String = "待命中"
)
