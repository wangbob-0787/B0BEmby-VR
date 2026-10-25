
/* 逻辑见 docs/slot-spec-2026-10-25/3-网页侧逻辑.md */
(function () {
  'use strict';

  /* 一、工具 */

  function CH(step, kv) {
    try {
      var p = [];
      for (var k in (kv || {})) p.push(k + '=' + kv[k]);
      console.log('[链]' + step + ' ' + p.join(' '));
    } catch (e) {}
  }
  function send(o) {
    try { if (window.b0bHost && window.b0bHost.post) { window.b0bHost.post(JSON.stringify(o)); return; } } catch (e) {}
    try { (window.__b0bOutbox = window.__b0bOutbox || []).push(JSON.stringify(o)); } catch (e2) {}
  }
  function site() {
    var h = String(location.hostname || '');
    if (/bilibili\.com$/.test(h)) return 'bi';
    if (/qq\.com$/.test(h)) return 'qq';
    if (/iqiyi\.com$/.test(h)) return 'iq';
    return '';
  }
  function clean(t) { return String(t || '').replace(/\s+/g, ' ').trim(); }
  function txt(sel) {
    try { var el = document.querySelector(sel); return el ? clean(el.textContent) : ''; } catch (e) { return ''; }
  }
  function noQuery(u) { var s = String(u || ''), i = s.indexOf('?'); return i > 0 ? s.slice(0, i) : s; }
  function mainVideo() {
    var vs = document.querySelectorAll('video'), best = null, bestA = 0;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect(), a = Math.max(0, r.width) * Math.max(0, r.height);
      if (a > bestA) { bestA = a; best = vs[i]; }
    }
    return best || document.querySelector('video');
  }
  function pageDur() {
    try { var v = mainVideo(); return v && v.duration > 0 && isFinite(v.duration) ? Math.round(v.duration) : 0; } catch (e) { return 0; }
  }
  var CN = { '零': 0, '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9 };
  function cn2num(s) {
    var str = String(s);
    if (/^\d+$/.test(str)) return String(parseInt(str, 10));
    var t = 0, cur = 0;
    for (var i = 0; i < str.length; i++) {
      var c = str.charAt(i);
      if (CN[c] !== undefined) { cur = CN[c]; continue; }
      if (c === '十') { t += (cur || 1) * 10; cur = 0; continue; }
      return str;
    }
    return String(t + cur);
  }

  /* 二、播放页判据 */

  var PAGES = {
    bi: /\/(video\/BV[0-9A-Za-z]+|bangumi\/play\/(?:ep|ss)\d+)/,
    qq: /\/x\/cover\/[A-Za-z0-9]+\/[A-Za-z0-9]+\.html/,
    iq: /\/v_[A-Za-z0-9]+\.html/
  };
  function onPlayPage() {
    var s = site();
    return !!(s && PAGES[s] && PAGES[s].test(String(location.pathname || '')));
  }

  /* 三、槽与交付 */

  var slot = { bi: null, qq: null, iq: null };
  var lastSent = '';

  function fingerprint(c) { return [c.id, c.title, c.dur, noQuery(c.v), noQuery(c.a)].join('|'); }
  function whyNotReady() {
    var s = site();
    if (!s) return '不在三站';
    if (!onPlayPage()) return '不是播放页';
    var c = slot[s];
    if (!c) return '槽空';
    if (!c.id) return '没有身份';
    if (!c.title) return '没有标题';
    if (!c.dur) return '时长还没读到';
    if (!c.v) return '没有画面地址';
    if (!c.a) return '没有声音地址';
    return '就绪';
  }
  function slotReady() { return whyNotReady() === '就绪'; }
  function pausePage(reason) {
    try {
      var vs = document.querySelectorAll('video'), n = 0;
      for (var i = 0; i < vs.length; i++) { if (vs[i] && !vs[i].paused) { try { vs[i].pause(); n++; } catch (e) {} } }
      console.log('[b0b] 已交给内核，网页视频停下（' + reason + '，' + n + ' 个）');
    } catch (e) {}
  }
  function streamsOf(s, c) {
    if (s === 'bi') return [{ video: c.v, audio: c.a }];
    if (s === 'qq') return [{ video: c.v, audio: '' }];
    return [{ video: '', audio: '', playlist: c.v, needProxy: 1 }];
  }
  function deliver() {
    if (!slotReady()) return;
    var s = site(), c = slot[s], sig = s + '|' + c.fp;
    if (sig === lastSent) return;
    lastSent = sig;
    var payload = {
      kind: 'play', site: s, frame: location.href,
      title: c.title, ep: String(c.ep || ''), season: String(c.season || ''),
      durSec: c.dur, quality: c.quality || '', cookie: '', at: c.at,
      streams: streamsOf(s, c)
    };
    if (s === 'iq') payload.needProxy = 1;
    CH('⑤交原生', { 站: s, 身份: c.id, 集: c.ep || '-', 时长: c.dur + 's', at: payload.at, 地址尾: '…' + String(c.v).slice(-24), 标题: String(c.title).slice(0, 24) });
    send(payload);
    pausePage('已交付');
  }
  function commit(s, cand) {
    if (!cand || !cand.id || !cand.title || !cand.dur || !cand.v || !cand.a) return;
    var fp = fingerprint(cand);
    if (slot[s] && slot[s].fp === fp) return;
    cand.fp = fp; cand.at = Date.now();
    slot[s] = cand;
    CH('②槽变', { 站: s, 身份: cand.id, 集: cand.ep || '-', 时长: cand.dur + 's', 档: cand.quality || '-', at: cand.at, 地址尾: '…' + String(cand.v).slice(-24), 标题: String(cand.title).slice(0, 24) });
    deliver();
  }

  /* 四、B站 */

  var biCtx = { key: '', cid: '', dur: 0, v: '', a: '', quality: '' };
  var biSegs = [];

  function biId() {
    var p = String(location.pathname || '');
    var m = /\/video\/(BV[0-9A-Za-z]+)/.exec(p);
    if (m) return m[1];
    m = /\/bangumi\/play\/(ep\d+)/.exec(p);
    return m ? m[1] : '';
  }
  function biTitle() {
    var t = clean(document.title).replace(/\s*[_|]\s*哔哩哔哩\s*[_|]?\s*bilibili\s*$/i, '');
    var cut = t.search(/[-_|]\s*(电视剧|电影|纪录片|番剧|综艺|国创|动画|全集|高清|正版|独家)\s*$/);
    if (cut > 0) t = t.slice(0, cut).trim();
    var seasonM = /第\s*([零一二三四五六七八九十\d]+)\s*季/.exec(t);
    var series = clean(t.replace(/第\s*[零一二三四五六七八九十\d]+\s*[季集期话][^-_|]*/g, '')) || t;
    var ov = txt('.bpx-player-top-title') || txt('[class*="player-top-title"]');
    var mOv = /第\s*([零一二三四五六七八九十\d]+)\s*([集期话])\s*(.*)$/.exec(ov);
    if (mOv) {
      var out = series;
      if (seasonM) out += ' 第' + cn2num(seasonM[1]) + '季';
      out += ' 第' + cn2num(mOv[1]) + mOv[2];
      var nm = clean(mOv[3]);
      if (nm) out += ' ' + nm;
      return clean(out);
    }
    if (/^(正片|预告|花絮|片段|特辑|彩蛋)$/.test(ov)) ov = '';
    if (ov && series && ov.indexOf(series) < 0 && series.indexOf(ov) < 0) return clean(series + '(' + ov + ')');
    t = t.replace(/第\s*([零一二三四五六七八九十\d]+)\s*季/g, function (x, d) { return ' 第' + cn2num(d) + '季'; });
    t = t.replace(/第\s*([零一二三四五六七八九十\d]+)\s*([集期话])/g, function (x, d, u) { return ' 第' + cn2num(d) + u; });
    return clean(t);
  }
  function biEpNo() {
    var ov = txt('.bpx-player-top-title') || txt('[class*="player-top-title"]');
    var m = /第\s*([零一二三四五六七八九十\d]+)\s*[集期话]/.exec(ov) || /第\s*([零一二三四五六七八九十\d]+)\s*[集期话]/.exec(biTitle());
    return m ? cn2num(m[1]) : '';
  }
  function biSeasonNo() {
    var m = /第\s*([零一二三四五六七八九十\d]+)\s*季/.exec(String(document.title || ''));
    return m ? cn2num(m[1]) : '';
  }
  function biSeg(u) {
    var one = String(u || '');
    if (one.indexOf('/upgcxcode/') < 0 || one.indexOf('.m4s') < 0) return;
    biSegs.push(one);
    if (biSegs.length > 80) biSegs.shift();
  }
  function biSegPair() {
    var v = '', a = '';
    for (var i = 0; i < biSegs.length; i++) {
      var nm = biSegs[i];
      var mDir = /\/upgcxcode\/\d+\/\d+\/(\d+)\//.exec(nm);
      if (!mDir) continue;
      if (biCtx.cid && mDir[1] !== biCtx.cid) continue;
      var mq = /-1-(\d+)\.m4s/.exec(nm);
      if (!mq) continue;
      if (/^(?:302|303)\d{2}$/.test(mq[1])) a = nm; else v = nm;
    }
    return { v: v, a: a };
  }
  function biAsk(id, cid) {
    var u;
    if (/^ep\d+$/.test(id)) u = 'https://api.bilibili.com/pgc/player/web/playurl?ep_id=' + id.slice(2) + '&qn=80&fnver=0&fnval=4048&fourk=1';
    else if (cid) u = 'https://api.bilibili.com/x/player/playurl?bvid=' + id + '&cid=' + cid + '&qn=80&fnver=0&fnval=4048&fourk=1';
    else return;
    fetch(u, { credentials: 'include' }).then(function (r) { return r.json(); }).then(function (j) {
      var d = (j && (j.result || j.data)) || {}, dash = d.dash || {};
      var vs = dash.video || [], as = dash.audio || [];
      if (!vs.length) return;
      var best = vs[0], bq = -1;
      for (var i = 0; i < vs.length; i++) {
        var q = (vs[i].id || 0) * 1e6 + (vs[i].bandwidth || 0);
        if (q > bq) { bq = q; best = vs[i]; }
      }
      var au = as[0], ab = -1;
      for (var k = 0; k < as.length; k++) { if ((as[k].bandwidth || 0) > ab) { ab = as[k].bandwidth || 0; au = as[k]; } }
      biCtx.v = String(best.baseUrl || best.base_url || '');
      biCtx.a = au ? String(au.baseUrl || au.base_url || '') : '';
      biCtx.quality = String(best.id || '');
      var ms = Number(d.timelength || 0);
      biCtx.dur = ms > 0 ? Math.round(ms / 1000) : Math.round(Number(dash.duration || 0));
      CH('②取址', { 站: 'bi', 身份: id, 档: biCtx.quality || '-', 时长: biCtx.dur + 's' });
    }).catch(function (e) { console.log('[b0b] B站 播放信息请求失败：' + e); });
  }
  function biSync() {
    var id = biId(), key = String(location.pathname);
    if (!id || key === biCtx.key) return;
    biCtx = { key: key, cid: '', dur: 0, v: '', a: '', quality: '' };
    biSegs = [];
    CH('①页面变化', { 站: 'bi', 身份: id });
    if (/^ep\d+$/.test(id)) { biAsk(id, ''); return; }
    fetch('https://api.bilibili.com/x/web-interface/view?bvid=' + id, { credentials: 'include' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        var d = (j && j.data) || {};
        biCtx.cid = String(d.cid || '');
        biCtx.dur = parseInt(d.duration || 0, 10) || 0;
        biAsk(id, biCtx.cid);
      }).catch(function (e) { console.log('[b0b] B站 取编号失败：' + e); });
  }
  function biTick() {
    if (site() !== 'bi' || !onPlayPage()) return;
    biSync();
    var id = biId();
    if (!id) return;
    var seg = biSegPair();
    commit('bi', {
      id: id, ep: biEpNo(), season: biSeasonNo(), title: biTitle(),
      dur: biCtx.dur || pageDur(),
      v: seg.v || biCtx.v, a: seg.a || biCtx.a, quality: biCtx.quality
    });
  }

  /* 五、腾讯 */

  var QQ_TIERS = { 'f322100': '臻彩MAX', 'f322104': '臻彩MAX', 'f322093': '4K', 'f322150': '臻彩1080P' };
  var qqCtx = { url: '', name: '', dur: 0 };

  function qqId() {
    var m = String(location.pathname).match(/\/x\/cover\/[A-Za-z0-9]+\/([A-Za-z0-9]+)\.html/i);
    return m ? m[1] : '';
  }
  function qqTitle() {
    var ov = txt('.player-play-title__title'), sub = txt('.player-play-title__sub-title');
    var series = clean(String(document.title || '').split('_')[0]);
    if (/^\s*第\s*\d+\s*[集期话]/.test(series)) series = '';
    var mOv = /第\s*(\d{1,4})\s*([集期话])\s*[：:]?\s*(.*)$/.exec(ov);
    if (mOv) {
      var ep = parseInt(mOv[1], 10) || 0, unit = mOv[2] || '集', nm = clean(mOv[3]) || sub;
      if (!series) return clean(ov);
      var out = series + (ep ? ' 第' + ep + unit : '');
      if (nm && ep) out += ' ' + nm;
      return clean(out);
    }
    if (ov) return sub && sub !== ov ? clean(ov + ' ' + sub) : clean(ov);
    return clean(series);
  }
  function qqEpNo() {
    var m = /第\s*(\d{1,4})\s*[集期话]/.exec(txt('.player-play-title__title') || qqTitle());
    return m ? String(parseInt(m[1], 10)) : '';
  }
  function qqPlaylistDur(url) {
    if (!url) return;
    fetch(url, { credentials: 'include' }).then(function (r) { return r.text(); }).then(function (t) {
      var n = 0;
      String(t).replace(/#EXTINF:([\d.]+)/g, function (m, d) { n += parseFloat(d) || 0; return m; });
      if (n > 0) qqCtx.dur = Math.round(n);
    }).catch(function () {});
  }
  function qqTake(u) {
    var one = String(u || '').replace(/\\\//g, '/');
    var m = one.match(/\.(f\d{6})\.(?:ts\.)?m3u8/);
    if (!m) return;
    var name = QQ_TIERS[m[1]];
    if (!name) return;
    if (/smtcdns|subtitle|\.vtt\./.test(one)) return;
    if (!qqId()) return;
    if (qqCtx.url === one) return;
    qqCtx = { url: one, name: name, dur: 0 };
    CH('②取址', { 站: 'qq', 档: name + '(' + m[1] + ')', 地址尾: '…' + one.slice(-24) });
    qqPlaylistDur(one);
  }
  function qqTick() {
    if (site() !== 'qq' || !onPlayPage()) return;
    var id = qqId();
    if (!id) return;
    commit('qq', {
      id: id, ep: qqEpNo(), title: qqTitle(),
      dur: qqCtx.dur || pageDur(),
      v: qqCtx.url, a: qqCtx.url, quality: qqCtx.name
    });
  }

  /* 六、爱奇艺 */

  var IQ_SALT = 'ulc2h7tka0mdrf2lkb1n6m6mulc2htbn';
  var IQ_CONST = { t: '0', us: '', kuid: '', kt: '0', vf: '', hevc: '0', qd: '0', r: '1', s: '1', cp: '14', p: '1', pn: '0', p1: '1', q: '1', o: '0', cmd: '0' };
  var iqEps = { aid: '', list: [] };
  var iqBusy = false;
  var iqDone = { tvid: '', at: 0 };

  function iqCookie(name) {
    try { var m = String(document.cookie || '').match(new RegExp('(?:^|; )' + name + '=([^;]*)')); return m ? decodeURIComponent(m[1]) : ''; } catch (e) { return ''; }
  }
  function iqTitle() {
    var t = txt('.iqp-title_left') || txt('[class*="play_title"]') || clean(document.title);
    return clean(t.replace(/[-_]\s*爱奇艺.*$/, '').replace(/【.*?】/g, ''));
  }
  function iqEpNo() {
    var m = /第\s*(\d{1,4})\s*集/.exec(iqTitle());
    return m ? String(parseInt(m[1], 10)) : '';
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
  function iqUrl(tvid) {
    var sb = '';
    for (var k in IQ_CONST) sb += k + '=' + IQ_CONST[k] + '&';
    sb += 'tvid=' + tvid + '&bid=600&vid=&uid=' + iqCookie('P00003') + '&k_uid=' + iqCookie('QC005') +
      '&dfp=' + String(localStorage.getItem('__dfp') || '').split('@')[0] + '&authKey=&tm=' + Date.now();
    var path = '/dash?' + sb;
    return 'https://cache.video.iqiyi.com' + path + '&vf=' + md5(path + IQ_SALT);
  }
  function iqParse(body) {
    var j = JSON.parse(body), data = j.data || {};
    var vlist = (data.vidl || [])[0] || {};
    var pl = vlist._bl || {};
    var best = null, bestN = 0;
    for (var k in pl) {
      var cand = pl[k];
      if (!cand || !cand.vs) continue;
      var n = String(cand.vs).length;
      if (n > bestN) { bestN = n; best = cand; }
    }
    if (!best) return null;
    var segs = 0, dur = 0;
    String(best.vs).split('-').forEach(function (x) {
      var parts = x.split('.');
      if (parts.length >= 3) { segs++; dur += parseInt(parts[2], 10) || 0; }
    });
    return { playlist: String(best.vs), segs: segs, dur: dur || parseInt(best.duration || 0, 10) || 0, bid: parseInt(best.bid || 0, 10) || 0 };
  }
  function iqAlbumIds() {
    var out = [];
    function add(v) { var s = String(v || ''); if (s && out.indexOf(s) < 0) out.push(s); }
    try {
      var vi = ((window.QiyiPlayerProphetData || {}).videoInfo) || {};
      add(vi.albumId);
      if (vi.album) add(vi.album.id);
    } catch (e) {}
    try {
      var es = performance.getEntriesByType('resource');
      for (var i = es.length - 1; i >= 0; i--) {
        var m = /[?&]album_id=(\d{6,})/.exec(es[i].name || '');
        if (m) add(m[1]);
      }
    } catch (e2) {}
    try { add((window.QiyiPlayerProphetData || {}).albumId); } catch (e3) {}
    return out;
  }
  function iqSeriesName() {
    var t = iqTitle();
    return /第\s*\d+\s*集/.test(t) ? clean(t.replace(/第\s*\d+\s*集[^]*$/, '')) : t;
  }
  function iqLoadEps() {
    if (iqEps.list.length) return;
    var cands = iqAlbumIds(), sn = iqSeriesName(), i = 0;
    function next() {
      if (i >= cands.length) return;
      var aid = cands[i++];
      fetch('https://pcw-api.iqiyi.com/albums/album/avlistinfo?aid=' + aid + '&page=1&size=200', { credentials: 'include' })
        .then(function (r) { return r.json(); })
        .then(function (j) {
          var list = (((j || {}).data || {}).epsodelist) || [];
          var n0 = list.length ? clean(list[0].name) : '';
          if (n0 && sn && n0.indexOf(sn) === 0) { iqEps = { aid: aid, list: list }; CH('②分集表', { 站: 'iq', 集数: list.length }); }
          else next();
        }).catch(function () { next(); });
    }
    next();
  }
  function iqTvid() {
    var ep = parseInt(iqEpNo() || '0', 10), l = iqEps.list;
    if (!ep) {
      try {
        var vi = ((window.QiyiPlayerProphetData || {}).videoInfo) || {};
        if (vi.tvId) return String(vi.tvId);
      } catch (e) {}
      return l.length ? String(l[0].tvId || '') : '';
    }
    var it = l[ep - 1];
    return it ? String(it.tvId || '') : '';
  }
  function iqGrab() {
    if (iqBusy) return;
    var tvid = iqTvid();
    if (!tvid) return;
    if (iqDone.tvid === tvid && Date.now() - iqDone.at < 15000) return;
    iqBusy = true;
    try {
      var x = new XMLHttpRequest();
      x.open('GET', iqUrl(tvid), true);
      x.onload = function () {
        iqBusy = false;
        try {
          var r = iqParse(x.responseText);
          if (!r || !r.playlist) return;
          iqDone = { tvid: tvid, at: Date.now() };
          commit('iq', {
            id: tvid, ep: iqEpNo(), title: iqTitle(), dur: r.dur,
            v: r.playlist, a: r.playlist, quality: r.bid === 600 ? '1080P' : ''
          });
        } catch (e) { console.log('[b0b] 爱奇艺解析出错：' + e); }
      };
      x.onerror = function () { iqBusy = false; };
      x.send();
    } catch (e) { iqBusy = false; }
  }
  function iqTick() {
    if (site() !== 'iq' || !onPlayPage()) return;
    iqLoadEps();
    iqGrab();
  }

  /* 七、爱奇艺网页高度 */

  var IQ_BOX = ['#root', '[class*="app_App"]', '[class*="pages_pages"]', '[class*="pages_page__"]', '[class*="pages_container"]'];

  function iqFixHeight() {
    if (site() !== 'iq') return;
    var H = window.innerHeight || document.documentElement.clientHeight || 0;
    if (!H) return;
    var n = 0, names = [];
    for (var i = 0; i < IQ_BOX.length; i++) {
      var els = document.querySelectorAll(IQ_BOX[i]);
      for (var k = 0; k < els.length; k++) {
        var el = els[k];
        if (el.clientHeight >= H) continue;
        el.style.height = H + 'px';
        el.style.minHeight = H + 'px';
        el.style.overflowY = 'auto';
        n++;
        if (names.length < 5) names.push(IQ_BOX[i]);
      }
    }
    var all = document.body ? document.body.querySelectorAll('*') : [], m = 0;
    for (var q = 0; q < all.length && m < 12; q++) {
      var e2 = all[q];
      if (e2.clientHeight > 8 || !e2.scrollHeight || e2.scrollHeight < H / 2) continue;
      var st = window.getComputedStyle(e2);
      if (st.display === 'none' || st.visibility === 'hidden') continue;
      e2.style.height = H + 'px';
      e2.style.minHeight = H + 'px';
      m++;
      if (names.length < 5) names.push(e2.className ? '.' + String(e2.className).split(' ')[0] : e2.tagName);
    }
    if (n + m > 0) console.log('[链]⑦撑高 根链=' + n + ' 空高=' + m + ' ' + names.join(','));
  }

  /* 八、按钮灰/实 */

  var FS_SEL = '.bpx-player-ctrl-full, .txp_btn_fullscreen, .txp_btn_fscreen, .iqp-btn-fullscreen, .iqp-player-icon-fullscreen';
  var fsState = -1, fsPend = 0;

  function markFs() {
    try {
      var ok = slotReady() ? 1 : 0, btns = document.querySelectorAll(FS_SEL);
      if (ok !== fsState) {
        if (fsPend !== ok) { fsPend = ok; return; }
        fsState = ok;
        CH(ok ? '③按钮变实' : '③按钮变灰', { 站: site(), 原因: ok ? '槽里这份齐了' : whyNotReady(), 按钮数: btns.length });
      }
      for (var i = 0; i < btns.length; i++) {
        var b = btns[i];
        if (ok) {
          if (b.getAttribute('data-b0b-gray')) { b.style.pointerEvents = ''; b.style.opacity = ''; b.removeAttribute('data-b0b-gray'); }
        } else if (b.getAttribute('data-b0b-gray') !== '1') {
          b.style.pointerEvents = 'none';
          b.style.opacity = '0.35';
          b.setAttribute('data-b0b-gray', '1');
        }
      }
    } catch (e) {}
  }
  function guardFullscreen() {
    try {
      var raw = Element.prototype.requestFullscreen;
      if (!raw || raw.__b0bGuarded) return;
      var patched = function () {
        if (!slotReady()) { CH('④点全屏-拦下', { 站: site(), 原因: whyNotReady() }); return Promise.reject(new Error('b0b-slot-not-ready')); }
        CH('④点全屏-放行', { 站: site(), 时长: ((slot[site()] && slot[site()].dur) || 0) + 's' });
        return raw.apply(this, arguments);
      };
      patched.__b0bGuarded = 1;
      Element.prototype.requestFullscreen = patched;
    } catch (e) {}
  }

  /* 九、网络钩子 */

  function qqPatchBody(b) {
    try {
      if (typeof b === 'string' && b.indexOf('vinfoparam') >= 0 && b.indexOf('spvvc') < 0) {
        return b + '&spvvc=3&sphevcfps=52776560265087';
      }
    } catch (e) {}
    return b;
  }
  function hookNet() {
    try {
      var oF = window.fetch;
      if (oF && !oF.__b0bHooked) {
        var nf = function () {
          var a = [].slice.call(arguments);
          try {
            var u = (typeof a[0] === 'string') ? a[0] : (a[0] && a[0].url), us = String(u || '');
            if (site() === 'qq') {
              if (us.indexOf('qq.com') >= 0 && a[1] && typeof a[1].body === 'string') {
                a[1] = Object.assign({}, a[1], { body: qqPatchBody(a[1].body) });
              }
              if (us.indexOf('.m3u8') > 0) qqTake(us);
            } else if (site() === 'bi' && us.indexOf('.m4s') > 0) {
              biSeg(us);
            }
          } catch (e) {}
          return oF.apply(window, a);
        };
        nf.__b0bHooked = 1;
        window.fetch = nf;
      }
    } catch (e) {}
    try {
      var oOpen = XMLHttpRequest.prototype.open, oSend = XMLHttpRequest.prototype.send;
      if (oOpen && !oOpen.__b0bHooked) {
        var np = function () { try { this.__b0bU = arguments[1]; } catch (e) {} return oOpen.apply(this, arguments); };
        np.__b0bHooked = 1;
        XMLHttpRequest.prototype.open = np;
        XMLHttpRequest.prototype.send = function (b) {
          try {
            var u = String(this.__b0bU || '');
            if (site() === 'qq') {
              if (u.indexOf('qq.com') >= 0) b = qqPatchBody(b);
              if (u.indexOf('.m3u8') > 0) this.addEventListener('load', function () { qqTake(u); });
            } else if (site() === 'bi' && u.indexOf('.m4s') > 0) {
              biSeg(u);
            }
          } catch (e2) {}
          return oSend.call(this, b);
        };
      }
    } catch (e3) {}
  }

  /* 十、定时与对外接口 */

  function tick() {
    var s = site();
    if (!s) return;
    if (s === 'bi') biTick();
    else if (s === 'qq') qqTick();
    else iqTick();
    markFs();
  }
  function reportRect() {
    try {
      var v = mainVideo();
      if (!v) return;
      var r = v.getBoundingClientRect(), W = window.innerWidth || 1, H = window.innerHeight || 1;
      var area = Math.max(0, r.width) * Math.max(0, r.height);
      if (area <= 0) return;
      send({
        kind: 'rect', frame: location.href, cookie: String(document.cookie || ''), urls: [],
        rect: {
          x: r.left / W, y: r.top / H, w: r.width / W, h: r.height / H,
          ar: v.videoHeight > 0 ? (v.videoWidth / v.videoHeight) : 0,
          area: area / (W * H)
        }
      });
    } catch (e) {}
  }
  function scanQQ() {
    if (site() !== 'qq' || qqCtx.url) return;
    try {
      var es = performance.getEntriesByType('resource');
      for (var i = es.length - 1; i >= 0; i--) {
        var nm = es[i].name || '';
        if (nm.indexOf('.m3u8') > 0) { qqTake(nm); return; }
      }
    } catch (e) {}
  }

  window.__b0bClearSlots = function (why) {
    slot = { bi: null, qq: null, iq: null };
    lastSent = ''; fsState = -1; fsPend = 0;
    biCtx = { key: '', cid: '', dur: 0, v: '', a: '', quality: '' }; biSegs = [];
    qqCtx = { url: '', name: '', dur: 0 };
    iqDone = { tvid: '', at: 0 };
    try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen(); } catch (e) {}
    try { window.__b0bRealFull = 0; } catch (e2) {}
    try { localStorage.removeItem('b0b:realFullAt'); } catch (e3) {}
    CH('⑨回网页', { 原因: why || '-', 站: site(), 时刻: Date.now() });
    markFs();
  };
  window.__b0bPageTitle = function () {
    var s = site();
    if (s === 'bi') return biTitle();
    if (s === 'qq') return qqTitle();
    if (s === 'iq') return iqTitle();
    return clean(document.title);
  };
  window.__b0bIqTakeover = function () { deliver(); };

  hookNet();
  guardFullscreen();
  setInterval(tick, 1200);
  setInterval(markFs, 700);
  setInterval(iqFixHeight, 800);
  setInterval(reportRect, 400);
  setInterval(scanQQ, 1500);
  console.log('[b0b] 网页脚本已就绪（2026-10-25 版）：站=' + (site() || '其他'));
})();
