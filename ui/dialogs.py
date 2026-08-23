# -*- coding: utf-8 -*-
"""设置对话框 & 源管理对话框。"""
import tkinter as tk
from tkinter import ttk, messagebox

from config import (FONT_FAMILY, FONT_SIZE, CATEGORY_LABELS, PROTOCOL_LABELS,
                    theme, APP_NAME)
from ui.style import RoundedButton, Icon, RelayEntry, DialogTitle
from core import store


def _entry(parent, **kw):
    return RelayEntry(parent, **kw)


class SettingsDialog(tk.Toplevel):
    def __init__(self, master, settings: dict):
        super().__init__(master)
        self.title("设置")
        self.configure(bg=theme.COLOR_BG)
        self.settings = dict(settings)
        self.result = None
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=theme.COLOR_BG, padx=24, pady=20)
        body.pack(fill="both", expand=True)

        # --- 注意：pack 和 grid 不能共享同一个父容器，否则 Tk 布局不渲染
        #     这里 body.pack, 内部分成 title_frame.pack + grid_frame.pack
        #     grid_frame 内部再用 grid()
        DialogTitle(body, "· 设置", "本地代理监听与界面主题").pack(anchor="w", pady=(0, 14))

        # 独立 grid_frame：纯 grid 布局，不和 pack 抢父容器
        grid_frame = tk.Frame(body, bg=theme.COLOR_BG)
        grid_frame.pack(fill="x")

        def row(label_text, r):
            tk.Label(grid_frame, text=label_text, bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                     font=(FONT_FAMILY, FONT_SIZE)).grid(
                row=r, column=0, sticky="w", pady=7, padx=(0, 12))

        row("监听地址", 1)
        self.host_var = tk.StringVar(value=settings.get("local_host", "127.0.0.1"))
        _entry(grid_frame, textvariable=self.host_var, width=24).grid(
            row=1, column=1, sticky="w", pady=7)

        row("监听端口", 2)
        self.port_var = tk.StringVar(value=str(settings.get("local_port", 8888)))
        _entry(grid_frame, textvariable=self.port_var, width=24).grid(
            row=2, column=1, sticky="w", pady=7)

        row("访问 Token", 3)
        self.token_var = tk.StringVar(value=settings.get("auth_token", ""))
        _entry(grid_frame, textvariable=self.token_var, width=30).grid(
            row=3, column=1, sticky="w", pady=7)

        row("最大延迟 (ms)", 4)
        self.max_lat_var = tk.StringVar(value=str(settings.get("max_latency_ms", 3000)))
        _entry(grid_frame, textvariable=self.max_lat_var, width=24).grid(
            row=4, column=1, sticky="w", pady=7)

        row("最小速度 (KB/s)", 5)
        self.min_spd_var = tk.StringVar(value=str(settings.get("min_speed_kbps", 0)))
        _entry(grid_frame, textvariable=self.min_spd_var, width=24).grid(
            row=5, column=1, sticky="w", pady=7)

        self.auto_var = tk.IntVar(value=1 if settings.get("auto_switch", True) else 0)
        tk.Checkbutton(grid_frame, text="[●] 上游失效时自动切换", variable=self.auto_var,
                       bg=theme.COLOR_BG, fg=theme.COLOR_TEXT, font=(FONT_FAMILY, FONT_SIZE),
                       activebackground=theme.COLOR_BG, selectcolor=theme.COLOR_CARD,
                       bd=0, highlightthickness=0).grid(
            row=6, column=0, columnspan=2, sticky="w", pady=7)

        tk.Label(grid_frame, text="界面主题", bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE)).grid(
            row=7, column=0, sticky="w", pady=(12, 7), padx=(0, 12))
        self.theme_var = tk.StringVar(value=settings.get("theme", "dark"))
        self._theme_radios = []  # 保存用于即时刷新
        theme_row = tk.Frame(grid_frame, bg=theme.COLOR_BG)
        theme_row.grid(row=7, column=1, sticky="w", pady=(12, 7))
        for val, lbl in (("dark", "暗色"), ("light", "浅色")):
            rb = tk.Radiobutton(theme_row,
                                text=f"[{('●' if self.theme_var.get() == val else '○')}] {lbl}",
                                variable=self.theme_var, value=val,
                                bg=theme.COLOR_BG, fg=theme.COLOR_TEXT,
                                activebackground=theme.COLOR_BG,
                                selectcolor=theme.COLOR_CARD,
                                font=(FONT_FAMILY, FONT_SIZE), bd=0, highlightthickness=0,
                                command=lambda v=val: self._on_theme_change(v))
            rb.pack(side="left", padx=(0, 14))
            self._theme_radios.append(rb)

        tip = ("保存后重启本地代理监听生效（端口/Token/地址变更是下次启动生效）。\n"
               "HTTPS 站点走 CONNECT 隧道，TLS 端到端加密，上游与本代理均看不到明文。\n"
               "设置 Token 后请求需带 Header：X-Relay-Token: <token>。")
        tk.Label(grid_frame, text=tip, bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE - 1), justify="left").grid(
            row=8, column=0, columnspan=2, sticky="w", pady=(14, 0))

        btns = tk.Frame(grid_frame, bg=theme.COLOR_BG)
        btns.grid(row=9, column=0, columnspan=2, sticky="e", pady=(20, 0))
        RoundedButton(btns, "取消", command=self._cancel, kind="ghost").pack(side="right", padx=(8, 0))
        RoundedButton(btns, "保存", command=self._save).pack(side="right")

        self.geometry("540x480")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx()+80}+{master.winfo_rooty()+80}")

    def _on_theme_change(self, val):
        """点主题立即重绘对话框（让用户看到效果），并同步给主窗口。"""
        theme.set(val)
        self.configure(bg=theme.COLOR_BG)
        # 重绘所有已知控件颜色（最小刷新范围）
        for child in (self.winfo_children() + [self]):
            try:
                child.configure(bg=theme.COLOR_BG)
            except Exception:
                pass
        # 重新计算 radio 文本（[●]/[○] 切换）
        for rb in self._theme_radios:
            cur_val = rb.cget("value")
            lbl = "暗色" if cur_val == "dark" else "浅色"
            rb.configure(bg=theme.COLOR_BG, fg=theme.COLOR_TEXT,
                         activebackground=theme.COLOR_BG,
                         selectcolor=theme.COLOR_CARD,
                         text=f"[{('●' if cur_val == val else '○')}] {lbl}")
        # 让主窗口也即时应用新主题
        try:
            self.master.event_generate("<<RelayThemeChanged>>", when="tail")
        except Exception:
            pass

    def _save(self):
        try:
            port = int(self.port_var.get())
            if not (1 <= port <= 65535):
                raise ValueError()
        except ValueError:
            messagebox.showerror("错误", "端口必须是 1-65535 的整数", parent=self)
            return
        try:
            max_lat = int(self.max_lat_var.get())
            min_spd = float(self.min_spd_var.get())
        except ValueError:
            messagebox.showerror("错误", "延迟/速度需为数字", parent=self)
            return
        self.result = {
            "local_host": self.host_var.get().strip() or "127.0.0.1",
            "local_port": port,
            "auth_token": self.token_var.get().strip(),
            "max_latency_ms": max_lat,
            "min_speed_kbps": min_spd,
            "auto_switch": bool(self.auto_var.get()),
            "theme": self.theme_var.get(),
        }
        self.destroy()

    def _cancel(self):
        self.destroy()


class SourcesDialog(tk.Toplevel):
    """抓取源管理：分类展示、镜像配置、抓取状态回显、增删改。

    标记规范：开关=[●]/[○]，行内操作=[ed]/[rm]。
    """
    def __init__(self, master, sources):
        super().__init__(master)
        self.title("抓取源管理")
        self.configure(bg=theme.COLOR_BG)
        self.result = None
        # 深拷贝并补 enabled 字段（默认启用）+ 状态字段
        self.sources = []
        for s in sources:
            d = dict(s)
            if "enabled" not in d:
                d["enabled"] = True
            d.setdefault("last_status", "N")
            d.setdefault("last_count", 0)
            d.setdefault("last_fetched_at", 0)
            d.setdefault("mirrors", [])
            d.setdefault("category", "CUSTOM")
            self.sources.append(d)
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=theme.COLOR_BG, padx=20, pady=20)
        body.pack(fill="both", expand=True)

        DialogTitle(body, "· 抓取源",
                    "开关=[●]启用 / [○]停用 · 分类 A json/api · B github · C html · D txt/csv · E sub(加密)").pack(
            anchor="w", pady=(0, 12))

        cols = ("idx", "enabled", "name", "cat", "url", "parser", "proto", "status", "count")
        tree_wrap = tk.Frame(body, bg=theme.COLOR_CARD, highlightbackground=theme.COLOR_BORDER,
                             highlightthickness=1)
        tree_wrap.pack(fill="both", expand=True)
        tree = ttk.Treeview(tree_wrap, columns=cols, show="headings", height=14)
        for c, h, w, a in [
            ("idx", "#", 38, "center"),
            ("enabled", "开关", 54, "center"),
            ("name", "名称", 160, "w"),
            ("cat", "类", 58, "center"),
            ("url", "URL", 360, "w"),
            ("parser", "解析器", 88, "w"),
            ("proto", "协议", 68, "w"),
            ("status", "状态", 56, "center"),
            ("count", "数量", 72, "e"),
        ]:
            if c == "enabled":
                tree.heading(c, text=h, command=lambda: self._toggle_all())
            else:
                tree.heading(c, text=h)
            tree.column(c, width=w, anchor=a, stretch=(c == "url"))
        tree.pack(side="left", fill="both", expand=True)
        vsb = ttk.Scrollbar(tree_wrap, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")
        self.tree = tree
        tree.bind("<Button-1>", self._on_tree_click)
        tree.bind("<Double-1>", self._on_double)

        form = tk.Frame(body, bg=theme.COLOR_BG)
        form.pack(fill="x", pady=(16, 0))
        # 第一行：输入控件（名称/URL/解析/协议/分类）
        row1 = tk.Frame(form, bg=theme.COLOR_BG)
        row1.pack(fill="x")
        self.e_name = _entry(row1, width=14)
        self.e_url = _entry(row1, width=52)
        self.cb_parser = ttk.Combobox(row1, width=13, state="readonly",
                                      values=["plain", "html_table", "geonode_json", "subscription"])
        self.cb_parser.set("plain")
        self.cb_proto = ttk.Combobox(row1, width=9, state="readonly",
                                     values=["http", "https", "socks4", "socks5", "auto"])
        self.cb_proto.set("http")
        self.cb_cat = ttk.Combobox(row1, width=8, state="readonly",
                                   values=["A", "B", "C", "D", "E", "CUSTOM"])
        self.cb_cat.set("CUSTOM")
        self.e_name.set_placeholder("名称")
        self.e_url.set_placeholder("https://...")
        tk.Label(row1, text="源  ", bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")
        self.e_name.pack(side="left", padx=(0, 6))
        self.e_url.pack(side="left", padx=(0, 6), fill="x", expand=True)
        self.cb_parser.pack(side="left", padx=(0, 6))
        self.cb_proto.pack(side="left", padx=(0, 6))
        self.cb_cat.pack(side="left", padx=(0, 6))

        # 第二行：操作按钮（+添加 / 编辑选中 / 删除）
        row2 = tk.Frame(form, bg=theme.COLOR_BG)
        row2.pack(fill="x", pady=(10, 0))
        tk.Label(row2, text="操作", bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(side="left")
        RoundedButton(row2, "+ 添加", command=self._add, kind="soft").pack(side="left", padx=6)
        RoundedButton(row2, "编辑选中", command=self._edit_selected, kind="soft").pack(side="left", padx=6)
        RoundedButton(row2, "删除选中", command=self._del, kind="danger").pack(side="left", padx=6)

        # 状态回显行
        status_row = tk.Frame(body, bg=theme.COLOR_BG)
        status_row.pack(fill="x", pady=(14, 0))
        self.status_lbl = tk.Label(status_row, text="状态：尚未抓取（主界面点「抓取代理」后回显每条结果）",
                                   bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_FAINT,
                                   font=(FONT_FAMILY, FONT_SIZE - 1))
        self.status_lbl.pack(side="left")

        self._render()

        btns = tk.Frame(body, bg=theme.COLOR_BG)
        btns.pack(fill="x", pady=(16, 0))
        RoundedButton(btns, "恢复默认 44 内建源", command=self._reset, kind="ghost").pack(side="left")
        RoundedButton(btns, "取消", command=self._cancel, kind="ghost").pack(side="right", padx=(8, 0))
        RoundedButton(btns, "保存", command=self._save).pack(side="right")

        self.geometry("1120x660")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx()+40}+{master.winfo_rooty()+40}")

    # ---- 状态辅助 ----
    def _status_meta(self, s):
        st = s.get("last_status", "N")
        if st == "OK":
            return "●", theme.COLOR_GOOD
        if st == "ERR":
            return "○", theme.COLOR_BAD
        return "-", theme.COLOR_TEXT_FAINT

    def _render(self):
        self.tree.delete(*self.tree.get_children())
        n_ok = sum(1 for s in self.sources if s.get("last_status") == "OK")
        n_err = sum(1 for s in self.sources if s.get("last_status") == "ERR")
        for i, s in enumerate(self.sources):
            chk = "●" if s.get("enabled", True) else "○"
            dot, color = self._status_meta(s)
            proto = s.get("protocol", "http")
            self.tree.insert("", "end", values=(
                i + 1, chk, s.get("name", ""),
                s.get("category", "CUSTOM"),
                s.get("url", ""), s.get("parser", "plain"),
                PROTOCOL_LABELS.get(proto, proto),
                dot, s.get("last_count", 0) if s.get("last_status") != "N" else "",
            ))
        self.status_lbl.configure(
            text=f"状态：OK {n_ok} 源 · ERR {n_err} 源 · 未抓取 {len(self.sources)-n_ok-n_err} 源"
                 f"（点「抓取代理」后回显）")

    def _on_tree_click(self, event):
        region = self.tree.identify("region", event.x, event.y)
        if region != "cell":
            return
        col = self.tree.identify_column(event.x)
        if col != "#2":  # 第二列 enabled 开关
            return
        row = self.tree.identify_row(event.y)
        if not row:
            return
        idx = self.tree.index(row)
        if 0 <= idx < len(self.sources):
            self.sources[idx]["enabled"] = not self.sources[idx].get("enabled", True)
            self._render()

    def _on_double(self, event):
        self._edit_selected()

    def _toggle_all(self):
        all_on = all(s.get("enabled", True) for s in self.sources)
        for s in self.sources:
            s["enabled"] = not all_on
        self._render()

    def _selected_idx(self):
        sel = self.tree.selection()
        if not sel:
            return None
        return self.tree.index(sel[0])

    def _add(self):
        name = self.e_name.get().strip()
        url = self.e_url.get().strip()
        if not url:
            messagebox.showerror("错误", "URL 不能为空", parent=self)
            return
        s = {"name": name or url[:30], "url": url,
             "parser": self.cb_parser.get(), "protocol": self.cb_proto.get(),
             "category": self.cb_cat.get(), "enabled": True,
             "mirrors": [], "last_status": "N", "last_count": 0,
             "last_fetched_at": 0}
        self.sources.append(s)
        self._render()
        self._clear_form()

    def _edit_selected(self):
        idx = self._selected_idx()
        if idx is None or not (0 <= idx < len(self.sources)):
            messagebox.showinfo("提示", "请先选中要编辑的源（双击或选中后点 [ed]）", parent=self)
            return
        s = self.sources[idx]
        EditSourceDialog(self, s, on_save=self._render)

    def _del(self):
        idx = self._selected_idx()
        if idx is None:
            return
        if messagebox.askyesno("确认", "删除该抓取源？", parent=self):
            self.sources.pop(idx)
            self._render()

    def _clear_form(self):
        self.e_name.delete(0, "end"); self.e_name.insert(0, "名称")
        self.e_url.delete(0, "end"); self.e_url.insert(0, "https://...")
        self.cb_parser.set("plain")
        self.cb_proto.set("http")
        self.cb_cat.set("CUSTOM")

    def _reset(self):
        from config import DEFAULT_SOURCES
        self.sources = [dict(s, enabled=True) for s in DEFAULT_SOURCES]
        self._render()

    def _save(self):
        self.result = self.sources
        self.destroy()

    def _cancel(self):
        self.destroy()


class EditSourceDialog(tk.Toplevel):
    """编辑单个源：名称/URL/解析器/协议/分类/镜像/超时。"""
    def __init__(self, master, source: dict, on_save=None):
        super().__init__(master)
        self.title(f"编辑源 · {source.get('name', '')}")
        self.configure(bg=theme.COLOR_BG)
        self.on_save = on_save
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=theme.COLOR_BG, padx=24, pady=22)
        body.pack(fill="both", expand=True)

        def row(label_text, r):
            tk.Label(body, text=label_text, bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                     font=(FONT_FAMILY, FONT_SIZE)).grid(
                row=r, column=0, sticky="w", pady=6, padx=(0, 12))

        row("名称", 0)
        self.e_name = _entry(body, width=44)
        self.e_name.insert(0, source.get("name", ""))
        self.e_name.grid(row=0, column=1, sticky="w", pady=6)

        row("URL", 1)
        self.e_url = _entry(body, width=60)
        self.e_url.insert(0, source.get("url", ""))
        self.e_url.grid(row=1, column=1, sticky="w", pady=6)

        row("解析器", 2)
        self.cb_parser = ttk.Combobox(body, width=14, state="readonly",
                                      values=["plain", "html_table", "geonode_json", "subscription"])
        self.cb_parser.set(source.get("parser", "plain"))
        self.cb_parser.grid(row=2, column=1, sticky="w", pady=6)

        row("协议", 3)
        self.cb_proto = ttk.Combobox(body, width=10, state="readonly",
                                     values=["http", "https", "socks4", "socks5", "auto"])
        self.cb_proto.set(source.get("protocol", "http"))
        self.cb_proto.grid(row=3, column=1, sticky="w", pady=6)

        row("分类", 4)
        self.cb_cat = ttk.Combobox(body, width=8, state="readonly",
                                   values=["A", "B", "C", "D", "E", "CUSTOM"])
        self.cb_cat.set(source.get("category", "CUSTOM"))
        self.cb_cat.grid(row=4, column=1, sticky="w", pady=6)

        row("超时(ms)", 5)
        self.e_timeout = _entry(body, width=10)
        self.e_timeout.insert(0, str(source.get("timeout", 20000)))
        self.e_timeout.grid(row=5, column=1, sticky="w", pady=6)

        row("镜像(每行一个)", 6)
        mir_wrap = tk.Frame(body, bg=theme.COLOR_CARD, highlightbackground=theme.COLOR_BORDER,
                            highlightthickness=1)
        mir_wrap.grid(row=6, column=1, sticky="ew", pady=6)
        self.txt_mirrors = tk.Text(mir_wrap, height=4, bg=theme.COLOR_CARD, fg=theme.COLOR_TEXT,
                                   font=(FONT_FAMILY, FONT_SIZE), relief="flat",
                                   padx=8, pady=6)
        self.txt_mirrors.pack(side="left", fill="both", expand=True)
        self.txt_mirrors.insert("1.0", "\n".join(source.get("mirrors") or []))

        tip = ("GitHub 源可自动生成镜像（保存时若为空会按规则补 gh-proxy/ghfast）。\n"
               "subscription 解析器会把 Base64/分享链接解码为 SS/VMess/VLess/Trojan 节点。")
        tk.Label(body, text=tip, bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE - 1), justify="left").grid(
            row=7, column=0, columnspan=2, sticky="w", pady=(14, 0))

        btns = tk.Frame(body, bg=theme.COLOR_BG)
        btns.grid(row=8, column=0, columnspan=2, sticky="e", pady=(20, 0))
        RoundedButton(btns, "取消", command=self.destroy, kind="ghost").pack(side="right", padx=(8, 0))
        RoundedButton(btns, "保存", command=self._save).pack(side="right")

        self.geometry("600x520")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx()+80}+{master.winfo_rooty()+40}")

    def _save(self):
        mirrors = [m.strip() for m in self.txt_mirrors.get("1.0", "end").splitlines()
                   if m.strip()]
        # GitHub 源自动补镜像
        url = self.e_url.get().strip()
        if not mirrors and url.startswith("https://raw.githubusercontent.com/"):
            from config import _gh_mirrors
            mirrors = _gh_mirrors(url)
        self.source.update({
            "name": self.e_name.get().strip() or url[:30],
            "url": url, "parser": self.cb_parser.get(),
            "protocol": self.cb_proto.get(), "category": self.cb_cat.get(),
            "mirrors": mirrors, "timeout": int(self.e_timeout.get() or 20000),
        })
        if self.on_save:
            self.on_save()
        self.destroy()


class RulesDialog(tk.Toplevel):
    """智能分流规则管理：直连清单 / 代理清单。"""
    def __init__(self, master, direct_domains, proxy_domains):
        super().__init__(master)
        self.title("智能分流规则")
        self.configure(bg=theme.COLOR_BG)
        self.result = None
        self.direct = list(direct_domains or [])
        self.proxy = list(proxy_domains or [])
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=theme.COLOR_BG, padx=20, pady=20)
        body.pack(fill="both", expand=True)

        tk.Label(body, text="智能分流规则", bg=theme.COLOR_BG, fg=theme.COLOR_TEXT,
                 font=(FONT_FAMILY, 13, "bold")).pack(anchor="w", pady=(0, 6))
        tk.Label(body, text="命中规则：域名等于该项，或以 .该项 结尾（如 baidu.com 命中 www.baidu.com）\n"
                            "优先级：强制代理 > 直连 > 默认走代理",
                 bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE - 1), justify="left").pack(anchor="w", pady=(0, 14))

        cols = tk.Frame(body, bg=theme.COLOR_BG)
        cols.pack(fill="both", expand=True)
        left = tk.Frame(cols, bg=theme.COLOR_BG)
        left.pack(side="left", fill="both", expand=True, padx=(0, 10))
        right = tk.Frame(cols, bg=theme.COLOR_BG)
        right.pack(side="left", fill="both", expand=True)

        self._build_list(left, "直连清单（国内直连，不走代理）",
                         self.direct, "direct")
        self._build_list(right, "强制代理清单（海外，必走代理）",
                         self.proxy, "proxy")

        btns = tk.Frame(body, bg=theme.COLOR_BG)
        btns.pack(fill="x", pady=(16, 0))
        RoundedButton(btns, "恢复默认", command=self._reset, kind="ghost").pack(side="left")
        RoundedButton(btns, "取消", command=self._cancel, kind="ghost").pack(side="right", padx=(8, 0))
        RoundedButton(btns, "保存", command=self._save).pack(side="right")

        self.geometry("720x460")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx()+60}+{master.winfo_rooty()+60}")

    def _build_list(self, parent, title, items, key):
        tk.Label(parent, text=title, bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE, "bold")).pack(anchor="w", pady=(0, 6))
        wrap = tk.Frame(parent, bg=theme.COLOR_CARD, highlightbackground=theme.COLOR_BORDER,
                        highlightthickness=1)
        wrap.pack(fill="both", expand=True)
        tree = ttk.Treeview(wrap, columns=("d",), show="headings", height=12)
        tree.heading("d", text="域名")
        tree.column("d", width=240, anchor="w")
        tree.pack(side="left", fill="both", expand=True)
        vsb = ttk.Scrollbar(wrap, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")
        for d in items:
            tree.insert("", "end", values=(d,))
        setattr(self, f"tree_{key}", tree)

        form = tk.Frame(parent, bg=theme.COLOR_BG)
        form.pack(fill="x", pady=(8, 0))
        e = _entry(form, width=22)
        e.insert(0, "example.com")
        e.pack(side="left", padx=(0, 6))
        RoundedButton(form, "+ 添加", command=lambda: self._add(key, e), kind="soft").pack(side="left", padx=(0, 6))
        RoundedButton(form, "− 删除", command=lambda: self._del(key), kind="danger").pack(side="left")

    def _add(self, key, entry):
        val = entry.get().strip().lower().lstrip(".")
        if not val:
            return
        tree = getattr(self, f"tree_{key}")
        lst = getattr(self, key)
        if val not in lst:
            lst.append(val)
            tree.insert("", "end", values=(val,))
        entry.delete(0, "end")
        entry.insert(0, "example.com")

    def _del(self, key):
        tree = getattr(self, f"tree_{key}")
        lst = getattr(self, key)
        for it in tree.selection():
            idx = tree.index(it)
            tree.delete(it)
            if 0 <= idx < len(lst):
                lst.pop(idx)

    def _reset(self):
        from config import DIRECT_DOMAINS_CN, PROXY_DOMAINS
        self.direct = list(DIRECT_DOMAINS_CN)
        self.proxy = list(PROXY_DOMAINS)
        for key in ("direct", "proxy"):
            tree = getattr(self, f"tree_{key}")
            tree.delete(*tree.get_children())
            for d in getattr(self, key):
                tree.insert("", "end", values=(d,))

    def _save(self):
        self.result = {"direct_domains": self.direct,
                       "proxy_domains": self.proxy}
        self.destroy()

    def _cancel(self):
        self.destroy()


class SubscriptionDialog(tk.Toplevel):
    """订阅导入：支持订阅地址抓取，或直接粘贴分享链接 / Base64 订阅内容。"""
    def __init__(self, master):
        super().__init__(master)
        self.title("订阅导入")
        self.configure(bg=theme.COLOR_BG)
        self.result = None
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=theme.COLOR_BG, padx=24, pady=22)
        body.pack(fill="both", expand=True)

        tk.Label(body, text="订阅导入", bg=theme.COLOR_BG, fg=theme.COLOR_TEXT,
                 font=(FONT_FAMILY, 13, "bold")).pack(anchor="w", pady=(0, 4))
        tk.Label(body, text="支持 Shadowsocks / VMess / VLess / Trojan 等加密节点",
                 bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE - 1)).pack(anchor="w", pady=(0, 16))

        tk.Label(body, text="订阅地址（每行一个，将自动从远程抓取）",
                 bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(anchor="w", pady=(0, 6))
        url_wrap = tk.Frame(body, bg=theme.COLOR_CARD, highlightbackground=theme.COLOR_BORDER,
                            highlightthickness=1)
        url_wrap.pack(fill="x")
        self.txt_urls = tk.Text(url_wrap, height=4, bg=theme.COLOR_CARD, fg=theme.COLOR_TEXT,
                                font=(FONT_FAMILY, FONT_SIZE), relief="flat",
                                padx=10, pady=8, wrap="word")
        self.txt_urls.pack(fill="x")

        tk.Label(body, text="或直接粘贴分享链接 / Base64 订阅内容",
                 bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(anchor="w", pady=(14, 6))
        text_wrap = tk.Frame(body, bg=theme.COLOR_CARD, highlightbackground=theme.COLOR_BORDER,
                             highlightthickness=1)
        text_wrap.pack(fill="both", expand=True)
        self.txt_nodes = tk.Text(text_wrap, height=8, bg=theme.COLOR_CARD, fg=theme.COLOR_TEXT,
                                 font=(FONT_FAMILY, FONT_SIZE), relief="flat",
                                 padx=10, pady=8, wrap="word")
        self.txt_nodes.pack(side="left", fill="both", expand=True)
        vsb = ttk.Scrollbar(text_wrap, orient="vertical", command=self.txt_nodes.yview)
        self.txt_nodes.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")

        btns = tk.Frame(body, bg=theme.COLOR_BG)
        btns.pack(fill="x", pady=(18, 0))
        RoundedButton(btns, "取消", command=self._cancel, kind="ghost").pack(side="right", padx=(8, 0))
        RoundedButton(btns, "导入", command=self._save).pack(side="right")

        self.geometry("520x520")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx()+80}+{master.winfo_rooty()+60}")

    def _save(self):
        urls = [u.strip() for u in self.txt_urls.get("1.0", "end").splitlines()
                if u.strip()]
        text = self.txt_nodes.get("1.0", "end").strip()
        if not urls and not text:
            messagebox.showerror("错误", "请输入订阅地址或粘贴订阅内容", parent=self)
            return
        self.result = (urls, text)
        self.destroy()

    def _cancel(self):
        self.destroy()


# ---------- 帮助对话框：用法说明（可滚动，终端风格） ----------
class HelpDialog(tk.Toplevel):
    """本地代理用法 + 意义 + 自检清单 + 分流说明。"""

    SECTIONS = [
        ("一、它是什么",
         "Relay 的「本地代理」是你电脑本地起的一个 HTTP 代理端口，默认 127.0.0.1:8888。\n"
         "它本身不直接提供代理能力，而是充当 浏览器 ↔ 上游代理池 之间的「智能调度中间层」。\n\n"
         "架构：\n"
         "  浏览器/系统（发请求到 127.0.0.1:8888）\n"
         "    ↓ 智能分流 · 自动切节点 · 协议翻译\n"
         "  上游代理池（抓取验证过的免费代理 / 加密订阅节点）\n"
         "    ↓\n"
         "  目标网站"),
        ("二、有什么意义（为什么不能直接把免费代理填浏览器）",
         "1. 自动切换：免费代理 5~10 分钟就挂，你不用手动一个个改，软件在后台自动换下一个。\n"
         "2. 协议统一：浏览器只认 HTTP/SOCKS5，加密协议（SS/VMess/VLESS/Trojan）浏览器根本不会用。\n"
         "   Relay 在内部把它们全翻译成 HTTP 代理接口给浏览器用。\n"
         "3. 智能分流：百度/淘宝走你家宽带（直连更快），Google/YouTube 走代理，本地路由器后台永远直连。\n"
         "4. 浏览器代理只设一次：永远 127.0.0.1:8888，换节点不用动浏览器设置。"),
        ("三、3 步就完事",
         "Step 1  拿到可用代理池：点「⚡ 一键流程」= 抓取 → 验证（推荐新手直接按这个）\n"
         "Step 2  启动本地代理：应用启动已自动监听 127.0.0.1:8888（状态栏绿灯即就绪）\n"
         "Step 3  浏览器设代理 → 二选一：\n"
         "        省心：点「系统代理：关」按钮（自动接管 Windows 系统代理，Chrome/Edge/Firefox 立即生效）\n"
         "        精细：装 SwitchyOmega，新建情景模式 = HTTP / 127.0.0.1 / 8888"),
        ("四、顶栏三种「模式」的区别",
         "[smart]  (默认推荐) 国内直连 + 海外走代理 + 本机/局域网永远直连\n"
         "[global] 全部走代理（本机/局域网依然直连，避免访问不了本地）\n"
         "[direct] 全直连（调试用：验证不用代理能不能连上）"),
        ("五、日常小技巧",
         "· 某个节点特别快：LIST 页右键 → [★] 设为当前上游（锁定，软件不自动切）\n"
         "· 网速变慢：右键 → 解除锁定，让它自动挑延迟最低的\n"
         "· 每天点一次「⚡ 一键流程」刷新：免费代理失效率高\n"
         "· 关闭应用：系统代理会自动恢复（必达恢复，进程崩溃也不会让你断网）\n"
         "· 真的断网了：顶栏右上角「⚠ 恢复」一键复原"),
        ("六、为什么不通？自检清单（对应界面「诊断」按钮）",
         "所有网站都打不开 → ①先看「可用代理数」是不是 0（要先抓+验证）\n"
         "                  → ②确认 8888 端口被 Relay 占着（诊断第 1/5 项）\n"
         "国外上不去国内能上 → 可用代理全挂了；或你锁的节点太慢（解除锁定等切换）\n"
         "国内上不去国外能上 → 顶栏模式改成 [smart]；或 CONF 页把直连域名恢复默认\n"
         "localhost / 192.168.x.x 上不去 → 升级到 v1.7.1+（之前的分流 bug 已修）"),
    ]

    def __init__(self, master):
        super().__init__(master)
        self.title(f"· 帮助 · {APP_NAME}")
        self.configure(bg=theme.COLOR_BG)
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=theme.COLOR_BG, padx=24, pady=20)
        body.pack(fill="both", expand=True)

        DialogTitle(body, "· 帮助 & 用法",
                    "本地代理是怎么回事 · 3 步上手 · 常见问题自检").pack(anchor="w", pady=(0, 10))

        # 内容区：终端风格只读文本，可滚动
        wrap = tk.Frame(body, bg=theme.COLOR_CARD, highlightthickness=1,
                        highlightbackground=theme.COLOR_BORDER)
        wrap.pack(fill="both", expand=True)
        txt = tk.Text(wrap, bg=theme.COLOR_CARD, fg=theme.COLOR_TEXT,
                      font=(FONT_FAMILY, FONT_SIZE), wrap="word",
                      relief="flat", padx=14, pady=12, width=76, height=26,
                      insertbackground=theme.COLOR_CARD,
                      selectbackground=theme.COLOR_PRIMARY_SOFT,
                      selectforeground=theme.COLOR_TEXT, spacing3=6)
        vsb = ttk.Scrollbar(wrap, orient="vertical", command=txt.yview)
        txt.configure(yscrollcommand=vsb.set, state="disabled")
        txt.pack(side="left", fill="both", expand=True)
        vsb.pack(side="right", fill="y")

        self._write(txt)

        btns = tk.Frame(body, bg=theme.COLOR_BG)
        btns.pack(fill="x", pady=(14, 0))
        RoundedButton(btns, "关闭", command=self.destroy).pack(side="right")
        tip = ("快捷键：点 HOME 页「诊断」按钮可逐项排查连通性问题。"
               "  模式 / 分流清单：顶栏右边「模式」+ [CONF] 页")
        tk.Label(btns, text=tip, bg=theme.COLOR_BG, fg=theme.COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE - 1)).pack(side="left")

        self.geometry("640x600")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx() + 60}+{master.winfo_rooty() + 40}")

    def _write(self, txt):
        """终端风格渲染：标题主色粗体，段落中性灰，[xx] 关键词主色。"""
        txt.configure(state="normal")
        txt.tag_config("h", foreground=theme.COLOR_PRIMARY,
                       font=(FONT_FAMILY, FONT_SIZE + 1, "bold"))
        txt.tag_config("p", foreground=theme.COLOR_TEXT_MUTED,
                       font=(FONT_FAMILY, FONT_SIZE))
        txt.tag_config("k", foreground=theme.COLOR_PRIMARY)
        txt.tag_config("d", foreground=theme.COLOR_TEXT_FAINT)

        for i, (title, paragraph) in enumerate(self.SECTIONS):
            if i > 0:
                txt.insert("end", "\n")
            txt.insert("end", f"  {i + 1:02d}  ", "d")
            txt.insert("end", title, "h")
            txt.insert("end", "\n")
            self._insert_paragraph(txt, paragraph)
            txt.insert("end", "\n")
        txt.insert("end", "─" * 72 + "\n", "d")
        txt.insert("end", f"{APP_NAME} · 本地代理永远优先让 localhost / 192.168 / 10.x 直连", "p")
        txt.configure(state="disabled")

    def _insert_paragraph(self, txt, text):
        """逐段插入，[xxx] 识别为关键词用主色显示。"""
        import re
        pos = 0
        for m in re.finditer(r"\[([^\[\]]{1,12})\]", text):
            if m.start() > pos:
                txt.insert("end", text[pos:m.start()], "p")
            txt.insert("end", m.group(0), "k")
            pos = m.end()
        if pos < len(text):
            txt.insert("end", text[pos:], "p")
