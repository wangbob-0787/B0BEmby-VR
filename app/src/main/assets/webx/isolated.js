/*
 * 内容脚本（隔离世界，父亲 2026-10-15）。
 *
 * 只做一件事：把页面世界（main.js）用 window.postMessage 发出来的数据转给扩展后台页
 * （background.js），由后台页连客户端。内容脚本自己**不**连客户端 —— 实测那样连上就断
 * （Attempt to postMessage on disconnected port）。
 *
 * 客户端那侧的名字是 b0b（见 WebRuntime.ensureBridge 里的 nativeApp）。
 */
(function () {
  window.addEventListener('message', function (ev) {
    if (ev.source !== window) return;
    var d = ev.data;
    if (!d || d.__b0b !== 1 || !d.payload) return;
    try {
      browser.runtime.sendMessage({ __b0b: 1, payload: d.payload });
    } catch (e) {
      console.log('[b0b] 转给后台页失败：' + e);
    }
  }, false);

  console.log('[b0b] 内容脚本就绪 ' + location.href);
})();
