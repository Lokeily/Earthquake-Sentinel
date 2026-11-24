# v1.4.3 发布：Release + APK 上传 + README 推送 + 仓库描述（令牌仅内存）
$ErrorActionPreference = "Stop"
$Token = $env:GITHUB_TOKEN
if (-not $Token) { Write-Host "ERROR: no token"; exit 1 }
$H = @{
  Authorization = "Bearer $Token"
  Accept        = "application/vnd.github+json"
  "User-Agent"  = "dianguard-release"
}
$Api = "https://api.github.com/repos/Lokeily/Earthquake-Sentinel"
$Utf8 = New-Object System.Text.UTF8Encoding($false)

# ---------- Release notes ----------
$Body = @"
## v1.4.3 更新说明 — 回归初衷：最快、最稳、官方源优先

> **一句话总结**：移除多源融合引擎，改为**先到先发**——首报烈度达标立即全屏告警，不再等任何第二源；同时接入 **CENC 官方目录**与 **ICL 官方 1 秒增量轮询**，官方源优先、wolfx 降为补充与兜底。告警页全面对齐 ICL / 国家标准呈现。

---

## 一、告警决策：先到先发（核心）

- **移除多源融合决策引擎（EewFusion）**：v1.3.x 的"首报进 1.5s 窗口等第二源交叉验证"会让每场地震的首次告警被人为推迟——在预警盲区边缘，这就是避险时间
- 现在：**任一源首报烈度达标（GB/T 四级：蓝<3° / 黄3-4° / 橙5-6° / 红≥7°）→ 立即全屏告警**
- 修正报到达 → **立即刷新** 震级 / 烈度 / 报数 / 倒计时（上修下修均实时更新）
- 误报防护改由**事件归并去重**承接：跨机构事件归并（±90s / ≤40km）、同源报数只增不减、同报数跨源去重、10 分钟去重窗口

## 二、官方实时源：ICL 1 秒增量轮询（近推送延迟）

- 逆向确认 ICL API 的 ``start_at`` 服务端增量过滤语义（updateAt > start_at）
- 以「已见最大 updateAt」为游标增量拉取：**静止期响应仅 ~33B**，轮询间隔从 3s 压到 **1s**
- 平均接收延迟 **1.5s → 0.5s**，逼近推送级；带宽从 ~345MB/天降到 ~2.8MB/天
- ICL 为官方预警发布机构直连域名，完全独立于 wolfx

## 三、接入 CENC 官方目录（权威数据，最高优先级）

- 逆向中国地震台网官网 SPA，发现官方数据接口 ``www.ceic.ac.cn/data/data.json``
- 免鉴权 / HTTPS 有效证书 / 大陆可达；实测 **795 条官方目录、覆盖近 8 个月、震级 1.7~7.9**
- 成为**历史 / 预警回放的第一优先级数据源**；wolfx 镜像按官方 eventId 去重，仅作补充

## 四、Wolfx 故障自动 HTTP 降级（源异常不再吓人）

- 实测确认 wolfx 为四个机构均提供与 WS 同构的 HTTP EEW 端点（cenc/sc/cq/cwa_eew.json）
- WS 连续失败 ≥2 次 → 自动启用同机构 HTTP 轮询（4s），恢复后自动切回
- 主页状态分层：官方源带「官方」标签；转发源异常且官方在线时显示「转发异常·官方源可用」，
  HTTP 降级时显示「HTTP降级·接收中」——用户能看出预警并未中断，不再满屏惊悚的「源异常·503」

## 五、告警页对齐 ICL / 国家标准

- 顶部：**预警等级横幅**（红色/橙色/黄色/蓝色预警）
- 中部：大号倒计时 + **「地震波将于 X 秒后到达」提示横幅**（每秒实时刷新，抵达后切「地震波已抵达 · 请立即避险」）
- 震中 / 震级 / 震源深度·距您X公里 + **分级避险指引**（一句话行动指令，随等级联动）
- 倒计时扣除「发震→收报」官方延迟，不再重复计入已流逝时间

## 六、可靠性

- Android 15+ dataSync 前台服务 6h 超时 → 自动以 specialUse 类型续期，7×24 常驻不失联
- 服务僵尸态防护（START_STICKY 拉起但监听已关时自停）；主页前台自愈（开关已开但服务未运行 → 自动恢复）
- 证书固定降级逃生舱全链路接入（WS / ICL / 目录抓取 / 降级轮询 / 更新下载）

## 七、测试

**88 项单元测试全部通过**（CoreLogic 82 + 预警链路一致性 6）。

---

**免责声明**：本项目仅供学习和研究使用。地震预警涉及生命安全，请以官方预警渠道为准。
"@

$jsonBody = @{
  tag_name          = "v1.4.3"
  target_commitish  = "main"
  name              = "地震哨兵 v1.4.3 — 先到先发 · 官方源优先 · 告警页对齐 ICL"
  body              = $Body
  draft             = $false
  prerelease        = $false
} | ConvertTo-Json -Depth 4

Write-Host "[1/5] create release v1.4.3"
try {
  $rel = Invoke-RestMethod -Method Post -Uri "$Api/releases" -Headers $H -ContentType "application/json; charset=utf-8" -Body $Utf8.GetBytes($jsonBody) -TimeoutSec 120
  Write-Host ("  created id=" + $rel.id)
} catch {
  $sc = $_.Exception.Response.StatusCode.value__
  if ($sc -eq 422) {
    Write-Host "  already exists, fetch"
    $rel = Invoke-RestMethod -Method Get -Uri "$Api/releases/tags/v1.4.3" -Headers $H -TimeoutSec 60
  } else { Write-Host "ERROR: $_"; exit 1 }
}
$rid = $rel.id

Write-Host "[2/5] upload release apk"
$apkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apkPath)) { Write-Host "ERROR apk missing"; exit 1 }
# remove same-name asset
$cur = Invoke-RestMethod -Method Get -Uri "$Api/releases/$rid" -Headers $H -TimeoutSec 60
foreach ($a in $cur.assets) {
  if ($a.name -eq "Dianguard-v1.4.3-release.apk") {
    Invoke-RestMethod -Method Delete -Uri "$Api/releases/assets/$($a.id)" -Headers $H -TimeoutSec 60 | Out-Null
    Write-Host "  old asset removed"
  }
}
$upH = @{ Authorization = "Bearer $Token"; "User-Agent" = "dianguard-release"; "Content-Type" = "application/vnd.android.package-archive" }
$apkBytes = [System.IO.File]::ReadAllBytes($apkPath)
$up = Invoke-RestMethod -Method Post -Uri "https://uploads.github.com/repos/Lokeily/Earthquake-Sentinel/releases/$rid/assets?name=Dianguard-v1.4.3-release.apk" -Headers $upH -Body $apkBytes -TimeoutSec 600
Write-Host ("  uploaded: " + $up.browser_download_url)

Write-Host "[3/5] push README.md"
$readmeBytes = [System.IO.File]::ReadAllBytes((Join-Path $PSScriptRoot "README.md"))
$readmeB64 = [Convert]::ToBase64String($readmeBytes)
$sha = $null
try {
  $cur2 = Invoke-RestMethod -Method Get -Uri "$Api/contents/README.md?ref=main" -Headers $H -TimeoutSec 60
  $sha = $cur2.sha
} catch {}
$rdPayload = @{
  message = "docs: README 重写为 v1.4.3 实际架构（移除融合引擎，先到先发；官方源优先；HTTP 降级；ICL 式告警页）"
  content = $readmeB64
  branch  = "main"
}
if ($sha) { $rdPayload.sha = $sha }
$rdJson = $rdPayload | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Put -Uri "$Api/contents/README.md" -Headers $H -ContentType "application/json; charset=utf-8" -Body $Utf8.GetBytes($rdJson) -TimeoutSec 120 | Out-Null
Write-Host "  README pushed"

Write-Host "[4/5] update repo description"
$desc = "地震哨兵 Dianguard — 官方源直连地震预警：ICL 官方 1s 增量实时 + CENC 官方目录 · 先到先发（无融合等待）· 三层降级兜底 · 全屏锁屏告警 · GB/T 四级标准"
$descJson = @{ description = $desc } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri $Api -Headers $H -ContentType "application/json; charset=utf-8" -Body $Utf8.GetBytes($descJson) -TimeoutSec 60 | Out-Null
Write-Host "  description updated"

Write-Host "[5/5] DONE"
Write-Host ("  release: " + $rel.html_url)