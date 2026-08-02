# 滇震卫士 v1.0.10 发布说明

- **版本**：1.0.10（versionCode = 11）
- **构建产物**：`Dianguard-v1.0.10-debug.apk`（约 17.7 MB）
- **仓库**：`Lokeily/Dianguard`，Release tag：`v1.0.10`
- **构建环境**：JDK 17（`buildtools/jdk/jdk-17.0.20+8`），AGP 8.5.2，PowerShell `.\gradlew.bat assembleDebug --no-daemon`

## 更新内容

### 1. 预警等级 ↔ 背景板颜色严格一一对应
- 旧版告警页背景写死为红色，导致橙色/黄色/蓝色预警也显示红底。
- 新版 `AlertActivity` 新增 `applyLevelTheme(level)`，按等级设置整屏背景板、状态栏/导航栏颜色与文字配色：
  - 红色预警 → 红底；橙色 → 橙底；黄色 → 黄底；蓝色 → 蓝底。
  - 黄色面板较亮，文字自动切换为深色以保证可读性（符合国标预警配色惯例）。
- 完整模拟预警（测试页「完整模拟预警」）与真实预警共用同一 `AlertActivity`，因此两者均遵循此规则。

### 2. 接入 4 个中国大陆官方地震预警数据源
- 数据源（经 Wolfx 公共聚合转发，免 Key、免费，HTTP/WS 双协议）：
  - 中国地震台网 (CENC) — `wss://ws-api.wolfx.jp/cenc_eew`
  - 四川省地震局 — `wss://ws-api.wolfx.jp/sc_eew`
  - 福建省地震局 — `wss://ws-api.wolfx.jp/fj_eew`
  - 重庆市地震局 — `wss://ws-api.wolfx.jp/cq_eew`
- 每个源一条独立 WebSocket 长连接，独立指数退避重连；前台服务状态实时显示「已连接 X/4 个官方预警源」。
- 解析层容错：震级字段 `Magnitude`(CENC/重庆) 与 `Magunitude`(四川/福建) 拼写不一、福建源无 `Depth`/`MaxIntensity` 均做了兼容；缺失烈度时按震级兜底估算。

### 3. 多接口同时发布去重
- 同一地震常被多个机构同时发布。新增 `makeQuakeKey()`：以「发震时间 + 震中(约0.001°) + 震级(约0.1)」生成物理去重键（刻意忽略各源不同的 EventID）。
- `recentQuakes` 维护 10 分钟去重窗口：同一物理地震在窗口内只触发一次全屏告警 / 一次通知栏提醒，避免多接口重复弹出。

## 备注
- 构建时注意：AGP 8.5.2 的 dex 任务会写入 `desugar_graph/.../graph.bin`，若沿用旧 `app/build`（如上一版遗留）可能因文件被锁定导致 `拒绝访问` 失败；执行一次 `gradlew.bat clean` 后再构建即可。
- 发布所用 GitHub PAT 为明文提供，建议发布后到 GitHub 设置中撤销该 Token。
