/*
 * 扩展与客户端之间的桥（父亲 2026-10-15）。
 *
 * 这一段跑在扩展的隔离世界里 —— 只有这里能用 browser.runtime.connectNative，
 * 但动不了页面的 fetch/XHR。所以：
 *   1) 把 main.js 注入页面自己的世界（抓地址、量画面）；
 *   2) 收页面世界用 window.postMessage 发出来的数据，转手发给客户端。
 *
 * 客户端那侧的名字是 b0b（见 WebRuntime.ensureBridge 里的 nativeApp）。
 */
(function () {
  var port = null;
  try {
    port = browser.runtime.connectNative('b0b');
  } catch (e) {
    port = null;
  }

  function inject() {
    try {
      var s = document.createElement('script');
      s.src = browser.runtime.getURL('main.js');
      s.onload = function () { try { s.remove(); } catch (e) {} };
      (document.head || document.documentElement).appendChild(s);
    } catch (e) {}
  }

  try {
    if (document.documentElement) inject();
    else document.addEventListener('DOMContentLoaded', inject, { once: true });
  } catch (e) {}

  window.addEventListener('message', function (ev) {
    if (ev.source !== window) return;
    var d = ev.data;
    if (!d || d.__b0b !== 1 || !d.payload) return;
    try { if (port) port.postMessage(d.payload); } catch (e) {}
  }, false);
})();
