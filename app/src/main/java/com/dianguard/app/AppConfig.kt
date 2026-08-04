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

    // 仅用于首次启动、且用户未授权定位时的兜底坐标（云南普洱·思茅）
    const val FALLBACK_HOME_LAT = 22.78
    const val FALLBACK_HOME_LON = 100.97

    const val DEFAULT_MIN_INTENSITY = 2.0        // 烈度阈值（默认+推荐 2°=轻微有感；用户明确要求默认 2 度，降低漏报风险）
    const val DEFAULT_DISTANT_NOTIFY = false     // 远震小震提醒（通知栏），默认关
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
    var homeLat: Double
        get() = synchronized(spLock) {
            sp.getString("home_lat_str", null)?.toDoubleOrNull()
                ?: sp.getFloat("home_lat", FALLBACK_HOME_LAT.toFloat()).toDouble()
        }
        set(v) = synchronized(spLock) {
            sp.edit().putString("home_lat_str", v.toString()).apply()
        }

    var homeLon: Double
        get() = synchronized(spLock) {
            sp.getString("home_lon_str", null)?.toDoubleOrNull()
                ?: sp.getFloat("home_lon", FALLBACK_HOME_LON.toFloat()).toDouble()
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
     * 可选 2°(轻微有感,推荐/默认) / 3°(明显有感) / 4°(强烈有感)。
     */
    var minIntensity: Double
        get() = synchronized(spLock) { sp.getFloat("min_intensity", DEFAULT_MIN_INTENSITY.toFloat()).toDouble() }
        set(v) = synchronized(spLock) { sp.edit().putFloat("min_intensity", v.toFloat()).apply() }

    // —— 远震小震提醒（通知栏轻提示） ——
    var distantNotify: Boolean
        get() = synchronized(spLock) { sp.getBoolean("distant_notify", DEFAULT_DISTANT_NOTIFY) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("distant_notify", v).apply() }

    var serviceEnabled: Boolean
        get() = synchronized(spLock) { sp.getBoolean("service_enabled", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("service_enabled", v).apply() }

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
     * 免责声明与用户协议是否已同意（v1.1.0）。
     * - 首次开启预警监听时若为 false，会弹窗强制要求阅读并同意才能启用；
     * - 在"设置"页可随时点开《免责声明与用户协议》查看完整内容；
     * - 用户卸载重装后此值回到 false，符合法律对"主动确认"的常规要求。
     */
    var disclaimerAccepted: Boolean
        get() = synchronized(spLock) { sp.getBoolean("disclaimer_accepted", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("disclaimer_accepted", v).apply() }

    /** 把 S 波到达时间估算为倒计时秒数：距离 / 波速 */
    fun estimateSWaveEtaSeconds(distanceKm: Double): Double =
        distanceKm / S_WAVE_SPEED_KM_S
}

/**
 * 单个地震预警数据源定义。
 *
 * @param id    内部标识（用于连接管理 / 去重日志）
 * @param name  展示名（官方机构）
 * @param wsUrl WebSocket 实时推送地址
 */
data class EewSource(
    val id: String,
    val name: String,
    val wsUrl: String
)

/**
 * BeeCLD·2v8 令牌（auth.beecld.com 免费注册获取，形如 wat_xxx），仅用于 api.2v8.cn 的
 * 中国地震预警网 (CEA) 国家级秒级预警源。留空时该连接会被服务端拒绝（4401），但不影响其它源。
 * 注意：FAN Studio 源无需令牌，可直接使用。
 *
 * 令牌值由构建系统注入 BuildConfig.BEECLD_TOKEN（来源：gradle.properties 或 local.properties），
 * 不再硬编码于源码中，避免 APK 反编译后直接提取。
 */
@Suppress("DEPRECATION") // BuildConfig.BEECLD_TOKEN 由 buildConfigField 生成，无替代方案
val BEECLD_TOKEN: String = BuildConfig.BEECLD_TOKEN

/**
 * FAN Studio 鉴权（ws.fanstudio.tech/cea 现已改为需 API 密钥，不再免密）。
 * appId + key（sk- 开头）从 FAN Studio 开发者平台获取；两者均留空时该连接不发起，
 * 也不会空转重连（服务端会以 1008 关闭）。仅在两者都非空时，`fanstudio_cea` 才会在
 * onOpen 后 5 秒内发送 {"type":"auth","appId":...,"key":...} 鉴权帧。
 */
const val FAN_APP_ID: String = ""
const val FAN_KEY: String = ""

/**
 * 中国大陆国家级「秒级预警」数据源（即地震发生瞬间推送、客户端据此全屏告警的 EEW）。
 *
 * 设计目标：去单点。所有源最终都指向【中国地震预警网 (CEA) / 中国地震台网 (CENC)】同套国家级
 * 预警数据，但来自**相互独立的域名/主机**，任一主机宕机/被墙/解析失败，其余仍在线：
 *  - Wolfx (ws-api.wolfx.jp)：历史最久的非官方聚合转发，免 Key；
 *  - FAN Studio (ws.fanstudio.tech)：独立主机，免 Key，专发 CEA 秒级预警；
 *  - BeeCLD·2v8 (api.2v8.cn)：独立主机（beecld.com 的国内镜像），需免费令牌。
 *
 * 启用源（均为秒级预警，不含速报/测定）：
 *  - cenc / sc / cq：Wolfx 转发的国家级 + 川渝区域预警；
 *  - fanstudio_cea：FAN Studio 的国家级 CEA 秒级预警（独立主机）；
 *  - bee_cea：BeeCLD·2v8 的国家级 CEA 秒级预警（独立主机）。
 *
 * 说明：
 *  - 多源订阅提升“能收到报文”的概率——同一地震常被多个机构同时发布；
 *  - 服务层按「物理参数」跨源去重，避免重复全屏告警；
 *  - 第三方聚合源（FAN Studio / BeeCLD）字段与 Wolfx 不同，统一由 parseExternalEew 容错解析。
 *
 * 关于「云南省地震局」：该局无公开程序化接口，境内地震由 CENC 统一发布，已含于 cenc 源。
 */
val EEW_SOURCES: List<EewSource> = listOf(
    // Wolfx 聚合转发（免 Key，单一域名单点——仅作其中一路，不依赖它独撑）
    EewSource("cenc", "中国地震台网 (CENC)", "wss://ws-api.wolfx.jp/cenc_eew"),
    EewSource("sc", "四川省地震局", "wss://ws-api.wolfx.jp/sc_eew"),
    EewSource("cq", "重庆市地震局", "wss://ws-api.wolfx.jp/cq_eew"),
    // FAN Studio 现已需 API 密钥：未配置 appId/key 时 URL 置空，connectAll 会跳过，避免空转重连
    EewSource(
        "fanstudio_cea", "中国地震预警网 (FAN Studio)",
        if (FAN_APP_ID.isNotEmpty() && FAN_KEY.isNotEmpty()) "wss://ws.fanstudio.tech/cea" else ""
    ),
    EewSource(
        "bee_cea", "中国地震预警网 (BeeCLD·2v8)",
        "wss://api.2v8.cn/ws/cea" + if (BEECLD_TOKEN.isNotEmpty()) "?token=$BEECLD_TOKEN" else ""
    )
)
