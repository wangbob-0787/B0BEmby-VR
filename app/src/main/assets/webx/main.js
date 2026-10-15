/*
 * 页面世界的探子（父亲 2026-10-15）。
 *
 * 由 isolated.js 用 <script src=...> 注入到页面自己的世界 —— 只有在这里才能
 * 动页面的 fetch / XHR（隔离世界改了页面也看不见），才抓得到播放地址。
 *
 * 每 400ms 上报两样东西（用 window.postMessage 交给 isolated.js 转发给客户端）：
 *   rect：正在播的那块视频画面在视口里占哪一块（归一化）+ 视频真实比例 + 绝对像素面积
 *   urls：这段时间抓到的播放地址候选（m3u8 / mpd / mp4 / m4s）
 *
 * 页面里有多个框架（播放器常在 iframe 里）时每层各报一份，谁的面积大由客户端挑。
 */
(function () {
  if (window.__b0bScan) return;
  window.__b0bScan = true;

  var urls = [];

  function note(u) {
    if (!u || typeof u !== 'string') return;
    if (!/\.(m3u8|mpd|mp4|m4s|flv)(\?|#|$)/i.test(u)) return;
    if (u.indexOf('data:') === 0) return;
    if (urls.indexOf(u) >= 0) return;
    urls.push(u);
    if (urls.length > 30) urls.shift();
  }

  try {
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function (input, init) {
        try { note(typeof input === 'string' ? input : (input && input.url)); } catch (e) {}
        return origFetch.apply(this, arguments);
      };
    }
  } catch (e) {}

  try {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      try { note(u); } catch (e) {}
      return origOpen.apply(this, arguments);
    };
  } catch (e) {}

  try {
    var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (desc && desc.set) {
      Object.defineProperty(HTMLMediaElement.prototype, 'src', {
        configurable: true,
        get: desc.get,
        set: function (v) { try { note(v); } catch (e) {} return desc.set.call(this, v); }
      });
    }
  } catch (e) {}

  function scan() {
    var best = null, bestArea = 0;
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      var vw = v.videoWidth || 0, vh = v.videoHeight || 0;
      if (!vw || !vh || v.readyState < 2) continue;
      var r = v.getBoundingClientRect();
      if (r.width < 40 || r.height < 40) continue;
      var st = window.getComputedStyle(v);
      if (!st || st.display === 'none' || st.visibility === 'hidden') continue;
      if (parseFloat(st.opacity || '1') < 0.05) continue;
      var area = r.width * r.height;
      if (area <= bestArea) continue;
      var cw = r.width, ch = r.height;
      var fit = st.objectFit || 'contain';
      if (fit !== 'cover' && fit !== 'fill') {
        var ar = vw / vh, er = cw / ch;
        if (ar > er) { ch = cw / ar; } else { cw = ch * ar; }
      }
      bestArea = area;
      best = {
        x: r.left + (r.width - cw) / 2,
        y: r.top + (r.height - ch) / 2,
        w: cw,
        h: ch,
        ar: (cw * (window.innerWidth || 1)) / (ch * (window.innerHeight || 1)),
        area: Math.round(cw * ch),
        url: v.currentSrc || v.src || ''
      };
    }
    var msg = { kind: 'rect', t: Date.now(), frame: location.href };
    if (best) {
      var iw = window.innerWidth || 1, ih = window.innerHeight || 1;
      msg.rect = {
        x: best.x / iw, y: best.y / ih, w: best.w / iw, h: best.h / ih,
        ar: best.ar, area: best.area, url: best.url
      };
      note(best.url);
    }
    if (urls.length) msg.urls = urls.slice(-10);
    try { window.postMessage({ __b0b: 1, payload: msg }, '*'); } catch (e) {}
  }

  setInterval(scan, 400);
  scan();
})();
