// HyperBot site — small, dependency-free behaviour + motion.
(() => {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ---- mobile nav
  const nav = document.querySelector('.nav');
  const toggle = document.querySelector('.nav-toggle');
  if (nav && toggle) {
    toggle.addEventListener('click', () => {
      const open = nav.classList.toggle('open');
      toggle.setAttribute('aria-expanded', open);
      toggle.textContent = open ? 'CLOSE ✕' : 'MENU ☰';
    });
    nav.querySelectorAll('.nav-links a').forEach(a => a.addEventListener('click', () => nav.classList.remove('open')));
  }

  // ---- marquees: duplicate once so translateX(-50%) loops seamlessly
  document.querySelectorAll('.ribbon-track').forEach(t => { t.innerHTML += t.innerHTML; });

  // ---- hero intro sequence (after fonts, so lines don't jump)
  const start = () => requestAnimationFrame(() => document.body.classList.add('loaded'));
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(start); else start();
  setTimeout(start, 1200);   // never wait forever

  // ---- stagger: children of [data-stagger] get increasing delays
  document.querySelectorAll('[data-stagger]').forEach(group => {
    const step = parseFloat(group.dataset.stagger) || 0.08;
    [...group.children].forEach((el, i) => {
      el.classList.add('reveal');
      el.style.transitionDelay = (i * step).toFixed(2) + 's';
    });
  });

  // ---- reveal on scroll (fade / slide / wipe / frame-in / glitch / lines)
  const targets = document.querySelectorAll('.reveal, .frame-in, .glitch, .draw-line, [data-count], [data-type]');
  // wait until any images inside are downloaded and decoded, so a reveal
  // never plays over an empty box and then pops
  const ready = el => {
    const imgs = el.tagName === 'IMG' ? [el] : [...el.querySelectorAll('img')];
    return Promise.all(imgs.map(img => {
      if (img.loading === 'lazy') img.loading = 'eager';
      return (img.decode ? img.decode() : Promise.resolve()).catch(() => {});
    }));
  };
  const show = el => {
    if (el.dataset.revealing) return;
    el.dataset.revealing = '1';
    ready(el).then(() => requestAnimationFrame(() => reveal(el)));
  };
  const reveal = el => {
    el.classList.add('in');
    if (el.dataset.count) countUp(el);
    if (el.dataset.type) typeOut(el);
  };
  if ('IntersectionObserver' in window && !reduce) {
    // clip-path wipes are invisible to the observer while fully clipped,
    // so watch their parent and reveal the wipe itself.
    const proxy = new Map();
    const io = new IntersectionObserver(entries => {
      entries.forEach(e => {
        if (!e.isIntersecting) return;
        (proxy.get(e.target) || [e.target]).forEach(show);
        io.unobserve(e.target);
      });
    }, { rootMargin: '0px 0px -10% 0px', threshold: 0.08 });
    const warm = new IntersectionObserver(entries => {
      entries.forEach(e => { if (e.isIntersecting) { e.target.loading = 'eager'; e.target.decode?.().catch(() => {}); warm.unobserve(e.target); } });
    }, { rootMargin: '1200px 0px' });
    document.querySelectorAll('img[loading="lazy"]').forEach(img => warm.observe(img));
    targets.forEach(el => {
      if (el.matches('.wipe, .wipe-up')) {
        const host = el.parentElement;
        if (!proxy.has(host)) { proxy.set(host, []); io.observe(host); }
        proxy.get(host).push(el);
      } else io.observe(el);
    });
  } else {
    targets.forEach(show);
  }

  // ---- number counters: <b data-count="128" data-suffix="×64">
  function countUp(el) {
    const end = parseFloat(el.dataset.count), suffix = el.dataset.suffix || '', prefix = el.dataset.prefix || '';
    if (reduce) { el.textContent = prefix + end + suffix; return; }
    const t0 = performance.now(), dur = 1200;
    const tick = t => {
      const p = Math.min(1, (t - t0) / dur), v = Math.round(end * (1 - Math.pow(1 - p, 3)));
      el.textContent = prefix + v + suffix;
      if (p < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }

  // ---- typewriter for small mono labels: <span data-type="SYS.ONLINE">
  function typeOut(el) {
    const text = el.dataset.type;
    if (reduce) { el.textContent = text; return; }
    let i = 0; el.textContent = '';
    const step = () => { el.textContent = text.slice(0, ++i) + (i < text.length ? '▌' : ''); if (i < text.length) setTimeout(step, 38); };
    step();
  }

  // ---- scroll: progress bar + parallax art ([data-speed])
  const bar = document.querySelector('.progress-bar');
  const para = [...document.querySelectorAll('[data-speed]')];
  let ticking = false;
  const onScroll = () => {
    ticking = false;
    const y = window.scrollY, h = document.documentElement.scrollHeight - innerHeight;
    if (bar) bar.style.transform = `scaleX(${h > 0 ? y / h : 0})`;
    if (reduce) return;
    para.forEach(el => {
      const r = el.parentElement.getBoundingClientRect();
      const off = Math.max(-90, Math.min(90, (r.top + r.height / 2 - innerHeight / 2) * parseFloat(el.dataset.speed)));
      el.style.translate = `0 ${off.toFixed(1)}px`;
    });
  };
  addEventListener('scroll', () => { if (!ticking) { ticking = true; requestAnimationFrame(onScroll); } }, { passive: true });
  onScroll();

  // ---- cards tilt toward the pointer
  if (!reduce && matchMedia('(hover: hover)').matches) {
    document.querySelectorAll('.card').forEach(card => {
      card.addEventListener('pointermove', e => {
        const r = card.getBoundingClientRect();
        card.style.setProperty('--ry', ((e.clientX - r.left) / r.width - .5) * 8 + 'deg');
        card.style.setProperty('--rx', -((e.clientY - r.top) / r.height - .5) * 8 + 'deg');
      });
      card.addEventListener('pointerleave', () => { card.style.setProperty('--rx', '0deg'); card.style.setProperty('--ry', '0deg'); });
    });
  }

  // ---- build page: Web Serial support (needed by the installer)
  const check = document.querySelector('.browser-check');
  if (check) check.classList.add('serial' in navigator && window.isSecureContext ? 'is-ok' : 'is-bad');

  // ---- build page: highlight the chapter you're reading
  const chapters = [...document.querySelectorAll('.chapter[id]')];
  const links = [...document.querySelectorAll('.side a, .progress a')];
  if (chapters.length && 'IntersectionObserver' in window) {
    const spy = new IntersectionObserver(entries => {
      entries.forEach(e => {
        if (!e.isIntersecting) return;
        links.forEach(l => l.classList.toggle('on', l.getAttribute('href') === '#' + e.target.id));
        const idx = document.querySelector('[data-chapter]');
        if (idx) idx.textContent = String(chapters.indexOf(e.target) + 1).padStart(2, '0');
      });
    }, { rootMargin: '-40% 0px -55% 0px' });
    chapters.forEach(c => spy.observe(c));
  }

  // ---- year in footer
  document.querySelectorAll('[data-year]').forEach(el => { el.textContent = new Date().getFullYear(); });
})();
