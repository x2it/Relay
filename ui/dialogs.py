# -*- coding: utf-8 -*-
"""设置对话框 & 源管理对话框。"""
import tkinter as tk
from tkinter import ttk, messagebox

from config import (COLOR_BG, COLOR_CARD, COLOR_BORDER, COLOR_TEXT,
                    COLOR_TEXT_MUTED, COLOR_TEXT_FAINT, COLOR_PRIMARY,
                    COLOR_PRIMARY_SOFT, COLOR_GOOD, COLOR_BAD, COLOR_WARN,
                    FONT_FAMILY, FONT_SIZE, CATEGORY_LABELS, PROTOCOL_LABELS)
from ui.style import RoundedButton, Icon
from core import store


def _entry(parent, **kw):
    e = tk.Entry(parent, relief="solid", bd=1, highlightthickness=0,
                 font=(FONT_FAMILY, FONT_SIZE), **kw)
    return e


class SettingsDialog(tk.Toplevel):
    def __init__(self, master, settings: dict):
        super().__init__(master)
        self.title("设置")
        self.configure(bg=COLOR_BG)
        self.settings = dict(settings)
        self.result = None
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=COLOR_BG, padx=24, pady=22)
        body.pack(fill="both", expand=True)

        tk.Label(body, text="本地代理", bg=COLOR_BG, fg=COLOR_TEXT,
                 font=(FONT_FAMILY, 12, "bold")).grid(row=0, column=0,
                 columnspan=2, sticky="w", pady=(0, 10))

        def row(label_text, r):
            tk.Label(body, text=label_text, bg=COLOR_BG, fg=COLOR_TEXT_MUTED,
                     font=(FONT_FAMILY, FONT_SIZE)).grid(
                row=r, column=0, sticky="w", pady=7, padx=(0, 12))

        row("监听地址", 1)
        self.host_var = tk.StringVar(value=settings.get("local_host", "127.0.0.1"))
        _entry(body, textvariable=self.host_var, width=24).grid(
            row=1, column=1, sticky="w", pady=7)

        row("监听端口", 2)
        self.port_var = tk.StringVar(value=str(settings.get("local_port", 8888)))
        _entry(body, textvariable=self.port_var, width=24).grid(
            row=2, column=1, sticky="w", pady=7)

        row("访问 Token", 3)
        self.token_var = tk.StringVar(value=settings.get("auth_token", ""))
        _entry(body, textvariable=self.token_var, width=30).grid(
            row=3, column=1, sticky="w", pady=7)

        row("最大延迟 (ms)", 4)
        self.max_lat_var = tk.StringVar(value=str(settings.get("max_latency_ms", 3000)))
        _entry(body, textvariable=self.max_lat_var, width=24).grid(
            row=4, column=1, sticky="w", pady=7)

        row("最小速度 (KB/s)", 5)
        self.min_spd_var = tk.StringVar(value=str(settings.get("min_speed_kbps", 0)))
        _entry(body, textvariable=self.min_spd_var, width=24).grid(
            row=5, column=1, sticky="w", pady=7)

        self.auto_var = tk.IntVar(value=1 if settings.get("auto_switch", True) else 0)
        tk.Checkbutton(body, text="上游失效时自动切换", variable=self.auto_var,
                       bg=COLOR_BG, fg=COLOR_TEXT, font=(FONT_FAMILY, FONT_SIZE),
                       activebackground=COLOR_BG, selectcolor=COLOR_CARD,
                       bd=0, highlightthickness=0).grid(
            row=6, column=0, columnspan=2, sticky="w", pady=7)

        tip = ("HTTPS 站点走 CONNECT 隧道，由目标站点 TLS 端到端加密，\n"
               "上游与本地代理均看不到明文。设 Token 后本端口需带\n"
               "Header：X-Relay-Token: <token>")
        tk.Label(body, text=tip, bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE - 1), justify="left").grid(
            row=7, column=0, columnspan=2, sticky="w", pady=(14, 0))

        btns = tk.Frame(body, bg=COLOR_BG)
        btns.grid(row=8, column=0, columnspan=2, sticky="e", pady=(20, 0))
        RoundedButton(btns, "取消", command=self._cancel, kind="ghost").pack(side="right", padx=(8, 0))
        RoundedButton(btns, "保存", command=self._save).pack(side="right")

        self.geometry("480x380")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx()+80}+{master.winfo_rooty()+80}")

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
        self.configure(bg=COLOR_BG)
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

        body = tk.Frame(self, bg=COLOR_BG, padx=20, pady=20)
        body.pack(fill="both", expand=True)

        tk.Label(body, text="· sources", bg=COLOR_BG, fg=COLOR_PRIMARY,
                 font=(FONT_FAMILY, 12, "bold")).pack(anchor="w", pady=(0, 4))
        tk.Label(body, text="开关=[●]启用 / [○]停用 · 分类 A json/api · B github raw · C html · D txt/csv · E sub(加密订阅)",
                 bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE - 1)).pack(anchor="w", pady=(0, 8))

        cols = ("idx", "enabled", "name", "cat", "url", "parser", "proto", "status", "count")
        tree_wrap = tk.Frame(body, bg=COLOR_CARD, highlightbackground=COLOR_BORDER,
                             highlightthickness=1)
        tree_wrap.pack(fill="both", expand=True)
        tree = ttk.Treeview(tree_wrap, columns=cols, show="headings", height=12)
        for c, h, w, a in [
            ("idx", "#", 34, "center"),
            ("enabled", "开关", 46, "center"),
            ("name", "名称", 150, "w"),
            ("cat", "类", 56, "center"),
            ("url", "URL", 300, "w"),
            ("parser", "解析器", 76, "w"),
            ("proto", "协议", 56, "w"),
            ("status", "状态", 48, "center"),
            ("count", "数量", 60, "e"),
        ]:
            tree.heading(c, text=h, command=(lambda: self._toggle_all()) if c == "enabled" else None)
            tree.column(c, width=w, anchor=a)
        tree.pack(side="left", fill="both", expand=True)
        vsb = ttk.Scrollbar(tree_wrap, orient="vertical", command=tree.yview)
        tree.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")
        self.tree = tree
        tree.bind("<Button-1>", self._on_tree_click)
        tree.bind("<Double-1>", self._on_double)
        self._render()

        form = tk.Frame(body, bg=COLOR_BG)
        form.pack(fill="x", pady=(14, 0))
        self.e_name = _entry(form, width=12)
        self.e_url = _entry(form, width=40)
        self.cb_parser = ttk.Combobox(form, width=11, state="readonly",
                                      values=["plain", "html_table", "geonode_json", "subscription"])
        self.cb_parser.set("plain")
        self.cb_proto = ttk.Combobox(form, width=8, state="readonly",
                                     values=["http", "https", "socks4", "socks5", "auto"])
        self.cb_proto.set("http")
        self.cb_cat = ttk.Combobox(form, width=6, state="readonly",
                                   values=["A", "B", "C", "D", "E", "CUSTOM"])
        self.cb_cat.set("CUSTOM")
        self.e_name.insert(0, "名称")
        self.e_url.insert(0, "https://...")
        tk.Label(form, text="新增/编辑", bg=COLOR_BG, fg=COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE)).grid(row=0, column=0, sticky="w")
        self.e_name.grid(row=0, column=1, padx=4)
        self.e_url.grid(row=0, column=2, padx=4)
        self.cb_parser.grid(row=0, column=3, padx=4)
        self.cb_proto.grid(row=0, column=4, padx=4)
        self.cb_cat.grid(row=0, column=5, padx=4)
        RoundedButton(form, "+ 添加", command=self._add, kind="soft").grid(
            row=0, column=6, padx=4)
        RoundedButton(form, "[ed] 编辑", command=self._edit_selected, kind="soft").grid(
            row=0, column=7, padx=4)
        RoundedButton(form, "[rm] 删除", command=self._del, kind="danger").grid(
            row=0, column=8, padx=4)

        # 状态回显行
        status_row = tk.Frame(body, bg=COLOR_BG)
        status_row.pack(fill="x", pady=(10, 0))
        self.status_lbl = tk.Label(status_row, text="状态：尚未抓取（点「抓取代理」后回显）",
                                   bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                                   font=(FONT_FAMILY, FONT_SIZE - 1))
        self.status_lbl.pack(side="left")

        btns = tk.Frame(body, bg=COLOR_BG)
        btns.pack(fill="x", pady=(12, 0))
        RoundedButton(btns, "恢复默认", command=self._reset, kind="ghost").pack(side="left")
        RoundedButton(btns, "取消", command=self._cancel, kind="ghost").pack(side="right", padx=(8, 0))
        RoundedButton(btns, "保存", command=self._save).pack(side="right")

        self.geometry("940x560")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx()+40}+{master.winfo_rooty()+40}")

    # ---- 状态辅助 ----
    def _status_meta(self, s):
        st = s.get("last_status", "N")
        if st == "OK":
            return "●", COLOR_GOOD
        if st == "ERR":
            return "○", COLOR_BAD
        return "-", COLOR_TEXT_FAINT

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
        self.configure(bg=COLOR_BG)
        self.on_save = on_save
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=COLOR_BG, padx=24, pady=22)
        body.pack(fill="both", expand=True)

        def row(label_text, r):
            tk.Label(body, text=label_text, bg=COLOR_BG, fg=COLOR_TEXT_MUTED,
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
        mir_wrap = tk.Frame(body, bg=COLOR_CARD, highlightbackground=COLOR_BORDER,
                            highlightthickness=1)
        mir_wrap.grid(row=6, column=1, sticky="ew", pady=6)
        self.txt_mirrors = tk.Text(mir_wrap, height=4, bg=COLOR_CARD, fg=COLOR_TEXT,
                                   font=(FONT_FAMILY, FONT_SIZE), relief="flat",
                                   padx=8, pady=6)
        self.txt_mirrors.pack(side="left", fill="both", expand=True)
        self.txt_mirrors.insert("1.0", "\n".join(source.get("mirrors") or []))

        tip = ("GitHub 源可自动生成镜像（保存时若为空会按规则补 gh-proxy/ghfast）。\n"
               "subscription 解析器会把 Base64/分享链接解码为 SS/VMess/VLess/Trojan 节点。")
        tk.Label(body, text=tip, bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE - 1), justify="left").grid(
            row=7, column=0, columnspan=2, sticky="w", pady=(14, 0))

        btns = tk.Frame(body, bg=COLOR_BG)
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
        self.configure(bg=COLOR_BG)
        self.result = None
        self.direct = list(direct_domains or [])
        self.proxy = list(proxy_domains or [])
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=COLOR_BG, padx=20, pady=20)
        body.pack(fill="both", expand=True)

        tk.Label(body, text="智能分流规则", bg=COLOR_BG, fg=COLOR_TEXT,
                 font=(FONT_FAMILY, 13, "bold")).pack(anchor="w", pady=(0, 6))
        tk.Label(body, text="命中规则：域名等于该项，或以 .该项 结尾（如 baidu.com 命中 www.baidu.com）\n"
                            "优先级：强制代理 > 直连 > 默认走代理",
                 bg=COLOR_BG, fg=COLOR_TEXT_FAINT,
                 font=(FONT_FAMILY, FONT_SIZE - 1), justify="left").pack(anchor="w", pady=(0, 14))

        cols = tk.Frame(body, bg=COLOR_BG)
        cols.pack(fill="both", expand=True)
        left = tk.Frame(cols, bg=COLOR_BG)
        left.pack(side="left", fill="both", expand=True, padx=(0, 10))
        right = tk.Frame(cols, bg=COLOR_BG)
        right.pack(side="left", fill="both", expand=True)

        self._build_list(left, "直连清单（国内直连，不走代理）",
                         self.direct, "direct")
        self._build_list(right, "强制代理清单（海外，必走代理）",
                         self.proxy, "proxy")

        btns = tk.Frame(body, bg=COLOR_BG)
        btns.pack(fill="x", pady=(16, 0))
        RoundedButton(btns, "恢复默认", command=self._reset, kind="ghost").pack(side="left")
        RoundedButton(btns, "取消", command=self._cancel, kind="ghost").pack(side="right", padx=(8, 0))
        RoundedButton(btns, "保存", command=self._save).pack(side="right")

        self.geometry("720x460")
        self.update_idletasks()
        self.geometry(f"+{master.winfo_rootx()+60}+{master.winfo_rooty()+60}")

    def _build_list(self, parent, title, items, key):
        tk.Label(parent, text=title, bg=COLOR_BG, fg=COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE, "bold")).pack(anchor="w", pady=(0, 6))
        wrap = tk.Frame(parent, bg=COLOR_CARD, highlightbackground=COLOR_BORDER,
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

        form = tk.Frame(parent, bg=COLOR_BG)
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
        self.configure(bg=COLOR_BG)
        self.result = None
        self.resizable(False, False)
        self.transient(master)
        self.grab_set()

        body = tk.Frame(self, bg=COLOR_BG, padx=24, pady=22)
        body.pack(fill="both", expand=True)

        tk.Label(body, text="订阅导入", bg=COLOR_BG, fg=COLOR_TEXT,
                 font=(FONT_FAMILY, 13, "bold")).pack(anchor="w", pady=(0, 4))
        tk.Label(body, text="支持 Shadowsocks / VMess / VLess / Trojan 等加密节点",
                 bg=COLOR_BG, fg=COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE - 1)).pack(anchor="w", pady=(0, 16))

        tk.Label(body, text="订阅地址（每行一个，将自动从远程抓取）",
                 bg=COLOR_BG, fg=COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(anchor="w", pady=(0, 6))
        url_wrap = tk.Frame(body, bg=COLOR_CARD, highlightbackground=COLOR_BORDER,
                            highlightthickness=1)
        url_wrap.pack(fill="x")
        self.txt_urls = tk.Text(url_wrap, height=4, bg=COLOR_CARD, fg=COLOR_TEXT,
                                font=(FONT_FAMILY, FONT_SIZE), relief="flat",
                                padx=10, pady=8, wrap="word")
        self.txt_urls.pack(fill="x")

        tk.Label(body, text="或直接粘贴分享链接 / Base64 订阅内容",
                 bg=COLOR_BG, fg=COLOR_TEXT_MUTED,
                 font=(FONT_FAMILY, FONT_SIZE)).pack(anchor="w", pady=(14, 6))
        text_wrap = tk.Frame(body, bg=COLOR_CARD, highlightbackground=COLOR_BORDER,
                             highlightthickness=1)
        text_wrap.pack(fill="both", expand=True)
        self.txt_nodes = tk.Text(text_wrap, height=8, bg=COLOR_CARD, fg=COLOR_TEXT,
                                 font=(FONT_FAMILY, FONT_SIZE), relief="flat",
                                 padx=10, pady=8, wrap="word")
        self.txt_nodes.pack(side="left", fill="both", expand=True)
        vsb = ttk.Scrollbar(text_wrap, orient="vertical", command=self.txt_nodes.yview)
        self.txt_nodes.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")

        btns = tk.Frame(body, bg=COLOR_BG)
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
