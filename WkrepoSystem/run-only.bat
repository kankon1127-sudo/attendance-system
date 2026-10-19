@echo off
set TITLE=Wkrepo System Run Only
title %TITLE%

echo ====================================================
echo   Wkrepo System - Run Only
echo ====================================================

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot"

if "%JAVA_HOME%" == "" goto err_no_java
if not exist "%JAVA_HOME%\bin\java.exe" goto err_invalid_java
set PATH=%JAVA_HOME%\bin;%PATH%

if not exist "target\wkrepo-system-1.0.0.jar" (
    echo.
    echo [ERROR] JAR file not found: target\wkrepo-system-1.0.0.jar
    echo Please run package-only.bat first!
    goto end
)

echo Starting server...
start "" "http://localhost:8080/login"
java -jar target\wkrepo-system-1.0.0.jar
goto end

:err_no_java
echo.
echo [ERROR] JAVA_HOME not found.
pause
exit /b 1

:err_invalid_java
echo.
echo [ERROR] Invalid JAVA_HOME path.
pause
exit /b 1

:end
pause
