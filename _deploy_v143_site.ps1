# 部署 docs/ 静态站到 Lokeily/dianguard-site（GitHub Pages），令牌仅内存
$ErrorActionPreference = "Stop"
$Token = $env:GITHUB_TOKEN
if (-not $Token) { Write-Host "ERROR: no token"; exit 1 }
$H = @{ Authorization = "Bearer $Token"; Accept = "application/vnd.github+json"; "User-Agent" = "dianguard-site-deploy" }
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$Owner = "Lokeily"; $Repo = "dianguard-site"
$ApiBase = "https://api.github.com/repos/$Owner/$Repo/contents"
$SiteDir = Join-Path $PSScriptRoot "docs"

# 收集要上传的文件（站点本体，不含 _shots/_audit 等内部工具）
$files = @(
  "index.html", "features.html", "how.html", "disclaimer.html", "changelog.html",
  "styles.css", "app.js",
  "assets/ic_launcher.png", "assets/ic_launcher_round.png"
)

$readmeSite = @"
# 地震哨兵 Dianguard 官网

Android 地震预警应用「地震哨兵」的官方展示与下载站点（GitHub Pages）。当前版本 **v1.4.3**。

- 在线访问: https://lokeily.github.io/dianguard-site/
- 版本更新页: https://lokeily.github.io/dianguard-site/changelog.html
- 免责声明:   https://lokeily.github.io/dianguard-site/disclaimer.html
- 应用仓库:   https://github.com/Lokeily/Earthquake-Sentinel

## v1.4.3 站点要点

- 官方源直连：ICL 官方预警网 1s 增量实时 + CENC 官方目录（近 8 个月权威数据）
- 先到先发：首报烈度达标立即全屏告警，不再等待融合
- 下载实时同步最新版：页面打开时实时读取 GitHub 最新 Release

## 本地预览

``````bash
cd docs
python3 -m http.server 8000
``````
"@

$commitMsg = "site update: v1.4.3（官方源直连/先到先发内容更新 + 交错入场动画 + FAN 移除 + 版本号同步）"

foreach ($f in $files) {
  $local = Join-Path $SiteDir ($f -replace '/', '\')
  if (-not (Test-Path $local)) { Write-Host "SKIP missing: $f"; continue }
  $b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($local))
  $sha = $null
  try {
    $cur = Invoke-RestMethod -Method Get -Uri "$ApiBase/$f`?ref=main" -Headers $H -TimeoutSec 60
    $sha = $cur.sha
  } catch {}
  $payload = @{ message = "$commitMsg : $f"; content = $b64; branch = "main" }
  if ($sha) { $payload.sha = $sha }
  $json = $payload | ConvertTo-Json -Depth 3
  try {
    $r = Invoke-RestMethod -Method Put -Uri "$ApiBase/$f" -Headers $H -ContentType "application/json; charset=utf-8" -Body $Utf8.GetBytes($json) -TimeoutSec 120
    Write-Host ("OK  {0}  (commit {1})" -f $f, $r.commit.sha.Substring(0,7))
  } catch {
    Write-Host ("FAIL {0}: {1}" -f $f, $_.Exception.Message)
  }
}

# README.md of site repo
$b64 = [Convert]::ToBase64String($Utf8.GetBytes($readmeSite))
$sha = $null
try { $cur = Invoke-RestMethod -Method Get -Uri "$ApiBase/README.md`?ref=main" -Headers $H -TimeoutSec 60; $sha = $cur.sha } catch {}
$payload = @{ message = "site update: README (v1.4.3)"; content = $b64; branch = "main" }
if ($sha) { $payload.sha = $sha }
$json = $payload | ConvertTo-Json -Depth 3
Invoke-RestMethod -Method Put -Uri "$ApiBase/README.md" -Headers $H -ContentType "application/json; charset=utf-8" -Body $Utf8.GetBytes($json) -TimeoutSec 120 | Out-Null
Write-Host "OK  README.md"

Write-Host "`nSITE DEPLOY DONE: https://lokeily.github.io/dianguard-site/"