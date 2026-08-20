# -*- coding: utf-8 -*-
"""精致优雅主题：圆角按钮、细窄滚动条、卡片、ghost 按钮。"""
import math
import tkinter as tk
from tkinter import ttk

from config import (COLOR_BG, COLOR_CARD, COLOR_BAR, COLOR_HOVER,
                    COLOR_PRIMARY, COLOR_PRIMARY_HOVER, COLOR_PRIMARY_SOFT,
                    COLOR_DANGER, COLOR_DANGER_HOVER, COLOR_TEXT,
                    COLOR_TEXT_MUTED, COLOR_TEXT_FAINT, COLOR_BORDER,
                    COLOR_GOOD, COLOR_BAD, COLOR_ROW_ALT, COLOR_ROW_HOVER,
                    FONT_FAMILY, FONT_SIZE)


# ---------- 统一图标体系（Unicode 符号，零依赖） ----------
class Icon:
    # 操作类
    FETCH = "⤓"        # 抓取/下载
    CHECK = "✓"        # 验证/完成
    CHECK_SEL = "◎"    # 验证选中
    ONESHOT = "⚡"      # 一键流程
    START = "▶"        # 启动
    STOP = "■"         # 停止
    IMPORT = "↧"       # 导入
    EXPORT = "↥"       # 导出
    EXPORT_ALIVE = "★" # 导出可用
    DELETE = "⊗"       # 删除
    CLEAR = "✕"        # 清空
    SETTINGS = "⚙"     # 设置
    SOURCE = "❖"       # 抓取源
    SEARCH = "⌕"       # 搜索
    COPY = "❐"         # 复制
    PIN = "★"          # 设为当前上游
    # 状态类
    DOT_ON = "●"       # 可用
    DOT_OFF = "○"      # 未检测
    DOT_FAIL = "✕"     # 不可用
    DOT_RUN = "●"      # 运行中
    OK_MARK = "✓"
    FAIL_MARK = "✕"
    SEP = "·"          # 分隔


def apply_style(root: tk.Tk):
    root.configure(bg=COLOR_BG)
    s = ttk.Style(root)
    try:
        s.theme_use("clam")
    except Exception:
        pass

    f = (FONT_FAMILY, FONT_SIZE)
    fb = (FONT_FAMILY, FONT_SIZE, "bold")

    # Frame
    s.configure("TFrame", background=COLOR_BG)
    s.configure("Card.TFrame", background=COLOR_CARD)
    s.configure("Bar.TFrame", background=COLOR_BAR)

    # Label
    s.configure("TLabel", background=COLOR_BG, foreground=COLOR_TEXT, font=f)
    s.configure("Card.TLabel", background=COLOR_CARD, foreground=COLOR_TEXT, font=f)
    s.configure("Muted.TLabel", background=COLOR_BG, foreground=COLOR_TEXT_MUTED, font=f)
    s.configure("MutedCard.TLabel", background=COLOR_CARD, foreground=COLOR_TEXT_MUTED, font=f)
    s.configure("Faint.TLabel", background=COLOR_BG, foreground=COLOR_TEXT_FAINT, font=f)
    s.configure("Title.TLabel", background=COLOR_BAR, foreground=COLOR_TEXT,
                font=(FONT_FAMILY, 15, "bold"))
    s.configure("Brand.TLabel", background=COLOR_BAR, foreground=COLOR_PRIMARY,
                font=(FONT_FAMILY, 15, "bold"))
    s.configure("Ver.TLabel", background=COLOR_BAR, foreground=COLOR_TEXT_FAINT, font=f)
    s.configure("Status.TLabel", background=COLOR_BAR, foreground=COLOR_TEXT_MUTED, font=f)

    # Treeview：无边框，精致表头
    s.configure("Treeview",
                background=COLOR_CARD, foreground=COLOR_TEXT,
                fieldbackground=COLOR_CARD, borderwidth=0,
                font=f, rowheight=28)
    s.configure("Treeview.Heading",
                background=COLOR_CARD, foreground=COLOR_TEXT_FAINT,
                font=(FONT_FAMILY, FONT_SIZE - 1, "bold"),
                borderwidth=0, relief="flat", padding=(8, 6))
    s.map("Treeview.Heading",
          background=[("active", COLOR_CARD)])
    s.map("Treeview",
          background=[("selected", COLOR_PRIMARY_SOFT)],
          foreground=[("selected", COLOR_PRIMARY)])

    # 滚动条：细窄、半透明灰、无箭头突起
    s.configure("Vertical.TScrollbar",
                background=COLOR_CARD, troughcolor=COLOR_BG,
                bordercolor=COLOR_BG, arrowcolor=COLOR_TEXT_FAINT,
                lightcolor=COLOR_CARD, darkcolor=COLOR_CARD,
                gripcount=0, arrowsize=0)
    s.map("Vertical.TScrollbar",
          background=[("active", COLOR_TEXT_FAINT)])
    s.configure("Horizontal.TScrollbar",
                background=COLOR_CARD, troughcolor=COLOR_BG,
                bordercolor=COLOR_BG, arrowcolor=COLOR_TEXT_FAINT,
                lightcolor=COLOR_CARD, darkcolor=COLOR_CARD,
                gripcount=0, arrowsize=0)
    s.map("Horizontal.TScrollbar",
          background=[("active", COLOR_TEXT_FAINT)])

    # Entry
    s.configure("TEntry",
                fieldbackground=COLOR_CARD, foreground=COLOR_TEXT,
                borderwidth=0, relief="flat", padding=7)
    s.map("TEntry",
          bordercolor=[("focus", COLOR_PRIMARY)])

    # Combobox
    s.configure("TCombobox",
                fieldbackground=COLOR_CARD, foreground=COLOR_TEXT,
                background=COLOR_CARD, borderwidth=0,
                arrowcolor=COLOR_TEXT_MUTED, padding=6)
    s.map("TCombobox",
          fieldbackground=[("readonly", COLOR_CARD)],
          selectbackground=[("readonly", COLOR_PRIMARY_SOFT)],
          selectforeground=[("readonly", COLOR_PRIMARY)])
    # 下拉列表样式
    s.configure("TCombobox.SListbox", background=COLOR_CARD, foreground=COLOR_TEXT,
                borderwidth=0, selectbackground=COLOR_PRIMARY_SOFT,
                selectforeground=COLOR_PRIMARY)

    # Checkbutton
    s.configure("TCheckbutton",
                background=COLOR_CARD, foreground=COLOR_TEXT, font=f)
    s.map("TCheckbutton", background=[("active", COLOR_CARD)])

    # Progressbar：精致主色细条
    s.configure("Horizontal.TProgressbar",
                background=COLOR_PRIMARY, troughcolor=COLOR_BORDER,
                borderwidth=0, thickness=4, lightcolor=COLOR_PRIMARY,
                darkcolor=COLOR_PRIMARY)


def _round_rect(canvas, x, y, w, h, r, **kw):
    """在 canvas 上画圆角矩形。"""
    points = []
    for px, py, sx, sy in [
        (x + r, y, 1, 1), (x + w - r, y, 1, 1),
        (x + w, y + r, 1, 1), (x + w, y + h - r, 1, 1),
        (x + w - r, y + h, 1, 1), (x + r, y + h, 1, 1),
        (x, y + h - r, 1, 1), (x, y + r, 1, 1),
    ]:
        pass
    # 用 create_polygon + smooth 近似圆角
    return canvas.create_polygon(
        x + r, y,
        x + w - r, y,
        x + w, y, x + w, y + r,
        x + w, y + h - r,
        x + w, y + h, x + w - r, y + h,
        x + r, y + h,
        x, y + h, x, y + h - r,
        x, y + r,
        x, y, x + r, y,
        smooth=True, **kw)


class RoundedButton(tk.Canvas):
    """圆角按钮，支持主色/危险/ghost 三种风格。"""

    def __init__(self, master, text, command=None, kind="primary",
                 width=None, height=30, padx=14, **kw):
        bg = COLOR_BG
        if master is not None:
            try:
                bg = master["bg"]
            except Exception:
                pass
        super().__init__(master, bg=bg, highlightthickness=0, bd=0, **kw)

        self.kind = kind
        self._text = text
        self._command = command
        self._padx = padx
        self._height = height
        self._enabled = True

        if kind == "primary":
            self._fill = COLOR_PRIMARY
            self._fill_hover = COLOR_PRIMARY_HOVER
            self._fg = "#ffffff"
        elif kind == "danger":
            self._fill = COLOR_DANGER
            self._fill_hover = COLOR_DANGER_HOVER
            self._fg = "#ffffff"
        elif kind == "soft":
            self._fill = COLOR_PRIMARY_SOFT
            self._fill_hover = "#dbe5ff"
            self._fg = COLOR_PRIMARY
        else:  # ghost
            self._fill = bg
            self._fill_hover = COLOR_HOVER
            self._fg = COLOR_TEXT

        self._bg = bg
        self._disabled_fill = "#e8eaee"
        self._disabled_fg = COLOR_TEXT_FAINT

        # 计算尺寸
        font = (FONT_FAMILY, FONT_SIZE)
        tw = self.tk.call("font", "measure", "TkDefaultFont", text)
        # 用 measure 更准
        tmp = tk.Label(self, text=text, font=font)
        tw = tmp.winfo_reqwidth()
        tmp.destroy()
        self._width = width if width else tw + padx * 2
        self.configure(width=self._width, height=height,
                       highlightthickness=0)

        self._r = 7
        self._shape = _round_rect(self, 1, 1, self._width - 1, height - 1,
                                  self._r, fill=self._fill, outline="")
        self._label = self.create_text(
            self._width // 2, height // 2,
            text=text, fill=self._fg, font=font)

        self.bind("<Enter>", self._on_enter)
        self.bind("<Leave>", self._on_leave)
        self.bind("<Button-1>", self._on_click)
        # 让整个区域可点
        self.bind("<Configure>", lambda e: None)

    def _on_enter(self, e):
        if not self._enabled:
            return
        self.itemconfigure(self._shape, fill=self._fill_hover)
        self.configure(cursor="hand2")

    def _on_leave(self, e):
        if not self._enabled:
            return
        self.itemconfigure(self._shape, fill=self._fill)
        self.configure(cursor="")

    def _on_click(self, e):
        if not self._enabled:
            return
        if self._command:
            self._command()

    def set_text(self, text):
        self._text = text
        self.itemconfigure(self._label, text=text)

    def set_enabled(self, on):
        self._enabled = on
        if on:
            self.itemconfigure(self._shape, fill=self._fill)
            self.itemconfigure(self._label, fill=self._fg)
        else:
            self.itemconfigure(self._shape, fill=self._disabled_fill)
            self.itemconfigure(self._label, fill=self._disabled_fg)

    def configure_state(self, state):
        """兼容 tk Button 的 state 接口。"""
        if state == "normal":
            self.set_enabled(True)
        elif state == "disabled":
            self.set_enabled(False)


class StatusBar(tk.Frame):
    """底部状态栏：圆点 + 分段。"""

    def __init__(self, master):
        super().__init__(master, bg=COLOR_BAR, height=32)
        self._items = {}
        self._sep_count = 0
        self._dot = tk.Label(self, text="●", fg=COLOR_TEXT_FAINT,
                             bg=COLOR_BAR, font=(FONT_FAMILY, 8))
        self._dot.pack(side="left", padx=(14, 6))

    def set_dot(self, color: str):
        self._dot.configure(fg=color)

    def set(self, key: str, text: str):
        if key in self._items:
            self._items[key].configure(text=text)
            return
        if self._sep_count:
            tk.Label(self, text="·", fg=COLOR_TEXT_FAINT, bg=COLOR_BAR,
                     font=(FONT_FAMILY, FONT_SIZE)).pack(side="left", padx=8)
        lbl = tk.Label(self, text=text, fg=COLOR_TEXT_MUTED, bg=COLOR_BAR,
                       font=(FONT_FAMILY, FONT_SIZE))
        lbl.pack(side="left")
        self._items[key] = lbl
        self._sep_count += 1


class Card(tk.Frame):
    """白色卡片 + 极淡边框。"""
    def __init__(self, master, **kw):
        super().__init__(master, bg=COLOR_CARD, highlightbackground=COLOR_BORDER,
                         highlightthickness=1, bd=0, **kw)
