# -*- coding: utf-8 -*-
"""
纯标准库 PNG 读写 + 缩放工具。

为什么不用 Pillow：本机无法联网安装依赖，只有 conda base 的 Python 3.12（自带
cryptography / zlib，但没有 PIL）。PNG 本身只是「zlib 压缩 + 逐行滤波器」，用
标准库完全能实现解码/编码，够用来做图标。

支持的输入：8 位深、非隔行的 PNG，颜色类型 0(灰度)/2(RGB)/4(灰度+A)/6(RGBA)/3(调色板)。
输出统一为 8 位 RGBA（颜色类型 6）。

只在本机生成图标时使用，不参与 APK 构建，也不需要在 Orange Pi 上安装。
"""

import struct
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


# ======================================================================
# 解码
# ======================================================================

def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    if pb <= pc:
        return b
    return c


def _unfilter(raw: bytes, width: int, height: int, bpp: int) -> list:
    """还原 PNG 逐行滤波，返回每行的 bytearray（已去掉滤波字节）。"""
    stride = width * bpp
    rows = []
    prev = bytearray(stride)
    pos = 0

    for _ in range(height):
        ftype = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride

        if ftype == 0:
            pass
        elif ftype == 1:                       # Sub
            for x in range(bpp, stride):
                line[x] = (line[x] + line[x - bpp]) & 0xFF
        elif ftype == 2:                       # Up
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 0xFF
        elif ftype == 3:                       # Average
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 0xFF
        elif ftype == 4:                       # Paeth
            for x in range(stride):
                a = line[x - bpp] if x >= bpp else 0
                b = prev[x]
                c = prev[x - bpp] if x >= bpp else 0
                line[x] = (line[x] + _paeth(a, b, c)) & 0xFF
        else:
            raise ValueError("不支持的 PNG 滤波类型 %d" % ftype)

        rows.append(line)
        prev = line

    return rows


def load_png(path: str):
    """
    读取 PNG。

    :return: (width, height, bytearray RGBA)
    """
    data = open(path, "rb").read()
    if data[:8] != PNG_SIGNATURE:
        raise ValueError("不是 PNG 文件：%s" % path)

    pos = 8
    width = height = bit_depth = color_type = interlace = 0
    palette = b""
    idat = bytearray()

    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]

        if ctype == b"IHDR":
            width, height, bit_depth, color_type, _comp, _filt, interlace = \
                struct.unpack(">IIBBBBB", body)
        elif ctype == b"PLTE":
            palette = body
        elif ctype == b"IDAT":
            idat += body
        elif ctype == b"IEND":
            break

        pos += 12 + length

    if bit_depth != 8:
        raise ValueError("只支持 8 位深 PNG，当前是 %d" % bit_depth)
    if interlace != 0:
        raise ValueError("不支持隔行（Adam7）PNG")

    raw = zlib.decompress(bytes(idat))

    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
    rows = _unfilter(raw, width, height, channels)

    rgba = bytearray(width * height * 4)

    for y in range(height):
        line = rows[y]
        base = y * width * 4

        if color_type == 2:                    # RGB
            for x in range(width):
                s = x * 3
                d = base + x * 4
                rgba[d] = line[s]
                rgba[d + 1] = line[s + 1]
                rgba[d + 2] = line[s + 2]
                rgba[d + 3] = 255

        elif color_type == 6:                  # RGBA
            rgba[base:base + width * 4] = line

        elif color_type == 0:                  # 灰度
            for x in range(width):
                g = line[x]
                d = base + x * 4
                rgba[d] = rgba[d + 1] = rgba[d + 2] = g
                rgba[d + 3] = 255

        elif color_type == 4:                  # 灰度 + Alpha
            for x in range(width):
                g = line[x * 2]
                d = base + x * 4
                rgba[d] = rgba[d + 1] = rgba[d + 2] = g
                rgba[d + 3] = line[x * 2 + 1]

        elif color_type == 3:                  # 调色板
            for x in range(width):
                idx = line[x] * 3
                d = base + x * 4
                rgba[d] = palette[idx]
                rgba[d + 1] = palette[idx + 1]
                rgba[d + 2] = palette[idx + 2]
                rgba[d + 3] = 255

    return width, height, rgba


# ======================================================================
# 编码
# ======================================================================

def save_png(path: str, width: int, height: int, rgba: bytearray) -> None:
    """写 8 位 RGBA PNG，行滤波统一用 Up（对照片/渐变压缩率不错，实现也简单）。"""
    stride = width * 4
    filtered = bytearray()
    prev = bytearray(stride)

    for y in range(height):
        line = rgba[y * stride:(y + 1) * stride]
        filtered.append(2)                     # filter type 2 = Up
        filtered += bytes((line[i] - prev[i]) & 0xFF for i in range(stride))
        prev = line

    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + tag + payload +
                struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (PNG_SIGNATURE +
           chunk(b"IHDR", ihdr) +
           chunk(b"IDAT", zlib.compress(bytes(filtered), 9)) +
           chunk(b"IEND", b""))

    with open(path, "wb") as handle:
        handle.write(png)


# ======================================================================
# 图像操作
# ======================================================================

def resize(src, src_w: int, src_h: int, dst_w: int, dst_h: int) -> bytearray:
    """
    区域平均（box filter）缩放。

    图标是从 1254x1254 往小尺寸缩，box 平均正好等价于正确的面积采样，
    比双线性更干净、更不会出现锯齿。
    """
    dst = bytearray(dst_w * dst_h * 4)

    for dy in range(dst_h):
        y0 = dy * src_h // dst_h
        y1 = max(y0 + 1, (dy + 1) * src_h // dst_h)

        for dx in range(dst_w):
            x0 = dx * src_w // dst_w
            x1 = max(x0 + 1, (dx + 1) * src_w // dst_w)

            r = g = b = a = 0
            count = 0
            for sy in range(y0, y1):
                row = sy * src_w * 4
                for sx in range(x0, x1):
                    p = row + sx * 4
                    r += src[p]
                    g += src[p + 1]
                    b += src[p + 2]
                    a += src[p + 3]
                    count += 1

            d = (dy * dst_w + dx) * 4
            dst[d] = r // count
            dst[d + 1] = g // count
            dst[d + 2] = b // count
            dst[d + 3] = a // count

    return dst


def crop(src, src_w: int, src_h: int, x: int, y: int, w: int, h: int) -> bytearray:
    """裁剪出一个矩形区域。"""
    dst = bytearray(w * h * 4)
    for row in range(h):
        sy = y + row
        if sy < 0 or sy >= src_h:
            continue
        for col in range(w):
            sx = x + col
            if sx < 0 or sx >= src_w:
                continue
            s = (sy * src_w + sx) * 4
            d = (row * w + col) * 4
            dst[d:d + 4] = src[s:s + 4]
    return dst


def new_canvas(w: int, h: int, color=(0, 0, 0, 0)) -> bytearray:
    return bytearray(bytes(color) * (w * h))


def blend_over(dst: bytearray, dst_w: int, dst_h: int,
               src: bytearray, src_w: int, src_h: int,
               offset_x: int, offset_y: int,
               corner_radius: int = 0) -> None:
    """
    把 src 以「source-over」方式叠加到 dst 的指定位置。

    corner_radius > 0 时给 src 做圆角遮罩（图标做成圆角方块更好看）。
    """
    for y in range(src_h):
        ty = offset_y + y
        if ty < 0 or ty >= dst_h:
            continue
        for x in range(src_w):
            tx = offset_x + x
            if tx < 0 or tx >= dst_w:
                continue

            if corner_radius > 0:
                # 四角做抗锯齿的圆角判断
                cx = cy = None
                if x < corner_radius and y < corner_radius:
                    cx, cy = corner_radius, corner_radius
                elif x >= src_w - corner_radius and y < corner_radius:
                    cx, cy = src_w - corner_radius - 1, corner_radius
                elif x < corner_radius and y >= src_h - corner_radius:
                    cx, cy = corner_radius, src_h - corner_radius - 1
                elif x >= src_w - corner_radius and y >= src_h - corner_radius:
                    cx, cy = src_w - corner_radius - 1, src_h - corner_radius - 1
                if cx is not None:
                    dx = x - cx
                    dy = y - cy
                    if dx * dx + dy * dy > corner_radius * corner_radius:
                        continue

            s = (y * src_w + x) * 4
            d = (ty * dst_w + tx) * 4

            sa = src[s + 3]
            if sa == 0:
                continue
            if sa == 255:
                dst[d:d + 4] = src[s:s + 4]
                continue

            inv = 255 - sa
            da = dst[d + 3]
            out_a = sa + da * inv // 255
            dst[d] = (src[s] * sa + dst[d] * da * inv // 255) // max(1, out_a)
            dst[d + 1] = (src[s + 1] * sa + dst[d + 1] * da * inv // 255) // max(1, out_a)
            dst[d + 2] = (src[s + 2] * sa + dst[d + 2] * da * inv // 255) // max(1, out_a)
            dst[d + 3] = out_a


def fill_background(dst: bytearray, w: int, h: int, color) -> None:
    """给整个画布铺一层不透明底色。"""
    dst[:] = bytes(color) * (w * h)


def average_color(src, w: int, h: int, x: int, y: int, cw: int, ch: int):
    """取一块区域的平均色（用来给自适应图标选背景色）。"""
    r = g = b = 0
    count = 0
    for yy in range(y, min(h, y + ch)):
        for xx in range(x, min(w, x + cw)):
            p = (yy * w + xx) * 4
            r += src[p]
            g += src[p + 1]
            b += src[p + 2]
            count += 1
    if count == 0:
        return (255, 255, 255)
    return (r // count, g // count, b // count)


def to_hex(color) -> str:
    return "#%02X%02X%02X" % (color[0], color[1], color[2])
