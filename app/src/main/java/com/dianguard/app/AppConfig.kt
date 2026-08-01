package com.dianguard.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置：参考位置、触发阈值、远震提醒开关等。
 * 通过 SharedPreferences 持久化，首页与后台服务共享同一份实例。
 *
 * 阈值模型（v1.0.9 起）：
 *  - 仅保留「烈度阈值」一项：预估烈度达到该值（含）即触发全屏倒计时告警；
 *  - 烈度档位：2°=轻微有感，3°=明显有感（推荐），4°=强烈有感；
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

    const val DEFAULT_MIN_INTENSITY = 3.0        // 烈度阈值（推荐 3°=明显有感）
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
     * 烈度阈值：预估烈度达到此值（含）才触发全屏告警。
     * 可选 2°(轻微有感) / 3°(明显有感,推荐) / 4°(强烈有感)。
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
     * 见 ThemeManager；默认跟随系统。
     */
    var themeMode: Int
        get() = synchronized(spLock) { sp.getInt("theme_mode", ThemeManager.MODE_SYSTEM) }
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
 * 中国大陆官方地震预警数据源（经 Wolfx 公共聚合转发，免 Key、免费、HTTP/WS 双协议）。
 *
 * 启用源（按用户偏好精简为 3 路）：
 *  - 中国地震台网 (CENC)：覆盖全国，含云南省；
 *  - 四川省地震局：西南片区主力源；
 *  - 重庆市地震局：西南片区主力源。
 *
 * 已移除的源（用户偏好，对大陆用户无增益）：
 *  - 福建省地震局：华东源，与 CENC/川/渝地理重合度高；
 *  - 台湾中央气象署 (CWA)：仅覆盖台湾本岛，距大陆过远、到不了烈度阈值，被过滤；
 *  - 日本气象厅 (JMA)：仅覆盖日本，与国内无关（Wolfx 提供但未启用）。
 *
 * 说明：
 *  - Wolfx 为非官方聚合项目，仅做转发，不生产数据；
 *  - 多源订阅可提升“能收到报文”的概率——同一地震常被多个机构同时发布；
 *  - 服务层按“物理参数”对同一地震做跨源去重，避免重复全屏告警；
 *  - 各源 JSON 字段基本一致，福建/CWA 源曾有的字段差异（如 Magunitude 拼写、MaxIntensity 强弱字符串）
 *    解析层已做容错，留作后路，将来若重新启用无需改解析。
 *
 * 关于“云南省地震局”：该局**没有公开的程序化接口**，公众仅能通过微信小程序、电视及专用终端
 * 接收预警；无法作为 WebSocket 数据源接入。云南省境内地震由中国地震台网(CENC)统一发布，
 * 经本列表首源即可覆盖。
 */
val EEW_SOURCES: List<EewSource> = listOf(
    EewSource("cenc", "中国地震台网 (CENC)", "wss://ws-api.wolfx.jp/cenc_eew"),
    EewSource("sc", "四川省地震局", "wss://ws-api.wolfx.jp/sc_eew"),
    EewSource("cq", "重庆市地震局", "wss://ws-api.wolfx.jp/cq_eew")
)
