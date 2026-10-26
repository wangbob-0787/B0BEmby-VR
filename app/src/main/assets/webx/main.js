
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
  /*
   * 集号与集名的唯一来源 = 选集面板里"正在播的那一项"（父亲 2026-10-26 定）。
   * 页面标题只能拿剧名：切集后它常还挂着上一集（实测《小城良方》播第 2 集、标题写第 1 集），
   * 而面板选中项是页面自己维护的当前位置，点哪一集它就是哪一集。
   * 实测（2026-10-26 .18 Chrome 现场读 DOM）：
   *   腾讯 容器 .episode-list、项 .episode-item、当前项多一个 selected；
   *        文本是集号（"1"），title 属性是集名（"厂长横尸铁轨！癫狂命案重现"）。
   *   爱奇艺 电视剧项带 episodes_playingItem 类；综艺没有类名差分，靠期号文字颜色区分。
   *   B站 CSS Modules，当前项类名带 activeItem；老版是 .ep-item.cur。
   */
  function parsePanelEl(el) {
    if (!el) return null;
    var raw = clean(el.textContent || '');
    var ttl = clean(el.getAttribute('title') || '');
    var no = '', kind = '集', name = '';
    var m = /第\s*([零一二三四五六七八九十百\d]+)\s*([集话期回部])/.exec(raw);
    if (m) {
      no = cn2num(m[1]);
      kind = m[2];
      name = clean(String(raw).slice(raw.indexOf(m[0]) + m[0].length));
    } else {
      var m2 = /^0*(\d{1,3})(?:\s|$|[集话期回部])/.exec(raw);
      if (m2) no = String(parseInt(m2[1], 10));
      name = clean(raw.replace(/^0*\d{1,3}\s*/, ''));
    }
    if (!name) name = ttl;
    return (no || name) ? { no: no, kind: kind, name: name } : null;
  }
  function pickPanelEp(sels) {
    for (var i = 0; i < sels.length; i++) {
      var el = null;
      try { el = document.querySelector(sels[i]); } catch (e) { el = null; }
      var r = parsePanelEl(el);
      if (r) return r;
    }
    return null;
  }
  function cn2num(s) {
    var str = String(s);
    if (/^\d+$/.test(str)) return String(parseInt(str, 10));
    var n = 0, cur = 0, c;
    for (var i = 0; i < str.length; i++) {
      c = str.charAt(i);
      if (CN[c] !== undefined) { cur = CN[c]; continue; }
      if (c === '十') { n += (cur || 1) * 10; cur = 0; continue; }
      if (c === '百') { n += (cur || 1) * 100; cur = 0; continue; }
      return str;
    }
    return String(n + cur);
  }

  /* 标题过滤（三站共用；口径见 docs/slot-spec-2026-10-25/3-网页侧逻辑.md 第 3 节）

     一条页面标题常叠着多层尾巴：站点后缀、分类词、画质、版本、语言、括号注解。
     所以按词表剥，剥到剥不动为止，再收尾。分隔符只认这几个：- _ | · — 以及空格。 */

  var T_SEP = /[-_|·—]/;
  var T_WORDS = [
    /* 站点名 */ '哔哩哔哩', 'bilibili', '腾讯视频', '爱奇艺', '优酷', '芒果tv',
    /* 分类词 */ '电视剧', '连续剧', '电影', '综艺', '动漫', '纪录片', '番剧', '国创', '少儿', '影视', '全集',
    /* 类型词（带「节目」二字） */ '综艺节目', '真人秀', '脱口秀', '体育节目', '娱乐节目', '资讯', '晚会',
    /* 画质与版本 */ '杜比视界', '1080p', '4k', 'hdr', '高清', '超清', '蓝光', '完整版', '正版', '独播', '独家', '加长版', '会员版', '免费', 'vip',
    /* 花絮类 */ '预告', '花絮', '片段', '正片', '抢先看', '特辑', '彩蛋',
    /* 语言版本 */ '普通话', '粤语', '潮汕话', '客家话', '闽南语', '国语', '原声', '中配', '台配', '日语', '英语'
  ];
  var T_LANG = ['普通话', '粤语', '潮汕话', '客家话', '闽南语', '国语', '原声', '中配', '台配', '日语', '英语'];
  var T_JUNK = ['选集', '选集列表', '播放列表', '相关推荐', '猜你喜欢'];
  /* 营销尾巴是一整段：画质 + 版本 + 视频 + 在线观看，中间没有分隔符 */
  var T_TAIL = /^(高清|超清|蓝光|4k|1080p|hdr|杜比视界)?(完整版|正版|独播|独家|免费|vip|会员版|加长版)?(视频)?(在线观看|在线播放|观看地址|观看|播放)版?$/;

  function lk(s) { return clean(String(s || '')).toLowerCase(); }

  /* 最后一段是垃圾尾巴：词表里的一个词、词加「版」、营销尾巴整段，或若干词表词连在一起（如「电视剧全集」） */
  function isWord(s) {
    var x = lk(s), left, hit, i;
    if (!x || x.length > 24) return false;
    if (T_TAIL.test(x)) return true;
    for (i = 0; i < T_WORDS.length; i++) {
      if (x === T_WORDS[i] || x === T_WORDS[i] + '版') return true;
    }
    left = x;
    while (left) {
      hit = '';
      for (i = 0; i < T_WORDS.length; i++) {
        if (left.indexOf(T_WORDS[i] + '版') === 0 && T_WORDS[i].length + 1 > hit.length) hit = T_WORDS[i] + '版';
        else if (left.indexOf(T_WORDS[i]) === 0 && T_WORDS[i].length > hit.length) hit = T_WORDS[i];
      }
      if (!hit) return false;
      left = left.slice(hit.length);
    }
    return true;
  }

  /* 剥一次：结尾是括号注解、或最后一段是词表里的词，就砍掉。剥不动返回 null */
  function cutTail(t) {
    var s = clean(t), p, pre, last = -1, i;
    if (!s) return null;
    p = /\s*[（(][^（）()]{1,16}[）)]\s*$/.exec(s);
    if (p) {
      pre = clean(s.slice(0, p.index));
      if (pre.length >= 2) return pre;
    }
    for (i = 0; i < s.length; i++) { if (T_SEP.test(s.charAt(i))) last = i; }
    if (last < 0) return null;
    var left = clean(s.slice(0, last)), right = clean(s.slice(last).replace(/^[-_|·—\s]+/, ''));
    if (left.length < 2 || !isWord(right)) return null;
    return left;
  }

  /* 循环剥尾，一轮剥不动就停，上限 6 轮；再收尾三步 */
  function fTitle(t) {
    var s = clean(t), n = 0, c;
    while (n++ < 6) { c = cutTail(s); if (c === null) break; s = c; }
    s = s.replace(/\[[^\]]{0,4}\]|【[^】]{0,4}】|[（）()\[\]【】]{2}/g, '');
    s = clean(s).replace(/\s{2,}/g, ' ').replace(/^[-_|·—\s]+|[-_|·—\s]+$/g, '');
    return s.length > 80 ? s.slice(0, 80) : s;
  }

  /* 整段不要、退回下一个取值来源的四条。
     第一条加了「不含中日文汉字」的例外：「刺马」「霸王」这类两字片名要留住。 */
  function isJunkTitle(t) {
    var s = lk(t), m;
    if (s.length <= 2 && !/[\u4e00-\u9fa5]/.test(s)) return true;
    if (/^第\s*[零一二三四五六七八九十\d]+\s*[集话期]/.test(s)) return true;
    m = /([\u4e00-\u9fa5a-z]+)版\s*$/.exec(s);
    if (m && T_LANG.indexOf(m[1]) >= 0) return true;
    return T_JUNK.indexOf(s) >= 0;
  }

  /* 拼装：单集片只给片名；分集片「剧名 第X季 第X集 集名」。季号抽不到就不写季。
     剧名里若已含集名先摘掉，否则会拼成「剧名 集名 第X集 集名」。 */
  function buildTitle(series, ep, unit, season, epName) {
    var s = fTitle(series);
    if (isJunkTitle(s)) return '';
    if (!ep) return s;
    var nm = fTitle(epName);
    var keep = nm && nm.length > 1 && nm.length <= 40 && nm.indexOf('第') < 0;
    s = s.replace(/第\s*[零一二三四五六七八九十\d]+\s*[季集话期]/g, ' ');
    if (keep) s = s.replace(nm, ' ');
    s = clean(s).replace(/\s{2,}/g, ' ');
    if (s.length < 2) return s;
    var out = s + (season ? ' 第' + cn2num(season) + '季' : '') + ' 第' + cn2num(ep) + (unit || '集');
    if (keep) out += ' ' + nm;
    return out.length > 80 ? out.slice(0, 80) : out;
  }

  /* 页面当前画幅比：起播前就交给原生，不必等内核解出第一帧 */
  function pageAr() {
    try {
      var v = mainVideo();
      return v && v.videoWidth > 0 ? v.videoWidth / v.videoHeight : 0;
    } catch (e) { return 0; }
  }

  /* 二、播放页判据 */

  var PAGES = {
    bi: /\/(video\/BV[0-9A-Za-z]+|bangumi\/play\/(?:ep|ss)\d+)/,
    qq: /\/x\/cover\/[A-Za-z0-9]+(?:\/[A-Za-z0-9]+)?\.html/,
    iq: /\/v_[A-Za-z0-9]+\.html/
  };
  function onPlayPage() {
    var s = site();
    return !!(s && PAGES[s] && PAGES[s].test(String(location.pathname || '')));
  }

  /* 三、槽与交付 */

  /* 页面刚加载时内核会重试注入，脚本因此可能被 eval 多遍；腾讯一条片只请求一次清单，
     收集到的数据必须挂在 window 上，否则重新 eval 一次就清空，槽永远空着、按钮永远灰。 */
  var S = window.__b0bState || (window.__b0bState = {
    slot: { bi: null, qq: null, iq: null },
    lastSent: '',
    qqCtx: { url: '', name: '', dur: 0, ep: '' },
  });
  var slot = S.slot;

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
    if (sig === S.lastSent) return;
    S.lastSent = sig;
    var payload = {
      kind: 'play', site: s, frame: location.href,
      title: c.title, ep: String(c.ep || ''), season: String(c.season || ''),
      durSec: c.dur, quality: c.quality || '', cookie: '', at: c.at,
      ar: c.ar || 0,
      streams: streamsOf(s, c)
    };
    /* 弹幕编号：B站 走 cid、腾讯走 vid。爱奇艺的片号在取流时就记下了，不从交付里拿 */
    if (s === 'iq') payload.needProxy = 1;
    else if (s === 'qq') payload.vid = c.id;
    else if (c.cid) payload.cid = String(c.cid);
    CH('⑤交原生', { 站: s, 身份: c.id, 集: c.ep || '-', 时长: c.dur + 's', at: payload.at, 地址尾: '…' + String(c.v).slice(-24), 标题: String(c.title).slice(0, 24) });
    send(payload);
  }
  /* 只认真地址：钩子偶尔会把夹着 JSON 片段的长串当成地址，
     交给内核就是"时长对、进度永远 0:00"（父亲 2026-10-25 报）。 */
  function looksUrl(u) {
    var t = String(u || '');
    return /^https?:\/\//.test(t) && t.indexOf('{') < 0 && t.indexOf('"') < 0 && t.indexOf(' ') < 0;
  }
  function commit(s, cand) {
    if (!cand || !cand.id || !cand.dur || !cand.v || !cand.a) return;
    if (!looksUrl(cand.v) || !looksUrl(cand.a)) return;
    if (isJunkTitle(cand.title || '')) return;   /* 频道页标题剥完只剩站点名或分类词，不入槽 */
    var fp = fingerprint(cand);
    if (slot[s] && slot[s].fp === fp) return;
    cand.fp = fp; cand.at = Date.now();
    slot[s] = cand;
    CH('②槽变', { 站: s, 身份: cand.id, 集: cand.ep || '-', 时长: cand.dur + 's', 档: cand.quality || '-', at: cand.at, 地址尾: '…' + String(cand.v).slice(-24), 标题: String(cand.title).slice(0, 24) });
    deliver();
  }

  /* 四、B站 */

  var biCtx = S.biCtx || (S.biCtx = { key: '', cid: '', dur: 0, v: '', a: '', quality: '' });
  var biSegs = [];

  /*
   * B站的身份必须带页码（父亲 2026-10-26 报：同一视频切第 2P 后控制条与画面不符）。
   * 多P 视频是不同的片子，但网址里只有 ?p=N 不同 —— 只认 BV 号会把它们当成同一份，
   * 地址永远停在第 1P。ep 号本身唯一，不需要附加。
   */
  function biPageNo() {
    var m = /[?&]p=(\d+)/.exec(String(location.search || ''));
    var n = m ? parseInt(m[1], 10) : 1;
    return n > 0 ? n : 1;
  }
  function biBv() {
    var m = /\/video\/(BV[0-9A-Za-z]+)/.exec(String(location.pathname || ''));
    return m ? m[1] : '';
  }
  /*
   * 季页面（/bangumi/play/ss<季号>）也要认得出正在播哪一集。
   *
   * B站 的推荐位很多是指向「整部片的入口」，点下去地址变成 ss 开头、里面没有集号
   * （实测：从《魔方小姐》点《10间赶死队》→ ss38561；再点《唐探1900》同样进季页）。
   * 认不出身份 → 槽一直是空的 → 真全屏按钮一直是灰的，整条接管卡死。
   * 取法：播放窗口下面那块分集面板里，正在播的那一项带 cur/active 类，它的链接里就有集号。
   */
  /*
   * 实证（2026-10-26，.18 Chrome 打开 /bangumi/play/ss38561 现场读 DOM）：
   *   分集面板是 CSS Modules，类名带哈希 —— 普通项 EpVirtList_listItem__xxx、
   *   正在播的那项 EpVirtList_activeItem__xxx；而且这类页面**没有** __INITIAL_STATE__。
   * 所以只按「class 里带 activeItem」这一条取，其余候选留着兜老版与别的页型。
   */
  var BI_EP_LINK_SEL = [
    'a[href*="/play/ep"][class*="activeItem"]',
    'a[href*="/play/ep"][class*="cur"]',
    'a[href*="/play/ep"][class*="active"]',
    '.eplist_ep_list .ep-item.cur a[href*="/play/ep"]',
    '.eplist_ep_item.cur a[href*="/play/ep"]',
    '.ep-list .ep-item.cur a[href*="/play/ep"]'
  ];
  function biEpFromDom() {
    for (var i = 0; i < BI_EP_LINK_SEL.length; i++) {
      var a = null;
      try { a = document.querySelector(BI_EP_LINK_SEL[i]); } catch (e) { a = null; }
      if (!a) continue;
      var m = /\/play\/(ep\d+)/.exec(String(a.getAttribute('href') || ''));
      if (m) return m[1];
    }
    return '';
  }
  function biEpFromState() {
    try {
      var st = window.__INITIAL_STATE__;
      var ep = st && st.epInfo && st.epInfo.episode_id;
      if (ep) return 'ep' + ep;
    } catch (e) {}
    return '';
  }
  function biId() {
    var bv = biBv();
    if (bv) {
      var n = biPageNo();
      return n > 1 ? bv + '#p' + n : bv;
    }
    var m = /\/bangumi\/play\/(ep\d+)/.exec(String(location.pathname || ''));
    if (m) return m[1];
    if (/\/bangumi\/play\/ss\d+/.test(String(location.pathname || ''))) {
      return biEpFromDom() || biEpFromState();
    }
    /*
     * 兜底：没见过的页型（课程 /cheese/…、收藏列表 /medialist/… 等）也要给出身份。
     * 不给身份的后果不是"少认一次"，而是**整条接管死锁**：槽一直空 → 真全屏按钮一直灰，
     * 用户再也点不动（2026-10-26 实测卡在季页面就是这样）。用规范化路径当身份，
     * 换片能被发现；取不到播放地址时槽自然不填，也不会误交。
     */
    var p2 = String(location.pathname || '');
    if (p2 && p2 !== '/' && p2.length > 1) return 'p:' + p2;
    return '';
  }
  var BI_PANEL_SEL = [
    'a[href*="/play/ep"][class*="activeItem"]',
    '.eplist_ep_list .ep-item.cur',
    '.eplist_ep_item.cur',
    '.ep-list .ep-item.cur',
    '.video-pod__item.active',
    '.list-box .list-item.active'
  ];
  function biTitle() {
    var series = fTitle(document.title) || fTitle(txt('.video-title') || txt('h1.video-title'));
    var p = pickPanelEp(BI_PANEL_SEL);
    if (p && p.no) return buildTitle(series, cn2num(p.no), p.kind, biSeasonNo(), p.name);
    var ov = clean(txt('.bpx-player-top-title') || txt('[class*="player-top-title"]'));
    var m = /第\s*([零一二三四五六七八九十\d]+)\s*([集话期])\s*[:：]?\s*(.*)$/.exec(ov);
    if (m) return buildTitle(series || clean(ov), cn2num(m[1]), m[2], biSeasonNo(), m[3]);
    return isJunkTitle(series) ? '' : series;
  }
  function biEpNo() {
    var m = /第\s*([零一二三四五六七八九十\d]+)\s*[集话期]/.exec(clean(txt('.bpx-player-top-title') || txt('[class*="player-top-title"]')));
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
      if (!vs.length) { biCtx.askFailed = Date.now(); return; }
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
    }).catch(function (e) { biCtx.askFailed = Date.now(); console.log('[b0b] B站 播放信息请求失败：' + e); });
  }
  function biSync() {
    var id = biId();
    if (!id || id === biCtx.key) return;
    biCtx = { key: id, cid: '', dur: 0, v: '', a: '', quality: '', askFailed: 0 };
    biSegs = [];
    CH('①页面变化', { 站: 'bi', 身份: id });
    if (/^ep\d+$/.test(id)) { biCtx.cid = id; biAsk(id, ''); return; }
    /* 多P：第 N 页的 cid 与时长在 pages[] 里，主 cid 只代表第 1P */
    var bv = biBv(), no = biPageNo();
    fetch('https://api.bilibili.com/x/web-interface/view?bvid=' + bv, { credentials: 'include' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        var d = (j && j.data) || {}, pg = (d.pages || [])[no - 1] || {};
        biCtx.cid = String(pg.cid || d.cid || '');
        biCtx.dur = parseInt(pg.duration || d.duration || 0, 10) || 0;
        biAsk(bv, biCtx.cid);
      }).catch(function (e) { console.log('[b0b] B站 取编号失败：' + e); });
  }
  function biTick() {
    if (site() !== 'bi' || !onPlayPage()) return;
    biSync();
    var id = biId();
    if (!id) return;
    /* 重试：取址失败 5 秒后且地址仍为空，重新取 */
    if (biCtx.askFailed && !biCtx.v && Date.now() - biCtx.askFailed > 5000) {
      biCtx.askFailed = 0;
      biAsk(id, biCtx.cid || '');
    }
    var seg = biSegPair();
    commit('bi', {
      id: id, ep: biEpNo(), season: biSeasonNo(), title: biTitle(),
      dur: biCtx.dur || pageDur(),
      v: seg.v || biCtx.v, a: seg.a || biCtx.a, quality: biCtx.quality,
      cid: biCtx.cid, ar: pageAr()
    });
  }

  /* 五、腾讯 */

  var QQ_TIERS = { 'f322100': '臻彩MAX', 'f322104': '臻彩MAX', 'f322093': '4K', 'f322150': '臻彩1080P' };
  var qqCtx = S.qqCtx;

  function qqId() {
    var m = String(location.pathname).match(/\/x\/cover\/[A-Za-z0-9]+\/([A-Za-z0-9]+)\.html/i);
    if (m) return m[1];
    /* 专辑页（/x/cover/<合集号>.html）地址里没有每集号，但清单文件名里带着：
       形如 gzc_1000102_<每集号>.f322093.ts.m3u8，就用它当身份。 */
    var f = /_([A-Za-z0-9]{6,})\.f\d{6}\.(?:ts\.)?m3u8/.exec(String(qqCtx.url || ''));
    return f ? f[1] : '';
  }
  var QQ_PANEL_SEL = ['.episode-list .episode-item.selected', '.episode-item.selected'];
  function qqPanelPick() { return pickPanelEp(QQ_PANEL_SEL); }
  function qqTitle() {
    /* 剧名只从页面标题拿，且先摘掉 "_02_" 这种集号尾巴（腾讯标题格式：剧名_集号_分类_…） */
    var series = fTitle(String(document.title || '').replace(/_\d{1,3}_/g, '_'));
    if (!series) series = fTitle(clean(txt('.player-play-title__title') || txt('.txp_title')));
    /* 集号与集名分开补（父亲 2026-10-26 报：只出剧名）：
       面板那一项可能只抽出集名没抽出集号，这时集号从播放器左上角那行补；
       任一边抽不到都不能让整条标题退化成只有剧名。 */
    var p = qqPanelPick();
    var no = (p && p.no) || qqEpNo();
    var nm = (p && p.name) || '';
    if (!nm) nm = clean(txt('.player-play-title__sub-title'));
    if (nm && (nm === String(no) || /^0*\d{1,3}[集话期回部]?$/.test(nm))) nm = '';
    /* 副标题那格常被会员标识占着（实测「尚公主 第3集VIP」），这类标记不是集名 */
    if (nm && /vip|会员|独播|独家|超前|付费|点播|预告|正片|花絮|片段|特辑|彩蛋/i.test(nm)) nm = '';
    if (no) return buildTitle(series, cn2num(no), (p && p.kind) || '集', '', nm);
    if (nm) return buildTitle(series, '', '', '', nm);
    /* 面板还没渲染出来（刚进页那几秒）才退回播放器那行 */
    var ov = clean(txt('.player-play-title__title') || txt('.txp_title'));
    var sub = clean(txt('.player-play-title__sub-title'));
    var m = /第\s*([零一二三四五六七八九十\d]+)\s*([集话期])\s*[:：]?\s*(.*)$/.exec(ov);
    if (m) return buildTitle(series || clean(ov), cn2num(m[1]), m[2], '', m[3] || sub);
    return buildTitle(series || ov, '', '', '', sub);
  }
  function qqEpNo() {
    var p = qqPanelPick();
    if (p && p.no) return String(p.no);
    var m = /第\s*([零一二三四五六七八九十\d]+)\s*[集话期]/.exec(clean(txt('.player-play-title__title') || txt('.txp_title')));
    return m ? cn2num(m[1]) : '';
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
    var one = String(u || '').replace(/\\\//g, '/').replace(/\\+$/, '');
    var m = one.match(/\.(f\d{6})\.(?:ts\.)?m3u8/);
    if (!m) return;
    var name = QQ_TIERS[m[1]];
    if (!name) return;
    if (/smtcdns|subtitle|\.vtt\./.test(one)) return;
    /* 这里不能先要求身份：专辑页的身份正是从这条地址里取的，先要求就互相卡死。
       但已经知道当前集号时，抓到别的集的清单要丢掉 —— 那是切集之后迟到的上一份。 */
    var nowEp = qqId();
    if (nowEp && qqCtx.ep && qqCtx.ep !== nowEp) return;
    if (qqCtx.url === one) return;
    qqCtx.url = one; qqCtx.name = name; qqCtx.dur = 0;
    CH('②取址', { 站: 'qq', 档: name + '(' + m[1] + ')', 地址尾: '…' + one.slice(-24) });
    qqPlaylistDur(one);
  }
  /*
   * 腾讯的网址会跟着换集变，但页面自己那份数据不会（见网页侧规格 9.3）。
   * 所以地址必须"跟着集号走"：集号一变，先前抓到的那份 m3u8 属于上一集，留着会出现
   * "身份是第 3 集、地址是第 2 集"，播出来还是上一集（父亲 2026-10-26 报的接管问题）。
   */
  function qqTick() {
    if (site() !== 'qq' || !onPlayPage()) return;
    var id = qqId();
    if (!id) return;
    if (qqCtx.ep && qqCtx.ep !== id) {
      CH('①页面变化', { 站: 'qq', 身份: id, 上一集: qqCtx.ep });
      qqCtx.url = ''; qqCtx.name = ''; qqCtx.dur = 0;
    }
    qqCtx.ep = id;
    commit('qq', {
      id: id, ep: qqEpNo(), title: qqTitle(),
      dur: qqCtx.dur || pageDur(),
      v: qqCtx.url, a: qqCtx.url, quality: qqCtx.name,
      ar: pageAr()
    });
  }

  /* 六、爱奇艺 */

  var iqEps = { aid: '', list: [] };
  var iqTvidHint = null;
  /* 点击取号：点在哪张集/期卡片上，就把那张卡片的片号、集号、集名记下来。
     爱奇艺综艺切集后页面自己的片号不跟着换（实测 40 秒还停在上一期），
     只有点击那一刻拿到的才准（见 docs/slot-spec-2026-10-25/3-网页侧逻辑.md 第 9 节）。 */
  var iqClick = S.iqClick || (S.iqClick = { tv: '', t: '', u: '', at: 0, path: '' });

  /* "第4期：名字" 这种串的拆解：给片名、集号、集名各取所需 */
  var IQ_EP_RE = /第\s*([零一二三四五六七八九十\d]+)\s*([集话期])\s*[:：]?\s*(.*)$/;
  function iqEpParts(text) {
    var t = clean(text);
    if (!t) return null;
    var m = IQ_EP_RE.exec(t);
    if (!m) return null;
    return { no: m[1], kind: m[2], name: clean(m[3]) };
  }

  /*
   * 拼"正在播放"的片名（父亲 2026-10-26 定的分工）。
   *
   * 剧名/综艺名走页面标题那一路：那一行是整部剧的名字，稳定、不受切集影响。
   * 集号与集名走点选的那一张卡：和取片号同一次点击、同一张卡，切到哪一集就是哪一集。
   * 之所以要分两路 —— 播放窗口左上角那行字在切集后经常滞后（综艺没有"当前集"样式差分，
   * 头部元素是缓存的），拿它当集号来源就会出现"标题第3期、画面放第4期"。
   */
  /*
   * 爱奇艺的"正在播那一项"：电视剧项带 episodes_playingItem 类；
   * 综艺没有类名差分 —— 当前期的期号文字变色（其余同色），所以按"与其他项不同色"找，
   * 不写死颜色值（主题一换就失效）。
   */
  function iqPanelPick() {
    var r = pickPanelEp(['[class*="episodes_playingItem"]', '.episodesNew_item.episodes_playingItem']);
    if (r) return r;
    var arr = [];
    try { arr = [].slice.call(document.querySelectorAll('[class*="episodesNew_item"]')); } catch (e) { arr = []; }
    if (arr.length < 2) return null;
    var cnt = {}, best = '';
    arr.forEach(function (e) {
      var c = '';
      try { c = window.getComputedStyle(e).color || ''; } catch (e2) { c = ''; }
      if (!c) return;
      cnt[c] = (cnt[c] || 0) + 1;
      if (!best || cnt[c] > cnt[best]) best = c;
    });
    if (!best) return null;
    for (var i = 0; i < arr.length; i++) {
      var mine = '';
      try { mine = window.getComputedStyle(arr[i]).color || ''; } catch (e3) { mine = ''; }
      if (mine && mine !== best) {
        var r2 = parsePanelEl(arr[i]);
        if (r2) return r2;
      }
    }
    return null;
  }
  function iqTitle() {
    var picked = (iqClick.t && Date.now() - (iqClick.at || 0) < 60000) ? clean(iqClick.t) : '';
    var head = clean(iqHeaderText());
    /* 面板选中项排在最前：它才是"正在播哪一期"的权威，点选与头部那行都只作兜底 */
    var panel = iqPanelPick();
    var series = fTitle(document.title) || fTitle(head) || fTitle(picked);
    var pickedEp = iqEpParts(picked);
    var headEp = iqEpParts(head);
    var ep = (panel && panel.no ? { no: panel.no, kind: panel.kind, name: panel.name } : null) || pickedEp || headEp;
    var out;
    if (!pickedEp && !headEp && picked) {
      /* 单片：电影、剧场版这类没有集号，海报上写的就是全名（父亲 2026-10-26 定）。
         剧集不会走到这里 —— 两路都拆不出"第N集/期"才认为是单片。 */
      out = picked;
    } else if (ep) {
      /* 集号以点选为准；集名两路各取一半时也不丢 —— 卡片常只写"第4期"，名字在头部那行 */
      var no = (panel && panel.no) || (pickedEp && pickedEp.no) || (headEp && headEp.no) || ep.no;
      var kind = (panel && panel.no ? panel.kind : '') || (pickedEp && pickedEp.kind) || (headEp && headEp.kind) || ep.kind;
      var name = (panel && panel.name) || (pickedEp && pickedEp.name) || (headEp && headEp.name) || '';
      out = buildTitle(series || clean(head) || clean(picked), cn2num(no), kind, '', name);
    } else {
      out = isJunkTitle(series) ? '' : series;
    }
    /* 只在变化时记一条，便于核对"标题对不对得上画面" */
    if (out && out !== S.iqTitleLast) {
      S.iqTitleLast = out;
      CH('③片名', { 剧名来源: '标题', 集号来源: pickedEp ? '点击' : '头部', 值: out });
    }
    return out;
  }

  /*
   * 播放窗口左上角那行字（"剧名 第N集 集名"）。类名会改，所以按几个候选类找，
   * 再退一步：在画面左上角区域里找带"第N集"的元素（父亲 2026-10-26 指出那里一定有）。
   */
  function iqHeaderText() {
    var sels = ['.iqp-title_left', '[class*="webHead_title"]', '[class*="play_title"]',
      '.player-title', '.mediainfo_mediaTitle', 'h1'];
    for (var i = 0; i < sels.length; i++) {
      var t = txt(sels[i]);
      if (t) return t;
    }
    try {
      var all = document.querySelectorAll('*'), H = window.innerHeight || 720;
      for (var k = 0; k < all.length; k++) {
        var e = all[k];
        var t2 = clean(e.textContent || '');
        if (t2.length < 4 || t2.length > 60) continue;
        /* 不要求"没有子元素"：综艺那行的标题常是 <span> 拼出来的，要求叶子节点会全漏掉 */
        if (!/第\s*[零一二三四五六七八九十\d]+/.test(t2)) continue;
        var r = e.getBoundingClientRect();
        if (r.top >= 0 && r.top < H * 0.40 && r.left < (window.innerWidth || 1280) * 0.7 && r.width > 40) {
          return t2;
        }
      }
    } catch (e3) {}
    return '';
  }
  function iqEpNo() {
    /* 面板选中项第一顺位：它随页面切换即时更新，不依赖有没有点到卡片 */
    var pn = iqPanelPick();
    if (pn && pn.no) return String(pn.no);
    /*
     * 其次点的那张卡片上就写着"第4期 喻言《灼》…"，比左上角那行早三秒到手。
     * 60 秒内有效：换片/换集都靠这一次点击，再久就对不上现在播的是哪一个了。
     */
    var cp = String(iqClick.t || '');
    if (cp && Date.now() - (iqClick.at || 0) < 60000) {
      var ca = cp.match(/第\s*([零一二三四五六七八九十百\d]+)/g);
      if (ca && ca.length) {
        var cm = /第\s*([零一二三四五六七八九十百\d]+)/.exec(ca[ca.length - 1]);
        if (cm) return cn2num(cm[1]);
      }
    }
    /*
     * 不穷举单位。左上角那行是"剧名 第N集/期/话/回/部…集名"，
     * 只要认"第 + 数字"，后面跟什么字都不影响 —— 综艺、纪录片、动漫的写法各不一样，
     * 按单位列举永远漏（父亲 2026-10-26 02:28 提醒）。
     */
    var t = clean(iqHeaderText() + ' ' + String(document.title || ''));
    /*
     * 取**最后一个**"第N"。左上角那行/网页标题里常同时出现别的集号
     * （"第2集 分集"、上一集的集名等），取第一个会取到隔壁那集 ——
     * 表现就是"正在播第3集、控制条写第2集"（父亲 2026-10-26 02:45）。
     */
    var all = t.match(/第\s*([零一二三四五六七八九十百\d]+)/g);
    if (!all || !all.length) return '';
    var last = all[all.length - 1];
    var m = /第\s*([零一二三四五六七八九十百\d]+)/.exec(last);
    return m ? cn2num(m[1]) : '';
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
  /* 页面自己那条 dash 请求里带着 tvid，这是最可靠的片号来源；
     页面有时不重发，那就先记下来备用。 */
  function iqRememberTvid(u) {
    try {
      var m = /[?&]tvid=(\d+)/.exec(String(u || ''));
      /* 带上当时的页面地址：换集/换片后旧片号不能再用 */
      if (m) iqTvidHint = { t: m[1], p: String(location.pathname) };
    } catch (e) {}
  }
  function iqTvid() {
    /* 先用"点的那一张"：切集/换片那一刻页面自己的号还没换（综艺实测 40 秒不动），
       点到的卡片上的号才是这一集/这一期的。换片后整页重载，脚本重跑，这份记录自然清空。 */
    if (iqClick.tv && Date.now() - (iqClick.at || 0) < 60000) return String(iqClick.tv);
    if (iqTvidHint && iqTvidHint.p === String(location.pathname)) return iqTvidHint.t;
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
  function iqTick() {
    if (site() !== 'iq' || !onPlayPage()) return;
    iqLoadEps();
  }

  /*
   * 点击取号（父亲 2026-10-26 定）：点在哪张集/期卡片上，就把那张卡片的片号记下来。
   * 页面自己的片号在切集后不更新，只有点击当刻拿到的才准；原生投进来的点击与手点
   * 都会冒泡到这里。绑定只做一次 —— 脚本会被反复注入，重绑会让一条点击记好几遍。
   */
  function iqWatchClick() {
    if (window.__b0bClickBound) return;
    window.__b0bClickBound = 1;
    document.addEventListener('click', function (ev) {
      try {
        var el = ev.target, i, j, k, f, mp, d;
        for (i = 0; i < 12 && el; i++) {
          k = Object.keys(el).filter(function (x) { return x.indexOf('__reactFiber') === 0; })[0];
          if (k) {
            f = el[k];
            for (j = 0; j < 12 && f; j++) {
              mp = f.memoizedProps || {};
              d = mp.data || mp.item || mp.cardData || mp.video || mp.info;
              var tv = d && (d.qipu_id || d.tvId || d.tvid || d.qipuId || d.videoId);
              if (tv) {
                iqClick = {
                  tv: String(tv),
                  t: clean(d.title || d.short_title || d.name || d.albumTitle || ''),
                  u: String(d.page_url || ''),
                  at: Date.now(),
                  path: String(location.pathname)
                };
                S.iqClick = iqClick;
                CH('②点击取号', { 站: site(), 片号: iqClick.tv, 卡片: iqClick.t.slice(0, 20) });
                return;
              }
              f = f.return;
            }
          }
          el = el.parentElement;
        }
      } catch (e) {}
    }, true);
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

  /* 爱奇艺的全屏按钮选择器一个都匹配不到（日志里"按钮数=0"），
     所以它从来没被变灰过。这里把页面上跟全屏有关的元素摸一遍，一次就够。 */
  var iqFsDumped = '';
  function iqDumpFsButtons() {
    if (site() !== 'iq' || !onPlayPage()) return;
    var key = String(location.pathname);
    if (iqFsDumped === key) return;
    iqFsDumped = key;
    try {
      var all = document.querySelectorAll('*'), out = [], seen = {};
      for (var i = 0; i < all.length && out.length < 6; i++) {
        var e = all[i], h = '';
        try { h = String(e.outerHTML || '').slice(0, 160); } catch (x) { continue; }
        if (!/fullscreen|全屏/i.test(h)) continue;
        var sig = e.tagName + '.' + String(e.className || '').slice(0, 50) + '|' + (e.title || '') + '|' + (e.getAttribute('data-pb') || '');
        if (seen[sig]) continue;
        seen[sig] = 1;
        out.push(sig);
      }
      CH('④爱奇艺全屏按钮候选', { 个数: out.length, 清单: out.join(' ;; ').slice(0, 300) });
    } catch (e) {}
  }

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
    /* 播放器不只调标准接口：爱奇艺这类常走带前缀的那几个，
       只包 requestFullscreen 等于没包 —— 它的调用照样出去、我们一条日志都没有，
       表现就是"按钮形状变了、画面没全屏"（父亲 2026-10-25 报）。 */
    var NAMES = ['requestFullscreen', 'webkitRequestFullscreen', 'mozRequestFullScreen', 'msRequestFullscreen'];
    for (var i = 0; i < NAMES.length; i++) {
      (function (nm) {
        try {
          var raw = Element.prototype[nm];
          if (!raw || raw.__b0bGuarded) return;
          var patched = function () {
            /* 爱奇艺例外：它的整集片单由原生自签（页面只能给六分钟的裸地址），
               所以不需要槽；这里必须放行，否则页面进不了真全屏，原生那条路也起不来。 */
            if (site() === 'iq') {
              CH('④点全屏-放行', { 站: 'iq', 接口: nm, 原因: '地址由原生自签，不看槽' });
              return raw.apply(this, arguments);
            }
            if (!slotReady()) {
              CH('④点全屏-拦下', { 站: site(), 接口: nm, 原因: whyNotReady() });
              return Promise.reject(new Error('b0b-slot-not-ready'));
            }
            CH('④点全屏-放行', { 站: site(), 接口: nm, 时长: ((slot[site()] && slot[site()].dur) || 0) + 's' });
            return raw.apply(this, arguments);
          };
          patched.__b0bGuarded = 1;
          Element.prototype[nm] = patched;
        } catch (e) {}
      })(NAMES[i]);
    }
  }

  /* 九、网络钩子 */

  /* 只补腾讯：命中判据是请求地址里的 qq.com，B站 与爱奇艺一律不碰。 */
  var QQ_CAPS = '&spvvc=3&sphevcfps=52776560265087';
  /* 重写前（dc9912a）的写法：解析 JSON，修改 vinfoparam 字段，重新序列化。
     直接拼接字符串不会生效，因为 vinfoparam 是 JSON 里的一个字段。 */
  function qqPatchBody(b, u) {
    if (b == null || String(u || '').indexOf('qq.com') < 0) return b;
    try {
      if (typeof b === 'string') {
        if (b.indexOf('vinfoparam') < 0 || b.indexOf('spvvc') >= 0) return b;
        var o = JSON.parse(b);
        if (!o || typeof o.vinfoparam !== 'string') return b;
        var p = o.vinfoparam;
        var changed = false;
        if (p.indexOf('spvvc=') < 0) { p += QQ_CAPS; changed = true; }
        if (!changed) return b;
        o.vinfoparam = p;
        CH('⑩能力位', { 站: 'qq', 通道: 'JSON', url: String(u).slice(0, 64) });
        return JSON.stringify(o);
      }
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) {
        if (b.get('spvvc') || !b.get('vinfoparam')) return b;
        b.set('spvvc', '3'); b.set('sphevcfps', '52776560265087');
        CH('⑩能力位', { 站: 'qq', 通道: '表单串', url: String(u).slice(0, 64) });
        return b;
      }
      if (typeof FormData !== 'undefined' && b instanceof FormData) {
        if (b.getAll('spvvc').length > 0 || !b.get('vinfoparam')) return b;
        b.append('spvvc', '3'); b.append('sphevcfps', '52776560265087');
        CH('⑩能力位', { 站: 'qq', 通道: '表单', url: String(u).slice(0, 64) });
        return b;
      }
    } catch (e) {}
    return b;
  }
  /* 整集地址在那条播放信息请求的**回复**里（页面不会另发一条 m3u8 请求），
     只改请求不看回复就永远收不到 —— 所以把回复里带档位号的地址全抠出来。 */
  function qqScanText(t) {
    try {
      var s = String(t || ''), n = 0, from = 0, idx;
      /* 用 indexOf 逐段找，不用带惰性量词的正则：回复常有几 MB，
         那种正则在这上面会疯狂回溯，整条 JS 线程卡住，页面就"卡死"（父亲 2026-10-25 报）。 */
      var tail = /\.f\d{6}\.(?:ts\.)?m3u8$/;
      while ((idx = s.indexOf('m3u8', from)) >= 0) {
        from = idx + 4;
        var start = idx;
        while (start > 0 && idx - start < 700) {
          var ch = s.charAt(start - 1);
          if (ch === '"' || ch === "'" || ch === ',' || ch === ' ' || ch === '\n' || ch === '\\') break;
          start--;
        }
        var cand = s.slice(start, idx + 4);
        if (tail.test(cand)) { n++; qqTake(cand); }
      }
      /* 时长就在这条回复里（td 是秒）。清单地址是跨域的，脚本自己再取一次会被挡，
         所以时长必须从这条回复拿，否则槽永远缺"时长"这一格、按钮一直灰。 */
      var td = /"(?:td|duration)"\s*:\s*(\d{2,6})/.exec(s);
      if (td && Number(td[1]) > 60 && !qqCtx.dur) qqCtx.dur = Number(td[1]);
      /* 抠不到就是"只补了能力位、没抓到地址"那种空转，必须留下回复的样子给人看 */
      if (!n) {
        var i = s.indexOf('m3u8');
        CH('⑩回复没地址', { 长度: s.length, 有m3u8: i >= 0 ? 1 : 0, 片段: i >= 0 ? s.slice(Math.max(0, i - 60), i + 40) : s.slice(0, 60) });
      }
    } catch (e) {}
  }
  function hookNet() {
    try {
      var oF = window.fetch;
      if (oF && !oF.__b0bHooked) {
        var nf = function () {
          var a = [].slice.call(arguments);
          var qqRead = false;
          try {
            var u = (typeof a[0] === 'string') ? a[0] : (a[0] && a[0].url), us = String(u || '');
            if (site() === 'qq') {
              if (us.indexOf('qq.com') >= 0 && a[1]) {
                var nb = qqPatchBody(a[1].body, us);
                if (nb !== a[1].body) { a[1] = Object.assign({}, a[1], { body: nb }); qqRead = true; }
              }
              if (us.indexOf('.m3u8') > 0) qqTake(us);
            } else if (site() === 'bi' && us.indexOf('.m4s') > 0) {
              biSeg(us);
            } else if (site() === 'iq' && us.indexOf('/dash?') > 0) {
              iqRememberTvid(us);
            }
          } catch (e) {}
          var pr = oF.apply(window, a);
          try {
            var uq = String((typeof a[0] === 'string') ? a[0] : (a[0] && a[0].url) || '');
            if (qqRead && pr && pr.then) {
              pr.then(function (r) {
                try {
                  r.clone().text().then(qqScanText).catch(function (e4) {
                    CH('⑩回复读不到', { 原因: String(e4).slice(0, 60) });
                  });
                } catch (e2) { CH('⑩回复读不到', { 原因: 'clone失败' }); }
              }).catch(function () {});
            }
          } catch (e3) {}
          return pr;
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
              if (u.indexOf('qq.com') >= 0) {
                var nb2 = qqPatchBody(b, u);
                if (nb2 !== b) {
                  b = nb2;
                  this.addEventListener('load', function () {
                    try { qqScanText(this.responseText); } catch (e4) {}
                  });
                }
              }
              if (u.indexOf('.m3u8') > 0) this.addEventListener('load', function () { qqTake(u); });
            } else if (site() === 'bi' && u.indexOf('.m4s') > 0) {
              biSeg(u);
            } else if (site() === 'iq' && u.indexOf('/dash?') > 0) {
              iqRememberTvid(u);
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

  /*
   * 清掉腾讯播放器自己的本地资源库（父亲 2026-10-26 定：按 B 回网页后就逼它重新要一次）。
   *
   * 为什么需要：腾讯播放器把播放清单存进本地（Cache Storage、IndexedDB 里的 thumbplayer
   * 资源库、以及几个 TP_RESOURCE_* 标记）。回网页后它直接从本地起播，一条网络请求都不发，
   * 于是页面资源表里没有 m3u8、我们抓不到地址，真全屏按钮就一直灰（实测：同时刻 B站 回网页
   * 两秒就恢复，腾讯四分钟不动，差别就在于 B站 每次都是真发请求）。
   *
   * 只清资源缓存相关的键，不动登录票据（登录在 cookie 里，与这些键无关），不会掉登录。
   * IndexedDB 的库可能被页面自己占着删不掉，那种会记一笔日志，不影响后续流程。
   */
  function clearQqResourceCache() {
    if (site() !== 'qq') return;
    var stat = { cache: 0, idb: 0, ls: 0 };
    try {
      if (window.caches && window.caches.keys) {
        window.caches.keys().then(function (ks) {
          stat.cache = (ks || []).length;
          (ks || []).forEach(function (k) { try { window.caches.delete(k); } catch (e) {} });
          CH('⑨清腾讯资源库', { 缓存库: stat.cache, 库清单: (ks || []).join(',') });
        }).catch(function () {});
      }
    } catch (e) {}
    try {
      Object.keys(localStorage).forEach(function (k) {
        if (/thumbplayer|TP_RESOURCE|offline-db|tenvideo_player|TVPLAYER.*cache/i.test(k)) {
          try { localStorage.removeItem(k); stat.ls++; } catch (e) {}
        }
      });
    } catch (e) {}
    try {
      if (window.indexedDB && indexedDB.databases) {
        indexedDB.databases().then(function (ds) {
          var n = 0;
          (ds || []).forEach(function (d) {
            if (!d || !d.name) return;
            try {
              var rq = indexedDB.deleteDatabase(d.name);
              rq.onsuccess = function () { n++; };
              rq.onblocked = function () { CH('⑨资源库被占用', { 库: d.name }); };
            } catch (e) {}
          });
          stat.idb = (ds || []).length;
          CH('⑨清腾讯资源库', { 数据库: stat.idb, 本地键: stat.ls });
        }).catch(function () {});
      }
    } catch (e) {}
  }

  window.__b0bClearSlots = function (why) {
    slot.bi = null; slot.qq = null; slot.iq = null;
    S.lastSent = ''; fsState = -1; fsPend = 0;
    biCtx.key = ''; biCtx.cid = ''; biCtx.dur = 0; biCtx.v = ''; biCtx.a = ''; biCtx.quality = '';
    biSegs = [];
    qqCtx.url = ''; qqCtx.name = ''; qqCtx.dur = 0; qqCtx.ep = '';
    try { if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen(); } catch (e) {}
    try { window.__b0bRealFull = 0; } catch (e2) {}
    try { localStorage.removeItem('b0b:realFullAt'); } catch (e3) {}
    /* 回网页先把腾讯的本地资源库清掉：它不清就会拿本地缓存起播、一条请求都不发（见函数注释） */
    clearQqResourceCache();
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
  /* 原生要片号时优先问这里：这里的号来自页面自己那条 dash 请求、并按页面地址防串集，
     比 QiyiPlayerProphetData 那个常常慢一拍的字段新（父亲 2026-10-25 报切集后一直等待）。 */
  window.__b0bIqTvid = function () { try { return String(iqTvid() || ''); } catch (e) { return ''; } };
  /* 分集表里第 n 集的片号：页面自己的分集表已经拉好了，原生直接问它最省事 */
  /* 当前是第几集（页面标题里那串"第N集"），原生拿不到就靠这个 */
  window.__b0bIqEpNo = function () { try { return String(iqEpNo() || ''); } catch (e) { return ''; } };
  /* 播放窗口左上角那一整行字（父亲 2026-10-26 02:26 定：控制条"正在播放"统一取它） */
  window.__b0bIqHeader = function () { try { return String(iqHeaderText() || ''); } catch (e) { return ''; } };
  window.__b0bIqEpTvId = function (n) {
    try {
      var l = (S.iqEps && S.iqEps.list) || [], it = l[(Number(n) || 0) - 1];
      return it ? String(it.tvId || '') : '';
    } catch (e) { return ''; }
  };

  hookNet();
  iqWatchClick();
  guardFullscreen();
  /* 注入会被重试，重试等于把整段脚本再跑一遍；旧定时器先收掉，
     否则同一条链每秒跑好几遍。 */
  try { (window.__b0bTimers || []).forEach(clearInterval); } catch (e) {}
  window.__b0bTimers = [
    setInterval(tick, 1200),
    setInterval(markFs, 700),
    setInterval(iqDumpFsButtons, 3000),
    setInterval(iqFixHeight, 800),
    setInterval(reportRect, 400),
    setInterval(scanQQ, 1500)
  ];
  /* 内核注入后回读这个时间戳判断"装上了没有"，缺了它内核会一直重试注入 */
  window.__b0bInjectedAt = Date.now();
  console.log('[b0b] 网页脚本已就绪（2026-10-25 版）：站=' + (site() || '其他'));
})();
