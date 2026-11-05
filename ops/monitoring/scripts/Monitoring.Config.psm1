Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-MonitoringJson([string] $Path) {
    try {
        $bytes = [IO.File]::ReadAllBytes([IO.Path]::GetFullPath($Path))
        if ($bytes.Length -gt 262144) { throw 'size' }
        $text = [Text.UTF8Encoding]::new($false, $true).GetString($bytes)
        $options = [System.Text.Json.JsonDocumentOptions]::new()
        $options.MaxDepth = 16
        $document = [System.Text.Json.JsonDocument]::Parse($text, $options)
        try { Assert-UniqueJson $document.RootElement } finally { $document.Dispose() }
        return ConvertFrom-Json -InputObject $text -AsHashtable -Depth 16
    } catch { throw '监控 JSON 无效或超出边界' }
}

function Assert-UniqueJson($Element) {
    if ($Element.ValueKind -eq 'Object') {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $names.Add($property.Name)) { throw 'duplicate' }
            Assert-UniqueJson $property.Value
        }
    } elseif ($Element.ValueKind -eq 'Array') {
        foreach ($item in $Element.EnumerateArray()) { Assert-UniqueJson $item }
    }
}

function Assert-Keys($Value, [string[]] $Names) {
    if ($Value -isnot [Collections.IDictionary] -or $Value.Count -ne $Names.Count) { throw '配置字段不完整' }
    foreach ($name in $Value.Keys) {
        if ($name -cnotin $Names) { throw '配置包含未知字段' }
    }
}

function Assert-Number($Value, [double] $Min, [double] $Max, [bool] $Integer = $false) {
    if ($Value -isnot [ValueType] -or $Value -is [bool] -or -not [double]::IsFinite([double]$Value) -or
        $Value -lt $Min -or $Value -gt $Max -or ($Integer -and [Math]::Truncate($Value) -ne $Value)) {
        throw '监控数值越界'
    }
}

function Assert-Root([string] $Value) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -cne 'https' -or
        -not $uri.Host -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/' -or
        $Value -match '[\s<>"\\]' -or $uri.IsLoopback) { throw '管理根地址必须为明确 HTTPS 来源' }
}

function Assert-MonitoringSettings($Settings, $Catalog) {
    Assert-Keys $Settings @('schemaVersion','environment','deployment','adminRoot','grafanaRoot','retentionDays','parserExpected','services','thresholds','waitSeconds')
    if ($Settings.schemaVersion -ne 1) { throw '不支持的监控配置版本' }
    foreach ($name in @('environment','deployment')) {
        if ($Settings[$name] -isnot [string] -or $Settings[$name] -cnotmatch '^[a-z][a-z0-9-]{0,31}$') { throw '部署身份无效' }
    }
    Assert-Root $Settings.adminRoot
    Assert-Root $Settings.grafanaRoot
    Assert-Number $Settings.retentionDays 1 365 $true
    if ($Settings.parserExpected -isnot [bool]) { throw '解析预期必须为布尔值' }
    $services = @('app','worker','ai','gateway')
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $targets = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if ($Settings.services -isnot [array] -or $Settings.services.Count -ne 4) { throw '必须显式声明四个应用服务' }
    foreach ($service in $Settings.services) {
        Assert-Keys $service @('service','expected','targets')
        if ($service.service -cnotin $services -or -not $seen.Add($service.service) -or $service.expected -isnot [bool]) { throw '服务声明无效或重复' }
        if ($service.targets -isnot [array] -or $service.targets.Count -gt 32 -or
            ($service.expected -and $service.targets.Count -eq 0) -or (-not $service.expected -and $service.targets.Count -gt 0)) { throw '预期服务与目标不一致' }
        foreach ($target in $service.targets) {
            $uri = $null
            if ($target -isnot [string] -or $target -cnotmatch '^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}:[0-9]{1,5}$' -or
                -not [Uri]::TryCreate("http://$target", [UriKind]::Absolute, [ref]$uri) -or
                $uri.Port -lt 1 -or $uri.Port -gt 65535 -or $uri.IsLoopback -or $uri.Host -eq '0.0.0.0' -or
                -not $targets.Add($target)) { throw '管理目标非法、重复或指向容器自身' }
        }
    }
    $thresholds = @{
        freshnessSeconds = @(60,600,$true); queueAgeSeconds = @(30,86400,$true)
        leaseLossCount = @(1,10000,$true); searchMinRequests = @(1,100000,$true)
        searchFailureRatio = @(0.001,1,$false); retrievalP95Seconds = @(0.01,300,$false)
        degradationRatio = @(0.001,1,$false); modelMinRequests = @(1,100000,$true)
        modelFailureRatio = @(0.001,1,$false); chatMinRequests = @(1,100000,$true)
        chatErrorRatio = @(0.001,1,$false); settlementAgeSeconds = @(30,86400,$true)
    }
    Assert-Keys $Settings.thresholds @($thresholds.Keys)
    foreach ($key in $thresholds.Keys) {
        $range = $thresholds[$key]
        Assert-Number $Settings.thresholds[$key] $range[0] $range[1] $range[2]
    }
    if ($Catalog -isnot [array] -or $Catalog.Count -ne 20) { throw '告警目录不完整' }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $allServices = $services + @('notifier','prometheus','alertmanager','grafana')
    foreach ($entry in $Catalog) {
        Assert-Keys $entry @('name','services','severity','family','summary','link')
        if ($entry.name -cnotmatch '^Kb[A-Za-z]{1,70}$' -or -not $names.Add($entry.name) -or
            $entry.severity -cnotin @('warning','critical') -or $entry.family -cnotmatch '^[a-z-]{1,32}$' -or
            $entry.summary -isnot [string] -or $entry.summary.Length -lt 1 -or $entry.summary.Length -gt 256) { throw '告警目录项无效' }
        if ($entry.services -isnot [array] -or $entry.services.Count -lt 1 -or $entry.services.Count -gt 8 -or
            @($entry.services | Sort-Object -Unique).Count -ne $entry.services.Count) { throw '告警服务无效' }
        foreach ($service in $entry.services) { if ($service -cnotin $allServices) { throw '告警包含未知服务' } }
        Assert-Keys $entry.link @('root','path')
        $paths = switch -CaseSensitive ($entry.link.root) {
            'admin' { @('/admin/parse/dead-letters','/admin/embedding','/admin/ai/audits') }
            'grafana' { @('/d/kb-overview','/d/kb-parse-index','/d/kb-search-ai') }
            default { throw '未知管理根地址类别' }
        }
        if ($entry.link.path -cnotin $paths) { throw '非固定管理路径' }
    }
    Assert-Keys $Settings.waitSeconds @($names)
    foreach ($name in $names) { Assert-Number $Settings.waitSeconds[$name] 30 86400 $true }
}

function Write-MonitoringOutput([string] $OutputDirectory, [Collections.IDictionary] $Files) {
    $output = [IO.Path]::GetFullPath($OutputDirectory)
    if ($output -eq [IO.Path]::GetPathRoot($output)) { throw '禁止写入根目录' }
    # 不跟随目录或文件链接；预检完成前不写任何目标。
    $cursor = $output
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '生成路径不得经过链接' }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    $marker = '.kb-monitoring-generated'
    if (Test-Path -LiteralPath $output) {
        if (-not (Test-Path -LiteralPath $output -PathType Container)) { throw '输出路径不是目录' }
        $existing = @(Get-ChildItem -LiteralPath $output -Force)
        if ($existing.Count -gt 0 -and (-not (Test-Path -LiteralPath (Join-Path $output $marker)) -or
            [IO.File]::ReadAllText((Join-Path $output $marker)) -cne 'kb-monitoring-v1')) { throw '拒绝覆盖非生成目录' }
        foreach ($file in $existing) {
            if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
                ($file.Name -cne $marker -and -not $Files.Contains($file.Name))) { throw '生成目录包含非目标文件' }
        }
    }
    foreach ($name in $Files.Keys) {
        if ([IO.Path]::GetFileName($name) -cne $name -or $name -match '[\\/:]') { throw '生成文件名越界' }
    }
    [IO.Directory]::CreateDirectory($output) | Out-Null
    foreach ($name in $Files.Keys) { [IO.File]::WriteAllText((Join-Path $output $name), [string]$Files[$name], [Text.UTF8Encoding]::new($false)) }
    [IO.File]::WriteAllText((Join-Path $output $marker), 'kb-monitoring-v1')
}

Export-ModuleMember -Function Read-MonitoringJson,Assert-MonitoringSettings,Write-MonitoringOutput
