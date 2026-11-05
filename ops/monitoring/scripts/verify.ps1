[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $ToolDirectory,
    [Parameter(Mandatory)][string] $ScratchDirectory,
    [string] $SettingsPath = (Join-Path $PSScriptRoot '../settings.example.json'),
    [string] $NotifierImage
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not [IO.Path]::IsPathFullyQualified($ScratchDirectory)) {
    throw 'ScratchDirectory 必须是明确的绝对临时目录'
}
$run = Join-Path $ScratchDirectory ('monitoring-verify-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($run) | Out-Null
$reportPath = Join-Path $run 'verification-report.json'
$steps = [Collections.Generic.List[object]]::new()
$pwsh = Join-Path $PSHOME 'pwsh.exe'
$fixtureImage = 'registry.invalid/kb-alert-notifier:static-check-only@sha256:' + ('0' * 64)
$usingFixture = [string]::IsNullOrEmpty($NotifierImage)
if ($usingFixture) {
    $NotifierImage = $fixtureImage
}

function Record-Step([string] $Name, [int] $Code, [string] $Status, [string] $Detail, [string[]] $Command = @()) {
    $steps.Add([ordered]@{name=$Name;exitCode=$Code;status=$Status;detail=$Detail;command=$Command})
    Write-Host "$Name : $Status (exit=$Code) $Detail"
}
function Local-Step([string] $Name, [string] $Executable, [string[]] $Arguments,
        [string] $Directory = $run, [bool] $Ready = $true, [hashtable] $Environment = @{}) {
    if (-not $Ready) {
        Record-Step $Name 126 'not-executed' '前置步骤失败或缺少固定版本工具' (@($Executable) + $Arguments)
        return $false
    }
    $outPath = Join-Path $run "$Name.stdout.log"
    $errPath = Join-Path $run "$Name.stderr.log"
    $code = 1
    $process = [Diagnostics.Process]::new()
    try {
        $start = [Diagnostics.ProcessStartInfo]::new($Executable)
        $start.UseShellExecute = $false
        $start.RedirectStandardOutput = $true
        $start.RedirectStandardError = $true
        $start.StandardOutputEncoding = [Text.Encoding]::UTF8
        $start.StandardErrorEncoding = [Text.Encoding]::UTF8
        $start.WorkingDirectory = $Directory
        foreach ($argument in $Arguments) {
            $start.ArgumentList.Add($argument)
        }
        foreach ($key in $Environment.Keys) {
            $start.Environment[$key] = [string]$Environment[$key]
        }
        $process.StartInfo = $start
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(300000)) {
            $process.Kill($true)
            $process.WaitForExit()
            $code = 124
        } else {
            $code = $process.ExitCode
        }
        [IO.File]::WriteAllText($outPath, $stdout.GetAwaiter().GetResult())
        [IO.File]::WriteAllText($errPath, $stderr.GetAwaiter().GetResult())
    } catch {
        $code = 1
        [IO.File]::WriteAllText($errPath, '本地进程执行失败：' + $_.Exception.GetType().Name)
    } finally {
        $process.Dispose()
    }
    Record-Step $Name $code $(if ($code -eq 0) {'passed'} else {'failed'}) "日志：$Name.stdout.log / $Name.stderr.log" (@($Executable) + $Arguments)
    return ($code -eq 0)
}
function Read-Env([string] $Path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
            continue
        }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$' -or $values.ContainsKey($Matches[1])) {
            throw '生成环境文件格式错误或有重复键'
        }
        $values[$Matches[1]] = $Matches[2]
    }
    return $values
}

try {
    $versions = Get-Content -LiteralPath (Join-Path $root 'versions.json') -Raw | ConvertFrom-Json -AsHashtable
    $tools = @{}
    $ready = @{}
    foreach ($name in @('promtool','amtool','compose')) {
        $spec = $versions.tools[$name]
        $tools[$name] = [IO.Path]::GetFullPath((Join-Path $ToolDirectory $spec.relativePath))
        $ready[$name] = $false
        if (-not (Test-Path -LiteralPath $tools[$name] -PathType Leaf)) {
            Record-Step "$name-integrity" 127 'failed' "缺少工具：$($tools[$name])"
            Record-Step "$name-version" 126 'not-executed' '缺少工具，未执行版本检查'
            continue
        }
        if ((Get-FileHash -LiteralPath $tools[$name] -Algorithm SHA256).Hash.ToLowerInvariant() -cne $spec.executableSha256) {
            Record-Step "$name-integrity" 1 'failed' '可执行文件 SHA-256 不符合版本清单'
            Record-Step "$name-version" 126 'not-executed' '拒绝执行未经固定校验的二进制'
            continue
        }
        Record-Step "$name-integrity" 0 'passed' "SHA-256 已匹配；要求版本 $($spec.version)"
        $ok = Local-Step "$name-version" $tools[$name] $spec.versionArgs
        if ($ok) {
            $text = [IO.File]::ReadAllText((Join-Path $run "$name-version.stdout.log")) + [IO.File]::ReadAllText((Join-Path $run "$name-version.stderr.log"))
            $ready[$name] = $text -match $spec.versionPattern
            if (-not $ready[$name]) {
                Record-Step "$name-version-match" 1 'failed' 'CLI 报告的版本不符合版本清单'
            }
        }
    }
    $null = Local-Step 'config-tests' $pwsh @('-NoProfile','-File',(Join-Path $root 'tests/config.tests.ps1'),'-WorkDirectory',(Join-Path $run 'config-tests'))
    $generated = Join-Path $run 'generated'
    $rendered = Local-Step 'render-config' $pwsh @('-NoProfile','-File',(Join-Path $root 'scripts/render-config.ps1'),'-SettingsPath',([IO.Path]::GetFullPath($SettingsPath)),'-OutputDirectory',$generated)
    $ruleDirectory = Join-Path $run 'rule-tests'
    $built = Local-Step 'build-rule-tests' $pwsh @('-NoProfile','-File',(Join-Path $root 'tests/build-rule-tests.ps1'),'-OutputDirectory',$ruleDirectory)
    $null = Local-Step 'promtool-check-config' $tools.promtool @('check','config','prometheus.json') $generated ($ready.promtool -and $rendered)
    $null = Local-Step 'promtool-check-rules' $tools.promtool @('check','rules','rules.json') $generated ($ready.promtool -and $rendered)
    $ruleTestFiles = @('rules.test.json')
    if ($built) {
        $ruleTestFiles = @(Get-ChildItem -LiteralPath $ruleDirectory -File -Filter '*.test.json' | Sort-Object Name | ForEach-Object { $_.Name })
        if ($ruleTestFiles.Count -eq 0) {
            Record-Step 'rule-test-discovery' 1 'failed' '生成器未输出任何合成时序文件'
            $built = $false
        }
    }
    $null = Local-Step 'promtool-test-rules' $tools.promtool (@('test','rules') + $ruleTestFiles) $ruleDirectory ($ready.promtool -and $built)
    $amConfig = Join-Path $generated 'alertmanager.json'
    $null = Local-Step 'amtool-check-config' $tools.amtool @('check-config',$amConfig) $run ($ready.amtool -and $rendered)
    $settings = @{environment='not-executed';deployment='not-executed'}
    if ($rendered) {
        $settings = Get-Content -LiteralPath $SettingsPath -Raw | ConvertFrom-Json -AsHashtable
    }
    $catalog = Get-Content -LiteralPath (Join-Path $root 'alert-catalog.json') -Raw | ConvertFrom-Json -AsHashtable
    foreach ($entry in $catalog) {
        foreach ($service in $entry.services) {
            $null = Local-Step "route-$($entry.name)-$service" $tools.amtool @('config','routes','test',"--config.file=$amConfig",'--verify.receivers=dingtalk',
                "environment=$($settings.environment)","deployment=$($settings.deployment)","service=$service","alertname=$($entry.name)","severity=$($entry.severity)") $run ($ready.amtool -and $rendered)
        }
    }
    $prepared = $false
    $composeEnvironment = @{}
    if ($rendered) {
        try {
            $composeEnvironment = Read-Env (Join-Path $root 'versions.env')
            foreach ($item in (Read-Env (Join-Path $generated 'deployment.env')).GetEnumerator()) {
                $composeEnvironment[$item.Key] = $item.Value
            }
            $composeEnvironment.KB_MONITORING_CONFIG_DIR = $generated.Replace('\','/')
            $composeEnvironment.KB_NOTIFIER_IMAGE = $NotifierImage
            $composeEnvironment.KB_NOTIFIER_IPV4 = '172.30.80.10'
            $composeEnvironment.KB_MONITORING_SUBNET = '172.30.80.0/24'
            # 仅在新建临时目录写入故意无效的值；永不读取部署凭据，也不启动适配器。
            $secretDirectory = Join-Path $run 'static-only-secrets'
            [IO.Directory]::CreateDirectory($secretDirectory) | Out-Null
            foreach ($key in @('KB_NOTIFIER_TOKEN_FILE','KB_DINGTALK_TOKEN_FILE','KB_DINGTALK_SECRET_FILE','KB_GRAFANA_PASSWORD_FILE')) {
                $file = Join-Path $secretDirectory ($key + '.txt')
                [IO.File]::WriteAllText($file, 'INVALID_STATIC_ONLY')
                $composeEnvironment[$key] = $file.Replace('\','/')
            }
            $composeEnvironment.COMPOSE_DISABLE_ENV_FILE = 'true'
            $composeEnvironment.COMPOSE_PROFILES = ''
            $prepared = $true
            Record-Step 'prepare-static-input' 0 'passed' '隔离的无效 secret 文件；不含真实凭据'
        } catch {
            Record-Step 'prepare-static-input' 1 'failed' '无法准备隔离静态输入'
        }
    } else {
        Record-Step 'prepare-static-input' 126 'not-executed' '配置生成失败'
    }
    $composed = Local-Step 'compose-config' $tools.compose @('--ansi','never','--project-directory',$root,'--project-name','kb-monitoring-static',
        '--env-file',(Join-Path $root 'versions.env'),'--file',(Join-Path $root 'compose.yml'),'config','--format','json') $run ($ready.compose -and $prepared) $composeEnvironment
    $testArgs = @('-NoProfile','-File',(Join-Path $root 'tests/deployment.tests.ps1'),'-ComposeJson',(Join-Path $run 'compose-config.stdout.log'),'-GeneratedDirectory',$generated)
    if ($usingFixture) {
        $testArgs += '-AllowStaticImageFixture'
    }
    $null = Local-Step 'deployment-tests' $pwsh $testArgs $run $composed
} catch {
    Record-Step 'verification-error' 1 'failed' ('验证入口异常：' + $_.Exception.GetType().Name)
} finally {
    $failed = @($steps | Where-Object { $_.exitCode -ne 0 }).Count
    $exitCode = if ($failed -eq 0) {0} else {1}
    $report = [ordered]@{
        schemaVersion=1;finishedUtc=[DateTime]::UtcNow.ToString('o');scope='local-cli-static-only';
        deployable=$false;notifierImageFixture=$usingFixture;exitCode=$exitCode;
        passed=@($steps | Where-Object status -EQ 'passed').Count;
        failed=@($steps | Where-Object status -EQ 'failed').Count;
        notExecuted=@($steps | Where-Object status -EQ 'not-executed').Count;
        steps=@($steps.ToArray())
    }
    [IO.File]::WriteAllText($reportPath, (ConvertTo-Json $report -Depth 12), [Text.UTF8Encoding]::new($false))
    Write-Host "验证报告：$reportPath"
}
exit $exitCode
