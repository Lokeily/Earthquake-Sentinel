# 地震哨兵 (Dianguard) · Earthquake Sentinel

> **多源融合地震预警引擎** —— 将一台旧手机变成守护家人的秒级预警哨兵。

🌐 **官网**：[lokeily.github.io/dianguard-site](https://lokeily.github.io/dianguard-site/)　|　📦 **最新版**：[v1.3.0](https://github.com/Lokeily/Earthquake-Sentinel/releases)

---

## 项目简介

Dianguard 是一款面向中国大陆的**个人级地震预警 Android 应用**，目标场景是"插上旧手机当私人预警哨兵"。软件实时订阅国家级地震预警（EEW）数据，在破坏性地震波（S 波）到达前数秒至数十秒，以**全屏锁屏告警 + 最大音量语音 + 震动**组合强制唤醒用户，争取宝贵的逃生窗口。

- **minSdk 21 / targetSdk 34**，全 Kotlin 实现，适配 Android 5.0+ 旧机型长期常驻
- **零第三方 SDK**：仅 AndroidX + OkHttp + org.json，攻击面最小化，无任何数据上报
- **70 项单元测试**全通过，覆盖解析 / 烈度衰减 / 多源融合 / 跨源去重等核心逻辑

---

## 核心架构

Dianguard 采用"**双实时源 + 多源融合 + 分区烈度衰减 + 渐进式响应**"四层架构，底层物理模型基于 GB 18306-2015《中国地震动参数区划图》，上层决策由自研 **EewFusion 融合引擎**驱动。

```
实时数据源（域名完全独立，双路冗余）
  ├─ Wolfx CENC WebSocket  ── wss://ws-api.wolfx.jp（秒级推送）
  └─ ICL 减灾所官方 HTTP   ── mobile-new.chinaeew.cn（3s 轮询，官方源）
        │
        ▼
  解析层：parseEew / parseExternalEew / parsePodrisEew / parseIclEew
        │
        ▼
  EewFusion 多源融合引擎 ── 加权中位数 · 坐标选优 · 动态趋势积分 · 1.5s 融合窗
        │
        ▼
  GB 18306-2015 分区烈度衰减 ── 6 分区 + 边界防抖 + 0.5σ 安全边际
        │
        ▼
  AlertActivity ── 全屏锁屏 · 四级真实语音 · 倒计时 · 震级渐进升级
        │
        ▼
  备用探测源（主链路全断时激活）── USGS + Wolfx HTTP + EMSC，30s 轮询
```

---

## 数据源拓扑（v1.3.0 实测）

| 源 | 协议 | 鉴权 | 延迟 | 独立性 | 说明 |
|------|------|------|------|--------|------|
| Wolfx CENC | WebSocket | 免 | ~0.5s | — | 主实时源，秒级推送 |
| ICL 成都高新减灾所 | HTTP 3s 轮询 | 免 | 1–4s | ✅ 域名完全独立 | 中国官方预警系统，31 省覆盖 |
| USGS FDSN | HTTP 30s | 免 | 震后分钟级 | ✅ | 备用探测源（震后速报，非实时） |
| EMSC FDSN | HTTP 30s | 免 | 震后分钟级 | ✅ | 备用探测源（独立第三源） |
| Project Podris | 待接入 | 免 | — | ✅ | 解析器已就绪并接入调用链，待取得 wsUrl |

> ⚠️ **单点风险治理**：v1.3.0 已移除 BeeCLD·2v8（令牌改由用户自行填写）与 FAN Studio（需 API 密钥）。当前 Wolfx WebSocket + ICL HTTP 轮询**域名完全独立**，任一可达即触发，双源报文共同进入 EewFusion 交叉验证。

---

## 核心算法

### EewFusion — 多源融合决策引擎

借鉴集成学习（Ensemble Learning）思想，将每个数据源视为独立"专家"，在 **1.5s 融合窗口**内收集多源报文后加权融合，降低单源误报风险：

| 组件 | 算法 | 说明 |
|------|------|------|
| 震级融合 | 加权中位数 | 比算术平均更抗异常值，单个源偏差异常不主导结果 |
| 震中坐标 | Centroid Selection | 取权重最高源的原始坐标，严禁加权平均（避免物理意义错误） |
| 置信度 | 源数因子×0.6 + 一致性因子×0.4 | 多源一致 → 高置信；单源初报 → 低置信 + `isPreliminary` 标记（禁止触发红色） |
| 权重更新 | 动态趋势积分 | 连续 2 次高于共识 → 权重 +10%（震级爬坡期奖励）；连续 3 次不变 → 归零；单事件加成封顶 ±20% |
| 坐标防抖 | 15km 死区 | 新坐标偏离上次输出 < 15km 则沿用旧坐标，防止倒计时高频抖动 |
| 版本隔离 | 按 quakeKey 独立递增 | 不同地震事件版本号不互串，余震不会覆盖主震状态 |
| 超时兜底 | 1.6s 强制输出 | 融合窗口到期后强制输出结果，防止网络抖动导致事件永久挂起 |

### GB 18306-2015 — 分区烈度衰减模型

放弃全国单一公式，改用第五代地震区划图的六分区短轴衰减模型（含震中坐标自动分区选择）：

```
I = A + B·M + C·lg(R + R₀) + 0.5σ
```

| 分区 | 覆盖范围 | 短轴系数 (A, B, C, R₀) | σ |
|------|---------|----------------------|-----|
| 川滇西南 | 云南全境+川西 | 2.941, 1.363, -1.494, 7 | 0.67 |
| 四川盆地 | 成都/重庆周边 | 3.456, 1.280, -1.608, 7 | 0.61 |
| 青藏区 | 青藏高原+甘南 | 3.368, 1.275, -3.312, 9 | 0.66 |
| 新疆区 | 天山南北 | 3.611, 1.435, -3.848, 13 | 0.59 |
| 东部强震区 | 华北+东北 | 3.659, 1.363, -3.541, 13 | 0.58 |
| 中强地震区 | 华南+华中 | 3.944, 1.071, -2.845, 7 | 0.52 |

**关键特性**：
- 云南深源地震区（depth > 60km）：启用郁曙君 1993 特殊公式
- 边界防抖：震中距分区边界 < 15km 时双区并行计算取 MAX
- 安全边际：触发阈值采用 +0.5σ 偏上分位数，宁可高报不低报
- 倒计时修正：S 波到达时间使用**空间震源距**（√(震中距² + 深度²)），深源地震不倒计时假警报
- 无效值拦截：M < 3.0 或 R < 0 直接返回 -1，跳过触发

### 告警触发与响应

```
本地预估烈度 ≥ 阈值(3°/2°/4°) ──→ 融合引擎决策 ──→ 全屏锁屏弹出
        │
        ▼
  四级分级（按用户所在地烈度）：红≥8° · 橙6-8° · 黄4-6° · 蓝2-4°
        │
        ▼
  三段式真实语音（大陆预警录音，非 TTS）：
    短语循环 → 逐秒倒计时 → 警报循环
        │
        ▼
  归零后显示避险指引：「趴下、掩护、抓牢 · 远离玻璃窗与重物」
```

---

## 安全架构

### 证书固定（Certificate Pinning）

所有预警/历史数据链路均实施 **SPKI 证书固定**（leaf + 中间 CA 双 pin），防中间人注入虚假地震预警：

| 域名 | 用途 | 固定方式 |
|------|------|---------|
| `ws-api.wolfx.jp` / `api.wolfx.jp` | 实时 EEW + 历史主源 | leaf + Google Trust Services WE1 |
| `mobile-new.chinaeew.cn` | ICL 官方轮询源 | leaf + WoTrus RSA DV SSL CA 2 |
| `earthquake.usgs.gov` | 备用源 | leaf + DigiCert Global G2 |
| `www.seismicportal.eu` | 备用源 | leaf + Let's Encrypt YR2 |
| `api.bigdatacloud.net` | 逆地理编码 | leaf + Thawte TLS RSA CA G1 |

> 即使攻击者控制网络层，也无法用伪 CA 证书冒充数据源。leaf 轮换由中间 CA pin 兜底，不中断预警链路。

### 防伪造 / 防重放

- **传输层**：证书固定防中间人（上表）
- **应用层**：EewFusion 多源交叉验证——单源伪造报文会被加权中位数 / 置信度机制抑制，双源以上一致才触发高等级告警
- **去重层**：10 分钟跨源去重窗口（quakeKey 量化归并），同事件多报不重复弹窗

### 密钥管理

- 签名密钥 `*.jks` / `local.properties` 均已 `.gitignore` 忽略，**从未进入版本库**
- 签名密码支持环境变量注入（`DIANGUARD_STORE_PASSWORD` 等），CI / 发布时无需落盘明文
- 崩溃日志 `crash.txt` 坐标自动脱敏；`allowBackup=false` 禁止数据备份导出

---

## 权限说明

| 权限 | 用途 |
|------|------|
| INTERNET / ACCESS_NETWORK_STATE | 数据源订阅 |
| FOREGROUND_SERVICE / DATA_SYNC | 常驻后台监听 |
| WAKE_LOCK | 后台保活（4h 无条件续期） |
| POST_NOTIFICATIONS | 预警推送 |
| ACCESS_FINE/COARSE_LOCATION | 计算震中距（仅本机保存） |
| SYSTEM_ALERT_WINDOW | 锁屏强制弹出告警 |
| VIBRATE | 告警震动 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 防后台被杀引导 |

**全部权限均有明确功能对应，无冗余。** 位置坐标仅保存在本机 SharedPreferences，除逆地理编码外不向任何服务器发送。

---

## 构建

```bash
# 环境：Android SDK 34 / JDK 17 / Gradle 8.9
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 签名凭据：优先读取环境变量 `DIANGUARD_KEYSTORE_PATH` / `DIANGUARD_STORE_PASSWORD` / `DIANGUARD_KEY_ALIAS` / `DIANGUARD_KEY_PASSWORD`，回退 `local.properties`。

---

## 测试

```bash
./gradlew testDebugUnitTest   # 70 个用例，覆盖解析/烈度/融合/去重
./gradlew lintDebug          # 静态分析质量门禁
```

---

## 参考文献

- 汪素云, 俞言祥, 高阿甲, 等. 中国分区地震动衰减关系的确定[J]. 中国地震, 2000, 16(2): 99-106.
- 雷建成, 高孟潭, 俞言祥. 四川及邻区地震动衰减关系[J]. 地震学报, 2007, 29(5): 500-511.
- 郁曙君. 川滇地区地震烈度衰减关系[J]. 地震工程与工程振动, 1993, 13(1): 58-66.
- GB 18306-2015《中国地震动参数区划图》
- 肖亮, 俞言祥. 新一代地震区划图地震动参数衰减关系的建立与特点分析[J]. 中国地震局, 2011.

---

## 免责声明

本项目仅供学习和研究使用。地震预警涉及生命安全，请以官方预警渠道为准。使用本应用即表示已阅读并同意《免责声明与用户协议》。
