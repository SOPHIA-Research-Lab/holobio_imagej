# HoloBio ImageJ plugin build (PowerShell; handles paths with spaces).
$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
$FijiDir = 'C:\Users\USUARIO\Downloads\fiji-stable-win64-jdk\Fiji.app'
$Javac = Join-Path $FijiDir 'java\win64\zulu8.86.0.25-ca-fx-jdk8.0.452-win_x64\bin\javac.exe'
$Jar = Join-Path $FijiDir 'java\win64\zulu8.86.0.25-ca-fx-jdk8.0.452-win_x64\bin\jar.exe'
$IjJar = Join-Path $FijiDir 'jars\ij-1.54p.jar'
$SrcDir = Join-Path $Root 'src'
$BuildDir = Join-Path $Root 'build'
$OutJar = Join-Path $Root 'HoloBio_.jar'

if (-not (Test-Path -LiteralPath $Javac)) {
    Write-Error ('javac not found at ' + $Javac + '; update $FijiDir in build.ps1.')
}

Write-Host '[1/5] Cleaning build directory...'
if (Test-Path -LiteralPath $BuildDir) {
    Remove-Item -LiteralPath $BuildDir -Recurse -Force
}
New-Item -ItemType Directory -Path $BuildDir | Out-Null

Write-Host '[2/5] Compiling Java sources...'
$sources = Get-ChildItem -LiteralPath $SrcDir -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
if (-not $sources) {
    Write-Error ('No .java files under ' + $SrcDir)
}
& $Javac -encoding UTF-8 -cp $IjJar -d $BuildDir @sources
if ($LASTEXITCODE -ne 0) {
    Write-Error 'Compilation failed.'
}

Write-Host '[3/5] Packaging JAR...'
Copy-Item -LiteralPath (Join-Path $SrcDir 'plugins.config') -Destination (Join-Path $BuildDir 'plugins.config') -Force
Push-Location -LiteralPath $BuildDir
try {
    & $Jar cf $OutJar .
    if ($LASTEXITCODE -ne 0) {
        Write-Error 'JAR creation failed.'
    }
} finally {
    Pop-Location
}

Write-Host '[4/5] Running HoloBioRectFftVerify...'
$Java = Join-Path $FijiDir 'java\win64\zulu8.86.0.25-ca-fx-jdk8.0.452-win_x64\bin\java.exe'
& $Java -cp $BuildDir HoloBioRectFftVerify
if ($LASTEXITCODE -ne 0) {
    Write-Error 'HoloBioRectFftVerify failed.'
}

Write-Host '[5/5] Installing to Fiji plugins folder...'
$FijiPlugins = Join-Path $FijiDir 'plugins\HoloBio_.jar'
Copy-Item -LiteralPath $OutJar -Destination $FijiPlugins -Force

Write-Host ''
Write-Host '============================================'
Write-Host 'BUILD SUCCESSFUL'
Write-Host ('JAR: ' + $OutJar)
Write-Host ('Installed to: ' + $FijiPlugins)
Write-Host '============================================'
Write-Host 'Restart Fiji to use the plugin.'
