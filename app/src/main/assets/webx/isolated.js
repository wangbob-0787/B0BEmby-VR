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

  /* 一分钟刷一次就够（cookie 不会秒变），拿不到就先用上一次的 */
  function cookies() {
    if (Date.now() - cookieAt < 60000) return cookieCache;
    cookieAt = Date.now();
    try {
      browser.cookies.getAll({ domain: 'bilibili.com' }).then(function (list) {
        var s = (list || []).map(function (c) { return c.name + '=' + c.value; }).join('; ');
        if (s) cookieCache = s;
      }).catch(function (e) {
        console.log('[b0b] 取 cookie 失败：' + e);
      });
    } catch (e) {}
    return cookieCache;
  }

  window.addEventListener('message', function (ev) {
    if (ev.source !== window) return;
    var d = ev.data;
    if (!d || d.__b0b !== 1 || !d.payload) return;

    var payload = d.payload;
    try {
      var c = cookies();
      if (c) payload.cookie = c;
    } catch (e) {}

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
  }, false);

  console.log('[b0b] 内容脚本就绪 ' + location.href);
})();
