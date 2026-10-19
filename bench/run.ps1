$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$JdkBin = 'C:\Users\USUARIO\Downloads\fiji-stable-win64-jdk\Fiji.app\java\win64\zulu8.86.0.25-ca-fx-jdk8.0.452-win_x64\bin'
$Javac = Join-Path $JdkBin 'javac.exe'
$Java = Join-Path $JdkBin 'java.exe'
$Out = Join-Path $PSScriptRoot 'classes'

if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Path $Out | Out-Null

$src = @(
  (Join-Path $Root 'src\HoloBioRectFft.java'),
  (Join-Path $Root 'src\HoloBioRtDhmMath.java'),
  (Join-Path $PSScriptRoot 'RtBench.java'),
  (Join-Path $PSScriptRoot 'FtDump.java')
)

& $Javac -encoding UTF-8 -d $Out @src
if ($LASTEXITCODE -ne 0) { throw 'compile failed' }

& $Java -cp $Out FtDump
& $Java -cp $Out RtBench
