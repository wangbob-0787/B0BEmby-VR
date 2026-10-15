/*
 * 扩展的后台页（父亲 2026-10-15）。
 *
 * 【为什么要有它】第一版直接在内容脚本里 connectNative，实测 port 立刻被断开：
 *   [b0b] 桥已连上客户端
 *   [b0b] 转发失败：Error: Attempt to postMessage on disconnected port
 * 客户端侧一条数据都没收到。浏览器里 native messaging 的正规形态是**从扩展的后台页发起**，
 * 内容脚本只负责把数据 sendMessage 给它 —— 换过来之后后台页只有一条连接，也顺便躲开
 * "每个框架各连一次"带来的混乱。
 *
 * 两条路都留着：port 通了走 port，没通退化成一次性 sendNativeMessage。
 * 客户端那侧的名字是 b0b（见 WebRuntime.ensureBridge 里的 nativeApp）。
 */
var port = null;

function connect() {
  try {
    port = browser.runtime.connectNative('b0b');
    console.log('[b0b] 后台页已连上客户端');
  } catch (e) {
    port = null;
    console.log('[b0b] 后台页连客户端失败：' + e);
  }
}

connect();

function forward(payload) {
  try {
    if (port) {
      port.postMessage(payload);
      return;
    }
  } catch (e) {
    console.log('[b0b] port 转发失败，退回一次性消息：' + e);
    port = null;
  }
  try {
    browser.runtime.sendNativeMessage('b0b', payload);
  } catch (e) {
    console.log('[b0b] 一次性消息也失败：' + e);
  }
}

browser.runtime.onMessage.addListener(function (msg) {
  if (!msg || msg.__b0b !== 1 || !msg.payload) return;
  forward(msg.payload);
});
