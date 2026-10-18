/*
 * 内容脚本（隔离世界，父亲 2026-10-15）。
 *
 * 两件事：
 *   ① 把页面世界（main.js）用 window.postMessage 发出来的数据交给客户端 ——
 *      优先一次性消息（runtime.sendNativeMessage）；后台页那条路当兜底
 *      （r548 实测：port 建起就断；r549 实测：GeckoView 没加载 MV2 的后台页）。
 *   ② 附上本站 cookie（会员片要用 SESSDATA 才给高清/会员内容）——
 *      MAIN world 里没有 browser API，所以只能在这里取。
 *
 * 客户端那侧的名字是 b0b（见 WebRuntime.ensureBridge 里的 nativeApp）。
 */
(function () {
  var cookieCache = '';
  var cookieAt = 0;
  var cookiePending = null;

  /*
   * 取哪个域的 cookie（2026-10-18 02:15 修）。
   *
   * 原来写死 `domain: 'bilibili.com'` —— B站 那条路一直好使，所以没人发现；
   * 腾讯的地址交给内核后**服务器回 403**（日志实证：
   * `ffmpeg https: HTTP error 403 Forbidden` → `Failed to open`
   * → 银幕一直等第一帧 = 父亲看到的"接管了但黑屏"）。
   * 原因是腾讯的媒体地址要带登录票（Cookie）才给数据，而我们从没给腾讯取过 cookie。
   * 现在按当前站点挑域，并且同时按"当前页面地址"取一遍（有些是主机专属 cookie）。
   */
  function cookieDomain() {
    var h = String(location.hostname || '');
    if (/qq\.com$/.test(h)) return 'qq.com';
    if (/iqiyi\.com$/.test(h)) return 'iqiyi.com';
    if (/bilibili\.com$/.test(h)) return 'bilibili.com';
    return h.replace(/^www\./, '');
  }

  /* 两个来源合并去重：按域取 + 按当前页面取 */
  function gatherCookies() {
    var dom = cookieDomain();
    /* 内容脚本里没有 cookies 接口 → 向后台页要（2026-10-18 02:24） */
    if (!(typeof browser !== 'undefined' && browser.cookies && browser.cookies.getAll)) {
      return new Promise(function (res) {
        try {
          browser.runtime.sendMessage({ __b0bCookies: 1, domain: dom }).then(function (r) {
            var c = (r && r.cookie) || '';
            console.log('[b0b] 向后台页取票（' + dom + '）：' + (c ? '有' : '无'));
            /*
             * 把票里**有哪些名字**打出来（只打名字不打值，2026-10-18 晚）。
             * 起因：客户端自己发请求时服务器认不出身份（只给 180 秒试看），
             * 需要确认"登录那几个名字（uin/vusession 之类）到底在不在我们取到的票里"。
             */
            try {
              var names = c.split(';').map(function (x) { return x.split('=')[0].trim(); }).filter(function (x) { return x; });
              var key = ['uin', 'p_uin', 'luin', 'skey', 'p_skey', 'vusession', 'vqq_vusession', 'vqq_openid',
                         'wxuin', 'access_token', 'login_uin', 'qq_uin', 'uid', 'ptui_loginuin', 'vuid'];
              var found = key.filter(function (k) { return names.indexOf(k) >= 0; });
              console.log('[b0b] 票里共 ' + names.length + ' 条，登录相关的有：' + (found.length ? found.join(',') : '一条都没有'));
            } catch (e) {}
            res(c);
          }).catch(function (e) {
            console.log('[b0b] 向后台页取票失败：' + e);
            res('');
          });
        } catch (e) {
          console.log('[b0b] 向后台页取票抛错：' + e);
          res('');
        }
      });
    }
    var jobs = [];
    try { jobs.push(browser.cookies.getAll({ domain: dom })); } catch (e) {}
    try { jobs.push(browser.cookies.getAll({ url: location.href })); } catch (e) {}
    return Promise.all(jobs).then(function (lists) {
      var seen = {}, out = [];
      for (var i = 0; i < lists.length; i++) {
        var l = lists[i] || [];
        for (var j = 0; j < l.length; j++) {
          var c = l[j];
          if (!c || !c.name || seen[c.name]) continue;
          seen[c.name] = 1;
          out.push(c.name + '=' + c.value);
        }
      }
      return out.join('; ');
    });
  }

  /* 一分钟刷一次就够（cookie 不会秒变），拿不到就先用上一次的 */
  function cookies() {
    if (Date.now() - cookieAt < 60000) return cookieCache;
    cookieAt = Date.now();
    try {
      gatherCookies().then(function (s) {
        if (s) cookieCache = s;
      }).catch(function (e) {
        console.log('[b0b] 取 cookie 失败：' + e);
      });
    } catch (e) {}
    return cookieCache;
  }

  /*
   * 播放消息专用：等 cookie 真取回来再发（2026-10-16 r560 实测「登录票 无」）。
   *
   * 原来所有消息都不等 —— 取 cookie 是异步的，第一条消息几乎必然在它返回之前发出，
   * 所以客户端拿到的永远是空票：免费内容不受影响（只认来源与浏览器标识），
   * 但会员内容换不到高清地址。播放消息本来就不在乎多等几十毫秒，这里等它。
   */
  function ensureCookie() {
    if (cookieCache) return Promise.resolve(cookieCache);
    if (!cookiePending) {
      try {
        cookiePending = gatherCookies().then(function (s) {
          if (s) cookieCache = s;
          cookieAt = Date.now();
          console.log('[b0b] 登录票已取（' + cookieDomain() + '）：' + (s ? '有' : '无'));
          return cookieCache;
        }).catch(function (e) {
          console.log('[b0b] 取 cookie 失败：' + e);
          return '';
        });
      } catch (e) {
        return Promise.resolve('');
      }
    }
    return cookiePending;
  }

  function send(payload) {
    try {
      var p = browser.runtime.sendNativeMessage('b0b', payload);
      if (p && p.catch) {
        p.catch(function (e) { console.log('[b0b] 一次性消息失败：' + e); });
      }
    } catch (e) {
      console.log('[b0b] 一次性消息抛错：' + e);
    }
    try {
      browser.runtime.sendMessage({ __b0b: 1, payload: payload });
    } catch (e) {}
  }

  window.addEventListener('message', function (ev) {
    if (ev.source !== window) return;
    var d = ev.data;
    if (!d || d.__b0b !== 1 || !d.payload) return;

    var payload = d.payload;
    if (payload && payload.kind === 'play') {
      /* 可播地址这条必须带票（会员片要），等它取回来再发 */
      ensureCookie().then(function (c) {
        if (c) payload.cookie = c;
        send(payload);
      });
      return;
    }
    try {
      var c = cookies();
      if (c) payload.cookie = c;
    } catch (e) {}
    send(payload);
  }, false);

  console.log('[b0b] 内容脚本就绪 ' + location.href);

  /*
   * 浏览器层面看到的媒体请求（2026-10-18）。
   *
   * 腾讯的分片是网页的播放器交给后台线程去取的 —— 页面里的清单看不见，
   * 只有扩展后台页的 webRequest 能看到。这一条把它送进页面世界去攒段。
   */
  try {
    browser.runtime.onMessage.addListener(function (m) {
      try {
        if (!m || m.__b0bNet !== 1) return;
        window.postMessage({ __b0bNet: 1, i: m.i, u: m.u }, '*');
      } catch (e) {
      }
    });
  } catch (e) {
    console.log('[b0b] 挂网络监听失败：' + e);
  }

  /*
   * ======== 腾讯整集地址（2026-10-18 晚；只碰腾讯，B站/爱奇艺那条链一行不动）========
   *
   * 背景与结论（都实测过，细节见 docs/ledger/b0bemby-vr-web-capture-ledger-2026-10-16.md）：
   *   · 腾讯网页把片子切成十几秒一段、一段一签，只能顺播、拖不动；老接口 getinfo 对会员片
   *     永远只给 180 秒试看（连登录着的浏览器去问也一样）。所以走"我们自己算签名 + 要整集文件"。
   *   · 签名算法（读它网页播放器 `Xh.$xx` 得到）：
   *       cKey = md5( 盐 + 片号 + 服务器时间(秒) + "*#06#" + 平台号 )，平台号 10201
   *     盐按当天星期取（版本 "7.1"…"7.7"，周日记 7）。
   *   · 为什么放在**内容脚本**里发这条请求：内容脚本的 fetch 走扩展权限、不受页面跨域限制，
   *     而且 `kind:'play'` 这条消息会自动附上登录票（见上面 send/ensureCookie）。
   *     页面世界直发实测拿不到响应（`签名路：没问到服务器时间`），所以不放那儿。
   */
  var B0B_SALT = { '1': '06fc1464', '2': '4244ce1b', '3': '77de31c5', '4': 'e0149fa2',
                   '5': '60394ced', '6': '2da639f0', '7': 'c2f0cf9f' };
  function b0bMd5(str) {
    function rl(n, c) { return (n << c) | (n >>> (32 - c)); }
    function au(x, y) { var l = (x & 0xFFFF) + (y & 0xFFFF); return (((x >> 16) + (y >> 16) + (l >> 16)) << 16) | (l & 0xFFFF); }
    function cmn(q, a, b, x, s2, t) { return au(rl(au(au(a, q), au(x, t)), s2), b); }
    function ff(a, b, c, d, x, s2, t) { return cmn((b & c) | (~b & d), a, b, x, s2, t); }
    function gg(a, b, c, d, x, s2, t) { return cmn((b & d) | (c & ~d), a, b, x, s2, t); }
    function hh(a, b, c, d, x, s2, t) { return cmn(b ^ c ^ d, a, b, x, s2, t); }
    function ii(a, b, c, d, x, s2, t) { return cmn(c ^ (b | ~d), a, b, x, s2, t); }
    var i, nblk, blks = [], str2 = unescape(encodeURIComponent(str)), n = str2.length;
    for (i = 0; i < n; i++) blks[i >> 2] |= str2.charCodeAt(i) << ((i % 4) * 8);
    blks[n >> 2] |= 0x80 << ((n % 4) * 8);
    nblk = ((n + 8) >> 6) + 1;
    while (blks.length < nblk * 16) blks.push(0);
    blks[nblk * 16 - 2] = n * 8;
    var a = 1732584193, b = -271733879, c = -1732584194, d = 271733878;
    for (i = 0; i < blks.length; i += 16) {
      var oa = a, ob = b, oc = c, od = d;
      a = ff(a, b, c, d, blks[i], 7, -680876936); d = ff(d, a, b, c, blks[i + 1], 12, -389564586);
      c = ff(c, d, a, b, blks[i + 2], 17, 606105819); b = ff(b, c, d, a, blks[i + 3], 22, -1044525330);
      a = ff(a, b, c, d, blks[i + 4], 7, -176418897); d = ff(d, a, b, c, blks[i + 5], 12, 1200080426);
      c = ff(c, d, a, b, blks[i + 6], 17, -1473231341); b = ff(b, c, d, a, blks[i + 7], 22, -45705983);
      a = ff(a, b, c, d, blks[i + 8], 7, 1770035416); d = ff(d, a, b, c, blks[i + 9], 12, -1958414417);
      c = ff(c, d, a, b, blks[i + 10], 17, -42063); b = ff(b, c, d, a, blks[i + 11], 22, -1990404162);
      a = ff(a, b, c, d, blks[i + 12], 7, 1804603682); d = ff(d, a, b, c, blks[i + 13], 12, -40341101);
      c = ff(c, d, a, b, blks[i + 14], 17, -1502002290); b = ff(b, c, d, a, blks[i + 15], 22, 1236535329);
      a = gg(a, b, c, d, blks[i + 1], 5, -165796510); d = gg(d, a, b, c, blks[i + 6], 9, -1069501632);
      c = gg(c, d, a, b, blks[i + 11], 14, 643717713); b = gg(b, c, d, a, blks[i], 20, -373897302);
      a = gg(a, b, c, d, blks[i + 5], 5, -701558691); d = gg(d, a, b, c, blks[i + 10], 9, 38016083);
      c = gg(c, d, a, b, blks[i + 15], 14, -660478335); b = gg(b, c, d, a, blks[i + 4], 20, -405537848);
      a = gg(a, b, c, d, blks[i + 9], 5, 568446438); d = gg(d, a, b, c, blks[i + 14], 9, -1019803690);
      c = gg(c, d, a, b, blks[i + 3], 14, -187363961); b = gg(b, c, d, a, blks[i + 8], 20, 1163531501);
      a = gg(a, b, c, d, blks[i + 13], 5, -1444681467); d = gg(d, a, b, c, blks[i + 2], 9, -51403784);
      c = gg(c, d, a, b, blks[i + 7], 14, 1735328473); b = gg(b, c, d, a, blks[i + 12], 20, -1926607734);
      a = hh(a, b, c, d, blks[i + 5], 4, -378558); d = hh(d, a, b, c, blks[i + 8], 11, -2022574463);
      c = hh(c, d, a, b, blks[i + 11], 16, 1839030562); b = hh(b, c, d, a, blks[i + 14], 23, -35309556);
      a = hh(a, b, c, d, blks[i + 1], 4, -1530992060); d = hh(d, a, b, c, blks[i + 4], 11, 1272893353);
      c = hh(c, d, a, b, blks[i + 7], 16, -155497632); b = hh(b, c, d, a, blks[i + 10], 23, -1094730640);
      a = hh(a, b, c, d, blks[i + 13], 4, 681279174); d = hh(d, a, b, c, blks[i], 11, -358537222);
      c = hh(c, d, a, b, blks[i + 3], 16, -722521979); b = hh(b, c, d, a, blks[i + 6], 23, 76029189);
      a = hh(a, b, c, d, blks[i + 9], 4, -640364487); d = hh(d, a, b, c, blks[i + 12], 11, -421815835);
      c = hh(c, d, a, b, blks[i + 15], 16, 530742520); b = hh(b, c, d, a, blks[i + 2], 23, -995338651);
      a = ii(a, b, c, d, blks[i], 6, -198630844); d = ii(d, a, b, c, blks[i + 7], 10, 1126891415);
      c = ii(c, d, a, b, blks[i + 14], 15, -1416354905); b = ii(b, c, d, a, blks[i + 5], 21, -57434055);
      a = ii(a, b, c, d, blks[i + 12], 6, 1700485571); d = ii(d, a, b, c, blks[i + 3], 10, -1894986606);
      c = ii(c, d, a, b, blks[i + 10], 15, -1051523); b = ii(b, c, d, a, blks[i + 1], 21, -2054922799);
      a = ii(a, b, c, d, blks[i + 8], 6, 1873313359); d = ii(d, a, b, c, blks[i + 15], 10, -30611744);
      c = ii(c, d, a, b, blks[i + 6], 15, -1560198380); b = ii(b, c, d, a, blks[i + 13], 21, 1309151649);
      a = ii(a, b, c, d, blks[i + 4], 6, -145523070); d = ii(d, a, b, c, blks[i + 11], 10, -1120210379);
      c = ii(c, d, a, b, blks[i + 2], 15, 718787259); b = ii(b, c, d, a, blks[i + 9], 21, -343485551);
      a = au(a, oa); b = au(b, ob); c = au(c, oc); d = au(d, od);
    }
    function hex(x) {
      var out = '', j;
      for (j = 0; j < 4; j++) out += ('0' + ((x >> (j * 8)) & 0xFF).toString(16)).slice(-2);
      return out;
    }
    return hex(a) + hex(b) + hex(c) + hex(d);
  }
  function b0bEncryptVer() {
    var d = new Date().getDay();
    return '7.' + (d === 0 ? 7 : d);
  }
  function b0bText(u, ms) {
    return new Promise(function (res) {
      var done = false;
      var t = setTimeout(function () { if (!done) { done = true; res(''); } }, ms || 8000);
      try {
        fetch(u, { credentials: 'include' }).then(function (r) { return r.text(); }).then(function (x) {
          if (!done) { done = true; clearTimeout(t); res(x); }
        }).catch(function () { if (!done) { done = true; clearTimeout(t); res(''); } });
      } catch (e) { if (!done) { done = true; clearTimeout(t); res(''); } }
    });
  }
  /* 请页面世界代发这条请求并等它回（页面身份才带得全，2026-10-18） */
  function b0bPageFetch(u, ms) {
    return new Promise(function (res) {
      var id = 'w' + Date.now() + Math.floor(Math.random() * 1000);
      var done = false;
      function h(ev) {
        var d = ev.data;
        if (!d || d.__b0bSignFetchBack !== 1 || d.id !== id || done) return;
        done = true;
        clearTimeout(t);
        window.removeEventListener('message', h, false);
        if (d.err) console.log('[b0b] 腾讯整集：页面代发失败（' + d.err + '）');
        res(d.text || '');
      }
      var t = setTimeout(function () {
        if (done) return;
        done = true;
        window.removeEventListener('message', h, false);
        res('');
      }, ms || 9000);
      window.addEventListener('message', h, false);
      try { window.postMessage({ __b0bSignFetch: 1, id: id, url: u }, '*'); } catch (e) { done = true; res(''); }
    });
  }

  /* 同一集只要过一次；换集（片号变）自然重来 */
  var b0bWholeVid = '';
  var b0bWholeBusy = false;
  function b0bTencentWhole() {
    try {
      if (b0bWholeBusy) return;
      /* 只认腾讯的播放页，别的站一律不管（B站/爱奇艺那条链不碰） */
      if (!/^v\.qq\.com$/i.test(String(location.hostname || ''))) return;
      var href = location.href;
      if (!/\/x\/(cover|page)\//.test(href)) return;
      var vid = (href.match(/\/([A-Za-z0-9]{10,12})\.html/) || [])[1];
      if (!vid || vid === b0bWholeVid) return;
      /* 页面得确实在放（点过播放、画面在动），不然不去打扰 */
      var v = document.querySelector('video');
      if (!v || v.paused || !(v.currentTime > 0.5)) return;
      b0bWholeVid = vid;
      b0bWholeBusy = true;
      var ver = b0bEncryptVer();
      b0bText('https://vv6.video.qq.com/checktime?otype=xml&rand=' + Math.random(), 8000).then(function (xml) {
        var m = /<t>(\d+)<\/t>/.exec(String(xml || ''));
        var tm = m ? m[1].substring(0, 10) : String(Math.floor(Date.now() / 1000));
        if (!m) console.log('[b0b] 腾讯整集：checktime 没回，改用本机时间兜底');
        var salt = B0B_SALT[ver.substring(2)] || B0B_SALT['7'];
        var ck = b0bMd5(salt + vid + tm + '*#06#' + '10201');
        var u = 'https://vv6.video.qq.com/getvinfo?otype=json&vid=' + vid +
          '&platform=10201&sdtfrom=v1010&defn=fhd&vt=3&charge=0' +
          '&cKey=' + ck + '&encryptVer=' + ver + '&tm=' + tm;
        return b0bPageFetch(u, 9000).then(function (t0) {
          if (t0) {
            console.log('[b0b] 腾讯整集：页面代发拿到响应');
            return t0;
          }
          console.log('[b0b] 腾讯整集：页面代发没回，改用内容脚本自己发');
          return b0bText(u, 12000);
        }).then(function (t) {
          b0bWholeBusy = false;
          if (!t) { console.log('[b0b] 腾讯整集：接口没回响应'); return; }
          var j = {};
          try { j = JSON.parse(String(t).replace(/^QZOutputJson=/, '').replace(/;\s*$/, '')); } catch (e) {
            console.log('[b0b] 腾讯整集：响应解析失败'); return;
          }
          var vi = (j.vl && j.vl.vi && j.vl.vi[0]) || null;
          if (!vi) { console.log('[b0b] 腾讯整集：没有片源信息（em=' + j.em + '）'); return; }
          var td = Number(vi.td || 0), prev = Number(j.preview);
          console.log('[b0b] 腾讯整集结果：允许看 ' + prev + ' 秒 / 整集 ' + td + ' 秒，档位 ' +
            vi.vh + 'p，片名 ' + vi.ti + '，登录 ' + j.login);
          if (!(prev >= td * 0.9 || prev >= 600)) {
            console.log('[b0b] 腾讯整集：身份没带全（只给试看），不接管');
            b0bWholeVid = '';
            return;
          }
          var url = vi.ul.ui[0].url + vi.fn + (vi.fvkey ? ('?vkey=' + vi.fvkey) : '');
          send({
            title: vi.ti || '', kind: 'play', frame: href, winId: 0,
            durSec: Math.round(td), quality: vi.vh || 0, whole: 1,
            streams: [{
              id: 0, height: vi.vh || 0, width: vi.vw || 0, bw: vi.br || 0, vcodec: '',
              video: url, audio: '',
              headers: { 'Referer': 'https://v.qq.com/', 'User-Agent': navigator.userAgent }
            }]
          });
          console.log('[b0b] 腾讯整集：整集地址已交给客户端（' + vi.vh + 'p，' +
            Math.round(td / 60) + ' 分钟）');
        });
      }).catch(function (e) { b0bWholeBusy = false; console.log('[b0b] 腾讯整集失败：' + e); });
    } catch (e) { b0bWholeBusy = false; }
  }
  setInterval(b0bTencentWhole, 3000);
})();
