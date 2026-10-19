@echo off
set TITLE=Wkrepo System Package Only
title %TITLE%

echo ====================================================
echo   Wkrepo System - Package Only
echo ====================================================

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot"

if "%JAVA_HOME%" == "" goto err_no_java
if not exist "%JAVA_HOME%\bin\java.exe" goto err_invalid_java
set PATH=%JAVA_HOME%\bin;%PATH%

echo Packaging project...
call mvnw clean package -DskipTests "-Dfile.encoding=UTF-8"

if ERRORLEVEL 1 (
    echo.
    echo Packaging failed!
    goto end
)

echo.
echo ====================================================
echo   Packaging successful!
echo   JAR file: target\wkrepo-system-1.0.0.jar
echo ====================================================
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
