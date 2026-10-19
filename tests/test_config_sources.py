# -*- coding: utf-8 -*-
"""内置抓取源结构校验：44 源存在性、字段完整性、分类/解析器合法、无重名。"""
import config

REQUIRED_FIELDS = ("name", "url", "parser", "protocol", "category",
                   "mirrors", "timeout", "enabled")
VALID_PARSERS = {"plain", "html_table", "geonode_json", "subscription"}
VALID_CATEGORIES = {"A", "B", "C", "D", "E"}


def test_builtin_sources_count():
    assert len(config.DEFAULT_SOURCES) == 44


def test_every_source_has_required_fields():
    for s in config.DEFAULT_SOURCES:
        for f in REQUIRED_FIELDS:
            assert f in s, f"源 {s.get('name')} 缺字段 {f}"


def test_source_values_valid():
    names = []
    for s in config.DEFAULT_SOURCES:
        assert s["url"].startswith(("http://", "https://")), s["name"]
        assert s["parser"] in VALID_PARSERS, s["name"]
        assert s["category"] in VALID_CATEGORIES, s["name"]
        assert isinstance(s["mirrors"], list), s["name"]
        assert isinstance(s["enabled"], bool), s["name"]
        assert isinstance(s["timeout"], int) and s["timeout"] > 0
        names.append(s["name"])
    # 无重名
    assert len(names) == len(set(names))


def test_github_raw_sources_have_mirrors():
    for s in config.DEFAULT_SOURCES:
        if s["url"].startswith("https://raw.githubusercontent.com/"):
            assert len(s["mirrors"]) == 2, s["name"]


def test_socks4_sources_present():
    socks4 = [s for s in config.DEFAULT_SOURCES if s["protocol"] == "socks4"]
    assert len(socks4) >= 5
