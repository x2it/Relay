# -*- coding: utf-8 -*-
"""用 PIL 精绘 Relay terminal 主题图标 → 多尺寸 ICO。

设计理念（对齐 app 暗色 terminal 主题）：
- 深灰黑圆角方块背景（#0F1216），细描边 #2C323B 增加质感
- 蓝紫主色（#6B8AFF）中继符号：三圆点 + 连线，表达"代理转发"
- 右下角 terminal 光标块 ▍点缀，呼应 [HOME][LIST][CONF] 界面语言
- 无文字、无 emoji，仅靠几何图形传达优雅极简感
"""
import os
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
DST = os.path.join(HERE, "assets", "Relay.ico")

SIZE = 1024
RADIUS = int(SIZE * 0.22)

# terminal 主题配色（对齐 config.py）
BG_TOP = (15, 18, 22)        # #0F1216 页面底
BG_BOT = (23, 27, 33)        # #171B21 卡片底
BORDER = (44, 50, 59)        # #2C323B 描边
PRIMARY = (107, 138, 255)    # #6B8AFF 主色蓝紫
PRIMARY_DK = (38, 50, 88)    # #263258 主色淡底

# ---- 1. 深色渐变背景 ----
bg = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
px = bg.load()
for y in range(SIZE):
    t = y / (SIZE - 1)
    r = int(BG_TOP[0] + (BG_BOT[0] - BG_TOP[0]) * t)
    g = int(BG_TOP[1] + (BG_BOT[1] - BG_TOP[1]) * t)
    b = int(BG_TOP[2] + (BG_BOT[2] - BG_TOP[2]) * t)
    for x in range(SIZE):
        px[x, y] = (r, g, b, 255)

# 圆角遮罩
mask = Image.new("L", (SIZE, SIZE), 0)
md = ImageDraw.Draw(mask)
md.rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=RADIUS, fill=255)

out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
out.paste(bg, (0, 0), mask)

draw = ImageDraw.Draw(out)
cx, cy = SIZE // 2, SIZE // 2

# 细描边（terminal 边框感）
draw.rounded_rectangle([6, 6, SIZE - 7, SIZE - 7], radius=RADIUS - 6,
                       outline=BORDER, width=10)

# ---- 2. 中继符号：左点 → 中点 → 右点 连线 ----
# 连线（主线）
line_w = 30
y_off = -60  # 整体上移，给下方 terminal 点缀留空间
pts = [(cx - 230, cy + y_off), (cx, cy + y_off - 110), (cx + 230, cy + y_off)]
for i in range(len(pts) - 1):
    draw.line([pts[i], pts[i + 1]], fill=PRIMARY, width=line_w)

# 三圆点（实心主色）
dot_r = 52
for p in pts:
    draw.ellipse([p[0] - dot_r, p[1] - dot_r, p[0] + dot_r, p[1] + dot_r],
                 fill=PRIMARY)

# 中心点内嵌深色，空心质感
inner_r = 22
draw.ellipse([pts[1][0] - inner_r, pts[1][1] - inner_r,
              pts[1][0] + inner_r, pts[1][1] + inner_r], fill=BG_BOT)

# ---- 3. terminal 光标块（右下角 ▍） ----
cur_x, cur_y = cx + 150, cy + 260
draw.rounded_rectangle([cur_x, cur_y, cur_x + 56, cur_y + 140],
                       radius=14, fill=PRIMARY)

# 光标左侧细横线（提示符感）
draw.rounded_rectangle([cur_x - 200, cur_y + 55, cur_x - 80, cur_y + 85],
                       radius=14, fill=PRIMARY_DK)
draw.rounded_rectangle([cur_x - 200, cur_y + 110, cur_x - 120, cur_y + 132],
                       radius=10, fill=PRIMARY_DK)

# ---- 4. 轻微发光增加质感 ----
glow = out.filter(ImageFilter.GaussianBlur(radius=4))
final = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
final.paste(out, (0, 0))
final.paste(glow, (0, 0), Image.new("L", (SIZE, SIZE), 50))

# 重新应用圆角遮罩（防止发光溢出圆角）
final2 = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
final2.paste(final, (0, 0), mask)

# ---- 5. 保存多尺寸 ICO ----
final2.save(DST, format="ICO", sizes=[(16, 16), (24, 24), (32, 32),
                                       (48, 48), (64, 64), (128, 128), (256, 256)])
print("saved:", DST)

# 同时存一份 PNG 预览
preview = os.path.join(HERE, "assets", "icon_preview.png")
final2.resize((256, 256), Image.LANCZOS).save(preview)
print("preview:", preview)
