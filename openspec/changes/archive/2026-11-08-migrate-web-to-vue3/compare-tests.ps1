param(
  # Vitest JSON 报告（npx vitest run --reporter=json --outputFile=...）
  [Parameter(Mandatory)][string]$Report,
  # 切换前的历史报告使用 src-vue；默认检查已切换的 src。
  [ValidateSet('src', 'src-vue')][string]$SourceRoot = 'src',
  # 仅比对报告中出现的迁移文件；未指定时要求清单内全部文件都已迁移。
  [switch]$Partial
)
$ErrorActionPreference = 'Stop'
$inventory = Join-Path $PSScriptRoot 'test-inventory.md'
$expected = [ordered]@{}
$pending = @{}
$current = $null
$inPending = $false
foreach ($line in [System.IO.File]::ReadAllLines($inventory, [System.Text.Encoding]::UTF8)) {
  if ($line -match '^### `(src/.+)`$') {
    $current = $Matches[1] -replace '^src/', "$SourceRoot/" -replace '\.test\.tsx$', '.test.ts'
    $expected[$current] = [System.Collections.Generic.List[string]]::new()
  } elseif ($current -and $line -match '^- (.*)$') {
    $expected[$current].Add(($Matches[1] -replace '\\\|', '|'))
  } elseif ($line -match '^## 迁移差异') {
    $current = $null
  } elseif ($line -match '^### 待补用例') {
    $inPending = $true
  } elseif ($inPending -and $line -match '^### ') {
    $inPending = $false
  } elseif ($inPending -and $line -match '^- `(src-vue/[^`]+)` :: (.+?) :: ') {
    $pendingPath = $Matches[1] -replace '^src-vue/', "$SourceRoot/"
    $pending["${pendingPath}::$($Matches[2])"] = $true
  }
}
$json = [System.IO.File]::ReadAllText((Resolve-Path $Report), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../../../kb-web')).Path.Replace('\', '/') + '/'
$actual = @{}
foreach ($file in $json.testResults) {
  $path = $file.name.Replace('\', '/').Replace($root, '')
  $actual[$path] = [pscustomobject]@{
    Titles = @($file.assertionResults | ForEach-Object fullName)
    Skipped = @($file.assertionResults | Where-Object { $_.status -in 'skipped', 'pending', 'todo' } | ForEach-Object fullName)
    Failed = @($file.assertionResults | Where-Object { $_.status -notin 'passed', 'skipped', 'pending', 'todo' } | ForEach-Object fullName)
    Status = $file.status
  }
}
$problems = [System.Collections.Generic.List[string]]::new()
$deferred = [System.Collections.Generic.List[string]]::new()
$checked = 0; $cases = 0
foreach ($path in $expected.Keys) {
  if (-not $actual.ContainsKey($path)) {
    if (-not $Partial) { $problems.Add("缺少迁移文件: $path") }
    continue
  }
  $checked++
  $want = $expected[$path]; $got = $actual[$path]
  $cases += $got.Titles.Count
  $isPending = { param($title) $Partial -and $pending.ContainsKey("${path}::$title") }
  foreach ($title in $want) {
    if ($got.Titles -notcontains $title) {
      if (& $isPending $title) { $deferred.Add("$path :: $title（未写入）") } else { $problems.Add("$path 缺少用例: $title") }
    }
  }
  foreach ($title in $got.Titles) { if ($want -notcontains $title) { $problems.Add("$path 多出用例: $title") } }
  $missingPending = @($want | Where-Object { $got.Titles -notcontains $_ -and (& $isPending $_) }).Count
  if ($want.Count -ne $got.Titles.Count + $missingPending) { $problems.Add("$path 用例数不同: 清单 $($want.Count)，实际 $($got.Titles.Count)") }
  foreach ($title in $got.Skipped) {
    if (& $isPending $title) { $deferred.Add("$path :: $title（已跳过）") } else { $problems.Add("$path 被跳过: $title") }
  }
  foreach ($title in $got.Failed) { $problems.Add("$path 未通过: $title") }
  if ($got.Status -notin 'passed', 'skipped' -and $got.Failed.Count -eq 0) { $problems.Add("$path 文件状态: $($got.Status)") }
}
foreach ($path in $actual.Keys) { if (-not $expected.Contains($path)) { Write-Output "清单外文件（迁移新增）: $path，$($actual[$path].Titles.Count) 项" } }
Write-Output "已比对 $checked/$($expected.Count) 个清单文件，$cases 项用例"
if ($deferred.Count) { Write-Output "  待补用例 $($deferred.Count) 项（见 test-inventory.md「待补用例」）"; $deferred | ForEach-Object { Write-Output "    … $_" } }
if ($problems.Count) { $problems | ForEach-Object { Write-Output "  ✗ $_" }; exit 1 }
Write-Output '  ✓ 标题、数量一致且全部通过'
