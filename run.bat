@echo off
chcp 65001 >nul
cd /d "%~dp0"
title 今天吃什么 - 每日饮食推荐系统

echo ========================================
echo   正在编译...
echo ========================================
if not exist out mkdir out
javac -encoding UTF-8 -d out src\SparkServer.java
if errorlevel 1 (
    echo.
    echo [失败] 编译出错，请检查上面的错误信息
    echo        常见原因：没装 JDK，或 JDK 版本低于 17
    pause
    exit /b 1
)
echo 编译成功
echo.
echo ========================================
echo   正在启动服务...
echo   浏览器会自动打开 http://localhost:8080
echo   关闭这个黑窗口即可停止服务
echo ========================================
echo.
start "" http://localhost:8080
java -cp out foodrec.SparkServer 8080
pause
