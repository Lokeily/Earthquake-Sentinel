# -*- coding: utf-8 -*-
import sys, os, subprocess, re, html, json

CHROME = r"C:/Program Files/Google/Chrome/Application/chrome.exe"
DOCS = os.path.dirname(os.path.abspath(__file__))
PAGES = ["index", "features", "how", "changelog", "disclaimer"]

AUDIT_JS = r"""
<style>.reveal,.reveal.from-left,.reveal.from-right,.reveal.zoom{opacity:1!important;transform:none!important}</style>
<script>
document.addEventListener('DOMContentLoaded', function(){
  function audit(){
    try {
      var w = window.innerWidth;
      var bw = document.documentElement.scrollWidth;
      var overflow = bw - w;
      var bad = [];
      document.querySelectorAll('*').forEach(function(el){
        var r = el.getBoundingClientRect();
        if (r.width > 0 && (r.right > w + 1.5 || r.left < -1.5)) {
          var cls = (el.className && el.className.toString) ? el.className.toString() : el.tagName;
          bad.push(cls.slice(0,60) + ' L=' + Math.round(r.left) + ' R=' + Math.round(r.right) + ' W=' + Math.round(r.width));
        }
      });
      function top(sel){ var e=document.querySelector(sel); if(!e) return 'none'; var r=e.getBoundingClientRect(); return Math.round(r.top)+'/'+Math.round(r.bottom); }
      var pre = document.createElement('pre');
      pre.id='__audit';
      pre.textContent = 'WIN=' + w + ' SCROLLW=' + bw + ' OVERFLOW=' + overflow + '\n'
        + 'hero-content.top=' + top('.hero-content') + ' hero-device.top=' + top('.hero-device') + '\n'
        + bad.slice(0,25).join('\n');
      document.body.appendChild(pre);
    } catch(e){ var p=document.createElement('pre'); p.id='__audit'; p.textContent='ERR '+e.message; document.body.appendChild(p); }
  }
  setTimeout(audit, 700);
});
</script>
"""

def build(page):
    src = os.path.join(DOCS, page + ".html")
    with open(src, encoding="utf-8") as f:
        content = f.read()
    if "</body>" in content:
        content = content.replace("</body>", AUDIT_JS + "</body>", 1)
    else:
        content = content + AUDIT_JS
    out = os.path.join(DOCS, "_audit_" + page + ".html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(content)
    return out

def run(page, width):
    out = build(page)
    url = "file:///" + os.path.abspath(out).replace("\\", "/")
    cmd = [CHROME, "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
           "--virtual-time-budget=4000", "--window-size=%d,800" % width,
           "--dump-dom", url]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    m = re.search(r'id="__audit"[^>]*>(.*?)</pre>', r.stdout, re.S)
    if m:
        return html.unescape(m.group(1))
    # fallback: show tail
    return "NO_AUDIT dump_len=%d err=%s" % (len(r.stdout), r.stderr[-300:])

if __name__ == "__main__":
    widths = [1280, 390]
    for page in PAGES:
        for w in widths:
            try:
                res = run(page, w)
            except Exception as e:
                res = "EXC " + str(e)
            print("===== [%s @ %d] =====" % (page, w))
            print(res)
