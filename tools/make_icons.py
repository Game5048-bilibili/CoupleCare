# -*- coding: utf-8 -*-
"""
从 logo.png 生成 Android 全套启动图标（纯标准库，无需 Pillow）。

生成内容：
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png        普通方形图标
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png  圆形图标（老启动器用）
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png
                                                                        自适应图标前景层
    app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml                  自适应图标描述
    app/src/main/res/values/ic_launcher_colors.xml                      自适应图标背景色

用法：
    python tools/make_icons.py --analyze      # 只分析 logo 内容范围，看看适合哪种版式
    python tools/make_icons.py                # 生成图标（默认 auto 版式）
    python tools/make_icons.py --layout fit   # 强制「居中贴图」版式
    python tools/make_icons.py --layout bleed # 强制「满幅出血」版式
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pnglib

# ----------------------------------------------------------------------
# 路径
# ----------------------------------------------------------------------

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGO_PATH = os.path.join(ROOT, "logo.png")
RES_DIR = os.path.join(ROOT, "android", "app", "src", "main", "res")

# ----------------------------------------------------------------------
# Android 图标尺寸规范
# ----------------------------------------------------------------------

# (目录后缀, legacy 图标边长, 自适应画布边长)
DENSITIES = [
    ("mdpi", 48, 108),
    ("hdpi", 72, 162),
    ("xhdpi", 96, 216),
    ("xxhdpi", 144, 324),
    ("xxxhdpi", 192, 432),
]

# 自适应图标规范：108dp 画布中，只有中央 72dp 一定可见，安全区 66dp
SAFE_ZONE_RATIO = 66.0 / 108.0


# ======================================================================
# 分析：找出 logo 里「主体」的范围
# ======================================================================

def analyze(logo_w, logo_h, logo_rgba):
    """用「与模糊版差异」的方式找出主体的包围盒，判断适合哪种版式。"""
    probe = 128
    small = pnglib.resize(logo_rgba, logo_w, logo_h, probe, probe)

    # 3x3 盒式模糊，得到背景/大色块的估计
    blur = bytearray(probe * probe * 4)
    radius = 6
    for y in range(probe):
        for x in range(probe):
            r = g = b = 0
            count = 0
            for dy in range(-radius, radius + 1):
                yy = y + dy
                if yy < 0 or yy >= probe:
                    continue
                for dx in range(-radius, radius + 1):
                    xx = x + dx
                    if xx < 0 or xx >= probe:
                        continue
                    p = (yy * probe + xx) * 4
                    r += small[p]
                    g += small[p + 1]
                    b += small[p + 2]
                    count += 1
            d = (y * probe + x) * 4
            blur[d] = r // count
            blur[d + 1] = g // count
            blur[d + 2] = b // count
            blur[d + 3] = 255

    threshold = 26
    mask = [[False] * probe for _ in range(probe)]
    min_x, min_y, max_x, max_y = probe, probe, -1, -1

    for y in range(probe):
        for x in range(probe):
            p = (y * probe + x) * 4
            diff = (abs(small[p] - blur[p]) +
                    abs(small[p + 1] - blur[p + 1]) +
                    abs(small[p + 2] - blur[p + 2])) / 3.0
            if diff > threshold:
                mask[y][x] = True
                min_x = min(min_x, x)
                min_y = min(min_y, y)
                max_x = max(max_x, x)
                max_y = max(max_y, y)

    print("=" * 60)
    print("logo 尺寸：%dx%d" % (logo_w, logo_h))

    corners = {
        "左上": pnglib.average_color(logo_rgba, logo_w, logo_h, 0, 0, 60, 60),
        "右上": pnglib.average_color(logo_rgba, logo_w, logo_h, logo_w - 60, 0, 60, 60),
        "左下": pnglib.average_color(logo_rgba, logo_w, logo_h, 0, logo_h - 60, 60, 60),
        "右下": pnglib.average_color(logo_rgba, logo_w, logo_h, logo_w - 60, logo_h - 60, 60, 60),
        "中心": pnglib.average_color(logo_rgba, logo_w, logo_h, logo_w // 2 - 30, logo_h // 2 - 30, 60, 60),
    }
    for name, color in corners.items():
        print("  %s平均色：%s" % (name, pnglib.to_hex(color)))

    print()
    print("主体包围盒（基于边缘检测，128x128 采样）：")
    if max_x < 0:
        print("  没有检测到明显主体（整张图都是平滑渐变）")
        return None

    bx = min_x / probe
    by = min_y / probe
    bw = (max_x - min_x + 1) / probe
    bh = (max_y - min_y + 1) / probe

    print("  x: %.1f%% ~ %.1f%%   y: %.1f%% ~ %.1f%%" %
          (bx * 100, (bx + bw) * 100, by * 100, (by + bh) * 100))
    print("  宽 %.1f%%  高 %.1f%%" % (bw * 100, bh * 100))

    # 安全区是中央 61.1%（66/108）
    safe_lo = (1 - SAFE_ZONE_RATIO) / 2
    safe_hi = 1 - safe_lo
    print()
    print("自适应图标安全区：%.1f%% ~ %.1f%%" % (safe_lo * 100, safe_hi * 100))

    inside = (bx >= safe_lo - 0.02 and by >= safe_lo - 0.02 and
              bx + bw <= safe_hi + 0.02 and by + bh <= safe_hi + 0.02)
    print("主体是否完全落在安全区内：%s" % ("是" if inside else "否"))

    print()
    print("ASCII 主体分布（■ = 有细节）：")
    step = probe // 48
    for yy in range(0, probe, step):
        line = ""
        for xx in range(0, probe, step):
            hit = any(mask[y][x]
                      for y in range(yy, min(probe, yy + step))
                      for x in range(xx, min(probe, xx + step)))
            line += "■" if hit else "·"
        print("  " + line)

    return (bx, by, bw, bh, inside)


# ======================================================================
# 生成图标
# ======================================================================

def make_square(logo_rgba, lw, lh, size, layout, bg_color):
    """生成一张 size x size 的图标位图。"""
    if layout == "bleed":
        # 满幅出血：直接把整张 logo 缩到目标尺寸
        return pnglib.resize(logo_rgba, lw, lh, size, size)

    # fit：先按安全区比例缩放 logo，居中贴在纯色背景上
    inner = max(1, int(round(size * SAFE_ZONE_RATIO / 1.0 * 0.94)))
    scaled = pnglib.resize(logo_rgba, lw, lh, inner, inner)
    canvas = pnglib.new_canvas(size, size, (bg_color[0], bg_color[1], bg_color[2], 255))
    offset = (size - inner) // 2
    pnglib.blend_over(canvas, size, size, scaled, inner, inner,
                      offset, offset, corner_radius=max(1, inner // 8))
    return canvas


def make_foreground(logo_rgba, lw, lh, canvas_size, layout):
    """自适应图标前景层（透明背景，内容限制在安全区内）。"""
    if layout == "bleed":
        # 满幅版式：前景全透明，背景层放满幅 logo
        return pnglib.new_canvas(canvas_size, canvas_size, (0, 0, 0, 0))

    inner = max(1, int(round(canvas_size * SAFE_ZONE_RATIO)))
    scaled = pnglib.resize(logo_rgba, lw, lh, inner, inner)
    canvas = pnglib.new_canvas(canvas_size, canvas_size, (0, 0, 0, 0))
    offset = (canvas_size - inner) // 2
    pnglib.blend_over(canvas, canvas_size, canvas_size, scaled, inner, inner,
                      offset, offset, corner_radius=max(1, inner // 8))
    return canvas


def make_round(logo_rgba, lw, lh, size, layout, bg_color):
    """圆形图标（给不支持自适应图标的老启动器）。"""
    square = make_square(logo_rgba, lw, lh, size, layout, bg_color)
    radius = size / 2.0
    circle = pnglib.new_canvas(size, size, (0, 0, 0, 0))
    cx = cy = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            dx = x - cx
            dy = y - cy
            if dx * dx + dy * dy <= radius * radius:
                p = (y * size + x) * 4
                circle[p:p + 4] = square[p:p + 4]
    return circle


def generate(layout, bg_color, logo_w, logo_h, logo_rgba):
    written = []

    for suffix, legacy_size, adaptive_size in DENSITIES:
        folder = os.path.join(RES_DIR, "mipmap-" + suffix)
        os.makedirs(folder, exist_ok=True)

        square = make_square(logo_rgba, logo_w, logo_h, legacy_size, layout, bg_color)
        path = os.path.join(folder, "ic_launcher.png")
        pnglib.save_png(path, legacy_size, legacy_size, square)
        written.append(path)

        round_icon = make_round(logo_rgba, logo_w, logo_h, legacy_size, layout, bg_color)
        path = os.path.join(folder, "ic_launcher_round.png")
        pnglib.save_png(path, legacy_size, legacy_size, round_icon)
        written.append(path)

        foreground = make_foreground(logo_rgba, logo_w, logo_h, adaptive_size, layout)
        path = os.path.join(folder, "ic_launcher_foreground.png")
        pnglib.save_png(path, adaptive_size, adaptive_size, foreground)
        written.append(path)

    # 满幅版式下，自适应图标的背景层需要放满幅 logo
    if layout == "bleed":
        for suffix, _legacy_size, adaptive_size in DENSITIES:
            folder = os.path.join(RES_DIR, "mipmap-" + suffix)
            background = pnglib.resize(logo_rgba, logo_w, logo_h, adaptive_size, adaptive_size)
            path = os.path.join(folder, "ic_launcher_background.png")
            pnglib.save_png(path, adaptive_size, adaptive_size, background)
            written.append(path)

    # 自适应图标描述文件
    anydpi = os.path.join(RES_DIR, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    if layout == "bleed":
        background_ref = "@mipmap/ic_launcher_background"
        # 满幅版式下前景层是全透明的，不能拿它当 monochrome（主题图标会变成一片空白），
        # 所以干脆不声明 monochrome，系统会退回使用普通图标
        monochrome_line = ""
    else:
        background_ref = "@color/ic_launcher_background"
        monochrome_line = (
            '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n'
        )

    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="%s" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '%s'
        '</adaptive-icon>\n'
    ) % (background_ref, monochrome_line)

    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(anydpi, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(xml)
        written.append(path)

    # 背景色（只有 fit 版式才需要；写进独立文件，避免和 colors.xml 里的同名资源冲突）
    colors_path = os.path.join(RES_DIR, "values", "ic_launcher_colors.xml")
    with open(colors_path, "w", encoding="utf-8") as handle:
        handle.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- 由 tools/make_icons.py 依据 logo.png 边缘色自动生成，请勿手工修改 -->\n'
            '<resources>\n'
            '    <color name="ic_launcher_background">%s</color>\n'
            '</resources>\n' % pnglib.to_hex(bg_color)
        )
    written.append(colors_path)

    # 清掉旧的矢量占位图，避免和新图标混淆
    for stale in ("ic_launcher_foreground.xml",):
        old = os.path.join(RES_DIR, "drawable", stale)
        if os.path.exists(old):
            os.remove(old)
            print("已删除旧的矢量占位图：%s" % old)

    return written


def main():
    parser = argparse.ArgumentParser(description="从 logo.png 生成 Android 图标")
    parser.add_argument("--analyze", action="store_true", help="只分析，不生成")
    parser.add_argument("--layout", choices=["auto", "fit", "bleed"], default="auto")
    parser.add_argument("--logo", default=LOGO_PATH)
    args = parser.parse_args()

    print("读取 %s …" % args.logo)
    logo_w, logo_h, logo_rgba = pnglib.load_png(args.logo)

    info = analyze(logo_w, logo_h, logo_rgba)
    if args.analyze:
        return 0

    layout = args.layout
    if layout == "auto":
        # 主体在安全区内 -> 居中贴图（更好看，四周留白安全）；
        # 否则满幅出血，至少不会把主体切掉
        layout = "fit" if (info is not None and info[4]) else "bleed"
        print("\n自动选择版式：%s" % layout)

    # 背景色取四角平均（logo 的底色本来就是渐变，取平均最接近）
    c1 = pnglib.average_color(logo_rgba, logo_w, logo_h, 0, 0, 80, 80)
    c2 = pnglib.average_color(logo_rgba, logo_w, logo_h, logo_w - 80, 0, 80, 80)
    c3 = pnglib.average_color(logo_rgba, logo_w, logo_h, 0, logo_h - 80, 80, 80)
    c4 = pnglib.average_color(logo_rgba, logo_w, logo_h, logo_w - 80, logo_h - 80, 80, 80)
    bg = tuple(sum(v) // 4 for v in zip(c1, c2, c3, c4))
    print("自适应图标背景色：%s" % pnglib.to_hex(bg))

    files = generate(layout, bg, logo_w, logo_h, logo_rgba)
    print("\n已生成 %d 个文件：" % len(files))
    for path in files:
        print("  " + os.path.relpath(path, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
