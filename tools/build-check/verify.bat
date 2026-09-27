@echo off
rem ============================================================
rem  本机编译校验脚本（非交付构建）
rem  用 javac 对 app 源码做全量语法/API 签名校验，产物在 out\，不入 APK。
rem
rem  依赖缓存目录 cache\（首次需手工准备，路径可按需调整）：
rem    cache\jdk\              JDK 17+（需 bin\javac.exe）
rem    cache\android.jar       Android platform 35 android.jar
rem    cache\api-classes.jar   libxposed api-102.0.0.aar 内的 classes.jar
rem    cache\service-classes.jar libxposed service-102.0.0.aar 内的 classes.jar
rem    cache\interface-classes.jar libxposed interface-102.0.0.aar 内的 classes.jar
rem                            （IXposedService 等 AIDL 类，service 的 runtime 依赖）
rem    cache\annotation.jar    androidx.annotation 1.8.x
rem    cache\kotlin-stdlib.jar kotlin-stdlib 2.0.x
rem
rem  aar 取 classes.jar 的方法：把 .aar 改名 .zip 解压，取根目录 classes.jar。
rem ============================================================
setlocal
set CACHE=%~dp0cache
set JDK=%CACHE%\jdk
set OUT=%~dp0out

if not exist "%JDK%\bin\javac.exe" (
    echo [X] 缺少 JDK：%JDK%\bin\javac.exe
    exit /b 1
)
for %%F in (android.jar api-classes.jar service-classes.jar interface-classes.jar annotation.jar kotlin-stdlib.jar) do (
    if not exist "%CACHE%\%%F" (
        echo [X] 缺少依赖：cache\%%F
        exit /b 1
    )
)

set CP=%CACHE%\android.jar;%CACHE%\api-classes.jar;%CACHE%\service-classes.jar;%CACHE%\interface-classes.jar;%CACHE%\annotation.jar;%CACHE%\kotlin-stdlib.jar
set SRC=..\..\app\src\main\java

rmdir /s /q "%OUT%" 2>nul
mkdir "%OUT%" 2>nul

"%JDK%\bin\javac.exe" -encoding UTF-8 -source 17 -target 17 -nowarn -Xlint:-options -cp "%CP%" -d "%OUT%" ^
  "%SRC%\io\github\mifitmask\MaskModule.java" ^
  "%SRC%\io\github\mifitmask\Prefs.java" ^
  "%SRC%\io\github\mifitmask\DeviceProfiles.java" ^
  "%SRC%\io\github\mifitmask\MaskApp.java" ^
  "%SRC%\io\github\mifitmask\SettingsActivity.java" ^
  "%SRC%\io\github\mifitmask\hooks\DeviceIdentityHooks.java" ^
  "%SRC%\io\github\mifitmask\hooks\StoreParamHooks.java" ^
  "%SRC%\io\github\mifitmask\hooks\ProbeHooks.java"

if errorlevel 1 (
    echo [X] 编译校验失败
    exit /b 1
)
echo [OK] 编译校验通过（产物仅用于校验，见 out\）
endlocal
