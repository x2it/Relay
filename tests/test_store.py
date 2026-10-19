# -*- coding: utf-8 -*-
"""持久化读写测试：代理列表 / 源 / 设置的保存加载、合并去重、导入导出。

通过 monkeypatch 把数据文件重定向到 tmp_path，不污染真实 data/ 目录。
"""
import pytest

from core import store


@pytest.fixture
def db_paths(tmp_path, monkeypatch):
    proxies = tmp_path / "proxies.json"
    sources = tmp_path / "sources.json"
    settings = tmp_path / "settings.json"
    monkeypatch.setattr(store, "PROXY_DB", str(proxies))
    monkeypatch.setattr(store, "SOURCES_FILE", str(sources))
    monkeypatch.setattr(store, "SETTINGS_FILE", str(settings))
    return {"proxies": str(proxies), "sources": str(sources),
            "settings": str(settings)}


def _p(ip, port, proto="http", config=None, **fields):
    p = store.new_proxy(ip, port, proto, config=config)
    for k, v in fields.items():
        p[k] = v
    return p


def test_save_load_proxies_roundtrip(db_paths):
    items = [_p("1.1.1.1", 8080), _p("2.2.2.2", 1080, "socks5")]
    store.save_proxies(items)
    loaded = store.load_proxies()
    assert len(loaded) == 2
    assert loaded[0]["ip"] == "1.1.1.1" and loaded[1]["protocol"] == "socks5"


def test_load_proxies_missing_file_returns_empty(db_paths):
    assert store.load_proxies() == []


def test_merge_proxies_dedup_plain():
    existing = [_p("1.1.1.1", 8080)]
    merged, added = store.merge_proxies(existing, [_p("1.1.1.1", 8080),
                                                    _p("3.3.3.3", 3128)])
    assert added == 1
    assert len(merged) == 2


def test_merge_proxies_encrypted_keyed_by_config():
    existing = [_p("1.1.1.1", 8388, "ss",
                   config={"method": "aes-256-gcm", "password": "a"})]
    # 同主机同协议但密钥不同，不应被合并
    new = [_p("1.1.1.1", 8388, "ss",
              config={"method": "aes-256-gcm", "password": "b"})]
    merged, added = store.merge_proxies(existing, new)
    assert added == 1 and len(merged) == 2


def test_update_proxy_status():
    p = _p("1.1.1.1", 8080)
    store.update_proxy_status(p, True, 123, 45.678)
    assert p["alive"] is True
    assert p["latency_ms"] == 123
    assert p["speed_kbps"] == 45.7
    assert p["last_check"]


def test_save_load_settings(db_paths):
    defaults = store.load_settings()
    assert defaults["local_port"] == 8888
    store.save_settings({**defaults, "local_port": 9000, "auth_token": "x"})
    loaded = store.load_settings()
    assert loaded["local_port"] == 9000
    assert loaded["auth_token"] == "x"
    # 缺省字段仍在
    assert "direct_domains" in loaded and "proxy_domains" in loaded


def test_export_json_and_reimport(db_paths, tmp_path):
    items = [_p("1.1.1.1", 8080, alive=True, latency_ms=50)]
    out = tmp_path / "out.json"
    store.export_json(items, str(out))
    reimported = store.import_file(str(out))
    assert len(reimported) == 1
    assert reimported[0]["ip"] == "1.1.1.1"
    assert reimported[0]["alive"] is True


def test_export_csv(db_paths, tmp_path):
    out = tmp_path / "out.csv"
    store.export_csv([_p("1.1.1.1", 8080)], str(out))
    text = out.read_text(encoding="utf-8-sig")
    assert "1.1.1.1" in text and "http" in text


def test_export_txt_includes_socks4(db_paths, tmp_path):
    out = tmp_path / "out.txt"
    items = [_p("1.1.1.1", 8080, "http"), _p("2.2.2.2", 1080, "socks4")]
    store.export_txt(items, str(out))
    text = out.read_text(encoding="utf-8")
    assert "# socks4" in text and "2.2.2.2:1080" in text


def test_import_txt_socks4_tag(db_paths, tmp_path):
    f = tmp_path / "in.txt"
    f.write_text("# socks4\n1.2.3.4:1080\n", encoding="utf-8")
    items = store.import_file(str(f))
    assert len(items) == 1
    assert items[0]["protocol"] == "socks4"
    assert items[0]["ip"] == "1.2.3.4"


def test_load_sources_missing_falls_back_defaults(db_paths):
    import config
    sources = store.load_sources()
    assert len(sources) == len(config.DEFAULT_SOURCES)
