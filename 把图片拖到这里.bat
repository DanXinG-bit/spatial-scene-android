@echo off
chcp 65001 >nul
title 空间场景 · 拖拽处理图片
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
    echo [x] 没有找到 Python，请先安装 Python 并勾选 Add to PATH
    pause
    exit /b 1
)

echo 使用 Python: !PY!
echo.

for %%F in (%*) do (
    echo ============================================================
    echo 处理: %%~nxF
    echo ============================================================
    "!PY!" "%SCRIPT%" --image "%%~fF" --dump-only --open-folder
    if errorlevel 1 (
        echo.
        echo [x] 处理失败：%%~nxF
        echo     请把上面的错误信息发给开发者
        echo.
    )
)

echo.
echo 全部完成。结果在每张图片旁边的 ^<图片名^>_sim 文件夹里。
pause
exit /b 0

:usage
echo 用法：把一张或多张图片用鼠标左键拖到这个 bat 文件上。
echo.
echo   输出：图片同目录下的 ^<图片名^>_sim\ 文件夹
echo         1_composite.png       合成（静止）
echo         2_depth.png           深度图
echo         3_mask.png            主体遮罩
echo         4_background_ai.png   背景板 / AI 补全
echo         5_ai_hole.png         AI 实际收到的洞
echo         6_mask_overlay.png    遮罩叠加（红=主体）
echo         7_composite_tilted.png 合成（倾斜）
echo.
pause
exit /b 1
