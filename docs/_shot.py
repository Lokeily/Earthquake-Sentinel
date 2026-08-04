# -*- coding: utf-8 -*-
# Full-page screenshot with .reveal forced visible so all sections show.
import os, subprocess

CHROME = r"C:/Program Files/Google/Chrome/Application/chrome.exe"
DOCS = r"C:/Users/nsklr/WorkBuddy/2026-08-01-14-49-09/Dianguard/docs"
FORCE = '<style>.reveal,.reveal.from-left,.reveal.from-right,.reveal.zoom{opacity:1!important;transform:none!important}</style>'

def shot(page, h):
    src = open(os.path.join(DOCS, page + ".html"), encoding="utf-8").read()
    src = src.replace("</head>", FORCE + "</head>", 1) if "</head>" in src else FORCE + src
    tmp = os.path.join(DOCS, "_shot_" + page + ".html")
    open(tmp, "w", encoding="utf-8").write(src)
    url = "file:///" + os.path.abspath(tmp).replace("\\", "/")
    os.makedirs(os.path.join(DOCS, "_shots"), exist_ok=True)
    out = os.path.join(DOCS, "_shots", page + "_full.png")
    cmd = [CHROME, "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
           "--virtual-time-budget=5000", "--window-size=1280,%d" % h,
           "--screenshot=" + out, url]
    subprocess.run(cmd, capture_output=True, text=True, timeout=150)
    print("saved", out, os.path.getsize(out), "bytes")

if __name__ == "__main__":
    shot("how", 3700)
    shot("index", 3300)
