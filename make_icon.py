# -*- coding: utf-8 -*-
"""用 PIL 精绘 LiteProxy 极简质感图标 → 多尺寸 ICO。

设计理念：
- 纯色渐变背景（深蓝→品牌蓝），圆角方形
- 白色极简中继符号：两段弧线 + 三个圆点，表达"代理转发"
- 无文字、无 emoji，仅靠几何图形传达专业感
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
DST = os.path.join(HERE, "assets", "LiteProxy.ico")

SIZE = 1024
RADIUS = int(SIZE * 0.22)

# ---- 1. 渐变背景 ----
bg = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
px = bg.load()
top = (27, 42, 107)       # #1b2a6b 深蓝
bot = (59, 108, 246)      # #3b6cf6 品牌蓝
for y in range(SIZE):
    t = y / (SIZE - 1)
    r = int(top[0] + (bot[0] - top[0]) * t)
    g = int(top[1] + (bot[1] - top[1]) * t)
    b = int(top[2] + (bot[2] - top[2]) * t)
    for x in range(SIZE):
        px[x, y] = (r, g, b, 255)

# 圆角遮罩
mask = Image.new("L", (SIZE, SIZE), 0)
md = ImageDraw.Draw(mask)
md.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=RADIUS, fill=255)

out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
out.paste(bg, (0, 0), mask)

# ---- 2. 中继符号 ----
draw = ImageDraw.Draw(out)
WHITE = (255, 255, 255, 255)
WHITE_60 = (255, 255, 255, 160)
cx, cy = SIZE // 2, SIZE // 2

# 左侧弧线（代表"入口"流量）
arc_box_l = [cx - 280, cy - 200, cx - 40, cy + 200]
draw.arc(arc_box_l, start=140, end=220, fill=WHITE, width=28)

# 右侧弧线（代表"出口"流量）
arc_box_r = [cx + 40, cy - 200, cx + 280, cy + 200]
draw.arc(arc_box_r, start=-40, end=40, fill=WHITE, width=28)

# 三个连接圆点
dot_r = 36
positions = [
    (cx - 190, cy),   # 左节点
    (cx, cy),          # 中心节点
    (cx + 190, cy),   # 右节点
]
for px_, py_ in positions:
    draw.ellipse([px_ - dot_r, py_ - dot_r, px_ + dot_r, py_ + dot_r],
                 fill=WHITE)

# 中心圆点略大，内嵌蓝色
inner_r = 16
draw.ellipse([cx - inner_r, cy - inner_r, cx + inner_r, cy + inner_r],
             fill=(27, 42, 107, 255))

# ---- 3. 轻微内发光增加质感 ----
glow = out.filter(ImageFilter.GaussianBlur(radius=3))
final = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
final.paste(out, (0, 0))
final.paste(glow, (0, 0), Image.new("L", (SIZE, SIZE), 60))

# 重新应用圆角遮罩（防止发光溢出圆角）
final2 = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
final2.paste(final, (0, 0), mask)

# ---- 4. 保存多尺寸 ICO ----
final2.save(DST, format="ICO", sizes=[(16, 16), (24, 24), (32, 32),
                                       (48, 48), (64, 64), (128, 128), (256, 256)])
print("saved:", DST)

# 同时存一份 PNG 预览
preview = os.path.join(HERE, "assets", "icon_preview.png")
final2.resize((256, 256), Image.LANCZOS).save(preview)
print("preview:", preview)
