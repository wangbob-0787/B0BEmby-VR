
/* 逻辑见 docs/ledger/web-side-spec-2026-10-25.md，代码里不再写注释 */
(function () {
  'use strict';

  
  function hookNet(onUrl) {
    try {
      var oF = window.fetch;
      if (oF && !oF.__b0bHooked) {
        window.fetch = function () {
          try { var a = [].slice.call(arguments); var u = (typeof a[0] === 'string') ? a[0] : (a[0] && a[0].url); if (u) onUrl(u); } catch (e) {}
          return oF.apply(this, arguments);
        };
        window.fetch.__b0bHooked = 1;
      }
    } catch (e) {}
    try {
      var oO = XMLHttpRequest.prototype.open, oS = XMLHttpRequest.prototype.send;
      if (!XMLHttpRequest.prototype.open.__b0bHooked) {
        XMLHttpRequest.prototype.open = function (m, u) { try { this.__b0bU = u; } catch (e) {} return oO.apply(this, arguments); };
        XMLHttpRequest.prototype.open.__b0bHooked = 1;
        XMLHttpRequest.prototype.send = function () {
          try { if (this.__b0bU) onUrl(this.__b0bU); } catch (e) {}
          return oS.apply(this, arguments);
        };
      }
    } catch (e) {}
  }

  function CH(step, kv) {
    try {
      var parts = [];
      for (var k in (kv || {})) parts.push(k + '=' + kv[k]);
      console.log('[链]' + step + ' ' + parts.join(' '));
    } catch (e) {}
  }
  function nowMs() { return Date.now(); }
  function send(o) {
    try {
      if (window.b0bHost && window.b0bHost.post) {
        window.b0bHost.post(JSON.stringify(o));
        return;
      }
    } catch (e) {}
    try { (window.__b0bOutbox = window.__b0bOutbox || []).push(JSON.stringify(o)); } catch (e2) {}
  }

  
  var CN = { '零': 0, '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9 };
  function cn2num(s) {
    if (/^\d+$/.test(s)) return parseInt(s, 10);
    var t = 0, cur = 0;
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (CN[c] !== undefined) { cur = CN[c]; continue; }
      if (c === '十') { t += (cur || 1) * 10; cur = 0; continue; }
      return s;
    }
    return String(t + cur);
  }
  function txt(sel) {
    try {
      var el = document.querySelector(sel);
      return el ? String(el.textContent || '').replace(/\s+/g, ' ').trim() : '';
    } catch (e) { return ''; }
  }
  function site() {
    var h = String(location.hostname || '');
    if (/bilibili\.com$/.test(h)) return 'bi';
    if (/qq\.com$/.test(h)) return 'qq';
    if (/iqiyi\.com$/.test(h)) return 'iq';
    return '';
  }
  function realFull() {
    var a = 0, b = 0;
    try { a = parseInt(String(window.__b0bRealFull || 0), 10) || 0; } catch (e) {}
    try { b = parseInt(localStorage.getItem('b0b:realFullAt') || '0', 10) || 0; } catch (e2) {}
    var t = Math.max(a, b);
    return t > 0 && (Date.now() - t) < 10 * 60 * 1000;
  }
  function pausePage(reason) {
    try {
      var vs = document.querySelectorAll('video'), n = 0;
      for (var i = 0; i < vs.length; i++) {
        if (vs[i] && !vs[i].paused) { try { vs[i].pause(); n++; } catch (e) {} }
      }
      console.log('[b0b] 已交给内核，网页视频停下（' + reason + '，' + n + ' 个）');
    } catch (e) {}
  }
  function clipDur() {
    try {
      var v = document.querySelector('video');
      return v && v.duration > 0 ? Math.round(v.duration) : 0;
    } catch (e) { return 0; }
  }

  
  setInterval(function () {
    try {
      var vs = document.querySelectorAll('video'), best = null, bestArea = 0;
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i], r = v.getBoundingClientRect();
        var area = Math.max(0, r.width) * Math.max(0, r.height);
        if (area > bestArea) { bestArea = area; best = { v: v, r: r }; }
      }
      if (!best || bestArea <= 0) return;
      var r2 = best.r, W = window.innerWidth || 1, H = window.innerHeight || 1;
      send({
        kind: 'rect', frame: location.href, cookie: String(document.cookie || ''),
        urls: [],
        rect: {
          x: r2.left / W, y: r2.top / H, w: r2.width / W, h: r2.height / H,
          ar: best.v.videoHeight > 0 ? (best.v.videoWidth / best.v.videoHeight) : 0,
          area: bestArea / (W * H)
        }
      });
    } catch (e) {}
  }, 400);

  
  var biSlot = null, biPend = {}, biNewest = '', biFloor = 0, biSig = '';

  function biPage() {
    var m = /\/(bangumi\/play\/(?:ep|ss)\d+|video\/BV[0-9A-Za-z]+)/.exec(location.pathname || '');
    return m ? m[1] : String(location.pathname || '');
  }
  function biTitle() {
    var t = String(document.title || '').replace(/\s*[_|]\s*哔哩哔哩\s*[_|]?\s*bilibili\s*$/i, '').trim();
    var cut = t.search(/[-_|]\s*(电视剧|电影|纪录片|番剧|综艺|国创|动画|全集|高清|正版|独家|bilibili)/i);
    if (cut > 0) t = t.slice(0, cut).trim();
    var ov = txt('.bpx-player-top-title') || txt('[class*="player-top-title"]');
    var season = (t.match(/第\s*([零一二三四五六七八九十\d]+)\s*季/) || [])[1];
    var series = t.replace(/第\s*[零一二三四五六七八九十\d]+\s*[季集期话][^-_|]*/g, '').replace(/\s{2,}/g, ' ').trim() || t;
    var mOv = /第\s*([零一二三四五六七八九十\d]+)\s*([集期话])\s*(.*)$/.exec(ov);
    if (mOv) {
      var out = series;
      if (season) out += ' 第' + cn2num(season) + '季';
      out += ' 第' + cn2num(mOv[1]) + mOv[2];
      var epName = String(mOv[3] || '').trim();
      if (epName) out += ' ' + epName;
      return out.replace(/\s{2,}/g, ' ').trim();
    }
    if (/^(正片|预告|花絮|片段|特辑|彩蛋)$/.test(ov)) ov = '';
    if (ov && series && ov.indexOf(series) < 0 && series.indexOf(ov) < 0) {
      return (series + '(' + ov + ')').replace(/\s{2,}/g, ' ').trim();
    }
    t = t.replace(/第\s*([零一二三四五六七八九十\d]+)\s*季/g, function (m, d) { return ' 第' + cn2num(d) + '季'; });
    t = t.replace(/第\s*([零一二三四五六七八九十\d]+)\s*([集期话])/g, function (m, d, u) { return ' 第' + cn2num(d) + u; });
    return t.replace(/\s{2,}/g, ' ').trim() || String(document.title || '');
  }
  function biEpNo() {
    var ov = txt('.bpx-player-top-title') || txt('[class*="player-top-title"]');
    var m = /第\s*([零一二三四五六七八九十\d]+)\s*[集期话]/.exec(ov) || /第\s*([零一二三四五六七八九十\d]+)\s*[集期话]/.exec(biTitle());
    return m ? String(cn2num(m[1])) : '';
  }
  function biSeasonNo() {
    var m = /第\s*([零一二三四五六七八九十\d]+)\s*季/.exec(String(document.title || ''));
    return m ? String(cn2num(m[1])) : '';
  }
  function biScanNewest() {
    try {
      var es = performance.getEntriesByType('resource') || [];
      for (var i = es.length - 1; i >= biFloor; i--) {
        var nm = es[i].name || '';
        if (nm.indexOf('.m4s') < 0) continue;
        var m = /\/upgcxcode\/\d+\/\d+\/(\d+)\//.exec(nm);
        if (m) { biNewest = m[1]; return biNewest; }
      }
    } catch (e) {}
    return biNewest;
  }
  function biNote(url) {
    try {
      var pu = new URL(url, location.href);
      if (/^(data|api|s?\.?message)\.bilibili\.com$/.test(pu.hostname)) return;
      if (pu.pathname.indexOf('/upgcxcode/') < 0) return;
    } catch (e) { return; }
    var m = /\/upgcxcode\/\d+\/\d+\/(\d+)\//.exec(url);
    if (!m) return;
    var cid = m[1];
    var mq = /-1-(\d+)\.m4s/.exec(url);
    if (!mq) return;
    var isAudio = /^(?:302|303)\d{2}$/.test(mq[1]);
    var newest = biScanNewest();
    if (newest && newest !== cid) return;                 
    if (!biPend[cid]) biPend[cid] = { v: '', a: '' };
    var cur = biPend[cid];
    if (isAudio) { if (cur.a === url) return; cur.a = url; }
    else { if (cur.v === url) return; cur.v = url; }
    if (!cur.v || !cur.a) return;                          
    biSlot = {
      cid: cid, v: cur.v, a: cur.a, title: biTitle(),
      ep: biEpNo(), season: biSeasonNo(), dur: clipDur(), at: Date.now(), page: biPage()
    };
    CH('②槽变', { 站: 'bi', cid: cid, 集: biSlot.ep || '-', 季: biSlot.season || '-', 时长: biSlot.dur + 's', at: biSlot.at, 画面尾: '…' + cur.v.slice(-24), 声音尾: '…' + cur.a.slice(-20), 标题: String(biSlot.title).slice(0, 24) });
    try { deliver(); } catch (e) {}
  }
  function biTick() {
    try {
      var es = performance.getEntriesByType('resource') || [];
      var sig = String(location.href) + '|' + String(document.title || '');
      if (sig !== biSig) {
        CH('①页面变化', { 站: 'bi', 页: String(location.href).slice(-60), 标题: String(document.title || '').slice(0, 30) });
        if (biNewest) biFloor = es.length;
        biSig = sig; biPend = {};
      }
      for (var i = biFloor; i < es.length; i++) {
        var u = es[i].name || '';
        if (u.indexOf('.m4s') > 0 && u.indexOf('/upgcxcode/') > 0) biNote(u);
      }
    } catch (e) {}
  }

  
  var QQ_NAMES = { 'f322100': '臻彩MAX', 'f322104': '臻彩MAX', 'f322093': '4K', 'f322150': '臻彩1080P' };
  var qqSlot = null;

  function qqVid() {
    var m = String(location.pathname).match(/\/x\/cover\/[a-z0-9]+\/([a-z0-9]+)\.html/i);
    return m ? m[1] : '';
  }
  function qqTitle() {
    var tt = String(document.title || '');
    var ov = txt('.player-play-title__title');
    var sub = txt('.player-play-title__sub-title');
    var series = (tt.split('_')[0] || '').trim();
    if (/^\s*第\s*\d+\s*[集期话]/.test(series)) series = '';
    var mOv = /第\s*(\d{1,4})\s*([集期话])\s*[：:]?\s*(.*)$/.exec(ov);
    if (mOv) {
      var ep = parseInt(mOv[1], 10) || 0, unit = mOv[2] || '集';
      var epName = String(mOv[3] || '').trim() || sub;
      if (!series) return ov;
      var out = series + (ep ? ' 第' + ep + unit : '');
      if (epName && ep) out += ' ' + epName;
      return out;
    }
    if (ov) return sub && sub !== ov ? (ov + ' ' + sub) : ov;
    return series || tt;
  }
  function qqTake(u) {
    if (!u) return;
    var one = String(u).replace(/\\\//g, '/');
    var m = one.match(/\.(f\d{6})\.(?:ts\.)?m3u8/);
    if (!m) return;
    var tier = m[1];
    var name = QQ_NAMES[tier];
    if (!name) return;                                   
    if (/smtcdns|subtitle|\.vtt\./.test(one)) return;
    var vid = qqVid();
    if (!vid) return;                                    
    if (qqSlot && qqSlot.url === one && qqSlot.vid === vid) return;
    qqSlot = { vid: vid, url: one, tier: tier, name: name, title: qqTitle(), dur: clipDur(), at: Date.now() };
    CH('②槽变', { 站: 'qq', 片号: vid, 档: name + '(' + tier + ')', 时长: qqSlot.dur + 's', at: qqSlot.at, 地址尾: '…' + one.slice(-26), 标题: String(qqSlot.title).slice(0, 24) });
    try { deliver(); } catch (e) {}
  }
  function qqTick() {
    try {
      var arr = (window.__qq18 && window.__qq18.hits && window.__qq18.hits()) || [];
      for (var i = 0; i < Math.min(arr.length, 4); i++) qqTake(arr[i].url);
    } catch (e) {}
  }

  
  var IQ_SALT = 'ulc2h7tka0mdrf2lkb1n6m6mulc2htbn';
  var IQ_CONST = {
    t: '0', us: '', kuid: '', kt: '0', vf: '', hevc: '0', qd: '0', r: '1', s: '1',
    cp: '14', p: '1', pn: '0', p1: '1', q: '1', o: '0', cmd: '0'
  };
  var iqSlot = null;

  function iqCookie(name) {
    try {
      var m = String(document.cookie || '').match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
      return m ? decodeURIComponent(m[1]) : '';
    } catch (e) { return ''; }
  }
  function iqHeadTitle() {
    var t = txt('.iqp-title_left') || txt('[class*="play_title"]') || String(document.title || '');
    return t.replace(/[-_]\s*爱奇艺.*$/, '').replace(/【.*?】/g, '').trim();
  }
  function iqHeadEp() {
    var m = /第\s*(\d{1,4})\s*集/.exec(iqHeadTitle());
    return m ? parseInt(m[1], 10) : 0;
  }
  function iqTvidNow() {
    var ep = iqHeadEp();
    var l = iqEpTable.list;
    if (!ep) {
      try {
        var v = ((window.QiyiPlayerProphetData || {}).videoInfo) || {};
        if (v.tvId) return String(v.tvId);
      } catch (e) {}
      return l.length ? String(l[0].tvId || l[0].tvid || '') : '';
    }
    if (!l.length) return '';
    var it = l[ep - 1];
    return it ? String(it.tvId || it.tvid || '') : '';
  }
  function md5(s) {
    function rl(n, c) { return (n << c) | (n >>> (32 - c)); }
    function au(x, y) { var l = (x & 0xFFFF) + (y & 0xFFFF), m = (x >> 16) + (y >> 16) + (l >> 16); return (m << 16) | (l & 0xFFFF); }
    function cmn(q, a, b, x, s2, t) { return au(rl(au(au(a, q), au(x, t)), s2), b); }
    function ff(a, b, c, d, x, s2, t) { return cmn((b & c) | ((~b) & d), a, b, x, s2, t); }
    function gg(a, b, c, d, x, s2, t) { return cmn((b & d) | (c & (~d)), a, b, x, s2, t); }
    function hh(a, b, c, d, x, s2, t) { return cmn(b ^ c ^ d, a, b, x, s2, t); }
    function ii(a, b, c, d, x, s2, t) { return cmn(c ^ (b | (~d)), a, b, x, s2, t); }
    return hex(md5core(String(s)));
  }
  function md5core(str) {
    var n = str.length, w = [], i;
    for (i = 0; i < n * 8; i += 32) w[i >> 5] = (w[i >> 5] || 0) | ((str.charCodeAt(i >> 3) | 0) << (i % 32));
    var a = 1732584193, b = -271733879, c = -1732584194, d = 271733878;
    var s = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
    var kk = [], L = ((n + 8) >> 6) + 1;
    for (i = 0; i < L * 16; i++) kk[i] = w[i] || 0;
    kk[n >> 2] |= 128 << ((n % 4) * 8);
    kk[L * 16 - 2] = n * 8;
    for (i = 0; i < L * 16; i += 16) {
      var A = a, B = b, C = c, D = d;
      for (var j = 0; j < 64; j++) {
        var f, g2;
        if (j < 16) { f = (B & C) | ((~B) & D); g2 = j; }
        else if (j < 32) { f = (D & B) | ((~D) & C); g2 = (5 * j + 1) % 16; }
        else if (j < 48) { f = B ^ C ^ D; g2 = (3 * j + 5) % 16; }
        else { f = C ^ (B | (~D)); g2 = (7 * j) % 16; }
        var tmp = D; D = C; C = B;
        B = au(B, rl(au(au(A, f), au(kk[i + g2], 0)), s[((j >> 4) * 4) + (j % 4)]));
        A = tmp;
      }
      a = au(a, A); b = au(b, B); c = au(c, C); d = au(d, D);
    }
    return [a, b, c, d];
  }
  function hex(arr) {
    var h = '0123456789abcdef', out = '';
    for (var i = 0; i < arr.length * 4; i++) {
      out += h.charAt((arr[i >> 2] >> ((i % 4) * 8 + 4)) & 0xF) + h.charAt((arr[i >> 2] >> ((i % 4) * 8)) & 0xF);
    }
    return out;
  }
  function iqBuildUrl(tvid, bid) {
    var sb = '';
    for (var k in IQ_CONST) sb += k + '=' + IQ_CONST[k] + '&';
    sb += 'tvid=' + tvid + '&bid=' + bid + '&vid=&uid=' + iqCookie('P00003') + '&k_uid=' + iqCookie('QC005') +
      '&dfp=' + (String(localStorage.getItem('__dfp') || '').split('@')[0]) + '&authKey=&tm=' + Date.now();
    var path = '/dash?' + sb;
    return 'https://cache.video.iqiyi.com' + path + '&vf=' + md5(path + IQ_SALT);
  }
  function iqParse(body) {
    var j = JSON.parse(body), data = j.data || {};
    var vlist = (data.vidl || [])[0] || {};
    var pl = vlist._bl || (data.vidl && data.vidl[0] && data.vidl[0]._bl) || {};
    var list = (pl ? (pl['2'] || pl) : null);
    var best = null, bestN = 0, wh = '';
    for (var k in (pl || {})) {
      var cand = pl[k];
      if (!cand || !cand.vs || !cand.vs.length) continue;
      var n = String(cand.vs).length;
      if (n > bestN) { bestN = n; best = cand; wh = k; }
    }
    if (!best) return null;
    var segs = 0, dur = 0;
    String(best.vs).split('-').forEach(function (x) {
      var parts = x.split('.');
      if (parts.length >= 3) { segs++; dur += parseInt(parts[2], 10) || 0; }
    });
    return { playlist: String(best.vs), segs: segs, dur: dur || parseInt(best.duration || 0, 10) || 0, bid: parseInt(best.bid || 0, 10) || 0, wh: wh };
  }
  var iqEpTable = { aid: '', list: [] };
  function iqAlbumCands() {
    var out = [];
    try {
      var vi = ((window.QiyiPlayerProphetData || {}).videoInfo) || {};
      if (vi.albumId) out.push(String(vi.albumId));
      if (vi.album && vi.album.id && out.indexOf(String(vi.album.id)) < 0) out.push(String(vi.album.id));
    } catch (e) {}
    try {
      var es = performance.getEntriesByType('resource');
      for (var i = es.length - 1; i >= 0; i--) {
        var m = /[?&]album_id=(\d{6,})/.exec(es[i].name || '');
        if (m && out.indexOf(m[1]) < 0) out.push(m[1]);
      }
    } catch (e) {}
    try {
      var m2 = /"albumId"\s*:\s*"?(\d{6,})/.exec(document.documentElement.innerHTML);
      if (m2 && out.indexOf(m2[1]) < 0) out.push(m2[1]);
    } catch (e) {}
    try { var d = window.QiyiPlayerProphetData || {}; if (d.albumId && out.indexOf(String(d.albumId)) < 0) out.push(String(d.albumId)); } catch (e) {}
    return out;
  }
  function iqSeriesName() {
    var t = iqHeadTitle();
    if (!/第\s*\d+\s*集/.test(t)) return t.replace(/\s+/g, ' ').trim();
    return t.replace(/第\s*\d+\s*集[^]*$/, '').replace(/\s+/g, ' ').trim();
  }
  function iqLoadEps() {
    if (iqEpTable.list.length) return;
    var cands = iqAlbumCands(), sn = iqSeriesName(), i = 0;
    function next() {
      if (i >= cands.length) return;
      var aid = cands[i++];
      fetch('https://pcw-api.iqiyi.com/albums/album/avlistinfo?aid=' + aid + '&page=1&size=200', { credentials: 'include' })
        .then(function (r) { return r.json(); })
        .then(function (j) {
          var list = (((j || {}).data || {}).epsodelist) || [];
          var n0 = list.length ? String(list[0].name || '') : '';
          if (n0 && sn && n0.indexOf(sn) === 0) {
            iqEpTable = { aid: aid, list: list };
            CH('②分集表', { 站: 'iq', aid: aid, 集数: list.length, 首集: n0.slice(0, 18) });
          } else { next(); }
        })
        .catch(function () { next(); });
    }
    next();
  }

  function iqGrab(tag) {
    var ep = iqHeadEp();
    var tvid = iqTvidNow();
    if (!tvid) { console.log('[b0b] 爱奇艺：片号还没读到（第' + ep + '集）→ 等'); return; }
    var url = iqBuildUrl(tvid, '600');
    console.log('[b0b] 爱奇艺：要清单 ' + tag + '，第' + ep + '集，片号=' + tvid);
    try {
      var x = new XMLHttpRequest();
      x.open('GET', url, true);
      x.onload = function () {
        try {
          var r = iqParse(x.responseText);
          if (!r || !r.playlist) { console.log('[b0b] 爱奇艺：清单没拿到'); return; }
          iqSlot = {
            tvid: tvid, ep: ep, playlist: r.playlist, segs: r.segs, dur: r.dur,
            bid: r.bid, wh: r.wh, title: iqHeadTitle(), at: Date.now()
          };
          CH('②槽变', { 站: 'iq', 片号: tvid, 集: ep, 段: r.segs, 时长: r.dur + 's', at: iqSlot.at, bid: r.bid, 标题: String(iqSlot.title).slice(0, 24) });
          try { deliver(); } catch (e) {}
        } catch (e) { console.log('[b0b] 爱奇艺解析出错：' + e); }
      };
      x.onerror = function () { console.log('[b0b] 爱奇艺：请求清单失败'); };
      x.send();
    } catch (e) { console.log('[b0b] 爱奇艺清单请求异常：' + e); }
  }

  
  var FS_BTN_SEL = [
    '.bpx-player-ctrl-full',                    
    '.txp_btn_fullscreen', '.txp_btn_fscreen',  
    '.iqp-btn-fullscreen', '.iqp-player-icon-fullscreen' 
  ].join(',');
  function whyNotReady() {
    var s = site();
    if (s === 'bi') {
      if (!biSlot) return '槽空';
      if (!biSlot.v || !biSlot.a) return '画面/声音未成对';
      if (!biSlot.dur) return '时长还没读到';
      var c = biScanNewest();
      if (c && String(biSlot.cid) !== String(c)) return '槽里是上一片(cid ' + biSlot.cid + ' ≠ 页面 ' + c + ')';
      return '就绪';
    }
    if (s === 'qq') {
      if (!qqSlot) return '槽空';
      if (!qqSlot.url) return '地址未到';
      if (!qqSlot.dur) return '时长还没读到';
      if (qqSlot.vid && qqVid() && qqSlot.vid !== qqVid()) return '槽里是上一片';
      return '就绪';
    }
    if (s === 'iq') {
      if (!iqSlot) return '槽空';
      if (!iqSlot.playlist) return '清单未到';
      if (!iqSlot.dur) return '时长还没读到';
      if (iqSlot.ep && iqHeadEp() && Number(iqSlot.ep) !== Number(iqHeadEp())) return '槽里是上一集';
      return '就绪';
    }
    return '不在三站';
  }
  function slotReady() {
    var s = site();
    if (s === 'bi') {
      if (!(biSlot && biSlot.v && biSlot.a && biSlot.dur)) return false;
      var cidNow = biScanNewest();
      if (cidNow) return String(biSlot.cid) === String(cidNow);
      return true;
    }
    if (s === 'qq') {
      if (!(qqSlot && qqSlot.url && qqSlot.dur)) return false;
      var vidNow = qqVid();
      if (vidNow) return String(qqSlot.vid) === String(vidNow);
      return true;
    }
    if (s === 'iq') {
      if (!(iqSlot && iqSlot.playlist && iqSlot.dur)) return false;
      var epNow = iqHeadEp();
      if (epNow) return String(iqSlot.ep) === String(epNow);
      return true;
    }
    return false;
  }
  var fsBtnLast = null;
  function markFullscreenBtn() {
    try {
      var btns = document.querySelectorAll(FS_BTN_SEL);
      var ok = slotReady();
      if (fsBtnLast !== ok) {
        fsBtnLast = ok;
        CH(ok ? '③按钮变实' : '③按钮变灰', { 站: site(), 原因: ok ? '槽里这一份已就绪' : whyNotReady(), 按钮数: btns.length });
      }
      for (var i = 0; i < btns.length; i++) {
        var b = btns[i];
        if (ok) {
          b.style.pointerEvents = '';
          b.style.opacity = '';
          b.removeAttribute('data-b0b-gray');
        } else {
          b.style.pointerEvents = 'none';
          b.style.opacity = '0.35';
          b.setAttribute('data-b0b-gray', '1');
        }
      }
    } catch (e) {}
  }
  
  try {
    var rawRFS = Element.prototype.requestFullscreen;
    if (rawRFS) {
      Element.prototype.requestFullscreen = function () {
        if (!slotReady()) {
          CH('④点全屏-拦下', { 站: site(), 原因: whyNotReady() });
          return Promise.reject(new Error('b0b-slot-not-ready'));
        }
        CH('④点全屏-放行', { 站: site(), 时长: (payloadDurHint() || 0) + 's' });
        return rawRFS.apply(this, arguments);
      };
    }
  } catch (eRF) {}
  setInterval(markFullscreenBtn, 700);

  
  var lastSent = '';
  function payloadDurHint() {
    var s = site();
    if (s === 'bi') return biSlot && biSlot.dur;
    if (s === 'qq') return qqSlot && qqSlot.dur;
    if (s === 'iq') return iqSlot && iqSlot.dur;
    return 0;
  }
  function deliver() {
    var s = site(), payload = null;
    if (s && !slotReady()) return;
    
    if (s === 'bi') {
      var b = biSlot;                                            
      if (!b || !b.v || !b.a || !b.dur) return;
      payload = {
        kind: 'play', site: 'bi', frame: location.href, title: b.title || biTitle(),
        ep: String(b.ep || ''), season: String(b.season || ''),
        durSec: b.dur || clipDur(), quality: '', width: 0, height: 0, cookie: '', at: b.at || 0,
        streams: [{ id: 0, height: 0, width: 0, bw: 0, vcodec: 'h264', video: b.v, audio: b.a }]
      };
    } else if (s === 'qq') {
      var q = qqSlot;
      if (!q || !q.url || !q.dur) return;
      payload = {
        kind: 'play', site: 'qq', frame: location.href, title: q.title || qqTitle(),
        ep: String(q.vid || ''), season: '', durSec: q.dur || clipDur(), at: q.at || 0,
        quality: q.name || '', width: 0, height: 0, cookie: '',
        streams: [{ id: 0, height: 0, width: 0, bw: 0, vcodec: 'h264', video: q.url, audio: '' }]
      };
    } else if (s === 'iq') {
      var i = iqSlot;
      if (!i || !i.playlist || !i.dur) return;
      payload = {
        kind: 'play', site: 'iq', frame: location.href, title: i.title || iqHeadTitle(),
        ep: String(i.ep || ''), season: '', durSec: i.dur || clipDur(), at: i.at || 0,
        quality: i.bid === 600 ? '1080P' : '', width: 0, height: 0, cookie: '',
        needProxy: 1,
        streams: [{ id: 0, height: 0, width: 0, bw: 0, vcodec: 'h264', video: '', audio: '', playlist: i.playlist, needProxy: 1 }]
      };
    }
    if (!payload) return;
    var sig = (payload.site || '') + '|' + String(payload.at || 0) + '|' +
      (payload.streams[0].video || payload.streams[0].playlist || '').slice(-90);
    if (sig === lastSent) return;                                 
    lastSent = sig;
    CH('⑤交原生', { 站: payload.site, 身份: (payload.ep || '?'), 集: payload.ep || '-', 时长: payload.durSec + 's', at: payload.at, 地址尾: '…' + String(payload.streams[0].video || payload.streams[0].playlist || '').slice(-26), 标题: String(payload.title || '').slice(0, 24) });
    send(payload);
    pausePage('已交付');
  }

  
  hookNet(function (u) {
    var st = site();
    if (st === 'bi') { if (u.indexOf('.m4s') > 0) biNote(u); }
    else if (st === 'qq') { if (u.indexOf('.m3u8') > 0) qqTake(u); }
  });
  setInterval(biTick, 1200);
  setInterval(biScanNewest, 1500);
  setInterval(qqTick, 1200);
  setInterval(function () {
    if (site() !== 'iq') return;
    iqLoadEps();
    iqGrab('定时');
  }, 4000);
  try { document.addEventListener('fullscreenchange', deliver); } catch (e) {}
  try { window.addEventListener('b0b-full', deliver); } catch (e2) {}

  
  
  window.__b0bClearSlots = function (why) {
    biSlot = null; qqSlot = null; iqSlot = null; biPend = {}; lastSent = '';
    
    try {
      if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen();
    } catch (e) {}
    try { window.__b0bRealFull = 0; } catch (e2) {}
    try { localStorage.removeItem('b0b:realFullAt'); } catch (e3) {}
    CH('⑨回网页', { 原因: why || '-', 站: site(), 真全屏: realFull() ? '真' : '非真', 时刻: nowMs() });
    markFullscreenBtn();
  };
  
  window.__b0bPageTitle = function () {
    var s2 = site();
    if (s2 === 'bi') return biTitle();
    if (s2 === 'qq') return qqTitle();
    if (s2 === 'iq') return iqHeadTitle();
    return String(document.title || '');
  };
  
  window.__b0bIqTakeover = function () { try { deliver(); } catch (e) {} };

  console.log('[b0b] 网页脚本已就绪（2026-10-24 重写版）：站=' + (site() || '其他'));
})();
