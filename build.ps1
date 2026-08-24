param(
    [string]$Configuration = "Release",
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"

$project = "src\WhisperByYashasVM\WhisperByYashasVM.csproj"
$publishDir = "src\WhisperByYashasVM\bin\$Configuration\net8.0-windows\win-x64\publish"
$installerScript = "installer\WhisperByYashasVM.iss"

Write-Host "Publishing app..."
$dotnet = "dotnet"
if (Test-Path ".\.dotnet\dotnet.exe") {
    $dotnet = ".\.dotnet\dotnet.exe"
}
& $dotnet publish $project -c $Configuration -r win-x64 --self-contained true `
    /p:PublishSingleFile=true `
    /p:IncludeNativeLibrariesForSelfExtract=true `
    /p:PublishReadyToRun=true

if (-not (Test-Path $publishDir)) {
    throw "Publish output not found: $publishDir"
}

Write-Host "Building installer..."
$isccCandidates = @(
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles}\Inno Setup 6\ISCC.exe",
    "${env:LOCALAPPDATA}\Programs\Inno Setup 6\ISCC.exe"
)
$isccCommand = Get-Command iscc.exe -ErrorAction SilentlyContinue
if ($isccCommand) {
    $isccCandidates = @($isccCommand.Source) + $isccCandidates
}
$iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) {
    throw "Inno Setup ISCC.exe not found."
}

& $iscc "/DAppVersion=$Version" $installerScript

Write-Host "Done."
