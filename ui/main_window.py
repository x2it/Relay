# -*- coding: utf-8 -*-
"""LiteProxy 主窗口 —— 精致简约优雅。"""
import os
import time
import threading
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

from config import (APP_NAME, APP_VERSION, COLOR_BG, COLOR_CARD, COLOR_BAR,
                    COLOR_HOVER, COLOR_BORDER, COLOR_TEXT, COLOR_TEXT_MUTED,
                    COLOR_TEXT_FAINT, COLOR_PRIMARY, COLOR_PRIMARY_SOFT,
                    COLOR_GOOD, COLOR_BAD, COLOR_WARN, COLOR_ROW_ALT,
                    COLOR_ROW_HOVER, FONT_FAMILY, FONT_SIZE,
                    DEFAULT_LOCAL_HOST, DEFAULT_LOCAL_PORT)
from ui.style import apply_style, RoundedButton, StatusBar, Card, Icon
from ui.dialogs import SettingsDialog, SourcesDialog, SubscriptionDialog
from core import store, fetcher, checker
from core.local_proxy import LocalProxyServer, ProxyRotator, SystemProxyManager
from core.protocols import ALL_PROTOCOLS, PROTOCOL_LABELS, ENCRYPTED_PROTOCOLS, parse_subscription


COLUMNS = ("idx", "status_dot", "ip", "port", "protocol", "https", "country", "anonymity",
           "latency", "speed", "source", "last_check")
COL_HEADERS = {
    "idx": "#", "status_dot": "", "ip": "IP 地址", "port": "端口", "protocol": "协议",
    "https": "HTTPS", "country": "国家", "anonymity": "匿名度",
    "latency": "延迟", "speed": "速度",
    "source": "来源", "last_check": "最后检测",
}
COL_WIDTH = {
    "idx": 44, "status_dot": 36, "ip": 150, "port": 60, "protocol": 70, "https": 50,
    "country": 70, "anonymity": 80, "latency": 80, "speed": 110,
    "source": 130, "last_check": 140,
}


def _status_text(p):
    if not p.get("last_check"):
        return "未检测"
    return "可用" if p.get("alive") else "不可用"


def _latency_text(p):
    if not p.get("alive"):
        return "-"
    return f"{p.get('latency_ms', 0)}ms"


def _speed_text(p):
    if not p.get("alive"):
        return "-"
    return f"{p.get('speed_kbps', 0)} KB/s"


def _dot_char(p):
    if not p.get("last_check"):
        return Icon.DOT_OFF
    return Icon.DOT_ON if p.get("alive") else Icon.DOT_FAIL


def _dot_color(p):
    if not p.get("last_check"):
        return COLOR_TEXT_FAINT
    return COLOR_GOOD if p.get("alive") else COLOR_BAD


class MainWindow:
    def __init__(self, root: tk.Tk):
        self.root = root
        apply_style(root)
        root.title(f"{APP_NAME}")
        root.geometry("1180x720")
        root.minsize(960, 560)
        root.configure(bg=COLOR_BG)

        # 数据
        self.proxies = store.load_proxies()
        self.settings = store.load_settings()
        self.sources = store.load_sources()

        # 自动保存定时器（每 15 秒）
        self._last_save_time = time.time()
        self._auto_save_id = None
        self._start_auto_save()

        # 本地代理
        self.rotator = ProxyRotator(
            get_proxies=lambda: self.proxies,
            auto_switch=self.settings.get("auto_switch", True),
            max_latency_ms=self.settings.get("max_latency_ms", 3000),
        )
        self.server: LocalProxyServer = None
        # 系统代理管理（两阶段提交 + 必达恢复）
        self.sys_proxy_mgr = SystemProxyManager()
        self._busy = False
        self._sort_key = None      # 当前排序列: ip/port/latency/speed
        self._sort_desc = False    # 降序？
        # 规则引擎（智能分流）
        from core.local_proxy import RuleEngine
        self.rule_engine = RuleEngine(
            mode=self.settings.get("mode", "smart"),
            direct_domains=self.settings.get("direct_domains"),
            proxy_domains=self.settings.get("proxy_domains"),
        )
        self._build_ui()
        self._refresh_table()
        self._update_status_bar()
        # 启动时显示已加载代理数
        if self.proxies:
            alive = sum(1 for p in self.proxies if p.get("alive"))
            self.prog_lbl.configure(text=f"已加载 {len(self.proxies)} 个代理 · {alive} 个可用")

    # ---------------- UI ----------------
    def _build_ui(self):
        # 顶栏
        top = tk.Frame(self.root, bg=COLOR_BAR, height=54)
        top.pack(fill="x", side="top")
        top.pack_propagate(False)
        # 底部细分割线
        tk.Frame(top, bg=COLOR_BORDER, height=1).pack(fill="x", side="bottom")

        tk.Label(top, text=APP_NAME, bg=COLOR_BAR, fg=COLOR_PRIMARY,
                 font=(FONT_FAMILY, 15, "bold")).pack(side="left", padx=(18, 0))
        tk.Label(top, text=f"  v{APP_VERSION}  ·  轻量代理工具",
                 bg=COLOR_BAR, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")

        # 紧急恢复（始终可见，小而醒目）
        RoundedButton(top, f"{Icon.ALARM} 紧急恢复网络", command=self._on_emergency_restore,
                      kind="danger").pack(side="right", padx=(6, 14), pady=12)

        RoundedButton(top, f"{Icon.SOURCE} 抓取源", command=self._open_sources,
                      kind="ghost").pack(side="right", padx=6, pady=12)
        RoundedButton(top, f"{Icon.SETTINGS} 设置", command=self._open_settings,
                      kind="ghost").pack(side="right", padx=6, pady=12)
        RoundedButton(top, "规则", command=self._open_rules,
                      kind="ghost").pack(side="right", padx=6, pady=12)

        # 模式切换
        tk.Label(top, text="模式", bg=COLOR_BAR, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="right", padx=(0, 0))
        from config import MODE_LABELS
        self.mode_var = tk.StringVar(
            value=MODE_LABELS.get(self.settings.get("mode", "smart"), "智能分流"))
        cb_mode = ttk.Combobox(top, textvariable=self.mode_var, width=10,
                               state="readonly", values=list(MODE_LABELS.values()))
        cb_mode.pack(side="right", padx=(6, 8), pady=12)
        cb_mode.bind("<<ComboboxSelected>>", lambda e: self._on_mode_change())

        # 主体内容区（带边距）
        content = tk.Frame(self.root, bg=COLOR_BG)
        content.pack(fill="both", expand=True, padx=16, pady=12)

        # 工具栏卡片
        toolbar = Card(content)
        toolbar.pack(fill="x")
        tb = tk.Frame(toolbar, bg=COLOR_CARD, padx=14, pady=10)
        tb.pack(fill="x")

        # 第一行：主流程操作
        row1 = tk.Frame(tb, bg=COLOR_CARD)
        row1.pack(fill="x")
        self.btn_fetch = RoundedButton(row1, f"{Icon.FETCH} 抓取代理", command=self._on_fetch)
        self.btn_fetch.pack(side="left", padx=6)
        self.btn_check = RoundedButton(row1, f"{Icon.CHECK} 验证全部", command=self._on_check_all, kind="soft")
        self.btn_check.pack(side="left", padx=6)
        self._vsep(row1)
        self.btn_oneshot = RoundedButton(row1, f"{Icon.ONESHOT} 一键流程", command=self._on_oneshot)
        self.btn_oneshot.pack(side="left", padx=6)
        self._vsep(row1)
        self.btn_toggle = RoundedButton(row1, f"{Icon.START} 启动本地代理", command=self._on_toggle_server)
        self.btn_toggle.pack(side="left", padx=6)
        RoundedButton(row1, "诊断", command=self._open_diagnostic, kind="ghost").pack(side="left", padx=6)

        # 第二行：数据管理 + 系统代理
        row2 = tk.Frame(tb, bg=COLOR_CARD)
        row2.pack(fill="x", pady=(10, 0))
        RoundedButton(row2, f"{Icon.IMPORT} 导入", command=self._on_import, kind="ghost").pack(side="left", padx=(0, 6))
        RoundedButton(row2, f"{Icon.EXPORT} 导出", command=self._on_export, kind="ghost").pack(side="left", padx=6)
        RoundedButton(row2, f"{Icon.SUBSCRIBE} 订阅导入", command=self._on_import_subscription,
                      kind="ghost").pack(side="left", padx=6)
        self._vsep(row2)
        RoundedButton(row2, f"{Icon.CLEAR} 清空不可用", command=self._on_clear_dead, kind="ghost").pack(side="left", padx=6)
        RoundedButton(row2, f"{Icon.DELETE} 删除选中", command=self._on_delete, kind="danger").pack(side="left", padx=6)
        # 系统代理开关（右对齐，独立分组）
        self.btn_sys_proxy = RoundedButton(row2, f"系统代理：关", command=self._on_toggle_system_proxy, kind="soft")
        self.btn_sys_proxy.pack(side="right", padx=(6, 0))

        # 进程可视化行
        prog = Card(content)
        prog.pack(fill="x", pady=(10, 0))
        prog_inner = tk.Frame(prog, bg=COLOR_CARD, padx=14, pady=10)
        prog_inner.pack(fill="x")

        self.prog_bar = ttk.Progressbar(prog_inner, length=240, mode="determinate",
                                        maximum=100, value=0)
        self.prog_bar.pack(side="left")
        self.prog_lbl = tk.Label(prog_inner, text="就绪", bg=COLOR_CARD,
                                 fg=COLOR_TEXT_MUTED, font=(FONT_FAMILY, FONT_SIZE))
        self.prog_lbl.pack(side="left", padx=(12, 0))
        self.stat_lbl = tk.Label(prog_inner, text="", bg=COLOR_CARD,
                                 fg=COLOR_TEXT_FAINT, font=(FONT_FAMILY, FONT_SIZE))
        self.stat_lbl.pack(side="right")

        # 过滤栏
        filt = tk.Frame(content, bg=COLOR_BG)
        filt.pack(fill="x", pady=(12, 8))
        self.search_var = tk.StringVar()
        self.search_var.trace_add("write", lambda *_: self._refresh_table())
        tk.Label(filt, text="搜索", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")
        se = tk.Entry(filt, textvariable=self.search_var, width=22,
                      relief="solid", bd=1, highlightthickness=0,
                      font=(FONT_FAMILY, FONT_SIZE), fg=COLOR_TEXT)
        se.pack(side="left", padx=(8, 16))
        se.bind("<Return>", lambda e: self._refresh_table())

        tk.Label(filt, text="协议", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")
        self.proto_var = tk.StringVar(value="全部")
        proto_choices = ["全部"] + [PROTOCOL_LABELS.get(k, k) for k in ALL_PROTOCOLS]
        cb_proto = ttk.Combobox(filt, textvariable=self.proto_var, width=13, state="readonly",
                                values=proto_choices)
        cb_proto.pack(side="left", padx=(4, 12))
        cb_proto.bind("<<ComboboxSelected>>", lambda e: self._refresh_table())

        tk.Label(filt, text="HTTPS", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")
        self.https_var = tk.StringVar(value="全部")
        cb_https = ttk.Combobox(filt, textvariable=self.https_var, width=8, state="readonly",
                                values=["全部", "仅 HTTPS", "仅 HTTP"])
        cb_https.pack(side="left", padx=(4, 12))
        cb_https.bind("<<ComboboxSelected>>", lambda e: self._refresh_table())

        tk.Label(filt, text="状态", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")
        self.status_var = tk.StringVar(value="全部")
        cb_status = ttk.Combobox(filt, textvariable=self.status_var, width=8, state="readonly",
                                 values=["全部", "可用", "不可用", "未检测"])
        cb_status.pack(side="left", padx=(4, 12))
        cb_status.bind("<<ComboboxSelected>>", lambda e: self._refresh_table())

        # 数值范围筛选：延迟上限 / 速度下限
        self._vsep(filt)
        tk.Label(filt, text="延迟 ≤", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")
        self.max_lat_var = tk.StringVar()
        self.max_lat_var.trace_add("write", lambda *_: self._refresh_table())
        e_lat = tk.Entry(filt, textvariable=self.max_lat_var, width=7,
                         relief="solid", bd=1, highlightthickness=0,
                         font=(FONT_FAMILY, FONT_SIZE), fg=COLOR_TEXT)
        e_lat.pack(side="left", padx=(4, 2))
        tk.Label(filt, text="ms", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left", padx=(0, 12))

        tk.Label(filt, text="速度 ≥", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")
        self.min_spd_var = tk.StringVar()
        self.min_spd_var.trace_add("write", lambda *_: self._refresh_table())
        e_spd = tk.Entry(filt, textvariable=self.min_spd_var, width=7,
                         relief="solid", bd=1, highlightthickness=0,
                         font=(FONT_FAMILY, FONT_SIZE), fg=COLOR_TEXT)
        e_spd.pack(side="left", padx=(4, 2))
        tk.Label(filt, text="KB/s", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left", padx=(0, 4))

        self.count_lbl = tk.Label(filt, text="", bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                                  font=(FONT_FAMILY, FONT_SIZE))
        self.count_lbl.pack(side="right")

        # 表格卡片
        table_card = Card(content)
        table_card.pack(fill="both", expand=True)
        tw = tk.Frame(table_card, bg=COLOR_CARD)
        tw.pack(fill="both", expand=True, padx=1, pady=1)

        self.tree = ttk.Treeview(tw, columns=COLUMNS, show="headings")
        # 可排序的列（除状态点和最后检测时间外都可排）
        sortable = {"idx", "ip", "port", "protocol", "country", "anonymity",
                    "latency", "speed", "source"}
        for c in COLUMNS:
            if c in sortable:
                self.tree.heading(c, text=COL_HEADERS[c],
                                  command=lambda col=c: self._on_sort(col))
            else:
                self.tree.heading(c, text=COL_HEADERS[c])
            anchor = "e" if c in ("idx", "port", "latency", "speed") else "w"
            self.tree.column(c, width=COL_WIDTH[c], anchor=anchor, stretch=True)
        # 行状态标签
        self.tree.tag_configure("ok", foreground=COLOR_GOOD)
        self.tree.tag_configure("bad", foreground=COLOR_BAD)
        self.tree.tag_configure("alt", background=COLOR_ROW_ALT)
        self.tree.tag_configure("hover", background=COLOR_ROW_HOVER)

        vsb = ttk.Scrollbar(tw, orient="vertical", command=self.tree.yview)
        hsb = ttk.Scrollbar(tw, orient="horizontal", command=self.tree.xview)
        self.tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        self.tree.grid(row=0, column=0, sticky="nsew")
        vsb.grid(row=0, column=1, sticky="ns")
        hsb.grid(row=1, column=0, sticky="ew")
        tw.rowconfigure(0, weight=1)
        tw.columnconfigure(0, weight=1)

        self.tree.bind("<Double-1>", self._on_double_click)
        self.tree.bind("<Button-3>", self._on_right_click)
        self.tree.bind("<Motion>", self._on_motion)
        self.tree.bind("<Leave>", lambda e: self._clear_hover())

        # 右键菜单
        self.menu = tk.Menu(self.root, tearoff=0, bg=COLOR_CARD, fg=COLOR_TEXT,
                            activebackground=COLOR_PRIMARY, activeforeground="#ffffff",
                            bd=0, relief="flat", font=(FONT_FAMILY, FONT_SIZE),
                            borderwidth=0, selectcolor=COLOR_PRIMARY)
        self.menu.add_command(label="锁定此代理", command=self._lock_selected)
        self.menu.add_command(label="解除锁定", command=self._unlock)
        self.menu.add_command(label=f"{Icon.PIN} 设为当前上游", command=self._set_current_upstream)
        self.menu.add_command(label=f"{Icon.CHECK} 单独验证", command=self._on_check_selected)
        self.menu.add_command(label=f"{Icon.COPY} 复制 ip:port", command=self._copy_selected)
        self.menu.add_separator()
        self.menu.add_command(label=f"{Icon.DELETE} 删除", command=self._on_delete)

        # 状态栏
        self.status = StatusBar(self.root)
        self.status.pack(side="bottom", fill="x")
        tk.Frame(self.root, bg=COLOR_BORDER, height=1).pack(side="bottom", fill="x")
        self.status.set("local", f"本地代理 {self.settings['local_host']}:{self.settings['local_port']} 未运行")
        self.status.set("upstream", "当前上游 无")
        self.status.set("stats", "请求 0 · 成功 0")
        self.status.set("count", "代理 0")

        self._tick()

    def _vsep(self, parent):
        tk.Frame(parent, width=1, bg=COLOR_BORDER).pack(side="left", fill="y", padx=10, pady=4)

    # ---------------- 表格 ----------------
    def _on_sort(self, col):
        """表头点击排序。"""
        if self._sort_key == col:
            self._sort_desc = not self._sort_desc
        else:
            self._sort_key = col
            self._sort_desc = (col == "speed")  # 速度默认降序，其余升序
        # 更新表头箭头
        arrow = " ↓" if self._sort_desc else " ↑"
        for c in ("idx", "ip", "port", "protocol", "country", "anonymity",
                  "latency", "speed", "source"):
            base = COL_HEADERS[c]
            if c == col:
                self.tree.heading(c, text=base + arrow,
                                  command=lambda k=c: self._on_sort(k))
            else:
                self.tree.heading(c, text=base,
                                  command=lambda k=c: self._on_sort(k))
        self._refresh_table()

    def _filter(self):
        kw = self.search_var.get().strip().lower()
        proto = self.proto_var.get()
        https_sel = self.https_var.get()
        st = self.status_var.get()
        # 数值范围
        max_lat = None
        min_spd = None
        try:
            if self.max_lat_var.get().strip():
                max_lat = float(self.max_lat_var.get().strip())
        except ValueError:
            pass
        try:
            if self.min_spd_var.get().strip():
                min_spd = float(self.min_spd_var.get().strip())
        except ValueError:
            pass

        out = []
        for i, p in enumerate(self.proxies):
            if kw and kw not in (p["ip"] + " " + p.get("country", "") + " " + p.get("source", "")).lower():
                continue
            if proto != "全部":
                proto_key = next((k for k, v in PROTOCOL_LABELS.items() if v == proto), proto)
                if p["protocol"] != proto_key:
                    continue
            if https_sel == "仅 HTTPS":
                proto = p.get("protocol", "http")
                if proto not in ENCRYPTED_PROTOCOLS and not p.get("https_ok"):
                    continue
            if https_sel == "仅 HTTP":
                proto = p.get("protocol", "http")
                if proto in ENCRYPTED_PROTOCOLS or p.get("https_ok"):
                    continue
            txt = _status_text(p)
            if st == "可用" and txt != "可用":
                continue
            if st == "不可用" and txt != "不可用":
                continue
            if st == "未检测" and txt != "未检测":
                continue
            # 数值筛选：仅对已检测的代理生效
            if max_lat is not None:
                if not p.get("alive") or p.get("latency_ms", 0) > max_lat:
                    continue
            if min_spd is not None:
                if not p.get("alive") or p.get("speed_kbps", 0) < min_spd:
                    continue
            out.append((i, p))

        # 排序
        sk = self._sort_key
        if sk:
            reverse = self._sort_desc
            if sk == "latency":
                # 可用优先，延迟升序；不可用排末尾
                out.sort(key=lambda x: (0, x[1].get("latency_ms", 99999)) if x[1].get("alive")
                        else (1, 0), reverse=False)
            elif sk == "speed":
                out.sort(key=lambda x: x[1].get("speed_kbps", 0), reverse=True)
            elif sk == "idx":
                out.sort(key=lambda x: x[0], reverse=reverse)
            elif sk == "port":
                out.sort(key=lambda x: x[1]["port"], reverse=reverse)
            elif sk == "protocol":
                out.sort(key=lambda x: x[1].get("protocol", ""), reverse=reverse)
            elif sk == "country":
                out.sort(key=lambda x: (x[1].get("country") or "").lower(), reverse=reverse)
            elif sk == "anonymity":
                out.sort(key=lambda x: (x[1].get("anonymity") or "").lower(), reverse=reverse)
            elif sk == "source":
                out.sort(key=lambda x: (x[1].get("source") or "").lower(), reverse=reverse)
            elif sk == "ip":
                # IP 按数值段排序更自然
                def ip_key(p):
                    try:
                        return tuple(int(x) for x in p["ip"].split("."))
                    except Exception:
                        return (0, 0, 0, 0)
                out.sort(key=lambda x: ip_key(x[1]), reverse=reverse)
        return out

    def _refresh_table(self):
        self.tree.delete(*self.tree.get_children())
        rows = self._filter()
        for n, (i, p) in enumerate(rows):
            tags = []
            if p.get("alive"):
                tags.append("ok")
            elif p.get("last_check"):
                tags.append("bad")
            if n % 2 == 1:
                tags.append("alt")
            dot = _dot_char(p)
            proto = p.get("protocol", "http")
            if proto in ENCRYPTED_PROTOCOLS or p.get("https_ok"):
                https_mark = "✓"
            else:
                https_mark = ""
            self.tree.insert("", "end", iid=str(i), values=(
                n + 1, dot, p["ip"], p["port"],
                PROTOCOL_LABELS.get(p["protocol"], p["protocol"]), https_mark,
                p.get("country", ""),
                p.get("anonymity", ""), _latency_text(p), _speed_text(p),
                p.get("source", ""), p.get("last_check", ""),
            ), tags=tuple(tags))
        # 圆点列用彩色：直接改 tag 颜色不方便，这里用 image 不可行；
        # 改为：圆点字符颜色随行 tag。简化：保留字符即可，可用行绿色。
        self._update_count(len(rows))

    def _update_count(self, shown=None):
        if shown is None:
            shown = len(self._filter())
        alive = sum(1 for p in self.proxies if p.get("alive"))
        self.count_lbl.configure(text=f"显示 {shown} / 共 {len(self.proxies)}  ·  可用 {alive}")
        self.status.set("count", f"代理 {len(self.proxies)} · 可用 {alive}")

    def _selected_indices(self):
        return [int(x) for x in self.tree.selection()]

    # hover 高亮
    def _on_motion(self, event):
        row = self.tree.identify_row(event.y)
        if not row:
            return
        if getattr(self, "_hover_row", None):
            try:
                self.tree.item(self._hover_row, tags=tuple(
                    t for t in self.tree.item(self._hover_row, "tags") if t != "hover"))
            except Exception:
                pass
        self._hover_row = row
        tags = list(self.tree.item(row, "tags"))
        if "hover" not in tags:
            tags.append("hover")
        self.tree.item(row, tags=tags)

    def _clear_hover(self):
        if getattr(self, "_hover_row", None):
            try:
                self.tree.item(self._hover_row, tags=tuple(
                    t for t in self.tree.item(self._hover_row, "tags") if t != "hover"))
            except Exception:
                pass
            self._hover_row = None

    # ---------------- 抓取 ----------------
    def _set_busy(self, on):
        self._busy = on
        for b in (self.btn_fetch, self.btn_check,
                  self.btn_oneshot, self.btn_toggle):
            b.set_enabled(not on)

    def _on_fetch(self):
        if not self.sources:
            messagebox.showwarning("提示", "未配置抓取源")
            return
        self._set_busy(True)
        self.prog_bar["maximum"] = len(self.sources)
        self.prog_bar["value"] = 0
        self.prog_lbl.configure(text="开始抓取…")
        self._fetch_total = 0
        self._fetch_ok = 0

        def work():
            try:
                def on_progress(done, total, name, count, ok):
                    self._fetch_total += count
                    if ok:
                        self._fetch_ok += 1
                    try:
                        self.root.after(0, lambda: self._fetch_progress(done, total, name, count, ok))
                    except Exception:
                        pass
                items = fetcher.fetch_all(self.sources, on_progress=on_progress)
                try:
                    self.root.after(0, lambda: self._fetch_done(items))
                except Exception:
                    self._set_busy(False)
            except Exception as e:
                try:
                    self.root.after(0, lambda: self._fetch_error(e))
                except Exception:
                    self._set_busy(False)

        threading.Thread(target=work, daemon=True).start()

    def _fetch_progress(self, done, total, name, count, ok):
        self.prog_bar["value"] = done
        mark = Icon.OK_MARK if ok else Icon.FAIL_MARK
        self.prog_lbl.configure(text=f"{mark} {name}  +{count}")
        self.stat_lbl.configure(text=f"源 {done}/{total}  ·  成功 {self._fetch_ok}  ·  累计 {self._fetch_total}")

    def _fetch_done(self, items):
        try:
            self.proxies, added = store.merge_proxies(self.proxies, items)
            self._force_save()
            self._refresh_table()
            self.prog_bar["value"] = 0
            self.prog_lbl.configure(text=f"新增 {added} · 共 {len(self.proxies)}")
            self.stat_lbl.configure(text="")
        except Exception as e:
            messagebox.showerror("处理出错", str(e))
        finally:
            self._set_busy(False)

    def _fetch_error(self, e):
        self._set_busy(False)
        self.prog_bar["value"] = 0
        self.prog_lbl.configure(text="抓取出错")
        messagebox.showerror("抓取异常", str(e))

    # ---------------- 验证 ----------------
    def _on_check_all(self):
        self._check(self.proxies[:])

    def _on_check_selected(self):
        idxs = self._selected_indices()
        if not idxs:
            messagebox.showinfo("提示", "请先选中要验证的代理")
            return
        self._check([self.proxies[i] for i in idxs])

    def _check(self, targets):
        if not targets:
            return
        self._set_busy(True)
        self.prog_bar["maximum"] = len(targets)
        self.prog_bar["value"] = 0
        alive0 = sum(1 for p in self.proxies if p.get("alive"))

        def work():
            try:
                def on_done(p, done, total):
                    try:
                        self.root.after(0, lambda d=done, t=total: self._check_one_done(d, t))
                    except Exception:
                        pass
                checker.check_batch(targets, on_done=on_done)
                try:
                    self.root.after(0, self._check_done, alive0)
                except Exception:
                    self._set_busy(False)
            except Exception as e:
                try:
                    self.root.after(0, lambda: self._check_error(e))
                except Exception:
                    self._set_busy(False)

        threading.Thread(target=work, daemon=True).start()

    def _check_one_done(self, done, total):
        self.prog_bar["value"] = done
        alive = sum(1 for p in self.proxies if p.get("alive"))
        self.prog_lbl.configure(text=f"验证 {done}/{total}")
        self.stat_lbl.configure(text=f"已发现可用 {alive}")
        # 周期性刷新表格（每若干个刷新一次，避免卡顿）
        if done % 20 == 0 or done == total:
            self._refresh_table()

    def _check_done(self, alive0):
        try:
            self._force_save()
            self._refresh_table()
            alive = sum(1 for p in self.proxies if p.get("alive"))
            self.prog_bar["value"] = 0
            self.prog_lbl.configure(text=f"完成 · 可用 {alive}")
            self.stat_lbl.configure(text=f"本次新增可用 {max(0, alive - alive0)}")
        except Exception as e:
            messagebox.showerror("处理出错", str(e))
        finally:
            self._set_busy(False)

    def _check_error(self, e):
        self._set_busy(False)
        self.prog_bar["value"] = 0
        self.prog_lbl.configure(text="验证出错")
        messagebox.showerror("验证异常", str(e))

    # ---------------- 一键流程 ----------------
    def _on_oneshot(self):
        """抓取 → 验证 → 启动本地代理。"""
        if self._busy:
            return
        self._oneshot_phase = "fetch"
        self._on_fetch_with_callback(self._oneshot_after_fetch)

    def _on_fetch_with_callback(self, cb):
        if not self.sources:
            messagebox.showwarning("提示", "未配置抓取源")
            return
        self._set_busy(True)
        self.prog_bar["maximum"] = len(self.sources)
        self.prog_bar["value"] = 0
        self.prog_lbl.configure(text="一键流程 · 抓取中…")
        self._fetch_total = 0
        self._fetch_ok = 0

        def work():
            try:
                def on_progress(done, total, name, count, ok):
                    self._fetch_total += count
                    if ok:
                        self._fetch_ok += 1
                    try:
                        self.root.after(0, lambda: self._fetch_progress(done, total, name, count, ok))
                    except Exception:
                        pass
                items = fetcher.fetch_all(self.sources, on_progress=on_progress)
                try:
                    self.root.after(0, lambda: self._fetch_done_cb(items, cb))
                except Exception:
                    self._set_busy(False)
            except Exception as e:
                try:
                    self.root.after(0, lambda: self._fetch_error(e))
                except Exception:
                    self._set_busy(False)

        threading.Thread(target=work, daemon=True).start()

    def _fetch_done_cb(self, items, cb):
        try:
            self.proxies, added = store.merge_proxies(self.proxies, items)
            self._force_save()
            self._refresh_table()
            self.prog_lbl.configure(text=f"抓取完成 · 新增 {added}")
            cb()
        except Exception as e:
            self._set_busy(False)
            messagebox.showerror("处理出错", str(e))

    def _oneshot_after_fetch(self):
        self._oneshot_phase = "check"
        self._check_with_callback(self._oneshot_after_check)

    def _check_with_callback(self, cb):
        targets = self.proxies[:]
        if not targets:
            self._set_busy(False)
            return
        self.prog_bar["maximum"] = len(targets)
        self.prog_bar["value"] = 0
        alive0 = sum(1 for p in self.proxies if p.get("alive"))

        def work():
            try:
                def on_done(p, done, total):
                    try:
                        self.root.after(0, lambda d=done, t=total: self._check_one_done(d, t))
                    except Exception:
                        pass
                checker.check_batch(targets, on_done=on_done)
                try:
                    self.root.after(0, lambda: self._oneshot_check_done(alive0, cb))
                except Exception:
                    self._set_busy(False)
            except Exception as e:
                try:
                    self.root.after(0, lambda: self._check_error(e))
                except Exception:
                    self._set_busy(False)

        threading.Thread(target=work, daemon=True).start()

    def _oneshot_check_done(self, alive0, cb):
        try:
            self._force_save()
            self._refresh_table()
            alive = sum(1 for p in self.proxies if p.get("alive"))
            self.prog_lbl.configure(text=f"验证完成 · 可用 {alive}")
            cb()
        except Exception as e:
            self._set_busy(False)
            messagebox.showerror("处理出错", str(e))

    def _oneshot_after_check(self):
        alive = sum(1 for p in self.proxies if p.get("alive"))
        self._set_busy(False)
        if not self.server and alive > 0:
            self._start_server()
        msg = f"一键流程完成\n\n可用代理：{alive} 个\n本地代理："
        if self.server:
            msg += f"{self.settings['local_host']}:{self.settings['local_port']} 运行中\n浏览器代理设为该地址即可使用"
        else:
            msg += "未启动（无可用代理）"
        messagebox.showinfo("一键流程", msg)

    # ---------------- 诊断面板 ----------------
    def _open_diagnostic(self):
        dlg = tk.Toplevel(self.root)
        dlg.title("连通性诊断")
        dlg.configure(bg=COLOR_BG)
        dlg.transient(self.root)
        dlg.geometry(f"640x640+{self.root.winfo_rootx()+40}+{self.root.winfo_rooty()+40}")

        wrap = tk.Frame(dlg, bg=COLOR_BG, padx=20, pady=18)
        wrap.pack(fill="both", expand=True)
        tk.Label(wrap, text="连通性诊断", bg=COLOR_BG, fg=COLOR_TEXT,
                 font=(FONT_FAMILY, 13, "bold")).pack(anchor="w")
        tk.Label(wrap, text="自动逐项检查 · 给出修复建议",
                 bg=COLOR_BG, fg=COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE - 1)).pack(anchor="w", pady=(2, 12))

        log_wrap = tk.Frame(wrap, bg=COLOR_CARD, highlightbackground=COLOR_BORDER,
                            highlightthickness=1)
        log_wrap.pack(fill="both", expand=True)
        log = tk.Text(log_wrap, bg=COLOR_CARD, fg=COLOR_TEXT,
                      font=(FONT_FAMILY, FONT_SIZE - 1),
                      relief="flat", wrap="word", padx=14, pady=12,
                      spacing1=3, spacing3=3)
        log.pack(side="left", fill="both", expand=True)
        # 彩色 tag
        log.tag_configure("pass", foreground=COLOR_PRIMARY)
        log.tag_configure("fail", foreground="#e74c3c")
        log.tag_configure("warn", foreground="#f39c12")
        log.tag_configure("dim", foreground=COLOR_TEXT_MUTED)
        log.tag_configure("h", foreground=COLOR_TEXT, font=(FONT_FAMILY, FONT_SIZE, "bold"))
        vsb = ttk.Scrollbar(log_wrap, orient="vertical", command=log.yview)
        log.configure(yscrollcommand=vsb.set, state="disabled")
        vsb.pack(side="right", fill="y")
        dlg._log = log

        btns = tk.Frame(wrap, bg=COLOR_BG)
        btns.pack(fill="x", pady=(14, 0))
        tip = tk.Label(btns, text="", bg=COLOR_BG, fg=COLOR_PRIMARY,
                       font=(FONT_FAMILY, FONT_SIZE))
        tip.pack(side="left")
        run_btn = RoundedButton(btns, "▶ 开始诊断",
                                command=lambda: self._run_diagnostic(dlg, run_btn, tip))
        run_btn.pack(side="right")

        # 打开后自动开始
        self.root.after(200, lambda: self._run_diagnostic(dlg, run_btn, tip))

    def _run_diagnostic(self, dlg, btn, tip_lbl):
        btn.set_text("诊断中…")
        btn.configure_state("disabled")
        tip_lbl.configure(text="")
        log = dlg._log
        log.configure(state="normal"); log.delete("1.0", "end"); log.configure(state="disabled")

        def write(line, tag=None):
            log.configure(state="normal")
            if tag:
                log.insert("end", line + "\n", tag)
            else:
                log.insert("end", line + "\n")
            log.see("end")
            log.configure(state="disabled")
            dlg.update_idletasks()

        def ok(t): write(f"  ✓ {t}", "pass")
        def bad(t): write(f"  ✕ {t}", "fail")
        def w(t): write(f"  ⚠ {t}", "warn")

        import socket, threading, time

        # === 5 项检查 ===
        write("")
        write("【1 / 5】本地监听端口检查", "h")
        host = self.settings["local_host"]
        port = self.settings["local_port"]
        if self.server and self.server._running:
            ok(f"本地代理服务运行中 · {host}:{port}")
        else:
            s = None
            try:
                s = socket.socket(); s.settimeout(2)
                s.connect((host, port)); s.close()
                ok(f"端口 {host}:{port} 有程序在监听（但非本软件实例）")
                w("本软件代理未在本界面启动，其他程序占了该端口，需关抢占程序或换端口")
            except Exception:
                bad(f"端口 {host}:{port} 未被占用，需先点「启动本地代理」")

        write("")
        write("【2 / 5】可用代理池检查", "h")
        alive_cnt = sum(1 for p in self.proxies if p.get("alive"))
        if alive_cnt > 0:
            ok(f"可用代理：{alive_cnt} / {len(self.proxies)}")
            cur = self.rotator.current()
            if cur:
                ok(f"当前上游：{cur['ip']}:{cur['port']} ({cur['protocol']}, {cur.get('latency_ms',0)}ms)"
                   + (" · 锁定中" if self.rotator.is_locked() else ""))
                if self.rotator.is_locked() and cur.get("speed_kbps", 0) < 10:
                    w(f"锁定的代理速度仅 {cur.get('speed_kbps',0):.1f} KB/s，可能打不开网页")
                    w("建议：右键 → 解除锁定，让软件自动切换其他代理")
            else:
                w("还未选中上游，软件会在第一个请求时自动选")
        else:
            bad("可用代理为 0")
            w("必须先点「抓取代理 → 验证全部」得到可用代理才能用")

        write("")
        write("【3 / 5】本机能直连外网吗？（判断是否需用代理）", "h")
        try:
            s = socket.create_connection(("8.8.8.8", 53), timeout=5)
            s.close()
            ok("能 ping 到 8.8.8.8 · 本机外网可达（但不代表能访问 YouTube/Google）")
        except Exception as e:
            w(f"无法直接连 8.8.8.8:53（{e}）· 本机外网可能受限，必须靠代理")

        write("")
        write("【4 / 5】用当前上游代理尝试访问 Google", "h")
        cur = self.rotator.pick() if self.rotator.current() is None else self.rotator.current()
        if not cur:
            bad("无上游代理可测")
        else:
            from core.protocols import PROTOCOL_LABELS
            proto_lbl = PROTOCOL_LABELS.get(cur.get("protocol", "http"), cur.get("protocol", "http"))
            write(f"  目标：{cur['ip']}:{cur['port']} ({proto_lbl})")
            # 复用 LocalProxyServer 的建立隧道逻辑
            from core.local_proxy import LocalProxyServer
            fake_rotator = self.rotator
            probe = LocalProxyServer(fake_rotator, "127.0.0.1", 0)
            proto = cur.get("protocol", "http")
            start = time.time()
            tunnel = None
            try:
                if proto in ("http", "https"):
                    tunnel = probe._open_upstream_tunnel(cur, "www.google.com", 443)
                elif proto == "socks5":
                    s = socket.create_connection((cur["ip"], cur["port"]), timeout=12)
                    s.settimeout(15)
                    from core.local_proxy import _socks5_connect
                    if _socks5_connect(s, "www.google.com", 443):
                        tunnel = s
                    else:
                        s.close()
                else:  # 加密协议 ss/vmess/vless/trojan
                    from core.protocols import connect_proxy
                    tunnel = connect_proxy(cur, "www.google.com", 443, timeout=12)
                elapsed = int((time.time() - start) * 1000)
                if tunnel:
                    # 发一个 GET 看能不能拿到响应
                    try:
                        tunnel.sendall(b"GET /generate_204 HTTP/1.0\r\nHost: www.google.com\r\nConnection: close\r\n\r\n")
                        buf = b""
                        tunnel.settimeout(10)
                        try:
                            while True:
                                c = tunnel.recv(4096)
                                if not c: break
                                buf += c
                                if len(buf) > 4096: break
                        except Exception:
                            pass
                        tunnel.close()
                        if buf and b"204" in buf.split(b"\r\n", 1)[0]:
                            ok(f"{Icon.OK_MARK} 成功建立隧道并拿到 Google 204 · 用时 {elapsed}ms")
                        elif buf:
                            head_line = buf.split(b'\r\n', 1)[0][:80]
                            ok(f"隧道建立成功，拿到响应（{len(buf)}B，首行{head_line}） · {elapsed}ms")
                        else:
                            w(f"隧道建立但无响应返回 · {elapsed}ms（代理可能已不稳定）")
                    except Exception as e2:
                        w(f"隧道建立成功但发送 GET 失败：{e2} · {elapsed}ms")
                else:
                    elapsed = int((time.time() - start) * 1000)
                    bad(f"无法通过该代理建立 HTTPS 隧道 · {elapsed}ms")
                    w("建议：验证选中、解除锁定、换其他代理，或重新抓取验证一批")
            except Exception as e:
                bad(f"异常：{e}")

        write("")
        write("【5 / 5】本机能否连到本地代理端口（模拟浏览器）", "h")
        if not self.server:
            w("本地代理未运行，跳过。建议先启动后再诊断一次")
        else:
            try:
                s = socket.create_connection((host, port), timeout=5)
                s.settimeout(10)
                s.sendall(b"GET /connect-test HTTP/1.1\r\nHost: liteproxy.test\r\nProxy-Connection: close\r\n\r\n")
                try:
                    resp = s.recv(2048)
                except Exception:
                    resp = b""
                s.close()
                if resp:
                    ok(f"本机→127.0.0.1:{port} 联通，返回 {len(resp)}B")
                    first_line = resp.split(b'\r\n', 1)[0][:120]
                    write(f"    首行：{first_line}", "dim")
                else:
                    w(f"联通但无响应（正常，该路径会走HTTP逻辑，结果不影响）")
                # 统计栏请求计数校验
                st = self.server.stats
                if st["requests"] > 0:
                    ok(f"浏览器→软件：已有 {st['requests']} 个请求进入")
                    write(f"    代理 {st.get('proxy',0)} · 直连 {st.get('direct',0)} · 失败 {st['fail']}", "dim")
                else:
                    w(f"软件启动后请求数为 0 → 浏览器代理可能没设到 {host}:{port}")
                    w("  修：Chrome 去 选项→系统→代理设置，地址和端口都填对")
                    w("  修：或装 SwitchyOmega，新建情景 HTTP {host}:{port} 并启用")
            except Exception as e:
                bad(f"连不上本地代理 {host}:{port}：{e}")
                w("确认代理已启动；如端口冲突去「设置」换端口")

        write("")
        write("─" * 56, "dim")
        write("快速修复建议（按优先级）：", "h")
        if alive_cnt == 0:
            write("  ① 「抓取代理 → 验证全部」先拿到可用代理", "warn")
        write("  ② 点「启动本地代理」", "warn")
        if alive_cnt > 0 and cur and cur.get("speed_kbps", 0) < 10:
            write("  ③ 右键代理 → 解除锁定，让自动切换选快代理", "warn")
        write("  ④ 浏览器代理设为 127.0.0.1:8888（HTTP 和 HTTPS 都要）", "warn")
        write("  ⑤ 如仍不通，重新抓取验证（免费代理易失效）", "warn")

        btn.set_text("▶ 重新诊断")
        btn.configure_state("normal")
        tip_lbl.configure(text="诊断完成")

    # ---------------- 本地代理服务器 ----------------
    def _on_toggle_server(self):
        if self.server:
            self._stop_server()
        else:
            self._start_server()

    def _start_server(self):
        host = self.settings["local_host"]
        port = self.settings["local_port"]
        try:
            self.server = LocalProxyServer(self.rotator, host, port,
                                           auth_token=self.settings.get("auth_token", ""),
                                           rule_engine=self.rule_engine)
            self.server.start()
        except OSError as e:
            messagebox.showerror("启动失败", f"端口 {port} 可能被占用\n{e}")
            self.server = None
            return
        self.btn_toggle.set_text(f"{Icon.STOP} 停止本地代理")
        self.btn_toggle.kind = "danger"
        self.btn_toggle._fill = "#e5484d"
        self.btn_toggle._fill_hover = "#c93a3f"
        self.btn_toggle.itemconfigure(self.btn_toggle._shape, fill="#e5484d")
        self.status.set_dot(COLOR_GOOD)
        self.status.set("local", f"本地代理 {host}:{port} 运行中")

    def _stop_server(self):
        # 先关系统代理（如果开着）
        if self.sys_proxy_mgr.is_applied:
            self.sys_proxy_mgr.emergency_restore()
            self._update_sys_proxy_btn()
        if self.server:
            self.server.stop()
            self.server = None
        self.btn_toggle.set_text(f"{Icon.START} 启动本地代理")
        self.btn_toggle.kind = "primary"
        self.btn_toggle._fill = COLOR_PRIMARY
        self.btn_toggle._fill_hover = "#2f5bd6"
        self.btn_toggle.itemconfigure(self.btn_toggle._shape, fill=COLOR_PRIMARY)
        self.status.set_dot(COLOR_TEXT_FAINT)
        self.status.set("local", f"本地代理 {self.settings['local_host']}:{self.settings['local_port']} 未运行")

    def _on_toggle_system_proxy(self):
        """切换系统代理（两阶段提交：必须本地代理在运行）。"""
        if self.sys_proxy_mgr.is_applied:
            # 关闭
            self.sys_proxy_mgr.restore()
            self._update_sys_proxy_btn()
            self.status.set("local", f"系统代理已关闭 · 流量直连")
        else:
            # 开启：必须先有本地代理 + 可用上游
            if not self.server or not self.server._running:
                messagebox.showwarning("无法开启", "请先启动本地代理（点「启动本地代理」）")
                return
            alive = sum(1 for p in self.proxies if p.get("alive"))
            if alive == 0:
                messagebox.showwarning("无法开启", "没有可用代理，请先「抓取 → 验证」")
                return
            host = self.settings["local_host"]
            port = self.settings["local_port"]
            ok = self.sys_proxy_mgr.enable(host, port)
            if ok:
                self._update_sys_proxy_btn()
                self.status.set("local", f"系统代理已开启 · 浏览器流量走 {host}:{port}")
            else:
                messagebox.showerror("开启失败", f"无法连接 {host}:{port}，本地代理可能未就绪")

    def _on_emergency_restore(self):
        """紧急恢复网络：关闭系统代理 + 恢复原值 + 重置所有相关状态。"""
        if messagebox.askyesno("紧急恢复网络",
            "将立即关闭系统代理并恢复原值，所有流量直连。\n\n"
            "是否继续？"):
            self.sys_proxy_mgr.emergency_restore()
            self._update_sys_proxy_btn()
            self.status.set("local", "系统代理已关闭 · 网络已恢复直连")
            messagebox.showinfo("已恢复", "系统代理已关闭，网络已恢复直连。\n如仍有问题请手动检查。")

    def _update_sys_proxy_btn(self):
        if self.sys_proxy_mgr.is_applied:
            self.btn_sys_proxy.set_text("系统代理：开")
            self.btn_sys_proxy.kind = "danger"
            self.btn_sys_proxy._fill = "#e5484d"
            self.btn_sys_proxy._fill_hover = "#c93a3f"
            self.btn_sys_proxy.itemconfigure(self.btn_sys_proxy._shape, fill="#e5484d")
        else:
            self.btn_sys_proxy.set_text("系统代理：关")
            self.btn_sys_proxy.kind = "soft"
            self.btn_sys_proxy._fill = COLOR_PRIMARY_SOFT
            self.btn_sys_proxy._fill_hover = "#dbe5ff"
            self.btn_sys_proxy.itemconfigure(self.btn_sys_proxy._shape, fill=COLOR_PRIMARY_SOFT)

    # ---------------- 设置 / 源 ----------------
    def _open_settings(self):
        dlg = SettingsDialog(self.root, self.settings)
        self.root.wait_window(dlg)
        if dlg.result:
            old = (self.settings["local_host"], self.settings["local_port"])
            self.settings.update(dlg.result)
            store.save_settings(self.settings)
            self.rotator.auto_switch = self.settings.get("auto_switch", True)
            self.rotator.max_latency_ms = self.settings.get("max_latency_ms", 3000)
            if self.server and (old != (self.settings["local_host"], self.settings["local_port"])
                                or self.server.auth_token != self.settings.get("auth_token", "")):
                self._stop_server()
                self._start_server()
            self._update_status_bar()

    def _open_sources(self):
        dlg = SourcesDialog(self.root, self.sources)
        self.root.wait_window(dlg)
        if dlg.result is not None:
            self.sources = dlg.result
            store.save_sources(self.sources)
            messagebox.showinfo("已保存", f"共 {len(self.sources)} 个抓取源")

    def _lock_selected(self):
        sel = self.tree.selection()
        if not sel:
            return
        idx = int(sel[0])
        if 0 <= idx < len(self.proxies):
            p = self.proxies[idx]
            self.rotator.lock(p)
            self._update_status_bar()
            self.prog_lbl.configure(text=f"已锁定 {p['ip']}:{p['port']}")

    def _unlock(self):
        self.rotator.lock(None)
        self._update_status_bar()
        self.prog_lbl.configure(text="已解除锁定，恢复自动切换")

    def _open_rules(self):
        from ui.dialogs import RulesDialog
        dlg = RulesDialog(self.root,
                          self.settings.get("direct_domains", []),
                          self.settings.get("proxy_domains", []))
        self.root.wait_window(dlg)
        if dlg.result:
            self.settings.update(dlg.result)
            store.save_settings(self.settings)
            self.rule_engine.update(direct_domains=dlg.result["direct_domains"],
                                    proxy_domains=dlg.result["proxy_domains"])
            messagebox.showinfo("已保存",
                                f"直连 {len(dlg.result['direct_domains'])} 条 / "
                                f"代理 {len(dlg.result['proxy_domains'])} 条\n"
                                f"当前模式：{self.mode_var.get()}")

    def _on_mode_change(self):
        from config import MODE_LABELS
        label = self.mode_var.get()
        # 反查 mode key
        mode = "smart"
        for k, v in MODE_LABELS.items():
            if v == label:
                mode = k
                break
        self.settings["mode"] = mode
        store.save_settings(self.settings)
        self.rule_engine.update(mode=mode)
        # 运行中则热更新服务器规则引擎
        if self.server:
            self.server.rule_engine = self.rule_engine
        self._update_status_bar()

    # ---------------- 导入 / 导出 ----------------
    def _on_import(self):
        path = filedialog.askopenfilename(
            title="导入代理列表",
            filetypes=[("所有支持的格式", "*.json *.csv *.txt"),
                       ("JSON", "*.json"), ("CSV", "*.csv"), ("TXT", "*.txt")])
        if not path:
            return
        try:
            items = store.import_file(path)
        except Exception as e:
            messagebox.showerror("导入失败", str(e))
            return
        self.proxies, added = store.merge_proxies(self.proxies, items)
        self._force_save()
        self._refresh_table()
        messagebox.showinfo("导入完成", f"导入 {len(items)} 个，新增 {added} 个")

    def _on_import_subscription(self):
        """订阅导入：粘贴订阅地址或分享链接 / Base64 订阅内容。"""
        dlg = SubscriptionDialog(self.root)
        self.root.wait_window(dlg)
        if dlg.result is None:
            return
        urls, text = dlg.result
        self._set_busy(True)
        self.prog_lbl.configure(text="订阅导入中…")

        def work():
            items = []
            try:
                import requests
                for url in urls:
                    try:
                        resp = requests.get(url, timeout=30, verify=False)
                        if resp.status_code < 400:
                            items.extend(parse_subscription(resp.text))
                    except Exception:
                        continue
            except Exception:
                pass
            items.extend(parse_subscription(text))
            try:
                self.root.after(0, lambda it=items: self._subscription_done(it))
            except Exception:
                self._set_busy(False)

        threading.Thread(target=work, daemon=True).start()

    def _subscription_done(self, items):
        if not items:
            self.prog_lbl.configure(text="未解析到代理")
            self._set_busy(False)
            messagebox.showinfo("订阅导入", "未从订阅内容中解析到代理\n请检查订阅地址是否有效")
            return
        self.proxies, added = store.merge_proxies(self.proxies, items)
        self._force_save()
        self._refresh_table()
        self.prog_lbl.configure(text=f"订阅导入 · 新增 {added}")
        self._set_busy(False)
        messagebox.showinfo("订阅导入",
                            f"解析 {len(items)} 个节点，新增 {added} 个\n"
                            f"加密代理需点「验证全部」才会标记可用")

    def _on_export(self):
        # 奥卡姆剃刀：导出 = 导出当前筛选结果（需要什么就筛什么导出）
        rows = self._filter()
        data = [p for _, p in rows]
        if not data:
            data = self.proxies  # 筛选为空时回退到全部
        label = f"导出 {len(data)} 个代理"
        path = filedialog.asksaveasfilename(
            title=label,
            defaultextension=".json",
            filetypes=[("JSON", "*.json"), ("CSV", "*.csv"), ("TXT ip:port", "*.txt")],
            initialfile="proxies.json",
        )
        if not path:
            return
        try:
            ext = os.path.splitext(path)[1].lower()
            if ext == ".json":
                store.export_json(data, path)
            elif ext == ".csv":
                store.export_csv(data, path)
            else:
                store.export_txt(data, path)
            messagebox.showinfo("完成", f"已导出 {len(data)} 个到:\n{path}")
        except Exception as e:
            messagebox.showerror("导出失败", str(e))

    def _on_export_alive(self):
        # 兼容旧调用（如果还有地方用）
        self._on_export()

    def _export(self, proxies, title):
        if not proxies:
            messagebox.showinfo("提示", "没有代理可导出")
            return
        path = filedialog.asksaveasfilename(
            title=title,
            defaultextension=".json",
            filetypes=[("JSON", "*.json"), ("CSV", "*.csv"), ("TXT", "*.txt")])
        if not path:
            return
        try:
            ext = os.path.splitext(path)[1].lower()
            if ext == ".json":
                store.export_json(proxies, path)
            elif ext == ".csv":
                store.export_csv(proxies, path)
            else:
                store.export_txt(proxies, path)
        except Exception as e:
            messagebox.showerror("导出失败", str(e))
            return
        messagebox.showinfo("导出完成", f"已导出 {len(proxies)} 个代理\n{path}")

    # ---------------- 删除 ----------------
    def _on_delete(self):
        idxs = sorted(set(self._selected_indices()), reverse=True)
        if not idxs:
            return
        if not messagebox.askyesno("确认", f"删除选中的 {len(idxs)} 个代理？"):
            return
        for i in idxs:
            if 0 <= i < len(self.proxies):
                self.proxies.pop(i)
        self._force_save()
        self._refresh_table()

    def _on_clear_dead(self):
        before = len(self.proxies)
        self.proxies = [p for p in self.proxies if p.get("alive") or not p.get("last_check")]
        self._force_save()
        self._refresh_table()
        messagebox.showinfo("清理完成", f"删除不可用 {before - len(self.proxies)} 个")

    # ---------------- 右键 / 双击 ----------------
    def _on_right_click(self, event):
        row = self.tree.identify_row(event.y)
        if row:
            if row not in self.tree.selection():
                self.tree.selection_set(row)
            self.menu.tk_popup(event.x_root, event.y_root)

    def _on_double_click(self, event):
        self._set_current_upstream()

    def _set_current_upstream(self):
        idxs = self._selected_indices()
        if not idxs:
            return
        p = self.proxies[idxs[0]]
        if not p.get("alive"):
            if not messagebox.askyesno("提示", "该代理未验证为可用，仍设为当前上游？"):
                return
            p["alive"] = True
        self.rotator._current = p
        self.rotator._idx = 0
        self._update_status_bar()
        messagebox.showinfo("已设置", f"当前上游：{p['ip']}:{p['port']} ({p['protocol']})")

    def _copy_selected(self):
        idxs = self._selected_indices()
        if not idxs:
            return
        p = self.proxies[idxs[0]]
        self.root.clipboard_clear()
        self.root.clipboard_append(f"{p['ip']}:{p['port']}")
        self.prog_lbl.configure(text=f"已复制 {p['ip']}:{p['port']}")

    # ---------------- 状态栏 ----------------
    def _update_status_bar(self):
        host = self.settings["local_host"]
        port = self.settings["local_port"]
        mode_label = self.mode_var.get() if hasattr(self, "mode_var") else "智能分流"
        if self.server:
            self.status.set("local", f"本地代理 {host}:{port} 运行中 · {mode_label}")
            self.status.set_dot(COLOR_GOOD)
        else:
            self.status.set("local", f"本地代理 {host}:{port} 未运行 · {mode_label}")
            self.status.set_dot(COLOR_TEXT_FAINT)
        cur = self.rotator.current()
        if cur:
            lock_tag = " [锁定]" if self.rotator.is_locked() else ""
            self.status.set("upstream",
                            f"当前上游 {cur['ip']}:{cur['port']} ({cur['protocol']}, "
                            f"{cur.get('latency_ms', 0)}ms){lock_tag}")
        else:
            self.status.set("upstream", "当前上游 无")
        self._update_count()

    def _tick(self):
        if self.server:
            st = self.server.stats
            # 实时分流计数：代理 N · 直连 N · 失败 N
            self.status.set("stats",
                            f"请求 {st['requests']} · 代理 {st.get('proxy',0)} · "
                            f"直连 {st.get('direct',0)} · 失败 {st['fail']}")
            cur = self.rotator.current()
            alive = sum(1 for p in self.proxies if p.get("alive"))
            if cur:
                lock_tag = " [锁定]" if self.rotator.is_locked() else ""
                self.status.set("upstream",
                                f"当前上游 {cur['ip']}:{cur['port']} ({cur['protocol']}, "
                                f"{cur.get('latency_ms', 0)}ms){lock_tag}")
            elif alive == 0:
                self.status.set("upstream", "⚠ 无可用上游，请先抓取并验证代理")
            else:
                self.status.set("upstream", f"可用 {alive} 个，等待选择上游…")
        self.root.after(1000, self._tick)

    def on_close(self):
        if self.server:
            self.server.stop()
        if self._auto_save_id:
            self.root.after_cancel(self._auto_save_id)
            self._auto_save_id = None
        store.save_proxies(self.proxies)
        self.root.destroy()

    # ---------------- 自动保存 ----------------
    def _start_auto_save(self):
        """启动周期性自动保存（每 15 秒）。"""
        def tick():
            try:
                now = time.time()
                if now - self._last_save_time >= 15:
                    store.save_proxies(self.proxies)
                    self._last_save_time = now
            except Exception:
                pass
            self._auto_save_id = self.root.after(15000, tick)
        self._auto_save_id = self.root.after(15000, tick)

    def _force_save(self):
        """立即保存并重置计时器。"""
        store.save_proxies(self.proxies)
        self._last_save_time = time.time()
