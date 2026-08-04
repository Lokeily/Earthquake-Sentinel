# -*- coding: utf-8 -*-
# Scrolls into each key section and reports TRUE computed opacity / bounding box,
# WITHOUT forcing .reveal visible (so we test the real reveal-on-scroll logic).
import sys, os, subprocess, re, html

CHROME = r"C:/Program Files/Google/Chrome/Application/chrome.exe"
DOCS = os.path.dirname(os.path.abspath(__file__))

VERIFY_JS = r"""
<script>
(async function(){
  function scrollToEl(el){ if(el) el.scrollIntoView({block:'center'}); }
  function sleep(ms){ return new Promise(r=>setTimeout(r,ms)); }
  function op(el){ return el?getComputedStyle(el).opacity:'none'; }
  function box(el){ if(!el) return 'none'; var r=el.getBoundingClientRect(); return Math.round(r.width)+'x'+Math.round(r.height); }
  async function main(){
    var res={};
    var pairs=[
      ['.timeline','timeline.op'], ['.intensity-vis','intensity.op'],
      ['.blindzone','blindzone.op'], ['.flow','flow.op'],
      ['.reliability-graph','reliability.op'],
      ['.rg-lines','rg-lines.box'], ['.rg-node','rg-node.box'],
      ['.rg-hub-svg','rg-hub.box'],
      ['.vs-speaker','vs-speaker.box'], ['.vs-wave','vs-wave.box']
    ];
    for(var i=0;i<pairs.length;i++){
      var sel=pairs[i][0], key=pairs[i][1];
      var el=document.querySelector(sel);
      if(el){ scrollToEl(el); await sleep(550); }
      var e2=document.querySelector(sel);
      if(!e2){ res[key]='MISSING'; continue; }
      res[key] = (key.indexOf('.box')>=0) ? box(e2) : op(e2);
    }
    var pre=document.createElement('pre'); pre.id='__verify';
    pre.textContent=JSON.stringify(res); document.body.appendChild(pre);
  }
  if(document.readyState!=='loading') main(); else document.addEventListener('DOMContentLoaded', main);
})();
</script>
"""

def build(page):
    src = os.path.join(DOCS, page + ".html")
    c = open(src, encoding="utf-8").read()
    c = c.replace("</body>", VERIFY_JS + "</body>", 1) if "</body>" in c else c + VERIFY_JS
    out = os.path.join(DOCS, "_verify_" + page + ".html")
    open(out, "w", encoding="utf-8").write(c)
    return out

def run(page):
    out = build(page)
    url = "file:///" + os.path.abspath(out).replace("\\", "/")
    cmd = [CHROME, "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
           "--virtual-time-budget=9000", "--window-size=1280,900", "--dump-dom", url]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    m = re.search(r'id="__verify"[^>]*>(.*?)</pre>', r.stdout, re.S)
    return html.unescape(m.group(1)) if m else "NO_VERIFY dump_len=%d err=%s" % (len(r.stdout), r.stderr[-400:])

if __name__ == "__main__":
    for page in sys.argv[1:]:
        print("===== %s =====" % page)
        print(run(page))
