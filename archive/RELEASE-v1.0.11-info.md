# 地震哨兵 v1.0.11 发布说明

- **版本**：1.0.11（versionCode = 12）
- **构建产物**：`Dianguard-v1.0.11-debug.apk`（约 17.7 MB）
- **仓库**：`Lokeily/Dianguard`，Release tag：`v1.0.11`
- **构建环境**：JDK 17（`buildtools/jdk/jdk-17.0.20+8`），AGP 8.5.2，PowerShell `.\gradlew.bat clean assembleDebug --no-daemon`
- **发布脚本**：`buildtools/publish_v111.py`（幂等；复用/创建 tag=v1.0.11 的 Release 并上传 APK）

## 更新内容

### 应用内更新检测与引导机制（本次核心功能）
打开 APP 时自动检查 GitHub 上发布的最新 Release，发现新版本后引导用户在应用内完成更新。

1. **自动检测**：`MainActivity.maybeCheckUpdate()` 在 `onCreate` 中触发；首次构建失败的根因之一是该项目此前从未启用 `BuildConfig`（仅 `viewBinding`），已在 `build.gradle.kts` 的 `buildFeatures` 中显式开启 `buildConfig = true`，`AppUpdateChecker` 才能读到 `BuildConfig.VERSION_NAME`。
2. **GitHub 查询**：`AppUpdateChecker.check()` 后台线程请求
   `GET https://api.github.com/repos/Lokeily/Dianguard/releases/latest`，
   取最新非草稿/非预发布版本，挑选资源列表中第一个 `.apk` 的 `browser_download_url` 作为下载地址。
3. **版本比较**：解析 `tag_name`（去 `v`/`V` 前缀）为 `major*1e6+minor*1e3+patch` 数值，与本地 `BuildConfig.VERSION_NAME` 比较，得出「是否有更新」。
4. **弹窗提示**：`AppUpdater.showUpdateDialog()` 弹出「发现新版本 vX.X.X」对话框，含更新说明，按钮「前往更新」/「稍后」。`setCancelable(false)` 确保用户必须选择。
5. **应用内下载 + 安装**：
   - 点「前往更新」后 `AppUpdater.downloadAndInstall()` 以 `ProgressDialog`（横向进度 0–100）在后台线程流式下载 APK 到 `外部存储/getExternalFilesDir/updates/app-update.apk`。
   - 下载完成调 `installApk()`：通过 `FileProvider`（`authorities=com.dianguard.app.fileprovider`，`res/xml/file_paths.xml` 暴露 `updates/` 目录）生成 `content://` URI，以 `Intent.ACTION_VIEW` + `application/vnd.android.package-archive` + `FLAG_GRANT_READ_URI_PERMISSION` 调起系统安装器。
   - 因安装包与已装版本**同签名**且 `versionCode` 更高，系统安装器**自动覆盖旧版**，无需用户手动卸载。
6. **失败回退**：下载异常时回退到「浏览器打开」最新下载链接 / 发布页，保证更新流程不中断。
7. **节流与去重提示**：
   - 检查节流：默认 30 分钟内只查一次（规避 GitHub 匿名 API 60 次/小时/IP 限流），由 `AppConfig.lastUpdateCheckMs` 记录上次检查时间。
   - 用户忽略：点「稍后」写入 `AppConfig.dismissedUpdateVersion`，同一版本不再重复弹窗。
8. **新增权限 / 组件**：`AndroidManifest.xml` 增加 `REQUEST_INSTALL_PACKAGES` 权限与 `FileProvider`；`res/values/strings.xml` 增加 9 条更新相关字符串。

## 构建修复记录（本版踩坑）
- `compileDebugKotlin FAILED` 三处根因：
  1. `AppUpdateChecker` 引用 `BuildConfig` 报「Unresolved reference」→ 项目原未启用 `buildConfig`，已在 `buildFeatures` 显式开启。
  2. `MainActivity.maybeCheckUpdate()` 漏写函数闭合 `}`，导致其后所有方法被当成嵌套「局部函数」，级联出大量 `private/override 不适用于局部函数` 与「未解析引用」错误 → 补上函数闭合 `}`。
  3. `AppUpdater` 误用 `R.string.xxx + "\n..."`（`R.string` 是 Int，不能 `+ String`）→ 改为 `activity.getString(R.string.xxx) + "\n..."`。
- 仍需 `gradlew.bat clean` 后再构建（AGP 8.5.2 的 `desugar_graph/.../graph.bin` 锁文件问题）。

## 产品更名与换标（本版同步完成）
- 产品名由「滇震卫士 / Dianguard」更名为「**地震哨兵**」（英文/仓库/包名等技术标识 `Dianguard` 保持不变：`com.dianguard.app`、`Lokeily/Dianguard`）。
- 用户可见文案全部替换：`strings.xml` 的 `app_name`/`main_title`、引导页 `activity_guide.xml`、`EewService` 通知标题与渠道名、`MainActivity` 提示文案、README/BUILD 等文档。
- 启动图标更换：移除旧的矢量 `drawable/ic_launcher.xml`（红底白三角），改用用户提供的新 logo 生成各密度 PNG（`mipmap-{mdpi..xxxhdpi}/ic_launcher.png` 与 `ic_launcher_round.png`，48/72/96/144/192px），`AndroidManifest.xml` 的 `android:icon`/`roundIcon` 改为指向 `@mipmap`。新 APK（17.9 MB）已用新图标重新构建并覆盖归档。

## 备注
- ✅ **已发布（23:17）**：`python buildtools/publish_v111.py`（GH_TOKEN 环境变量）→ 创建 Release（id=363554377），上传 `Dianguard-v1.0.11-debug.apk`（17.1 MB）→ https://github.com/Lokeily/Dianguard/releases/tag/v1.0.11 。
- ⚠️ 本次发布所用的 GitHub PAT 为明文提供，建议发布后到 GitHub 设置中撤销该 Token。
