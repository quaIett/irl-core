param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME,
    [string]$SourceDirectory,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (!$SourceDirectory) { $SourceDirectory = Join-Path $repo 'src/main/java/org/qualet/irl/light' }
if (!$GradleUserHome) { $GradleUserHome = Join-Path $env:USERPROFILE '.gradle' }
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repo 'build/upload-gl-test' }
$classes = Join-Path $OutputDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$jars = [Collections.Generic.List[string]]::new()
foreach ($module in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl')) {
    $modulePath = Join-Path $GradleUserHome "caches/modules-2/files-2.1/org.lwjgl/$module/3.3.3"
    foreach ($filename in @("$module-3.3.3.jar", "$module-3.3.3-natives-windows.jar")) {
        $found = @(Get-ChildItem -LiteralPath $modulePath -Recurse -Filter $filename)
        if ($found.Count -ne 1) { throw "Expected one cached $filename under $modulePath" }
        $jars.Add($found[0].FullName)
    }
}
$jomlRoot = Join-Path $GradleUserHome 'caches/modules-2/files-2.1/org.joml/joml/1.10.5'
$jars.Add((Get-ChildItem -LiteralPath $jomlRoot -Recurse -Filter 'joml-1.10.5.jar' | Select-Object -First 1 -ExpandProperty FullName))
$classpath = $jars -join [IO.Path]::PathSeparator
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$sources = @('LightBuffer.java', 'LightProfile.java', 'LightProfilesBuffer.java', 'LightRegistry.java', 'VlGlobalsBuffer.java', 'ClusterGridBuffer.java') | ForEach-Object { Join-Path $SourceDirectory $_ }
& $javac --release 17 -proc:none -encoding UTF-8 -cp $classpath -d $classes @sources (Join-Path $PSScriptRoot 'upload/UploadGlTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Upload GL harness compilation failed' }
& $java '-Djava.awt.headless=true' -cp "$classes$([IO.Path]::PathSeparator)$classpath" UploadGlTest $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Upload GL harness failed' }
& $javac --release 17 -proc:none -encoding UTF-8 -cp "$classes$([IO.Path]::PathSeparator)$classpath" -d $classes (Join-Path $PSScriptRoot 'upload/ProfileRegistryGlTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Profile registry harness compilation failed' }
& $java '-Djava.awt.headless=true' -cp "$classes$([IO.Path]::PathSeparator)$classpath" ProfileRegistryGlTest $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Profile registry harness failed' }
