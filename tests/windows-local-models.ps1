$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Repo
$App = Join-Path $Repo 'desktopApp/build/compose/binaries/main/app/Kasha'
$Exe = Join-Path $App 'Kasha.exe'
$Out = Join-Path $Repo 'test-output/desktop-ai/windows'
$Fixture = Join-Path $Repo 'test-output/real-models/russian-with-pauses.wav'
if (!(Test-Path $Exe) -or !(Test-Path $Fixture)) { throw 'Application or synthetic speech fixture is missing' }
New-Item -ItemType Directory -Force $Out | Out-Null
# Ограничение касается только тестового Kasha и его дочерних движков, не runner/ОС.
$Programs = @($Exe) + @(Get-ChildItem (Join-Path $App 'app/resources/bin') -Filter '*.exe' -File | ForEach-Object FullName)
$Programs += @(Get-ChildItem (Join-Path $App 'runtime/bin') -Filter 'java*.exe' -File -ErrorAction SilentlyContinue | ForEach-Object FullName)
$RuleNames = @()
$Process = $null
try {
    foreach ($Program in $Programs) {
        $Name = 'Kasha-AI-check-' + [guid]::NewGuid().ToString('N')
        New-NetFirewallRule -Name $Name -DisplayName $Name -Direction Outbound -Action Block -Profile Any -Program $Program | Out-Null
        $RuleNames += $Name
    }
    Get-NetFirewallRule -Name $RuleNames | Select-Object Name,Enabled,Direction,Action | ConvertTo-Json | Set-Content (Join-Path $Out 'firewall.json')
    $Result = Join-Path $Out 'application'
    $Args = @('--self-test', "`"$Result`"", "`"$Fixture`"")
    $Process = Start-Process -FilePath $Exe -ArgumentList $Args -PassThru `
        -RedirectStandardOutput (Join-Path $Out 'application.log') -RedirectStandardError (Join-Path $Out 'application.stderr.log')
    if (!$Process.WaitForExit(1200000)) { throw 'Local model inference did not finish within the test limit' }
    $Process.Refresh()
    Get-Content (Join-Path $Out 'application.log') | Write-Host
    Get-Content (Join-Path $Out 'application.stderr.log') | Write-Host
    if ($Process.ExitCode -ne 0) { throw "Kasha local model test failed: $($Process.ExitCode)" }
    $Evidence = Get-Content (Join-Path $Result 'self-test.json') -Raw | ConvertFrom-Json
    if (!$Evidence.passed -or !$Evidence.applicationRouter -or $Evidence.externalNetworkReachable) {
        throw 'Native execution without external network was not confirmed'
    }
} finally {
    if ($null -ne $Process -and !$Process.HasExited) {
        & taskkill /PID $Process.Id /T /F | Out-Null
    }
    if ($RuleNames.Count -gt 0) { Remove-NetFirewallRule -Name $RuleNames }
}
