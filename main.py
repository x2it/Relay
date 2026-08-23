# -*- coding: utf-8 -*-
"""LiteProxy 主入口。"""
import sys
import os

# 让打包后能正确找到模块
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import tkinter as tk
from ui.main_window import MainWindow
from config import APP_NAME


def _set_window_icon(root: tk.Tk):
    """设置窗口图标（打包与开发环境均生效）。"""
    candidates = []
    if getattr(sys, "frozen", False):
        candidates.append(os.path.join(sys._MEIPASS, "assets", "LiteProxy.ico"))
    candidates.append(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "assets", "LiteProxy.ico"))
    for c in candidates:
        if os.path.exists(c):
            try:
                root.iconbitmap(c)
                return
            except Exception:
                pass


def main():
    root = tk.Tk()
    try:
        # Windows 高 DPI 自适应
        from ctypes import windll
        windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    _set_window_icon(root)

    app = MainWindow(root)
    root.protocol("WM_DELETE_WINDOW", app.on_close)
    # 全局异常捕获：任何未处理异常都恢复系统代理
    import atexit
    def _global_cleanup():
        try:
            if hasattr(app, 'sys_proxy_mgr') and app.sys_proxy_mgr.is_applied:
                app.sys_proxy_mgr.emergency_restore()
        except Exception:
            pass
    atexit.register(_global_cleanup)
    root.mainloop()


if __name__ == "__main__":
    main()
