@echo off
rem ============================================================
rem  Windows 本地一键构建 APK（等价于 ./gradlew :app:assembleDebug）
rem
rem  仓库里只有 Unix 的 gradlew，没有 gradlew.bat，
rem  所以这里直接用 java 调 gradle-wrapper.jar，无需额外安装 Gradle。
rem
rem  前置要求：JDK 17+ 已加入 PATH（或设置 JAVA_HOME），
rem            并设置 ANDROID_HOME 指向 Android SDK。
rem ============================================================
setlocal
cd /d "%~dp0"

set "JAVA_EXE=java"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

if not defined ANDROID_HOME (
  echo [!] 未设置 ANDROID_HOME，请先设置，例如：
  echo     set ANDROID_HOME=%%LOCALAPPDATA%%\Android\Sdk
  exit /b 1
)

echo [*] 生成 local.properties ...
> local.properties echo sdk.dir=%ANDROID_HOME:\=\\%

echo [*] 开始构建 (debug + release) ...
"%JAVA_EXE%" -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :app:assembleDebug :app:assembleRelease
if errorlevel 1 (
  echo [!] 构建失败
  exit /b 1
)

echo.
echo [*] 产物：
dir /s /b app\build\outputs\apk\*.apk
echo.
echo [*] APK 签名证书（把 MD5 填到 connect.qq.com 后台）：
for %%f in (app\build\outputs\apk\debug\*.apk) do (
  if exist "%%f" keytool -printcert -jarfile "%%f"
)
endlocal
