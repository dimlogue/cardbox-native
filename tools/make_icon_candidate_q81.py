#!/usr/bin/env python3
# Q81 图标候选生成器（手写绘制，零素材）：三张彩卡错角叠放，扁平+细白描边，自家语言。
# 只产候选文件，绝不写 res/mipmap（正式图标须用户点头才换）。
from PIL import Image, ImageDraw, ImageFilter
import os, math

ROOT = os.path.join(os.path.dirname(__file__), "..")
OUT = os.path.join(ROOT, "icon-candidates", "q81-stacked")
os.makedirs(OUT, exist_ok=True)
S = 1024  # 母版绘制分辨率，最终缩到 512 及各 mipmap 尺寸

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def gradient_card(w, h, c1, c2, radius):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / max(w - 1, 1) * 0.55 + y / max(h - 1, 1) * 0.45)
            px[x, y] = lerp(c1, c2, t) + (255,)
    mask = Image.new("L", (w, h), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
    img.putalpha(mask)
    return img

def decorate(card, stripe=True):
    w, h = card.size
    d = ImageDraw.Draw(card)
    if stripe:
        d.rectangle([0, int(h * 0.24), w, int(h * 0.42)], fill=(255, 255, 255, 235))
    # 芯片：白圆角块+细线十字（沿现行图标母题）
    cw, ch = int(w * 0.17), int(h * 0.24)
    x0, y0 = int(w * 0.13), int(h * 0.56)
    d.rounded_rectangle([x0, y0, x0 + cw, y0 + ch], radius=int(ch * 0.22), fill=(255, 255, 255, 245))
    d.line([x0, y0 + ch // 2, x0 + cw, y0 + ch // 2], fill=(160, 190, 220, 255), width=max(4, S // 170))
    d.line([x0 + cw // 2, y0, x0 + cw // 2, y0 + ch], fill=(160, 190, 220, 255), width=max(4, S // 170))
    # 两道短线（卡号示意，同现行语言）
    lw = max(5, S // 150)
    for i, yy in enumerate((0.60, 0.76)):
        x1 = int(w * 0.44); x2 = int(w * (0.78 if i == 0 else 0.68))
        d.rounded_rectangle([x1, int(h * yy), x2, int(h * yy) + lw * 2], radius=lw, fill=(255, 255, 255, 230))
    # 细白描边
    d.rounded_rectangle([3, 3, w - 4, h - 4], radius=int(h * 0.11), outline=(255, 255, 255, 255), width=max(5, S // 120))
    return card

def shadow_for(card, blur):
    sh = Image.new("RGBA", card.size, (0, 0, 0, 0))
    alpha = card.split()[3].point(lambda a: int(a * 0.28))
    black = Image.new("RGBA", card.size, (18, 28, 52, 255))
    black.putalpha(alpha)
    return black.filter(ImageFilter.GaussianBlur(blur))

def paste_rot(base, card, center, angle, dy_shadow=14):
    sh = shadow_for(card, 18)
    for img, off in ((sh, dy_shadow), (card, 0)):
        r = img.rotate(angle, resample=Image.BICUBIC, expand=True)
        x = int(center[0] - r.size[0] / 2); y = int(center[1] - r.size[1] / 2 + off)
        base.alpha_composite(r, (x, y))

def main():
    bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(bg)
    # 浅底（沿现行图标的浅蓝白底，不做满版彩色）
    for y in range(S):
        c = lerp((240, 247, 255), (224, 238, 253), y / S)
        bd.line([(0, y), (S, y)], fill=c + (255,))
    bd.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.18), fill=None)  # 母版保持方图，圆角由系统遮罩处理

    cw, ch = int(S * 0.66), int(S * 0.42)
    rad = int(ch * 0.11)
    back = decorate(gradient_card(cw, ch, (64, 199, 164), (31, 169, 201), rad), stripe=False)   # 青绿后卡
    mid = decorate(gradient_card(cw, ch, (139, 108, 224), (198, 92, 196), rad), stripe=False)    # 紫中卡
    front = decorate(gradient_card(cw, ch, (10, 92, 214), (0, 163, 200), rad), stripe=True)     # 品牌蓝前卡
    cx, cy = S // 2, S // 2
    paste_rot(bg, back, (cx - int(S * 0.045), cy - int(S * 0.115)), 13)   # 后卡向右上探
    paste_rot(bg, mid, (cx - int(S * 0.055), cy - int(S * 0.015)), -11)   # 中卡向左上探
    paste_rot(bg, front, (cx + int(S * 0.01), cy + int(S * 0.10)), 0)     # 前卡正放压底
    master = bg.resize((512, 512), Image.LANCZOS)
    master.save(os.path.join(OUT, "ic_launcher_candidate_512.png"))
    for name, sz in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        d = os.path.join(OUT, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        master.resize((sz, sz), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))

    # 并排效果图：现行 vs 候选，大图+真实小尺寸+深底一格
    cur = Image.open(os.path.join(ROOT, "res/mipmap-xxxhdpi/ic_launcher.png")).convert("RGBA")
    sheet = Image.new("RGB", (1280, 880), (247, 249, 252))
    sd = ImageDraw.Draw(sheet)
    sd.text((60, 36), "Q81 icon candidates  (left: current / right: Q81 stacked-cards)", fill=(28, 32, 40))
    for i, (label, icon) in enumerate((("CURRENT", cur), ("Q81 CANDIDATE", master))):
        x = 60 + i * 610
        sd.rounded_rectangle([x, 90, x + 560, 610], radius=28, fill=(255, 255, 255), outline=(214, 222, 235), width=2)
        big = icon.resize((320, 320), Image.LANCZOS)
        sheet.paste(big, (x + 120, 120), big)
        sd.text((x + 24, 470), label, fill=(28, 32, 40))
        for j, sz in enumerate((96, 64, 48, 32)):
            im = icon.resize((sz, sz), Image.LANCZOS)
            xx = x + 24 + j * 130
            sheet.paste(im, (xx, 510), im)
            sd.text((xx, 510 + sz + 6), f"{sz}px", fill=(110, 118, 132))
    # 深底检验行
    sd.rounded_rectangle([60, 660, 1220, 830], radius=24, fill=(22, 26, 36))
    sd.text((84, 682), "on dark background", fill=(220, 228, 240))
    for i, icon in enumerate((cur, master)):
        im = icon.resize((110, 110), Image.LANCZOS)
        sheet.paste(im, (300 + i * 320, 690), im)
    sheet.save(os.path.join(OUT, "compare.png"))
    print("OK", OUT)

if __name__ == "__main__":
    main()
