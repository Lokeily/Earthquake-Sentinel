import os, glob, codecs
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools.subset import Subsetter, Options

SRC = r"C:\Windows\Fonts\NotoSansSC-VF.ttf"
OUT = r"app/src/main/res/font/dianguard_zh.ttf"

# 1) Load variable font, instance to Regular (wght=400) -> static TTF
font = TTFont(SRC)
instancer.instantiateVariableFont(font, {"wght": 400}, inplace=True)

# 2) Unicode coverage set
u = set()
u |= set(range(0x20, 0x7F))            # ASCII printable
u |= set(range(0xA0, 0x100))          # Latin-1 Supplement (degree sign, +/-/x ...)
u |= set(range(0x2000, 0x2070))       # General Punctuation (— – … etc.)
u |= set(range(0x3000, 0x3040))       # CJK Symbols and Punctuation
u |= set(range(0xFF00, 0xFFF0))       # Fullwidth Forms

# GB2312 common Simplified-Chinese set (~6,763 hanzi + symbols). Covers virtually
# all place names / everyday text; rare chars fall back to the system font gracefully.
for lead in range(0xA1, 0xFF):
    for trail in range(0xA1, 0xFF):
        try:
            s = bytes([lead, trail]).decode("gb2312")
            for ch in s:
                u.add(ord(ch))
        except Exception:
            pass

# Add every character already used anywhere in the app (resources + code) => 100% UI coverage
root = "app/src/main"
text = ""
for f in glob.glob(os.path.join(root, "**", "*"), recursive=True):
    if os.path.isfile(f):
        try:
            text += open(f, encoding="utf-8", errors="ignore").read()
        except Exception:
            pass
for ch in text:
    cp = ord(ch)
    if cp > 0x1F:
        u.add(cp)

# 3) Subset (drop hints + glyph names to shrink)
options = Options()
options.hinting = False
options.glyph_names = False
options.notdef_outline = True
options.recalc_bounds = True
ss = Subsetter(options=options)
ss.populate(unicodes=u)
ss.subset(font)

font.save(OUT)
print("unicode coverage:", len(u))
print("glyph count:", len(font.getGlyphOrder()))
sz = os.path.getsize(OUT)
print("output size: %.2f MB" % (sz / 1024.0 / 1024.0))
