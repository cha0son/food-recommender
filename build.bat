@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist out mkdir out
javac -encoding UTF-8 -d out src\SparkServer.java
if errorlevel 1 (echo 编译失败 & pause & exit /b 1)
echo 编译成功，class 文件在 out\ 目录
pause
