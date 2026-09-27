# ============================================================
#  MiFitDeviceMask - one-command APK build (no Gradle required)
#  Pipeline: javac -> aapt2 link -> d8 -> zip assemble -> zipalign -> apksigner
#
#  Prerequisites (one-time, see docs/WORKFLOW.md):
#    1. tools\build-check\cache\jdk\        JDK 17+ (javac/java/keytool)
#    2. tools\build-check\cache\android.jar + api/service/annotation/kotlin jars
#    3. build-tools dir with aapt2.exe / zipalign.exe / lib\d8.jar / lib\apksigner.jar
#       (pass via -BuildToolsDir; default %TEMP%\mfm_bt\bt as fetched by the
#        documented download command)
#
#  Usage (from anywhere):
#    powershell -ExecutionPolicy Bypass -File <workspace>\tools\build-apk.ps1 `
#        [-BuildToolsDir <dir>]
#  Output: dist\MiFitDeviceMask-v<versionName>.apk  (test-signed, v1+v2)
#  Signing key: tools\signing\mifitmask.p12 (pass: mifitmask2026)
#  IMPORTANT: reuse the same key for every future version so upgrades install
#  over previous builds without uninstalling.
# ============================================================
param(
    [string]$JavaDir = '',
    [string]$BuildToolsDir = ''
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root  = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $PSScriptRoot 'build-check\cache'
if (-not $JavaDir)      { $JavaDir       = Join-Path $cache 'jdk' }
if (-not $BuildToolsDir){ $BuildToolsDir = "$env:TEMP\mfm_bt\bt" }

$java    = Join-Path $JavaDir 'bin\java.exe'
$javac   = Join-Path $JavaDir 'bin\javac.exe'
$keytool = Join-Path $JavaDir 'bin\keytool.exe'
$aapt2   = Join-Path $BuildToolsDir 'aapt2.exe'
$zipalign= Join-Path $BuildToolsDir 'zipalign.exe'
$d8jar   = Join-Path $BuildToolsDir 'lib\d8.jar'
$apsjar  = Join-Path $BuildToolsDir 'lib\apksigner.jar'
foreach ($f in @($java, $javac, $keytool, $aapt2, $zipalign, $d8jar, $apsjar)) {
    if (-not (Test-Path $f)) { throw "missing tool: $f" }
}

# ---- version from app/build.gradle (single source of truth) ----
$gradle = Get-Content (Join-Path $root 'app\build.gradle') -Raw
$vName  = [regex]::Match($gradle, 'versionName\s+"([^"]+)"').Groups[1].Value
$vCode  = [regex]::Match($gradle, 'versionCode\s+(\d+)').Groups[1].Value
$minSdk = [regex]::Match($gradle, 'minSdk\s+(\d+)').Groups[1].Value
$tgtSdk = [regex]::Match($gradle, 'targetSdk\s+(\d+)').Groups[1].Value
$pkg    = [regex]::Match($gradle, 'applicationId\s+"([^"]+)"').Groups[1].Value
if (-not $vName -or -not $vCode) { throw 'cannot parse versionName/versionCode from app/build.gradle' }
Write-Output "build: $pkg v$vName ($vCode), minSdk=$minSdk targetSdk=$tgtSdk"

# ---- workspace inputs ----
$srcJava   = Join-Path $root 'app\src\main\java'
$metaDir   = Join-Path $root 'app\src\main\resources\META-INF\xposed'
$manifest  = Join-Path $root 'app\src\main\AndroidManifest.xml'
$ks        = Join-Path $root 'tools\signing\mifitmask.p12'
$ksPass    = 'mifitmask2026'
$distDir   = Join-Path $root 'dist'
$finalApk  = Join-Path $distDir "MiFitDeviceMask-v$vName.apk"
$cpJars    = @('android.jar', 'api-classes.jar', 'service-classes.jar', 'interface-classes.jar', 'annotation.jar', 'kotlin-stdlib.jar') |
              ForEach-Object { Join-Path $cache $_ }
foreach ($f in (@($cpJars) + @($manifest))) {
    if (-not (Test-Path $f)) { throw "missing input: $f" }
}

# ---- staging (ASCII paths only) ----
$t = "$env:TEMP\mfm_build"
Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$t\out", "$t\dex", "$t\meta\xposed" | Out-Null

Write-Output '== 1. javac =='
$sources = (Get-ChildItem $srcJava -Recurse -Filter *.java).FullName
& $javac -encoding UTF-8 -source 17 -target 17 -nowarn -Xlint:-options `
    -cp ($cpJars -join ';') -d "$t\out" $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

Write-Output '== 2. aapt2 link =='
$mf = (Get-Content $manifest -Raw -Encoding UTF8).Replace(
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    "<manifest package=""$pkg"" xmlns:android=""http://schemas.android.com/apk/res/android"">")
[IO.File]::WriteAllText("$t\AndroidManifest.xml", $mf, (New-Object Text.UTF8Encoding($false)))
$baseApk = "$t\base.apk"
& $aapt2 link -o $baseApk -I (Join-Path $cache 'android.jar') `
    --manifest "$t\AndroidManifest.xml" `
    --min-sdk-version $minSdk --target-sdk-version $tgtSdk `
    --version-code $vCode --version-name $vName `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed: $LASTEXITCODE" }

Write-Output '== 3. d8 =='
$classes = (Get-ChildItem "$t\out" -Recurse -Filter *.class).FullName
# service + interface 两库必须打进 APK（模块 App 进程里框架不提供这些类）：
# - service：XposedServiceHelper/XposedProvider/RemotePreferences（漏打=设置页拿不到服务，见 v1.0.2）
# - interface：IXposedService 等 AIDL Binder 类，service 的 runtime 依赖（漏打=收到 binder 也
#   会在 onBinderReceived 里 NoClassDefFoundError，被静默吞掉，见 v1.0.3）
# api 库保持 compileOnly 不打包（运行期由框架在被 hook 进程提供，打进包反而可能遮蔽框架版本）。
$bundleJars = @((Join-Path $cache 'service-classes.jar'), (Join-Path $cache 'interface-classes.jar'))
& $java -cp $d8jar com.android.tools.r8.D8 `
    --release --min-api $minSdk --lib (Join-Path $cache 'android.jar') `
    --output "$t\dex" ($classes + $bundleJars)
if ($LASTEXITCODE -ne 0) { throw "d8 failed: $LASTEXITCODE" }

Write-Output '== 4. assemble (dex + META-INF/xposed) =='
Copy-Item "$metaDir\*" "$t\meta\xposed" -Force
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$t\dex\classes.dex", 'classes.dex', 'Optimal') | Out-Null
    foreach ($f in @('module.prop', 'java_init.list', 'scope.list')) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$t\meta\xposed\$f", "META-INF/xposed/$f", 'Optimal') | Out-Null
    }
} finally { $zip.Dispose() }

Write-Output '== 5. zipalign =='
$alignedApk = "$t\aligned.apk"
& $zipalign -f 4 $baseApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }

Write-Output '== 6. sign =='
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $ks) | Out-Null
    & $keytool -genkeypair -keystore $ks -storetype PKCS12 -alias mifitmask `
        -storepass $ksPass -keypass $ksPass -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=MiFitMask,O=Test,C=CN' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed: $LASTEXITCODE" }
    Write-Output '  (new signing key generated)'
}
& $java -cp $apsjar com.android.apksigner.ApkSignerTool sign `
    --ks $ks --ks-pass "pass:$ksPass" --ks-key-alias mifitmask `
    --out $finalApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed: $LASTEXITCODE" }

Write-Output '== 7. verify =='
& $java -cp $apsjar com.android.apksigner.ApkSignerTool verify --print-certs $finalApk
if ($LASTEXITCODE -ne 0) { throw "verify failed: $LASTEXITCODE" }

$apk = Get-Item $finalApk
Write-Output ("DONE: " + $apk.FullName + "  " + [math]::Round($apk.Length / 1KB, 1) + " KB")
