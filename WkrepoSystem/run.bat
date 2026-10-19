@echo off
set TITLE=Wkrepo System Starter
title %TITLE%

:: 强制使用 UTF-8 编码以支持中文显示
chcp 65001 > nul

echo ====================================================
echo   Wkrepo System - 勤怠管理システム 起動スクリプト
echo ====================================================

:: 【手动配置区】
:: 如果你的 JAVA_HOME 报错，请取消下面这一行的注释(去掉开头的 ::)，并将路径修改为你电脑上 JDK 的实际安装位置
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot"

echo [1/3] 正在检查环境并清理旧进程...
taskkill /F /IM java.exe /T > nul 2>&1 || echo 无正在运行的Java进程。

echo [2/3] 正在编译项目 (clean compile)...

:: 检查 Java 环境
if "%JAVA_HOME%" == "" goto :err_no_java
if not exist "%JAVA_HOME%\bin\java.exe" goto :err_invalid_java

:: 将 Java 临时加入 PATH 确保 Maven 能够运行
set PATH=%JAVA_HOME%\bin;%PATH%

:: 先执行编译
call mvnw clean compile "-Dfile.encoding=UTF-8"
if ERRORLEVEL 1 goto :end

echo [3/3] 编译成功！正在启动服务器并打开浏览器: http://localhost:8080/login
start "" "http://localhost:8080/login"
call mvnw spring-boot:run "-Dfile.encoding=UTF-8"
goto :end

:err_no_java
echo.
echo [严重错误] 找不到 JAVA_HOME。
echo 请安装 JDK 17，或在 run.bat 顶部的【手动配置区】设置路径。
pause
exit /b 1

:err_invalid_java
echo.
echo [严重错误] JAVA_HOME 路径无效，找不到 java.exe。
echo 当前设置: "%JAVA_HOME%"
pause
exit /b 1

:end
pause