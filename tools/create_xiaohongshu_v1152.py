#!/usr/bin/env python3
"""Create deterministic Xiaohongshu cards without altering the device screenshot."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "marketing/xiaohongshu-1.15.2/supernote-current-page.png"
OUTPUT = ROOT / "marketing/xiaohongshu-1.15.2"
FONT_MEDIUM = "/System/Library/Fonts/STHeiti Medium.ttc"
FONT_LIGHT = "/System/Library/Fonts/STHeiti Light.ttc"

WIDTH, HEIGHT = 1242, 1656
PAPER = (247, 242, 232)
PAPER_CARD = (252, 249, 242)
INK = (25, 25, 23)
MUTED = (92, 85, 76)
ORANGE = (226, 83, 45)
LINE = (201, 188, 168)


def font(size: int, medium: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_MEDIUM if medium else FONT_LIGHT, size)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGB", (WIDTH, HEIGHT), PAPER)
    draw = ImageDraw.Draw(image)
    for y in range(0, HEIGHT, 34):
        draw.line((0, y, WIDTH, y), fill=(246, 240, 229), width=1)
    return image, draw


def header(draw: ImageDraw.ImageDraw, number: str, label: str) -> None:
    draw.text((56, 40), f"{number} / {label}", font=font(31, True), fill=INK)
    draw.line((265, 60, 1182, 60), fill=MUTED, width=2)


def rounded(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], radius: int = 22) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=PAPER_CARD, outline=LINE, width=2)


def center_text(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    text: str,
    size: int,
    fill=INK,
    medium: bool = False,
    spacing: int = 8,
) -> None:
    box_width = box[2] - box[0]
    box_height = box[3] - box[1]
    text_box = draw.multiline_textbbox((0, 0), text, font=font(size, medium), spacing=spacing, align="center")
    text_width = text_box[2] - text_box[0]
    text_height = text_box[3] - text_box[1]
    draw.multiline_text(
        (box[0] + (box_width - text_width) / 2, box[1] + (box_height - text_height) / 2),
        text,
        font=font(size, medium),
        fill=fill,
        spacing=spacing,
        align="center",
    )


def place_screenshot(image: Image.Image, box: tuple[int, int, int, int]) -> tuple[int, int, int, int]:
    source = Image.open(SOURCE).convert("RGB")
    target_width = box[2] - box[0]
    target_height = box[3] - box[1]
    fitted = ImageOps.contain(source, (target_width, target_height), Image.Resampling.LANCZOS)
    left = box[0] + (target_width - fitted.width) // 2
    top = box[1] + (target_height - fitted.height) // 2
    frame = (left - 8, top - 8, left + fitted.width + 8, top + fitted.height + 8)
    ImageDraw.Draw(image).rounded_rectangle(frame, radius=12, fill=(255, 255, 255), outline=INK, width=3)
    image.paste(fitted, (left, top))
    return left, top, left + fitted.width, top + fitted.height


def footer(draw: ImageDraw.ImageDraw) -> None:
    draw.line((56, 1610, 330, 1610), fill=LINE, width=2)
    draw.line((912, 1610, 1186, 1610), fill=LINE, width=2)
    center_text(
        draw,
        (330, 1584, 912, 1638),
        "基于 James Zhu 的 ReadAssist 二次开发（MIT）· 非官方版本",
        20,
        MUTED,
    )


def feature_card() -> None:
    image, draw = canvas()
    header(draw, "01", "新版功能")
    draw.text((58, 100), "Supernote", font=font(82, True), fill=INK)
    draw.text((58, 190), "页内", font=font(92, True), fill=INK)
    draw.text((270, 190), "生词", font=font(92, True), fill=ORANGE)
    draw.text((470, 190), "提示", font=font(92, True), fill=INK)
    draw.text((62, 300), "双击「译」，英文难词直接显示中文释义", font=font(36, True), fill=INK)
    draw.line((62, 355, 600, 355), fill=ORANGE, width=8)

    place_screenshot(image, (242, 390, 1000, 1370))

    labels = ["按水平过滤", "每页最多 20 个", "一键隐藏 / 显示", "离线识别，不调用 AI"]
    card_width = 270
    gap = 18
    left = 54
    for index, label in enumerate(labels):
        box = (left + index * (card_width + gap), 1392, left + index * (card_width + gap) + card_width, 1566)
        rounded(draw, box, 18)
        center_text(draw, (box[0], box[1] + 12, box[2], box[1] + 62), f"0{index + 1}", 25, ORANGE, True)
        center_text(draw, (box[0] + 10, box[1] + 48, box[2] - 10, box[3] - 8), label, 25, INK, True)
    footer(draw)
    image.save(OUTPUT / "01-新版生词提示.png", quality=95)


def usage_card() -> None:
    image, draw = canvas()
    header(draw, "02", "使用方法")
    center_text(draw, (50, 88, 1192, 200), "「译」怎么用？", 78, INK, True)
    draw.rounded_rectangle((54, 216, 1188, 320), radius=22, fill=INK)
    center_text(draw, (60, 220, 620, 316), "单击：打开操作页", 31, (255, 255, 255), True)
    draw.line((621, 238, 621, 298), fill=(255, 255, 255), width=2)
    center_text(draw, (622, 220, 1182, 316), "双击：按当前模式执行", 31, (255, 255, 255), True)

    place_screenshot(image, (58, 350, 760, 1286))
    steps = [
        ("1", "单击词典按钮", "打开操作页"),
        ("2", "选择「生词提示」", "按钮变为「译」"),
        ("3", "双击「译」", "扫描并标注当前页"),
        ("4", "隐藏 / 显示", "右下角随时切换"),
    ]
    top = 365
    for number, title, body in steps:
        box = (790, top, 1184, top + 188)
        rounded(draw, box, 20)
        draw.ellipse((812, top + 22, 882, top + 92), fill=ORANGE)
        center_text(draw, (812, top + 22, 882, top + 92), number, 32, (255, 255, 255), True)
        draw.text((904, top + 24), title, font=font(30, True), fill=INK)
        draw.text((904, top + 78), body, font=font(24), fill=MUTED)
        top += 208

    rounded(draw, (58, 1320, 1184, 1548), 22)
    draw.text((92, 1350), "翻页后再次双击「译」", font=font(36, True), fill=ORANGE)
    draw.text((92, 1418), "不会自动扫描，也不会后台连续截屏", font=font(31, True), fill=INK)
    draw.text((92, 1480), "旧页标注会在新一轮扫描开始时清除", font=font(25), fill=MUTED)
    footer(draw)
    image.save(OUTPUT / "02-译功能使用方法.png", quality=95)


def settings_card() -> None:
    image, draw = canvas()
    header(draw, "03", "设置建议")
    draw.text((58, 100), "两处设置，", font=font(74, True), fill=INK)
    draw.text((58, 190), "生词提示更清爽", font=font(74, True), fill=INK)
    draw.line((60, 286, 715, 286), fill=ORANGE, width=8)

    rounded(draw, (56, 340, 1186, 720), 26)
    draw.ellipse((88, 380, 174, 466), fill=ORANGE)
    center_text(draw, (88, 380, 174, 466), "1", 38, (255, 255, 255), True)
    draw.text((210, 380), "设置 → 生词提示起点", font=font(38, True), fill=INK)
    draw.text((210, 442), "选择适合自己的最低词汇级别", font=font(28), fill=MUTED)
    levels = ["小学", "初中", "高中", "四级", "六级", "考研", "雅思 / 托福"]
    x, y = 210, 520
    for level in levels:
        width = 150 if level != "雅思 / 托福" else 230
        draw.rounded_rectangle((x, y, x + width, y + 76), radius=18, fill=(255, 255, 255), outline=LINE, width=2)
        center_text(draw, (x, y, x + width, y + 76), level, 25, INK, True)
        x += width + 18
        if x > 1000:
            x = 210
            y += 96

    rounded(draw, (56, 752, 1186, 1110), 26)
    draw.ellipse((88, 792, 174, 878), fill=ORANGE)
    center_text(draw, (88, 792, 174, 878), "2", 38, (255, 255, 255), True)
    draw.text((210, 792), "阅读器 → 行间距", font=font(38, True), fill=INK)
    draw.text((210, 854), "建议选择最宽，为中文释义留出空间", font=font(28), fill=MUTED)
    for index, gap in enumerate((20, 34, 52)):
        left = 220 + index * 290
        box = (left, 930, left + 240, 1058)
        draw.rounded_rectangle(
            box,
            radius=16,
            fill=(255, 255, 255),
            outline=ORANGE if index == 2 else LINE,
            width=4 if index == 2 else 2,
        )
        line_y = 952
        for _ in range(3):
            draw.line((left + 30, line_y, left + 210, line_y), fill=INK, width=4)
            line_y += gap
        label = ("较窄", "适中", "最宽")[index]
        center_text(draw, (left, 1010, left + 240, 1060), label, 22, ORANGE if index == 2 else MUTED, True)

    rounded(draw, (56, 1144, 1186, 1510), 26)
    draw.text((90, 1180), "使用原则", font=font(34, True), fill=ORANGE)
    draw.text((90, 1250), "• 每页最多 20 个生词", font=font(31, True), fill=INK)
    draw.text((90, 1312), "• 等级越低，提示通常越多", font=font(31, True), fill=INK)
    draw.text((90, 1374), "• 建议先从「高中」或「四级」开始", font=font(31, True), fill=INK)
    draw.text((90, 1436), "• 识别、分级和释义全部离线完成", font=font(31, True), fill=INK)
    footer(draw)
    image.save(OUTPUT / "03-生词提示设置.png", quality=95)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    feature_card()
    usage_card()
    settings_card()
    print("Created 3 deterministic Xiaohongshu cards")


if __name__ == "__main__":
    main()
