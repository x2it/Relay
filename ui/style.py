# -*- coding: utf-8 -*-
"""Relay terminal 主题：圆角按钮、细窄滚动条、卡片、ghost 按钮、terminal 导航。

标记规范（全 app 统一）：
  开关  = [●] 开 / [○] 关
  选择  = [✓] 选中 / [ ] 未选中
  行内操作 = [ed] 编辑 / [rm] 删除
  禁止使用 [x] / [X]（易被误读为"关闭/错误"）。
"""
import math
import tkinter as tk
from tkinter import ttk

from config import (FONT_FAMILY, FONT_SIZE, theme)


# ---------- 统一图标体系（Unicode 符号，零依赖，符合 Relay 标记规范） ----------
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
    SETTINGS = "⚙"     # 设置
    SOURCE = "❖"       # 抓取源
    SEARCH = "⌕"       # 搜索
    COPY = "❐"         # 复制
    PIN = "★"          # 设为当前上游
    SUBSCRIBE = "↻"     # 订阅导入
    ALARM = "⚠"        # 紧急/警告
    CLEAR = "⌫"        # 清空/清理（不用 ✕，避免误读为关闭）
    DELETE = "−"       # 删除（不用 ✕，避免误读为错误）
    # 状态类（规范：禁止 [x]/[X]）
    DOT_ON = "●"       # 可用 / 开
    DOT_OFF = "○"      # 未检测 / 关
    DOT_FAIL = "○"     # 不可用（空心，配合红色文字区分）
    DOT_RUN = "●"      # 运行中
    OK_MARK = "OK"
    FAIL_MARK = "ERR"
    # 选择类（规范：选择 = [✓]/[ ]，不用圆圈）
    SEL_ON = "✓"       # 选中
    SEL_OFF = " "      # 未选中
    # 行内操作
    OP_EDIT = "[ed]"   # 编辑
    OP_REMOVE = "[rm]" # 删除
    SEP = "·"          # 分隔


class NavItem(tk.Label):
    """terminal 导航项：选中=[HOME]，未选中= HOME 。"""

    def __init__(self, master, label, selected=False, command=None, **kw):
        self._label = label          # 如 "HOME"
        self._command = command
        self._selected = selected
        super().__init__(master, text=self._fmt(selected),
                         font=(FONT_FAMILY, FONT_SIZE + 1, "bold" if selected else "normal"),
                         fg=theme.COLOR_PRIMARY if selected else theme.COLOR_TEXT_MUTED,
                         bg=theme.COLOR_BAR, cursor="hand2", **kw)
        self.bind("<Button-1>", self._on_click)
        self.bind("<Enter>", lambda e: self.configure(fg=theme.COLOR_PRIMARY if self._selected else theme.COLOR_TEXT))
        self.bind("<Leave>", lambda e: self.configure(
            fg=theme.COLOR_PRIMARY if self._selected else theme.COLOR_TEXT_MUTED))

    def _fmt(self, selected):
        return f"[{self._label}]" if selected else f" {self._label} "

    def set_selected(self, on):
        self._selected = on
        self.configure(text=self._fmt(on),
                       font=(FONT_FAMILY, FONT_SIZE + 1, "bold" if on else "normal"),
                       fg=theme.COLOR_PRIMARY if on else theme.COLOR_TEXT_MUTED)

    def _on_click(self, e):
        if self._command:
            self._command()


class TermLine(tk.Frame):
    """terminal 分隔线：细横线。"""

    def __init__(self, master, **kw):
        super().__init__(master, bg=theme.COLOR_BORDER, height=1, **kw)


class RelayEntry(tk.Entry):
    """统一输入框：扁平无凸起、聚焦主色描边、主题自适应。"""

    def __init__(self, master, **kw):
        super().__init__(master, relief="flat", bd=0, highlightthickness=1,
                         highlightbackground=theme.COLOR_BORDER,
                         highlightcolor=theme.COLOR_PRIMARY,
                         bg=theme.COLOR_ENTRY_BG, fg=theme.COLOR_TEXT,
                         insertbackground=theme.COLOR_TEXT,
                         selectbackground=theme.COLOR_PRIMARY_SOFT,
                         selectforeground=theme.COLOR_PRIMARY,
                         font=(FONT_FAMILY, FONT_SIZE), **kw)
        self.bind("<FocusIn>", lambda e: self.configure(
            highlightbackground=theme.COLOR_PRIMARY, highlightcolor=theme.COLOR_PRIMARY))
        self.bind("<FocusOut>", lambda e: self.configure(
            highlightbackground=theme.COLOR_BORDER, highlightcolor=theme.COLOR_BORDER))

    def set_placeholder(self, text, muted=True):
        """浅色占位提示（真实内容置空时显示）。"""
        self._placeholder = text
        if not self.get():
            self._show_placeholder()
        self.bind("<FocusIn>", self._on_focus_in_ph, add="+")
        self.bind("<FocusOut>", self._on_focus_out_ph, add="+")

    def _show_placeholder(self):
        self.configure(fg=theme.COLOR_TEXT_FAINT)
        self.insert(0, self._placeholder)

    def _hide_placeholder(self):
        if self.get() == self._placeholder:
            self.delete(0, "end")
        self.configure(fg=theme.COLOR_TEXT)

    def _on_focus_in_ph(self, e):
        if self.get() == getattr(self, "_placeholder", None):
            self._hide_placeholder()

    def _on_focus_out_ph(self, e):
        if not self.get() and getattr(self, "_placeholder", None):
            self._show_placeholder()


class DialogTitle(tk.Frame):
    """统一对话框标题：主色小标题 + 副题 + 分隔线。"""

    def __init__(self, master, title, subtitle=None, **kw):
        super().__init__(master, bg=theme.COLOR_BG, **kw)
        tk.Label(self, text=title, bg=theme.COLOR_BG, fg=theme.COLOR_PRIMARY,
                 font=(FONT_FAMILY, 13, "bold")).pack(anchor="w")
        if subtitle:
            tk.Label(self, text=subtitle, bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                     font=(FONT_FAMILY, FONT_SIZE - 1)).pack(anchor="w", pady=(3, 0))
        TermLine(self).pack(fill="x", pady=(10, 0))


def apply_style(root: tk.Tk):
    root.configure(bg=theme.COLOR_BG)
    s = ttk.Style(root)
    try:
        s.theme_use("clam")
    except Exception:
        pass

    f = (FONT_FAMILY, FONT_SIZE)
    fb = (FONT_FAMILY, FONT_SIZE, "bold")

    # Frame
    s.configure("TFrame", background=theme.COLOR_BG)
    s.configure("Card.TFrame", background=theme.COLOR_CARD)
    s.configure("Bar.TFrame", background=theme.COLOR_BAR)

    # Label
    s.configure("TLabel", background=theme.COLOR_BG, foreground=theme.COLOR_TEXT, font=f)
    s.configure("Card.TLabel", background=theme.COLOR_CARD, foreground=theme.COLOR_TEXT, font=f)
    s.configure("Muted.TLabel", background=theme.COLOR_BG, foreground=theme.COLOR_TEXT_MUTED, font=f)
    s.configure("MutedCard.TLabel", background=theme.COLOR_CARD, foreground=theme.COLOR_TEXT_MUTED, font=f)
    s.configure("Faint.TLabel", background=theme.COLOR_BG, foreground=theme.COLOR_TEXT_FAINT, font=f)
    s.configure("Title.TLabel", background=theme.COLOR_BAR, foreground=theme.COLOR_TEXT,
                font=(FONT_FAMILY, 15, "bold"))
    s.configure("Brand.TLabel", background=theme.COLOR_BAR, foreground=theme.COLOR_PRIMARY,
                font=(FONT_FAMILY, 15, "bold"))
    s.configure("Ver.TLabel", background=theme.COLOR_BAR, foreground=theme.COLOR_TEXT_FAINT, font=f)
    s.configure("Status.TLabel", background=theme.COLOR_BAR, foreground=theme.COLOR_TEXT_MUTED, font=f)

    # Treeview：无边框，精致表头
    s.configure("Treeview",
                background=theme.COLOR_CARD, foreground=theme.COLOR_TEXT,
                fieldbackground=theme.COLOR_CARD, borderwidth=0,
                font=f, rowheight=28)
    s.configure("Treeview.Heading",
                background=theme.COLOR_CARD, foreground=theme.COLOR_TEXT_FAINT,
                font=(FONT_FAMILY, FONT_SIZE - 1, "bold"),
                borderwidth=0, relief="flat", padding=(8, 6))
    s.map("Treeview.Heading",
          background=[("active", theme.COLOR_CARD)])
    s.map("Treeview",
          background=[("selected", theme.COLOR_PRIMARY_SOFT)],
          foreground=[("selected", theme.COLOR_PRIMARY)])

    # 滚动条：细窄、半透明灰、无箭头突起
    s.configure("Vertical.TScrollbar",
                background=theme.COLOR_CARD, troughcolor=theme.COLOR_BG,
                bordercolor=theme.COLOR_BG, arrowcolor=theme.COLOR_TEXT_FAINT,
                lightcolor=theme.COLOR_CARD, darkcolor=theme.COLOR_CARD,
                gripcount=0, arrowsize=0)
    s.map("Vertical.TScrollbar",
          background=[("active", theme.COLOR_TEXT_FAINT)])
    s.configure("Horizontal.TScrollbar",
                background=theme.COLOR_CARD, troughcolor=theme.COLOR_BG,
                bordercolor=theme.COLOR_BG, arrowcolor=theme.COLOR_TEXT_FAINT,
                lightcolor=theme.COLOR_CARD, darkcolor=theme.COLOR_CARD,
                gripcount=0, arrowsize=0)
    s.map("Horizontal.TScrollbar",
          background=[("active", theme.COLOR_TEXT_FAINT)])

    # Entry
    s.configure("TEntry",
                fieldbackground=theme.COLOR_ENTRY_BG, foreground=theme.COLOR_TEXT,
                borderwidth=0, relief="flat", padding=7)
    s.map("TEntry",
          bordercolor=[("focus", theme.COLOR_PRIMARY)])

    # Combobox
    s.configure("TCombobox",
                fieldbackground=theme.COLOR_CARD, foreground=theme.COLOR_TEXT,
                background=theme.COLOR_CARD, borderwidth=0,
                arrowcolor=theme.COLOR_TEXT_MUTED, padding=6)
    s.map("TCombobox",
          fieldbackground=[("readonly", theme.COLOR_CARD)],
          selectbackground=[("readonly", theme.COLOR_PRIMARY_SOFT)],
          selectforeground=[("readonly", theme.COLOR_PRIMARY)])
    # 下拉列表样式
    s.configure("TCombobox.SListbox", background=theme.COLOR_CARD, foreground=theme.COLOR_TEXT,
                borderwidth=0, selectbackground=theme.COLOR_PRIMARY_SOFT,
                selectforeground=theme.COLOR_PRIMARY)

    # Checkbutton
    s.configure("TCheckbutton",
                background=theme.COLOR_CARD, foreground=theme.COLOR_TEXT, font=f)
    s.map("TCheckbutton", background=[("active", theme.COLOR_CARD)])

    # Progressbar：精致主色细条
    s.configure("Horizontal.TProgressbar",
                background=theme.COLOR_PRIMARY, troughcolor=theme.COLOR_BORDER,
                borderwidth=0, thickness=4, lightcolor=theme.COLOR_PRIMARY,
                darkcolor=theme.COLOR_PRIMARY)


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
        bg = theme.COLOR_BG
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
            self._fill = theme.COLOR_PRIMARY
            self._fill_hover = theme.COLOR_PRIMARY_HOVER
            self._fg = theme.COLOR_ON_PRIMARY
        elif kind == "danger":
            self._fill = theme.COLOR_DANGER
            self._fill_hover = theme.COLOR_DANGER_HOVER
            self._fg = theme.COLOR_ON_PRIMARY
        elif kind == "soft":
            self._fill = theme.COLOR_PRIMARY_SOFT
            self._fill_hover = theme.COLOR_PRIMARY_SOFT_HOVER
            self._fg = theme.COLOR_PRIMARY
        else:  # ghost
            self._fill = bg
            self._fill_hover = theme.COLOR_HOVER
            self._fg = theme.COLOR_TEXT

        self._bg = bg
        self._disabled_fill = theme.COLOR_BORDER
        self._disabled_fg = theme.COLOR_TEXT_FAINT

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

    def rebuild(self, text, kind=None):
        """整体重绘（切换 kind 时按钮底色+文字全量刷新，宽度不变）。"""
        if kind is None:
            kind = self.kind
        else:
            self.kind = kind
        # 按新 kind 计算配色
        if kind == "primary":
            self._fill = theme.COLOR_PRIMARY
            self._fill_hover = theme.COLOR_PRIMARY_HOVER
            self._fg = theme.COLOR_ON_PRIMARY
        elif kind == "danger":
            self._fill = theme.COLOR_DANGER_STRONG
            self._fill_hover = theme.COLOR_DANGER_STRONG_HOVER
            self._fg = theme.COLOR_ON_PRIMARY
        elif kind == "soft":
            self._fill = theme.COLOR_PRIMARY_SOFT
            self._fill_hover = theme.COLOR_PRIMARY_SOFT_HOVER
            self._fg = theme.COLOR_PRIMARY
        else:  # ghost
            self._fill = self._bg
            self._fill_hover = theme.COLOR_HOVER
            self._fg = theme.COLOR_TEXT
        self._text = text
        self.itemconfigure(self._shape, fill=self._fill)
        self.itemconfigure(self._label, text=text, fill=self._fg)


class StatusBar(tk.Frame):
    """底部状态栏：圆点 + 分段。"""

    def __init__(self, master):
        super().__init__(master, bg=theme.COLOR_BAR, height=32)
        self._items = {}
        self._sep_count = 0
        self._dot = tk.Label(self, text="●", fg=theme.COLOR_TEXT_FAINT,
                             bg=theme.COLOR_BAR, font=(FONT_FAMILY, 8))
        self._dot.pack(side="left", padx=(14, 6))

    def set_dot(self, color: str):
        self._dot.configure(fg=color)

    def set(self, key: str, text: str):
        if key in self._items:
            self._items[key].configure(text=text)
            return
        if self._sep_count:
            tk.Label(self, text="·", fg=theme.COLOR_TEXT_FAINT, bg=theme.COLOR_BAR,
                     font=(FONT_FAMILY, FONT_SIZE)).pack(side="left", padx=8)
        lbl = tk.Label(self, text=text, fg=theme.COLOR_TEXT_MUTED, bg=theme.COLOR_BAR,
                       font=(FONT_FAMILY, FONT_SIZE))
        lbl.pack(side="left")
        self._items[key] = lbl
        self._sep_count += 1


class Card(tk.Frame):
    """白色卡片 + 极淡边框。"""
    def __init__(self, master, **kw):
        super().__init__(master, bg=theme.COLOR_CARD, highlightbackground=theme.COLOR_BORDER,
                         highlightthickness=1, bd=0, **kw)
