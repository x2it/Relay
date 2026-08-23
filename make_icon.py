# -*- coding: utf-8 -*-
"""用 PIL 复刻 Android 版 Relay 启动图标 → 多尺寸 ICO。

设计（对齐 android/.../drawable/ic_launcher_foreground.xml「方案B 同心聚焦」）：
- 深色圆角方块背景 #0F1216（terminal 页面底）
- 细环：对角线性渐变描边 A7B8FF → 3A56C8（左上亮 → 右下深）
- 核心光点：径向渐变 E6ECFF → 4B6BE8（中心亮 → 边缘深）
- 无文字、无 emoji，纯几何：深底 + 同心聚焦环，视觉干净克制
"""
import math
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
DST = os.path.join(HERE, "assets", "Relay.ico")

SIZE = 1024
SCALE = SIZE / 108.0   # 安卓 viewport 108 → 1024

# 安卓配色（ic_launcher_foreground.xml）
RING_A = (0xA7, 0xB8, 0xFF)   # A7B8FF 环亮端
RING_B = (0x3A, 0x56, 0xC8)   # 3A56C8 环深端
CORE_A = (0xE6, 0xEC, 0xFF)   # E6ECFF 光点亮心
CORE_B = (0x4B, 0x6B, 0xE8)   # 4B6BE8 光点边缘
BG = (0x0F, 0x12, 0x16)       # 0F1216 深底

# 几何（108 坐标系 → 画布）
CX = CY = SIZE // 2
RING_R = 17 * SCALE            # 环半径
RING_W = int(3.2 * SCALE) + 4  # 环描边（+4 保证小尺寸可见）
CORE_R = 6 * SCALE             # 光点半径
CORNER = int(6 / 48.0 * SIZE)  # 背景圆角（旧版满幅 48 坐标系）

# 渐变端点（108 坐标 start 37,37 → end 71,71，即左上→右下）
sx = sy = 37 * SCALE
ex = ey = 71 * SCALE


def _lerp(c1, c2, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)) + (255,)


img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# ---- 1. 深色圆角方块背景 ----
d.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=CORNER, fill=BG)

# ---- 2. 细环：分段 arc 模拟对角线性渐变 ----
SEG = 120
for i in range(SEG):
    a0 = i * 360 / SEG
    a1 = (i + 1) * 360 / SEG
    mid = math.radians((a0 + a1) / 2)
    px = CX + RING_R * math.cos(mid)
    py = CY + RING_R * math.sin(mid)
    t = ((px + py) - (sx + sy)) / ((ex + ey) - (sx + sy))
    col = _lerp(RING_A, RING_B, t)
    d.arc([CX - RING_R, CY - RING_R, CX + RING_R, CY + RING_R],
          start=a0, end=a1, fill=col, width=RING_W)

# ---- 3. 核心光点：多层同心圆模拟径向渐变（边缘深 → 中心亮） ----
LAYERS = 72
for i in range(LAYERS):
    frac = i / (LAYERS - 1)            # 0=最外
    r = CORE_R * (1 - frac * 0.94)     # 逐层缩小
    col = _lerp(CORE_B, CORE_A, frac)  # 外深内亮
    d.ellipse([CX - r, CY - r, CX + r, CY + r], fill=col)

# ---- 4. 保存多尺寸 ICO ----
img.save(DST, format="ICO", sizes=[(16, 16), (24, 24), (32, 32),
                                   (48, 48), (64, 64), (128, 128), (256, 256)])
print("saved:", DST)

preview = os.path.join(HERE, "assets", "icon_preview.png")
img.resize((256, 256), Image.LANCZOS).save(preview)
print("preview:", preview)
