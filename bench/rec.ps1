$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Fiji = 'C:\Users\USUARIO\Downloads\fiji-stable-win64-jdk\Fiji.app'
$JdkBin = Join-Path $Fiji 'java\win64\zulu8.86.0.25-ca-fx-jdk8.0.452-win_x64\bin'
$Javac = Join-Path $JdkBin 'javac.exe'
$Java = Join-Path $JdkBin 'java.exe'
$Out = Join-Path $PSScriptRoot 'rec-classes'

$IjJar = Get-ChildItem -Path (Join-Path $Fiji 'jars') -Filter 'ij-*.jar' |
         Select-Object -First 1 -ExpandProperty FullName
if (-not $IjJar) { throw 'ij jar not found' }

if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Path $Out | Out-Null

$src = @(
  (Join-Path $Root 'src\HoloBioRtRecorder.java'),
  (Join-Path $PSScriptRoot 'RecTest.java')
)

& $Javac -encoding UTF-8 -cp $IjJar -d $Out @src
if ($LASTEXITCODE -ne 0) { throw 'compile failed' }

Push-Location $Root
& $Java -cp "$Out;$IjJar" RecTest
Pop-Location
