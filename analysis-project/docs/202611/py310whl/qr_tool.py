#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
qr_tool - 二维码生成/解码 离线工具 (替代 ZZY.QR, 纯 Python 实现)

依赖 (py310whl 离线安装):
    pip3 install --no-index --find-links=. qrcode zxing-cpp Pillow

用法:
    生成: python3 qr_tool.py gen  "要编码的文本" -o qr.png
          python3 qr_tool.py gen  @文本文件.txt -o qr.png   # 从文件读内容
          超长文本自动切成多张: qr_1of4.png qr_2of4.png ...

    解码: python3 qr_tool.py decode qr.png
          python3 qr_tool.py decode "qr_*.png"              # 多张自动拼回原文(支持通配符)
          python3 qr_tool.py decode qr_1of4.png qr_3of4.png qr_2of4.png qr_4of4.png  # 顺序随意

    可选: gen 加 --chunk 1000 控制单片字节数(默认1000, 越小越好扫)
          decode 加 -o out.txt 把拼回的原文存文件
"""
import argparse
import glob
import json
import sys

HEADER_KEYS = {"i", "n", "d"}


def _split_bytes(text, max_bytes):
    """按 UTF-8 字节切块, 不切断多字节字符. 返回字节列表."""
    encoded = text.encode("utf-8")
    chunks = []
    start = 0
    n = len(encoded)
    while start < n:
        end = min(start + max_bytes, n)
        # 回退到不截断 UTF-8 序列的位置
        while end < n and (encoded[end] & 0xC0) == 0x80:
            end -= 1
        if end == start:
            raise ValueError("chunk 太小, 装不下单个字符")
        chunks.append(encoded[start:end].decode("utf-8", errors="strict"))
        start = end
    return chunks


def cmd_gen(args):
    import qrcode

    text = args.text
    if text.startswith("@"):
        with open(text[1:], "r", encoding="utf-8", newline="") as f:
            text = f.read().strip()

    def new_qr():
        return qrcode.QRCode(
            version=None,
            error_correction=qrcode.constants.ERROR_CORRECT_M,  # ~15% 容错
            box_size=args.scale,
            border=4,
        )

    total_bytes = len(text.encode("utf-8"))

    # 单张: 纯文本编码 (装得下就不加元数据, 任何解码器都能扫)
    qr = new_qr()
    qr.add_data(text)
    fits = True
    try:
        qr.make(fit=True)  # 超容量时这里抛 ValueError (Invalid version 41)
    except ValueError:
        fits = False
    if fits:
        qr.make_image(fill_color="black", back_color="white").save(args.out)
        print(f"已生成 1 张: {args.out} (文本 {total_bytes} 字节, QR 版本 {qr.version})")
        return

    # 多张: 每片 JSON 元数据 {"i":序号,"n":总数,"d":内容}
    pieces = _split_bytes(text, args.chunk)
    n = len(pieces)
    stem, dot, ext = args.out.rpartition(".")
    if not stem:
        stem, ext = args.out, "png"
    paths = []
    for i, piece in enumerate(pieces, 1):
        payload = json.dumps({"i": i, "n": n, "d": piece},
                             ensure_ascii=False, separators=(",", ":"))
        qr = new_qr()
        qr.add_data(payload)
        try:
            qr.make(fit=True)
        except ValueError:
            print(f"单片内容仍超长 (chunk={args.chunk} 字节), 请用 --chunk 调小", file=sys.stderr)
            sys.exit(2)
        out = f"{stem}_{i}of{n}.{ext}"
        qr.make_image(fill_color="black", back_color="white").save(out)
        paths.append(out)
        print(f"已生成 {i}/{n}: {out} ({len(piece.encode('utf-8'))} 字节内容, QR 版本 {qr.version})")
    print(f"共 {n} 张, 解码: python3 qr_tool.py decode \"{stem}_*of{n}.{ext}\"")


def _read_one(path):
    """读一张图, 返回 ('plain', 文本) 或 ('piece', i, n, 内容). 解不开返回 None."""
    from PIL import Image
    import zxingcpp

    results = zxingcpp.read_barcodes(Image.open(path))
    if not results:
        return None
    text = str(results[0].text)
    try:
        obj = json.loads(text)
        if isinstance(obj, dict) and HEADER_KEYS <= set(obj.keys()):
            return ("piece", int(obj["i"]), int(obj["n"]), obj["d"])
    except (json.JSONDecodeError, ValueError, TypeError):
        pass
    return ("plain", text)


def cmd_decode(args):
    # 展开通配符 (Windows shell 不展开, 手动 glob)
    paths = []
    for pattern in args.images:
        matched = sorted(glob.glob(pattern))
        paths.extend(matched if matched else [pattern])

    plains, pieces, unreadable = [], {}, []
    for p in paths:
        try:
            r = _read_one(p)
        except Exception as e:
            r = None
            print(f"警告: {p} 读取失败: {e}", file=sys.stderr)
        if r is None:
            unreadable.append(p)
        elif r[0] == "plain":
            plains.append(r[1])
        else:
            _, i, n, content = r
            pieces.setdefault(n, {})[i] = content

    # 多片拼装
    for n, parts in sorted(pieces.items()):
        missing = [i for i in range(1, n + 1) if i not in parts]
        if missing:
            print(f"分片不完整: 缺 {len(missing)} 片 -> {missing} "
                  f"(共需 {n} 片, 已扫 {len(parts)} 片)", file=sys.stderr)
            sys.exit(3)
        text = "".join(parts[i] for i in range(1, n + 1))
        if args.out:
            with open(args.out, "w", encoding="utf-8", newline="") as f:
                f.write(text)
            print(f"已拼回 {n} 片 -> {args.out} ({len(text.encode('utf-8'))} 字节)")
        else:
            print(text)
        return

    # 普通二维码
    if plains:
        if args.out:
            with open(args.out, "w", encoding="utf-8", newline="") as f:
                f.write("\n".join(plains))
            print(f"已写入 {args.out}")
        else:
            print("\n".join(plains))
    if unreadable:
        print(f"未识别: {unreadable} (图片需清晰、完整、对比度足够)", file=sys.stderr)
        if not plains and not pieces:
            sys.exit(2)


def cmd_version(_args):
    from importlib.metadata import version
    from PIL import Image
    print("qrcode:", version("qrcode"))
    print("zxing-cpp:", version("zxing-cpp"))
    print("Pillow:", Image.__version__)


def main():
    for s in (sys.stdout, sys.stderr):
        if hasattr(s, "reconfigure"):
            s.reconfigure(encoding="utf-8", errors="replace")

    p = argparse.ArgumentParser(description="二维码生成/解码离线工具 (超长文本自动分片)")
    sub = p.add_subparsers(dest="cmd", required=True)

    g = sub.add_parser("gen", help="文本 -> 二维码 PNG (超长自动分多张)")
    g.add_argument("text", help='文本内容, 或 "@文件路径" 从文件读取')
    g.add_argument("-o", "--out", default="qr.png", help="输出 PNG 路径 (默认 qr.png, 分片自动加 _1ofN 后缀)")
    g.add_argument("-s", "--scale", type=int, default=8, help="每模块像素数 (默认 8)")
    g.add_argument("-c", "--chunk", type=int, default=1000,
                   help="分片每片最大 UTF-8 字节数 (默认 1000, 越小越好扫, 最大建议 2200)")
    g.set_defaults(func=cmd_gen)

    d = sub.add_parser("decode", help="二维码 PNG -> 文本 (多张自动拼装)")
    d.add_argument("images", nargs="+", help="图片路径, 支持通配符和乱序")
    d.add_argument("-o", "--out", default=None, help="结果写入文件 (可选)")
    d.set_defaults(func=cmd_decode)

    v = sub.add_parser("version", help="显示依赖版本")
    v.set_defaults(func=cmd_version)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
