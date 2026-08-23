@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
set "GRADLE_VERSION=8.9"
set "PORTABLE_GRADLE=%ROOT%.gradle-dist\gradle-%GRADLE_VERSION%\bin\gradle.bat"
set "GRADLE_ZIP=%ROOT%.gradle-dist\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

set "BUILD_TYPE=%~1"
if not defined BUILD_TYPE set "BUILD_TYPE=debug"

if /I "%BUILD_TYPE%"=="debug" (
    set "GRADLE_TASK=assembleDebug"
    set "APK_PATH=%ROOT%build\outputs\apk\debug\CloudDriveSync-debug.apk"
) else if /I "%BUILD_TYPE%"=="release" (
    set "GRADLE_TASK=assembleRelease"
    set "APK_PATH=%ROOT%build\outputs\apk\release\CloudDriveSync-release-unsigned.apk"
    if exist "%ROOT%keystore.properties" set "APK_PATH=%ROOT%build\outputs\apk\release\CloudDriveSync-release.apk"
) else if /I "%BUILD_TYPE%"=="clean" (
    set "GRADLE_TASK=clean"
    set "APK_PATH="
) else (
    echo Usage: build.bat [debug^|release^|clean]
    exit /b 2
)

where java.exe >nul 2>nul
if errorlevel 1 (
    echo ERROR: Java 17 or newer was not found.
    echo Install Android Studio or set JAVA_HOME to a JDK 17 installation.
    exit /b 1
)

if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined ANDROID_SDK_ROOT if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if exist "C:\Android\Sdk" set "ANDROID_SDK_ROOT=C:\Android\Sdk"
if not defined ANDROID_SDK_ROOT (
    echo ERROR: Android SDK was not found.
    echo Install Android SDK 35 in Android Studio, then set ANDROID_SDK_ROOT.
    exit /b 1
)
set "SDK_MANAGER=%ANDROID_SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat"
if not exist "%SDK_MANAGER%" set "SDK_MANAGER=%ANDROID_SDK_ROOT%\cmdline-tools\bin\sdkmanager.bat"
if not exist "%SDK_MANAGER%" set "SDK_MANAGER=%ANDROID_SDK_ROOT%\tools\bin\sdkmanager.bat"
if not exist "%ANDROID_SDK_ROOT%\platforms\android-35\android.jar" (
    if not exist "%SDK_MANAGER%" (
        echo ERROR: Android SDK platform 35 and sdkmanager are missing from:
        echo   %ANDROID_SDK_ROOT%
        echo Install "Android SDK Command-line Tools" using Android Studio SDK Manager.
        exit /b 1
    )
    echo Android SDK platform 35 is missing. Installing required SDK packages...
    (for /L %%I in (1,1,20) do @echo y) | call "%SDK_MANAGER%" --sdk_root="%ANDROID_SDK_ROOT%" --licenses >nul
    call "%SDK_MANAGER%" --sdk_root="%ANDROID_SDK_ROOT%" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
    if errorlevel 1 (
        echo ERROR: Failed to install Android SDK platform 35.
        exit /b 1
    )
    if not exist "%ANDROID_SDK_ROOT%\platforms\android-35\android.jar" (
        echo ERROR: sdkmanager completed but Android SDK platform 35 is still unavailable.
        exit /b 1
    )
)
set "ANDROID_HOME=%ANDROID_SDK_ROOT%"

if exist "%ROOT%gradlew.bat" (
    set "GRADLE_COMMAND=%ROOT%gradlew.bat"
) else if exist "%PORTABLE_GRADLE%" (
    set "GRADLE_COMMAND=%PORTABLE_GRADLE%"
) else (
    echo Gradle %GRADLE_VERSION% was not found. Downloading it once...
    if not exist "%ROOT%.gradle-dist" mkdir "%ROOT%.gradle-dist"
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
        "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%GRADLE_URL%' -OutFile '%GRADLE_ZIP%'; Expand-Archive -LiteralPath '%GRADLE_ZIP%' -DestinationPath '%ROOT%.gradle-dist' -Force"
    if errorlevel 1 (
        echo ERROR: Failed to download or extract Gradle %GRADLE_VERSION%.
        del /q "%GRADLE_ZIP%" >nul 2>nul
        exit /b 1
    )
    del /q "%GRADLE_ZIP%" >nul 2>nul
    if not exist "%PORTABLE_GRADLE%" (
        echo ERROR: Portable Gradle installation is incomplete.
        exit /b 1
    )
    set "GRADLE_COMMAND=%PORTABLE_GRADLE%"
)

echo.
echo Building CloudDrive Sync: %BUILD_TYPE%
echo Android SDK: %ANDROID_SDK_ROOT%
echo.

pushd "%ROOT%"
call "%GRADLE_COMMAND%" %GRADLE_TASK% --stacktrace
set "BUILD_RESULT=%ERRORLEVEL%"
popd

if not "%BUILD_RESULT%"=="0" (
    echo.
    echo BUILD FAILED with exit code %BUILD_RESULT%.
    exit /b %BUILD_RESULT%
)

echo.
if defined APK_PATH (
    if exist "%APK_PATH%" (
        echo BUILD SUCCESSFUL
        echo APK: %APK_PATH%
    ) else (
        echo BUILD SUCCESSFUL, but the expected APK path was not found.
        echo Check: %ROOT%build\outputs\apk\release
    )
) else (
    echo CLEAN SUCCESSFUL
)
exit /b 0
