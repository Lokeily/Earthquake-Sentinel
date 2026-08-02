# 地震哨兵 Dianguard — 本地构建说明（v1.0.1）

本环境（沙箱）无法构建 Android APK：JDK/Android SDK/Gradle 均未安装，且沙箱网络对 Google/Microsoft/Amazon 的大文件下载做了阻断（仅 GitHub CDN 可用且限速 30KB/s）。请在你的本机构建。

## 本次改动（已本地 commit，未推送）
- 主题：`Theme.Material3.DayNight.NoActionBar` → `Theme.AppCompat.Light.NoActionBar`（修复部分机型 Material3 控件 inflation 闪退）。
- 三套布局全部改用纯基础控件（TextView/EditText/Switch/Button + LinearLayout），去掉 MaterialCardView/MaterialButton/SwitchMaterial/TextInputLayout → 更轻量、兼容性更好。
- iOS 风格 UI：#F2F2F7 分组底 + 白色圆角卡片 + #007AFF 蓝 + #FF3B30 红。
- `MainActivity` 内置崩溃自记录器：崩溃时把堆栈写入 `Android/data/com.dianguard.app/files/crash.txt`。
- 版本号 1.0.0 → 1.0.1（versionCode 2）。所有控件 ID 不变，Kotlin 逻辑零改。

## 在你本机构建（二选一）

### 方式 A：Android Studio（推荐）
1. 用 Android Studio 打开 `Dianguard/` 文件夹，等 Gradle 同步完成（首次会自动下载 SDK/Gradle/依赖，你的网络快，几分钟即可）。
2. 菜单 `Build → Build App Bundle(s) / APK(s) → Build APK(s)`。
3. 产物：`app/build/outputs/apk/debug/app-debug.apk`。

### 方式 B：命令行
```bash
cd Dianguard
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装到手机
- 把 `app-debug.apk` 传到手机，系统「设置 → 安全」允许「未知来源」安装，点击安装。
- ⚠️ **重要**：debug 签名是按本机生成的。如果手机上还装着旧版 v1.0 的 debug 包，签名可能不一致导致安装失败（提示“签名冲突”/“应用未安装”）——请**先卸载旧版**再装新版。
- 打开 App：若仍闪退，崩溃堆栈会写入 `Android/data/com.dianguard.app/files/crash.txt`，把内容发我即可精确定位。

## 备注
- 这是一个 debug 签名包，仅自用/家人分发，未上架应用商店。
- 源码与旧版 APK 已在 `../archive/` 本地存档；GitHub 仓库你随时可删，不影响本地。
