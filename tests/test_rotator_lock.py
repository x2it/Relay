# -*- coding: utf-8 -*-
"""ProxyRotator 锁定行为测试：lock/is_locked/unlock + 锁定时不被轮换。"""
import threading

from core.local_proxy import ProxyRotator


def _make_rotator():
    """固定一份候选池（闭包持有同一批 dict 对象，便于身份断言）。"""
    pool = [
        {"ip": "1.1.1.1", "port": 8080, "protocol": "http",
         "alive": True, "latency_ms": 100},
        {"ip": "2.2.2.2", "port": 8080, "protocol": "http",
         "alive": True, "latency_ms": 200},
    ]
    return ProxyRotator(lambda: pool), pool


def test_not_locked_initially():
    r, _ = _make_rotator()
    assert r.is_locked() is False
    assert r.locked_proxy() is None


def test_lock_marks_and_returns_proxy():
    r, pool = _make_rotator()
    p = pool[0]
    r.lock(p)
    assert r.is_locked() is True
    assert r.locked_proxy() is p
    assert r.pick() is p
    assert r.current() is p


def test_locked_proxy_not_rotated():
    r, pool = _make_rotator()
    p = pool[0]
    r.lock(p)
    # rotate / report_fail / pick_https 都不能把锁定代理换掉
    assert r.rotate() is p
    r.report_fail(p)
    assert r.pick() is p
    assert r.pick_https() is p


def test_unlock_restores_auto_switch():
    r, pool = _make_rotator()
    p = pool[0]
    r.lock(p)
    r.unlock()
    assert r.is_locked() is False
    assert r.locked_proxy() is None
    # 解锁后 rotate 能切到第二个候选（恢复自动轮换）
    assert r.rotate() is pool[1]


def test_lock_none_alias_for_unlock():
    r, pool = _make_rotator()
    r.lock(pool[0])
    r.lock(None)
    assert r.is_locked() is False


def test_lock_thread_smoke():
    r, pool = _make_rotator()
    p = pool[0]
    errors = []

    def worker():
        try:
            for _ in range(200):
                r.lock(p)
                r.is_locked()
                r.unlock()
        except Exception as e:  # pragma: no cover - 线程安全冒烟
            errors.append(e)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert errors == []
