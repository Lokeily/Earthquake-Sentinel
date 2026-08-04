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
 * 中国大陆国家级「秒级预警」WebSocket 数据源列表。
 *
 * 当前实际拓扑（v1.3.0，R5 审查确认）：
 *  - WebSocket 实时源：Wolfx CENC（wss://ws-api.wolfx.jp/cenc_eew）——秒级推送；
 *  - ICL 减灾所官方：经 IclPoller 以 HTTP 3s 轮询独立接入（域名与 Wolfx 完全独立），
 *    不在本列表中，融合引擎会同时消费两类报文（Wolfx WS + ICL HTTP + 备用源）；
 *  - Project Podris（免鉴权聚合源）：parsePodrisEew 解析器已就绪并接入 handleRaw 回退链，
 *    待取得其 WebSocket 地址后在此追加一条 EewSource 即可启用（零代码改动）。
 *
 * 说明：FAN Studio（需 API 密钥）与 BeeCLD·2v8（令牌已改由用户自行填写）均不再内置，
 * 故本列表不再包含二者条目。
 */
val EEW_SOURCES: List<EewSource> = listOf(
    EewSource("cenc", "中国地震台网 (CENC)", "wss://ws-api.wolfx.jp/cenc_eew")
    // ICL（减灾所官方）通过 IclPoller HTTP 轮询独立工作，不在 WebSocket 列表中
)
