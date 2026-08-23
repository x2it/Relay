# -*- coding: utf-8 -*-
"""Relay 主入口。"""
import sys
import os
import ctypes

# 让打包后能正确找到模块
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import tkinter as tk
from ui.main_window import MainWindow
from config import APP_NAME


# ---------- 单实例：Windows 命名互斥量 ----------
_MUTEX_HANDLE = None


def _claim_single_instance() -> bool:
    """尝试占用单实例互斥量。

    返回 True 表示本次是唯一实例；False 表示已有实例在运行。
    handle 保存在模块全局，防止被 GC 释放导致互斥量失效。
    """
    global _MUTEX_HANDLE
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.CreateMutexW(None, False, "Local\\Relay_SingleInstance_Mutex")
    if ctypes.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
        kernel32.CloseHandle(handle)
        return False
    _MUTEX_HANDLE = handle
    return True


def _notify_already_running():
    """已有实例时提示用户（无 tk 依赖）。"""
    ctypes.windll.user32.MessageBoxW(
        None, f"{APP_NAME} 已在运行，请勿重复打开。", APP_NAME, 0x40)


def _set_window_icon(root: tk.Tk):
    """设置窗口图标（打包与开发环境均生效）。"""
    candidates = []
    if getattr(sys, "frozen", False):
        candidates.append(os.path.join(sys._MEIPASS, "assets", "Relay.ico"))
    candidates.append(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "assets", "Relay.ico"))
    for c in candidates:
        if os.path.exists(c):
            try:
                root.iconbitmap(c)
                return
            except Exception:
                pass


def main():
    # 单实例：已有实例在运行时提示并退出
    if not _claim_single_instance():
        _notify_already_running()
        return

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
