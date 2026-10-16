/*
 * 页面世界的探子（父亲 2026-10-15）。
 *
 * 由 manifest 声明为 world:MAIN 的内容脚本，直接跑在页面自己的世界里 —— 只有在这里
 * 才动得了页面的 fetch / XHR，才抓得到播放地址，也才绕得过页面 CSP。
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
  var saidHello = false;
  var lastKey = '';
  var lastSent = 0;
  var lastGood = null;

  /** 播放接口解析出来的可播地址（多档清晰度），客户端据此用自己的播放器播 */
  var playable = null;
  var lastPlaySig = '';

  function note(u) {
    if (!u || typeof u !== 'string') return;
    if (!/\.(m3u8|mpd|mp4|m4s|flv)(\?|#|$)/i.test(u)) return;
    if (u.indexOf('data:') === 0) return;
    /* 埋点上报不算播放地址（r551 实测：26 条里绝大多数是 data.bilibili.com/log/web 的埋点） */
    if (u.indexOf('data.bilibili.com') >= 0 || u.indexOf('/log/') >= 0) return;
    if (urls.indexOf(u) >= 0) return;
    urls.push(u);
    if (urls.length > 30) urls.shift();
  }

  /*
   * 播放接口的返回是"金矿"（2026-10-16）：B站 的 dash 音视频地址、清晰度、时长都在里面。
   * 本机实测（yt-dlp 取真实流 + mpv 播）：**只要 Referer + User-Agent 两样**，
   * mpv 用「视频流 + --audio-file 音频流」就能直接播，不需要网页播放器。
   * 会员片才额外需要 Cookie（SESSDATA），那一条由客户端从网页里取。
   */
  /*
   * 编码偏好（2026-10-16 本机实测后定）：
   * B站 最高那档常是 AV1（日志里 4096x1716 就是 av1），而 PICO 4 的芯片**没有 AV1 硬解**，
   * 挑"分辨率最高"等于挑一个播不动的档。所以先按"这台机器解不解得动"排，
   * 同编码内再挑分辨率高、码率大的。HEVC（hev1/hvc1）最稳 → AVC → AV1 垫底。
   */
  function codecRank(c) {
    c = String(c || '').toLowerCase();
    if (c.indexOf('hev') >= 0 || c.indexOf('hvc') >= 0 || c.indexOf('dvh') >= 0) return 3;
    if (c.indexOf('avc') >= 0) return 2;
    if (c.indexOf('av01') >= 0 || c.indexOf('av1') >= 0) return 1;
    return 2;
  }

  /* 播放信息（B站 的 playurl 返回，或页面全局 __playinfo__）→ 可播地址 */
  function applyPlayinfo(d) {
    try {
      if (!d) return;
      var streams = [];
      var durSec = 0;
      if (d.dash) {
        durSec = Math.round(d.dash.duration || 0);
        var auds = (d.dash.audio || []).slice().sort(function (a, b) {
          return (b.bandwidth || 0) - (a.bandwidth || 0);
        });
        var audioUrl = auds.length ? (auds[0].baseUrl || auds[0].base_url || '') : '';
        var vids = (d.dash.video || []).slice().sort(function (a, b) {
          var sa = codecRank(a.codecs) * 100000 + (a.height || 0) * 10 + (a.bandwidth || 0) / 1000000;
          var sb = codecRank(b.codecs) * 100000 + (b.height || 0) * 10 + (b.bandwidth || 0) / 1000000;
          return sb - sa;
        });
        for (var i = 0; i < vids.length; i++) {
          var v = vids[i];
          var u = v.baseUrl || v.base_url || '';
          if (!u) continue;
          streams.push({
            id: v.id || 0, height: v.height || 0, width: v.width || 0,
            bw: v.bandwidth || 0, vcodec: v.codecs || '',
            video: u, audio: audioUrl
          });
        }
      }
      if (!streams.length && d.durl && d.durl.length) {
        durSec = Math.round((d.timelength || 0) / 1000);
        for (var k = 0; k < d.durl.length; k++) {
          streams.push({
            id: d.quality || 0, height: 0, width: 0, bw: 0, vcodec: '',
            video: d.durl[k].url || '', audio: ''
          });
        }
      }
      if (!streams.length) return;
      /* 同一份播放接口不重复上报（URL 很长，没变化就不发） */
      var sig = streams.length + '|' + String(streams[0].video).slice(-70);
      if (sig === lastPlaySig) return;
      lastPlaySig = sig;
      playable = {
        kind: 'play', t: Date.now(), frame: location.href,
        durSec: durSec, quality: d.quality || 0, streams: streams.slice(0, 10)
      };
      console.log('[b0b] 拿到播放接口：' + streams.length + ' 档，时长 ' + durSec +
        's，最高 ' + (streams[0].height || '?') + 'p');
      try { window.postMessage({ __b0b: 1, payload: playable }, '*'); } catch (e) {}
    } catch (e) {}
  }

  /* 接口返回是文本：解析后交给 applyPlayinfo */
  function parsePlayurl(text) {
    try {
      var j = JSON.parse(text);
      if (j && j.data) applyPlayinfo(j.data);
    } catch (e) {}
  }

  /*
   * B站 会把播放信息直接挂在页面全局 __playinfo__ 上（2026-10-16 加，最稳的一路）。
   * 上一版只 hook 了 fetch / XHR —— 实测没抓到（客户端没等到 kind:'play'），
   * 说明播放器那次请求没走我们 hook 到的对象。全局变量不受这个影响。
   */
  var lastPlayinfoObj = null;
  function readGlobalPlayinfo() {
    try {
      var j = window.__playinfo__;
      if (!j || !j.data || j === lastPlayinfoObj) return;
      lastPlayinfoObj = j;
      console.log('[b0b] 从页面全局拿到播放信息');
      applyPlayinfo(j.data);
    } catch (e) {}
  }

  /* 诊断：看清播放接口到底有没有被请求（只打与播放接口相关的） */
  function noteApi(u) {
    if (!u || typeof u !== 'string') return;
    if (u.indexOf('bilibili.com') < 0) return;
    if (u.indexOf('/x/player/') < 0 && u.indexOf('playurl') < 0) return;
    console.log('[b0b] 播放接口请求 ' + u.slice(0, 130));
  }

  /*
   * 兜底：从已抓到的分片地址里配对（2026-10-16）。
   * B站 的 dash 命名有规律：…/upgcxcode/aa/bb/<cid>/<cid>[_srN]-1-<code>.m4s，
   * code 30xxx 是音频（30216/30232/30280），其余是视频（30011…30080、1000xx）。
   * 同一个 cid 里挑一条视频 + 一条音频就能播（本机 mpv 实测这种地址可直接播）。
   */
  /*
   * 分片编号打分（兜底配对用）：B站 的视频编号里
   *   30077 / 30080 是 HEVC（头显能硬解）→ 最高分；
   *   30032 / 30033 等 30xxx 是 AVC → 次之；
   *   1000xx / 1002xx 是 AV1（PICO 4 无硬解）→ 垫底（只在没有别的可用时才选）。
   */
  function vidScore(code) {
    if (code === 30077 || code === 30080) return 300000 + code;
    if (code >= 30000 && code < 30100) return 200000 + code;
    if (code >= 100000) return 100000 + code;
    return 150000 + code;
  }

  function pairFromUrls() {
    var vid = null, aud = null;
    for (var i = 0; i < urls.length; i++) {
      var u = urls[i];
      var m = /\/upgcxcode\/(?:\d+\/)+([0-9]{5,})\/[^/]*?-([0-9]{5,})\.m4s/.exec(u);
      if (!m) continue;
      var cid = m[1];
      var code = parseInt(m[2], 10);
      /* 音频编号都在 302xx（30216 / 30232 / 30280）；30011…30080、1000xx 都是视频 */
      var isAudio = code >= 30200 && code < 30300;
      if (isAudio) {
        if (!aud || code > aud.code) aud = { cid: cid, code: code, url: u };
      } else {
        var sc = vidScore(code);
        if (!vid || sc > vid.score) vid = { cid: cid, code: code, url: u, score: sc };
      }
    }
    if (!vid) return null;
    if (aud && aud.cid !== vid.cid) aud = null;
    console.log('[b0b] 配对：视频 code=' + vid.code + '（' +
      (vid.score >= 300 ? 'HEVC' : vid.score >= 200 ? 'AVC' : 'AV1/其它') + '）' +
      '，音频 ' + (aud ? ('code=' + aud.code) : '无'));
    return {
      id: vid.code, height: 0, width: 0, bw: 0, vcodec: '', acodec: '',
      video: vid.url, audio: aud ? aud.url : '', pair: true
    };
  }

  function isPlayApi(u) {
    return !!u && u.indexOf('playurl') >= 0;
  }

  try {
    var origFetch = window.fetch;
    if (origFetch) {
      window.fetch = function (input, init) {
        var u = '';
        try {
          u = (typeof input === 'string') ? input : ((input && input.url) || '');
          note(u);
          noteApi(u);
        } catch (e) {}
        var p = origFetch.apply(this, arguments);
        try {
          if (isPlayApi(u) && p && p.then) {
            p.then(function (res) {
              try {
                res.clone().text().then(parsePlayurl).catch(function () {});
              } catch (e) {}
            }).catch(function () {});
          }
        } catch (e) {}
        return p;
      };
    }
  } catch (e) {}

  try {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      try {
        note(u);
        noteApi(u);
        if (isPlayApi(u)) {
          this.addEventListener('load', function () {
            /*
             * 2026-10-16 实测补：播放接口那一路一直解析不出来 —— 接口请求确实发生了
             * （诊断日志里有 /x/player/wbi/v2、/pgc/player/web/v2/playurl），
             * 但读返回的方式没吃全：responseType=json 时取 responseText 会直接抛错，
             * 被 catch 吞掉就什么都没发生。三种类型都照顾到。
             */
            try {
              var rt = this.responseType;
              if (rt === '' || rt === 'text') {
                parsePlayurl(this.responseText);
              } else if (rt === 'json') {
                var j = this.response;
                if (j) applyPlayinfo(j.data ? j.data : j);
              } else if (this.response) {
                parsePlayurl(new TextDecoder('utf-8').decode(this.response));
              }
            } catch (e) {}
          });
        }
      } catch (e) {}
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
    readGlobalPlayinfo();
    var best = null, bestArea = 0;
    /*
     * 只在"播放页"上认画面（父亲 2026-10-16：还没播、只是在首页点个「历史播放」，
     * 银幕比例也跟着变）—— 非播放页上的自动预览视频一律不算。
     */
    var onPlayPage = /\/(video|bangumi\/play|cheese\/play|medialist)\//.test(location.href);
    var vs = onPlayPage ? document.querySelectorAll('video') : [];
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      var vw = v.videoWidth || 0, vh = v.videoHeight || 0;
      if (!vw || !vh || v.readyState < 2) continue;
      var r = v.getBoundingClientRect();
      if (r.width < 40 || r.height < 40) continue;
      var st = window.getComputedStyle(v);
      if (!st || st.display === 'none' || st.visibility === 'hidden') continue;
      if (parseFloat(st.opacity || '1') < 0.05) continue;
      /*
       * 只认"正在播"的那个（2026-10-16 父亲实测：比例一直不停变）。
       * B站 页面上除了正片，还有推荐位/预览/广告的小视频，它们 paused=true；
       * 不筛的话每次扫描挑中的对象会来回换，银幕比例跟着跳。
       */
      if (v.paused) continue;
      /*
       * 太小的预览窗不算（父亲 2026-10-16：浏览页面时点个「历史播放」按钮，比例也在变）。
       * 正片在播放页上占视口宽度六成左右（实测 668/1100），缩略预览远小于此。
       */
      if (r.width < (window.innerWidth || 1) * 0.4) continue;
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
        /*
         * 银幕要用的比例 = 视频自己的比例（vw/vh）。
         * 【2026-10-16 修正】原来写的是 (cw*innerWidth)/(ch*innerHeight)，把视口尺寸乘进去，
         * 实测打出 4.242 这种荒唐值（4096x1716 的片子应是 2.387）→ 银幕会按错比例变宽。
         */
        ar: vw / vh,
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
      lastGood = { t: Date.now(), rect: msg.rect };
      note(best.url);
      if (!saidHello) {
        saidHello = true;
        console.log('[b0b] 看到视频 ' + vw + 'x' + vh + ' 画面块 ' + Math.round(cw) + 'x' +
          Math.round(ch) + ' @ ' + Math.round(best.x) + ',' + Math.round(best.y) +
          ' 视口 ' + iw + 'x' + ih);
      }
    } else if (lastGood && Date.now() - lastGood.t < 10000) {
      /*
       * 缓冲/暂停的瞬间别当成"没画面"（那会让银幕缩一下再弹回来）——沿用 10 秒内的上一份。
       */
      msg.rect = lastGood.rect;
    }
    if (urls.length) msg.urls = urls.slice(-10);
    /*
     * 兜底（2026-10-16）：接口那一路一直没抓到（playable 为空）时，
     * 用从分片地址配对出来的视频 + 音频顶上，先让"自己的播放器播"能跑起来。
     */
    if (!playable && urls.length) {
      var pick = pairFromUrls();
      if (pick) {
        var sig2 = 'pair|' + String(pick.video).slice(-60);
        if (sig2 !== lastPlaySig) {
          lastPlaySig = sig2;
          playable = {
            kind: 'play', t: Date.now(), frame: location.href,
            durSec: 0, quality: 0, streams: [pick]
          };
          console.log('[b0b] 兜底配对成功：视频 + ' + (pick.audio ? '音频' : '（无音频）'));
          try { window.postMessage({ __b0b: 1, payload: playable }, '*'); } catch (e) {}
        }
      }
    }
    /*
     * 节流：画面没变就最多每 3 秒报一次（客户端本来就是 500ms 挑一次），变了立刻报 ——
     * 免得每 400ms 给客户端塞一条，框架一多就成灾。
     */
    var key = msg.rect
      ? [Math.round(msg.rect.x * 100), Math.round(msg.rect.y * 100),
         Math.round(msg.rect.w * 100), Math.round(msg.rect.h * 100), urls.length].join(',')
      : 'none';
    if (key === lastKey && Date.now() - lastSent < 3000) return;
    lastKey = key;
    lastSent = Date.now();
    try { window.postMessage({ __b0b: 1, payload: msg }, '*'); } catch (e) {}
  }

  console.log('[b0b] 页面探子就绪 ' + location.href);
  setInterval(scan, 400);
  scan();
})();
