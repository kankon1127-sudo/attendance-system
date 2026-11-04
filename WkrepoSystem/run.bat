@echo off
set TITLE=Wkrepo System Starter
title %TITLE%

:: 中国語を正しく表示するため、文字コードを UTF-8 に強制 (chcp 65001)
chcp 65001 > nul

echo ====================================================
echo   Wkrepo System - 勤怠管理システム 起動スクリプト
echo ====================================================

:: 【手动配置区】
:: JAVA_HOME でエラーになる場合は、次の行のコメントを外し（先頭の :: を削除）、パスを PC 上の JDK の実際のインストール先に変更する
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"

echo [1/3] 正在检查环境并清理旧进程...
:: 8080 ポートを使用しているプロセス（前回起動した本システム）だけを終了し、IDE など他の Java プロセスには影響しない
set "PORT_PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":8080 .*LISTENING"') do set "PORT_PID=%%p"
if defined PORT_PID (
    echo 结束占用 8080 端口的进程 PID=%PORT_PID%
    taskkill /F /PID %PORT_PID% > nul 2>&1
) else (
    echo 8080 端口未被占用。
)

echo [2/3] 正在编译项目 (clean compile)...

:: Java 環境のチェック
if "%JAVA_HOME%" == "" goto :err_no_java
if not exist "%JAVA_HOME%\bin\java.exe" goto :err_invalid_java

:: Maven が動くように Java を一時的に PATH に追加
set PATH=%JAVA_HOME%\bin;%PATH%

:: 先にコンパイルを実行
call mvnw clean compile "-Dfile.encoding=UTF-8"
if ERRORLEVEL 1 goto :end

echo [3/3] 编译成功！正在启动服务器并打开浏览器: http://localhost:8080/login
start "" "http://localhost:8080/login"
:: dev モード：DB が空のときテストアカウントを自動作成（パスワード 123456）
call mvnw spring-boot:run "-Dfile.encoding=UTF-8" "-Dspring-boot.run.profiles=dev"
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