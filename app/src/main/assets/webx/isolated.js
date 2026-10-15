/*
 * 内容脚本（隔离世界，父亲 2026-10-15）。
 *
 * 把页面世界（main.js）用 window.postMessage 发出来的数据交给客户端。两条路一起发：
 *   ① runtime.sendNativeMessage —— 一次性消息，不依赖长连接（r548 实测 port 建起就断：
 *      「Attempt to postMessage on disconnected port」，所以先试这条）
 *   ② runtime.sendMessage 给扩展后台页，由后台页转发（background.js 那条，
 *      r549 实测后台页在 GeckoView 里没被加载，留着当兜底）
 *
 * 客户端那侧的名字是 b0b（见 WebRuntime.ensureBridge 里的 nativeApp）。
 */
(function () {
  window.addEventListener('message', function (ev) {
    if (ev.source !== window) return;
    var d = ev.data;
    if (!d || d.__b0b !== 1 || !d.payload) return;

    try {
      var p = browser.runtime.sendNativeMessage('b0b', d.payload);
      if (p && p.catch) {
        p.catch(function (e) { console.log('[b0b] 一次性消息失败：' + e); });
      }
    } catch (e) {
      console.log('[b0b] 一次性消息抛错：' + e);
    }

    try {
      browser.runtime.sendMessage({ __b0b: 1, payload: d.payload });
    } catch (e) {}
  }, false);

  console.log('[b0b] 内容脚本就绪 ' + location.href);
})();
