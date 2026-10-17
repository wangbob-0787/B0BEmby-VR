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
})();
