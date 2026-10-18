"""
把图片拖到这个文件上 → 打开交互窗口（鼠标拖拽 / WASD 倾斜）。

窗口操作：
  鼠标左键拖动 = 倾斜       W A S D = 倾斜
  Q / E = 缩放              R = 回中
  1~6 = 切换视图（合成/深度/遮罩/背景板/洞/遮罩叠加）
  P = 保存截图              ESC = 退出
"""

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SIM = os.path.join(HERE, 'tools', 'sim', 'sim.py')
DIALOG = os.environ.get('SIM_NO_DIALOG') != '1'
FLAGS = 0x08000000 if sys.platform.startswith('win') else 0  # CREATE_NO_WINDOW


def dialog(kind, title, text):
    if not DIALOG:
        print(f'[{title}] {text}')
        return
    try:
        import tkinter as tk
        from tkinter import messagebox
        root = tk.Tk()
        root.withdraw()
        getattr(messagebox, kind)(title, text)
        root.destroy()
    except Exception:
        print(f'[{title}] {text}')


def find_python():
    """找一个真正装了 numpy / cv2 / onnxruntime 的解释器。"""
    cands = [sys.executable, 'python']
    local = os.environ.get('LOCALAPPDATA', '')
    for ver in ('Python39', 'Python310', 'Python311', 'Python312'):
        cands.append(os.path.join(local, 'Programs', 'Python', ver, 'python.exe'))
    for c in cands:
        try:
            r = subprocess.run([c, '-c', 'import cv2, numpy, onnxruntime'],
                               capture_output=True, creationflags=FLAGS, timeout=60)
            if r.returncode == 0:
                return c
        except Exception:
            continue
    return None


def main():
    images = [a for a in sys.argv[1:] if a]
    if not images:
        dialog('showinfo', '空间场景 · 倾斜预览',
               '用法：把一张图片拖到这个文件上，会打开交互窗口。\n\n'
               '窗口里：\n'
               '  鼠标左键拖动 = 倾斜\n'
               '  W A S D = 倾斜，Q/E = 缩放，R = 回中\n'
               '  1~6 = 切换视图，P = 截图，ESC = 退出')
        return

    if not os.path.exists(SIM):
        dialog('showerror', '缺少文件', f'找不到：\n{SIM}')
        return

    py = find_python()
    if not py:
        dialog('showerror', '缺少依赖',
               '没有找到装有 numpy / opencv / onnxruntime 的 Python。\n\n'
               '请在命令行执行：\n'
               'py -m pip install numpy onnxruntime opencv-python')
        return

    r = subprocess.run(
        [py, SIM, '--image', images[0], '--no-dump'],
        capture_output=True, creationflags=FLAGS, timeout=7200,
    )
    if r.returncode != 0:
        out = (r.stdout or b'').decode('utf-8', 'replace')
        err = (r.stderr or b'').decode('utf-8', 'replace')
        dialog('showerror', '预览失败', (err or out)[-1500:])


if __name__ == '__main__':
    main()
