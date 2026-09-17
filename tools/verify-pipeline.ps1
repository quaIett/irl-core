param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$JomlJar,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$code = Split-Path -Parent $repo
$workspace = Split-Path -Parent $code
$testRoot = Join-Path $repo 'build/pipeline-test'
$classes = Join-Path $testRoot 'classes'
$stubs = Join-Path $testRoot 'stubs'
New-Item -ItemType Directory -Force -Path $classes, $stubs | Out-Null
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
if (!$JomlJar) {
    $cache = if ($GradleUserHome) { $GradleUserHome } else { Join-Path $workspace '.gradle-user' }
    $jomlRoot = Join-Path $cache 'caches/modules-2/files-2.1/org.joml/joml'
    $JomlJar = Get-ChildItem -LiteralPath $jomlRoot -Recurse -Filter 'joml-*.jar' |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (!$JomlJar -or !(Test-Path -LiteralPath $JomlJar)) { throw 'Pass -JomlJar with the cached production JOML jar' }

# Every shipped variant uses header.w, including both surface and VL passes.
# The legacy region declaration/lookup stays fixed even in the wide variants.
$patchRoots = @(
    (Join-Path $code 'bbs-irlights-addon/patches'),
    (Join-Path $code 'bbs-dof-addon/patches'),
    (Join-Path $code 'irlights/src/client/resources/assets/irl-redactor/patches')
)
$variants = 0
foreach ($root in $patchRoots) {
    foreach ($file in Get-ChildItem -LiteralPath $root -File -Filter '*.irlights') {
        $shader = [IO.File]::ReadAllText($file.FullName)
        if ($shader -notmatch 'irlite_clusterWide') { throw "Cluster contract missing: $($file.FullName)" }
        $shader = [regex]::Replace($shader, '(?s)/\*.*?\*/|//[^\r\n]*', '')
        if ($shader -notmatch 'uvec4\s+irlite_clusterHeader\s*;\s*uvec2\s+irlite_clusterMasks\s*\[576\]\s*;\s*uint\s+irlite_clusterWide\s*\[\]\s*;') {
            throw "Cluster fixed region layout changed: $($file.FullName)"
        }
        $bases = [regex]::Matches($shader, 'uint\s+irlite_clusterWideBase\([^)]*\)\s*\{([^}]*)\}')
        if ($bases.Count -ne 2) { throw "Expected surface and VL wide bases: $($file.FullName)" }
        foreach ($base in $bases) {
            if ($base.Groups[1].Value -notmatch 'return\s+\(ty\s*\*\s*gridX\s*\+\s*tx\)\s*\*\s*irlite_clusterHeader\.w\s*;') {
                throw "Hard-coded wide stride: $($file.FullName)"
            }
        }
        $reads = [regex]::Matches($shader, 'irlite_clusterWide\s*\[([^\]]+)\]')
        if ($reads.Count -ne 2) { throw "Unexpected wide mask accesses: $($file.FullName)" }
        foreach ($read in $reads) {
            if ($read.Groups[1].Value -notmatch '^irlWideBase\s*\+\s*\(i\s*>>\s*5u\)$') {
                throw "Unexpected wide mask index: $($file.FullName)"
            }
            $prefix = $shader.Substring(0, $read.Index)
            $lastCount = $prefix.LastIndexOf('uint count = irlite_lightCount;')
            $lastLoop = $prefix.LastIndexOf('for (uint i = 0u; i < count; i++)')
            if ($lastCount -lt 0 -or $lastLoop -lt $lastCount) { throw "Wide read not bounded by packed light count: $($file.FullName)" }
        }
        if ([regex]::Matches($shader, 'return\s+irlite_clusterMasks\s*\[ty\s*\*\s*gridX\s*\+\s*tx\]\s*;').Count -ne 2) {
            throw "Legacy mask addressing changed: $($file.FullName)"
        }
        $variants++
    }
}
if ($variants -lt 21) { throw "Expected all 21 shipped shader variants, found $variants" }
Write-Output "Shader ABI verified: $variants variants, both surface/VL runtime strides and fixed legacy offsets."

# Test doubles stop at the GL/Iris boundary; actual production Java below is
# compiled unchanged. Uploads retain their bytes so layout assertions include
# ByteBuffer limits, native byte order and the fixed-offset cached IntBuffer view.
$stubSources = @{
    'org/lwjgl/opengl/GL11.java' = @'
package org.lwjgl.opengl;
public final class GL11 { public static final int GL_TEXTURE_2D = 3553; }
'@
    'org/lwjgl/opengl/GL15.java' = @'
package org.lwjgl.opengl;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
public final class GL15 {
    public static final int GL_DYNAMIC_DRAW = 35048;
    public static ByteBuffer lastUpload;
    public static int uploads;
    public static int glGenBuffers() { return 1; }
    public static void glBindBuffer(int target, int buffer) {}
    public static void glBufferData(int target, long bytes, int usage) {}
    public static void glDeleteBuffers(int buffer) {}
    public static void glBufferSubData(int target, long offset, ByteBuffer bytes) {
        lastUpload = ByteBuffer.allocate(bytes.remaining()).order(ByteOrder.nativeOrder());
        lastUpload.put(bytes.duplicate()).flip();
        uploads++;
    }
}
'@
    'org/lwjgl/opengl/GL30.java' = @'
package org.lwjgl.opengl;
public final class GL30 {
    public static final int GL_TEXTURE_2D_ARRAY = 35866;
    public static int binds;
    public static void glBindBufferBase(int target, int binding, int buffer) { binds++; }
}
'@
    'org/lwjgl/opengl/GL40.java' = @'
package org.lwjgl.opengl;
public final class GL40 { public static final int GL_TEXTURE_CUBE_MAP_ARRAY = 36873; }
'@
    'org/lwjgl/opengl/GL43.java' = @'
package org.lwjgl.opengl;
public final class GL43 { public static final int GL_SHADER_STORAGE_BUFFER = 37074; }
'@
    'org/lwjgl/system/MemoryUtil.java' = @'
package org.lwjgl.system;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
public final class MemoryUtil {
    public static ByteBuffer memAlloc(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()); }
    public static void memFree(ByteBuffer bytes) {}
}
'@
    'org/qualet/irl/light/LightBuffer.java' = @'
package org.qualet.irl.light;
public final class LightBuffer { public static final int MAX_LIGHTS = 2048; }
'@
    'org/qualet/irl/light/BlueNoiseTexture.java' = @'
package org.qualet.irl.light;
public final class BlueNoiseTexture { public static int getId() { return 0; } }
'@
    'net/irisshaders/iris/gl/IrisRenderSystem.java' = @'
package net.irisshaders.iris.gl;
public final class IrisRenderSystem {
    public static int target, unit, id, binds;
    public static void bindTextureToUnit(int t, int u, int i) { target=t; unit=u; id=i; binds++; }
}
'@
    'net/irisshaders/iris/gl/program/ProgramSamplers.java' = @'
package net.irisshaders.iris.gl.program;
import java.util.function.IntSupplier;
public final class ProgramSamplers {
    public static final class Builder { public boolean addDynamicSampler(IntSupplier id, String... name) { return true; } }
}
'@
}
foreach ($class in @('PointDepthAtlas', 'SpotlightDepthAtlas', 'SpotShadowPyramid', 'SpotShadowEvsm')) {
    $stubSources["org/qualet/irl/light/shadow/$class.java"] = "package org.qualet.irl.light.shadow; public final class $class { public static int getGlTextureId() { return 0; } }"
}
foreach ($class in @('PointShadowPyramid', 'PointShadowEvsm')) {
    $stubSources["org/qualet/irl/light/shadow/$class.java"] = "package org.qualet.irl.light.shadow; public final class $class { public static int getGlTextureId(int tier) { return 0; } }"
}
$sources = @()
foreach ($entry in $stubSources.GetEnumerator()) {
    $path = Join-Path $stubs $entry.Key
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, $entry.Value, [Text.UTF8Encoding]::new($false))
    $sources += $path
}
$src = Join-Path $repo 'src/main/java/org/qualet/irl/light'
$sources += (Join-Path $src 'ClusterGridBuffer.java')
$sources += (Join-Path $src 'IrlSamplers.java')
$sources += (Join-Path $src 'iris/IrlSamplersBind.java')
$sources += (Join-Path $PSScriptRoot 'pipeline/PipelineTest.java')
& $javac --release 17 -encoding UTF-8 -cp $JomlJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Pipeline test compilation failed' }
& $java -cp "$classes;$JomlJar" org.qualet.irl.light.PipelineTest
if ($LASTEXITCODE -ne 0) { throw 'Pipeline tests failed' }
