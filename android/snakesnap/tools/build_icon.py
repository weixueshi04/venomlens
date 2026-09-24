#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从线稿母版生成 Android 图标资源。

用法：
    python build_icon.py --master design/ic_launcher_master.png --res app/src/main/res

产出：
    drawable-{m,h,x,xx,xxx}dpi/ic_launcher_foreground.png   自适应图标前景（透明底）
    mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.webp               传统图标（圆角方块）
    mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_round.webp         传统圆形图标
    自适应图标的背景层是纯色 #0C3B1E，见 drawable/ic_launcher_background.xml

关键约束（踩过坑，别改）：
    自适应图标画布是 108dp，但系统只保证正中的 72dp「可见区」不被遮罩裁掉
    （左右上下各留 18dp 给视差和各家厂商的遮罩形状）。
    实测 MIUI 可见区 = 画布 72..360px（@4x），与 AOSP 规范一致。

    所以母版要整块映射到 288px（= 72dp）而不是 432px。
    按 432px 映射会让线稿铺满 100% 可见宽 —— 取景框四边正好贴在被裁掉的边界上。
    按 288px 映射后：取景框占可见区 74.3%，与设计稿自身比例一致，且上下左右都留有余量。
"""
import argparse
import os
import sys

try:
    from PIL import Image, ImageDraw
    import numpy as np
except ImportError:
    sys.exit("需要 Pillow 与 numpy：pip install pillow numpy")

BG = (12, 59, 30, 255)              # #0C3B1E 深墨绿（取自源图实测底色）
CANVAS_DP = 108
VISIBLE_DP = 72                     # 系统保证可见的区域
WITHIN_SRC = 1522                   # 母版里取景框边长（像素）
SRC_EDGE = 2048                     # 母版画布边长
SRC_RADIUS = 408                    # 母版圆角半径（老图标用）
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 母版里取景框的 bbox，用于圆形图标的锚点（按取景框而非线稿 bbox 居中，
# 因为线稿 bbox 含探出框外的蛇头，会整体偏上）
FRAME_BBOX = (263, 267, 1784, 1814)


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--master", default=os.path.join(os.path.dirname(__file__), "..", "design",
                                                     "ic_launcher_master.png"))
    ap.add_argument("--res", default=os.path.join(os.path.dirname(__file__), "..",
                                                  "app", "src", "main", "res"))
    args = ap.parse_args()

    art = Image.open(args.master).convert("RGBA")
    if art.size != (SRC_EDGE, SRC_EDGE):
        sys.exit("母版应为 %dx%d，实际 %s" % (SRC_EDGE, SRC_EDGE, art.size))

    # ---- 1. 自适应图标前景：母版整块映射到 72dp 可见区 ----
    for name, sc in DENSITIES.items():
        canvas = round(CANVAS_DP * sc)
        side = round(VISIBLE_DP * sc)
        small = art.resize((side, side), Image.LANCZOS)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        layer.alpha_composite(small, ((canvas - side) // 2, (canvas - side) // 2))
        d = os.path.join(args.res, "drawable-%s" % name)
        os.makedirs(d, exist_ok=True)
        layer.save(os.path.join(d, "ic_launcher_foreground.png"))
        print("前景 %-8s %3dpx 画布 / %3dpx 可见  -> 取景框 %.1fdp"
              % (name, canvas, side, WITHIN_SRC * side / SRC_EDGE / sc))

    # ---- 2. 传统图标（圆角方块）：老系统无遮罩，直接用母版的满幅构图 ----
    for name, size in LEGACY.items():
        k = size / SRC_EDGE
        base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        base.paste(Image.new("RGBA", (size, size), BG), (0, 0),
                   rounded_mask(size, max(1, round(SRC_RADIUS * k))))
        out = Image.alpha_composite(base, art.resize((size, size), Image.LANCZOS))
        d = os.path.join(args.res, "mipmap-%s" % name)
        os.makedirs(d, exist_ok=True)
        out.save(os.path.join(d, "ic_launcher.webp"), "WEBP", lossless=True, quality=100)
        print("传统 %-8s %3dpx 圆角 %d" % (name, size, round(SRC_RADIUS * k)))

    # ---- 3. 传统圆形图标 ----
    a = np.asarray(art).astype(np.float32)
    ys, xs = np.where(a[..., 3] / 255.0 > 0.30)
    acx = (FRAME_BBOX[0] + FRAME_BBOX[2]) / 2
    acy = (FRAME_BBOX[1] + FRAME_BBOX[3]) / 2
    maxr = float(np.sqrt((xs - acx) ** 2 + (ys - acy) ** 2).max())
    for name, size in LEGACY.items():
        k = (size / 2 * 0.90) / maxr          # 四周留 10% 边距
        side = max(1, round(SRC_EDGE * k))
        base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        base.paste(Image.new("RGBA", (size, size), BG), (0, 0), circle_mask(size))
        base.alpha_composite(art.resize((side, side), Image.LANCZOS),
                             (round(size / 2 - acx * k), round(size / 2 - acy * k)))
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(base, (0, 0), circle_mask(size))
        out.save(os.path.join(args.res, "mipmap-%s" % name, "ic_launcher_round.webp"),
                 "WEBP", lossless=True, quality=100)
        print("圆形 %-8s %3dpx 线稿最远点占半径 %.0f%%" % (name, size, maxr * k / (size / 2) * 100))

    print("\n完成。背景层颜色在 drawable/ic_launcher_background.xml，需与 #0C3B1E 一致。")


if __name__ == "__main__":
    main()
