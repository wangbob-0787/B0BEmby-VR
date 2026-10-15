/*
 * 扩展与客户端之间的桥（父亲 2026-10-15）。
 *
 * 这一段跑在扩展的隔离世界里 —— 只有这里能用 browser.runtime.connectNative，
 * 但动不了页面的 fetch/XHR。所以页面数据的采集交给 main.js（它由 manifest 声明为
 * world:MAIN 的内容脚本，直接跑在页面自己的世界里），两边用 window.postMessage 通信。
 *
 * 【2026-10-15 第二版】第一版是用 <script src=moz-extension://...> 注入 main.js 的，
 * 实测 B站 完全没数据回来 —— 页面 CSP 把外部脚本拦了。改成 manifest 里声明
 * world:MAIN 的内容脚本，注入由内核自己做，绕开 CSP。
 *
 * 客户端那侧的名字是 b0b（见 WebRuntime.ensureBridge 里的 nativeApp）。
 */
(function () {
  var port = null;
  try {
    port = browser.runtime.connectNative('b0b');
    console.log('[b0b] 桥已连上客户端');
  } catch (e) {
    port = null;
    console.log('[b0b] 连客户端失败：' + e);
  }

  window.addEventListener('message', function (ev) {
    if (ev.source !== window) return;
    var d = ev.data;
    if (!d || d.__b0b !== 1 || !d.payload) return;
    try {
      if (port) port.postMessage(d.payload);
    } catch (e) {
      console.log('[b0b] 转发失败：' + e);
    }
  }, false);

  console.log('[b0b] 隔离世界就绪 ' + location.href);
})();
