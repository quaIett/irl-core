param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME,
    [string]$CacheSource
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (!$GradleUserHome) { $GradleUserHome = Join-Path $repo '../../.gradle-user' }
$fastutil = Get-ChildItem -LiteralPath (Join-Path $GradleUserHome 'caches/modules-2/files-2.1/it.unimi.dsi/fastutil') -Recurse -Filter 'fastutil-*.jar' |
    Where-Object Name -NotMatch '(sources|javadoc)' | Select-Object -First 1
if (!$fastutil) { throw 'Fastutil not cached; run the core build or provide -GradleUserHome.' }
if (!$CacheSource) { $CacheSource = Join-Path $repo 'src/main/java/org/qualet/irl/light/shadow/BlockShadowCache.java' }
$classes = Join-Path $repo 'build/block-cache-test'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'block-cache') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $javac --release 17 -encoding UTF-8 -cp $fastutil.FullName -d $classes $CacheSource @sources
if ($LASTEXITCODE -ne 0) { throw 'Block cache test compilation failed' }
& $java -cp ($classes + [IO.Path]::PathSeparator + $fastutil.FullName) org.qualet.irl.light.shadow.BlockShadowCacheTest
if ($LASTEXITCODE -ne 0) { throw 'Block cache tests failed' }
