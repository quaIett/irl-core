param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$src = Join-Path $repo 'src/main/java/org/qualet/irl/light/shadow'
$classes = Join-Path $repo 'build/budget-test'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$sources = @(
    (Join-Path $src 'ShadowVramBudget.java'),
    (Join-Path $src 'ShadowAllocLog.java'),
    (Join-Path $PSScriptRoot 'budget/ShadowVramBudgetTest.java'),
    (Join-Path $PSScriptRoot 'budget/GL.java'),
    (Join-Path $PSScriptRoot 'budget/GL11.java')
)
& $javac --release 17 -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Shadow VRAM budget test compilation failed' }
& $java '-Dirlite.shadowVramReserveMb=2560' '-Dirlite.shadowVramBudgetMb=-1' -cp $classes org.qualet.irl.light.shadow.ShadowVramBudgetTest
if ($LASTEXITCODE -ne 0) { throw 'Shadow VRAM budget tests failed' }
