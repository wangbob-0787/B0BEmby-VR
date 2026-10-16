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
  var lastPlaySigAt = 0;   /* 去重限时用（见 applyPlayinfo） */

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

  /*
   * 抓到可播地址后**先暂存**，等页面确实"是我点开的播放页、画面真的在放"再交出去。
   *
   * 2026-10-16 父亲实测：「不等我选片，它就自动选了一部」—— B站 的页面自己会起播
   * （列表页/详情页的预览），只按 URL 判据不够，必须再看画面是不是真在放。
   */
  var pendingPlay = null;

  /*
   * 必须"扣过扳机"才允许接管（父亲 2026-10-16 实测）：
   * 「我的光点只要指到网页上一部片子，我还没有扣扳机，它就被选中了」——
   * B站 的悬停预览自己会播，光看"有画面在放"分不清是他在看还是只是划过。
   * 这里记下最后一次点击类动作的时间，只有 30 秒内的动作才算数（点完到起播足够）。
   */
  var actedAt = 0;
  try {
    ['pointerdown', 'mousedown', 'click', 'touchstart', 'keydown'].forEach(function (n) {
      window.addEventListener(n, function () { actedAt = Date.now(); }, true);
    });
  } catch (e) {}

  function actedRecently() {
    /*
     * 原来 30 秒：番剧页要过会员校验再起播，经常超过它，
     * 于是"点了、页面也确实在播，却被自己的超时挡掉"——
     * 现象就是"同一部片有时抓得到有时抓不到"（父亲 2026-10-16 报）。
     * 放宽到 2 分钟；真正的判据交给"页面是否在播"。
     */
    return Date.now() - actedAt < 120000;
  }

  function isPlayPage() {
    /*
     * 腾讯的播放页有两种形态：单集页 /x/cover/<cid>/<vid>.html，
     * 还有剧集页 /x/cover/<cid>.html —— 后者页面里内嵌的播放器照样会播
     * （父亲 2026-10-16 实测：停在剧集页面上，画面在播）。
     * 另外 /x/page/<vid>.html 也是它的单集形态。
     */
    if (/\/(video|bangumi\/play|cheese\/play|medialist)\//.test(location.href)) return true;
    if (/v\.qq\.com\/x\/cover\//.test(location.href)) return true;
    if (/v\.qq\.com\/x\/page\//.test(location.href)) return true;
    return false;
  }

  function pageIsPlaying() {
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (!v) continue;
      if (v.paused) continue;
      /* 0.3 秒太严：缓冲刚过、画面刚出来那一两秒也会被判"没在播" */
      if ((v.currentTime || 0) < 0.05) continue;
      if (!(v.videoWidth > 0)) continue;
      return true;
    }
    return false;
  }

  /*
   * 片名（父亲 2026-10-16：电影电视剧的片名没出来，UP主视频的名字倒是有）。
   *
   * 原因：番剧/影视页的 `document.title` 长时间还是站点首页标题，
   * 而 UP 主视频页的标题很早就换成视频名 —— 所以只取 document.title 时，
   * 影视内容抓到的就是"哔哩哔哩"。
   * 先从页面上的标题元素取（各站这类元素的类名不同，挨个试），
   * 再退回 document.title，并剥掉站点后缀。
   */
  /*
   * 选集列表里的一项、语言版本标签都不是片名 —— 父亲 2026-10-16 实测：
   * 电影电视剧抓回来的是"第12集""潮汕话版"这种。
   */
  function isJunkTitle(s) {
    if (!s) return true;
    if (s.length <= 2) return true;
    if (/^第?\s*\d+\s*[集话期]/.test(s)) return true;
    if (/(普通话|粤语|潮汕话|国语|原声|英语|日语|韩语)版?$/.test(s)) return true;
    if (/^(选集|选集列表|播放列表|相关推荐|猜你喜欢)$/.test(s)) return true;
    return false;
  }

  /*
   * 剥掉标题尾巴上的集号与语言版本：B站影视页的标题元素常常长成
   * "剧名 第12集"/"剧名 粤语版"，整段判"垃圾"不够，得先剥了再看
   * （父亲 2026-10-16：抓回来只剩后半截）。
   */
  function stripTitleTail(s) {
    var t = String(s || ''), prev = '';
    /* 反复剥："剧名 第3集 粤语版"要剥两次才干净（2026-10-16 自测发现） */
    for (var i = 0; i < 6 && t !== prev; i++) {
      prev = t;
      /* 站点名（前缀或后缀） */
      t = t.replace(/\s*[-_|·]\s*(bilibili|哔哩哔哩|腾讯视频|爱奇艺|优酷|芒果TV)\s*[-_|·]?\s*$/i, '');
      t = t.replace(/^\s*(哔哩哔哩|bilibili)\s*[-_|·]\s*/i, '');
      /* "…-电影-高清正版在线观看…" 这一串尾巴（父亲 2026-10-16：控制条上写着整页标题） */
      t = t.replace(/\s*[-_|·]\s*(高清|超清|蓝光)?(正版)?(在线观看|免费观看|观看|播放).*$/, '');
      /* 分类词 */
      t = t.replace(/\s*[-_|·]\s*(电影|电视剧|综艺|动漫|纪录片|番剧|国创|少儿|音乐|游戏|知识|科技|体育|影视|直播|娱乐)\s*[-_|·]?\s*$/, '');
      /* 集号与语言版本 */
      t = t.replace(/\s*[-_|·]?\s*(普通话|粤语|潮汕话|客家话|闽南语|国语|原声|中配|台配|日语|英语)\s*版?\s*[)）]?\s*$/, '');
      t = t.replace(/\s*[-_|·]?\s*第?\s*\d+\s*[集话期]\s*$/, '');
      /* 花絮类 */
      t = t.replace(/\s*[-_|·]?\s*(预告|花絮|片段|正片|抢先看|会员版|加长版)\s*$/, '');
      t = t.trim();
    }
    return t.trim();
  }

  function pageTitle() {
    var sels = [
      '.video-title', 'h1.video-title', '.mediainfo_mediaTitle',
      '.media-title', '.bpx-player-top-title', '.player-title', 'h1'
    ];
    for (var i = 0; i < sels.length; i++) {
      var e = document.querySelector(sels[i]);
      var s = e && e.textContent ? e.textContent.trim() : '';
      if (s && !/^哔哩哔哩/.test(s)) {
        var st = stripTitleTail(s);
        if (st && !isJunkTitle(st)) return st.slice(0, 80);
      }
    }
    var dt = (document.title || '')
      .replace(/[_|]\s*哔哩哔哩.*$/, '')
      .replace(/^哔哩哔哩.*$/, '')
      .replace(/[-_|]\s*腾讯视频.*$/, '')
      .trim();
    var st2 = stripTitleTail(dt);
    return (st2 && !isJunkTitle(st2)) ? st2.slice(0, 80) : '';
  }

  /* 每轮扫描都问一次：条件齐了就交出去（只交一次） */
  function flushPendingPlay() {
    /*
     * 腾讯那条兜底必须放在最前面：它本身就是"没有解析到播放接口"时的替代品。
     * 原来写在"待播为空就返回"的后面，等于永远走不到 ——
     * 父亲 2026-10-16 现场：播放列表已经识别到，却始终没交出去。
     */
    if (!pendingPlay && pendingQq && Date.now() - qqAt < 180000) {
      pendingPlay = {
        title: '', kind: 'play', t: Date.now(), frame: location.href,
        durSec: 0, quality: 0, site: 'qq',
        streams: [{ id: 0, height: 0, width: 0, bw: 0, vcodec: 'h264',
                    video: pendingQq, audio: '' }]
      };
      console.log('[b0b] 腾讯：用抓到的 HLS 播放列表交给内核');
    }
    /* 每道关卡都留下记录：抓不到时能一眼看出卡在哪 */
    if (!pendingPlay) return;
    if (!isPlayPage()) { console.log('[b0b] 没交出去：当前不是播放页 ' + location.href); return; }
    if (!actedRecently()) { console.log('[b0b] 没交出去：扣扳机已经超过 2 分钟'); return; }
    if (!pageIsPlaying()) { console.log('[b0b] 没交出去：页面还没在播（缓冲/广告/没开始）'); return; }
    var obj = pendingPlay;
    pendingPlay = null;
    /* 标题在交出去这一刻现取（抓取时页面标题可能还没更新） */
    try { obj.title = pageTitle(); } catch (e) {}
    console.log('[b0b] 页面已在播放，把抓到的地址交给客户端');
    try { window.postMessage({ __b0b: 1, payload: obj }, '*'); } catch (e) {}
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
      /*
       * 同一份播放接口短时间去重即可 —— 原来是不限时去重，
       * 于是同一部片子第二次打开时签名与上次相同、直接 return，
       * 表现为"同一部片有时抓得到有时抓不到"（父亲 2026-10-16 实测）。
       */
      if (sig === lastPlaySig && Date.now() - lastPlaySigAt < 15000) return;
      lastPlaySig = sig;
      lastPlaySigAt = Date.now();
      playable = {

        /* 控制条要写「站点-片名」，标题从页面取（父亲 2026-10-16） */

        title: '',   /* 交出去那一刻用 pageTitle() 现取 */
        kind: 'play', t: Date.now(), frame: location.href,
        durSec: durSec, quality: d.quality || 0, streams: streams.slice(0, 10)
      };
      console.log('[b0b] 拿到播放接口：' + streams.length + ' 档，时长 ' + durSec +
        's，最高 ' + (streams[0].height || '?') + 'p');
      pendingPlay = playable;
    } catch (e) {}
  }

  /* 接口返回是文本：解析后交给 applyPlayinfo */
  /*
   * 腾讯在火狐内核下不请求 m3u8 播放列表（父亲 2026-10-16 现场：分片 .ts 抓到了、
   * 播放列表一次都没有）。它的地址是随接口响应发下来的，所以不去猜它的结构，
   * 直接在响应文本里找 m3u8 字样。
   */
  /*
   * 兜底路：不管这次响应叫什么名字，只要正文里带着 dash 地址就捡出来。
   * B站 换片/切集时接口名会变，按名字认容易漏（父亲 2026-10-16）。
   */
  function scanTextForBiliDash(text) {
    try {
      if (!text || text.length > 4000000) return;
      if (text.indexOf('"baseUrl"') < 0 && text.indexOf('"base_url"') < 0) return;
      var j = JSON.parse(text);
      var d = (j && (j.result || j.data || j)) || null;
      if (d && (d.dash || d.durl)) applyPlayinfo(d);
    } catch (e) {}
  }

  function scanTextForQqHls(text) {
    try {
      if (!text) return;
      var m = String(text).match(/https?:\/\/[^"'\s]{10,600}?\.m3u8[^"'\s]{0,300}/g);
      if (m && m.length) {
        var u = m[0].replace(/\\u002F/gi, '/').replace(/\\\//g, '/');
        if (u !== pendingQq) {
          pendingQq = u; qqAt = Date.now();
          console.log('[b0b] 腾讯播放列表（从接口响应里找到）');
        }
      }
    } catch (e) {}
  }

  function parsePlayurl(text) {
    try {
      var j = JSON.parse(text);
      if (!j) return;
      scanTextForQqHls(text);            /* 腾讯的地址夹在响应里 */
      /*
       * 两种结构都要认（父亲 2026-10-16）：
       *   普通视频 x/player/playurl  →  { data:  { dash } }
       *   番剧影视 pgc/.../playurl   →  { code, message, result: { dash } }
       * 原来只认 data，番剧那一路等于没解析 —— 表现就是"有时抓得到有时抓不到"。
       */
      applyPlayinfo(j.result || j.data || j);
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

  /*
   * 通用媒体地址雷达（2026-10-16，摸腾讯 / 爱奇艺 / 优酷用）。
   *
   * 每家站的播放接口千差万别（腾讯 getinfo 要拼 vkey，爱奇艺还要签名），
   * 与其先猜，不如先把"页面到底去要了哪些地址"看清楚：
   *   · 播放接口（getinfo / playurl / dash / .json）；
   *   · 媒体文件（.m4s / .mp4 / .m3u8 / .ts）；
   *   · video 标签的最终地址（HLS 会是 blob:，那时只能靠请求记录）。
   * 收满一批按行打到日志，客户端那边的日志里就能直接 grep `[b0b]媒体 `。
   */
  var mediaHits = [];
  var lastPairedVideo = '';
  /*
   * 闸：页面"真在放片"的时刻。0 = 还没确认，此时收到的片段一律丢掉。
   * 由 scan() 每 400ms 维护（父亲 2026-10-16 定的顺序：
   * 先确认真在播 → 才开始收它下载的片段 → 时间窗就没必要了）。
   */
  var liveSince = 0;
  var mediaKeys = {};

  function looksMedia(u) {
    /* 只挡掉一眼就是静态资源/统计的（图标、字体、埋点），其余都记 ——
     * 摸新站时宁可多记：腾讯那种接口名事先并不知道，漏一条就等于白跑一趟。
     * （2026-10-16 第一次雷达就因为只认"媒体后缀 + 已知接口名"，把腾讯整站漏掉了。） */
    if (/\.(svg|png|jpe?g|webp|gif|ico|woff2?|ttf|otf|css|js|mjs|wasm|map)(\?|$)/i.test(u)) return false;
    if (/data\.bilibili\.com\/log|hdslb\.com\/bfs|hm\.baidu\.com|sentry|beacon|favicon/i.test(u)) return false;
    return true;
  }

  /*
   * 腾讯视频（v.qq.com）：播放走标准 HLS，`<video>` 的 src 是 blob:，
   * 看不到真地址；但请求里那条 .m3u8 播放列表就是全部答案 ——
   * 它自带时效 token，抓到交给内核就能直接播。
   * 与 B站 不同：腾讯这条路不需要解析播放接口，认出来即可。
   * （2026-10-16 在电脑浏览器上实测：mpv 带 Referer 能正常出画出声）
   */
  var pendingQq = '', qqAt = 0;

  function isQqHls(u) {
    if (!/\.m3u8(\?|$)/i.test(u)) return false;
    return /(gtimg\.com|smtcdns\.com|tc\.qq\.com|video\.qq\.com)/i.test(u);
  }

  /*
   * 有些请求拦不到 fetch/XHR 的钩子（播放器在 Worker 里发、或内核代发），
   * 但浏览器的资源时间线里一定有它 —— 父亲 2026-10-16 实测：腾讯的
   * 播放列表没抓到，反倒是分片抓到了，说明播放列表请求走了另一条路。
   * 每轮扫描顺带看一眼资源时间线。
   */
  function scanPerfForHls() {
    try {
      if (!window.performance || !performance.getEntriesByType) return;
      var es = performance.getEntriesByType('resource');
      for (var i = 0; i < es.length; i++) {
        var u = es[i] && es[i].name;
        if (u && isQqHls(u) && u !== pendingQq) {
          pendingQq = u; qqAt = Date.now();
          console.log('[b0b] 腾讯播放列表（从资源时间线找到）');
        }
      }
    } catch (e) {}
  }

  function noteMedia(u) {
    if (!u || typeof u !== 'string') return;
    if (u.slice(0, 5) === 'blob:') return;
    if (!looksMedia(u)) return;
    /* 闸：没确认真在放片之前，收到的片段一律不算（父亲 2026-10-16 定的顺序） */
    if (!liveSince) return;
    var key = u.split('?')[0];
    if (mediaKeys[key]) return;            /* 同一条主干只记一次（分片换参数也算一条） */
    /* 值要存完整地址：分片带时效 token，丢了参数就播不了（2026-10-16） */
    mediaKeys[key] = u;
    mediaHits.push(u);
    if (isQqHls(u)) {                      /* 腾讯：抓到播放列表就等于拿到了地址 */
      pendingQq = u; qqAt = Date.now();
      console.log('[b0b] 腾讯播放列表已记下（长度 ' + u.length + '）');
    }
    addCandidate(u);                  /* 进候选池（不按名字挑） */
    {
      var kk = candKeyOf(u), cc = String(u).match(/-1-(\d{3,6})\.(m4s|mp4)/i);
      console.log('[b0b]② 收到片段：片子 ' + kk + '，类型 ' + (cc ? cc[1] : '?') +
        '，池子里已有 ' + candPool.length + ' 条');
    }
    if (mediaHits.length >= 60) flushMedia();
    tryPairFromHits(mediaKeys);       /* 每收到一条就试着取一条候选（父亲 2026-10-16） */
  }

  /* 配对成功就把待播地址填上（页面是否在播交给后面几道关卡判断） */
  /*
   * B站 番剧/影视还会给一条"整片"（路径带 ogvresxcode 的 .mp4），
   * 一条就含画面和声音，比配对省事也更稳（父亲 2026-10-16 现场日志里出现过）。
   */
  function findWholeFile(list) {
    for (var i = 0; i < list.length; i++) {
      var u = String(list[i]);
      if (/ogvresxcode/i.test(u) && /\.mp4(\?|$)/i.test(u)) return u;
    }
    return '';
  }

  /*
   * 候选池（父亲 2026-10-16 定调：一次搞明白，别再按名字猜）。
   * B站 的文件名写法随片子变（-sr1 / _sr1 / _qe1 / ogvresxcode / 纯数字 …），
   * 按名字认必然漏，前面两轮就是这么漏的。
   * 改成：凡是视频服务器上下的东西全进池子，按"一条能不能直接播"排序，
   * 一条不行换下一条，交给播放器去判 —— 比我猜命名规则准。
   */
  var candPool = [];          /* [{u, t, key}]，带时间戳 */
  var candSeen = {};
  var candTried = 0;
  /*
   * 只看最近这段时间内出现的片段（父亲 2026-10-16 定的口径：往前 5 秒、往后 10 秒）。
   * 不能按历史累计 —— 上一部片子的片段还留在池子里，
   * 累计数一多就会把已经换掉的那部当成"在播的那部"，直接播错片。
   * 前 5 秒：刚点下去就冒出来的那几条要算；后 10 秒：起播慢的片子给它缓冲余地。
   */
  var CAND_WINDOW_MS = 15000;

  function isBiliMedia(u) {
    if (!/bilivideo\.com|akamaized\.net|hdslb\.com/i.test(u)) return false;
    return /\.(m4s|mp4|m3u8)(\?|$)/i.test(u);
  }

  /* 分数越高越先试：整片 > 播放列表 > 高画质分片 > 声音分片 > 其他 */
  function candRank(u) {
    if (/ogvresxcode/i.test(u) && /\.mp4(\?|$)/i.test(u)) return 900;
    if (/\.m3u8(\?|$)/i.test(u)) return 800;
    var m = String(u).match(/-1-(\d{3,6})\.(m4s|mp4)/i);
    var code = m ? parseInt(m[1], 10) : 0;
    if (code >= 30200 && code < 30400) return 300;
    if (code >= 30000 && code < 30200) return 600 + (vidScore(code) % 100) / 100;
    if (code >= 100000) return 400;
    if (code >= 1000) return 500;
    return 200;
  }

  /*
   * 片子编号要从"文件名"里取。
   * 原来从整条路径里找数字，取到的是 upgcxcode/73/43/ 里的 43，
   * 结果所有片段被归成同一部，配对全错（父亲 2026-10-16 用真实地址定位）。
   * 真实形态：…/upgcxcode/73/43/41325364373/41325364373_sr1-1-100035.m4s
   */
  function biliFileOf(u) {
    return String(u).split('?')[0].split('/').pop() || '';
  }
  function candKeyOf(u) {
    var f = biliFileOf(u);
    var m = f.match(/^([0-9a-z_]{5,}?)(?:[-_]s?r\d+|[-_]qe\d+)?-1-\d{3,6}\.(m4s|mp4)$/i);
    if (m) return m[1];
    var m2 = String(u).match(/upgcxcode\/[^\/]+\/[^\/]+\/(\d{5,})\//);
    return m2 ? m2[1] : 'x';
  }

  function addCandidate(u) {
    if (!isBiliMedia(u)) return;
    var k = String(u).split('?')[0];
    if (candSeen[k]) return;
    candSeen[k] = 1;
    candPool.push({ u: u, t: Date.now(), key: candKeyOf(u) });
    if (candPool.length > 400) candPool = candPool.slice(-200);
  }

  /*
   * 按"片子编号"分组（父亲 2026-10-16 提醒会播错片）。
   * 页面会顺手预取旁边列表里其他片子的片段，混在一起就会拿错；
   * 正在播的那部会持续下很多片段，预取的只有一两条 —— 取片段最多的那组。
   * 组内若出现"整片 mp4"，那一定是在播的那部，优先它。
   */
  function pickGroup() {
    var now = Date.now();
    var g = {}, order = [], i, key;
    for (i = 0; i < candPool.length; i++) {
      var e = candPool[i];
      if (!e) continue;
      /* 不再按时间过滤：能进池的只可能是"确认真在播之后"收的片段（见 noteMedia 的闸） */
      key = e.key;
      if (!g[key]) { g[key] = []; order.push(key); }
      g[key].push(e.u);
    }
    var wholeKey = '', bestKey = '', bestN = 0;
    for (i = 0; i < order.length; i++) {
      var kk = order[i];
      if (findWholeFile(g[kk])) wholeKey = kk;          /* 整片优先 */
      if (g[kk].length > bestN) { bestN = g[kk].length; bestKey = kk; }
    }
    var useKey = wholeKey || bestKey;
    return useKey ? g[useKey] : [];
  }

  /*
   * 一次给出排好序的多条候选（父亲 2026-10-16 定）：
   * 客户端拿第一条起播，8 秒还不出画面就换第二条 —— 换的动作放在客户端，
   * 因为只有它知道内核有没有真的拉起来（扩展这边看不到播放器）。
   * 顺序：整片文件 → 画面+声音配对 → 其它画面分片（清晰度高的在前）。
   */
  function pickCandidates(n) {
    var grp = pickGroup();
    if (!grp.length) return [];
    var out = [], seen = {}, i, u;
    var whole = findWholeFile(grp);
    if (whole) {
      out.push({ id: 0, height: 0, width: 0, bw: 0, vcodec: 'h264', video: whole, audio: '' });
      seen[whole] = 1;
    }
    var pair = pairFromHits(grp);
    if (pair && pair.video && !seen[pair.video]) {
      out.push({ id: 0, height: 0, width: 0, bw: 0, vcodec: 'h264',
                 video: pair.video, audio: pair.audio || '' });
      seen[pair.video] = 1;
    }
    var sorted = grp.slice().sort(function (a, b) { return candRank(b) - candRank(a); });
    for (i = 0; i < sorted.length && out.length < n; i++) {
      u = sorted[i];
      if (seen[u]) continue;
      /* 声音分片单独交出去没意义（没画面），只挑画面类 */
      if (candRank(u) < 500) continue;
      seen[u] = 1;
      out.push({ id: 0, height: 0, width: 0, bw: 0, vcodec: 'h264', video: u, audio: '' });
    }
    return out;
  }

  function tryPairFromHits(keysAll) {
    try {
      if (pendingPlay) return;
      var cands = pickCandidates(6);
      if (!cands.length) return;
      if (cands[0].video === lastPairedVideo) return;
      lastPairedVideo = cands[0].video;
      pendingPlay = {
        title: '', kind: 'play', t: Date.now(), frame: location.href,
        durSec: 0, quality: 0, site: '', streams: cands
      };
      console.log('[b0b]③ 交给客户端 ' + cands.length + ' 条候选：' +
        cands.map(function (c, i) {
          return (i + 1) + ')' + (c.audio ? '画面+声音' : '单条') + ' ' + String(c.video).slice(-26);
        }).join(' | '));
    } catch (e) {}
  }

  /*
   * 从抓到的分片里配对出可播地址 —— 这是 B站 换片/切集/点旁边列表唯一可靠的路。
   * 原因（父亲 2026-10-16 现场三层证据）：换片时它不请求播放地址接口，
   * 地址在页面自己的缓存里，外面接不到；它发的那几个请求正文里也没有地址。
   * 但分片一直在下载，命名有规律：
   *   …/upgcxcode/aa/bb/<cid>/<cid>[-srN]-1-<code>.m4s
   * 同一条 <cid> 里挑一条画面 + 一条声音即可（本机 mpv 实测这种地址可直接播）。
   * 30xxx 里 302xx/303xx 是音频，300xx 是画面；1000xx 是 AV1（垫底）。
   */
  function pairFromHits(list) {
    var vids = {}, auds = {}, cidOrder = [];
    for (var i = 0; i < list.length; i++) {
      var u = list[i];
      /* 实测命名有 -sr1 / _sr1 / _qe1 几种写法（父亲 2026-10-16 现场日志） */
      var f = biliFileOf(u);
      var m = f.match(/^([0-9a-z_]{5,}?)(?:[-_]s?r\d+|[-_]qe\d+)?-1-(\d{3,6})\.(m4s|mp4)$/i);
      if (!m) continue;
      var cid = m[1];
      var code = parseInt(m[2], 10);
      if (!vids[cid] && !auds[cid]) cidOrder.push(cid);
      var isAudio = (code >= 30200 && code < 30400);
      if (isAudio) {
        if (!auds[cid] || code > auds[cid].code) auds[cid] = { u: u, code: code };
      } else {
        if (!vids[cid] || vidScore(code) > vidScore(vids[cid].code)) vids[cid] = { u: u, code: code };
      }
    }
    var best = null;
    for (var k = 0; k < cidOrder.length; k++) {
      var c = cidOrder[k], v = vids[c];
      if (!v) continue;
      var a = auds[c];
      var sc = vidScore(v.code) + (a ? 1000000 : 0);
      if (!best || sc > best.sc) best = { sc: sc, v: v, a: a, cid: c };
    }
    if (!best) return null;
    return { video: best.v.u, audio: best.a ? best.a.u : '' };
  }

  function flushMedia() {
    if (!mediaHits.length) return;
    var list = mediaHits;
    mediaHits = [];
    for (var i = 0; i < list.length; i++) {
      console.log('[b0b]媒体 ' + String(list[i]).slice(0, 900));
    }
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
    /*
     * 原来只认名字里带 playurl 的请求。B站 在同一个页面里换片、点集数标签切集时
     * 走的是 x/player/wbi/v2、x/player/v2 这些（名字里没有 playurl），
     * 于是"进页面第一次能接管、之后换内容就不行"（父亲 2026-10-16 报）。
     * 凡是 B站 播放相关的接口全接。
     */
    if (!u || u.indexOf('bilibili.com') < 0) return false;
    if (u.indexOf('playurl') >= 0) return true;
    if (u.indexOf('/x/player/') >= 0) return true;
    if (u.indexOf('/pgc/player/') >= 0) return true;
    return false;
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
        noteMedia(u);
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
        noteMedia(u);
        /* B站：不管接口叫什么名字，正文里带 dash 地址就捡（换片/切集兜底） */
        if (u.indexOf('bilibili.com') >= 0) {
          this.addEventListener('load', function () {
            try {
              var rt0 = this.responseType;
              if (rt0 === '' || rt0 === 'text') scanTextForBiliDash(this.responseText);
              else if (rt0 === 'json') scanTextForBiliDash(JSON.stringify(this.response));
              else if (this.response) scanTextForBiliDash(new TextDecoder('utf-8').decode(this.response));
            } catch (e) {}
          });
        }
        /* 腾讯：地址在这些接口的响应里，读回来扫一遍 */
        if (u.indexOf('qq.com') >= 0) {
          this.addEventListener('load', function () {
            try {
              var rt = this.responseType;
              if (rt === '' || rt === 'text') scanTextForQqHls(this.responseText);
              else if (rt === 'json') scanTextForQqHls(JSON.stringify(this.response));
              else if (this.response) scanTextForQqHls(new TextDecoder('utf-8').decode(this.response));
            } catch (e) {}
          });
        }
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
                if (j) applyPlayinfo(j.result || j.data || j);
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
    try { scanPerfForHls(); } catch (e) {}
    /*
     * video 标签的最终地址也扫一眼：有的站不给接口拿地址（或地址是 blob:），
     * 这一路能把 HLS/MP4 的真实入口露出来。
     */
    try {
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) {
        if (vs[i] && vs[i].currentSrc) noteMedia(vs[i].currentSrc);
      }
    } catch (e) {}
    flushMedia();
    readGlobalPlayinfo();
    flushPendingPlay();
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
      /*
       * 预览窗不算（父亲 2026-10-16 定的口径：真在播的至少占视口面积两成）。
       * 原来按宽度卡四成，遇到"又宽又扁"的海报预览挡不住，改按面积。
       * 实测参照：正片播放 1100x619 占满；海报预览约 300x170，只有几个点。
       */
      if ((r.width * r.height) < (window.innerWidth || 1) * (window.innerHeight || 1) * 0.2) continue;
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
    /*
     * 开闸/关闸（父亲 2026-10-16 定的顺序）：
     * 先确认页面上真有一个大窗口在放片，确认之后才开始收它下载的片段；
     * 页面一停就清空已收的（换集时正好靠这一下重来，不会串片）。
     */
    if (best) {
      if (!liveSince) {
        liveSince = Date.now();
        console.log('[b0b]① 检测到在播的播放窗口：' + Math.round(best.w) + 'x' + Math.round(best.h) +
          '，占屏幕 ' + Math.round(best.area * 100 / ((window.innerWidth || 1) * (window.innerHeight || 1))) +
          '%，开始收它的片段');
      }
    } else if (liveSince) {
      liveSince = 0;
      candPool = [];
      candSeen = {};
      candTried = 0;
      console.log('[b0b]① 页面上没有在播的播放窗口了，已收的片段清空');
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
          pendingPlay = playable;
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
