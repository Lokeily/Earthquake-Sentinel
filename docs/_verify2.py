# -*- coding: utf-8 -*-
# Force .reveal visible (settled layout) and report real dimensions + JS-drawn
# graphic content for how.html sections, and homepage SVG rendering.
import sys, os, subprocess, re, html

CHROME = r"C:/Program Files/Google/Chrome/Application/chrome.exe"
DOCS = os.path.dirname(os.path.abspath(__file__))

VERIFY_JS = r"""
<style>.reveal,.reveal.from-left,.reveal.from-right,.reveal.zoom{opacity:1!important;transform:none!important}</style>
<script>
window.addEventListener('load', function(){
  setTimeout(function(){
    function box(sel){ var e=document.querySelector(sel); if(!e) return 'MISSING'; var r=e.getBoundingClientRect(); return Math.round(r.width)+'x'+Math.round(r.height); }
    function attr(sel,a){ var e=document.querySelector(sel); return e?(e.getAttribute(a)||'').length:'MISSING'; }
    function count(sel){ return document.querySelectorAll(sel).length; }
    var res = {
      'how.timeline': box('.timeline'),
      'how.intensityVis': box('#intensity-vis'),
      'how.ivCurve_d_len': attr('.intensity-vis .iv-curve','d'),
      'how.ivArea_d_len': attr('.intensity-vis .iv-area','d'),
      'how.ivMarker': box('.intensity-vis .iv-marker'),
      'how.blindzone': box('.blindzone'),
      'how.bzGraphChildren': count('.blindzone .bz-graph *'),
      'how.flow': box('.flow'),
      'how.flowPkt': box('.flow .flow-pkt'),
      'how.flowNodes': count('.flow .flow-node'),
      'idx.rgLines': box('.rg-lines'),
      'idx.rgHubSvg': box('.rg-hub-svg'),
      'idx.vsSpeaker': box('.vs-speaker'),
      'idx.vsWave': box('.vs-wave'),
      'idx.reliabilityText': count('.reliability-list li')
    };
    var pre=document.createElement('pre'); pre.id='__v2'; pre.textContent=JSON.stringify(res); document.body.appendChild(pre);
  }, 600);
});
</script>
"""

def build(page):
    src = os.path.join(DOCS, page + ".html")
    c = open(src, encoding="utf-8").read()
    c = c.replace("</body>", VERIFY_JS + "</body>", 1) if "</body>" in c else c + VERIFY_JS
    out = os.path.join(DOCS, "_verify2_" + page + ".html")
    open(out, "w", encoding="utf-8").write(c)
    return out

def run(page):
    out = build(page)
    url = "file:///" + os.path.abspath(out).replace("\\", "/")
    cmd = [CHROME, "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
           "--virtual-time-budget=6000", "--window-size=1280,900", "--dump-dom", url]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    m = re.search(r'id="__v2"[^>]*>(.*?)</pre>', r.stdout, re.S)
    return html.unescape(m.group(1)) if m else "NO_V2 dump_len=%d err=%s" % (len(r.stdout), r.stderr[-400:])

if __name__ == "__main__":
    for page in sys.argv[1:]:
        print("===== %s =====" % page)
        print(run(page))
