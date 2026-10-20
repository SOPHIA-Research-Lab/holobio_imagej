# HoloBio ImageJ plugin build (PowerShell; handles paths with spaces).
$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
$FijiDir = 'C:\Users\USUARIO\Downloads\fiji-stable-win64-jdk\Fiji.app'
$Javac = Join-Path $FijiDir 'java\win64\zulu8.86.0.25-ca-fx-jdk8.0.452-win_x64\bin\javac.exe'
$Jar = Join-Path $FijiDir 'java\win64\zulu8.86.0.25-ca-fx-jdk8.0.452-win_x64\bin\jar.exe'
$IjJar = Join-Path $FijiDir 'jars\ij-1.54p.jar'
$CobylaJar   = Join-Path $Root 'lib\jcobyla-1.4.jar'
$WebcamJar   = Join-Path $Root 'lib\webcam-capture-0.3.12.jar'
$BridjJar    = Join-Path $Root 'lib\bridj-0.7.0.jar'
$JnaJar      = Join-Path $FijiDir 'jars\jna-5.14.0.jar'  # ships with Fiji; compile-time only
$JamaJar     = Join-Path $FijiDir 'jars\jama-1.0.3.jar'    # ships with Fiji; compile-time only
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
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& $Javac -encoding UTF-8 -cp "$IjJar;$CobylaJar;$WebcamJar;$BridjJar;$JnaJar;$JamaJar" -d $BuildDir @sources
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $prev
if ($javacExit -ne 0) { Write-Error 'Compilation failed.' }

Write-Host '[3/5] Packaging JAR...'
Copy-Item -LiteralPath (Join-Path $SrcDir 'plugins.config') -Destination (Join-Path $BuildDir 'plugins.config') -Force
# Unpack cobyla, webcam-capture and BridJ so they are bundled inside the plugin JAR
# (and the helper JAR below, which is packed from the same folder).
Push-Location -LiteralPath $BuildDir
& $Jar xf $CobylaJar
& $Jar xf $WebcamJar
& $Jar xf $BridjJar
Pop-Location
Copy-Item -LiteralPath (Join-Path $Root 'lib\THIRD-PARTY-NOTICES.txt') -Destination (Join-Path $BuildDir 'META-INF\THIRD-PARTY-NOTICES.txt') -Force
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

# webcam-capture and BridJ are now inside HoloBio_.jar, and the HoloBio-core.jar helper is no
# longer needed (HoloBioFijiUi loads the QPI / Speckle dialogs from the plugin JAR itself).
# Remove copies left in jars/ by older builds so Fiji does not see the same classes twice.
$FijiJars = Join-Path $FijiDir 'jars'
foreach ($old in @('webcam-capture-0.3.12.jar', 'bridj-0.7.0.jar', 'HoloBio-core.jar')) {
    $p = Join-Path $FijiJars $old
    if (Test-Path -LiteralPath $p) {
        Write-Host ('      Removing from Fiji jars/: ' + $old)
        try {
            Remove-Item -LiteralPath $p -Force -ErrorAction Stop
        } catch {
            Write-Warning ('Could not remove ' + $old + ' (is Fiji running?). Close Fiji and delete it by hand.')
        }
    }
}

Write-Host ''
Write-Host '============================================'
Write-Host 'BUILD SUCCESSFUL'
Write-Host ('JAR: ' + $OutJar)
Write-Host ('Installed to: ' + $FijiPlugins)
Write-Host '============================================'
Write-Host 'Restart Fiji to use the plugin.'
