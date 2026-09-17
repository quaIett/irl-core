param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$src = Join-Path $repo 'src/main/java/org/qualet/irl/light/shadow'
$testRoot = Join-Path $repo 'build/caster-pool-test'
$classes = Join-Path $testRoot 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$production = [IO.File]::ReadAllText((Join-Path $src 'ShadowBaker.java'))
$method = [regex]::Match($production, '(?s)    private static void put\(.*?(?=    private static void collect\()').Value
if (!$method) { throw 'Production put method not found' }
$fields = @()
foreach ($name in @('MAX_OCCLUDERS', 'occ', 'occType', 'ox', 'oy', 'oz', 'orad', 'orh', 'ohv', 'oStatic', 'ostatichash', 'odist2', 'farthestOccHeap', 'occCount', 'farthestOccIdx', 'farthestOccDist2', 'occCamX', 'occCamY', 'occCamZ')) {
    $field = [regex]::Match($production, '(?m)^    private static (?:final )?[^\r\n;]+?\s+' + $name + '\s*(?:=[^;]*)?;').Value
    if (!$field) { throw "Production field missing: $name" }
    $fields += $field
}
$collect = $production.Substring($production.IndexOf('    private static void collect('))
$reset = [regex]::Match($collect, '(?s)        occCount = 0;.*?(?=        Arrays.fill\(spotMembersBySlot)').Value
if (!$reset) { throw 'Production pool reset not found' }
# Compile the actual put body/fields/reset unchanged except package visibility;
# the sole boundary double records telemetry. No reimplementation of new logic.
foreach ($entry in @{
    ProductionPool = $method
    ReferencePool = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'pool/reference/ShadowBaker.put.java.txt'))
}.GetEnumerator()) {
    $entryReset = if ($entry.Key -eq 'ReferencePool') { [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'pool/reference/ShadowBaker.reset.java.txt')) } else { $reset }
    $body = "package org.qualet.irl.light.shadow;`nfinal class $($entry.Key) {`n" + ($fields -join "`n") + "`n" + $entry.Value + "`nstatic void reset() {`n" + $entryReset + "}`n}"
    $body = $body.Replace('private static', 'static')
    [IO.File]::WriteAllText((Join-Path $testRoot ($entry.Key + '.java')), $body, [Text.UTF8Encoding]::new($false))
}
$sources = @(
    (Join-Path $src 'FarthestCasterHeap.java'),
    (Join-Path $testRoot 'ProductionPool.java'),
    (Join-Path $testRoot 'ReferencePool.java'),
    (Join-Path $PSScriptRoot 'pool/CasterPoolTest.java')
)
& $javac --release 17 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Caster pool test compilation failed' }
& $java -cp $classes org.qualet.irl.light.shadow.CasterPoolTest
if ($LASTEXITCODE -ne 0) { throw 'Caster pool tests failed' }

# Separate instrumentation build counts comparisons in the SAME source bodies.
# Timings/allocation above always run the uninstrumented production helper.
$instrumented = Join-Path $testRoot 'instrumented'
New-Item -ItemType Directory -Force -Path $instrumented | Out-Null
$heap = [IO.File]::ReadAllText((Join-Path $src 'FarthestCasterHeap.java'))
$heap = $heap.Replace('private boolean ready;', 'private boolean ready; static long comparisons;')
$heap = $heap.Replace('float da = distances[a], db = distances[b];', 'comparisons++; float da = distances[a], db = distances[b];')
[IO.File]::WriteAllText((Join-Path $instrumented 'FarthestCasterHeap.java'), $heap, [Text.UTF8Encoding]::new($false))
$reference = [IO.File]::ReadAllText((Join-Path $testRoot 'ReferencePool.java'))
$reference = $reference.Replace('final class ReferencePool {', 'final class ReferencePool { static long comparisons;')
$reference = $reference.Replace('if (odist2[k] > farthestOccDist2)', 'comparisons++; if (odist2[k] > farthestOccDist2)')
[IO.File]::WriteAllText((Join-Path $instrumented 'ReferencePool.java'), $reference, [Text.UTF8Encoding]::new($false))
& $javac --release 17 -encoding UTF-8 -d $instrumented (Join-Path $instrumented 'FarthestCasterHeap.java') (Join-Path $instrumented 'ReferencePool.java') (Join-Path $testRoot 'ProductionPool.java') (Join-Path $PSScriptRoot 'pool/CasterPoolTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Instrumented pool compilation failed' }
& $java -cp $instrumented org.qualet.irl.light.shadow.CasterPoolTest --operations
if ($LASTEXITCODE -ne 0) { throw 'Pool comparison counts failed' }
