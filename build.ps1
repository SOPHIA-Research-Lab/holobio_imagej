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
# Unpack cobyla classes so they are bundled inside the plugin JAR
Push-Location -LiteralPath $BuildDir
& $Jar xf $CobylaJar
Pop-Location
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

# Webcam-capture runtime libs — Fiji loads all JARs from its jars/ directory
Write-Host '      Copying webcam-capture runtime JARs to Fiji jars/...'
$FijiJars = Join-Path $FijiDir 'jars'
Copy-Item -LiteralPath $WebcamJar -Destination (Join-Path $FijiJars 'webcam-capture-0.3.12.jar') -Force
Copy-Item -LiteralPath $BridjJar  -Destination (Join-Path $FijiJars 'bridj-0.7.0.jar')           -Force

# Helper classes (QPI / Speckle) must also live on Fiji's application classpath.
# Fiji can define plugin classes on AppClassLoader, which does not search plugins/*.jar.
# Omit plugins.config so this copy does not register a second HoloBio menu.
Write-Host '      Installing HoloBio-core.jar to Fiji jars/ (analysis tools)...'
$CoreJar = Join-Path $FijiJars 'HoloBio-core.jar'
$pluginsConfig = Join-Path $BuildDir 'plugins.config'
if (Test-Path -LiteralPath $pluginsConfig) {
    Remove-Item -LiteralPath $pluginsConfig -Force
}
Push-Location -LiteralPath $BuildDir
try {
    & $Jar cf $CoreJar .
    if ($LASTEXITCODE -ne 0) {
        Write-Error 'HoloBio-core.jar creation failed.'
    }
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '============================================'
Write-Host 'BUILD SUCCESSFUL'
Write-Host ('JAR: ' + $OutJar)
Write-Host ('Installed to: ' + $FijiPlugins)
Write-Host '============================================'
Write-Host 'Restart Fiji to use the plugin.'
