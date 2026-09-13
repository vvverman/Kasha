param([string]$Version = "1.2.0")

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$Desktop = Join-Path $Repo "platforms\desktop"
$Build = Join-Path $Desktop "build"
$Dist = Join-Path $Build "windows-dist"
$MsiOut = Join-Path $Dist "msi"
$ExeOut = Join-Path $Dist "exe"
$Temp = Join-Path $Build "windows-jpackage-temp"
$ResourceDir = Join-Path $PSScriptRoot "windows-jpackage"
$InnoScript = Join-Path $PSScriptRoot "windows-inno\Kasha.iss"

Set-Location $Repo

Write-Host "== Compose Windows app image =="
& gradle :desktopApp:createDistributable
if ($LASTEXITCODE -ne 0) { throw "createDistributable failed with exit code $LASTEXITCODE" }

$AppImage = Join-Path $Desktop "build\compose\binaries\main\app\Kasha"
if (!(Test-Path (Join-Path $AppImage "Kasha.exe"))) { throw "Compose app image not found: $AppImage" }

$Resources = Join-Path $AppImage "app\resources"
foreach ($relative in @(
    "bin\whisper-cli.exe",
    "bin\llama-completion.exe",
    "bin\ffmpeg.exe",
    "models\ggml-small.bin",
    "models\Qwen3-4B-Q4_K_M.gguf"
)) {
    if (!(Test-Path (Join-Path $Resources $relative))) { throw "Bundled payload is missing $relative" }
}

Remove-Item $Dist -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $Temp -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $MsiOut, $ExeOut, $Temp | Out-Null

$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
if (!(Test-Path $jpackage)) { throw "jpackage.exe not found under JAVA_HOME=$env:JAVA_HOME" }

Write-Host "== MSI with external split CABs =="
$jpackageArgs = @(
    "--type", "msi",
    "--app-image", $AppImage,
    "--dest", $MsiOut,
    "--temp", $Temp,
    "--name", "Kasha",
    "--app-version", $Version,
    "--vendor", "Vyacheslav Verman",
    "--description", "Kasha local-first voice notes and tasks",
    "--win-per-user-install",
    "--win-dir-chooser",
    "--win-menu",
    "--win-menu-group", "Kasha",
    "--win-shortcut",
    "--resource-dir", $ResourceDir,
    "--verbose"
)
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage MSI failed with exit code $LASTEXITCODE" }

$Msi = Get-ChildItem $MsiOut -Filter "*.msi" -File | Select-Object -First 1
if ($null -eq $Msi) { throw "MSI was not produced" }
$Cabs = @(Get-ChildItem $MsiOut -Filter "*.cab" -File | Sort-Object Name)
if ($Cabs.Count -lt 2) { throw "Expected multiple external CABs for the bundled model payload" }
foreach ($cab in $Cabs) {
    if ($cab.Length -ge 1_900_000_000) { throw "CAB $($cab.Name) is too large: $($cab.Length) bytes" }
}

Write-Host "== Self-contained Inno Setup EXE =="
$isccCommand = Get-Command "ISCC.exe" -ErrorAction SilentlyContinue
$iscc = if ($null -ne $isccCommand) { $isccCommand.Source } else { $null }
if (!$iscc) {
    $candidates = @(
        (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
        (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe")
    )
    $iscc = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
}
if (!$iscc) { throw "Inno Setup compiler ISCC.exe was not found" }

& $iscc "/DAppImage=$AppImage" "/DOutputDir=$ExeOut" "/DAppVersion=$Version" $InnoScript
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed with exit code $LASTEXITCODE" }

$SetupExe = Get-ChildItem $ExeOut -Filter "*.exe" -File | Select-Object -First 1
if ($null -eq $SetupExe) { throw "Self-contained setup EXE was not produced" }
if ($SetupExe.Length -ge 4_000_000_000) { throw "Setup EXE is too close to the Windows single-executable size ceiling: $($SetupExe.Length) bytes" }

$ManifestPath = Join-Path $Dist "windows-packages.txt"
$HashesPath = Join-Path $Dist "SHA256SUMS.txt"
Remove-Item $HashesPath -Force -ErrorAction SilentlyContinue
$Artifacts = @($Msi.FullName) + @($Cabs.FullName) + @($SetupExe.FullName)
$Artifacts | Set-Content $ManifestPath -Encoding UTF8
foreach ($path in $Artifacts) {
    $item = Get-Item $path
    $hash = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($item.Name)" | Add-Content $HashesPath -Encoding UTF8
    Write-Host ("{0,14:N0}  {1}" -f $item.Length, $item.Name)
}

Write-Host "Windows packaging complete: $Dist"
