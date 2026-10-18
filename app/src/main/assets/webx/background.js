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


/* 全屏记录（2026-10-18 晚）：谁按了网页全屏、什么时候、当时哪一集 —— 各层都来这儿问 */
var b0bFullAt = 0;
var b0bFullVid = '';

browser.runtime.onMessage.addListener(function (msg) {
  if (!msg || msg.__b0b !== 1 || !msg.payload) return;
  forward(msg.payload);
});

/*
 * 代内容脚本取 cookie（2026-10-18 02:24）。
 *
 * 内容脚本里可能没有 cookies 接口（GeckoView 的 MV2 环境对内容脚本限制更严），
 * 后台页是扩展里权限最全的地方 —— 这里取好再回给内容脚本。
 * 腾讯那次"登录票：无"到底是真没票，还是取票通道不通，这条通道能分开。
 */
/*
 * 用**浏览器自己的身份**去问腾讯要整片地址（2026-10-18 晚）。
 *
 * 为什么放到后台页：内容脚本发跨域请求会被 CORS 拦，而后台页有主机权限、
 * 请求由浏览器带票，不需要我们自己去抠 cookie（实测我们抠出来的 110 条票里
 * 一条登录凭证都没有，而页面本身是登录着的 —— 由浏览器发请求就绕开了这件事）。
 * 顺带把响应里的 preview / login 两个字段回给内容脚本，用于判定身份有没有生效。
 */
browser.runtime.onMessage.addListener(function (msg, sender, respond) {
  try {
    /*
     * 页面世界直发失败时的兜底：把**已经算好签名的完整地址**丢给后台页代发
     * （2026-10-18 晚）。后台页发的请求同样带浏览器身份。
     */
    if (msg && msg.__b0bSignGet && msg.url) {
      fetch(msg.url, { credentials: 'include' }).then(function (r) { return r.text(); }).then(function (t) {
        try { respond({ text: t }); } catch (e) {}
      }).catch(function (e) {
        console.log('[b0b] 后台页代发签名请求失败：' + e);
        try { respond({ error: String(e) }); } catch (e2) {}
      });
      return true;
    }
    /*
     * 全屏这件事跨层共享（2026-10-18 晚，父亲定的简单逻辑：
     * 「我点了网页全屏 → 去要整集 → 接管」）。
     *
     * 为什么放后台页记：腾讯把播放器放在别的层里，按全屏那一层和去要地址那一层
     * 往往不是同一个（头显日志「本地存=0 别层报=0」就是这个病）。后台页只有一份，
     * 谁按了全屏都往这儿报，谁要判别都来这儿问。
     */
    if (msg && msg.__b0bFullReport) {
      b0bFullAt = Date.now();
      b0bFullVid = String(msg.vid || '');
      console.log('[b0b] 后台页记下"按了网页全屏"：' + new Date(b0bFullAt).toTimeString().slice(0, 8) +
        '，片号 ' + (b0bFullVid || '未知'));
      try { respond({ ok: 1 }); } catch (e) {}
      return true;
    }
    if (msg && msg.__b0bFullAsk) {
      try { respond({ at: b0bFullAt, vid: b0bFullVid }); } catch (e) {}
      return true;
    }
    /* 一次按键只触发一次接管（2026-10-19，父亲报"没点全屏也接管了"） */
    if (msg && msg.__b0bFullConsume) {
      b0bFullAt = 0;
      b0bFullVid = '';
      console.log('[b0b] 这次"按了网页全屏"已用掉，清掉记录（不再重复接管）');
      try { respond({ ok: 1 }); } catch (e) {}
      return true;
    }
    if (!msg || !msg.__b0bWhole || !msg.vid) return;
    var u = 'https://vv.video.qq.com/getinfo?otype=json&vid=' + encodeURIComponent(msg.vid) +
      '&platform=101001&sdtfrom=v1010&defn=fhd&vt=3&charge=0&defnpayver=1';
    fetch(u, { credentials: 'include' }).then(function (r) { return r.text(); }).then(function (t) {
      try { respond({ text: t }); } catch (e) {}
    }).catch(function (e) {
      console.log('[b0b] 后台页要整片地址失败：' + e);
      try { respond({ error: String(e) }); } catch (e2) {}
    });
    return true;
  } catch (e) {}
  return false;
});

try {
  browser.runtime.onMessage.addListener(function (msg, sender, respond) {
    try {
      if (!msg || !msg.__b0bCookies) return;
      var dom = msg.domain || '';
      /*
       * 2026-10-18 晚改：原来只按 `{domain:'qq.com'}` 取，票里一条登录名字都没有，
       * 而父亲明确 app 浏览器是登录着的 —— 问题在过滤条件：httpOnly 的登录票挂在 `.qq.com`，
       * 不带点的 `qq.com` 过滤会漏掉。改成"按具体网址 + 带点域名"多路取，再合并去重。
       */
      var filters = [
        { url: 'https://v.qq.com/' },
        { url: 'https://vv.video.qq.com/' },
        { url: 'https://vv6.video.qq.com/' },
        { domain: '.' + (dom || 'qq.com') },
        { domain: dom || 'qq.com' }
      ];
      Promise.all(filters.map(function (f) {
        return browser.cookies.getAll(f).catch(function () { return []; });
      })).then(function (lists) {
        var seen = {}, out = [];
        (lists || []).forEach(function (list) {
          (list || []).forEach(function (c) {
            var k = c.name + '@' + (c.domain || '') + '@' + (c.path || '');
            if (seen[k]) return;
            seen[k] = 1;
            out.push(c.name + '=' + c.value);
          });
        });
        var str = out.join('; ');
        console.log('[b0b] 后台页代取 cookie（' + dom + '，多路合并）：' + str.length + ' 字符，名字=' +
          out.map(function (x) { return x.split('=')[0]; }).join(','));
        try { respond({ cookie: str }); } catch (e) {}
      }).catch(function (e) {
        console.log('[b0b] 后台页取 cookie 失败：' + e);
        try { respond({ cookie: '' }); } catch (e2) {}
      });
      return true;
    } catch (e) {}
    return false;
  });
} catch (e) {}

/*
 * 从浏览器层面看媒体的每一跳请求（2026-10-18）。
 *
 * 【为什么必须在这一层】腾讯的网页播放器把分片（一小段一小段、各带各的签名）
 * 交给后台线程去取 —— 页面里的资源清单在火狐内核里看不到这些请求，
 * 于是"攒分片"这条路在头显上一条都攒不到（r680 实测日志：0 条）。
 * webRequest 是唯一能看见全部请求的地方，收到就转发给内容脚本 → 页面世界去攒。
 */
try {
  browser.webRequest.onCompleted.addListener(
    function (d) {
      try {
        var u = d && d.url ? d.url : '';
        if (u.indexOf('index=') < 0) return;
        if (!/\.ts\?|\/B_|gtimg|ltsyd|ltshwy|vlive|vipzj/i.test(u)) return;
        var m = /[?&]index=(\d+)/.exec(u);
        if (!m) return;
        browser.runtime.sendMessage({ __b0bNet: 1, i: parseInt(m[1], 10), u: u }).catch(function () {});
      } catch (e) {}
    },
    { urls: ['<all_urls>'] },
  );
} catch (e) {
  console.log('[b0b] 后台页挂网络监听失败：' + e);
}
