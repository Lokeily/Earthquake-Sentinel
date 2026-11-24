(function () {
  'use strict';

  /* ---------- 1. 波纹背景动画 ---------- */
  const canvas = document.getElementById('wave-canvas');
  if (canvas) {
    const ctx = canvas.getContext('2d');
    let width, height;
    const waves = [];

    function resize() {
      width = canvas.width = window.innerWidth;
      height = canvas.height = window.innerHeight;
    }
    window.addEventListener('resize', resize);
    resize();

    class Wave {
      constructor() { this.reset(); this.y = Math.random() * height; }
      reset() {
        this.x = -Math.random() * 200;
        this.y = Math.random() * height;
        this.speed = 0.4 + Math.random() * 0.8;
        this.amplitude = 20 + Math.random() * 40;
        this.wavelength = 120 + Math.random() * 180;
        this.opacity = 0.05 + Math.random() * 0.12;
        this.hue = Math.random() > 0.7 ? 0 : 210;
      }
      update() {
        this.x += this.speed;
        if (this.x - this.wavelength > width) this.reset();
      }
      draw() {
        ctx.beginPath();
        const color = this.hue === 0
          ? `rgba(255, 59, 48, ${this.opacity})`
          : `rgba(10, 132, 255, ${this.opacity})`;
        ctx.strokeStyle = color;
        ctx.lineWidth = 1.5;
        for (let px = 0; px <= width; px += 6) {
          const dy = Math.sin((px - this.x) / this.wavelength * Math.PI * 2) * this.amplitude;
          if (px === 0) ctx.moveTo(px, this.y + dy);
          else ctx.lineTo(px, this.y + dy);
        }
        ctx.stroke();
      }
    }
    for (let i = 0; i < 8; i++) waves.push(new Wave());

    (function animate() {
      ctx.clearRect(0, 0, width, height);
      waves.forEach(w => { w.update(); w.draw(); });
      requestAnimationFrame(animate);
    })();
  }

  /* ---------- 2. 首屏倒计时进度环 ---------- */
  const countEl = document.querySelector('.alert-eta .count');
  const ringFgEl = document.querySelector('.eta-ring-fg');
  const C = 2 * Math.PI * 44; // r=44
  let cd = 12;
  function setCountdown(n) {
    if (countEl) countEl.textContent = n;
    if (ringFgEl) ringFgEl.style.strokeDashoffset = (C * (1 - n / 12)).toFixed(2);
  }
  setCountdown(12);
  setInterval(() => { cd = cd <= 0 ? 12 : cd - 1; setCountdown(cd); }, 1000);

  /* ---------- 3. 滚动进度条 + 导航栏悬浮态 ---------- */
  const progressBar = document.querySelector('.scroll-progress i');
  const siteNav = document.getElementById('site-nav');
  // 时间轴滚动联动引用（仅技术原理页）
  let tlFill = null, tlRunner = null, tlSection = null;

  function onScroll() {
    const doc = document.documentElement;
    const max = doc.scrollHeight - doc.clientHeight;
    const scrolled = doc.scrollTop || document.body.scrollTop || window.scrollY || 0;
    if (progressBar) {
      const p = max > 0 ? scrolled / max : 0;
      progressBar.style.width = (Math.min(1, Math.max(0, p)) * 100).toFixed(2) + '%';
    }
    if (siteNav) siteNav.classList.toggle('scrolled', scrolled > 12);

    // 时间轴：进度随滚动填充 + 光点行进
    if (tlSection && tlFill) {
      const r = tlSection.getBoundingClientRect();
      const vh = window.innerHeight;
      let p = (vh - r.top) / (vh + r.height);
      p = Math.max(0, Math.min(1, p));
      tlFill.style.width = (p * 100).toFixed(1) + '%';
      if (tlRunner) {
        tlRunner.style.left = (p * 100).toFixed(1) + '%';
        if (p > 0.02 && p < 0.99) tlSection.classList.add('running');
        else tlSection.classList.remove('running');
      }
    }
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* ---------- 4. 移动端导航菜单 ---------- */
  const navToggle = document.getElementById('nav-toggle');
  const navLinks = document.getElementById('nav-links');
  if (navToggle && navLinks) {
    navToggle.addEventListener('click', () => {
      const open = navLinks.classList.toggle('open');
      navToggle.classList.toggle('open', open);
      navToggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    navLinks.querySelectorAll('a').forEach(a => a.addEventListener('click', () => {
      navLinks.classList.remove('open');
      navToggle.classList.remove('open');
      navToggle.setAttribute('aria-expanded', 'false');
    }));
  }

  /* ---------- 5. 光标跟随高光 (spotlight) ---------- */
  const spotEls = document.querySelectorAll(
    '.feature-card, .source-card, .level-row, .perm-card, .stat-cell, ' +
    '.arch-node, .tl-step, .flow-node, .callout, .formula-card, .release-card'
  );
  spotEls.forEach(el => el.classList.add('spot'));
  document.addEventListener('mousemove', (e) => {
    const s = e.target.closest && e.target.closest('.spot');
    if (s) {
      const r = s.getBoundingClientRect();
      s.style.setProperty('--mx', (e.clientX - r.left) + 'px');
      s.style.setProperty('--my', (e.clientY - r.top) + 'px');
    }
  }, { passive: true });

  /* ---------- 6. 数字滚动计数 ---------- */
  function animateCount(el) {
    if (el.dataset.counted) return;
    el.dataset.counted = '1';
    const target = parseFloat(el.getAttribute('data-count')) || 0;
    const decimals = parseInt(el.getAttribute('data-decimal') || '0', 10);
    const suffix = el.getAttribute('data-suffix') || '';
    const dur = 1500;
    const start = performance.now();
    (function tick(now) {
      const t = Math.min(1, (now - start) / dur);
      const eased = 1 - Math.pow(1 - t, 3);
      el.textContent = (target * eased).toFixed(decimals) + suffix;
      if (t < 1) requestAnimationFrame(tick);
      else el.textContent = target.toFixed(decimals) + suffix;
    })(start);
  }

  /* ---------- 7. 滚动渐显 + 计数触发 ---------- */
  const REVEAL_SELECTOR = [
    '.fade-block', '.fx-block', '.fx-row', '.fx-copy', '.fx-visual',
    '.feature-card', '.source-card', '.level-row', '.perm-card', '.stat-cell',
    '.stat-strip', '.arch', '.arch-node', '.tl-step', '.tl-track', '.wave-demo',
    '.formula-card', '.bz-graph', '.bz-legend', '.flow-node', '.flow-split',
    '.callout', '.cmp-table', '.cta-inner', '.how-step', '.how-arrow',
    '.reliability-text', '.reliability-graph', '.release-card', '.disclaimer-block',
    '.review-card', '.download-version', '.phone.small', '.step', '.faq-item',
    '.voice-seq', '.vs-item', '.screen-showcase',
    '.timeline', '.blindzone', '.flow', '.intensity-vis'
  ].join(', ');

  const revealTargets = document.querySelectorAll(REVEAL_SELECTOR);
  revealTargets.forEach(el => el.classList.add('reveal'));

  // 方向性提示
  document.querySelectorAll('.fx-visual, .tl-track, .voice-visual, .reliability-graph, .screen-showcase')
    .forEach(el => el.classList.add('from-right'));
  document.querySelectorAll('.fx-copy, .reliability-text, .voice-text')
    .forEach(el => el.classList.add('from-left'));

  const io = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (!entry.isIntersecting) return;
      entry.target.classList.add('revealed');
      entry.target.querySelectorAll('[data-count]').forEach(animateCount);
      if (entry.target.matches('[data-count]')) animateCount(entry.target);
      io.unobserve(entry.target);
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' });

  revealTargets.forEach(el => io.observe(el));

  /* ---------- 7.5 网格卡片交错入场（stagger）----------
     同一网格容器内的卡片按序延迟显现，视觉更有节奏；
     显现完成后移除行内延迟，避免影响 hover 等后续过渡。 */
  document.querySelectorAll(
    '.source-grid, .feature-grid, .faq-grid, .how-grid, .install-steps'
  ).forEach(grid => {
    Array.from(grid.children).forEach((child, i) => {
      if (i === 0) return;
      child.style.transitionDelay = Math.min(i * 70, 420) + 'ms';
      child.addEventListener('transitionend', function onEnd(ev) {
        if (ev.propertyName !== 'opacity') return;
        child.style.transitionDelay = '';
        child.removeEventListener('transitionend', onEnd);
      });
    });
  });

  /* ---------- 8. 子页锚点导航高亮 ---------- */
  const subnav = document.getElementById('subnav');
  if (subnav) {
    const links = Array.from(subnav.querySelectorAll('a'));
    const map = {};
    links.forEach(l => {
      const href = l.getAttribute('href') || '';
      if (href.charAt(0) === '#') {
        const sec = document.getElementById(href.slice(1));
        if (sec) map[href.slice(1)] = l;
      }
    });
    const subIO = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          links.forEach(l => l.classList.remove('active'));
          const l = map[entry.target.id];
          if (l) l.classList.add('active');
        }
      });
    }, { rootMargin: '-45% 0px -50% 0px' });
    Object.keys(map).forEach(id => {
      const s = document.getElementById(id);
      if (s) subIO.observe(s);
    });
  }

  /* ---------- 8.5 技术原理页动效（仅 how.html） ---------- */
  function initHowPage() {
    const reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    tlFill = document.querySelector('.page-how #timeline .tl-fill');
    tlRunner = document.querySelector('.page-how #timeline .tl-runner');
    tlSection = document.querySelector('.page-how #timeline');

    initWaveDemo(reduce);
    initIntensityVis(reduce);
    initArchPackets(reduce);
    initDecisionFlow(reduce);
  }

  // 波纹 demo：P/S 波前扩张 + 你处受击 + 计时
  function initWaveDemo(reduce) {
    const wrap = document.getElementById('wave-demo');
    if (!wrap) return;
    const canvas = wrap.querySelector('.wd-canvas');
    const youEl = wrap.querySelector('.wd-you');
    const timeEl = wrap.querySelector('.wd-time b');
    if (!canvas || !timeEl) return;
    const ctx = canvas.getContext('2d');
    let W = 0, H = 0, dpr = 1;

    function resize() {
      const r = wrap.getBoundingClientRect();
      W = r.width; H = r.height;
      dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.max(1, Math.round(W * dpr));
      canvas.height = Math.max(1, Math.round(H * dpr));
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    function ecX() { return W * 0.13; }
    function youX() { return W * 0.88; }
    function cy() { return H * 0.5; }

    const D_KM = 480, P_SPEED = 6, S_SPEED = 3.5;
    const T_LOOP = 7.0;
    const T_P = T_LOOP * (S_SPEED / P_SPEED);
    const ppk = () => Math.abs(youX() - ecX()) / D_KM;

    let pBirths = [], sBirths = [];
    for (let b = 0; b <= T_LOOP; b += 1.0) pBirths.push(b);
    for (let b = 0; b <= T_LOOP; b += 1.7) sBirths.push(b);

    function drawRing(cx, cyy, radius, type, frac) {
      if (radius <= 0.5) return;
      ctx.beginPath();
      ctx.arc(cx, cyy, radius, 0, Math.PI * 2);
      if (type === 'p') {
        ctx.strokeStyle = 'rgba(10,132,255,' + (0.5 * (1 - frac) + 0.05).toFixed(3) + ')';
        ctx.lineWidth = 1.6;
      } else {
        ctx.strokeStyle = 'rgba(255,59,48,' + (0.7 * (1 - frac) + 0.07).toFixed(3) + ')';
        ctx.lineWidth = 3;
      }
      ctx.stroke();
    }

    let t = 0, last = 0, running = false, arrived = false;

    function frame(now) {
      if (!running) return;
      const dt = Math.min(0.05, (now - last) / 1000); last = now;
      t += dt;
      if (t > T_LOOP) {
        t = 0; arrived = false;
        wrap.classList.remove('hit');
        if (youEl) youEl.classList.remove('hit');
      }
      ctx.clearRect(0, 0, W, H);
      const cx = ecX(), cyy = cy(), dist = youX() - cx, k = ppk();
      pBirths.forEach(b => {
        const age = t - b; if (age < 0) return;
        const frac = Math.min(1, age / T_P);
        drawRing(cx, cyy, dist * frac * 1.05, 'p', frac);
      });
      sBirths.forEach(b => {
        const age = t - b; if (age < 0) return;
        const frac = Math.min(1, age / T_LOOP);
        drawRing(cx, cyy, dist * frac * 1.05, 's', frac);
      });
      if (!arrived && t >= T_LOOP - 0.001) {
        arrived = true;
        wrap.classList.add('hit');
        if (youEl) youEl.classList.add('hit');
      }
      if (timeEl) timeEl.textContent = (t / T_LOOP * (D_KM / S_SPEED)).toFixed(1);
      requestAnimationFrame(frame);
    }

    resize();
    window.addEventListener('resize', resize);

    if (reduce) {
      const cx = ecX(), cyy = cy(), dist = youX() - cx;
      ctx.clearRect(0, 0, W, H);
      drawRing(cx, cyy, dist * 0.6, 'p', 0.6);
      drawRing(cx, cyy, dist * 0.9, 's', 0.9);
      if (timeEl) timeEl.textContent = '0.0';
      return;
    }

    const io = new IntersectionObserver((entries) => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          if (!running) { running = true; last = performance.now(); requestAnimationFrame(frame); }
        } else { running = false; }
      });
    }, { threshold: 0.12 });
    io.observe(wrap);
  }

  // 烈度曲线：绘制 + 扫描标记
  function initIntensityVis(reduce) {
    const vis = document.getElementById('intensity-vis');
    if (!vis) return;
    const svg = vis.querySelector('.iv-svg');
    const curve = vis.querySelector('.iv-curve');
    const area = vis.querySelector('.iv-area');
    const marker = vis.querySelector('.iv-marker');
    const vline = vis.querySelector('.iv-vline');
    const valEl = vis.querySelector('.iv-value');
    const subEl = vis.querySelector('.iv-sub');
    if (!svg || !curve || !marker) return;

    const M = 6.5, R_MIN = 5, R_MAX = 600;
    const X0 = 38, X1 = 344, Y0 = 210, Y1 = 22, IMIN = 0, IMAX = 9.2;
    const Iof = (R) => 2.941 + 1.363 * M - 1.494 * Math.log(R + 7);
    const xOf = (R) => X0 + (R - R_MIN) / (R_MAX - R_MIN) * (X1 - X0);
    const yOf = (I) => Y0 - (I - IMIN) / (IMAX - IMIN) * (Y0 - Y1);

    let d = '';
    const STEPS = 80;
    for (let i = 0; i <= STEPS; i++) {
      const R = R_MIN + (R_MAX - R_MIN) * i / STEPS;
      d += (i === 0 ? 'M' : 'L') + xOf(R).toFixed(1) + ' ' + yOf(Iof(R)).toFixed(1) + ' ';
    }
    d = d.trim();
    curve.setAttribute('d', d);
    area.setAttribute('d', d + 'L' + X1.toFixed(1) + ' ' + Y0 + ' L' + X0.toFixed(1) + ' ' + Y0 + ' Z');

    const L = curve.getTotalLength();
    curve.style.strokeDasharray = L;
    curve.style.strokeDashoffset = reduce ? 0 : L;

    function setMarker(R) {
      const x = xOf(R), y = yOf(Iof(R));
      marker.setAttribute('cx', x.toFixed(1));
      marker.setAttribute('cy', y.toFixed(1));
      vline.setAttribute('x1', x.toFixed(1));
      vline.setAttribute('x2', x.toFixed(1));
      const I = Iof(R);
      if (valEl) valEl.textContent = I.toFixed(1);
      if (subEl) {
        let q = '微震感';
        if (I >= 6) q = '强 · 需避险'; else if (I >= 4) q = '中 · 明显'; else if (I >= 2) q = '弱 · 有感';
        subEl.textContent = '震级 M ' + M + ' · 距离 R ' + Math.round(R) + ' km · ' + q;
      }
    }

    if (reduce) { setMarker(120); return; }

    let raf = null, running = false, start = 0;
    const DUR = 6000;
    function loop(now) {
      if (!running) return;
      const p = ((now - start) % DUR) / DUR;
      setMarker(R_MAX - (R_MAX - R_MIN) * p);
      raf = requestAnimationFrame(loop);
    }
    const io = new IntersectionObserver((entries) => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          curve.style.strokeDashoffset = 0;
          if (!running) { running = true; start = performance.now(); raf = requestAnimationFrame(loop); }
        } else { running = false; if (raf) cancelAnimationFrame(raf); }
      });
    }, { threshold: 0.2 });
    io.observe(vis);
  }

  // 架构数据流：沿路径行进的光点包
  function initArchPackets(reduce) {
    const arch = document.querySelector('.page-how .arch');
    if (!arch) return;
    const svg = arch.querySelector('.arch-flow svg');
    if (!svg) return;
    const paths = svg.querySelectorAll('path');
    if (!paths.length) return;
    const NS = 'http://www.w3.org/2000/svg';
    const packets = [];
    paths.forEach(p => {
      const len = p.getTotalLength();
      const c = document.createElementNS(NS, 'circle');
      c.setAttribute('r', '3.4');
      c.setAttribute('class', 'arch-pkt');
      svg.appendChild(c);
      packets.push({ p: p, c: c, len: len, off: Math.random() });
    });
    if (reduce) {
      packets.forEach(pk => { const pt = pk.p.getPointAtLength(0); pk.c.setAttribute('cx', pt.x); pk.c.setAttribute('cy', pt.y); });
      return;
    }
    let raf = null, running = false, start = 0;
    const SPEED = 0.12;
    function loop(now) {
      if (!running) return;
      const tt = (now - start) / 1000;
      packets.forEach(pk => {
        const f = (pk.off + tt * SPEED) % 1;
        const pt = pk.p.getPointAtLength(f * pk.len);
        pk.c.setAttribute('cx', pt.x);
        pk.c.setAttribute('cy', pt.y);
      });
      raf = requestAnimationFrame(loop);
    }
    const io = new IntersectionObserver((entries) => {
      entries.forEach(e => {
        if (e.isIntersecting) { if (!running) { running = true; start = performance.now(); raf = requestAnimationFrame(loop); } }
        else { running = false; if (raf) cancelAnimationFrame(raf); }
      });
    }, { threshold: 0.25 });
    io.observe(arch);
  }

  // 决策流程：当前步高亮 + 行进光点
  function initDecisionFlow(reduce) {
    const flow = document.querySelector('.page-how .flow');
    if (!flow) return;
    const nodes = flow.querySelectorAll('.flow-node');
    const io = new IntersectionObserver((entries) => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          nodes.forEach(n => n.classList.remove('active'));
          e.target.classList.add('active');
        }
      });
    }, { rootMargin: '-45% 0px -45% 0px', threshold: 0 });
    nodes.forEach(n => io.observe(n));

    if (reduce) return;
    const pkt = flow.querySelector('.flow-pkt');
    if (!pkt) return;
    let raf = null, running = false;
    function loop() {
      if (!running) return;
      const r = flow.getBoundingClientRect();
      let p = (window.innerHeight * 0.5 - r.top) / r.height;
      p = Math.max(0, Math.min(1, p));
      pkt.style.top = (p * r.height) + 'px';
      raf = requestAnimationFrame(loop);
    }
    const vio = new IntersectionObserver((entries) => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          flow.classList.add('running');
          if (!running) { running = true; raf = requestAnimationFrame(loop); }
        } else { flow.classList.remove('running'); running = false; if (raf) cancelAnimationFrame(raf); }
      });
    }, { threshold: 0.15 });
    vio.observe(flow);
  }

  /* ---------- 9. GitHub Release 实时同步 ---------- */
  const REPO = 'Lokeily/Earthquake-Sentinel';
  const RELEASES_URL = 'https://api.github.com/repos/' + REPO + '/releases?per_page=20';
  const CACHE_KEY = 'dg_releases_v1';
  const CACHE_TTL = 30 * 60 * 1000; // 30 分钟

  function findApk(release) {
    if (!release.assets) return null;
    const apk = release.assets.find(a => /\.apk$/i.test(a.name));
    return apk ? apk.browser_download_url : null;
  }

  function versionOf(release) {
    const t = release.tag_name || release.name || '';
    return t.replace(/^v/i, '');
  }

  function fmtDate(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d)) return iso.slice(0, 10);
    const p = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
  }

  function applyLatest(rel) {
    if (!rel) return;
    const ver = versionOf(rel);
    const apkUrl = findApk(rel);
    const apkName = (rel.assets || []).find(a => /\.apk$/i.test(a.name));
    const date = fmtDate(rel.published_at);
    const size = apkName && apkName.size ? apkName.size : 0;

    document.querySelectorAll('[data-release-download]').forEach(a => {
      if (apkUrl) a.href = apkUrl;
    });
    const mainLabel = document.querySelector('.download .dl-label');
    if (mainLabel) mainLabel.textContent = '下载 Dianguard v' + ver;
    const badge = document.querySelector('[data-release-badge]');
    if (badge) badge.textContent = 'Android 版 v' + ver + ' 已发布';
    const apkEl = document.querySelector('[data-release-apkname]');
    if (apkEl && apkName) apkEl.textContent = apkName.name;
    const sizeEl = document.querySelector('[data-release-size]');
    if (sizeEl && size) sizeEl.textContent = String(Math.max(1, Math.round(size / 1e6)));
    const line = document.querySelector('[data-release-version-line]');
    if (line) line.innerHTML = '当前最新版本 <strong>v' + ver + '</strong> · 发布于 ' + date;
  }

  // 极简 Markdown 渲染（标题 / 列表 / 加粗 / 行内代码 / 链接 / 分割线）
  function renderMarkdown(md) {
    if (!md) return '';
    const lines = md.replace(/\r/g, '').split('\n');
    let html = '', inUl = false, inOl = false;
    const closeLists = () => {
      if (inUl) { html += '</ul>'; inUl = false; }
      if (inOl) { html += '</ol>'; inOl = false; }
    };
    const inline = t => t
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    for (const raw of lines) {
      const line = raw.trimEnd();
      let m;
      if ((m = line.match(/^###\s+(.*)$/))) { closeLists(); html += '<h4>' + inline(m[1]) + '</h4>'; }
      else if ((m = line.match(/^##\s+(.*)$/))) { closeLists(); html += '<h3>' + inline(m[1]) + '</h3>'; }
      else if ((m = line.match(/^#\s+(.*)$/))) { closeLists(); html += '<h2>' + inline(m[1]) + '</h2>'; }
      else if (/^---+\s*$/.test(line)) { closeLists(); html += '<hr>'; }
      else if ((m = line.match(/^\s*[-*]\s+(.*)$/))) {
        if (inOl) { html += '</ol>'; inOl = false; }
        if (!inUl) { html += '<ul>'; inUl = true; }
        html += '<li>' + inline(m[1]) + '</li>';
      }
      else if ((m = line.match(/^\s*\d+\.\s+(.*)$/))) {
        if (inUl) { html += '</ul>'; inUl = false; }
        if (!inOl) { html += '<ol>'; inOl = true; }
        html += '<li>' + inline(m[1]) + '</li>';
      }
      else if (line === '') { closeLists(); }
      else { closeLists(); html += '<p>' + inline(line) + '</p>'; }
    }
    closeLists();
    return html;
  }

  function renderChangelog(releases) {
    const list = document.getElementById('release-list');
    if (!list) return;
    if (!releases || !releases.length) {
      list.innerHTML = '<div class="release-error">暂时无法加载更新记录，请稍后重试，或直接前往 ' +
        '<a href="https://github.com/' + REPO + '/releases" target="_blank" rel="noopener">GitHub Release</a> 查看。</div>';
      return;
    }
    list.innerHTML = releases.map((rel, i) => {
      const ver = versionOf(rel);
      const date = fmtDate(rel.published_at);
      const notes = renderMarkdown(rel.body || '（暂无文字说明，详见 GitHub Release）');
      const dl = findApk(rel);
      const dlHtml = dl
        ? '<p class="release-dl"><a class="btn btn-secondary btn-small" href="' + dl + '" target="_blank" rel="noopener">下载此版本 APK</a></p>'
        : '';
      const latest = i === 0 ? ' latest' : '';
      const badge = i === 0 ? '<span class="release-badge">最新</span>' : '';
      return '<article class="release-card' + latest + ' reveal">' +
        '<div class="release-head">' +
          '<span class="release-version">v' + ver + '</span>' + badge +
          '<span class="release-date">' + date + '</span>' +
        '</div>' +
        '<div class="release-notes">' + notes + '</div>' +
        dlHtml +
      '</article>';
    }).join('');
    list.querySelectorAll('.release-card').forEach(el => { el.classList.add('revealed'); });
  }

  function loadFallback() {
    // 接口不可用时，用页面内嵌快照兜底（保证页面不空白）
    try {
      const raw = document.getElementById('fallback-releases');
      if (raw) {
        const fb = JSON.parse(raw.textContent);
        const mapped = fb.map(r => ({
          tag_name: r.tag, name: r.tag, published_at: r.date + 'T00:00:00Z',
          body: r.notes, assets: [], prerelease: false, draft: false
        }));
        renderChangelog(mapped);
      }
    } catch (e) { /* ignore */ }
  }

  function fetchReleases() {
    const cached = (function () {
      try {
        const s = localStorage.getItem(CACHE_KEY);
        if (!s) return null;
        const o = JSON.parse(s);
        if (Date.now() - o.ts < CACHE_TTL) return o.data;
      } catch (e) {}
      return null;
    })();

    if (cached) {
      applyLatest(cached[0]);
      renderChangelog(cached);
      return;
    }

    fetch(RELEASES_URL, { headers: { 'Accept': 'application/vnd.github+json' } })
      .then(r => { if (!r.ok) throw new Error('http ' + r.status); return r.json(); })
      .then(data => {
        const list = (Array.isArray(data) ? data : [])
          .filter(r => !r.draft && !r.prerelease);
        if (!list.length) throw new Error('empty');
        try {
          localStorage.setItem(CACHE_KEY, JSON.stringify({ ts: Date.now(), data: list }));
        } catch (e) {}
        applyLatest(list[0]);
        renderChangelog(list);
      })
      .catch(() => {
        // 失败时：首页保持静态默认值，更新页用内嵌快照兜底
        if (document.getElementById('release-list')) loadFallback();
      });
  }

  fetchReleases();

  // 技术原理页专属动效
  if (document.querySelector('.page-how')) initHowPage();
})();
