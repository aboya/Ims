@echo off
setlocal EnableDelayedExpansion
rem ===============================================================
rem  Ims -> Android device (build + install via adb)
rem
rem  Usage:  ims-pixel.bat [release|debug]      default: release
rem
rem  Both variants are signed with the same debug key (app/build.gradle.kts), so
rem  switching between them reinstalls in place and keeps the app's data.
rem
rem  No UI/launcher activity in this app - after install we just kick a manual
rem  config run via ConfigChangeReceiver's APPLY action (see CLAUDE.md).
rem ===============================================================

set "JAVA_HOME=D:\Program Files\Android Studio\jbr"
set "ADB=D:\ProgramData\AndroidStudioSDK\platform-tools\adb.exe"
set "PROJECT=D:\projects\ImsGit\Ims"
set "PACKAGE=io.github.vvb2060.ims"
set "APPLY=%PACKAGE%/.ConfigChangeReceiver"

rem Leave empty to use whatever single device adb sees; set it when several are attached.
set "SERIAL="

rem ---- build variant -------------------------------------------------------
set "VARIANT=%~1"
if "%VARIANT%"=="" set "VARIANT=release"
if /i "%VARIANT%"=="debug" (
    set "VARIANT=debug"
    set "TASK=:app:assembleDebug"
    set "APK=%PROJECT%\app\build\outputs\apk\debug\app-debug.apk"
) else (
    set "VARIANT=release"
    set "TASK=:app:assembleRelease"
    set "APK=%PROJECT%\app\build\outputs\apk\release\app-release.apk"
)

echo ===============================================
echo  Ims  ^(%VARIANT%^)
if defined SERIAL (echo  target: %SERIAL%) else (echo  target: default adb device)
echo ===============================================

rem ---- sanity checks -------------------------------------------------------
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK not found: %JAVA_HOME%
    echo         Point JAVA_HOME at Android Studio's bundled JBR ^(21^).
    pause & exit /b 1
)
if not exist "%ADB%" (
    echo [ERROR] adb not found: %ADB%
    pause & exit /b 1
)
if not exist "%PROJECT%\gradlew.bat" (
    echo [ERROR] gradlew.bat not found in %PROJECT%
    pause & exit /b 1
)

rem ---- build ---------------------------------------------------------------
pushd "%PROJECT%"
call gradlew.bat %TASK% --console=plain
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (
    echo.
    echo [ERROR] Build failed ^(exit %RC%^)
    pause & exit /b 1
)
if not exist "%APK%" (
    echo [ERROR] APK not found: %APK%
    pause & exit /b 1
)

rem ---- install -------------------------------------------------------------
set "ADBTARGET="
if defined SERIAL set "ADBTARGET=-s %SERIAL%"

"%ADB%" %ADBTARGET% get-state >nul 2>&1
if errorlevel 1 (
    echo [ERROR] No device. Connect one and enable USB debugging:
    "%ADB%" devices
    pause & exit /b 1
)

echo.
echo Installing %APK% ...
"%ADB%" %ADBTARGET% install -r --user 0 "%APK%"
if errorlevel 1 (
    echo [WARN] Reinstall failed - uninstalling and retrying.
    "%ADB%" %ADBTARGET% uninstall %PACKAGE%
    "%ADB%" %ADBTARGET% install --user 0 "%APK%"
    if errorlevel 1 ( echo [ERROR] Install failed & pause & exit /b 1 )
)

rem ---- kick a manual run -----------------------------------------------------
rem No launcher activity: the app runs off Shizuku's binder + SIM/carrier-config
rem broadcasts. This APPLY broadcast is the manual trigger from CLAUDE.md.
"%ADB%" %ADBTARGET% shell am broadcast -a io.github.vvb2060.ims.action.APPLY -n %APPLY% >nul 2>&1

echo.
echo Done. %VARIANT% installed. Manual APPLY broadcast sent (needs Shizuku running to do anything).
echo.
echo Logs:  "%ADB%" %ADBTARGET% logcat -s vvb
echo Dump:  "%ADB%" %ADBTARGET% shell dumpsys carrier_config
pause
