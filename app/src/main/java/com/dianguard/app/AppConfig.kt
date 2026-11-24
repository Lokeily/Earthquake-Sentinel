package com.dianguard.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置：参考位置、触发阈值、远震提醒开关等。
 * 通过 SharedPreferences 持久化，首页与后台服务共享同一份实例。
 *
 * 阈值模型（v1.4.x 起，基于 ICL 烈度阈值策略，不启用震级触发条件）：
 *  - 仅以「用户所在地烈度（由距震中距离计算）」作为唯一触发条件；
 *  - 烈度阈值分昼夜：白天（6:00–22:00）/ 夜间（22:00–次日6:00）；
 *  - 参考 ICL 官方口径（365icl.com 科普页 + 微博 2020-02-17）：
 *      · 推荐烈度阈值：白天 2 度，夜间 3 度；
 *      · 不启用震级触发条件（与 ICL 官方策略的唯一差异——Dianguard 只做
 *        用户所在地烈度触发，不依赖全国网震级，避免大震远距被震级门槛挡掉）。
 *  - 历史版本包含「震级+烈度双阈值」（v1.4.0 过渡版）或「仅烈度单阈值」
 *    （v1.0.9–v1.3.x），升级时自动迁移旧值。
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

    // ===== 烈度阈值（昼夜分设，基于 ICL 推荐值，不启用震级触发条件） =====
    // 参考：https://www.365icl.com/View.asp?Pid=307（科普页）
    //       微博 @成都高新减灾研究所 2020-02-17（官方说明）
    // 说明：Dianguard 仅以「用户所在地烈度（由距震中距离计算）」作为唯一触发条件，
    //       不启用震级阈值。这是与 ICL 官方双阈值策略的唯一差异——纯烈度触发
    //       避免大震远距被震级门槛挡掉，保护远距离用户。
    const val DEFAULT_MIN_INTENSITY_DAY = 2.0    // 白天烈度阈值（ICL 推荐 2 度）
    const val DEFAULT_MIN_INTENSITY_NIGHT = 3.0  // 夜间烈度阈值（ICL 推荐 3 度）
    /** 白天起始小时（含）：6:00–22:00 为白天（与 Eew.kt ICL_DAY_START_HOUR 一致） */
    const val DAY_START_HOUR = ICL_DAY_START_HOUR
    /** 白天结束前小时（不含）：22:00–次日6:00 为夜间（与 Eew.kt ICL_NIGHT_START_HOUR 一致） */
    const val NIGHT_START_HOUR = ICL_NIGHT_START_HOUR

    // 旧版单烈度阈值默认值（v1.3.x 及以前，含 v1.4.0 过渡版双阈值），用于升级迁移
    const val DEFAULT_MIN_INTENSITY_LEGACY = 3.0

    const val DEFAULT_LOCATION_NAME = ""         // 当前定位的中文详细地址

    // 震前预警数据源：以下接口均已迁移到 AppConfig 外的 EEW_SOURCES 列表（多源订阅）。
    // 旧的单源常量保留仅为兼容，请勿继续使用。
    @Deprecated("use EEW_SOURCES")
    const val EEW_WS_URL = "wss://ws-api.wolfx.jp/cenc_eew"

    // S 波传播速度（km/s），用于估算 S 波到达倒计时
    const val S_WAVE_SPEED_KM_S = 3.5

    /** 协议版本号：重大更新（如新增《用户服务协议》）时递增，低于此版本的用户会被要求重新阅读并同意 */
    const val DISCLAIMER_VERSION = 2

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
        migrateLegacyThresholdOnce()
        migrateThemeToDarkOnce()
    }

    /**
     * 一次性迁移（v1.4.x 起）：兼容两代旧格式——
     *  v1.4.0 过渡版：含 min_magnitude + min_intensity_day/night（双阈值）；
     *  v1.3.x 及以前：仅 min_intensity（单烈度阈值）。
     * 迁移目标（v1.4.1 起）：仅保留烈度双阈值，震级阈值不再参与触发。
     *  - 已有 min_intensity_day/night → 保留用户设置；
     *  - 仅 min_intensity → 用旧值回填白天/夜间烈度；
     *  - min_magnitude 键不再读取（无触发器引用，残留无害，抹除避免混淆）。
     * 迁移完成后写入标记，避免反复迁移覆盖用户后续的新设置。
     */
    private fun migrateLegacyThresholdOnce() {
        synchronized(spLock) {
            if (sp.getBoolean("threshold_migrated_v141", false)) return
            val hasNewDay = sp.contains("min_intensity_day")
            val hasNewNight = sp.contains("min_intensity_night")
            val hasLegacy = sp.contains("min_intensity")
            if (!hasNewDay && !hasNewNight && hasLegacy) {
                val legacy = sp.getFloat("min_intensity", DEFAULT_MIN_INTENSITY_LEGACY.toFloat()).toDouble()
                sp.edit()
                    .putFloat("min_intensity_day", legacy.toFloat())
                    .putFloat("min_intensity_night", legacy.toFloat())
                    .apply()
            }
            // 清除过渡版震级阈值残留（v1.4.0 曾写入）
            sp.edit().remove("min_magnitude").apply()
            sp.edit().putBoolean("threshold_migrated_v141", true).apply()
        }
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

    // ===================== 演练参考位置覆盖（v1.4.6，仅 debug 演练包） =====================
    // 演示机常在虚拟机/实验环境，GPS 拿到的是假位置，无法用于演练。
    // 这里在【配置存取层】加一层覆盖：debug 包手动输入或后台下发坐标后，
    // homeLat / homeLon / hasLocation / locationName 全部优先返回演练坐标，
    // 因此距离、烈度、S 波倒计时、方位等所有调用点无需任何修改即自动生效；
    // 自动定位仍可照常写入 SharedPreferences，但读取时被覆盖，不会污染演练。
    var drillLocationOverride: Boolean
        get() = synchronized(spLock) { sp.getBoolean("drill_loc_override", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("drill_loc_override", v).apply() }

    /** 演练参考位置（String 存储，避免 Float 精度损失） */
    var drillOverrideLat: Double
        get() = synchronized(spLock) { sp.getString("drill_lat_str", null)?.toDoubleOrNull() ?: 0.0 }
        set(v) = synchronized(spLock) { sp.edit().putString("drill_lat_str", v.toString()).apply() }

    var drillOverrideLon: Double
        get() = synchronized(spLock) { sp.getString("drill_lon_str", null)?.toDoubleOrNull() ?: 0.0 }
        set(v) = synchronized(spLock) { sp.edit().putString("drill_lon_str", v.toString()).apply() }

    /** 位置来源：manual（手机手动输入）/ console（后台下发）/ gps */
    var drillLocationSource: String
        get() = synchronized(spLock) { sp.getString("drill_loc_source", "gps") ?: "gps" }
        set(v) = synchronized(spLock) { sp.edit().putString("drill_loc_source", v).apply() }

    /** 参考位置展示名（后台下发时带地名） */
    var drillLocationLabel: String
        get() = synchronized(spLock) { sp.getString("drill_loc_label", "演练参考位置") ?: "演练参考位置" }
        set(v) = synchronized(spLock) { sp.edit().putString("drill_loc_label", v).apply() }

    /** 覆盖是否生效：仅 debug + 显式开关 + 坐标合法（Java 监视器可重入，嵌套 synchronized 安全） */
    val drillLocationActive: Boolean
        get() = BuildConfig.IS_DEBUG && drillLocationOverride &&
            drillOverrideLat in -90.0..90.0 && drillOverrideLon in -180.0..180.0 &&
            (drillOverrideLat != 0.0 || drillOverrideLon != 0.0)

    /** 后台控制帧或手机手动输入的统一写入口，写完广播供设置页刷新 */
    fun setDrillLocation(lat: Double, lon: Double, source: String, label: String = "演练参考位置") {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        synchronized(spLock) {
            sp.edit()
                .putString("drill_lat_str", lat.toString())
                .putString("drill_lon_str", lon.toString())
                .putBoolean("drill_loc_override", true)
                .putString("drill_loc_source", source)
                .putString("drill_loc_label", label)
                .apply()
        }
        AppEvents.postDrillLocation(lat, lon, source, label)
    }

    // 修复 #16：经纬度改用 String 存储，彻底消除 Double↔Float 精度损失。
    // 同时兼容旧版 Float 存储：首次读取时若新 key 不存在则回退旧 Float 值，避免升级即丢坐标。
    // R5：不再提供兜底坐标——未设定位置时返回 0.0，调用方须以 hasLocation 判断是否可用。
    var homeLat: Double
        get() = synchronized(spLock) {
            if (drillLocationActive) drillOverrideLat
            else sp.getString("home_lat_str", null)?.toDoubleOrNull()
                ?: sp.getFloat("home_lat", 0f).toDouble()
        }
        set(v) = synchronized(spLock) {
            sp.edit().putString("home_lat_str", v.toString()).apply()
        }

    var homeLon: Double
        get() = synchronized(spLock) {
            if (drillLocationActive) drillOverrideLon
            else sp.getString("home_lon_str", null)?.toDoubleOrNull()
                ?: sp.getFloat("home_lon", 0f).toDouble()
        }
        set(v) = synchronized(spLock) {
            sp.edit().putString("home_lon_str", v.toString()).apply()
        }

    /** 用户是否已通过“获取当前位置”写入过有效参考坐标；演练覆盖启用时恒为 true */
    var hasLocation: Boolean
        get() = synchronized(spLock) {
            if (drillLocationActive) true else sp.getBoolean("has_location", false)
        }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("has_location", v).apply() }

    /** 当前定位的中文详细地址（只读展示，由“获取当前位置”写入） */
    var locationName: String
        get() = synchronized(spLock) {
            if (drillLocationActive) drillLocationLabel
            else sp.getString("location_name", DEFAULT_LOCATION_NAME) ?: DEFAULT_LOCATION_NAME
        }
        set(v) = synchronized(spLock) { sp.edit().putString("location_name", v.trim()).apply() }

    /**
     * 白天烈度阈值（6:00–22:00）：用户所在地预估烈度达到此值（含）才可能触发全屏告警。
     * ICL 推荐白天 2 度。可选档位：2/3/4。
     */
    var minIntensityDay: Double
        get() = synchronized(spLock) { sp.getFloat("min_intensity_day", DEFAULT_MIN_INTENSITY_DAY.toFloat()).toDouble() }
        set(v) = synchronized(spLock) { sp.edit().putFloat("min_intensity_day", v.toFloat()).apply() }

    /**
     * 夜间烈度阈值（22:00–次日6:00）：用户所在地预估烈度达到此值（含）才可能触发全屏告警。
     * ICL 推荐夜间 3 度。可选档位：2/3/4。
     */
    var minIntensityNight: Double
        get() = synchronized(spLock) { sp.getFloat("min_intensity_night", DEFAULT_MIN_INTENSITY_NIGHT.toFloat()).toDouble() }
        set(v) = synchronized(spLock) { sp.edit().putFloat("min_intensity_night", v.toFloat()).apply() }

    /**
     * 按当前时段返回烈度阈值（白天/夜间）。
     * 白天 6:00–22:00（含），夜间 22:00–次日6:00。
     * 委托 Eew.kt 顶层纯函数 iclIntensityThreshold（单一事实来源，可单元测试）。
     */
    fun currentMinIntensity(): Double {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return iclIntensityThreshold(hour, minIntensityDay, minIntensityNight)
    }

    /**
     * 纯烈度触发判定：用户所在地预估烈度 ≥ 当前时段阈值（白天/夜间）。
     * 不启用震级触发条件（与 ICL 官方双阈值策略的唯一差异，见类头注释）。
     * 委托 Eew.kt 顶层纯函数 iclIntensityTriggered（单一事实来源，可单元测试）。
     * @param siteIntensity 用户所在地预估烈度（由距震中距离计算）
     * @return true=应触发全屏倒计时
     */
    fun alertTriggered(siteIntensity: Double): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return iclIntensityTriggered(siteIntensity, hour, minIntensityDay, minIntensityNight)
    }

    /**
     * @deprecated v1.4.x 起改用 minIntensityDay/minIntensityNight + currentMinIntensity()。
     * 保留此属性仅为兼容旧版调用方（如设置页回显），新代码请勿使用。
     */
    @Deprecated("use currentMinIntensity() instead")
    var minIntensity: Double
        get() = currentMinIntensity()
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
     * 预警回放历史记录（测试版专用，v1.4.0）：JSON 数组字符串，由 ReplayHistory 读写。
     * 独立于 quake_history_v1（7天/100条），回放数据保留 365 天 / 500 条，
     * 供「预警回放」页面浏览并完整重现历史预警流程。
     */
    var replayHistoryJson: String
        get() = synchronized(spLock) { sp.getString("replay_history_v1", "[]") ?: "[]" }
        set(v) = synchronized(spLock) { sp.edit().putString("replay_history_v1", v).apply() }

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

    /** 用户已同意的协议版本号；低于 DISCLAIMER_VERSION 时需重新阅读并同意（协议重大更新） */
    var disclaimerAcceptedVersion: Int
        get() = synchronized(spLock) { sp.getInt("disclaimer_version", 0) }
        set(v) = synchronized(spLock) { sp.edit().putInt("disclaimer_version", v).apply() }

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

    // ===================== 演练控制台（v1.4.5 起，仅 debug 演练包可用） =====================
    // 配套 Sentinel Console 后台：演示机在「更多设置 → 演练控制台」填入后台生成的
    // ws://<局域网IP>:8787/ws/cea?token=...&device=... 接入地址后，以独立 sourceId
    // "drill" 订阅该通道。后台只向白名单设备下发带 drill 标记的报文，release 正式包
    // 不含此源、不开放明文流量，因此普通用户没有任何途径收到演练数据。
    /** 演练通道完整 WebSocket 地址（由后台「接入地址」复制而来，不做任何默认主机拼接） */
    var drillEndpoint: String
        get() = synchronized(spLock) { sp.getString("drill_endpoint", "") ?: "" }
        set(v) = synchronized(spLock) { sp.edit().putString("drill_endpoint", v.trim()).apply() }

    /** 演练通道是否启用（默认关闭；关闭时不发起任何连接） */
    var drillEnabled: Boolean
        get() = synchronized(spLock) { sp.getBoolean("drill_enabled", false) }
        set(v) = synchronized(spLock) { sp.edit().putBoolean("drill_enabled", v).apply() }

    /**
     * 计算演练通道地址。三重保险：
     *  ① 仅 debug 构建返回非空（release 包编译期常量为 false，代码路径被裁剪）；
     *  ② 必须用户显式启用；
     *  ③ 必须是完整 ws:// / wss:// 地址，杜绝误填 token 被拼到公网主机。
     */
    fun drillWsUrl(): String {
        if (!BuildConfig.IS_DEBUG || !drillEnabled) return ""
        val url = drillEndpoint
        if (!url.startsWith("ws://", ignoreCase = true) &&
            !url.startsWith("wss://", ignoreCase = true)) return ""
        return url
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
    val headers: Map<String, String> = emptyMap(),
    /**
     * 是否为官方直接渠道（v1.4.3）。
     * - true：官方机构自有域名/直连（如 ICL 减灾所官方预警网）。
     * - false：第三方转发官方数据（如 wolfx 镜像 cenc/sc/cq/cwa）或用户自建源（beecld）。
     * 主页据此给官方源打「官方」标签；转发源异常时若官方源仍可用，显示安抚文案而非惊悚的「源异常」。
     */
    val isOfficial: Boolean = false
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
    "beecld" to "BeeCLD 地震预警 (用户源)",
    "drill" to "Sentinel 演练控制台 (模拟)"
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
    // ICL（减灾所官方）HTTP 轮询源：官方预警发布机构直连域名，isOfficial=true；wsUrl 留空 = 非 WebSocket 源，由 IclPoller 维护连通性
    EewSource("icl", "中国地震预警网 (ICL)", "", isOfficial = true),
    // BeeCLD·2v8（用户自注册可选源）：wsUrl 留空 = 动态源；仅在用户于设置页填好 token 并启用后，
    // 由 EewConnectionManager 按 AppConfig.beecldWsUrl() 动态起连（wss://api.2v8.cn/ws/cea?token=...）。
    // 数据节点 api.2v8.cn 未在 HttpClient 证书固定表中，连接时走系统信任链，证书固定逃生舱不对其计数。
    EewSource("beecld", "BeeCLD 地震预警 (用户源)", ""),
    // 演练控制台（v1.4.5）：仅 debug 演练包可见的动态源，wsUrl 留空由 EewConnectionManager
    // 按 AppConfig.drillWsUrl() 起连。release 正式包完全不含此条目（编译期常量裁剪），
    // 因而正式用户的主页、源状态、告警链路都不存在演练通道。
) + if (BuildConfig.IS_DEBUG) listOf(
    EewSource("drill", "Sentinel 演练控制台 (模拟)", "")
) else emptyList()
