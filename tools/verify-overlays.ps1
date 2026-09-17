param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$src = Join-Path $repo 'src/main/java/org/qualet/irl/light/shadow'
$classes = Join-Path $repo 'build/overlay-test'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
& $javac --release 17 -encoding UTF-8 -d $classes (Join-Path $src 'CasterRevision.java') (Join-Path $src 'ShadowOverlayCache.java') (Join-Path $PSScriptRoot 'overlay/ShadowOverlayCacheTest.java') (Join-Path $PSScriptRoot 'overlay/SpotMembersTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Shadow overlay test compilation failed' }
& $java -cp $classes org.qualet.irl.light.shadow.ShadowOverlayCacheTest
if ($LASTEXITCODE -ne 0) { throw 'Shadow overlay tests failed' }
& $java -cp $classes org.qualet.irl.light.shadow.SpotMembersTest
if ($LASTEXITCODE -ne 0) { throw 'Spot member tests failed' }
