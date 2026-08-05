package com.dianguard.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置：参考位置、触发阈值、远震提醒开关等。
 * 通过 SharedPreferences 持久化，首页与后台服务共享同一份实例。
 *
 * 阈值模型（v1.0.9 起）：
 *  - 仅保留「烈度阈值」一项：预估烈度达到该值（含）即触发全屏倒计时告警；
 *  - 烈度档位：2°=轻微有感（推荐/默认），3°=明显有感，4°=强烈有感；
 *  - 去掉了原来的“震级阈值”，避免普通用户不会设置而漏报。
 *
 * 参考位置（家庭/当前位置）：
 *  - 由用户在设置页点击“获取当前位置”抓取设备 GPS/网络定位后写入；
 *  - locationName 保存逆编码得到的“中文详细地址”，只读展示，不可手动修改；
 *  - 后台服务会周期性重新抓取位置写入此处，防止用户移动后距离计算滞后。
 *
 * firstLaunch：首次启动（约等于安装后第一次打开）标记，用于弹出“必需设置/自启动”引导。
 */
object AppConfig {

    // R5 修复：取消“云南普洱”兜底坐标。
    // 原因：未设定参考位置时若沿用兜底坐标，距离/烈度计算全部失真，
    // 会导致针对普洱的误报或对真实震中距的错误估算。未设置位置时
    // 由调用方以 AppConfig.hasLocation 判断，不触发全屏告警（见 EewAlertManager）。

    const val DEFAULT_MIN_INTENSITY = 3.0        // 烈度阈值（默认+推荐 3°=明显有感；R5 修复：由 2° 上调，避免告警疲劳）
    const val DEFAULT_LOCATION_NAME = ""         // 当前定位的中文详细地址

    // 震前预警数据源：以下接口均已迁移到 AppConfig 外的 EEW_SOURCES 列表（多源订阅）。
    // 旧的单源常量保留仅为兼容，请勿继续使用。
    @Deprecated("use EEW_SOURCES")
    const val EEW_WS_URL = "wss://ws-api.wolfx.jp/cenc_eew"

    // S 波传播速度（km/s），用于估算 S 波到达倒计时
    const val S_WAVE_SPEED_KM_S = 3.5

    // 后台服务周期性刷新参考位置的间隔（毫秒）
    const val LOCATION_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

    private const val PREFS = "dianguard_prefs"

    private lateinit var sp: SharedPreferences

    // 修复 #17：SharedPreferences 跨线程并发 edit 理论风险。
    // 虽然 Android 的 SharedPreferences 自身对读写有内部锁保护，但为彻底消除并发写冲突，
    // 这里对所有读写为同一把锁串行化（EewService 的 OkHttp 工作线程与主页主线程都可能写入）。
    private val spLock = Any()

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        migrateThemeToDarkOnce()
    }

    /**
     * 迁移：旧版本可能保存了浅色/跟随系统主题，而用户要求默认深色模式运行。
     * 仅执行一次强制切回深色，之后用户仍可在设置页手动切换。
     */
    private fun migrateThemeToDarkOnce() {
        synchronized(spLock) {
            if (!sp.getBoolean("theme_migrated_dark_v122", false)) {
                sp.edit()
                    .putInt("theme_mode", ThemeManager.MODE_DARK)
                    .putBoolean("theme_migrated_dark_v122", true)
                    .apply()
            }
        }
    }

    // 修复 #16：经纬度改用 String 存储，彻底消除 Double↔Float 精度损失。
    // 同时兼容旧版 Float 存储：首次读取时若新 key 不存在则回退旧 Float 值，避免升级即丢坐标。
    // R5：不再提供兜底坐标——未设定位置时返回 0.0，调用方须以 hasLocation 判断是否可用。
    var homeLat: Double
        get() = synchronized(spLock) {
            sp.getString("home_lat_str", null)?.toDoubleOrNull()
                ?: sp.getFloat("home_lat", 0f).toDouble()
        }
        set(v) = synchronized(spLock) {
            sp.edit().putString("home_lat_str", v.toString()).apply()
        }

    var homeLon: Double
        get() = synchronized(spLock) {
            sp.getString("home_lon_str", null)?.toDoubleOrNull()
                ?: sp.getFloat("home_lon", 0f).toDouble()
        }
        set(v) = synchronized(spLock) {
            sp.edit().putString("home_lon_str", v.toString()).apply()
        }

    /** 用户是否已通过“获取当前位置”写入过有效参考坐标 */
    var hasLocation: Boolean
        get() = synchronized(spLock) { sp.getBoolean("has_location", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("has_location", v).apply() }

    /** 当前定位的中文详细地址（只读展示，由“获取当前位置”写入） */
    var locationName: String
        get() = synchronized(spLock) { sp.getString("location_name", DEFAULT_LOCATION_NAME) ?: DEFAULT_LOCATION_NAME }
        set(v) = synchronized(spLock) { sp.edit().putString("location_name", v.trim()).apply() }

    /**
     * 烈度阈值：用户所在地预估烈度达到此值（含）才触发全屏告警。
     * 机制：EewAlertManager 收到报文后用 estimateSiteIntensity(震级, 震中距, 深度)
     * 估算本地烈度（地震烈度与震级、震源深度、离震中距离相关），再与本阈值比较。
     * 可选 3°(明显有感,推荐/默认) / 2°(轻微有感,高级·易频繁打扰) / 4°(强烈有感)。
     * 注：R5 起默认 3°——2° 虽更灵敏，但全国轻微有感地震频率高，默认 2° 会频繁全屏
     * 打扰用户，长期诱发“告警疲劳”（狼来了效应），反而削弱真实强震时的响应意愿。
     */
    var minIntensity: Double
        get() = synchronized(spLock) { sp.getFloat("min_intensity", DEFAULT_MIN_INTENSITY.toFloat()).toDouble() }
        set(v) = synchronized(spLock) { sp.edit().putFloat("min_intensity", v.toFloat()).apply() }

    var serviceEnabled: Boolean
        get() = synchronized(spLock) { sp.getBoolean("service_enabled", false) }
        set(v) = synchronized(spLock) {
            // 切换预警监听开关时，同步记录/清除“开启监听基准时刻”：
            // - 置 true：写入当前时间，作为「只推送此后发生的地震」的基准（见 EewAlertManager.suppressQuakeNotification）；
            // - 置 false：清零，下次重新开启时重新计时。
            sp.edit()
                .putBoolean("service_enabled", v)
                .putLong("monitor_start_ms", if (v) System.currentTimeMillis() else 0L)
                .apply()
        }

    /**
     * 开启预警监听的基准时刻（epoch 毫秒）。
     * - 由 serviceEnabled 置 true 时自动写入当前时间；
     * - 远震/小震/强震通知派发时，凡「发震时刻 < 此基准」的地震一律不再推送，
     *   避免用户开启监听后收到“很久之前的旧震”而被吓到（用户明确要求）。
     * - 默认 0：表示尚未记录基准（旧版本升级遗留或从未开启），
     *   此时由 EewService.onStartCommand 在 serviceEnabled=true 时补记当前时间。
     */
    var monitorStartMs: Long
        get() = synchronized(spLock) { sp.getLong("monitor_start_ms", 0L) }
        set(v) = synchronized(spLock) { sp.edit().putLong("monitor_start_ms", v).apply() }

    /** 是否首次启动（用于安装后弹出“必需设置 / 自启动”引导） */
    var firstLaunch: Boolean
        get() = synchronized(spLock) { sp.getBoolean("first_launch", true) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("first_launch", v).apply() }

    /** 上次更新检查时间戳（毫秒），用于限制检查频率，避免触发 GitHub API 限流 */
    var lastUpdateCheckMs: Long
        get() = synchronized(spLock) { sp.getLong("last_update_check_ms", 0L) }
        set(v) = synchronized(spLock) { sp.edit().putLong("last_update_check_ms", v).apply() }

    /** 用户已选择“稍后”的版本号；相同版本不再重复弹窗催促 */
    var dismissedUpdateVersion: String
        get() = synchronized(spLock) { sp.getString("dismissed_update_version", "") ?: "" }
        set(v) = synchronized(spLock) { sp.edit().putString("dismissed_update_version", v).apply() }

    /**
     * 主页「数据来源」面板是否收起（v1.3.1+）。
     * - false（默认）：展开，显示每一路源的状态行；
     * - true：收起，只显示标题；下次进入 App 仍保持收起。
     */
    var sourcesCollapsed: Boolean
        get() = synchronized(spLock) { sp.getBoolean("sources_collapsed", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("sources_collapsed", v).apply() }

    /**
     * 深色模式档位（v1.0.17）：0=跟随系统 / 1=浅色 / 2=深色（OLED 纯黑）。
     * 见 ThemeManager；默认强行深色模式运行，避免浅色下文字看不清（用户明确要求）。
     */
    var themeMode: Int
        get() = synchronized(spLock) { sp.getInt("theme_mode", ThemeManager.MODE_DARK) }
        set(v) = synchronized(spLock) { sp.edit().putInt("theme_mode", v).apply() }

    /**
     * 地震历史记录（v1.0.17）：JSON 数组字符串，由 QuakeHistory 读写。
     * 直接存 JSON 而非引入数据库，是为了在老手机上把依赖和体积压到最小。
     */
    var historyJson: String
        get() = synchronized(spLock) { sp.getString("quake_history_v1", "[]") ?: "[]" }
        set(v) = synchronized(spLock) { sp.edit().putString("quake_history_v1", v).apply() }

    /**
     * 融合引擎校准画像持久化（FusionCalibration）：JSON 数组字符串。
     * 仅在「在线学习」开启时才会被写入；默认关闭状态下引擎只做纯查表修正，此值恒为 "[]"。
     */
    var fusionCalibJson: String
        get() = synchronized(spLock) { sp.getString("fusion_calib_v1", "[]") ?: "[]" }
        set(v) = synchronized(spLock) { sp.edit().putString("fusion_calib_v1", v).apply() }

    /**
     * 融合引擎在线自学习开关（默认 false）。
     * 关闭时 FusionCalibration 仅使用内置预训练画像做纯查表修正，不采集样本、不回填真值、
     * 不写 SharedPreferences——对小用户量场景收益趋近于零，故默认关闭以省去开销。
     * 引擎的进化改为在开发机本地用训练管道完成，随版本更新内置到 App。
     */
    var onlineLearningEnabled: Boolean
        get() = synchronized(spLock) { sp.getBoolean("online_learning_enabled", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("online_learning_enabled", v).apply() }

    /**
     * 免责声明与用户协议是否已同意（v1.1.0）。
     * - 首次开启预警监听时若为 false，会弹窗强制要求阅读并同意才能启用；
     * - 在"设置"页可随时点开《免责声明与用户协议》查看完整内容；
     * - 用户卸载重装后此值回到 false，符合法律对"主动确认"的常规要求。
     */
    var disclaimerAccepted: Boolean
        get() = synchronized(spLock) { sp.getBoolean("disclaimer_accepted", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("disclaimer_accepted", v).apply() }

    /**
     * BeeCLD 用户自注册源（v1.3.x 新增，可选）：
     *  - 数据节点 = wss://api.2v8.cn/ws/cea，鉴权 = URL 参数 ?token=<用户 API Key>；
     *  - API Key 由用户自行前往 https://auth.beecld.com/ 注册获取，App 不托管账号、不收集密钥；
     *  - 仅在用户显式启用且已填写有效 token 时，由 EewConnectionManager 动态起连，
     *    关闭或清空 token 后立即断开；App 不内置任何默认密钥，也不在编译期写入 token。
     *  - 解析复用现有 parseExternalEew（BeeCLD 信封 {"Data":{...}} 已支持），无需新建解析器。
     */
    var beecldToken: String
        get() = synchronized(spLock) { sp.getString("beecld_token", "") ?: "" }
        set(v) = synchronized(spLock) { sp.edit().putString("beecld_token", v.trim()).apply() }

    /** BeeCLD 是否启用（用户手动开关；保存 token 时置 true，断开时置 false） */
    var beecldEnabled: Boolean
        get() = synchronized(spLock) { sp.getBoolean("beecld_enabled", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("beecld_enabled", v).apply() }

    /**
     * 根据当前启用状态与 token 计算 BeeCLD 的 WebSocket 地址。
     * 返回空串表示“不应连接”（未启用或 token 为空）；否则为
     * wss://api.2v8.cn/ws/cea?token=<用户 API Key>。
     */
    fun beecldWsUrl(): String {
        if (!beecldEnabled) return ""
        val t = beecldToken
        if (t.isBlank()) return ""
        return "wss://api.2v8.cn/ws/cea?token=${t.trim()}"
    }

    /** 把 S 波到达时间估算为倒计时秒数：**空间震源距** / 波速（含深度修正） */
    fun estimateSWaveEtaSeconds(distanceKm: Double, depthKm: Double = 0.0): Double {
        val spatialDist = if (depthKm > 1.0) {
            kotlin.math.sqrt(distanceKm * distanceKm + depthKm * depthKm)
        } else distanceKm
        return spatialDist / S_WAVE_SPEED_KM_S
    }
}

/**
 * 单个地震预警数据源定义。
 *
 * @param id      内部标识（用于连接管理 / 去重日志）
 * @param name    展示名（官方机构）
 * @param wsUrl   WebSocket 实时推送地址；留空表示非 WebSocket 源（如 ICL 由 IclPoller HTTP 轮询维护）
 * @param headers 连接时附加的 HTTP 头（如 EMSC SockJS 需 Origin）；默认空
 */
data class EewSource(
    val id: String,
    val name: String,
    val wsUrl: String,
    val headers: Map<String, String> = emptyMap()
)

/**
 * 源展示名兜底表：任何 handleRaw / 历史记录传入的 sourceId 都能解析为可读名，
 * 避免回退显示原始 id（如 "icl" / "sc"）。EEW_SOURCES 中的源会优先按列表命中，
 * 此表兜住未列入连接列表的派生源（all_eew 聚合流、备用 HTTP 源等）。
 */
val SOURCE_DISPLAY_NAMES: Map<String, String> = mapOf(
    "cenc" to "中国地震台网 (CENC)",
    "sc" to "四川省地震局 (SC)",
    "cq" to "重庆市地震局 (CQ)",
    "cwa" to "中国台湾地震测报中心 (CWA)",
    "icl" to "中国地震预警网 (ICL)",
    "beecld" to "BeeCLD 地震预警 (用户源)"
)

/**
 * 「监控型」源：仅维持连接心跳与连通性指示，不参与本地告警判定、不发送远震通知、不写入历史。
 * 当前所有源均为中国大陆或对中国大陆有用（含中国台湾），均参与本地告警判定，故本集合为空。
 * 若日后重新接入纯海外独立源（如作为 Wolfx+ICL 同时失效时的兜底心跳），在此登记其 id 即可。
 */
val MONITOR_ONLY_SOURCES: Set<String> = emptySet()

/**
 * 实时「秒级预警」数据源列表（v1.4，仅保留中国大陆及对中国大陆有用的源）。
 *
 * 当前实际拓扑（已剔除全部海外/对中国大陆无用源：JMA / EMSC / p2pquake / USGS）：
 *  - 国内独立机构源（Wolfx 镜像，但分别来自中国地震台网 / 四川省局 / 重庆市局三家机构，
 *    互为 corroboration，西南地区地震可三方互证），均为 WebSocket 实时：
 *      · CENC  wss://ws-api.wolfx.jp/cenc_eew
 *      · 四川局 wss://ws-api.wolfx.jp/sc_eew   （已实测可连接）
 *      · 重庆局 wss://ws-api.wolfx.jp/cq_eew   （已实测可连接）
 *  - 中国台湾地震测报中心 (CWA)：同为 Wolfx 镜像实时源，覆盖台湾海峡地震——此类震源
 *    直接影响中国东南沿海（闽浙），且 CENC 对离岸震源常有延迟或欠报，故作为「邻近区域
 *    实时源」接入并参与本地告警判定（带全字段：经纬度 / 深度 / 震级，app 本地算倒计时+烈度）。
 *    已实测可连接 + 心跳。
 *  - ICL 减灾所官方：经 IclPoller 以 HTTP 3s 轮询独立接入（域名与 Wolfx 完全独立），
 *    不在 WS 列表中（wsUrl 留空），融合引擎同样消费其报文；
 *
 * 说明：FAN Studio（需 API 密钥，已申请被拒）与全部海外源（JMA / EMSC / p2pquake / USGS）均不再内置；
 * BeeCLD·2v8 则改为「用户自注册可选源」接入——App 不内置密钥，由用户在设置页自行前往
 * https://auth.beecld.com/ 注册获取 API Key 后填写启用（见下方 beecld 条目）。
 *
 * 注：ICL 以「空白 wsUrl」登记，目的有二：
 *   1) 让主页数据源状态区出现独立的 ICL 行；
 *   2) 让告警历史里的 sourceName 正确解析为「中国地震预警网 (ICL)」，不再回退显示原始 "icl"。
 * 其连接由 IclPoller 以 HTTP 3s 轮询独立维护，EewConnectionManager.connectAll() 会跳过空白 wsUrl，
 * 连接状态由 IclPoller 经 EewService.patchSourceState("icl", ...) 上报。
 */
val EEW_SOURCES: List<EewSource> = listOf(
    EewSource("cenc", "中国地震台网 (CENC)", "wss://ws-api.wolfx.jp/cenc_eew"),
    // 四川 / 重庆省级地震局（Wolfx 镜像，独立机构源，已实测可连接）——与 CENC 互为国内 corroboration
    EewSource("sc", "四川省地震局 (SC)", "wss://ws-api.wolfx.jp/sc_eew"),
    EewSource("cq", "重庆市地震局 (CQ)", "wss://ws-api.wolfx.jp/cq_eew"),
    // 中国台湾地震测报中心 (CWA)：邻近区域实时源，影响中国东南沿海，参与本地告警判定
    EewSource("cwa", "中国台湾地震测报中心 (CWA)", "wss://ws-api.wolfx.jp/cwa_eew"),
    // ICL（减灾所官方）HTTP 轮询源；wsUrl 留空 = 非 WebSocket 源，由 IclPoller 维护连通性
    EewSource("icl", "中国地震预警网 (ICL)", ""),
    // BeeCLD·2v8（用户自注册可选源）：wsUrl 留空 = 动态源；仅在用户于设置页填好 token 并启用后，
    // 由 EewConnectionManager 按 AppConfig.beecldWsUrl() 动态起连（wss://api.2v8.cn/ws/cea?token=...）。
    // 数据节点 api.2v8.cn 未在 HttpClient 证书固定表中，连接时走系统信任链，证书固定逃生舱不对其计数。
    EewSource("beecld", "BeeCLD 地震预警 (用户源)", "")
)
