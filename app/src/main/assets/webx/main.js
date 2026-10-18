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
    /* 【2026-10-17 12:55 父亲实测定案：电影频道页 /movie/ 也是播放页 ——
   * 12:52 现场他在 /movie/ 的片上放大，全程被"当前不是播放页"拦死。 */
    if (/\/(video|bangumi\/play|cheese\/play|medialist|movie)\//.test(location.href)) return true;
    if (/v\.qq\.com\/x\/cover\//.test(location.href)) return true;
    if (/v\.qq\.com\/x\/page\//.test(location.href)) return true;
    return false;
  }

  /*
   * 页面上那个正在播的视频元素的真实尺寸（拿不到就 0）。
   * 用途：银幕比例必须每部片子重算（父亲 2026-10-17 立的规矩）——
   * 网页流的地址常常不带宽高，客户端就只能沿用上一部片子的比例，画面就被压扁。
   * 页面里的视频元素自己知道尺寸，顺手带过去最准、也最早。
   */
  function pageVideoSize() {
    try {
      var vs = document.querySelectorAll('video');
      for (var i = 0; i < vs.length; i++) {
        var v = vs[i];
        if (v && v.videoWidth > 0 && v.videoHeight > 0) {
          return { w: v.videoWidth | 0, h: v.videoHeight | 0 };
        }
      }
    } catch (e) {}
    return { w: 0, h: 0 };
  }

  function pageIsPlaying() {
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (!v) continue;
      if (v.paused) continue;
      if (!v.__b0bWin) { v.__b0bWin = 'w' + (++winSeq); }
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
    if (!pendingPlay && !pendingQq) {
      var lq = loadQqLocal();
      if (lq) {
        pendingQq = cleanUrl(lq.u); qqAt = lq.at || 0; qqKey = lq.key || '';
        console.log('[b0b] 从本地存储取回腾讯地址（页面或播放器层被重建过）');
      }
    }
    /* 手里有地址却交不出去：每 3 秒报一次状态（父亲 2026-10-18 02:10 要求日志更细） */
    var qqNow = Date.now();
    if (pendingQq && qqNow - (window.__b0bHoldLog || 0) > 3000) {
      window.__b0bHoldLog = qqNow;
      console.log('[b0b] 交接追踪：地址在手（' + String(pendingQq).length + ' 字符，抓于 ' +
        Math.round((qqNow - qqAt) / 1000) + ' 秒前）尚未交出去 —— 全屏(本层=' +
        (fullState ? 1 : 0) + ' 存=' + (fsShared ? 1 : 0) + ' 别层=' + (peerFullAt ? 1 : 0) +
        ') 同集=' + (keyKnown(qqKey) ? (qqKey === pageVideoKey(location.href) ? 1 : 0) : '未知'));
    }
    var qqHere = pageVideoKey(location.href);
    var qqSamePage = !keyKnown(qqKey) || !keyKnown(qqHere) || qqKey === qqHere;
    if (!pendingPlay && pendingQq && Date.now() - qqAt < 30 * 60 * 1000 && qqSamePage) {
      var qqSz = pageVideoSize();
      pendingPlay = {
        title: '', kind: 'play', t: Date.now(), frame: location.href, winId: poolWin,
        durSec: 0, quality: 0, site: 'qq', width: qqSz.w, height: qqSz.h,
        streams: [{ id: 0, height: 0, width: 0, bw: 0, vcodec: 'h264',
                    video: pendingQq, audio: '' }]
      };
      console.log('[b0b] 腾讯：用抓到的 HLS 播放列表交给内核（抓于 ' +
        Math.round((Date.now() - qqAt) / 1000) + ' 秒前，同一集）');
    }
    if (!pendingPlay && pendingQq && !qqSamePage) {
      console.log('[b0b] 手里那份腾讯地址是**别的集**的（已经翻页），丢掉等新的');
      pendingQq = ''; qqAt = 0;
    }
    /* 每道关卡都留下记录：抓不到时能一眼看出卡在哪 */
    if (!pendingPlay) return;
    /* 【2026-10-17 15:14 父亲定：页面白名单撤了 —— 全屏按钮就是身份 */
    /*
     * 【2026-10-17 03:22 父亲定的口径：停多久都没关系】
     * 原来这里有一道"扣扳机超过 2 分钟就不交"的门 —— 删掉。
     * 判据只有两条：① 地址没变（还是同一份记录）② 播放窗口铺满九成。
     * 只看这一下，不再看"他多久以前点的"。
     */
    /*
     * 【2026-10-17 23:14 父亲定：撤掉"页面在播"这道判定】
     *
     * 父亲原话："不播就不会有地址请求，你既然抓到了地址，说明在播。"
     * 地址是从页面自己的请求里捞出来的 —— 能捞到，就说明页面真去要过片子。
     * 腾讯今晚的现场正是反例：他点了播放、页面在放，我们的探子却看不见它的播放器
     * （腾讯把真正的 video 封在自己的播放器组件里），于是明明手里有地址，却在这里被挡回去。
     * 现在交接的判据只剩两条：① 地址是刚抓到的（时效见上面 pendingQq 的 3 分钟）
     * ② 你按了全屏（下面那道 fullState）。广告/切集的旧地址由"抓到新的就再交一次"顶掉。
     */
    /* 原来这里：if (!pageIsPlaying()) { …return; } —— 已撤（2026-10-17 23:14 父亲定） */
    /*
     * 【2026-10-16 父亲实测定案：小窗抢播的真凶就是缺这一道关】
     *
     * 上面的 pageIsPlaying() 只问"页面上有东西在播"，点一集时那个小窗、推荐位预览
     * 都能满足它 —— 于是 90% 的门槛形同虚设，父亲还没选完季/集就被顶走。
     * liveSince 是 scan() 里只有"窗口占了屏幕九成"才会置上的标记：
     *   没置上 = 现在只是小窗试看 → 不交。
     */
    /*
     * 接管门槛仍是九成（父亲 2026-10-17 定的顺序：先抓地址，铺满九成才接管）。
     *
     * 【2026-10-18 01:05 破案】父亲点网页全屏后日志仍说「你还没按全屏按钮」。
     * 原因：全屏这件事原来只记在**按下它的那一层**（fullState 是个每层独立的变量），
     * 而腾讯把它记在最外层、播放接口的响应却是在播放器那一层被扫到的 ——
     * 两层各自算，里头那层永远看不见外头按过全屏，于是地址抓到了也交不出去。
     * 改成：按全屏时往 localStorage 记一个时间戳（**同站点各层共享**），
     * 哪一层来交都能读到；翻页时清掉（下面归零那处），语义仍是"这一页我按过全屏"。
     */
    var fsShared = 0;
    try { fsShared = parseInt(localStorage.getItem('b0b:fullAt') || '0', 10) || 0; } catch (e) {}
    var fsStamps = [fullState ? Date.now() : 0, fsShared, peerFullAt];
    var fsBest = Math.max.apply(null, fsStamps);
    var fsOk = fsBest > 0 && Date.now() - fsBest < 3 * 3600 * 1000;
    if (!fsOk) {
      console.log('[b0b] 没交出去：你还没按全屏按钮（本层=' + (fullState ? 1 : 0) +
        ' 本地存=' + (fsShared ? 1 : 0) + ' 别层报=' + (peerFullAt ? 1 : 0) + '）');
      return;
    }
    /*
     * 交接现场诊断（2026-10-17 23:14 加）：把这一刻页面上的 video 元素长什么样打出来。
     * 腾讯"抓到了地址却说没在播"那件事，靠这一行就能分清是
     * 「它把播放器封在组件里、我们一个都看不见」还是「它真的没播」。
     */
    try {
      var vids = document.querySelectorAll('video');
      var vst = [];
      for (var vi = 0; vi < vids.length && vi < 3; vi++) {
        var vv = vids[vi];
        vst.push((vv.paused ? '停' : '播') + ' ' + (vv.videoWidth || 0) + 'x' + (vv.videoHeight || 0) +
          ' ' + (Math.round((vv.currentTime || 0) * 10) / 10) + 's');
      }
      console.log('[b0b] 交接现场：页面 video 元素 ' + vids.length + ' 个' +
        (vids.length ? '（' + vst.join(' / ') + '）' : ' —— 一个都没找到'));
    } catch (e) {}
    /*
     * 【2026-10-17 03:54 简化】只有一个门槛之后，手里的那份必定属于这一次放大，
     * 所以只做一件事：它是不是当前这一集？是就交，不是就丢掉重新攒。
     */
    var obj = pendingPlay;
    /* 身份里带上当前页面地址：腾讯那种没有集号的情况靠它判同一次播放（2026-10-17） */
    var nowId = { ep: biliEp, season: biliSeason, cid: biliCid, href: location.href };
    if (obj && !sameEpisode(obj, nowId)) {
      console.log('[b0b]③手里那份不是当前这一集（它 ' + (obj.ep || obj.cid || '?') +
        '，现在 ep' + (biliEp || '?') + '）→ 丢掉，重新攒');
      pendingPlay = null;
      obj = null;
    }
    var objFrom = '手里那份';
    /*
     * 【2026-10-17 04:36 审读发现】播放接口的响应（playurl / __playinfo__）是页面
     * 自己报的标准答案：画面+声音成对、带清晰度和时长。手里那份若**没有声音**
     * 而接口那份（同一集）有声音 → 改用接口那份；手里空着而接口有货 → 直接用接口那份。
     * 片段池配对仍是兜底（B站 换片时接口不重新请求，那时只能靠碎片）。
     */
    try {
      var apiHasAudio = !!(playable && playable.streams && playable.streams[0] &&
                           playable.streams[0].audio);
      var apiUsable = !!(playable && playable.streams && playable.streams.length &&
                         sameEpisode(playable, nowId));
      var mineHasAudio = !!(obj && obj.streams && obj.streams[0] && obj.streams[0].audio);
      if (apiUsable && apiHasAudio && (!obj || !mineHasAudio)) {
        if (obj) console.log('[b0b]③手里那份没有声音，改用播放接口那份（成对，' +
          playable.streams.length + ' 档）');
        else console.log('[b0b]③手里没有攒到货，直接用播放接口那份（成对，' +
          playable.streams.length + ' 档）');
        /*
         * 【2026-10-17 04:42 审读发现】客户端的弹幕按我们报的"片子编号"拉
         * （api.bilibili.com/x/v1/dm/list.so?oid=<编号>）。接口那份创建时编号可能还没得知，
         * 递交这一刻现补 —— 不补弹幕就拉不到。
         */
        if (!playable.cid && biliCid) playable.cid = biliCid;
        playable.subtitle = biliSubUrl;
        playable.winId = poolWin;
        obj = playable;
        objFrom = '播放接口';
      }
    } catch (e) {}

    /*
     * 手里那份被丢掉、播放接口那份也没有 → 这一轮没有可交的东西。
     * 原来不判空就往下走（去取 obj.streams），每轮都抛一次
     * TypeError: can't access property "streams", obj is null（2026-10-17 腾讯现场）。
     */
    if (!obj) {
      console.log('[b0b]③手里那份被丢了、接口那份也没有 → 这一轮不交');
      return;
    }

    /*
     * 【2026-10-17 03:22】交过的那份**不再丢掉**：父亲会按 B 回网页、再放大，那时还要用同一份。
     * 只做 3 秒去重，免得每 400ms 重复递一次（客户端本来也有去重，这里少打日志）。
     */
    var subSig = String((obj.streams && obj.streams[0] && obj.streams[0].video) || '').slice(-60);
    if (subSig === lastSubmitSig && Date.now() - lastSubmitAt < 3000) {
      console.log('[b0b]③刚刚交过同一份（3 秒内），不重复交');
      return;
    }
    lastSubmitSig = subSig;
    lastSubmitAt = Date.now();
    /*
     * 【2026-10-17 13:31 父亲定的唯一防误】同一条地址 20 秒内只接一次（记在浏览器本地存储，
     * 按过 B 网页重建也认得）：不然按 B 回来 B站 自己恢复成全屏（实测会），
     * 3 秒后就被再次顶走，按 B 就永远退出不来。这不是小窗判断，就是"同一条地址不重复接"。
     */
    try {
      var _last = {};
      _last = JSON.parse(localStorage.getItem('b0b_sub') || '{}');
    } catch (e) { _last = {}; }
    if (_last && _last.s === subSig && Date.now() - (_last.t || 0) < 20000) {
      console.log('[b0b]③同一条地址 20 秒内已交过（防按B回来被再顶走），不重复接');
      return;
    }
    try { localStorage.setItem('b0b_sub', JSON.stringify({ s: subSig, t: Date.now() })); } catch (e) {}
    /* 标题在交出去这一刻现取（抓取时页面标题可能还没更新） */
    try { obj.title = pageTitle(); } catch (e) {}
    /*
     * 画面宽高也在这刻现取（2026-10-18 00:24 父亲实测：从 Emby 片切到 B站 片后比例不对）。
     *
     * 抓取那一跳的窗口里常常看不到带尺寸的 video 元素（拿到的是 0x0），
     * 于是 client 手上这份没有宽高、只能等内核报 —— 而内核那条今天没生效，比例就停在上一部的值。
     * 递交这一刻页面必然在播，再取一次最准，取到就盖上。
     */
    try {
      var _sz = pageVideoSize();
      if (_sz.w > 0 && _sz.h > 0) {
        obj.width = _sz.w; obj.height = _sz.h;
        /*
         * 客户端读的是 streams 里**那一条**的宽高（webStream.optInt("width")），
         * 所以那一条上也要补 —— 只补顶层等于没补（2026-10-18 00:44 日志实证：
         * "递交时补上画面宽高 3840x2160" 打出来了，银幕比例却没跟着变）。
         */
        if (obj.streams) {
          for (var si = 0; si < obj.streams.length; si++) {
            var st = obj.streams[si];
            if (st && !(st.width > 0 && st.height > 0)) { st.width = _sz.w; st.height = _sz.h; }
          }
        }
        console.log('[b0b] 递交时补上画面宽高 ' + _sz.w + 'x' + _sz.h + '（含 streams 那一条）');
      } else {
        console.log('[b0b] 递交时页面里没有带尺寸的 video 元素（比例只能等内核报）');
      }
    } catch (e) {}
    /*
     * 【2026-10-17 00:12 观测：交出去的到底是哪一份】
     * 父亲报"《良医》无论选哪集，放大后都是同一集"。这一行把
     * "这份待交内容是什么时候生成的、它记的页面是哪个"打出来，
     * 就能一眼看出是不是一直在交一份旧的。
     */
    try {
      console.log('[b0b]③提交：生成于 ' + Math.round((Date.now() - obj.t) / 1000) + ' 秒前' +
        '，它记的页面 ' + String(obj.frame).slice(-46) +
        '（当前页面 ' + String(location.href).slice(-46) + '）' +
        '，编号 ' + (obj.cid || 0) + '，季 ' + (obj.season || '?') + '，集 ' + (obj.ep || '?') +
        '，片名「' + String(obj.title || '').slice(0, 30) + '」' +
        '，首条 ' + String(obj.streams[0].video).slice(-34));
      console.log('[b0b]③这份是哪来的：' + objFrom + '；收它的窗口 ' + (obj.winId || '?') +
        '，现在铺满的窗口 ' + (poolWin || '?'));
      console.log('[b0b]③比对：这份记的集 ep' + (obj.ep || '?') + '／季 ' + (obj.season || '?') +
        '，当前页面集 ep' + (biliEp || '?') + '／季 ' + (biliSeason || '?') +
        ' → ' + ((String(obj.ep || '') === String(biliEp || '') &&
                  String(obj.season || '') === String(biliSeason || '')) ? '一致' : '不一致★'));
    } catch (e) {}
    if (lastSmallLog) console.log('[b0b]③小窗最后看到的：' + lastSmallLog);
    /*
     * 交接追踪（父亲 2026-10-18 02:10 要求"日志弄详细些，看看到底卡在哪"）：
     * 一行交代这次交出去的是什么、抓了多久、凭哪一条放行。
     */
    try {
      var tHost = String(pendingPlay.video || '').split('/')[2] || '?';
      console.log('[b0b] 交接追踪：交出去 主机=' + tHost +
        ' 长度=' + String(pendingPlay.video || '').length +
        ' 抓于=' + Math.round((Date.now() - qqAt) / 1000) + '秒前' +
        ' 放行依据=' + (fullState ? '本层按过全屏'
          : (fsShared && fsShared >= fsBest ? '本地存的全屏时刻' : '别层报的全屏时刻')));
    } catch (e) {}
    console.log('[b0b] 页面已在播放，把抓到的地址交给客户端');
    try { window.postMessage({ __b0b: 1, payload: obj }, '*'); } catch (e) {}
  }

  /* 播放信息（B站 的 playurl 返回，或页面全局 __playinfo__）→ 可播地址 */
  function applyPlayinfo(d) {
    try {
      if (!d) return;
      takeBiliSubtitle(d);      /* 同一份响应里就有字幕地址（父亲 2026-10-16） */
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
        cid: biliCid || 0,
        ep: biliEp,
        season: biliSeason,

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
      takeBiliSubtitle(j);
      takeBiliCid(j);
      var d = (j && (j.result || j.data || j)) || null;
      if (d && (d.dash || d.durl)) applyPlayinfo(d);
    } catch (e) {}
  }

  /*
   * 字幕单独扫一遍（2026-10-19 头显实测定案）。
   *
   * 之前只在 applyPlayinfo（能解析出 dash/durl 的那条支路）里取字幕，
   * 而剧集走的是 pgc/player/web/v2/playurl，响应结构里没有 dash 顶层，
   * 于是 takeBiliSubtitle 一次都没被调到 —— 日志里连 subtitle 字样都没有。
   * 现在跟播放地址那条支路解耦：只要正文里出现 subtitle 就解析。
   */
  function scanTextForBiliSubtitle(text) {
    try {
      if (!text || text.length > 4000000) return;
      if (text.indexOf('"subtitle"') < 0) return;
      if (text.indexOf('subtitle_url') < 0) return;
      var jj = JSON.parse(text);
      takeBiliSubtitle(jj);
      takeBiliCid(jj);
    } catch (e) {}
  }

  function scanTextForQqHls(text) {
    try {
      if (!text) return;
      var t = String(text);
      /*
       * 【2026-10-18 00:26 探针破案】腾讯的地址就夹在这条响应里，但**斜杠是转义的**：
       *   腾讯探针：https://vd6.l.qq.com/proxyhttp 类型 text 长度 7369 含m3u8=true 含引号url=false
       * JSON 里的地址写成 https:\/\/…（每个斜杠前面一个反斜杠），
       * 而原来的正则要求字面的 https:// —— 于是"含 m3u8"却一条都没匹配上，
       * 表现为"有时能抓到（别处的地址没转义）有时抓不到"。
       * 现在正则把可选的反斜杠吃进去，取到后再还原成真地址。
       */
      var all = t.match(/https?:\\?\/\\?\/[^"'\s]{6,600}?\.m3u8[^"'\s\\]{0,400}/g) || [];
      /*
       * 【2026-10-18 01:33 破案 —— 腾讯"接管了却没画面"的真凶】
       *
       * 上一版把地址交出去了、内核也起来了，但 mpv 打开的是
       *   https://playproxy.video.qq.com/captionV3/…f53102000.vtt.m3u8?…
       * 也就是**字幕轨的播放列表**（captionV3 / .vtt.m3u8）—— 视频列表里
       * 字幕的 m3u8 排在前面，我们取的是第一条，等于把字幕当片子交给内核，
       * mpv 自然 loading failed（reason 4），第一帧永远不来，银幕就一直不切。
       * 现在先把字幕类的挑掉，只认正片列表。
       */
      var m = all.filter(function (x) {
        return !/captionV3|\/caption|subtitle|\.vtt\.m3u8|\.ass\.m3u8|\.srt\.m3u8|dm\.video\.qq/i.test(x);
      });
      if (!m.length && all.length) {
        console.log('[b0b] 腾讯播放列表：只找到字幕类的 m3u8（' + all.length + ' 条），先不用它');
      }
      /*
       * 【2026-10-18 09:50 —— 父亲："没有抓到正片，一部电视剧只抓了 1 分多钟"】
       *
       * 内核确实播了，但只播 0 → 77 秒就结束（日志实证），说明交出去的是
       * **试看片段 / 广告片**，不是这一集正片。而网页自己还在放正片，
       * 也就是说同一条响应里的候选不止一条，我们**取第一条**（挑掉字幕后）取错了。
       * 现在给每条候选打分再挑，并把候选清单打进日志（父亲要的"看看到底卡在哪"）：
       *   · 带 vinfo / "vi": 这种是播放信息体，里面是正片 → 加分
       *   · 带 ad / adv / 广告 字样的 → 扣分（腾讯起播前插广告）
       *   · 码率高（br 上百万）→ 加分；分辨率大 → 加分
       */
      /*
       * 【2026-10-18 09:52 父亲口径】候选要**列出时长和清晰度**，并**挑最高清晰度**的。
       *
       * 做法：每条候选都从它周围的正文里抠出 清晰度(vw×vh) / 码率(br) / 时长(td 或 duration)，
       * 排成一张表打进日志（父亲要的"看看到底卡在哪"），
       * 排序规则：先看时长（试看/广告那种短片段直接沉底），再看清晰度，最后看码率。
       * 正文里还可能带整集的时长（"td":秒），一并打出来，方便对照"是不是正片"。
       */
      function candInfo(x, idx) {
        var i = t.indexOf(x);
        var ctx = t.slice(Math.max(0, i - 420), i + x.length);
        function num(re) { var mm = ctx.match(re); return mm ? parseInt(mm[1], 10) : 0; }
        var w = num(/"vw"\s*:\s*(\d{2,5})/) || num(/"width"\s*:\s*(\d{2,5})/);
        var h = num(/"vh"\s*:\s*(\d{2,5})/) || num(/"height"\s*:\s*(\d{2,5})/);
        var br = num(/"br"\s*:\s*(\d{4,9})/) || num(/"bandwidth"\s*:\s*(\d{4,9})/);
        var dur = num(/"td"\s*:\s*(\d{2,7})/) || num(/"duration"\s*:\s*(\d{1,7})/);
        var base = cleanUrl(x).split('/')[2] || '?';
        var bad = /\bad(s|v|x)?\b|广告|captionV3|\.vtt\.m3u8/i.test(ctx) ? 1 : 0;
        var prev = /preview|试看|trailer|预告|trailer/i.test(ctx) ? 1 : 0;
        var sc = (w * h) + br;
        /* 出自"播放信息体"（vinfo）的那条才是正片：广告/花絮不会夹在 vinfo 里 */
        if (/vinfo|"vl":|"vi":\[/.test(ctx)) sc += 1e11;
        if (bad) sc -= 1e12;
        if (prev) sc -= 1e9;
        if (dur && dur < 600) sc -= 1e8;
        return { x: x, i: idx, w: w, h: h, br: br, dur: dur, sc: sc, host: base, bad: bad, prev: prev };
      }
      if (m && m.length) {
        var ranked = m.map(candInfo);
        ranked.sort(function (a, b) { return (b.sc - a.sc) || (a.i - b.i); });
        try {
          var td = (t.match(/"td"\s*:\s*(\d{2,7})/) || [0, 0])[1];
          console.log('[b0b] 腾讯候选（' + m.length + ' 条，正文里的整集时长=' + td + ' 秒）：' +
            ranked.slice(0, 4).map(function (r) {
              return (r.w && r.h ? r.h + 'p' : '清晰度?') +
                (r.br ? '@' + Math.round(r.br / 1000) + 'k' : '') +
                (r.dur ? ' 时长' + r.dur + 's' : ' 时长?') +
                (r.bad ? ' [广告类]' : '') + (r.prev ? ' [试看类]' : '') +
                ' ' + r.host;
            }).join(' | '));
        } catch (e) {}
        var u = cleanUrl(ranked[0].x);
        if (u !== pendingQq) {
          shareQq(u);
          console.log('[b0b] 腾讯播放列表（正片非字幕，取最高清晰度 ' +
            (ranked[0].w && ranked[0].h ? ranked[0].h + 'p' : '未知') +
            '，长度 ' + u.length + '）');
        }
      } else if (t.indexOf('m3u8') >= 0) {
        /* 有 m3u8 字样却没拼出地址：把那一小段原样打出来，下一轮照着它修（父亲 2026-10-18） */
        var i = t.indexOf('m3u8');
        console.log('[b0b] 腾讯播放列表：有 m3u8 字样但拼不出地址，现场 → ' +
          t.slice(Math.max(0, i - 90), i + 40).replace(/\s+/g, ' '));
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
  /*
   * 【2026-10-17 11:33 父亲问"为什么普通视频秒接、电视剧/电影不行"的根治】
   *
   * 普通视频(BV)页面把完整播放信息写死在页面里（__playinfo__），每 0.4 秒就有现成的；
   * 电视剧/电影(bangumi)的地址只在**整页打开**时向 B站 要一次：SPA 换集/从推荐点进去
   * 都不整页刷新、B站 不重新要 —— 我们只能等分片，缓冲充足时几十秒都不来。
   *
   * 现在：发现换集（或放大而手里没有当前这一集的清单）时，
   * **我们自己带着页面里的身份去调一次 B站 自己的播放接口** ——
   * 与它自己调用的完全相同（同样的接口、同样的登录态），拿回来的清单
   * 和普通视频一样：画面+声音成对、带各档清晰度。电影/电视剧从此同级。
   */
  var selfFetchAt = 0;
  var selfFetchKey = '';
  function selfFetchBiliPlayurl(why) {
    try {
      /*
       * 【2026-10-17 18:36 父亲定：点全屏那一刻现识别最准确】
       * 原来这里卡了一道路径白名单（只认 `/bangumi/play/`），而电影页/普通视频页
       * 的路径各不相同 → 电影《消失的人》现场一次都没触发。
       * 撤掉白名单，改由"有没有拿到 B站 的集号 + 片子编号"认人 ——
       * 这两样只可能来自 B站 自己的播放接口，足以证明这是 B站 的片。
       */
      if (!fullState || !/bilibili\.com/.test(location.href)) return;
      if (!biliEp || !biliCid) return;
      var key = biliEp + '|' + biliCid;
      if (key === selfFetchKey) return;
      if (Date.now() - selfFetchAt < 5000) return;
      selfFetchAt = Date.now();
      selfFetchKey = key;
      var url = 'https://api.bilibili.com/pgc/player/web/v2/playurl?ep_id=' + biliEp +
        '&cid=' + biliCid + '&qn=120&fnver=0&fnval=16&fourk=1';
      console.log('[b0b]②自己调一次播放接口（' + why + '）：ep' + biliEp + ' 编号 ' + biliCid);
      fetch(url, { credentials: 'include' })
        .then(function (r) { return r.text(); })
        .then(function (t) {
          try {
            var j = JSON.parse(t);
            var d = j.result || j.data;
            if (d) {
              console.log('[b0b]②自调播放接口：拿到 ' + (d.dash ? 'dash' : 'durl') + ' 清单');
              applyPlayinfo(d);
            } else {
              console.log('[b0b]②自调播放接口：响应里没有地址（code=' + (j.code || '?') + '）');
            }
          } catch (e) { console.log('[b0b]②自调播放接口解析失败：' + e); }
        })
        .catch(function (e) { console.log('[b0b]②自调播放接口失败：' + e); });
    } catch (e) {}
  }

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
  /*
   * 【2026-10-17 父亲 02:01 定的口径：收集与接管分两个门槛】
   *
   * 病根（r614 实测）：这两个门槛原来是同一个 `liveSince`（九成）——
   * 于是**小窗期间下载的画面/声音片段一条都不记**，等父亲放大时这一集早就缓冲好了，
   * 池子里只剩放大后新下的一条画面片 → 声音配不上，接管后有画无声。
   *
   * 现在：
   *   · `poolSince` —— 窗口占到屏幕 **两成**（原来的 1/5 门槛）就开始记片段（小窗也是真在播）；
   *   · `liveSince` —— 只有占到 **九成** 才允许交出去（接管）。
   * 收集归收集，接管归接管，互不牵连。
   */
  var poolSince = 0;
  /*
   * 【2026-10-17 02:03 父亲问："你怎么知道那个小窗变成了大窗？"】
   *
   * 光看"有个窗口占屏多少"是不够的 —— 页面上可能有好几个在播的元素（正片小窗、
   * 推荐位、悬停预览）。收片段的那一个和放大接管的那一个**必须同一个**，
   * 所以给每个在播的 video 元素贴一个自己的编号，全程带着它：
   *   · 开池时记下是哪个窗口（poolWin）；
   *   · 铺满九成时要求还是那个窗口，换了窗口就当作换片，池子重来；
   *   · 交给客户端的那一份也带着编号，递交前再核一次。
   */
  var winSeq = 0;
  var poolWin = '';
  var poolGoneAt = 0;      /* 页面短暂没有在播窗口的起点（给 20 秒宽限） */

  /* 递交 3 秒去重（与本地存储里的 20 秒防重互补：前者防刷屏，后者防按B复活） */
  /*
   * 【2026-10-17 15:11-15:14 父亲定稿：接管门槛就是那两个全屏按钮】
   *
   * 父亲原话："接管门槛不需要九成了，接管门槛就是这两个按钮，任意按一个都可以。"
   * 本机 CDP 实测：按钮 aria 进出都叫"网页全屏"不变；进入网页全屏后
   * #bilibili-player 容器类多出 mode-webscreen，退出则消失 —— 进/退用它裁决。
   * /tv/ 那类元素全屏走 fullscreenchange 事件，进出都知道。
   * 占屏比、页面白名单全部退场。
   */
  /*
   * 【2026-10-17 15:50 父亲定稿：两把开关各自计数 —— 合在一起逻辑会乱】
   * 父亲原话："即使我点了网页全屏，它只是网页全屏，我再点全屏，它就真正全屏。它是两个不同的逻辑啊。
   * 但是现在我们不管是网页全屏还是真正全屏，都要接管。"
   * 所以：webClick = 网页全屏按钮的点击计数（奇=网页全屏开着）；fullClick = 进入全屏按钮的计数（奇=真全屏开着）。
   * fullState = 任一为奇 —— 网页全屏或真全屏，都接管。
   */
  /*
   * 【2026-10-17 15:52 我的判断（经父亲问责"要有判断力"后定案，A 方案）】
   *
   * 状态机只有一句：**按钮被点（或浏览器进入元素全屏）= 我要投屏**。
   * 退出只有一个出口：B 键三清。没有奇偶计数、没有两把开关、没有反向语义 ——
   * 误交的风险由 20 秒同址防重兜住（同一条地址不会反复交）。
   * 换代成本：删掉 webClick/fullClick 即可，状态只剩 fullState 单变量。
   */
  var fullState = false;
  var lastFullAt = 0;      /* 最近一次全屏信号，用来做接管间隔粗计时 */
  var lastSubmitSig = '';
  var lastSubmitAt = 0;

  var biliSubUrl = '';
  var biliSubLan = '';
  /*
   * 弹幕（父亲 2026-10-16）：B站 的滚动评论按"片子编号"分接口拉，
   * 编号就在同一份播放信息里（data.cid / result.cid）。字幕与弹幕是两个接口，别混。
   */
  var biliCid = 0;
  /* 观测（2026-10-17）：当前页面在播的是哪一季哪一集 —— 父亲要拿它对上"我选的那集" */
  var biliEp = '';
  var biliSeason = '';
  function takeBiliCid(obj) {
    try {
      /*
       * 【2026-10-17 00:12 修一个真 bug：这个函数被两种形状的参数调用】
       *
       * 一处传的是接口响应体（包装结构 {data:{cid}} 或 {result:{cid}}），
       * 另一处（从接口地址里取到编号那条路）传的是**裸的** {cid: n}。
       * 原来只认包装结构，于是裸的那种永远取不到 —— 头显日志实证：
       * 页面为五集分别请求了不同编号，而"拿到片子编号"只打了一次。
       * 现在两种形状都认。
       */
      var d = obj && (obj.data || obj.result);
      var c = (d && d.cid) || (obj && obj.cid);
      if (!c) return;
      if (String(c) === String(biliCid)) return;
      biliCid = c;
      console.log('[b0b]⑦ 拿到片子编号 ' + c + '（弹幕按它拉）');
    } catch (e) {}
  }
  function takeBiliSubtitle(obj) {
    try {
      var d = obj && (obj.data || obj.result);
      var subs = d && d.subtitle && d.subtitle.subtitles;
      if (!subs || !subs.length) return;
      var pick = null, i, s;
      for (i = 0; i < subs.length; i++) {
        s = subs[i];
        if (!s || !s.subtitle_url) continue;
        var lan = String(s.lan || '');
        /* 优先中文（含 AI 字幕），没有中文就拿第一条 */
        if (lan.indexOf('zh') === 0 || lan.indexOf('ai-zh') === 0) { pick = s; break; }
        if (!pick) pick = s;
      }
      if (!pick) return;
      var u = String(pick.subtitle_url);
      if (u.slice(0, 2) === '//') u = 'https:' + u;
      if (u === biliSubUrl) return;
      biliSubUrl = u;
      biliSubLan = String(pick.lan || '');
      console.log('[b0b]⑥ 拿到字幕地址（' + biliSubLan + '）：' + u.slice(-40));
    } catch (e) {}
  }
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
  var pendingQq = '', qqAt = 0, qqKey = '';
  /* 手上最好那条的时长（秒）：正文能数出时长后，只留最长的（父亲 2026-10-18 10:04） */
  var qqBestDur = 0;
  /*
   * 页面编号是不是"能认的"（2026-10-18 02:00）：
   * 播放器那一层的地址是 …/thumbplayer，编号就成了 thumbplayer ——
   * 它不是"哪一集"的编号，不能拿它去跟页面比，否则会把好地址误判成别的集。
   */
  function keyKnown(k) {
    return !!k && k.length >= 6 && !/thumbplayer|player|^https?:/i.test(k);
  }

  /*
   * 【2026-10-18 02:00 破案】父亲问"为什么检测不到我按了全屏"——检测到了
   * （日志里「①全屏按钮被点（网页全屏）」一条不少），问题在**检测到的那一层
   * 与攥着地址的那一层不是同一层**：腾讯一进网页全屏就把播放器那一层重建
   * （新文档、新脚本上下文），而地址是记在那一层内存里的 —— 重建即清空，
   * 于是"按了全屏"这个事实与"手里那份地址"永远碰不上面
   * （01:58 日志：01:58:05 还在判"没交出去"，01:58:11 按下全屏之后一条日志都没有）。
   *
   * 改法：抓到地址时**同时报给最外层**（最外层不随播放器重建），
   * 最外层再转发给其余各层；哪一层都记一份。按全屏这一下恰恰是最外层记的 ——
   * 两者终于在同一层会合。
   */
  /*
   * 【2026-10-18 02:08 补：按全屏会重建整棵文档树】
   *
   * 上一版把地址报给最外层，日志确实收到了（「收到别的层报来的腾讯地址（长度 502）」×4），
   * 但父亲一按网页全屏（02:06:02）之后**又是一条日志都没有** ——
   * 因为腾讯进网页全屏时把文档整个换了一遍，我们所有层的脚本上下文都是新的，
   * 旧的广播对象已经不存在，新上下文手里什么都没有（探针日志证明新上下文在跑）。
   * 所以地址不能只放在内存里，要落到**同源共享的本地存储**：
   * 文档重建后，新上下文照样能把它取回来接着用。
   */
  function saveQqLocal(u, at, key) {
    try { localStorage.setItem('b0b:qq', JSON.stringify({ u: u, at: at, key: key })); } catch (e) {}
  }
  function loadQqLocal() {
    try {
      var raw = localStorage.getItem('b0b:qq');
      if (!raw) return null;
      var o = JSON.parse(raw);
      if (o && o.u && (Date.now() - (o.at || 0)) < 30 * 60 * 1000) return o;
    } catch (e) {}
    return null;
  }

  /*
   * 【2026-10-18 02:27 破案：黑屏的真凶是地址尾部多了一个反斜杠】
   *
   * 从接口响应里抠地址时，正则的尾段把 JSON 的转义符号也吃进来了：
   * 交出去的地址尾巴是 `…f321003.ts.m3u8?ver=4\` —— 多一个反斜杠。
   * 用这条地址去要数据，腾讯的 CDN 直接回 **403 Forbidden**（签名对不上），
   * 内核拿不到列表 → 一直等第一帧 → 父亲看到"接管很快但黑屏"。
   * 在本机把尾部反斜杠去掉重试同一地址：**200 OK，正常返回播放列表**（实测）。
   * 所以这是我们的地址清洗问题，跟登录票无关。
   */
  function cleanUrl(u) {
    var v = String(u || '');
    v = v.replace(/\\u002F/gi, '/').replace(/\\\//g, '/');
    return v.replace(/[\\"'\s]+$/, '');
  }

  function shareQq(raw) {
    var u = cleanUrl(raw);
    pendingQq = u; qqAt = Date.now();
    qqKey = pageVideoKey(location.href);
    var m = { __b0bQq: 1, u: u, at: qqAt, key: qqKey };
    saveQqLocal(u, qqAt, qqKey);
    try { window.top.postMessage(m, '*'); } catch (e) {}
    try {
      if (window.self === window.top) {
        for (var i = 0; i < window.frames.length; i++) {
          try { window.frames[i].postMessage(m, '*'); } catch (e2) {}
        }
      }
    } catch (e) {}
  }
  /*
   * 地址是属于哪一页的（2026-10-18 01:44 加）。
   * 腾讯的播放列表**只在起播那一刻出现一次**，之后再也不请求 ——
   * 父亲 01:41 的现场就是这样：他起播后过十来分钟才按网页全屏，
   * 三分钟时效早过了，手里那份地址被当成过期丢掉，于是永远交不出去。
   * 现在把时效放宽（见 flushPendingPlay），并用"页面里的片子编号"作安全绳：
   * 只要还在同一集上，旧地址照样能用；换了集就自动作废，不会交出上一集的地址。
   */
  /*
   * 【2026-10-18 01:52 补：跨层广播】
   * 父亲 01:49 的现场：地址 01:49:26 抓到了、也组装好了（「用抓到的 HLS 播放列表交给内核」），
   * 但同一秒被判「没交出去：你还没按全屏按钮」——他 01:49:31 才按的网页全屏；
   * 按下之后**再也没有任何提交动作**。说明按下全屏那一层，和手里攥着地址的那一层
   * 不是同一层，而"按过全屏"这个事实没能传到那一层（同源才共享的本地存储不够用，
   * 腾讯的播放器层与页面层未必同源）。
   * 现在按全屏时向所有层广播一次，各层都记下这个时刻；拿地址的那一层据此放行。
   */
  var peerFullAt = 0;
  function announceFullscreen(t) {
    peerFullAt = t;
    try { localStorage.setItem('b0b:fullAt', String(t)); } catch (e) {}
    try { window.top.postMessage({ __b0bFull: 1, t: t }, '*'); } catch (e) {}
  }
  try {
    window.addEventListener('message', function (ev) {
      try {
        var d = ev.data;
        if (!d || typeof d !== 'object') return;
        if (d.__b0bFull) {
          peerFullAt = Math.max(peerFullAt, d.t || Date.now());
          if (window.self === window.top) {
            /* 最外层收到后替下层转发一遍（下层之间不互通） */
            for (var i = 0; i < window.frames.length; i++) {
              try { window.frames[i].postMessage({ __b0bFull: 1, t: d.t }, '*'); } catch (e2) {}
            }
          }
        } else if (d.__b0bQq && d.u) {
          /* 别层抓到的地址（2026-10-18 02:00）：收下，最外层再转给其余各层 */
          pendingQq = cleanUrl(d.u); qqAt = d.at || Date.now(); qqKey = d.key || '';
          saveQqLocal(pendingQq, qqAt, qqKey);
          if (window.self === window.top && d.__b0bRelay !== 1) {
            for (var j = 0; j < window.frames.length; j++) {
              try {
                window.frames[j].postMessage(
                  { __b0bQq: 1, u: d.u, at: d.at, key: d.key, __b0bRelay: 1 }, '*');
              } catch (e4) {}
            }
          }
          console.log('[b0b] 收到别的层报来的腾讯地址（长度 ' + String(d.u).length + '）');
        } else if (d.__b0bAsk) {
          if (peerFullAt > 0 && ev.source) {
            try { ev.source.postMessage({ __b0bFull: 1, t: peerFullAt }, '*'); } catch (e3) {}
          }
        }
      } catch (e) {}
    });
  } catch (e) {}

  function pageVideoKey(u) {
    try {
      var t = String(u || '').split('?')[0];
      var m = /\/([A-Za-z0-9_-]{8,})\.html?$/i.exec(t);
      if (m) return m[1];
      return t.slice(-40);
    } catch (e) { return ''; }
  }

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
        if (!u) continue;
        /*
         * 【2026-10-17 05:14 父亲报"接管太慢/有时接管不过去"提】
         * r627 实测：B站 换集(SPA)不重新请求播放接口；全屏后画面分片要等
         * 播放器下一段真正的分片请求才进池 —— 父亲等了 63 秒。
         * 而资源时间线(performance resource)里躺着页面**早就下过**的全部分片地址。
         * 全屏一开盘(每 0.4 �扫一轮)就把它们捞进收集 → 秒配对秒接管。
         */
        /*
         * 【2026-10-17 18:24 父亲报"这部电影接管慢 + 接管后没声音"定案】
         *
         * 原来这里是 `if (liveSince && …)`：只有画面铺满九成之后才允许从资源时间线里捞分片。
         * 而 电影《消失的人》现场（18:18:37）**声音那一片是页面开机预载时就下完的**，
         * 那时还没开始播（`pageIsPlaying()` 假），XHR/fetch 钩子把它的记录丢了；
         * 等全屏时池子里只剩画面那一片 → 干等 24 秒（日志：`等了 24 秒还没有声音那条`）
         * → 交出去只有画面（`④ 起播第 1/1 条：音轨=false`）→ 有画无声。
         *
         * 资源时间线里躺着这一页**下过的全部分片**（含那条声音），跟窗口大小毫无关系，
         * 所以这里把九成门槛撤掉：只要页面在播就捞。挑组仍认"当前编号"、
         * 递交仍认"同一集 + 全屏按钮"，不会串片。
         */
        if (u.indexOf('/upgcxcode/') > 0) {
          try { noteMedia(u); } catch (e) {}
        }
        if (isQqHls(u) && u !== pendingQq) {
          shareQq(u);
          console.log('[b0b] 腾讯播放列表（从资源时间线找到）');
        }
      }
    } catch (e) {}
  }

  function noteMedia(u) {
    if (!u || typeof u !== 'string') return;
    if (u.slice(0, 5) === 'blob:') return;
    if (!looksMedia(u)) return;
    /*
     * 【2026-10-17 13:40 三道关卡定稿之三：收集只是后勤，不再绑窗口大小】
     * 页面确实在播就记它下载的分片；分片只是接口拿不到时的兜底，不该左右接管。
     * 串片风险由两道关卡挡住：挑组认"当前编号"、递交核"同一集"。
     */
    if (!pageIsPlaying()) return;
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
  /* 观测用（2026-10-17）：池子里出现过哪些"片子编号"，以及页面地址变过没有 */
  var poolKeysLog = {};
  var lastHrefLogged = '';
  var lastSmallLog = '';   /* 小窗/全屏在播那一行，内容变了才重打 */

  /*
   * 【2026-10-17 修「无论选哪一集，放大后都是同一集」】
   *
   * 头显实证（r612）：交出去的那一份是 213 秒前在**另一集页面**上生成的旧快照，
   * 而当前这一集的候选池是空的 —— 因为
   *   ① `tryPairFromHits` 见"手里已有一份待交"就直接返回，新的进不来；
   *   ② 片段池只在"够大到九成"之后才收，小窗试看期间没收也不清，留着上一集的碎片。
   * 现在做一条总规矩：**在播的集号一变，手里那份和池子一起作废**，按新集重新攒。
   */
  /* 单一门槛后只需要"清掉重来"这一个动作（父亲 2026-10-17 03:54） */
  function resetCollection(why) {
    try {
      pendingPlay = null;
      if (candPool.length) console.log('[b0b]②清掉已收的 ' + candPool.length + ' 条（' + why + '）');
      candPool = [];
      candSeen = {};
      poolKeysLog = {};
      lastPairedVideo = '';
      mediaKeys = {};
      mediaHits = [];
    } catch (e) {}
  }
  function invalidateForNewEpisode(why) { resetCollection(why); }

  /* 两份"在播身份"是不是同一集（编号优先，其次集号+季号） */
  /*
   * 同一个页面（比域名 + 路径，忽略查询串与锚点）。
   * 用途：两边都没有「集号/编号」这套身份时，用它代替身份做判断。
   */
  function samePageUrl(x, y) {
    try {
      if (!x || !y) return false;
      var ux = new URL(x), uy = new URL(y);
      return ux.host === uy.host && ux.pathname === uy.pathname;
    } catch (e) { return false; }
  }

  function sameEpisode(a, b) {
    if (!a || !b) return false;
    if (a.cid && b.cid && String(a.cid) === String(b.cid)) return true;
    var ae = String(a.ep || ''), be = String(b.ep || '');
    if (ae !== '' && ae === be && String(a.season || '') === String(b.season || '')) return true;
    /*
     * 【2026-10-17 23:42 r652 实测定案：腾讯每次都被这一关丢掉】
     *
     * 现场（r652，腾讯播放页，页面确实在播 1920x1080）：
     *   [b0b] 腾讯：用抓到的 HLS 播放列表交给内核
     *   [b0b] 交接现场：页面 video 元素 2 个（播 1920x1080 400.9s / 停 0x0 0s）
     *   [b0b]③手里那份不是当前这一集（它 ?，现在 ep?）→ 丢掉，重新攒
     *   [b0b]扫描出错(flushPendingPlay)：TypeError: can't access property "streams", obj is null
     * 每 400 毫秒一轮，地址抓到就被丢一次 —— 腾讯永远交不出去。
     *
     * 根因：腾讯的地址没有「集号/编号」这套身份（那是 B站 的），两边都是空；
     * 原来的写法把「两边都空」当成「不是同一集」。两边都没有身份时改用「是不是同一个页面」判 ——
     * 同一页就是同一次播放（换集/换片必然换页）。
     */
    if (ae === '' && be === '' && !a.cid && !b.cid) return samePageUrl(a.frame, b.href);
    return false;
  }
  var candTried = 0;
  /*
   * 只看最近这段时间内出现的片段（父亲 2026-10-16 定的口径：往前 5 秒、往后 10 秒）。
   * 不能按历史累计 —— 上一部片子的片段还留在池子里，
   * 累计数一多就会把已经换掉的那部当成"在播的那部"，直接播错片。
   * 前 5 秒：刚点下去就冒出来的那几条要算；后 10 秒：起播慢的片子给它缓冲余地。
   */
  var CAND_WINDOW_MS = 15000;

  function isBiliMedia(u) {
    /*
     * 【2026-10-17 04:56 r626 实测定案 —— 这就是"有时行有时不行"的真凶】
     *
     * B站 的分片不只从 bilivideo.com 下，还会从它自家的 P2P 边缘域下
     * （r626 现场：*.edge.mountaintoys.cn:4483）。原来只认 bilivideo.com/
     * akamaized/hdslb，这些货全被扔在门口：日志里"收到片段"认出了编号，
     * 但候进池的 addCandidate 里 isBiliMedia 把它拒了 → 池子 0 条 →
     * 候选池分组"选 无" → 永远交不出去（父亲 04:55 电视剧没接管，全程池子为空）。
     * 改法：认**路径特征**而不是主机 —— /upgcxcode/<段>/<段>/<编号>/ 是
     * B站 分片统一的路径，主机是什么无所谓；老域名清单仅作后备。
     */
    if (/\/upgcxcode\/\d+\/\d+\/\d{5,}\//.test(u) && /\.(m4s|mp4)(\?|$)/i.test(u)) return true;
    if (!/bilivideo\.(com|cn)|akamaized\.net|hdslb\.com|mountaintoys\.cn|mcdn/i.test(u)) return false;
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
    if (m) {
      /*
       * 【2026-10-17 19:03 父亲点醒："你都是用正则，对吧？正则比较死板"】
       *
       * 上一版是"去掉某种形状的尾巴"（`_da2`、`_nb3`…）—— 枚举形状确实死板：
       * B站 换一种尾巴的花样就得再改一次代码。
       *
       * 改成认**开头那串纯数字**（B站 的内容编号）当身份，后面挂什么装饰一律不看；
       * 名字不是数字开头（BV 号那种）时保持原样，免得把分段（`xxx_p2`）并成一组。
       */
      var d = m[1].match(/^(\d{5,})/);
      if (d) return d[1];
      return m[1];
    }
    var m2 = String(u).match(/upgcxcode\/[^\/]+\/[^\/]+\/(\d{5,})\//);
    return m2 ? m2[1] : 'x';
  }

  function addCandidate(u) {
    if (!isBiliMedia(u)) return;
    var k = String(u).split('?')[0];
    if (candSeen[k]) return;
    candSeen[k] = 1;
    var key = candKeyOf(u);
    candPool.push({ u: u, t: Date.now(), key: key });
    /*
     * 【2026-10-17 00:12 观测：池子里出现过哪些"片子编号"】
     * 只在出现新的编号时打一行 —— 用来回答"我翻到第 5 集了，池子里有没有第 5 集的编号"。
     */
    if (!poolKeysLog[key]) {
      poolKeysLog[key] = 1;
      console.log('[b0b]②候选池出现新编号 ' + key + '，当前页面 ' + String(location.href).slice(-42));
    }
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
    /*
     * 【2026-10-17 03:37】池子从此不再清空，于是里面会同时留着好几集的东西。
     * 挑的时候：**当前正在播的那一集**（页面报的编号）优先，其次整片，再次最多的那组。
     * 这样"留着"就没有串片风险。
     */
    var curKey = '';
    try { if (biliCid && g[String(biliCid)]) curKey = String(biliCid); } catch (e) {}
    var useKey = curKey || wholeKey || bestKey;
    /*
     * 【2026-10-17 00:10 只做观测、不改行为】
     * 父亲报"无论选哪一季哪一集，放大后都是固定的某一集"。
     * 要判的是：候选池里到底有几个片子编号、最后挑中的是哪一个、
     * 它与"当前页面地址"对不对得上。不看到这三样，任何改法都是猜。
     */
    try {
      var brief = order.map(function (k) { return k + '(' + g[k].length + ')'; }).join(' ');
      console.log('[b0b]③候选池分组：' + brief +
        ' → 选 ' + (useKey || '无') + (wholeKey ? '（整片）' : '') +
        '，页面 ' + String(location.href).slice(-42));
    } catch (e) {}
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
      if (pendingPlay) {
        /*
         * 【2026-10-17 修】原来这里是"有就不生成"，于是换集后旧的那份一直占位 ——
         * 改成一集一判：手里那份还是**当前这一集**就留着（同时省掉重复劳动），
         * 属于别的集就作废，改用这次新攒的。
         */
        var nowEp = { ep: biliEp, season: biliSeason, cid: biliCid, winId: poolWin, href: location.href };
        if (sameEpisode(pendingPlay, nowEp)) {
          /*
           * 【2026-10-17 04:19】手里那份**只有画面**、而现在成对（画面+声音）了 →
           * 不能像原来那样直接返回挡掉，必须换新的，否则永远是"有画无声"。
           */
          var haveAudioAlready = !!(pendingPlay.streams && pendingPlay.streams[0] && pendingPlay.streams[0].audio);
          var freshPick = pickCandidates(1);
          var nowHasAudio = !!(freshPick.length && freshPick[0].audio);
          if (!haveAudioAlready && nowHasAudio) {
            console.log('[b0b]③手里那份只有画面，现在声音那条到了 → 换成交配对的那份');
            pendingPlay = null;
          } else {
            console.log('[b0b]③配对跳过：手里那份就是当前这一集（' +
              (pendingPlay.ep ? 'ep' + pendingPlay.ep : '编号 ' + pendingPlay.cid) + '）');
            return;
          }
        }
        console.log('[b0b]③手里那份属于别的集（它记的 ' +
          (pendingPlay.ep ? 'ep' + pendingPlay.ep : '编号 ' + pendingPlay.cid) +
          '，现在 ep' + (biliEp || '?') + '）→ 存起来，换新的继续攒');
        pendingPlay = null;
      }
      var cands = pickCandidates(6);
      if (!cands.length) return;
      /*
       * 【2026-10-17 04:19 父亲报的'没有接管'根因（r622 日志）】
       *
       * 池子里刚有**画面那一条**就交出去了（日志 `1)单条`），声音那条晚一秒到，
       * 结果交出去的还是"有画无声"。所以：B站 这种音视频分轨的站点，
       * **必须等到画面+声音成对才交**；实在等不到（整片 mp4 / HLS 自带声音 / 等太久）
       * 才退回"单条"。
       */
      var pairSig = String(cands[0].video) + '|' + String(cands[0].audio || '');
      var isWhole = !!findWholeFile([cands[0].video]) || /\.m3u8/i.test(String(cands[0].video));
      if (!cands[0].audio && !isWhole) {
        var waitedMs = 0;
        if (liveSince) waitedMs = Date.now() - liveSince;
        else if (fullState) waitedMs = Date.now() - (lastFullAt || (Date.now() - 3000));
        if (waitedMs < 8000) {
          console.log('[b0b]③先不交：现在只有画面那条，等声音那条（已等 ' +
            Math.round(waitedMs / 1000) + ' 秒，最多 8 秒）');
          return;
        }
        console.log('[b0b]③等了 ' + Math.round(waitedMs / 1000) + ' 秒还没有声音那条 → 先按单条交（有画无声）');
      }
      if (pairSig === lastPairedVideo) {
        console.log('[b0b]③配对跳过：候选与上次完全相同');
        return;
      }
      lastPairedVideo = pairSig;
      pendingPlay = {
        subtitle: biliSubUrl,
        cid: biliCid,
        ep: biliEp,
        season: biliSeason,
        title: '', kind: 'play', t: Date.now(), frame: location.href, winId: poolWin,
        durSec: 0, quality: 0, site: '', width: pageVideoSize().w, height: pageVideoSize().h,
        streams: cands
      };
      console.log('[b0b]③ 交给客户端 ' + cands.length + ' 条候选：' +
        cands.map(function (c, i) {
          return (i + 1) + ')' + (c.audio ? '画面+声音' : '单条') + ' ' + String(c.video).slice(-26);
        }).join(' | ') + '，页面 ' + String(location.href).slice(-42));
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
      /*
       * 【2026-10-17 19:16 现场定案（父亲报《珍馐记》还是慢+没声音，r646 已在跑）】
       *
       * 这里原来自己又写了一份"认编号"的正则，尾巴照样留在编号里：
       * 画面 `564290981-1-100241.m4s` → cid 564290981
       * 声音 `564290981_da2-1-30280.m4s` → cid 564290981_da2
       * 于是音频被记到另一个 cid 名下，配对结果永远是"只有画面那条"，
       * 干等到 26 秒才交（日志：`③等了 26 秒还没有声音那条 → 先按单条交（有画无声）`）。
       *
       * **认编号只留一处**：身份交给 candKeyOf（它才是"开头纯数字就是身份"的唯一实现），
       * 这里只负责取"分片类型编号"。以后改规矩只改一个地方。
       */
      var code = String(u).match(/-1-(\d{3,6})\.(m4s|mp4)/i);
      if (!code) continue;
      var cid = candKeyOf(u);
      if (!cid || cid === 'x') continue;
      var videoCode = parseInt(code[1], 10);
      if (!vids[cid] && !auds[cid]) cidOrder.push(cid);
      var isAudio = (videoCode >= 30200 && videoCode < 30400);
      if (isAudio) {
        if (!auds[cid] || videoCode > auds[cid].code) auds[cid] = { u: u, code: videoCode };
      } else {
        if (!vids[cid] || vidScore(videoCode) > vidScore(vids[cid].code)) {
          vids[cid] = { u: u, code: videoCode };
        }
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
    if (u.indexOf('/x/player/') < 0 && u.indexOf('playurl') < 0 &&
        u.indexOf('/pgc/player/') < 0 && u.indexOf('/x/v2/dm/') < 0) return;
    console.log('[b0b] 播放接口请求 ' + u.slice(0, 130));
    /*
     * 片子编号优先从**接口地址**里取（父亲 2026-10-16 实测：从响应体里取不到，
     * 本文件的日志证明这条 URL 路一定被走到 —— 地址里就带 cid=…）。
     */
    try {
      var m = /[?&](?:cid|oid)=(\d{5,})/.exec(u);
      if (m) takeBiliCid({ cid: parseInt(m[1], 10) });
      /*
       * 观测（2026-10-17）：把"哪一季哪一集"也记下来。
       * B站 番剧的接口地址里带 ep_id 与 season_id，直接取即可 —— 不用猜结构。
       */
      var me = /[?&]ep_id=(\d+)/.exec(u);
      var ms = /[?&]season_id=(\d+)/.exec(u);
      if (me && me[1] !== biliEp) {
        biliEp = me[1];
        console.log('[b0b]②页面在播的集号变了：ep' + biliEp + '（季 ' + (biliSeason || '?') + '）');
        resetCollection('页面在播的集号变了');
        selfFetchBiliPlayurl('换集');
      }
      if (ms && ms[1] !== biliSeason) {
        biliSeason = ms[1];
        console.log('[b0b]②页面在播的季号变了：season ' + biliSeason);
        resetCollection('页面在播的季号变了');
        selfFetchBiliPlayurl('换集');
      }
    } catch (e) {}
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
        /*
         * 通用：**响应正文本身就是播放列表**（以 #EXTM3U 开头）—— 2026-10-18 01:22 加。
         *
         * 现场（父亲 01:21 实测，腾讯仍不接管）：真正的播放流是
         *   https://ltsyd.qq.com/B_BDw…（一长串不透明令牌，**没有 .m3u8 后缀**）
         * 播放列表请求是 https://pl.video.qq.com/plr/tr?te=…&ce=…（同样没有后缀）。
         * 我们一直靠"地址里有没有 .m3u8"来认，腾讯这版页面根本不出现 .m3u8 字样，
         * 所以候选池恒为空（日志一路「候选池分组： → 选 无」、池子 0 条）。
         *
         * 认列表最可靠的办法是看**正文开头**：HLS 列表一定以 #EXTM3U 起头，
         * 与地址长什么样无关。命中就把这个请求地址当作播放列表交给客户端。
         */
        if (u.indexOf('qq.com') >= 0 || u.indexOf('ltsyd') >= 0) {
          this.addEventListener('load', function () {
            try {
              var rtA = this.responseType;
              var body = '';
              if (rtA === '' || rtA === 'text') body = String(this.responseText || '');
              else if (this.response && this.response.byteLength < 4 * 1024 * 1024) {
                body = new TextDecoder('utf-8').decode(this.response);
              }
              var isCaptionBody = /captionV3|TYPE=SUBTITLES|\.vtt/i.test(body) ||
                /captionV3|\.vtt\.m3u8/i.test(String(this.responseURL || u));
              if (body.slice(0, 7) === '#EXTM3U' && !isCaptionBody) {
                var self_u = String(this.responseURL || u);
                /*
                 * 【2026-10-18 10:04 父亲："还是时长很短"】
                 *
                 * 今天真正抓到地址的那条路是**这里**（看响应正文开头是不是 #EXTM3U），
                 * 不是接口响应里抠 URL 的那条 —— 所以候选打分/时长清晰度那张表根本没轮到，
                 * 交出去的可能是**试看片段**（前面实测内核只播了 77 秒就结束）。
                 * 正文就在眼前，直接**数时长**：把列表里每段 #EXTINF 的秒数加起来，
                 * 只认最长的那一条（短的明显是试看/广告，先留着当兜底）。
                 */
                var durs = body.match(/#EXTINF:([0-9.]+)/g) || [];
                var total = 0;
                for (var di = 0; di < durs.length; di++) {
                  total += parseFloat(durs[di].replace('#EXTINF:', '')) || 0;
                }
                console.log('[b0b] 腾讯列表候选：时长≈' + Math.round(total) + ' 秒（' + durs.length +
                  ' 段，' + (/ENDLIST/.test(body) ? '整片' : '未完结') + '）' +
                  (total > 0 && total < 600 ? ' —— 像试看/广告片段' : ''));
                if (total > qqBestDur) {
                  qqBestDur = total;
                } else if (total > 0 && qqBestDur > total) {
                  console.log('[b0b] 这条比手上的短（' + Math.round(total) + ' < ' +
                    Math.round(qqBestDur) + ' 秒），不替换');
                  return;
                }
                if (self_u !== pendingQq) {
                  shareQq(self_u);
                  console.log('[b0b] 腾讯播放列表（响应正文就是列表，地址无 .m3u8 后缀，时长≈' +
                    Math.round(total) + ' 秒）');
                }
              }
            } catch (e) {}
          });
        }
        /* 腾讯：地址在这些接口的响应里，读回来扫一遍 */
        if (u.indexOf('qq.com') >= 0) {
          this.addEventListener('load', function () {
            try {
              var rt = this.responseType;
              var seen = '';
              if (rt === '' || rt === 'text') seen = String(this.responseText || '');
              else if (rt === 'json') seen = JSON.stringify(this.response || '');
              else if (this.response && this.response.byteLength < 4 * 1024 * 1024) {
                seen = new TextDecoder('utf-8').decode(this.response);
              }
              scanTextForQqHls(seen);
              /*
               * 腾讯探针（2026-10-18 00:10 加，父亲实测"腾讯还是没接播"）。
               *
               * 现场：几轮里有时能抓到 .m3u8（日志"腾讯播放列表（从接口响应里找到）"），
               * 有时一条都抓不到（同一台机器、同一个页面，只有一条 vd6.l.qq.com/proxyhttp 请求）。
               * 到底是「响应里根本没有 m3u8（腾讯把地址藏在加密串里）」还是
               * 「响应是二进制、我们当文本解失败」，只有把这条现场打出来才能分清。
               */
              if (/\/\/(vd|vd6|vv)\.l\.qq\.com|proxyhttp|getvinfo|getprog/i.test(String(this.responseURL || '')) ||
                  /proxyhttp|getvinfo|getprog/i.test(u)) {
                console.log('[b0b] 腾讯探针：' + String(this.responseURL || u).slice(0, 80) +
                  ' 类型 ' + (rt || 'text') + ' 长度 ' + seen.length +
                  ' 含m3u8=' + /m3u8/.test(seen) +
                  ' 含引号url=' + /"url"|'url'/.test(seen) +
                  ' 开头 ' + seen.slice(0, 70));
              }
            } catch (e) { console.log('[b0b] 腾讯探针：读响应失败 ' + e); }
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
                scanTextForBiliSubtitle(this.responseText);
              } else if (rt === 'json') {
                var j = this.response;
                if (j) takeBiliSubtitle(j);
                if (j) takeBiliCid(j);
                if (j) applyPlayinfo(j.result || j.data || j);
              } else if (this.response) {
                var t2 = new TextDecoder('utf-8').decode(this.response);
                parsePlayurl(t2);
                scanTextForBiliSubtitle(t2);
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

  /*
   * 「登录票到底有没有」的现场取证（父亲 2026-10-18 02:22）。
   *
   * 腾讯那条路服务器回 403（缺票），而扩展那边报"登录票：无"。
   * 两种可能分不清：① 这个浏览器里确实没登录腾讯；② 我们取票的通道不通
   * （扩展的内容脚本里 likely 拿不到 cookies 接口）。
   * 页面世界能看到非 HttpOnly 的 cookie，就先用它打个底：
   * 有名字有长度说明票在浏览器里，那就是②；空的说明就是①。
   */
  function reportCookies() {
    try {
      if (window.__b0bCookieLogged) return;
      var c = '';
      try { c = String(document.cookie || ''); } catch (e) { c = ''; }
      var names = c ? c.split(';').map(function (x) { return x.split('=')[0].trim(); }) : [];
      window.__b0bCookieLogged = 1;
      console.log('[b0b] 页面 cookie 取证：主机=' + location.hostname +
        ' 有票=' + (c ? '有' : '无') + ' 长度=' + c.length +
        ' 名字=' + (names.length ? names.slice(0, 8).join(',') : '（空）'));
    } catch (e) {}
  }

  function scan() {
    try { scanPerfForHls(); } catch (e) {}
    try { reportCookies(); } catch (e) {}
    /* 每拍问一次"谁按过全屏"（2026-10-18 01:52）：跨层、跨源都靠它 */
    try { if (window.self !== window.top) window.top.postMessage({ __b0bAsk: 1 }, '*'); } catch (e) {}
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
    /*
     * 【2026-10-17 04:35 教训】这三步原来一个 try 都没有：
     * 递交那一处读了一个没声明的变量，报错把整个 scan 打断 —— 之后窗口量不到、
     * 日志也停了、永远交不出去（表现就是父亲报的"没有接管"）。
     * 现在每一步各自兜住错误，出错只记一行、继续跑。
     */
    try { flushMedia(); } catch (e) { console.log('[b0b]扫描出错(flushMedia)：' + e); }
    try { readGlobalPlayinfo(); } catch (e) { console.log('[b0b]扫描出错(readGlobalPlayinfo)：' + e); }
    try { flushPendingPlay(); } catch (e) { console.log('[b0b]扫描出错(flushPendingPlay)：' + e); }
    var best = null, bestArea = 0;
    /*
     * 只在"播放页"上认画面（父亲 2026-10-16：还没播、只是在首页点个「历史播放」，
     * 银幕比例也跟着变）—— 非播放页上的自动预览视频一律不算。
     */
    var onPlayPage = /\/(video|bangumi\/play|cheese\/play|medialist|movie)\//.test(location.href);
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
      if (!v.__b0bWin) { v.__b0bWin = 'w' + (++winSeq); }
      /*
       * 太小的预览窗不算（父亲 2026-10-16：浏览页面时点个「历史播放」按钮，比例也在变）。
       * 正片在播放页上占视口宽度六成左右（实测 668/1100），缩略预览远小于此。
       */
      /*
       * 预览窗不算（父亲 2026-10-16 定的口径：真在播的至少占视口面积两成）。
       * 原来按宽度卡四成，遇到"又宽又扁"的海报预览挡不住，改按面积。
       * 实测参照：正片播放 1100x619 占满；海报预览约 300x170，只有几个点。
       */
      /*
       * 门槛：只认「铺满屏幕」的那个窗口（父亲 2026-10-16 改定，原来是 1/5）。
       *
       * 原因（父亲实测）：剧集有好多季好多集，点一集时页面上的小窗也会开始播，
       * 1/5 的门槛把这种"试看"也当成正式播放抢着接管，结果他还没选完季/集就被顶走了。
       * 现在的语义是：**把画面放到全屏才算"我要看"，这时才接管**；小窗是试看，不碰。
       */
      /*
       * 门槛回到**两成**（父亲 2026-10-17 02:01 明确："小窗还是用我们原来的那个 1/5"）。
       *
       * 九成那道门挪去管"接管"了 —— 这里只负责挑出"页面上真在播的那个窗口"，
       * 小窗试看也算，因为它的画面与声音此刻正在下载，正是我们要收的东西。
       */
      /*
       * 【2026-10-17 13:40 父亲定口径：量"播放器区域"占屏，不量"内容"占屏】
       *
       * 父亲原话："我觉得黑边也是播放口的内容，我觉得应该计算播放口占屏幕的比例，
       * 而不是计算播放口内容占屏幕比例。"
       * 实现（通用，不猜站名）：从画面元素向上找"包含它的最小盒子" —— 允许容器
       * 比内容大一成以内（全屏时黑边就是这块），一旦明显放大（翻倍级别）就是页面版式，
       * 停止并入。网页全屏时这个盒子就是整屏 → 任何片型都是 100%，不再出现 92/96 浮动。
       */
      var pbw = r.width, pbh = r.height;
      try {
        var pel = v.parentElement;
        var capW = r.width * 1.10, capH = r.height * 1.10;
        for (var k = 0; pel && k < 6; k++, pel = pel.parentElement) {
          var pr = pel.getBoundingClientRect();
          if (pr.width <= capW && pr.height <= capH) { pbw = pr.width; pbh = pr.height; }
          else break;
        }
      } catch (e) {}
      var area = pbw * pbh;
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
        url: v.currentSrc || v.src || '',
        winId: v.__b0bWin
      };
    }
    /*
     * 开闸/关闸（父亲 2026-10-16 定的顺序）：
     * 先确认页面上真有一个大窗口在放片，确认之后才开始收它下载的片段；
     * 页面一停就清空已收的（换集时正好靠这一下重来，不会串片）。
     */
    var vpArea = (window.innerWidth || 1) * (window.innerHeight || 1);
    /*
     * 【2026-10-17 03:54 父亲定的模型：只有一个门槛】
     *
     * 父亲原话："放大到接管之间会有一两秒延迟没关系。关键是又要判断小窗口，又要判断大窗口，
     * 逻辑复杂了，你的代码容易出错。我记得我们只管小窗口的时候非常稳定。
     * 现在从非常稳定的只管小窗口，改成大窗口，我觉得还是会稳定。"
     *
     * 所以：
     *   小窗一概不管（不收、也不交）；
     *   窗口占到九成 → 开始收它正在下载的画面与声音；
     *   收到画面+声音两条 → 交（客户端接过去播）；
     *   退回小窗 → 把刚才收的清掉，下次放大重新收。
     * 只有这一个门槛，没有存档、没有钥匙、没有"留着"。
     */
    var bigNow = false;
    if (best) {
      var pctNow = best.area * 100 / vpArea;
      bigNow = (pctNow >= 90);
      if (bigNow) {
        if (!liveSince) {
          /*
           * 【2026-10-17 04:36 审读发现】这里原来把 pendingPlay 和池子一起清掉 ——
           * 但播放接口给的成对地址（画面+声音）在放大之前就已经到手，
           * 清掉等于扔掉"标准答案"，只能靠放大后的碎片重新凑，
           * 而声音那条常常不再重新下载 → 有画无声。所以开闸时**不清**；
           * 清的时机仍是父亲定的两处：退回小窗、换集。
           */
          liveSince = Date.now();
          poolSince = liveSince;
          poolWin = best.winId;
          selfFetchBiliPlayurl('放大');
          console.log('[b0b]① 窗口 ' + best.winId + ' 占到九成（' + Math.round(best.w) + 'x' +
            Math.round(best.h) + '，' + Math.round(pctNow) + '%）→ 开始收它的画面与声音');
        }
      }
    }
    if (bigNow) {
      poolGoneAt = 0;
    } else if (liveSince) {
      /* 退回小窗：给 3 秒宽限（切全屏那一瞬可能量不到九成），超时才清 */
      if (!poolGoneAt) poolGoneAt = Date.now();
      if (Date.now() - poolGoneAt > 3000) {
        liveSince = 0;
        poolSince = 0;
        poolWin = '';
        poolGoneAt = 0;
        console.log('[b0b]① 缩回小窗：接管交给全屏按钮信号，已收的分片留着（串片有两道关挡着）');
      }
    }
    /*
     * 【2026-10-17 00:20 观测：小窗里在试播什么，也要打出来】
     *
     * 父亲的要求：先看到他"在小窗口试播的是哪一季哪一集"，再看到"放大之后接管的
     * 是不是同一个"。原来只有"够大到九成"才打一行，小窗阶段完全是黑盒。
     * 这里每轮扫描都算一遍：在播的那个窗口多大、占屏多少、当前页面在播哪一集。
     * 只在内容变了时打（尺寸或集号变了），免得每 400ms 刷屏。
     */
    try {
      var iw0 = window.innerWidth || 1, ih0 = window.innerHeight || 1;
      var anyBest = best;
      if (anyBest) {
        anyBest.pct = Math.round(anyBest.area * 100 / (iw0 * ih0));
        anyBest.tag = anyBest.pct >= 90 ? '全屏' : '小窗';
      } else {
        var smalls = document.querySelectorAll('video');
        for (var si = 0; si < smalls.length; si++) {
          var sv = smalls[si];
          if (sv.paused) continue;
          if (!sv.videoWidth) continue;
          var sr = sv.getBoundingClientRect();
          if (sr.width < 40 || sr.height < 30) continue;
          var ov = 0;
          try { ov = parseFloat(getComputedStyle(sv).opacity || '1'); } catch (e) {}
          if (ov < 0.05) continue;
          anyBest = {
            w: sr.width, h: sr.height,
            area: Math.round(sr.width * sr.height),
            pct: Math.round(sr.width * sr.height * 100 / (iw0 * ih0)),
            tag: '小窗',
          };
          break;
        }
      }
      if (anyBest) {
        var line = anyBest.tag + '在播 ' + Math.round(anyBest.w) + 'x' + Math.round(anyBest.h) +
          '，占屏 ' + anyBest.pct + '%（门槛90%），集 ep' + (biliEp || '?') +
          '，季 ' + (biliSeason || '?') + '，编号 ' + (biliCid || 0) +
          '，页面 ' + String(location.href).slice(-42);
        if (line !== lastSmallLog) {
          lastSmallLog = line;
          console.log('[b0b]①' + line);
        }
      } else if (lastSmallLog) {
        lastSmallLog = '';
        console.log('[b0b]①页面上没有在播的窗口了（小窗与全屏都没有）');
      }
    } catch (e) {}
    /*
     * 观测（2026-10-17）：页面地址变过没有。
     * 父亲翻季/翻集时，如果这个地址一直不动，就说明"换集没换页"，
     * 我们按页面地址判断的两处（播放页判据、交给客户端时记的页面）都会跟着钝掉。
     */
    if (location.href !== lastHrefLogged) {
      lastHrefLogged = location.href;
      /*
       * 【2026-10-17 15:46 父亲定稿：每新开一个页面，全屏按钮计数归零】
       * 页面重建（按B后重载）自身会从零；SPA 换页（不重载）在这里归零 ——
       * 首次点全屏=铺满，第二次=收回。
       */
      lastFullAt = 0;
      fullState = false;
      /*
       * 共享标记只在**最外层**换页时清（2026-10-18 01:05）：
       * 播放器那一层自己换地址不算换页，清了就等于把父亲刚按的全屏白按掉。
       */
      try { if (window.self === window.top) localStorage.removeItem('b0b:fullAt'); } catch (e) {}
      console.log('[b0b]①页面地址变为 ' + String(location.href).slice(-70) + '（全屏计数归零）');
    }
    /*
     * 【2026-10-17 05:14 父亲报"按 B 白屏"根修】
     * 每个 iframe 里都有一个探子实例：message.bilibili.com 那个小框架的地址
     * 曾把"要回去的那一页"顶掉 → 按 B 回来重建的是一张空白页。
     * 跨框架探子一律不上报；播放器都在顶层文档里（B站/腾讯/爱奇艺 均是）。
     */
    if (window.self !== window.top) return;
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
            kind: 'play', t: Date.now(), frame: location.href, winId: poolWin,
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
  try {
    window.addEventListener('error', function (ev) {
      try { console.log('[b0b]脚本出错：' + (ev && ev.message ? ev.message : '?') +
        ' @ ' + (ev && ev.filename ? ev.filename : '?') + ':' + (ev && ev.lineno ? ev.lineno : '?')); } catch (e) {}
    });
  } catch (e) {}
  try {
    window.addEventListener('click', function (ev) {
      if (window.self !== window.top) return;
      /*
       * 【2026-10-18 00:43 父亲实测：腾讯上"全屏"按钮点不动，他点的是"网页全屏"，
       * 而我们的识别只认 B站 那几个类名 → fullState 一直是假 → 地址抓到了却永远不交】
       * （日志实证：00:45:42「腾讯播放列表（从接口响应里找到，长度 506）」紧跟
       *   「没交出去：你还没按全屏按钮（网页全屏或全屏）」）
       *
       * 各站播放器的按钮类名都不一样（腾讯是 txp_*，爱奇艺又是另一套），
       * 与其一个个补，不如按"这个名字里带 full/全屏"来认 —— 认错最多是提前交一次，
       * 而抓不到就是永远交不出去。命中时把类名打进日志，下一轮可以据此收窄。
       */
      var el = ev.target && ev.target.closest ? ev.target.closest(
        '.bpx-player-ctrl-web,.bpx-player-ctrl-web-enter,.bpx-player-ctrl-full,' +
        '.squirtle-video-pagefullscreen,.squirtle-video-fullscreen,' +
        '[aria-label*="全屏"],[title*="全屏"],[class*="fullscreen"],[class*="full-screen"],' +
        '[class*="webfull"],[class*="pagefull"],[class*="full"]') : null;
      if (!el) return;
      fullState = true;
      lastFullAt = Date.now();
      /* 同站点各层共享（2026-10-18 01:05）：见 flushPendingPlay 里的说明 */
      announceFullscreen(Date.now());
      /*
       * 【2026-10-17 18:35 父亲问"为什么点全屏时不重新拿一次地址，要去前面捞"】
       *
       * 对，这里应该现问一次。原来那条"自己调播放接口"只在**窗口占到九成**时才触发
       * （scan 里 liveSince 那一段），而网页全屏时探子量到的窗口是 72%~75%，
       * 永远够不到九成 → 从没触发过，只能退回去从下载记录里捞。
       *
       * 现在改成：**按下全屏按钮这一下就直接问一次接口** —— 接口给的是官方那一对
       * （画面 + 声音，带清晰度和时长），比捞旧记录干净。捞只当它失败时的兜底。
       * 认人仍靠 ep_id + 编号：拿不到这两样（腾讯/爱奇艺页面）直接返回，不会误请求。
       */
      selfFetchBiliPlayurl('全屏按钮');
      var label = (el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title'))) || '';
      if (!label) {
        label = String(el.className || el.id || '').slice(0, 40);
        console.log('[b0b]①全屏按钮被点（类名可能是 ' + label + '）→ 记下这个类名，下一轮可据此收窄');
      }
      console.log('[b0b]①全屏按钮被点（' + (label || String(el.className).slice(0, 36)) + '）→ 进入投屏态（退出只认 B 键）');
    }, true);
    document.addEventListener('fullscreenchange', function () {
      if (window.self !== window.top) return;
      lastFullAt = Date.now();
      fullState = !!(document.fullscreenElement || document.webkitFullscreenElement);
      if (fullState) announceFullscreen(Date.now());
      else { try { localStorage.removeItem('b0b:fullAt'); } catch (e) {} }
      console.log('[b0b]①浏览器全屏事件 → ' + (fullState ? '进入全屏（可接管）' : '退出全屏'));
    });
  } catch (e) {}
  setInterval(scan, 400);
  scan();
})();
