# 地震哨兵 (Dianguard)

> 把一台旧手机变成守护家人的地震预警哨兵 —— 7×24h 监听中国大陆国家级地震预警，S 波到达前全屏倒计时告警。

🌐 **官网**：[lokeily.github.io/dianguard-site](https://lokeily.github.io/dianguard-site/)

---

## 核心功能

- **多源实时预警** — 同时订阅 Wolfx（CENC/四川局/重庆局）与 BeeCLD·2v8（CEA 国家级源），独立域名互备
- **全屏强制告警** — 锁屏之上弹出，蓝/黄/橙/红四级配色 + 真实大陆预警语音 + 震动，S 波到达前逐秒倒计时
- **烈度阈值可调** — 2°/3°/4° 三档，低于阈值仅通知栏轻提示
- **地震历史记录** — 中国地震台网速报目录 + USGS/EMSC 兜底，按等级色展示
- **模拟预警测试** — 以周边真实城市为震中模拟全屏告警，熟悉流程
- **深色 OLED 省电** — 纯黑背景适配常亮旧手机
- **开机自启 + 厂商保活引导** — WakeLock + 小米/华为/OPPO/vivo/三星分品牌白名单步骤
- **系统自检** — 一键检查通知/悬浮窗/定位/WakeLock/电池优化是否就绪
- **应用内自动更新** — 检测 GitHub Release 新版本，签名校验后覆盖安装

---

## 快速开始

[下载最新版 APK](https://github.com/Lokeily/Earthquake-Sentinel/releases/latest)

安装后：授权通知/悬浮窗/定位权限 → 设置参考位置 → 开启预警监听 → 将应用加入厂商白名单。

---

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release（需配置 local.properties 中签名）
./gradlew assembleRelease
```

要求：Android SDK 34 / JDK 17 / Gradle 8.9。

---

## 技术架构

```
MainActivity (三 Tab: 主页 / 历史 / 设置)
    └── EewService (前台常驻服务)
          ├── EewConnectionManager → WebSocket × 多源订阅
          ├── EewAlertManager → 解析 → 烈度衰减 → 去重 → 触发告警
          └── BackupSource → USGS/EMSC 备用轮询（全断时激活）
                  │
                  ▼
           AlertActivity (全屏倒计时 + 三段式语音)
```

**烈度模型**：汪素云等 2000 短轴衰减公式 `I = 2.941 + 1.363M − 1.494·ln(R+7)`，按用户所在地估算。

**数据源**：中国地震台网 (CENC) / 四川省地震局 / 重庆市地震局 / 中国地震预警网 (CEA) — 均来自不同独立主机转发。

---

## 已知限制

- 数据源依赖第三方社区转发（Wolfx / BeeCLD），非官方直连
- 国产 ROM 需手动加入自启动白名单（App 内有引导）
- 仅覆盖中国大陆，不适用于台湾/日本等地区
- 备用探测源（USGS/EMSC）为震后速报，非秒级预警

---

## 免责声明

本项目仅供学习和研究使用。地震预警涉及生命安全，请以官方预警渠道为准。使用本应用即表示已阅读并同意《免责声明与用户协议》：开发者不对因数据延迟、转发中断、设备限制等原因导致的漏报、误报或损失承担责任。
