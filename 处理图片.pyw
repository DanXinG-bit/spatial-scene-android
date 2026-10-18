"""
把图片拖到这个文件上 → 批量处理 → 在图片旁边生成 <图片名>_sim\\ 文件夹。

为什么是 .pyw：没有控制台窗口、没有 .bat 的安全提示，失败时用弹窗告诉你原因。
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
        dialog('showinfo', '空间场景',
               '用法：把一张或多张图片拖到这个文件上。\n\n'
               '结果会生成在图片旁边的 <图片名>_sim 文件夹里。')
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

    done, failed = [], []
    for path in images:
        r = subprocess.run(
            [py, SIM, '--image', path, '--dump-only'],
            capture_output=True, creationflags=FLAGS, timeout=3600,
        )
        if r.returncode != 0:
            out = (r.stdout or b'').decode('utf-8', 'replace')
            err = (r.stderr or b'').decode('utf-8', 'replace')
            failed.append(f'{os.path.basename(path)}\n{(err or out)[-700:]}')
        else:
            out_dir = os.path.splitext(os.path.abspath(path))[0] + '_sim'
            done.append(out_dir)

    for d in done[:1]:
        try:
            os.startfile(d)
        except Exception:
            pass

    if failed:
        dialog('showerror', '处理失败', '\n\n'.join(failed)[:2000] + (
            f'\n\n（成功 {len(done)} 张）' if done else ''))
    else:
        dialog('showinfo', '完成', '已生成：\n\n' + '\n'.join(done) +
               '\n\n1_composite 合成 · 2_depth 深度 · 3_mask 遮罩\n'
               '4_background_ai 背景板 · 5_ai_hole 洞\n'
               '6_mask_overlay 遮罩叠加 · 7_composite_tilted 倾斜')


if __name__ == '__main__':
    main()
