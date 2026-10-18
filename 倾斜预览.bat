@echo off
chcp 65001 >nul
title 空间场景 · 倾斜预览（WASD / 鼠标拖拽）
setlocal enabledelayedexpansion

set "SCRIPT=%~dp0tools\sim\sim.py"

if "%~1"=="" goto usage
if not exist "%SCRIPT%" (
    echo [x] 找不到 %SCRIPT%
    pause
    exit /b 1
)

set "PY="
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"
where python >nul 2>nul && set "PY=python"
if not defined PY (
    where py >nul 2>nul && set "PY=py"
)
if not defined PY (
    echo [x] 没有找到 Python
    pause
    exit /b 1
)

echo 正在分析图片，请稍候（约 3 秒）……
"!PY!" "%SCRIPT%" --image "%~1" --no-dump
if errorlevel 1 (
    echo.
    echo [x] 出错，请把上面的信息发给开发者
    pause
    exit /b 1
)
echo.
echo 窗口已关闭。
pause
exit /b 0

:usage
echo 用法：把一张图片拖到这个 bat 上。
echo.
echo 打开窗口后：
echo   1. 先点一下窗口（保证键盘焦点在窗口上）
echo   2. 按住鼠标左键拖动 = 倾斜（最直观）
echo   3. 或者用 WASD 键：W 上 / S 下 / A 左 / D 右
echo   Q E 缩放，R 回中，1~6 切换调试视图，P 保存截图，ESC 退出
echo.
pause
exit /b 1
