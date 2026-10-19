# -*- coding: utf-8 -*-
"""pytest 公共夹具：把仓库根目录加入 sys.path，保证 `import config` / `import core.*` 可用。

测试全部为纯逻辑 / mock 网络，不依赖 Windows 专属模块（winreg / PowerShell）。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)
