package com.xxxx.emby_vr.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.chromium.android_crwebview.webkit.WebChromeClient
/*
 * 【2026-10-20 15:37 换内核：网页视图与它的客户端改用打进应用的 Chromium 132】
 *
 * 这两行原来是 org.chromium.android_crwebview.webkit.WebView / WebViewClient —— 也就是头显系统自带的 Chromium 90。
 * 现在换成 crwebview 2.0（Chromium 132）里同名同接口的类，调用侧代码完全不用改。
 * WebChromeClient 仍用系统的（库里没导出它），它是接口，可以混用。
 * 火狐那套（GeckoView）等这条新的跑通后再整体拆掉。
 */
import org.chromium.android_crwebview.webkit.WebView
import org.chromium.android_crwebview.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xxxx.emby_vr.R
import com.xxxx.emby_vr.panel.vrClickTarget
import com.xxxx.emby_vr.web.WebPageHub

/**
 * 网页媒体库页（父亲 2026-10-13）。
 *
 * 海报墙首页上「B站 / 腾讯视频 / 爱奇艺」三张媒体库海报点进来就是这一页：
 * 面板整块换成该网站的网页版，宽度按面板宽度缩放，摇杆上下滚动看更多内容 ——
 * 和进一个普通 Emby 媒体库是同一个位置、同一个感觉，只是内容变成了网页。
 *
 * ## 为什么网页能放进面板
 * 面板本身就是"虚拟显示器 + 一块窗口"，网页在我们自己的进程里渲染，
 * 不需要任何系统特权（嵌别的应用窗口那条路普通应用拿不到画面也点不动，2026-10-13 实测）。
 *
 * ## 交互（这是关键）
 * 面板点击不走系统触摸，而是"光柱落点 → 查控件坐标表 → 无参动作"，
 * 而网页必须知道点在哪 —— 所以这里把"面板像素 → 网页像素"的派发登记到 [WebPageHub]，
 * 主界面收到光柱点击时直接交给这里（落点先按本页在面板里的矩形换算，再发给网页）。
 */
/**
 * 藏掉"打开APP / 下载客户端"这类浮层（父亲 2026-10-13：爱奇艺一直有个横幅提示打开app）。
 *
 * 只动"固定定位 + 文字命中关键词 + 面积不大"的元素，避免误伤正文。
 */
private const val HIDE_APP_BANNER_JS = """
(function(){
  try{
    var kw=['打开APP','打开 APP','立即打开','下载APP','下载客户端','打开客户端','去APP看','打开看看'];
    var all=document.querySelectorAll('div,a,section,header,span,p');
    for(var i=0;i<all.length;i++){
      var e=all[i];
      var t=(e.innerText||'').trim();
      if(!t||t.length>40){continue;}
      var hit=false;
      for(var k=0;k<kw.length;k++){ if(t.indexOf(kw[k])>=0){hit=true;break;} }
      if(!hit){continue;}
      var st=window.getComputedStyle(e);
      if(st.position!=='fixed'&&st.position!=='sticky'){continue;}
      e.style.display='none';
    }
  }catch(err){}
})()
"""

/**
 * 读出"网页里正在播的那块视频画面"占视口哪一块（父亲 2026-10-15）。
 *
 * 为什么要它：三站的网页播放器都按视频原始比例居中显示，比例不是 16:9 时四周留黑边；
 * 大银幕以前固定按 16:9 贴整张画布，于是黑边一起被贴上去、画面也用不足墙宽。
 * 把这块矩形交给原生后，银幕按视频比例变宽、并且只采这一块 —— 视频正好铺满墙宽。
 *
 * 取面积最大的那个可见 video：contain（默认）按视频比例居中算画面块；
 * cover / fill 时元素矩形本身就是画面（cover 是裁掉边缘、fill 是拉伸）。
 * 读不到就返回 'null'，原生照旧按整张画布来。
 *
 * 返回 "x,y,w,h,aspect"（前四个归一化到视口，aspect 是真实像素比）。
 */
private const val VIDEO_RECT_JS = """
(function(){
  try{
    var vs=document.querySelectorAll('video'),best=null,area0=0;
    for(var i=0;i<vs.length;i++){
      var v=vs[i];
      var vw=v.videoWidth||0,vh=v.videoHeight||0;
      if(!vw||!vh||v.readyState<2){continue;}
      var r=v.getBoundingClientRect();
      if(r.width<40||r.height<40){continue;}
      var st=window.getComputedStyle(v);
      if(st.display==='none'||st.visibility==='hidden'){continue;}
      if(parseFloat(st.opacity||'1')<0.05){continue;}
      var area=r.width*r.height;
      if(area<=area0){continue;}
      var cw=r.width,ch=r.height,fit=(st.objectFit||'contain');
      if(fit!=='cover'&&fit!=='fill'){
        var ar=vw/vh,er=cw/ch;
        if(ar>er){ ch=cw/ar; } else { cw=ch*ar; }
      }
      area0=area;
      best={x:r.left+(r.width-cw)/2,y:r.top+(r.height-ch)/2,w:cw,h:ch};
    }
    if(!best){return 'null';}
    var iw=window.innerWidth||1,ih=window.innerHeight||1;
    return [best.x/iw,best.y/ih,best.w/iw,best.h/ih,(best.w*iw)/(best.h*ih)].join(',');
  }catch(err){ return 'null'; }
})()
"""

/** 把 [VIDEO_RECT_JS] 的返回值解析成 x/y/w/h/aspect；读不到返回 null */
private fun parseVideoRect(raw: String?): FloatArray? {
    val s = raw?.trim()?.trim('"') ?: return null
    if (s.isEmpty() || s == "null") return null
    val p = s.split(",")
    if (p.size < 5) return null
    val f = FloatArray(5)
    for (i in 0 until 5) {
        f[i] = p[i].trim().toFloatOrNull() ?: return null
    }
    if (f[2] <= 0.02f || f[3] <= 0.02f || f[4] <= 0.1f) return null
    return f
}

/** 把探测结果报给原生（null = 没在播/读不到 → 原生按整张画布走） */
private fun pushVideoRect(f: FloatArray?) {
    if (f == null) {
        /* 银幕不再跟随网页画面比例（父亲 2026-10-16 08:09）：系统网页这条路也同样不下发 */
    } else {
        /* 同上，不再下发 */
    }
}

/**
 * 按"鼠标"身份给网页派发一个事件（父亲 2026-10-13）。
 *
 * 用 SOURCE_MOUSE 而不是默认的触摸：网页播放器的控制条是"鼠标一活动才浮出来"的，
 * 光柱停着不动时它一直藏着 —— 表现就是"网页全屏后点不到控制按钮"。
 * 移动类事件走 dispatchGenericMotionEvent，按下/抬起走 dispatchTouchEvent。
 *
 * 【2026-10-14 修正】原来每次事件都拿"自己的时间"同时当按下时间和事件时间，
 * 一次点击的 DOWN 与 UP 于是按下时间不同 —— 网页那边把它当成两个不相干的手势，
 * 点击就丢了。现在按下时间统一（DOWN / MOVE / UP 共用一个），只在事件时间上递增，
 * 并在抬起前补一次 MOVE（真实点击就是这个序列）。
 * 另加运行时开关 <web_click>：0 = 鼠标语义、1 = 触摸语义，戴着切一下就知道哪种认。
 */
private fun dispatchMouse(
    wv: android.view.View,
    action: Int,
    x: Float,
    y: Float,
    downTime: Long,
    eventMs: Long,
) {
    /*
     * 两种发法，按 <web_click> 选：
     *   2（默认）= 鼠标按键式：按下/抬起发成"鼠标左键按下/抬起"，走**悬停同一条通道**
     *              （dispatchGenericMotionEvent + SOURCE_MOUSE）。
     *              父亲 2026-10-14 实测：光柱移到海报上网页有焦点反应，说明这条通道是通的；
     *              而原来那种触摸式按下它不认。
     *   1 = 触摸式：ACTION_DOWN/UP + SOURCE_TOUCHSCREEN。
     *   0 = 鼠标触摸式：ACTION_DOWN/UP + SOURCE_MOUSE。
     */
    /*
     * 【2026-10-20 16:55 换内核后点击只能走触摸】
     *
     * 实机日志（r797）：「网页点击派发（方式 0）：0 被接住=true … 有焦点=false」——
     * 事件送到了网页，网页却不理。原因是方式 0 把按下/抬起发成"鼠标源的触摸事件"，
     * 火狐内核吃这一口，Chromium 不吃（它只认真正的触摸序列）。
     * 滚动那条一直是鼠标滚轮、新内核照吃，所以只有点击坏掉，与现象一致。
     *
     * 这里不再看 <web_click> 开关，点击一律按标准触摸发（FINGER + TOUCHSCREEN）；
     * 悬停/移出仍走鼠标通道（网页的 hover 效果必须有鼠标源）。
     */
    /* HOVER_EXIT 也必须按"悬停类"处理（2026-10-15）：它是"鼠标移出"，走的是
     * 通用运动事件那条路，若按触摸发，网页根本收不到。 */
    val hover = action == MotionEvent.ACTION_HOVER_MOVE ||
        action == MotionEvent.ACTION_HOVER_EXIT
    /*
     * 【2026-10-21 10:40 恢复运行时可切换：爱奇艺的弹层按钮只认真鼠标点击】
     *
     * 头显实测（r839）：打在"智能（清晰度）""选集"上的坐标完全正确（换算日志 u/v 对得上、
     * elementFromPoint 命中的就是这两个按钮），可点了就是不弹层。
     * 同一页在调试口用**真鼠标点击**（Input.dispatchMouseEvent）立刻弹出 1080P 选项 ——
     * 说明 iQiyi 这类弹层按钮认鼠标点击，不认我们现在发的触摸点击。
     * 因此把 <web_click> 开关恢复成生效：0 = 鼠标点击、1 = 触摸点击，戴着切一下即可定论，
     * 不用重新编译。
     */
    val mode = if (hover) 0 else com.xxxx.emby_vr.vr.VrTuning.webClickMode
    if (mode == 2 && !hover) {
        /* 「鼠标左键按下 / 抬起」在 Android 里是通用运动事件，不是触摸的 DOWN / UP */
        val btnAction = when (action) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_BUTTON_PRESS
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_BUTTON_RELEASE
            else -> return /* MOVE 不重复发，否则等于点了两下 */
        }
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
                size = 1f
            },
        )
        val ev = MotionEvent.obtain(
            downTime, eventMs, btnAction, 1, props, coords,
            0,
            if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        val taken = runCatching { wv.dispatchGenericMotionEvent(ev) }.getOrDefault(false)
        android.util.Log.i(
            "B0BEmbyVR",
            "网页点击派发（鼠标按键式）：$btnAction 被接住=$taken 坐标=$x,$y " +
                "视图=${wv.javaClass.simpleName} ${wv.width}x${wv.height} " +
                "有焦点=${wv.hasFocus()} 窗口焦点=${wv.hasWindowFocus()}",
        )
        ev.recycle()
        return
    }
    val touchMode = !hover && mode == 1
    val props = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (touchMode) MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_MOUSE
        },
    )
    val coords = arrayOf(
        MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
            size = 1f
        },
    )
    /*
     * 鼠标左键必须显式带上 BUTTON_PRIMARY：按下时置位、抬起时清零。
     * 原来这里一律传 0，等于告诉网页"没有任何按键按下" —— 光有坐标没有左键，
     * 点击自然没反应（2026-10-14 复查事件构造时发现）。
     */
    val buttonState = when (action) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> MotionEvent.BUTTON_PRIMARY
        else -> 0
    }
    val ev = MotionEvent.obtain(
        downTime, eventMs, action, 1, props, coords,
        0, buttonState, 1f, 1f, 0, 0,
        if (touchMode) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_MOUSE,
        0,
    )
    val taken = runCatching {
        if (action == MotionEvent.ACTION_HOVER_MOVE ||
            action == MotionEvent.ACTION_HOVER_EXIT
        ) {
            wv.dispatchGenericMotionEvent(ev)
        } else {
            wv.dispatchTouchEvent(ev)
        }
    }.getOrDefault(false)
    /*
     * 只有"按下 / 抬起"记日志（悬停太频繁，不记）。
     * 这一行是排查用的关键证据：被接住=false 说明内核根本没处理这个事件；
     * =true 却点不动，说明事件收下了但网页那边没用它 —— 两条路要查的东西完全不同。
     */
    if (action != MotionEvent.ACTION_HOVER_MOVE) {
        android.util.Log.i(
            "B0BEmbyVR",
            "网页点击派发（方式 $mode）：$action 被接住=$taken 坐标=$x,$y " +
                "视图=${wv.javaClass.simpleName} ${wv.width}x${wv.height} " +
                "有焦点=${wv.hasFocus()} 窗口焦点=${wv.hasWindowFocus()}",
        )
    }
    ev.recycle()
}

/**
 * 给网页发一次"左右方向键"（父亲 2026-10-13：网页全屏播放时摇杆左右 = 快退/快进）。
 *
 * 三个站的网页播放器都认键盘的 ← →（B站一档 5 秒、腾讯同类），所以不去猜进度条坐标，
 * 直接按键最稳 —— 网页自己跳、自己更新进度条。
 */
private fun dispatchArrowKey(wv: android.view.View, right: Boolean) {
    val code = if (right) {
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    } else {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT
    }
    val now = SystemClock.uptimeMillis()
    runCatching {
        wv.dispatchKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0),
        )
        wv.dispatchKeyEvent(
            android.view.KeyEvent(now + 8, now + 8, android.view.KeyEvent.ACTION_UP, code, 0),
        )
    }
}

/**
 * 爱奇艺槽（2026-10-22 17:29 父亲定）。
 *
 * 和 B站、腾讯 一样要有槽，只是爱奇艺 的槽多一步：槽里存的是**整集片单**，
 * 核对通过后交给本地服务，大屏从本地服务读。
 *
 * 四格：身份（网址 v_ 编号 + 片号）、标题、时刻、地址（片单）。
 * 另外记一张「网址 v_ 编号 → 片号」的配对表：网址变了、片号没变 = 页面还没切过去。
 */
private object IqSlot {
    /** 网址 v_ 编号 → 片号（成功交付一次记一笔，用来识别"页面还没切完"） */
    val pair = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    var v: String = ""

    @Volatile
    var tvid: String = ""

    @Volatile
    var title: String = ""

    @Volatile
    var at: Long = 0L
}

/**
 * 问页面此刻是哪一集：返回（播放器对象上的片号, 网址里的 v_ 编号）。
 * 在后台线程调用，内部把取值动作投回主线程，避免跨线程碰 WebView。
 */
/**
 * 滚动黑洞兜底：光柱落在视频、画布这类元素上时，它们会把滚轮事件吃掉却不滚任何东西，
 * 整块区域就滚不动了。这段 JS 顺着命中元素的父链找真正会滚的那个容器；
 * 事件派发后 80ms 那个容器还没滚动，就说明落进了黑洞，直接补滚它。
 */
private fun scrollFallbackJs(u: Float, v: Float, sy: Float): String {
    val d = if (sy > 0f) -60 else 60
    return "(function(u,v,d){" +
        "var cx=Math.round(u*window.innerWidth),cy=Math.round(v*window.innerHeight);" +
        "var need=window.innerHeight*0.25,cands=[],n=document.elementFromPoint(cx,cy);" +
        /* 光点落点往上每一层都收进来：不能只认第一个"能滚"的，
           播放器里面常有一层又小又能滚的覆盖层，滚它等于没滚。 */
        "while(n){if(n.scrollHeight>n.clientHeight+2&&n.clientHeight>need)cands.push(n);n=n.parentElement;}" +
        "cands.push(document.scrollingElement||document.documentElement);cands.push(document.body);" +
        "setTimeout(function(){" +
        "for(var i=0;i<cands.length;i++){var s=cands[i];if(!s)continue;var b=s.scrollTop;" +
        "try{if(s.scrollBy)s.scrollBy(0,d);if(s.scrollTop===b)s.scrollTop=b+d;}catch(e){}" +
        "if(Math.abs(s.scrollTop-b)>1){console.log('[b0b] 网页滚了 '+(s.tagName||'?')+" +
        "' 高'+s.clientHeight+'/'+s.scrollHeight+' '+b+'→'+s.scrollTop);return;}}" +
        "console.log('[b0b] 网页滚不动：候选'+cands.length+'个，点'+cx+','+cy);" +
        "},80);" +
        "})(${u},${v},${d});"
}

/** 取播放窗口左上角那行字。 */
private fun askPageHeader(w: WebView?): String {
    if (w == null) return ""
    var t = ""
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching {
            w.evaluateJavascript(
                "String((window.__b0bIqHeader&&window.__b0bIqHeader())||'')",
            ) { raw ->
                t = (raw ?: "").trim('"')
                latch.countDown()
            }
        }.onFailure { latch.countDown() }
    }
    runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    return t
}

/** 问页面"现在是第几集"（取不到就沿用标题里的）。 */
private fun askPageEpNo(w: WebView?): Int {
    if (w == null) return 0
    var v = ""
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching {
            w.evaluateJavascript(
                "String((window.__b0bIqEpNo&&window.__b0bIqEpNo())||'')",
            ) { raw ->
                v = (raw ?: "").trim('"')
                latch.countDown()
            }
        }.onFailure { latch.countDown() }
    }
    runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    return v.toIntOrNull() ?: 0
}

/** 问页面自己那份分集表：第 n 集的片号。 */
private fun askPageEpTvId(w: WebView?, n: Int): String {
    if (w == null || n <= 0) return ""
    var id = ""
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching {
            w.evaluateJavascript(
                "String((window.__b0bIqEpTvId&&window.__b0bIqEpTvId(" + n + "))||'')",
            ) { raw ->
                id = (raw ?: "").trim('"')
                latch.countDown()
            }
        }.onFailure { latch.countDown() }
    }
    runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    return id
}

/** 读页面上的专辑号（分集表要它才能查）。 */
private fun askPageAlbum(w: WebView?): String {
    if (w == null) return ""
    var a = ""
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching {
            w.evaluateJavascript(
                "String((window.QiyiPlayerProphetData&&QiyiPlayerProphetData.videoInfo&&" +
                    "QiyiPlayerProphetData.videoInfo.albumId)||'')",
            ) { raw ->
                a = (raw ?: "").trim('"')
                latch.countDown()
            }
        }.onFailure { latch.countDown() }
    }
    runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    return a
}

private fun askPageIdentity(w: WebView?): Pair<String, String> {
    if (w == null) return "" to ""
    var t = ""
    var v = ""
    val latch = java.util.concurrent.CountDownLatch(1)
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        runCatching {
            w.evaluateJavascript(
                "(function(){try{var pd=window.QiyiPlayerProphetData||{};" +
                    /* 页面脚本那份更新（取自页面自己的取流请求），优先用它 */
                    "var j=String((window.__b0bIqTvid&&window.__b0bIqTvid())||'');" +
                    "var t=j||String(pd.tvid||pd.tvId||(pd.videoInfo||{}).tvId||'');" +
                    "var m=/\\/v_([0-9A-Za-z]+)/.exec(location.pathname||'');" +
                    "return {t:t,v:m?m[1]:''};}catch(e){return {};}})()"
            ) { raw ->
                runCatching {
                    val o = org.json.JSONObject((raw ?: "").trim('"'))
                    t = o.optString("t")
                    v = o.optString("v")
                }
                latch.countDown()
            }
        }.onFailure { latch.countDown() }
    }
    runCatching { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) }
    return t to v
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLibraryScreen(
    site: Int,
    /**
     * 【2026-10-17 修「按 B 回到 B站 首页」】要载入的**具体页面**。
     *
     * 原来这里只会载入站点首页。接管时网页面整个被拆掉（`webSiteState = null`），
     * 回网页时再把它建回来 —— 建的时候只有站点编号可用，于是重新载入的必然是站点首页。
     * 头显日志实证：按 B 后紧跟 `网页开始载入：about:blank` → `https://www.bilibili.com/`。
     * 现在接管那一刻把"当时那一页的地址"记下来，回网页时按它载入，落回原页。
     */
    startUrl: String? = null,
    /**
     * 抓到"可播地址"时的回调（2026-10-16）。
     *
     * 由 MainActivity 接过去，用我们自己的播放器播 —— 这样网页只当选片器，
     * 比例、弹幕、控制条都不再受网页排版摆布（父亲 02:05 定的方向）。
     */
    onWebStream: (org.json.JSONObject) -> Unit = {},
    /**
     * 攒到"播放分片"时的回调（2026-10-18）。
     *
     * 腾讯这种站没有整片地址也没有播放列表，只能一小段一小段地收；这里把收来的段
     * 一批一批交给 MainActivity，由它接在播放列表后面（第一段负责起播）。
     */
    onWebFrags: (org.json.JSONObject) -> Unit = {},
    /*
     * "这一集可以播了"的提前通报（2026-10-19 父亲定）。
     *
     * 父亲的口径：他在网页上选好集，地址其实早就到手了，不必等他去点网页全屏；
     * 客户端先把这一集的信息填到控制条上，他按控制条上的播放键再起播。
     * 这条只带信息（地址/时长/集号），**不触发接管**。
     */
    onWebPreview: (org.json.JSONObject) -> Unit = {},
) {
    /* 上下文：整集清单要落盘/起本机服务（2026-10-21 23:58 补，之前一直是空导致接不过去） */
    WebRuntime.setAppCtx(androidx.compose.ui.platform.LocalContext.current)

    /*
     * 换站点 = 整棵网页子树重建（父亲 2026-10-13 报"B站切腾讯打不开，要先去播一部片子才能开"）。
     *
     * 原因：建 WebView 的那一步（AndroidView 的 factory）只在**第一次**组合这块内容时跑，
     * 换站点不会重跑；而清理逻辑在换站时把这个 WebView `destroy()` 掉了 ——
     * 于是新站点被 loadUrl 到一个已经销毁的窗口上，什么都不会发生，加载提示一直转。
     * 去播一部 Emby 片子再回来 = 重新进这块内容，factory 重跑、新窗口建起来，所以能开。
     * 用 key(site) 按站点分组：换站时旧的整棵销毁（连窗口一起）、新的整棵重建。
     */
    /* 抓到可播地址的出口（onBridgePlayable 住在 WebRuntime 里，够不到这里的参数） */
    WebRuntime.setStreamSink(onWebStream)
    /* 攒到的分片走另一个出口：它不换源，只是往播放列表后面接（2026-10-18） */
    WebRuntime.setFragSink(onWebFrags)
    WebRuntime.setPreviewSink(onWebPreview)
    /*
     * 起始页一起进 key：接管后回网页要落在"接管那一刻那一页"，那是同一站点下的另一个地址，
     * 只按站点编号做 key 不会重建（2026-10-17）。
     */
    key(site, startUrl) { WebPageForSite(site, startUrl) }
}

/*
 * 火狐内核的运行环境是**进程级单例**，只能有一个。
 *
 * run 528 实测崩溃（父亲：从腾讯切到 B站 直接闪退）：
 *   java.lang.IllegalStateException: Only one GeckoRuntime instance is allowed
 *     at GeckoRuntime.getDefault(GeckoRuntime.java:241)
 * 切站点会重建网页块 → 再进一次 factory → 第二次 create 抛「只允许一个」，
 * 连兜底的 getDefault 也抛同一个异常 → 整个应用退出。
 * 修法：拿到就留着复用，第二次直接用旧的；参数只在第一次生效
 * （所以改 <web_touch> 要重启应用）。
 */
/**
 * 让网页面保持活跃（2026-10-18）。
 *
 * 攒分片接管播放时网页面留在后台，火狐内核可能把会话判成不活跃而让页面停手；
 * 播放侧定时调这一下，把它按回"活跃 + 高优先级"。`WebRuntime` 是文件内私有的，
 * 所以由这个顶层函数对外开口。
 */
fun keepWebPageActive() {
    /*
     * 2026-10-20：火狐内核拆掉后这条不再需要 —— 新内核的网页跑在独立渲染进程里，
     * 不会因为"会话判成不活跃"而停手。函数保留为空实现，免得调用方到处改。
     */
}

/**
 * 取网页面此刻的 Cookie（2026-10-18）。
 *
 * 签名路线要地址时，会员身份就靠这一条（腾讯的播放接口认 Cookie）。
 * 扩展那条桥从 qq.com 取到的票存在 WebRuntime 里，这里对外开口。
 */
fun currentWebCookie(): String {
    return WebRuntime.cookieNow()
}

private object WebRuntime {
    /* 火狐内核已拆（2026-10-20）：原来缓存的 GeckoRuntime 不再需要 */

    /**
     * 应用上下文（2026-10-20）：整集清单要起本机服务 / 落盘成 m3u8 文件，需要 cacheDir。
     *
     * 【2026-10-21 23:58 修】这个字段一直**没有任何地方赋值**，
     * 于是 onBridgeHls 走到 `val c = appCtx ?: ...` 直接「拿不到上下文，放弃」——
     * 爱奇艺自取 671 段片单明明到了，播放却起不来（父亲："接不过去"）。
     * 现在由 WebLibraryScreen 在组合时灌进来。
     */
    private var appCtx: android.content.Context? = null

    fun setAppCtx(ctx: android.content.Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    /** 整集清单的本机服务（2026-10-20）：同一个进程复用，换集只换内容 */
    private var hlsServer: com.xxxx.emby_vr.web.PlaylistServer? = null

    /**
     * 网页抓到可播地址之后交给谁（2026-10-16）。
     *
     * onBridgePlayable 住在 WebRuntime 里，够不到 WebLibraryScreen 的 Composable 参数，
     * 所以界面进来时把去向登记在这儿；为 null 时只打日志、不做切换。
     */
    private var streamSink: ((org.json.JSONObject) -> Unit)? = null

    fun setStreamSink(f: ((org.json.JSONObject) -> Unit)?) {
        streamSink = f
    }

    /**
     * 网页面那个会话（2026-10-18）。
     *
     * 接管播放时网页面会被收起来，火狐内核如果把这个会话判成"不活跃"，
     * 页面里的计时器和播放都会停 —— 分片就不再来（头显实测：收起来之后
     * 十秒一跳的心跳整段消失，客户端手里那一段播完就回到网页，看着就是"反复播一小段"）。
     * 这里留一个口子，由播放侧定时把它按回"活跃 + 高优先级"。
     */
    /*
     * 火狐内核的两个能力（attachPageSession / keepPageActive）随内核一起删了（2026-10-20）。
     * 新内核（Chromium 132）里网页跑在自己的渲染进程，不需要我们手动保活。
     */

    /**
     * 攒到的播放分片交给谁（2026-10-18）。
     *
     * 腾讯这种站没有整片地址也没有播放列表，只能一段一段收；收来的段交给
     * MainActivity，由它喂进我们自己的播放器（第一段起播，后面的接在后面）。
     */
    private var fragSink: ((org.json.JSONObject) -> Unit)? = null

    fun setFragSink(f: ((org.json.JSONObject) -> Unit)?) {
        fragSink = f
    }

    /**
     * "这一集可以播了"的提前通报交给谁（2026-10-19 父亲定）。
     *
     * 与 streamSink 的区别：这条**只填信息、不起播**。父亲在网页上选好集之后，
     * 控制条上就该显示这一集的时长与集号，他按控制条上的播放键才真正接管。
     */
    private var previewSink: ((org.json.JSONObject) -> Unit)? = null

    fun setPreviewSink(f: ((org.json.JSONObject) -> Unit)?) {
        previewSink = f
    }

    /*
     * 原来这里按启动参数建火狐 runtime（get），随火狐内核一起删了（2026-10-20）。
     * 新内核由库自己初始化，不需要我们建。
     */

    /*
     * ============ 内置扩展：把页面里的数据交回客户端（父亲 2026-10-15） ============
     *
     * 背景：火狐内核（GeckoView）早已删掉"在页面里直接跑 JS"的接口 —— 150 的 GeckoSession
     * 里 evaluate / runJavaScript 一个都没有。唯一正规通道是内置一个扩展，由它的内容脚本
     * 把数据用 nativeMessaging 发回来。扩展资源在 <assets/webx/>：
     *   isolated.js（隔离世界）负责注入 + 转发；main.js（页面世界）负责量画面、抓播放地址。
     *
     * 两条用途：
     *   1) 视频画面矩形 → 银幕按视频比例变宽、只采那一块（网页视频铺满墙宽）；
     *   2) 播放地址候选 → 为"网页只选片、播放交给客户端"铺路。
     */
    private const val BRIDGE_URI = "resource://android/assets/webx/"
    private const val BRIDGE_ID = "b0b-bridge@local"
    private const val BRIDGE_APP = "b0b"

    /** 各框架最近报上来的视频画面：frame → [x, y, w, h, 比例, 像素面积, 时刻] */
    private val bridgeRects = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    private val bridgeLoggedUrls = java.util.Collections.synchronizedSet(HashSet<String>())
    private var bridgeTicker: android.os.Handler? = null
    private var bridgeInstalled = false

    /** 已生效的视频比例（0 = 按整张来） */
    private var bridgeApplied = 0f

    /** 待确认的新比例 + 连续命中次数（稳定三次才采用，见 ticker 注释） */
    private var bridgeCandidate = 0f
    private var bridgeHits = 0

    /** 上次记录的"可播地址"指纹（同一份不重复打日志） */
    private var bridgeLastPlaySig = ""

    /**
     * 【2026-10-22 修 P1：去重原来没有时间窗】
     *
     * 指纹相同就永久丢弃 —— 父亲按 B 回网页、再按一次全屏要重新接管时，
     * 只要页面交的还是同一条地址（同一集、同一档、地址没重新签发），
     * 这一下就会被当成"重复"永远接不回来。
     * 现在与页面侧的窗口对齐：同一指纹 3 秒内才算重复。
     */
    private var bridgeLastPlaySigAt = 0L
    /* 扩展每轮扫描都会带 location.href 上来 —— 这是"此刻页面在哪"的最新事实（2026-10-17） */
    private var bridgeLastFrame = ""

    /* 接收槽：网页交来的最新一份，整份覆盖，不判断。全屏跳变时消费一次。
       逻辑见 docs/ledger/native-side-spec-2026-10-25.md */
    private var slotAny: org.json.JSONObject? = null
    private var slotRecvAt = 0L
    private var slotRecvAtWall = 0L

    /** 最近一次拿到的可播地址（网页播放接口的完整返回），供"用自己的播放器播"用 */
    private var bridgePlayable: org.json.JSONObject? = null

    /** 内容脚本随消息附上的本站 cookie（会员片要拿它才换得到高清地址） */
    private var bridgeCookie = ""

    /** 桥取到的票（Cookie），供签名路线复用 */
    fun cookieNow(): String = bridgeCookie

    /**
     * 【2026-10-22 修 P2：换站时把上一站的桥状态清掉】
     *
     * 这几个字段是**全站点共用**的：切到腾讯之后，第一条交接载荷很可能还带着
     * B站 的 Cookie 和 B站 那一页的地址（页面还没报新的），于是 Referer/Cookie 都指错站，
     * 表现是"新站第一下接不过去"。换站时清一次，新站自己会再报上来。
     */
    fun resetForSite() {
        bridgeCookie = ""
        bridgeLastFrame = ""
        bridgeLastPlaySig = ""
        bridgeLastPlaySigAt = 0L
        bridgePlayable = null
        bridgeRects.clear()
        bridgeAspectCleared = false
    }

    /** 网页那侧的银幕比例是否已归零（父亲 2026-10-16 08:09：网页显示不再改比例） */
    private var bridgeAspectCleared = false

    /*
     * 原来这里往火狐内核里注册内置扩展（ensureBridge）来收发页面数据，随火狐一起删了。
     * 新内核走「页面里执行脚本 + 原生接口回传」，入口见 WebPageBridge（2026-10-20）。
     */

    /* 链路日志：统一前缀 B0B链，便于按时间线核对两份文档 */
    fun chain(step: String, kv: String) {
        android.util.Log.i("B0B链", step + " " + kv)
    }

    private fun slotBrief(o: org.json.JSONObject?): String {
        if (o == null) return "无"
        val st = o.optJSONArray("streams")?.optJSONObject(0)
        val addr = st?.optString("video").orEmpty().ifBlank { st?.optString("playlist").orEmpty() }
        return "站=" + o.optString("site") +
            " 集=" + o.optString("ep").ifBlank { o.optString("vid") } +
            " 季=" + o.optString("season", "-") +
            " 时长=" + o.optInt("durSec", 0) + "s" +
            " at=" + o.optLong("at", 0L) +
            " 尾=…" + addr.takeLast(26) +
            " 标题「" + o.optString("title").take(24) + "」"
    }

    fun clearSlot() { slotAny = null }

    /* 入槽：整份覆盖，不做任何判断 */
    private fun takeSlot(obj: org.json.JSONObject, kind: String) {
        slotAny = obj
        slotRecvAt = android.os.SystemClock.uptimeMillis()
        slotRecvAtWall = System.currentTimeMillis()
        chain("⑥原生入槽", "（$kind）" + slotBrief(obj))
    }

    /* 消费：只在"非真全屏 → 真全屏"跳变时调用一次 */
    fun consumeSlot(tag: String) {
        val s = slotAny
        if (s == null) {
            android.util.Log.w("B0BEmbyVR", "◆消费($tag) 拒收：接收槽里还没有数据")
            return
        }
        val dur = s.optInt("durSec", 0)
        val now = System.currentTimeMillis()
        val atWeb = s.optLong("at", 0L)
        val lag = if (atWeb > 0) "网页变化→原生收到 " + (slotRecvAtWall - atWeb) + "ms，收到→消费 " + (now - slotRecvAtWall) + "ms" else "缺 at"
        if (dur < 300) {
            chain("⑧消费-拒收", "（$tag）时长只有 $dur 秒（不足五分钟） $lag " + slotBrief(s))
            return
        }
        chain("⑧消费-起播", "（$tag）$lag " + slotBrief(s))
        if (s.optString("kind") == "hls") onBridgeHls(s) else onBridgePlayable(s)
    }

    private fun onBridgePlayable(obj: org.json.JSONObject) {
        val streams = obj.optJSONArray("streams") ?: return
        val lines = StringBuilder()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            if (lines.isNotEmpty()) lines.append(" / ")
            lines.append(s.optInt("height", 0)).append("p@")
                .append(s.optInt("bw", 0) / 1000).append("k")
        }
        val dur = obj.optInt("durSec", 0)
        /*
         * 【2026-10-17】这一份待交是扩展在本集"攒片段"时生成的，它自带的 frame 可能是
         * 更早那一页（头显实证：交出来的那一份记的页面比当前页面早 213 秒）。
         * 记"回网页要落在哪一页"必须用**此刻**的页面地址，所以这里用最新一跳覆盖它。
         */
        if (bridgeLastFrame.isNotBlank() && bridgeLastFrame != obj.optString("frame")) {
            android.util.Log.i(
                "B0BEmbyVR",
                "页面地址以此刻为准：交接对象记的 " + obj.optString("frame").takeLast(50) +
                    " → 此刻 " + bridgeLastFrame.takeLast(50),
            )
            obj.put("frame", bridgeLastFrame)
        }
        val ck = if (bridgeCookie.isNotBlank()) "有" else "无"
        /*
         * 去重判据必须带"地址指纹"。
         * 原来只有 时长|档位|登录票 —— 而候选池交出来的地址这三项恒为 "0|0p@0|无"，
         * 于是装完第一次成功接管之后，换片、切集、点旁边列表全被这一句挡掉，
         * 表现为"播放器再也不接手"（父亲 2026-10-16 现场定案，r590 日志）。
         */
        val fp = (streams.optJSONObject(0)?.optString("video") ?: "").takeLast(80)
        val sig = "$dur|$lines|$ck|$fp"
        /* 3 秒内同一指纹算重复（页面每 0.4 秒扫一次）；过了窗口就认它是新一次接管 */
        bridgeLastPlaySig = sig
        bridgeLastPlaySigAt = android.os.SystemClock.uptimeMillis()
        bridgePlayable = obj
        android.util.Log.i(
            "B0BEmbyVR",
            "网页可播地址就绪：" + streams.length() + " 档（" + lines + "），时长 " + dur +
                "s，登录票 " + ck + "，页面 " + obj.optString("frame").take(60) +
                /*
                 * 【2026-10-17 00:16】父亲要问的是"抓的这一份是不是我选的那一季那一集"，
                 * 所以把扩展带过来的季号/集号/片子编号一并打出来：
                 * 拿它对上页面地址里的集号，就能判断抓的是不是当前这一集。
                 */
                "，季 " + obj.optString("season", "?") +
                "，集 " + obj.optString("ep", "?") +
                "，编号 " + obj.optString("cid", "?") +
                "，档名 " + obj.optString("slotName", "?") +
                "，片名「" + obj.optString("title", "").take(40) + "」" +
                "，首条地址尾 " + fp.takeLast(40),
        )
        /*
         * 自动接管（2026-10-16，父亲定的方向）：抓到地址就交给我们自己的播放器。
         * 同一次播放只交一次 —— sig 变了才走到这里，页面反复请求 playurl 不会反复切。
         * cookie 一并交给客户端（会员片要拿它才播得动高清流）。
         */
        if (bridgeCookie.isNotBlank()) {
            try {
                obj.put("cookie", bridgeCookie)
            } catch (e: Exception) {
            }
        }
        /*
         * 【2026-10-24 爱奇艺：清单先经本机转发，再交给内核】
         *
         * 清单里每段都会 302 跳到不同 CDN 节点，mpv 复用长连接后全段失败（实测）。
         * 这里起本机转发服务，把 streams[0].video 换成 127.0.0.1 的清单地址。
         */
        val s0 = streams.optJSONObject(0)
        val pl = s0?.optString("playlist", "") ?: ""
        if (pl.isNotBlank()) {
            val srv = iqProxy ?: com.xxxx.emby_vr.web.IqProxyServer().also { iqProxy = it }
            val local = runCatching { srv.start(pl) }.getOrNull()
            if (local.isNullOrBlank()) {
                android.util.Log.w("B0BEmbyVR", "爱奇艺：本机转发没起来，这次不交")
                return
            }
            runCatching { s0.put("video", local) }
            android.util.Log.i(
                "B0BEmbyVR",
                "爱奇艺：清单 " + pl.length + " 字符 → 本机转发 " + local,
            )
        }
        streamSink?.invoke(obj)
    }

    /**
     * 扩展攒到的一批播放分片（2026-10-18）。
     *
     * 只做两件事：把登录票附上（会员片的分片也要票），再转给界面那一侧去接播放列表。
     * 这条**不是**"可播地址"，所以不打那套档位日志，也不参与换源去重。
     */
    private fun onBridgeFrags(obj: org.json.JSONObject) {
        if (bridgeCookie.isNotBlank()) {
            try {
                obj.put("cookie", bridgeCookie)
            } catch (e: Exception) {
            }
        }
        fragSink?.invoke(obj)
    }

    /**
     * 整集分片清单（2026-10-20 父亲定：「把清单地址原样打出来」那条思路的落地）。
     *
     * 扩展在播放信息响应里一次就能看到整集全部分片（头显实测 4K 共 285 段，每段自带签名），
     * 所以不必等页面一段段拉完。这里把那份标准播放列表写到 cacheDir，再把文件交给内核：
     * mpv 打开它就是一个普通 HLS，整集时长、拖动、逐段取流全由内核自己处理。
     * 文件名里带上档位与段号范围 —— 客户端的换源去重是按地址尾部判的，文件名不同才算新内容。
     */
    /**
     * 立刻播放这一份（不走接收槽）。
     *
     * 爱奇艺的片单是我们自己在"真全屏"处理里签出来的，而那次跳变已经被这次处理消耗掉；
     * 再丢进接收槽就永远等不到下一次跳变 —— 表现为"签成功、也交付了，画面却不动"。
     */
    fun playNow(obj: org.json.JSONObject) {
        onBridgeHls(obj)
    }

    private fun onBridgeHls(obj: org.json.JSONObject) {
        val text = obj.optString("text")
        if (text.isBlank()) return
        val c = appCtx ?: run {
            android.util.Log.w("B0BEmbyVR", "整集清单：拿不到上下文，放弃")
            return
        }
        android.util.Log.i(
            "B0BEmbyVR",
            "整集清单到达：档 f" + obj.optString("tier") + "、" + obj.optInt("segs", 0) +
                " 段、" + obj.optInt("height", 0) + "p、文本 " + text.length + " 字符",
        )
        /* 内容特征：换集/换档必须让去重判据看到不同的地址，所以把签名挂在查询串上 */
        /*
         * 【2026-10-22 12:34 换集接管不了的真凶：去重签名撞车】
         *
         * 本机片单地址是 `…/list.m3u8?sig=<档>-<段数>-<首段>`，同一档、同样段数（这部是 700）
         * 的另一集算出来的地址**一模一样**；而 onBridgePlayable 的去重是"地址末 80 字符相同就不起播"
         * → 换集那次被当成重复丢掉（日志停在「整集清单就绪」后面什么都没有）。
         * 把片号（epKey）并进签名，换一集就必然不同。
         */
        val sig = obj.optString("tier", "x") + "-" + obj.optInt("segs", 0) + "-" + obj.optInt("first", 0) +
            "-" + obj.optString("epKey", "")
        /*
         * 【2026-10-22 10:10 实测翻案：文本片单必须走**本机服务**，不能给 file://】
         *
         * 父 07:45 问「为什么不起小服务、直接把文件给内核」，我改了 file-first，实测两条路都不行：
         *   ① 不强制解复用器：mpv 的"播放列表"解复用器抢走，只播一段就循环（4 秒）；
         *   ② 强制 `demuxer-lavf-format=hls`：`lavfFound 'hls' … size=0 (forced)` 之后
         *      `avformat_open_input() failed` → 「unrecognized file format」→ 进度条 0、放不出来。
         * 结论：ffmpeg 的 hls 解复用器要的是**URL 语义**（它要按基址拼分片），本地文件不给这套上下文。
         * 所以文本片单仍然交本机服务（127.0.0.1），落盘只作兜底。
         */
        var target = ""
        /*
         * 【2026-10-24 爱奇艺必须走转发，腾讯不需要】
         *
         * 腾讯的分片地址自带 token、内核直接能取，所以走 PlaylistServer 只吐文本即可。
         * 爱奇艺的清单里每段都会 302 跳到不同 CDN 节点，mpv 复用长连接后全段失败
         * （实测 ffmpeg 加 -http_persistent 0 才能播，mpv 没有这个开关）→ 必须经 IqProxyServer 逐段转发。
         */
        val needIqProxy = text.contains("iqiyi.com") || text.contains("71edge.com")
        if (needIqProxy) {
            val iqSrv = iqProxy ?: com.xxxx.emby_vr.web.IqProxyServer().also { iqProxy = it }
            val local = runCatching { iqSrv.start(text) }.getOrNull()
            if (local.isNullOrBlank()) {
                android.util.Log.w("B0BEmbyVR", "整集清单：爱奇艺转发没起来，放弃")
                return
            }
            android.util.Log.i("B0BEmbyVR", "整集清单：爱奇艺 → 本机转发 " + local)
            if (target.isBlank()) target = local
            val payload = org.json.JSONObject()
            payload.put("kind", "play")
            payload.put("frame", obj.optString("frame"))
            payload.put("title", obj.optString("title"))
            payload.put("ep", obj.optString("epKey"))
            payload.put("pid", obj.optString("epKey"))
            payload.put("durSec", obj.optInt("durSec"))
            payload.put("quality", "1080P")
            val st = org.json.JSONArray()
            st.put(
                org.json.JSONObject().apply {
                    put("id", 0)
                    put("video", local)
                    put("audio", "")
                    put("height", obj.optInt("height", 0))
                    put("width", obj.optInt("width", 0))
                    put("bw", 0)
                    put("vcodec", "h264")
                },
            )
            payload.put("streams", st)
            /* 爱奇艺不走接收槽：签完直接开播（父亲 2026-10-25 23:36 定）。
               放进槽里等下一次"非全屏→真全屏"跳变，可那次跳变已经被签名这一段用掉了，
               槽里这份永远没人取 —— 表现就是"转发就绪了，画面不动"。 */
            onBridgePlayable(payload)
            return
        }
        val srv = hlsServer ?: com.xxxx.emby_vr.web.PlaylistServer().also { hlsServer = it }
        runCatching {
            val base = srv.start(text)
            if (!base.isNullOrBlank()) target = "$base?sig=$sig"
        }
        if (target.isNotBlank()) {
            android.util.Log.i("B0BEmbyVR", "整集清单交本机服务 → " + target)
        } else {
            val f = runCatching {
                val dir = java.io.File(c.cacheDir, "vr-hls").apply { mkdirs() }
                /* 【2026-10-22】兜底落盘只留最近 2 份，免得一集一个文件把缓存目录堆满 */
                runCatching {
                    dir.listFiles { x -> x.name.startsWith("list-") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(2)
                        ?.forEach { it.delete() }
                }
                java.io.File(dir, "list-$sig.m3u8").also { it.writeText(text) }
            }.getOrNull()
            if (f != null) {
                target = "file://" + f.absolutePath
                android.util.Log.w("B0BEmbyVR", "整集清单：本机服务起不来，改走文件 " + f.absolutePath)
            }
        }
        if (target.isBlank()) {
            android.util.Log.w("B0BEmbyVR", "整集清单：落盘与本机服务都失败，放弃")
            return
        }
        android.util.Log.i(
            "B0BEmbyVR",
            "整集清单就绪（" + sig + "，" + text.length + " 字符）→ " + target,
        )
        val play = org.json.JSONObject()
        play.put("kind", "play")
        play.put("frame", obj.optString("frame"))
        play.put("title", obj.optString("title"))
        play.put("durSec", 0)
        val streams = org.json.JSONArray()
        val one = org.json.JSONObject()
        one.put("video", target)
        one.put("height", obj.optInt("height", 2160))
        one.put("bw", 0)
        one.put("audio", "")
        streams.put(one)
        play.put("streams", streams)
        onBridgePlayable(play)
    }

    /** 扩展报上来的数据；页面里有几个框架就报几份，谁的面积大由 ticker 挑 */
    /**
     * 【2026-10-20 17:18 新内核的脚本回传入口】
     *
     * 火狐那套是扩展的 nativeMessaging 把数据送进来；火狐拆掉后，页面脚本由我们直接注入，
     * 它用 window.postMessage({__b0b:1, payload:...}) 向外发，注入的转接头把 payload 拼成
     * 字符串调这里。内容格式与原来扩展通道完全一致，所以直接复用 onBridgeMessage。
     */
    fun acceptFromPage(json: String): String {
        return runCatching {
            val o = org.json.JSONObject(json)
            /* 打一行就够：能看出"页面确实交上来了什么"，没这行就是没接上 */
            android.util.Log.i(
                "B0BEmbyVR",
                "页面交来一条：kind=${o.optString("kind")} 长度=${json.length}",
            )
            onBridgeMessage(o)
            "ok"
        }.getOrElse {
            android.util.Log.w("B0BEmbyVR", "页面回传解析失败：${it.message}")
            "err"
        }
    }

    /** 爱奇艺本机转发服务（2026-10-24 起用；腾讯那份清单不需要它） */
    private var iqProxy: com.xxxx.emby_vr.web.IqProxyServer? = null


    private fun onBridgeMessage(message: Any?) {
        val obj = message as? org.json.JSONObject ?: return
        if (obj.optString("kind") == "hls") {
            takeSlot(obj, "hls")
            return
        }
        if (obj.optString("kind") == "play") {
            /*
             * 提前通报（2026-10-19 父亲定）：带 preview=1 的那条只填信息、不起播，
             * 也不进下面那条去重表 —— 否则真正的"起播"会被它顶掉。
             */
            if (obj.optInt("preview", 0) == 1) {
                previewSink?.invoke(obj)
                return
            }
            takeSlot(obj, "play")
            return
        }
        if (obj.optString("kind") == "frags") {
            if (obj.optString("frame").isNotBlank()) bridgeLastFrame = obj.optString("frame")
            val ck0 = obj.optString("cookie")
            if (ck0.isNotBlank()) bridgeCookie = ck0
            onBridgeFrags(obj)
            return
        }
        if (obj.optString("kind") != "rect") return
        /* 内容脚本随每条消息附上的本站 cookie（会员片要拿它才换得到高清地址） */
        val ck = obj.optString("cookie")
        if (ck.isNotBlank()) bridgeCookie = ck
        val frame = obj.optString("frame")
        if (frame.isNotBlank()) bridgeLastFrame = frame
        /*
         * 【2026-10-17 16:39 父亲定稿：把走过的页登记成合成历史栈（放 Hub 上）】
         * 接管时网页面销毁重建，真实历史断了；凭 rect 每跳带回来的页面地址登记，
         * 连续相同只记一次、上限 24 条、换站清栈 —— B 键按这个栈一路回退到首页。
         */
        if (frame.isNotBlank()) com.xxxx.emby_vr.web.WebPageHub.pushNav(frame)
        val rect = obj.optJSONObject("rect")
        if (rect != null) {
            bridgeRects[frame] = floatArrayOf(
                rect.optDouble("x", 0.0).toFloat(),
                rect.optDouble("y", 0.0).toFloat(),
                rect.optDouble("w", 1.0).toFloat(),
                rect.optDouble("h", 1.0).toFloat(),
                rect.optDouble("ar", 0.0).toFloat(),
                rect.optDouble("area", 0.0).toFloat(),
                android.os.SystemClock.uptimeMillis().toFloat(),
            )
        } else {
            bridgeRects.remove(frame)
        }
        val urls = obj.optJSONArray("urls") ?: return
        for (i in 0 until urls.length()) {
            val u = urls.optString(i)
            if (u.isNotEmpty() && bridgeLoggedUrls.add(u)) {
                android.util.Log.i("B0BEmbyVR", "网页播放地址候选：$u")
            }
        }
    }

    /** 每 500ms 挑一份"最新鲜且面积最大"的画面报给原生；都没有就报"按整张来" */
    private fun startBridgeTicker() {
        if (bridgeTicker != null) return
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        bridgeTicker = h
        val run = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                var best: FloatArray? = null
                for ((frame, v) in bridgeRects) {
                    if (now - v[6].toLong() > 1500L) {
                        bridgeRects.remove(frame)
                        continue
                    }
                    if (best == null || v[5] > best!![5]) best = v
                }
                /*
                 * 银幕不再跟随网页画面比例（父亲 2026-10-16 08:09 明确）：
                 * 「既然抓播放地址用自己播放器播放，那网页显示就不用变比例」。
                 *
                 * 于是网页彻底回到"选片器"的角色：银幕固定不动，网页怎么排版、有没有黑边都不影响。
                 * 真正要按影片比例铺满墙的是我们自己的播放器那条路（mpv 的画面比例），与这里无关。
                 * 这里只保留两件事：把旧值归零一次；把看到的数据记进日志供排查。
                 */
                if (!bridgeAspectCleared) {
                    bridgeAspectCleared = true
                    /* 按实际比例裁剪，不拉伸（2026-10-25 父亲报：网页视频铺满银幕时直接拉伸） */
                    com.xxxx.emby_vr.vr.VrNative.setWebVideoRect(0f, 0f, 1f, 1f, 0f)  /* 0 = 不裁剪，等扫描到实际比例 */
                    android.util.Log.i(
                        "B0BEmbyVR",
                        "网页显示等扫描到实际比例再裁剪（不拉伸）",
                    )
                }
                val b = best
                val target = if (b != null && b[4] > 0.1f) b[4] else 0f
                /*
                 * 稳定闸（2026-10-16 父亲实测：长宽比一直不停变）。
                 * 页面上同时有好几个视频元素（正片 / 推荐位预览 / 广告），扫描每次挑中的对象可能
                 * 来回换；主视频切清晰度时 videoWidth 也会变。所以新比例必须连续三次（1.5 秒）
                 * 一致才采用，否则继续用上一次的 —— 肉眼看到的就是"稳"。
                 */
                if (kotlin.math.abs(target - bridgeApplied) <= 0.05f) {
                    bridgeHits = 0
                } else {
                    if (kotlin.math.abs(target - bridgeCandidate) <= 0.05f) {
                        bridgeHits++
                    } else {
                        bridgeCandidate = target
                        bridgeHits = 1
                    }
                    /*
                     * 进入（开始放片）确认 3 次够快（1.5 秒）；退出（画面消失）要等 16 次（8 秒）——
                     * 浏览页面、切集、缓冲时页面上的视频元素会消失又出现，
                     * 退出太快银幕就会跟着缩来缩去（父亲 2026-10-16 实测）。
                     */
                    val need = if (target > 0.1f) 3 else 16
                    if (bridgeHits >= need) {
                        bridgeHits = 0
                        bridgeApplied = target
                        if (b != null && b[4] > 0.1f) {
                            android.util.Log.i(
                                "B0BEmbyVR",
                                "网页视频画面：比例 " + "%.3f".format(b[4]) +
                                    "，画面块 " + "%.3f".format(b[2]) + "x" + "%.3f".format(b[3]) +
                                    "，位置 " + "%.3f".format(b[0]) + "," + "%.3f".format(b[1]),
                            )
                            /* 下发的必须是页面里那块视频自己的矩形（x/y/宽/高 + 画幅比）：
                               渲染侧按这个矩形做纹理裁剪，只把视频那一块贴到银幕上。
                               传整张 0,0,1,1 等于不裁，画面就被拉成银幕比例（父亲 2026-10-25 报）。 */
                            com.xxxx.emby_vr.vr.VrNative.setWebVideoRect(b[0], b[1], b[2], b[3], b[4])
                        } else {
                            android.util.Log.i("B0BEmbyVR", "网页视频画面：没有了（银幕本来就不跟着变）")
                        }
                    }
                }
                h.postDelayed(this, 500L)
            }
        }
        h.postDelayed(run, 500L)
    }

    /** 网页层收摊：停掉上报，并把"画面"清回整张 */
    fun stopBridge() {
        bridgeRects.clear()
        bridgeTicker?.removeCallbacksAndMessages(null)
        bridgeTicker = null
        bridgeInstalled = false
        com.xxxx.emby_vr.vr.VrNative.setWebVideoRect(0f, 0f, 1f, 1f, 0f)
    }

    /**
     * 上次清腾讯站数据的时刻（毫秒）。
     *
     * 这里**不能**做成"进程内只清一次"：run 535 清完当次是好的，但父亲重新访问腾讯时又黑了
     * （2026-10-15 13:57 实测：SW 报错 50 条、预检 100 条）—— 腾讯页面**每次访问都会重新注册**
     * 那个 Service Worker，所以每次进网页栏都得清一遍。时间戳只为挡 Compose 的重复重组。
     */
    /*
     * 清腾讯站数据的三段（wipeFlags / wipeTencentOnce / wipeBeforeTencent）原本用的是
     * 火狐内核的 StorageController，随火狐一起删掉（2026-10-20）。
     * 新内核改用系统的网页 cookie/存储接口清，见下面 wipeTencentNow()。
     */

    private var lastWipeMs = 0L

    /**
     * 清掉腾讯站的网页数据（2026-10-20 换成 Chromium 后的实现）。
     *
     * 为什么还要清：腾讯页面注册的后台服务（Service Worker）会持久化在内核的存储里，
     * 残留注册会拦脚本请求 —— 2026-10-14 那次黑屏就是它。
     * 新内核没有"按域名清"的公开接口，这里清整个网页存储；代价是各站登录态会一起清掉，
     * 所以只在 <web_wipe>=1 时做一次（默认关）。
     */
    fun wipeTencentNow(ctx: android.content.Context) {
        if (!com.xxxx.emby_vr.vr.VrTuning.webWipeNow(ctx)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastWipeMs < 8000L) return
        lastWipeMs = now
        runCatching {
            org.chromium.android_crwebview.webkit.CookieManager.getInstance().removeAllCookies(null)
            org.chromium.android_crwebview.webkit.WebStorage.getInstance().deleteAllData()
            android.util.Log.i("B0BEmbyVR", "已清网页存储与 cookie（去掉残留的后台服务注册）")
        }.onFailure {
            android.util.Log.w("B0BEmbyVR", "清网页数据失败：${it.message}")
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
/**
 * 火狐内核的网页块（2026-10-14 父亲定）。
 *
 * 为什么要它：头显系统自带的网页视图停在 Chromium 90，B站这类重页面渲染慢；
 * GeckoView 是把火狐的内核当依赖打进应用，内核版本跟着 Mozilla 走，不受系统限制。
 *
 * 这一版只负责「把页面显示出来 + 报告加载状态」，点击/悬停/滚动在下一版接上。
 */
@Composable
private fun WebPageForSite(site: Int, startUrl: String? = null) {
    val context = LocalContext.current
    /*
     * 三个站点全部走**电脑版**网页（父亲 2026-10-13 定：腾讯也从手机版改成电脑版）。
     *
     * 之前腾讯走 m.v.qq.com + 手机浏览器标识（实测能播），现在换成电脑版
     * v.qq.com + 电脑浏览器标识，与 B站 / 爱奇艺同一套口径。
     * 手机宽度那套上下文（phoneWidthContext）随之没有调用方，按父亲"不留死代码"
     * 的要求一并删掉。
     */
    /*
     * 按站点给身份（2026-10-15 父亲实测）。
     *
     * 两家要求正好相反，一个全局身份必然得罪其中一家：
     *  · 腾讯：内核是火狐，若对外自称 Chrome，它的接口看到"自称 Chrome 却没有
     *    Chrome 的客户端提示头"就不返回数据 → 播放页只剩框架和灰条。所以腾讯必须
     *    自称火狐（与真实内核一致）。
     *  · 爱奇艺：直接要求 Chrome，自称火狐会被提示换浏览器。
     * B站不挑，跟随 <web_ua> 那个开关（切完重启应用生效）。
     */
    val firefoxUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"
    val chromeUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    val desktopUa = when (site) {
        1 -> firefoxUa                       // 腾讯
        2 -> chromeUa                        // 爱奇艺
        else -> if (com.xxxx.emby_vr.vr.VrTuning.webUaMode == 1) firefoxUa else chromeUa
    }
    /*
     * 诊断用（2026-10-15）：调参文件里给了 <web_url> 就加载它（本地测试页），
     * 否则用三个站点的默认电脑版首页。换试验页面不必重编 APK。
     */
    val url = startUrl?.takeIf { it.isNotBlank() }
        ?: com.xxxx.emby_vr.vr.VrTuning.webUrl.takeIf { it.isNotEmpty() }
        ?: when (site) {
            0 -> "https://www.bilibili.com/"
            1 -> "https://v.qq.com/"
            else -> "https://www.iqiyi.com/"
        }
    val title = when (site) {
        0 -> "哔哩哔哩"
        1 -> "腾讯视频"
        else -> "爱奇艺"
    }

    var webRef by remember { mutableStateOf<WebView?>(null) }
    /* 换站清栈（16:39 定稿的另一半）: navSite 不是本站就清掉上一站的路线 */
    LaunchedEffect(site) {
        com.xxxx.emby_vr.web.WebPageHub.navResetFor(site)
        /* 换站：上一站的 Cookie / 页面地址 / 去重指纹一律作废（2026-10-22） */
        WebRuntime.resetForSite()
    }
    /* 火狐内核那两个（2026-10-14）：view 用来发事件，session 用来加载与前进后退 */
    /* 火狐内核已拆（2026-10-20），原来这两个引用不再需要 */
    /*
     * 网页内核做成可实时切换（2026-10-14）：VrTuning 不是 Compose 状态，
     * 直接读它切不动 —— 这里每秒对一次，文件里改了，网页块立刻按新引擎重建。
     */
    var webEngine by remember { mutableStateOf(com.xxxx.emby_vr.vr.VrTuning.webEngine) }
    LaunchedEffect(Unit) {
        while (true) {
            val e = com.xxxx.emby_vr.vr.VrTuning.webEngine
            if (e != webEngine) webEngine = e
            kotlinx.coroutines.delay(1000L)
        }
    }

    /*
     * 每 250ms 问一次"网页里那块视频画面在哪、宽高多少"，报给原生（父亲 2026-10-15）。
     *
     * 银幕据此按视频真实比例变宽、并且只采那块画面 —— 网页视频也能像 Emby 播片那样用足墙宽。
     * 没在播、页面还没好、读失败，一律报"没有"，原生退回整张画布 + 原比例的老路。
     */
    LaunchedEffect(Unit) {
        while (true) {
            val js = VIDEO_RECT_JS
            run {
                val w = webRef
                if (w == null) {
                    pushVideoRect(null)
                } else {
                    w.evaluateJavascript(js) { r -> pushVideoRect(parseVideoRect(r)) }
                    /*
                     * 【2026-10-20 18:12 取件：页面把要交的数据攒在收件箱里，这里定时来取】
                     *
                     * 系统的 JS 接口（addJavascriptInterface）在这套内核里没接上，
                     * 页面交上来的地址一条都没到过原生。改成"页面放、原生取"：
                     * 脚本把每条数据塞进 window.__b0bOutbox，这里每 250ms 取空一次。
                     * 取件用的 evaluateJavascript 是已经在跑的通路（视频矩形就靠它），不依赖新接口。
                     */
                    /*
                     * 运行时应用 `web_nocache`（2026-10-21）：改完不用重装，下一拍就生效。
                     */
                    runCatching {
                        val want = com.xxxx.emby_vr.vr.VrTuning.webNoCache
                        val mode = if (want == 1)
                            org.chromium.android_crwebview.webkit.WebSettings.LOAD_NO_CACHE
                        else
                            org.chromium.android_crwebview.webkit.WebSettings.LOAD_DEFAULT
                        if (com.xxxx.emby_vr.vr.VrTuning.webCacheApplied != want) {
                            w.settings.cacheMode = mode
                            com.xxxx.emby_vr.vr.VrTuning.webCacheApplied = want
                            android.util.Log.i(
                                "B0BEmbyVR",
                                "网页缓存：" + if (want == 1) "已禁用（每次走网络）" else "恢复正常",
                            )
                        }
                    }
                    w.evaluateJavascript(B0B_TAKE_OUTBOX_JS) { r ->
                        if (r != null && r.length > 4 && r != "[]") {
                            runCatching {
                                val arr = org.json.JSONArray(
                                    if (r.startsWith("\"") && r.endsWith("\"")) {
                                        org.json.JSONTokener(r).nextValue() as String
                                    } else r,
                                )
                                for (i in 0 until arr.length()) {
                                    WebRuntime.acceptFromPage(arr.getString(i))
                                }
                            }.onFailure {
                                android.util.Log.w("B0BEmbyVR", "取件解析失败：${it.message}")
                            }
                        }
                    }
                }
            }
            kotlinx.coroutines.delay(250L)
        }
    }
    /* 网页要"全屏"时，它把自己的播放视图塞进这个容器里（腾讯的「全屏」按钮走这条路） */
    var fullscreen by remember { mutableStateOf(false) }
    var customContainer by remember { mutableStateOf<android.widget.FrameLayout?>(null) }
    var customViewCallback by remember { mutableStateOf<org.chromium.android_crwebview.webkit.WebChromeClient.CustomViewCallback?>(null) }
    /*
     * 全屏时网页塞过来的那个播放视图（父亲 2026-10-13 实测：全屏后点不到东西）。
     * 它被我们从 WebView 里摘出来挂到自己的容器上 —— 事件必须发给它，
     * 发给 WebView 等于发给一块不再显示内容的控件，点了什么都不会发生。
     */
    var customView by remember { mutableStateOf<android.view.View?>(null) }
    /* 网页这块矩形在面板画布里的位置（面板像素），用来换算光柱落点 */
    var webRect by remember { mutableStateOf<Rect?>(null) }
    /*
     * 加载态与失败态（父亲 2026-10-13：网页打开过程中「可以有个正在加载」，
     * 加载不出来要提示「请检查网络连接」）。
     *
     * 这两个状态只驱动面板正中一行提示字：不加底色、不登记点击
     * （登记了就会在光柱那张坐标表里跟网页抢同一块矩形），
     * 也不是"贴一层盖住网页"的蒙版 —— 网页照常铺满整块面板，
     * 加载完（或重新开始加载）提示立刻从界面上撤掉。
     */
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    /*
     * 悬停与快进的节流器（父亲 2026-10-13）。
     *
     * 悬停每帧都发会把网页拖慢（网页要为每个 mousemove 做命中测试与重绘），
     * 挪动不足 3 像素不发；快进每 250ms 最多一次，避免摇杆一推就跳出去十几分钟。
     * 用数组装是为了让闭包能改到同一个值（普通 var 会被 lambda 捕获成副本语义更绕）。
     */
    /*
     * 光柱当前是否悬在大银幕（网页）上（2026-10-15 父亲提）。
     *
     * 只在 hoverHandler 被原生推坐标时置真、推来"离开"哨兵时置假。
     * B 键据此判断"算不算网页内返回" —— 光柱飘在别处时不该让网页后退。
     */
    val hoverOnScreen = remember { booleanArrayOf(false) }
    val lastHoverX = remember { floatArrayOf(-9999f) }
    /* 2026-10-21：悬停坐标采样节流（诊断用，定位"银幕落点 → 网页像素"换算是否对） */
    val lastHoverLogAt = remember { longArrayOf(0L) }
    /* 2026-10-21：拦截诊断日志节流（判断能否看到网页请求） */
    val lastIqLogAt = remember { longArrayOf(0L) }
    val lastHoverY = remember { floatArrayOf(-9999f) }
    val lastArrowAt = remember { longArrayOf(0L) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0C0F))) {
        /*
         * 【2026-10-20 16:16 父亲要求：拆掉火狐与系统内核，只留打进应用的 Chromium 132】
         *
         * 原来这里是两套内核二选一（web_engine 开关选火狐或系统网页视图）。
         * 火狐那套连同依赖一起删掉，网页只走打进应用的 Chromium 132（crwebview）。
         * 开关字段保留但不再影响内核选择，只为兼容设备上已有的配置行。
         */
        AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        val p = coords.positionInRoot()
                        webRect = Rect(
                            p.x,
                            p.y,
                            p.x + coords.size.width,
                            p.y + coords.size.height,
                        )
                    },
                factory = { ctx ->
                    /* 三站都是电脑版：用原上下文，站点给的就是电脑排版（父亲 2026-10-13 要的） */
                    WebView(ctx).apply {
                        /*
                         * 【2026-10-20 16:44 手柄失效的根因：视图引用没早记下来】
                         *
                         * 原来只在 AndroidView 的 update 回调里给 webRef 赋值，而 update 在首次
                         * 组合时并不可靠地调用 —— 火狐那版有个 geckoRef 当中间层掩盖了这件事，
                         * 火狐拆掉后只剩 webRef，它仍是 null，于是点击/悬停/滚动/后退四个入口
                         * 第一句就是 `webRef ?: return`，整块手柄操作全部静默失效。
                         * 在创建（factory）当场就记下，后面所有投递才有对象。
                         */
                        webRef = this
                        /*
                         * 【2026-10-20 17:22 页面 → 原生 的回传口】
                         *
                         * 火狐那套是扩展的 nativeMessaging；换 Chromium 后没有扩展，
                         * 改用网页视图自带的 JS 接口：页面脚本调 window.b0bHost.post(...)。
                         */
                        addJavascriptInterface(WebPageBridge(), "b0bHost")
                        setBackgroundColor(AndroidColor.BLACK)
                        /*
                         * 【2026-10-20 19:46 开调试口（父亲报爱奇艺内容区全黑）】
                         *
                         * 三家站里只有爱奇艺内容区渲染不出来，本机同视口同身份却正常 ——
                         * 光看截图分不清是"页面元素没生成"还是"生成了但没画"。
                         * 打开网页调试口，.18 上可以直接连进头显里这个网页看 DOM 与报错，
                         * 一次定位，不用父亲反复试。发布版留着也无害（只在 adb 可达时用）。
                         */
                        WebView.setWebContentsDebuggingEnabled(true)
                        /*
                         * 【2026-10-20 20:18 爱奇艺内容区全黑的头号嫌疑：跨子域 cookie 被拦】
                         *
                         * 实机日志里它自己的接口被跨域策略挡掉：
                         *   Access to fetch at 'https://mesh.if.iqiyi.com/portal/lw/v5/channel/recommend…'
                         *   from origin 'https://www.iqiyi.com' has been blocked by CORS policy
                         * 而内容区就是靠这个子域上的接口取数据的 —— 被挡 → 内容区空。
                         * 本机同页面、同样注入我们的脚本都正常，差别只剩内核的 cookie 策略：
                         * 网页视图默认**不接受第三方（跨子域）cookie**，那几个接口就带不上登录态。
                         */
                        org.chromium.android_crwebview.webkit.CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        /* 网页播放器默认要"用户手势"才播，这里放开（父亲 2026-10-13） */
                        settings.mediaPlaybackRequiresUserGesture = false
                        /*
                         * http 资源放行（2026-10-13 实测抓到）：这些站点的封面/接口还有走 http 的，
                         * 默认策略全拦掉（日志里一片"图片加载失败"），页面就残了。
                         */
                        settings.mixedContentMode =
                            org.chromium.android_crwebview.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        /*
                         * 让站点自己的 viewport 生效；页面比屏幕宽时整体缩到屏幕宽度（看得全）。
                         */
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.setSupportZoom(false)
                        /*
                         * 【2026-10-21 12:50 爱奇艺整集片单截不到：正片取流请求被缓存住没发出去】
                         * 截到的那条是广告（只有 15 段）。戴着把 vr-tuning 的 `web_nocache` 改 1，
                         * 页面每次请求都走网络，正片的 dash 就会真发一次，我们才能截到整集片单。
                         */
                        if (com.xxxx.emby_vr.vr.VrTuning.webNoCache == 1) {
                            settings.cacheMode =
                                org.chromium.android_crwebview.webkit.WebSettings.LOAD_NO_CACHE
                            android.util.Log.i("B0BEmbyVR", "网页缓存：已按 web_nocache=1 禁用")
                        }
                        settings.builtInZoomControls = false
                        /*
                         * 不许网页自己弹新窗口（弹出来就是个空白窗，画面留在原地不动）。
                         * 配合下面的 onCreateWindow 一起挡。
                         */
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = desktopUa
                        webViewClient = object : WebViewClient() {
                            /*
                             * 【2026-10-21 12:40 爱奇艺整集片单：在应用侧网络层截】
                             *
                             * 取流接口发在播放器后台线程里，页面脚本看不到（performance 0 条、挂钩也抓不到），
                             * 但内核的 shouldInterceptRequest 能看到这个 WebView 的全部请求。
                             * 截到之后自己带 cookie/UA/Referer 重拉一遍（内容一致，原样回给页面），
                             * 顺手把整集 m3u8 抠出来存好；父亲按"真正的全屏"时把整张片单交给内核。
                             */

                            /*
                             * 拦掉"打开APP"这类跳转（父亲 2026-10-13）：
                             * 页面点视频或横幅时会甩出 bilibili:// iqiyi:// qiyi-iphone:// 这些自家协议，
                             * 我们没装那些 App，系统接不住，页面就卡在原地不动 —— 直接吃掉。
                             */
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: org.chromium.android_crwebview.webkit.WebResourceRequest,
                            ): Boolean {
                                val u = request.url.toString()
                                if (u.startsWith("http://") || u.startsWith("https://")) return false
                                android.util.Log.i("WebLib", "拦下 App 跳转 $u")
                                return true
                            }

                            /*
                             * 每开始一次加载就把状态复位回「正在加载」（父亲 2026-10-13）。
                             * 失败后用户点别的站点、或在网页里点链接进下一页，都从这里回到加载态，
                             * 不会把上一次的「请检查网络连接」留在画面上。
                             */
                            override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) {
                                loading = true
                                loadFailed = false
                                /*
                                 * 【2026-10-20 17:22 每次开页都把抓取脚本注进去】
                                 *
                                 * 火狐时代这些脚本由扩展自动注入（main.js 在页面世界、isolated.js 在
                                 * 隔离世界）。火狐拆掉后没人注入了 —— 于是「B站/腾讯接管」整条断掉。
                                 * 这里在页面刚开始加载时注入两样东西：
                                 *   ① 转接头：把脚本用 window.postMessage({__b0b:1,...}) 发出的数据
                                 *      转成对原生接口的调用（新内核没有 postMessage 到原生的通道）；
                                 *   ② 页面脚本本体 main.js（量画面、抓播放地址、攒分片）。
                                 */
                                injectPageScripts(view)
                            }

                            override fun onPageFinished(view: WebView, u: String) {
                                loading = false
                                /*
                                 * 打完页面把布局尺寸打进日志（父亲 2026-10-13：
                                 * 爱奇艺"菜单在、内容看不见"）—— 用来看是不是
                                 * 页面比可视区宽/高得多，内容跑到画面外面去了。
                                 */
                                view.evaluateJavascript(
                                    "(function(){var d=document.documentElement;" +
                                        "return [window.innerWidth,window.innerHeight," +
                                        "d.scrollWidth,d.scrollHeight," +
                                        "document.body?document.body.scrollWidth:0].join(',');})()"
                                ) { r -> android.util.Log.i("WebLib", "页面尺寸 $u → $r") }
                                /* "打开APP/下载客户端"横幅飘在页面上挡内容，按文案找出来藏掉 */
                                view.evaluateJavascript(HIDE_APP_BANNER_JS, null)
                                view.postDelayed(
                                    { view.evaluateJavascript(HIDE_APP_BANNER_JS, null) },
                                    1500,
                                )
                            }

                            /*
                             * 打不开就提示「请检查网络连接」（父亲 2026-10-13 定）。
                             *
                             * 只认**主文档**的失败：图片、埋点、广告这些子请求天天有失败的
                             * （这些站点的请求被拦是常态），拿它们当"网页打不开"会误报 ——
                             * 页面明明好好地在那儿，正中却挂着一行"请检查网络连接"。
                             *
                             * ERROR_UNKNOWN 也放过：这一档多半是"我们自己中断或重定向"
                             * （换站点时 stopLoading、点链接跳转），不是网络不通。
                             */
                            override fun onReceivedError(
                                view: WebView,
                                request: org.chromium.android_crwebview.webkit.WebResourceRequest,
                                error: org.chromium.android_crwebview.webkit.WebResourceError,
                            ) {
                                android.util.Log.w(
                                    "WebLib",
                                    "加载失败 ${request.url} : ${error.errorCode} ${error.description}",
                                )
                                if (request.isForMainFrame &&
                                    error.errorCode != WebViewClient.ERROR_UNKNOWN
                                ) {
                                    loadFailed = true
                                }
                            }

                            /* HTTP 4xx / 5xx（页面在、但服务器回绝）：同样是主文档才算整页打不开 */
                            override fun onReceivedHttpError(
                                view: WebView,
                                request: org.chromium.android_crwebview.webkit.WebResourceRequest,
                                errorResponse: org.chromium.android_crwebview.webkit.WebResourceResponse,
                            ) {
                                android.util.Log.w(
                                    "WebLib",
                                    "HTTP ${errorResponse.statusCode} ${request.url}",
                                )
                                if (request.isForMainFrame) loadFailed = true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            /* 网页想开新窗口（target=_blank / window.open）一律拒掉，留在当前页面播 */
                            override fun onCreateWindow(
                                view: WebView,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message,
                            ): Boolean {
                                android.util.Log.i("WebLib", "拦下网页新窗口")
                                return false
                            }

                            /*
                             * 网页要全屏（父亲 2026-10-13：腾讯有两个全屏，「网页全屏」有效、
                             * 「全屏」点了没反应）—— 没接这条通道，点了当然没反应。
                             * 接法：把网页塞过来的播放视图挂到我们自己的容器上，顶栏收起来，
                             * 让它铺满整块面板；退出全屏再把视图还回去。
                             */
                            override fun onShowCustomView(
                                view: android.view.View,
                                callback: CustomViewCallback,
                            ) {
                                val box = customContainer ?: run { callback.onCustomViewHidden(); return }
                                /*
                                 * 上一次的全屏回调没被清掉时，原来直接让这次作废 ——
                                 * 表现就是"按了真全屏不接管，按 B 退出去再来一次才行"
                                 * （父亲 2026-10-26 00:01 报）。这里改成先把上一次收干净再继续。
                                 */
                                if (customViewCallback != null) {
                                    runCatching { customViewCallback?.onCustomViewHidden() }
                                    customViewCallback = null
                                    runCatching {
                                        customView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                                    }
                                    customView = null
                                    fullscreen = false
                                    android.util.Log.i("WebLib", "上一次全屏残留 → 已收干净，接着进这一次")
                                }
                                customViewCallback = callback
                                (view.parent as? android.view.ViewGroup)?.removeView(view)
                                customView = view
                                box.addView(
                                    view,
                                    android.widget.FrameLayout.LayoutParams(
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                                box.visibility = android.view.View.VISIBLE
                                fullscreen = true
                                /* 头显排查用：把"这一刻到底走了哪条路"钉在日志里（2026-10-21 16:12） */
                                android.util.Log.i(
                                    "WebLib",
                                    "真全屏进入：站点=$site 爱奇艺开关=" +
                                        com.xxxx.emby_vr.vr.VrTuning.webIqiyi,
                                )
                                /*
                                 * 【2026-10-21 爱奇艺接管】父亲按"真正的全屏"= 把 1080P 整集地址交给大银幕。
                                 *
                                 * 爱奇艺的取流请求发在播放器后台线程里，页面脚本抓不到，但**分片地址**
                                 * 在页面资源表里读得到；页面侧已把"剥掉字节区间参数的裸地址"准备好，
                                 * 这里一按全屏就让它递交（非爱奇艺站点该函数自己会返回 false）。
                                 * 走的是既有的 kind:'play' 契约，接管逻辑与腾讯/B站同一套。
                                 */
                                /*
                                 * 优先交**整集片单**（网络层截下来的那份，含全部分段）；
                                 * 没截到才退回"当前这一段"的裸地址（只能播几分钟）。
                                 */
                                /*
                                 * 【2026-10-21 13:15 优先级：整集 m3u8 直链 > dash 里解析出的整集片单 > 单段裸地址】
                                 * 直链最省事：mpv 自己按 HLS 播整集、自己拖动（实测 702 段 / 45 分 26 秒，
                                 * 且不带 cookie 也能取）。
                                 */
                                /*
                                 * 【2026-10-21 13:50 先关掉爱奇艺接管】
                                 * 分段取流还回 405（身份/来源问题在查），而失败后 mpv 会自动重试，
                                 * 把播放器卡在重试循环里 —— 连带 B站/腾讯 都像"接管不了"。
                                 * 所以先用开关隔离：`web_iqiyi=1` 才走爱奇艺那条，默认 0。
                                 */
                                /*
                                 * 【2026-10-21 15:15 爱奇艺整集自取（1080P）】
                                 *
                                 * 三步：页面只读片号与身份 → 本地按 vf=md5(路径+盐) 自签 dash → 拿整集片单交内核。
                                 * 实测：bid=600 是 1080P（首段 1920×1080），bid=500 是 720P；
                                 * 片单地址在 pcw-data 上，长期有效（4 小时后仍 200）。
                                 * 只在 `web_iqiyi=1` 时走这条路，且完全不碰 WebView 的请求层。
                                 */
                                /*
                                 * 只在**爱奇艺**站点走这条自取路（2026-10-21 15:40 修）：
                                 * 上一版只看开关，结果 B站/腾讯 也被拦进来、直接 return，
                                 * 它们的接管（页面交地址 + 全屏戳）整段被跳过 —— 父亲实测"接管不过去"。
                                 * 站点编号：0=哔哩哔哩、1=腾讯视频、2=爱奇艺（见 MainActivity.WEB_SITE_NAMES）。
                                 */
                                if (com.xxxx.emby_vr.vr.VrTuning.webIqiyi == 1 && site == 2) {
                                    /*
                                     * 【2026-10-21 15:55 修两处】
                                     * ① 片号提取原来写成 JS 正则（`/qd_tvid=(\\d+)/`），经过 Kotlin 字符串后
                                     *    变成"反斜杠+d"，一条都匹配不到 → 日志里"片号="是空的。
                                     *    改成不用正则：找 `qd_tvid=` 的下标，手工往后收数字。
                                     * ② 设备指纹：头显页面 localStorage 里的 `__dfp` 是空的（.18 有），
                                     *    这里补一个兜底常量（2026-10-21 15:05 抓自浏览器请求里的 dfp）。
                                     */
                                    /*
                                     * 【2026-10-21 17:12 片号改用播放器自己的数据】
                                     *
                                     * r864 实测（头显日志）：`爱奇艺自取：片号= 设备=3319b8e0 指纹=a084c603`
                                     * → 片号还是空，自取必然失败退回 6 分钟裸地址。
                                     * 原因：性能资源表里根本没有 `qd_tvid=`（取流是 MSE，地址不出现在资源表）。
                                     * .18 实测爱奇艺把片号放在播放器对象上：
                                     *   window.QiyiPlayerProphetData.tvid / .tvId / .videoInfo.tvId
                                     * 所以优先级改成：播放器对象 → 全局 tvId → 资源表兜底。
                                     */
                                    val jsAsk = "(function(){try{" +
                                        "var t='';" +
                                        "try{var pd=window.QiyiPlayerProphetData||{};" +
                                        "t=String(pd.tvid||pd.tvId||(pd.videoInfo||{}).tvId||'');}catch(e1){}" +
                                        "if(!t){try{var pl=window.QiyiPlayerLoader||{};" +
                                        "t=String(pl.tvid||'');}catch(e2){}}" +
                                        "if(!t){var rs=performance.getEntriesByType('resource');" +
                                        "for(var i=rs.length-1;i>=0&&!t;i--){var n=rs[i].name||'';" +
                                        "var p=n.indexOf('tvid=');if(p<0)continue;var j=p+5,s='';" +
                                        "while(j<n.length){var c=n.charAt(j);if(c<'0'||c>'9')break;s+=c;j++;}" +
                                        "if(s.length>=8)t=s;}}" +
                                        "var diag={};try{diag.globals=Object.keys(window).filter(function(k){return /qiyi|iqy|player|prophet|__/i.test(k)}).slice(0,25);}catch(e4){}" +
                                        "try{var rsx=performance.getEntriesByType('resource');diag.res=rsx.length;" +
                                        "diag.rsamp=rsx.filter(function(r){return /dash|iqiyi|tvid/i.test(r.name||'');}).slice(0,3).map(function(r){return String(r.name).slice(0,120)});}catch(e5){}" +
                                        "try{diag.ls=Object.keys(localStorage).slice(0,15);}catch(e6){}" +
                                        "try{diag.pdKeys=Object.keys(window.QiyiPlayerProphetData||{}).slice(0,15);}catch(e7){}" +
                                        "var d=(localStorage.getItem('__dfp')||'').split('@')[0];" +
                                        "var en='';try{en=String(((window.QiyiPlayerProphetData||{}).videoInfo||{}).title||'');}catch(e3){}" +
                                        /*
                                         * 【2026-10-21 22:21 修双重编码】
                                         * 原来这里 `return JSON.stringify({...})`：JS 返回的是**字符串**，
                                         * evaluateJavascript 回调再把它当字符串转义一次 →
                                         * 拿到的是 `"{\"tvid\":\"6837360877143400\"…}"`，
                                         * Kotlin 侧 trim('"') 后仍是带转义的 JSON，JSONObject 解析失败 →
                                         * 片号永远是空。r870 诊断日志实证：页面明明给了 6837360877143400。
                                         * 直接返回对象，回调就是干净的 JSON。
                                         */
                                        "var ft='';try{ft=(window.__b0bPageTitle?window.__b0bPageTitle():String(document.title||''));}catch(e8){}" +
                                        "var pgv='';try{var vm=/\\/v_([0-9A-Za-z]+)/.exec(location.pathname||'');if(vm)pgv=vm[1];}catch(e9){}" +
                                        "var sw=0;try{sw=Number(window.__b0bSwitchAt||0)||0;}catch(es){}" +
                                        "return {tvid:t,dfp:d,epTitle:en,fullTitle:ft,pageV:pgv,switchAt:sw,diag:diag};}catch(e){return {};}})()"
                                    webRef?.evaluateJavascript(jsAsk) { raw ->
                                        val kv = runCatching { org.json.JSONObject((raw ?: "").trim('"')) }
                                            .getOrElse { org.json.JSONObject() }
                                        val tvid = kv.optString("tvid")
                                        /* 网址里的 v_ 编号：用来识别"页面还没切到这一集"（2026-10-22 17:29） */
                                        val pageV0 = kv.optString("pageV")
                                        android.util.Log.i("B0BEmbyVR", "爱奇艺页面数据：" + (raw ?: "").take(220))
                                        val iqSw = kv.optLong("switchAt", 0L)
                                        val iqNow = System.currentTimeMillis()
                                        android.util.Log.i(
                                            "B0BEmbyVR",
                                            "◆真全屏 站=iq 时刻=" + iqNow +
                                                " 距切换=" + (if (iqSw > 0) "+" + (iqNow - iqSw) + "ms" else "（页面没记过切换）") +
                                                " 网址=v_" + kv.optString("pageV") +
                                                " 页面片号=" + tvid +
                                                " 页面标题「" + kv.optString("fullTitle").take(30) + "」",
                                        )
                                        /* 这一集的名字：页面播放器对象里的 title（如"恋爱脑被骗顶罪"） */
                                        val pageEpTitle = kv.optString("epTitle")
                                        /* 页面自己拼好的整句（剧名 第X季 第X集 集名），结构化取不到时用它兜底 */
                                        val pageFullTitle = kv.optString("fullTitle")
                                        /*
                                         * 设备指纹兜底：头显页面 localStorage 里 `__dfp` 是空的（.18 有），
                                         * 这里回落到 2026-10-21 15:05 抓到的那个值。
                                         */
                                        val dfp = kv.optString("dfp").ifBlank {
                                            "a084c6039f4f1d4c7da12402b0c503665e7087da9b29304c06de5fdddb79fd744c"
                                        }
                                        val ckAll = runCatching {
                                            org.chromium.android_crwebview.webkit.CookieManager
                                                .getInstance().getCookie("https://www.iqiyi.com/")
                                        }.getOrNull().orEmpty()
                                        val uid = Regex("P00003=([^;]+)").find(ckAll)?.groupValues?.get(1).orEmpty()
                                        val kuid = Regex("QC005=([^;]+)").find(ckAll)?.groupValues?.get(1).orEmpty()
                                        val ua = runCatching { webRef?.settings?.userAgentString }.getOrNull()
                                        val ctx = webRef?.context
                                        /*
                                         * 【2026-10-21 16:15 弹幕要的片号在这里补上】
                                         * 弹幕接口按 tvid 取（cmts.iqiyi.com/bullet/...）。
                                         * 原来这个片号是网络层钩子（shouldInterceptRequest）顺手记的，
                                         * 那个钩子为了不连累 B站/腾讯已经整段删掉 —— 于是新路径下
                                         * `IqiyiDash.tvid` 永远是空，弹幕层判定「没片号」直接不加载。
                                         * 自取这条路本来就把片号抓到了，这里直接回填。
                                         */
                                        if (tvid.isNotBlank()) {
                                            runCatching {
                                                com.xxxx.emby_vr.web.IqiyiDash.noteTvid("tvid=$tvid")
                                            }
                                        }
                                        android.util.Log.i(
                                            "B0BEmbyVR",
                                            "爱奇艺自取：片号=$tvid 设备=" + kuid.take(8) + " 指纹=" + dfp.take(8),
                                        )
                                        Thread {
                                            /*
                                             * 【2026-10-22 17:29 父亲定：拿到地址之后要核对，对不上不交、等新的】
                                             *
                                             * 现场（《蒸蒸日上》17:13）：页面已经切到第 3 集（网址 v_qqcbpladfg），
                                             * 播放器对象里的片号还是第 7 集的 6060062170737200，
                                             * 自取回来的片单是第 7 集（696 段 / 2725 秒，与第 7 集完全一样），
                                             * 标题却按页面文字显示第 3 集 → 控制条第 3 集、大屏第 7 集。
                                             * 父亲判断：网络慢时页面要过几秒才真正切过去，这几秒里取就是错的。
                                             */
                                            /*
                                             * 【2026-10-22 20:20 改：宽限 3 秒，绝不拦死接管】
                                             *
                                             * r912 实测 19:58:50、19:59:27 两次都是：
                                             * 「爱奇艺：页面还没切到这一集（网址 v_xxx 以前对应片号 …）→ 这次不交」
                                             * —— 这就是父亲报的「爱奇艺有时接管不了」：旧配对表一直不放行。
                                             * 现在只保留一条：网址变了、片号还是上一次交付那个 → 最多等 3 秒看片号跟不跟得上；
                                             * 3 秒后不管变没变都照交（页面此刻放的确实就是这个片号那一集）。
                                             */
                                            var useTvid = tvid
                                            var pageV = pageV0
                                            val suspect = useTvid.isNotBlank() && useTvid == IqSlot.tvid &&
                                                pageV.isNotBlank() && pageV != IqSlot.v
                                            if (suspect) {
                                                /*
                                                 * 2026-10-23 00:29 日志实证：点选集 13 后网址已换（v_11wu39wzl6c），
                                                 * 页面片号仍是第 16 集，等 3 秒没跟上就照交 → 交出去的是第 16 集片单
                                                 * （324 段 / 2749 秒），标题却是第 13 集。父亲口径：宁可这次不接管，
                                                 * 也不能播错集。所以：网址已换而片号没变 → 判据是'片号相对切集时刻变过'——不变就一直等（5 分钟兜底）。
                                                 */
                                                android.util.Log.i(
                                                    "B0BEmbyVR",
                                                    "◆等待 站=iq 网址已换（v_" + IqSlot.v + " → v_" + pageV +
                                                        "）但片号还是上一集的 " + useTvid + "，等它真的变（不看时间）",
                                                )
                                                /*
                                                 * 【2026-10-23 11:45 父亲定：只认变化，不做时间兜底】
                                                 * 网址已换而片号没变 → 一直等，直到片号相对基线发生变化（旧→新、无→有）。
                                                 * 唯一的退出条件不是时间，而是"页面读不出身份"连续失败（人可能已经离开）。
                                                 */
                                                var caught = false
                                                /*
                                                 * 先按分集表查一次，别干等页面。
                                                 * 页面切集后片号会滞后，播放器又常吃缓存不重发取流请求，
                                                 * 光等只能等到超时（父亲 2026-10-26 00:30 现场：
                                                 * 等 13 秒仍没跟上，按 B 重进才成功）。
                                                 * 分集表里每集都带自己的片号，按集号直接取，最准。
                                                 */
                                                runCatching {
                                                    var epNo = run {
                                                        val m = java.util.regex.Pattern
                                                            .compile("第\\s*([0-9]{1,4})\\s*[集话期]")
                                                            .matcher(pageFullTitle + " " + pageEpTitle)
                                                        if (m.find()) m.group(1)?.toIntOrNull() ?: 0 else 0
                                                    }
                                                    /* 标题里没有"第N集"就问页面（播放器标题栏那一串） */
                                                    if (epNo <= 0) epNo = askPageEpNo(webRef)
                                                    if (epNo > 0) {
                                                        /* 优先问页面自己那份分集表（已经拉好了，最省事），问不到再自己查 */
                                                        val fromPage = askPageEpTvId(webRef, epNo)
                                                        val album = if (fromPage.isBlank()) askPageAlbum(webRef) else ""
                                                        val fromAlbum = when {
                                                            fromPage.isNotBlank() -> fromPage
                                                            album.isNotBlank() -> com.xxxx.emby_vr.web.IqiyiDash2
                                                                .tvIdByEpisode(album, epNo, ckAll, ua)
                                                            else -> null
                                                        }
                                                        if (!fromAlbum.isNullOrBlank() && fromAlbum != useTvid) {
                                                            useTvid = fromAlbum
                                                            caught = true
                                                            android.util.Log.i(
                                                                "B0BEmbyVR",
                                                                "◆分集表 站=iq 第 " + epNo + " 集片号=" + fromAlbum +
                                                                    "（来源=" + (if (fromPage.isNotBlank()) "页面分集表" else "专辑分集表") +
                                                                    "，不用再等页面片号）",
                                                            )
                                                        } else {
                                                            android.util.Log.i(
                                                                "B0BEmbyVR",
                                                                "◆分集表 站=iq 第 " + epNo + " 集没取到片号（页面=" +
                                                                    (if (fromPage.isBlank()) "空" else fromPage) +
                                                                    " 专辑=" + album.take(20) + "），转去等页面",
                                                            )
                                                        }
                                                    }
                                                }
                                                var readFails = 0
                                                var waitedMs = 0
                                                while (!caught) {
                                                    Thread.sleep(1500)
                                                    waitedMs += 1500
                                                    val again = askPageIdentity(webRef)
                                                    if (again.first.isBlank()) {
                                                        readFails++
                                                        if (readFails >= 40) break
                                                        continue
                                                    }
                                                    readFails = 0
                                                    if (again.first != useTvid) {
                                                        useTvid = again.first
                                                        if (again.second.isNotBlank()) pageV = again.second
                                                        caught = true
                                                        android.util.Log.i(
                                                            "B0BEmbyVR",
                                                            "◆等待 站=iq 片号跟上了 → " + useTvid +
                                                                "（等了 " + (waitedMs / 1000) + " 秒）",
                                                        )
                                                        break
                                                    }
                                                }
                                                if (!caught) {
                                                    android.util.Log.w(
                                                        "B0BEmbyVR",
                                                        "◆不交 站=iq 页面身份读不到了（片号仍是 " + useTvid +
                                                            "）→ 这次不接管",
                                                    )
                                                    return@Thread
                                                }
                                            }
                                            var res = runCatching {
                                                com.xxxx.emby_vr.web.IqiyiDash2.fetch(useTvid, uid, kuid, dfp, "600", ckAll, ua)
                                            }.getOrNull()
                                            /*
                                             * 【2026-10-23 09:06 父亲"爱奇艺播错片最多"→ 取流后再确认一次】
                                             *
                                             * 取流要几百毫秒，这一小段里页面可能又切了一集；原来是"取完就交"，
                                             * 交出去的那份属于上一集。现在取完再读一次页面片号：
                                             *   · 变了 → 用新片号重取一次（只重取一次，避免来回抖）
                                             *   · 重取后又变了 → 这次不交（宁可这次不接管，也不播错片）
                                             */
                                            try {
                                                val chk = askPageIdentity(webRef)
                                                if (chk.first.isNotBlank() && chk.first != useTvid) {
                                                    android.util.Log.i(
                                                        "B0BEmbyVR",
                                                        "◆复检 站=iq 取流用的是 " + useTvid + "，页面已经变成 " + chk.first + " → 用新片号重取一次",
                                                    )
                                                    useTvid = chk.first
                                                    if (chk.second.isNotBlank()) pageV = chk.second
                                                    res = runCatching {
                                                        com.xxxx.emby_vr.web.IqiyiDash2.fetch(useTvid, uid, kuid, dfp, "600", ckAll, ua)
                                                    }.getOrNull()
                                                    val chk2 = askPageIdentity(webRef)
                                                    if (chk2.first.isNotBlank() && chk2.first != useTvid) {
                                                        android.util.Log.w(
                                                            "B0BEmbyVR",
                                                            "◆不交 站=iq 重取期间页面又变了（" + useTvid + " → " + chk2.first + "）→ 这次不接管",
                                                        )
                                                        return@Thread
                                                    }
                                                }
                                            } catch (eChk: Throwable) {
                                                if (eChk is kotlinx.coroutines.CancellationException) throw eChk
                                                android.util.Log.w(
                                                    "B0BEmbyVR",
                                                    "◆复检 站=iq 读页面身份失败：" + eChk,
                                                )
                                            }
                                            if (res == null) {
                                                android.util.Log.w(
                                                    "B0BEmbyVR",
                                                    "爱奇艺自取：dash 没拿到可用片单（见上一条日志里的原因）",
                                                )
                                            }
                                            /*
                                             * 【2026-10-23 11:10 父亲实测：比例不对的那些，时长都只有一分多钟
                                             * —— 说明抓到的是某个分片/预告，不是整集】
                                             * 拿到片单先看时长：不到 5 分钟就当"这不是整集"，重取一次；
                                             * 还是短的就这次不交（宁可这次不接管，也不把一段几十秒的东西当整集播）。
                                             */
                                            if (res != null && res.durationSec in 1..299) {
                                                android.util.Log.w(
                                                    "B0BEmbyVR",
                                                    "◆重取 站=iq 拿到的是短片段（" + res.durationSec + " 秒 / " +
                                                        res.segs + " 段），不是整集 → 重取一次",
                                                )
                                                try { Thread.sleep(1500) } catch (eSl: InterruptedException) {}
                                                res = runCatching {
                                                    com.xxxx.emby_vr.web.IqiyiDash2.fetch(useTvid, uid, kuid, dfp, "600", ckAll, ua)
                                                }.getOrNull()
                                                if (res == null || res!!.durationSec in 1..299) {
                                                    android.util.Log.w(
                                                        "B0BEmbyVR",
                                                        "◆不交 站=iq 重取后仍是短片段 → 这次不接管",
                                                    )
                                                    return@Thread
                                                }
                                            }
                                            if (res != null) {
                                                android.util.Log.i(
                                                    "B0BEmbyVR",
                                                    "◆新址 站=iq 身份=" + useTvid + " 槽=爱奇艺一格" +
                                                        " 段数=" + res.segs + " 时长=" + res.durationSec + "s" +
                                                        " 时刻=" + System.currentTimeMillis(),
                                                )
                                            } else {
                                                android.util.Log.w(
                                                    "B0BEmbyVR",
                                                    "◆新址 站=iq 身份=" + useTvid + " 取流失败（没拿到片单）",
                                                )
                                            }
                                            val file = if (res != null && ctx != null)
                                                com.xxxx.emby_vr.web.IqiyiDash2.writeTo(ctx.cacheDir, res.m3u8) else null
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                if (file != null) {
                                                    runCatching {
                                                        val play = org.json.JSONObject()
                                                        play.put("kind", "hls")
                                                        play.put("text", file.readText())
                                                        play.put("tier", "iq" + res!!.bid)
                                                        play.put("segs", res.segs)
                                                        play.put("durSec", res.durationSec)
                                                        /* 片号：让"这一集"进入去重签名，换集不会被误判为重复 */
                                                        play.put("epKey", useTvid)
                                                        /* 交付成功才记账：这份片单属于哪一集、什么时候取的 */
                                                        IqSlot.v = pageV
                                                        IqSlot.tvid = useTvid
                                                        IqSlot.title = pageFullTitle
                                                        IqSlot.at = System.currentTimeMillis()
                                                        android.util.Log.i(
                                                            "B0BEmbyVR",
                                                            "◆交付 站=iq 身份=" + useTvid +
                                                                " 网址=v_" + pageV +
                                                                " 标题「" + pageFullTitle.take(30) + "」" +
                                                                " 段数=" + res!!.segs + " 时长=" + res!!.durationSec + "s" +
                                                                " 时刻=" + System.currentTimeMillis() +
                                                                " 距切换=" + (if (iqSw > 0) "+" + (System.currentTimeMillis() - iqSw) + "ms" else "?") +
                                                                " 核对=片单按页面此刻的片号现取（片号即身份）",
                                                        )
                                                        if (pageV.isNotBlank()) IqSlot.pair[pageV] = useTvid
                                                        /*
                                                         * 宽高一起声明（原来只给"高"，宽缺失 → 原生跳过定比例、银幕沿用上一次
                                                         * 的比例，换到不同比例的片子就变形 —— 父亲 2026-10-23 00:45 报的"有时比例不对"）。
                                                         * 变体没报宽高就都不声明，交给内核按真实画面纠偏。
                                                         */
                                                        if (res.width > 0 && res.height > 0) {
                                                            play.put("width", res.width)
                                                            play.put("height", res.height)
                                                            android.util.Log.i(
                                                                "B0BEmbyVR",
                                                                "◆比例 站=iq 按变体声明 " + res.width + "x" + res.height +
                                                                    "（比例 " + String.format("%.3f", res.width.toFloat() / res.height.toFloat()) + "）",
                                                            )
                                                        } else {
                                                            android.util.Log.i(
                                                                "B0BEmbyVR",
                                                                "◆比例 站=iq 变体没报宽高 → 不声明，等内核按真实画面纠偏",
                                                            )
                                                        }
                                                        play.put("first", 0)
                                                        /*
                                                         * 【2026-10-22 修 P1】原来写死成站点首页 → 页面还没报 rect
                                                         * 时，"按 B 回网页"落到爱奇艺首页、Referer 也变首页。
                                                         * 改成此刻网页真实的地址。
                                                         */
                                                        play.put("frame", webRef?.url ?: "")
                                                        /*
                                                         * 【2026-10-21 21:58 父亲定：文案完全按结构数据构造】
                                                         *
                                                         * 剧名与集号来自爱奇艺的 playervideoinfo 接口
                                                         * （an / pd，实测"蒸蒸日上 / 第2集"），集名来自页面播放器对象
                                                         * （QiyiPlayerProphetData.videoInfo.title）。
                                                         * 拼法：剧名[ 第X季] 第X集 [集名]
                                                         */
                                                        /*
                                                         * 这个接口要发网络请求，必须离开主线程：
                                                         * 原来直接在 UI 线程调，抛 NetworkOnMainThreadException 被 runCatching 吞掉，
                                                         * epi 恒为空 → 文案只剩剧名、没有第几集、没有集名，弹幕也因此对不上集
                                                         * （父亲 2026-10-26 00:01 报）。
                                                         */
                                                        val epi = runCatching {
                                                            var r: com.xxxx.emby_vr.web.IqiyiDash2.EpInfo? = null
                                                            val th = Thread {
                                                                r = runCatching {
                                                                    com.xxxx.emby_vr.web.IqiyiDash2.episodeInfo(tvid, ckAll, ua)
                                                                }.getOrNull()
                                                            }
                                                            th.start()
                                                            th.join(2500)
                                                            r
                                                        }.getOrNull()
                                                        android.util.Log.i(
                                                            "B0BEmbyVR",
                                                            "爱奇艺文案：结构化=" + (epi?.let { it.series + "/" + it.episode } ?: "空") +
                                                                " 页面整句=" + pageFullTitle + " 集名=" + pageEpTitle,
                                                        )
                                                        val composed = runCatching {
                                                            val sb = StringBuilder()
                                                            if (epi != null && epi.series.isNotBlank()) {
                                                                sb.append(epi.series)
                                                                /* 电影没有集号：接口也会回 episode=1，照抄就变成"片名 第1集"（父亲 2026-10-26 报） */
                                                                if (epi.episode > 0 && epi.total > 1) {
                                                                    sb.append(" 第").append(epi.episode).append("集")
                                                                }
                                                                if (pageEpTitle.isNotBlank() &&
                                                                    !pageEpTitle.contains("第") &&
                                                                    !epi.series.contains(pageEpTitle)
                                                                ) {
                                                                    sb.append(" ").append(pageEpTitle)
                                                                }
                                                            } else if (pageFullTitle.isNotBlank()) {
                                                                /* 爱奇艺的 playervideoinfo 没取到时，用页面按结构数据拼好的那句 */
                                                                sb.append(pageFullTitle)
                                                            } else {
                                                                sb.append(pageEpTitle)
                                                            }
                                                            sb.toString().trim()
                                                        }.getOrNull().orEmpty()
                                                        /*
                                                         * 弹幕按 tvid 取（cmts.iqiyi.com/bullet/…），发起处读的是
                                                         * IqiyiDash.tvid。这条路径以前没人给它赋值，所以爱奇艺接管后
                                                         * 一条弹幕都进不来（父亲 2026-10-26 00:45 报）。
                                                         */
                                                        runCatching { com.xxxx.emby_vr.web.IqiyiDash.setTvid(useTvid) }
                                                        /* 控制条"正在播放"统一取播放窗口左上那行字（父亲 2026-10-26 02:26 定），
                                                           取不到才用拼出来的那份 */
                                                        val headerText = askPageHeader(webRef)
                                                        play.put("title", headerText.ifBlank { composed })
                                                        /* 集号与片号一起带上：控制条要显示"第几集"，弹幕也要按集去查 */
                                                        play.put("vid", useTvid)
                                                        if (epi != null && epi.episode > 0) play.put("ep", epi.episode.toString())
                                                        play.put("durSec", res.durationSec)
                                                        android.util.Log.i(
                                                            "B0BEmbyVR",
                                                            "爱奇艺自取：交整集片单（" + res.segs + " 段，" +
                                                                (if (res.bid >= 600) "1080P" else "720P") + "）",
                                                        )
                                                        /*
                                                         * 直接开播，不要再进"接收槽"等下一次跳变：
                                                         * 这一份是我们自己在"真全屏"处理里签出来的，
                                                         * 而那次跳变已经被这次处理消耗掉了；再丢进槽里等，
                                                         * 就永远等不到下一次，表现为"签成功、也交付了，但画面没动"
                                                         * （父亲 2026-10-25 23:32 实测）。
                                                         */
                                                        com.xxxx.emby_vr.ui.WebRuntime.playNow(play)
                                                    }
                                                } else {
                                                    /* 自取失败就让网页继续放，不再去要页面那份 6 分钟地址（父亲 07:36 定） */
                                                    android.util.Log.w(
                                                        "B0BEmbyVR",
                                                        "爱奇艺自取失败：网页继续播放，不接管",
                                                    )
                                                }
                                            }
                                        }.start()
                                    }
                                    return
                                }
                                val hls = runCatching {
                                    com.xxxx.emby_vr.web.IqiyiDash.toHlsMessage(null)
                                }.getOrNull()
                                if (hls != null) {
                                    android.util.Log.i(
                                        "B0BEmbyVR",
                                        "爱奇艺接管：交整集片单（" +
                                            com.xxxx.emby_vr.web.IqiyiDash.segCount + " 段）",
                                    )
                                    runCatching { WebRuntime.acceptFromPage(hls) }
                                } else {
                                    webRef?.evaluateJavascript(
                                        "window.__b0bIqTakeover && window.__b0bIqTakeover();",
                                        null,
                                    )
                                }
                                /* 告诉主界面：把画面投到影院大银幕（父亲 2026-10-13） */
                                WebPageHub.setFullscreen(true)
                                /*
                                 * 【2026-10-20 19:24 父亲定：两个全屏按钮要分开】
                                 *
                                 * 腾讯页面有两个按钮：网页全屏（只有点了它，清晰度选择才出来）
                                 * 和真正的全屏。他要的流程是：先用网页全屏选臻彩MAX，再点真全屏接管。
                                 * 这里在"真全屏"（播放器被交给系统全屏）这一刻给页面打个时间戳，
                                 * 页面只在看到这个戳之后才交地址 —— 网页全屏不算。
                                 */
                                runCatching {
                                    /* onShowCustomView 拿到的是被交出来的那个视图，不是网页本身；
                                       打标记必须发给网页（webRef），它才有 evaluateJavascript。 */
                                    webRef?.evaluateJavascript(
                                        /*
                                         * 【2026-10-22】同时写进 localStorage：腾讯的播放器常在
                                         * 另一层/另一个窗口里，只写当前这一层它看不到；
                                         * 共享那份在换页时由页面脚本一起清掉。
                                         */
                                        "window.__b0bRealFull=Date.now();" +
                                            "try{localStorage.setItem('b0b:realFullAt',String(Date.now()));}catch(e){}",
                                        null,
                                    )
                                }
                                android.util.Log.i("WebLib", "网页全屏：已接管播放视图")
                                runCatching {
                                    WebRuntime.chain("⑦跳变", "非真全屏 → 真全屏（进入侧，只触发一次消费）")
                                    WebRuntime.consumeSlot("真全屏")
                                }
                            }

                            override fun onHideCustomView() {
                                val box = customContainer
                                if (box != null) {
                                    box.removeAllViews()
                                    box.visibility = android.view.View.GONE
                                }
                                customViewCallback?.onCustomViewHidden()
                                customViewCallback = null
                                customView = null
                                fullscreen = false
                                WebPageHub.setFullscreen(false)
                                android.util.Log.i("WebLib", "网页全屏：已退出")
                                runCatching { WebRuntime.chain("⑦跳变", "真全屏 → 非真全屏") }
                            }

                            override fun onConsoleMessage(m: org.chromium.android_crwebview.webkit.ConsoleMessage): Boolean {
                                android.util.Log.i("WebLib", "控制台 ${m.message()} @${m.lineNumber()}")
                                return true
                            }
                        }
                        /* 加载放到 LaunchedEffect(site) 里做，换站点时能重新加载 */
                    }
                },
                update = { webRef = it },
            )
        /* 网页容器结束（火狐分支已拆，这里不再有 else 的收尾括号） */
        /*
         * 网页全屏用的容器：平时藏着，网页要全屏时把它的播放视图挂进来铺满整块画面。
         * 它排在网页之后 = 画在最上层。
         */
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    visibility = android.view.View.GONE
                    customContainer = this
                }
            },
        )
        /*
         * 面板正中的加载 / 失败提示（父亲 2026-10-13）。
         *
         * 排在两个网页容器之后 = 画在它们上面，但只在"还没加载完"或"没加载出来"时存在：
         * 加载完这个 if 直接为假，整块提示从界面树上撤掉，不存在"压着网页"的可能。
         * 不铺底色是因为网页自己会画白底页面的加载过程，蒙一层反而看不清它在动。
         */
        if (loading || loadFailed) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (loadFailed) "请检查网络连接" else "正在加载…",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                )
            }
        }
    }

    /* 打开（或换）站点：网页整块在大银幕上（换站由外层 key(site) 重建整棵子树，见上） */
    LaunchedEffect(Unit) {
        /* 换站点把提示复位回"正在加载"，别把上一个站点的失败提示带过来 */
        loading = true
        loadFailed = false
        webRef?.loadUrl(url)
    }

    /*
     * 登记交互：光柱点击 / 摇杆滚动都派发给网页。
     * 落点先按"网页在面板里的矩形"换算成网页自己的像素 ——
     * 落在矩形外（比如点在顶栏上）就不处理，交给原来的查表逻辑。
     */
    DisposableEffect(Unit) {
        /*
         * 回网页时清空三槽（2026-10-20 父亲定）：主界面"让位回网页"那一步会调它。
         * 走 evaluateJavascript，调用页面里注册的 window.__b0bClearSlots。
         */
        WebPageHub.clearSlotsHandler = {
            WebRuntime.clearSlot()
            runCatching {
                webRef?.evaluateJavascript(
                    "window.__b0bClearSlots && window.__b0bClearSlots();",
                    null,
                )
                WebRuntime.chain("⑨回网页", "清空接收槽 + 通知网页清槽并真退出全屏")
            }
        }
        WebPageHub.tapHandler = handler@{ px, py ->
            val r = webRect
            /*
             * 全屏时事件发给**网页塞过来的那个播放视图**，不是 WebView
             * （父亲 2026-10-13 实测：网页全屏播放后光标点不动大银幕）——
             * 那个视图已经从 WebView 里摘出来挂到我们自己的容器上了，
             * 发给 WebView 等于发给一块不再显示内容的控件，什么都不会发生。
             */
            /* 火狐内核那条路也要能点：geckoRef 插在中间（2026-10-14） */
            val wv: android.view.View? = customView ?: webRef
            if (r != null && wv != null && r.width > 0f && r.height > 0f &&
                wv.width > 0 && wv.height > 0
            ) {
                val u = (px - r.left) / r.width
                val v = (py - r.top) / r.height
                if (u in 0f..1f && v in 0f..1f) {
                    val x = (u * wv.width).coerceIn(0f, (wv.width - 1).toFloat())
                    val y = (v * wv.height).coerceIn(0f, (wv.height - 1).toFloat())
                    val now = SystemClock.uptimeMillis()
                    /*
                     * 【2026-10-21 09:35 交互坐标对不上：把换算现场一起打出来】
                     *
                     * 父亲报爱奇艺播放页"选集/清晰度"能高亮但点不开。已用网页调试口证实：
                     * 在同一页用真鼠标点 (1027,686) 能开面板，所以问题在"我们发出去的坐标"。
                     * 日志里只有派发坐标、没有换算依据，无法判断是落点算错还是缩放算错，
                     * 这里把 webRect、视图尺寸、归一化 u/v 一并打出来，一次对齐。
                     */
                    android.util.Log.i(
                        "WebLib",
                        "点击换算：webRect=" + r.left.toInt() + "," + r.top.toInt() + " " +
                            r.width.toInt() + "x" + r.height.toInt() +
                            " 视图=" + wv.width + "x" + wv.height +
                            " u=" + String.format("%.3f", u) + " v=" + String.format("%.3f", v) +
                            " → 派发=" + x.toInt() + "," + y.toInt() +
                            "（全屏=" + (customView != null) + "）",
                    )
                    runCatching {
                        /*
                         * 先把焦点要过来（2026-10-20 日志：新内核下网页视图"有焦点=false"，
                         * Chromium 会因此忽略部分输入事件）。
                         */
                        if (!wv.hasFocus()) wv.requestFocus()
                        /*
                         * 只发"按下 → 抬起"，干净的一次点击。
                         *
                         * 原来在按下前还补两条"鼠标移动"（想让网页控制条浮出来），但光柱本来
                         * 就一直在发悬停，那两条是重复的，还会让网页先看到"鼠标"、再看到
                         * "触摸/别的设备"，设备语义来回切 —— 2026-10-14 实测这种夹着移动的序列
                         * 点不动海报。控制条浮出交给光柱的悬停就够了。
                         */
                        dispatchMouse(wv, MotionEvent.ACTION_DOWN, x, y, now, now)
                        dispatchMouse(wv, MotionEvent.ACTION_UP, x, y, now, now + 40)
                    }
                    return@handler true
                }
            }
            /* 落在网页外面（顶栏等）：交回常规查表 */
            false
        }
        /*
         * 光柱在银幕上移动 = 给网页发鼠标悬停（父亲 2026-10-13：手柄当鼠标用）。
         *
         * 只由原生"银幕上放着网页"那一路推过来（onWebHover），所以自带"限制在大屏幕上"。
         * 发的是 HOVER_MOVE（SOURCE_MOUSE）—— 网页据此更新 :hover / 触发 mouseenter，
         * 停在一些按钮上（比如 B站头像）就会像真鼠标那样浮出菜单。
         */
        WebPageHub.hoverHandler = hover@{ px, py ->
            /*
             * 【2026-10-21 09:35 悬停坐标采样】用来核对"银幕落点 → 网页像素"这条换算：
             * 父亲把光柱在屏幕上扫一圈，日志里的 min/max 就能算出真实映射与预期差多少。
             */
            if (px >= 0f && py >= 0f) {
                val t = SystemClock.uptimeMillis()
                if (t - lastHoverLogAt[0] > 400) {
                    lastHoverLogAt[0] = t
                    val rr = webRect
                    val vv = customView ?: webRef
                    android.util.Log.i(
                        "WebLib",
                        "悬停换算：面板=" + px.toInt() + "," + py.toInt() +
                            " webRect=" + (rr?.let { it.left.toInt().toString() + "," + it.top.toInt() + " " + it.width.toInt() + "x" + it.height.toInt() } ?: "无") +
                            " 视图=" + (vv?.let { it.width.toString() + "x" + it.height } ?: "无"),
                    )
                }
            }
            val r = webRect ?: return@hover
            val wv = customView ?: webRef ?: return@hover
            if (r.width <= 0f || r.height <= 0f) return@hover
            /*
             * 负坐标 = 光柱离开银幕（原生推来的哨兵，2026-10-15 父亲提）：
             * 给网页补一条"鼠标移出"，它才会把播放器控制条收起来。
             * 用最后那个有效坐标发（越界坐标会被内核丢掉）。
             */
            if (px < 0f || py < 0f) {
                hoverOnScreen[0] = false
                if (lastHoverX[0] > -9000f) {
                    val nowExit = SystemClock.uptimeMillis()
                    runCatching {
                        /*
                         * 先补一条"挪到画面中心"，再发"移出"（父亲 2026-10-15 实测）。
                         *
                         * 从左右移开控制条会收起、从下边移开不收起 —— 腾讯是看"鼠标最后
                         * 停在哪"决定收不收，最后停在画面底部它就以为你要用控制条。
                         * 先把指针挪到画面中心（那儿不会唤出控制条），再撤走。
                         */
                        val midX = wv.width / 2f
                        val midY = wv.height / 2f
                        dispatchMouse(
                            wv,
                            MotionEvent.ACTION_HOVER_MOVE,
                            midX,
                            midY,
                            nowExit,
                            nowExit,
                        )
                        dispatchMouse(
                            wv,
                            MotionEvent.ACTION_HOVER_EXIT,
                            midX,
                            midY,
                            nowExit,
                            nowExit,
                        )
                    }
                    lastHoverX[0] = -9999f
                    lastHoverY[0] = -9999f
                }
                return@hover
            }
            val u = (px - r.left) / r.width
            val v = (py - r.top) / r.height
            if (u !in 0f..1f || v !in 0f..1f) {
                hoverOnScreen[0] = false
                return@hover
            }
            hoverOnScreen[0] = true
            /*
             * 【2026-10-21 12:22 闪退】视图尺寸为 0 时 coerceIn(0f, -1f) 会抛
             * IllegalArgumentException: Cannot coerce value to an empty range
             * —— 爱奇艺一按全屏（播放视图刚换手、宽高还是 0）就命中这里，进程直接崩。
             * 尺寸不可用时这一拍不发悬停，等下一拍尺寸出来再发。
             */
            if (wv.width <= 0 || wv.height <= 0) return@hover
            val x = (u * wv.width).coerceIn(0f, (wv.width - 1).toFloat())
            val y = (v * wv.height).coerceIn(0f, (wv.height - 1).toFloat())
            /* 挪动不足 3 像素不发（网页每个 mousemove 都要做命中测试，太密会拖慢） */
            if (kotlin.math.abs(x - lastHoverX[0]) < 3f &&
                kotlin.math.abs(y - lastHoverY[0]) < 3f
            ) {
                return@hover
            }
            lastHoverX[0] = x
            lastHoverY[0] = y
            runCatching {
                val hoverNow = SystemClock.uptimeMillis()
                dispatchMouse(
                    wv,
                    MotionEvent.ACTION_HOVER_MOVE,
                    x,
                    y,
                    hoverNow,
                    hoverNow,
                )
            }
        }
        /*
         * 滚动：派发"滚轮事件"，和面板滚菜单用的是同一条路（父亲 2026-10-13 实测：
         * 之前用 scrollBy 三个网站都滚不动 —— 网页的滚动容器不是文档本身，
         * scrollBy 滚的是文档，所以没反应；滚轮事件按坐标做命中测试，
         * 落点在哪一块就滚哪一块，网页自己会把它滚起来）。
         *
         * 两种兜底：事件没人接（网页没监听）→ scrollBy 滚文档；
         * 事件被接了但落点是视频这类不吃滚动的元素 → 黑洞，
         * 用 scrollFallbackJs 顺着 DOM 找真正会滚的容器补一滚。
         * 爱奇艺详情页的播放窗口特别大，光柱常落在视频上，没有这个兜底整块滚不动
         * （父亲 2026-10-25 实测）。
         */
        /* 网页内返回（B 键）：只在网页的历史里退，退不动就什么都不做 */
        WebPageHub.backHandler = back@{
            /*
             * 光柱不在银幕上时，B 键不算"网页内返回"（2026-10-15 父亲提）。
             *
             * 主界面那条路给过来的条件是"银幕上放着网页 且 光柱不在面板上"，
             * 光柱飘在空中、指着别处也满足 —— 于是网页无条件后退。
             * 这里再收一道：只有光柱确实落在银幕矩形内，才让网页自己退。
             * 返回 false 时主界面会照旧走"面板返回上一级"。
             */
            if (!hoverOnScreen[0]) return@back false
            /*
             * 火狐内核那条路：直接让它的会话后退（它没有同步的 canGoBack 可问，
             * 退不动时就是什么都不发生，与原来"退不动返回 false"的观感一致）。
             */
            /*
             * 【16:39 修正】接管回来那一页没有真实历史（网页销毁重建），
             * 原来 gecko 这条"永远成功"的分支让 B 键按了没动静。
             * 现在先走合成历史栈（网页屏自己记的路线）， honestly 回退一步是一步；
             * 没得退时再走 gecko 真历史（会话没重建的场合）。
             */
            val hub = com.xxxx.emby_vr.web.WebPageHub
            if (hub.navStack.size >= 2) {
                val nav = hub.navHandler ?: return@back false
                val prev = hub.navStack.removeAt(hub.navStack.size - 1)   // 当前页（栈底是当前页）
                nav(prev)
                return@back true
            }
            val wv = webRef
            if (wv != null && wv.canGoBack()) {
                runCatching { wv.goBack() }
                true
            } else {
                false
            }
        }
        WebPageHub.scrollHandler = scroll@{ px, py, sx, sy ->
            /* 同上：全屏时滚的是那个播放视图 */
            val wv: android.view.View? = customView ?: webRef
            val r = webRect
            /*
             * 摇杆左右 = 网页里快退 / 快进（父亲 2026-10-13 定，2026-10-14 放宽）。
             *
             * 原条件要求「网页自己触发了全屏播放」（customView 非空）。实测（父亲 2026-10-14）：
             * B站 网页播放器在页面内播放时根本不走那条路，customView 一直是空的 ——
             * 快进快退于是永远不生效。现在改成「银幕上有网页就认」：三个站的播放器都吃键盘
             * ← →，发方向键最稳（网页自己跳、自己更新进度条）；列表页的左右键网页本来也无害。
             */
            if (wv != null &&
                kotlin.math.abs(sx) > 0.35f && kotlin.math.abs(sx) >= kotlin.math.abs(sy)
            ) {
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - lastArrowAt[0] > 250L) {
                    lastArrowAt[0] = nowMs
                    dispatchArrowKey(wv, sx > 0f)
                }
                return@scroll
            }
            if (wv != null && r != null && r.width > 0f && r.height > 0f &&
                kotlin.math.abs(sy) > 0.2f
            ) {
                val u = ((px - r.left) / r.width).coerceIn(0.05f, 0.95f)
                val v = ((py - r.top) / r.height).coerceIn(0.05f, 0.95f)
                val x = u * wv.width
                val y = v * wv.height
                /* 方向跟面板一致：摇杆往上推 = 往回滚（滚轮往上），往下推 = 往下滚 */
                val notch = if (sy > 0f) 0.5f else -0.5f
                val props = arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_MOUSE
                    },
                )
                val coords = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                        pressure = 1f
                        size = 1f
                        setAxisValue(MotionEvent.AXIS_VSCROLL, notch)
                    },
                )
                val now = SystemClock.uptimeMillis()
                @Suppress("DEPRECATION")
                val ev = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
                )
                /*
                 * 爱奇艺播放窗口把落在视频上的滚轮当成音量：光柱压在播放器上时
                 * 上下推摇杆只调音量、页面一动不动（父亲 2026-10-25 实测）。
                 * 所以爱奇艺不走"先派发给网页"这条：直接顺着 DOM 找会滚的容器滚。
                 */
                if (site == 2) {
                    ev.recycle()
                    runCatching { (wv as? WebView)?.evaluateJavascript(scrollFallbackJs(u, v, sy), null) }
                } else {
                    val eaten = runCatching { wv.dispatchGenericMotionEvent(ev) }.getOrDefault(false)
                    ev.recycle()
                    if (!eaten) runCatching { wv.scrollBy(0, if (sy > 0f) -60 else 60) }
                    else runCatching { (wv as? WebView)?.evaluateJavascript(scrollFallbackJs(u, v, sy), null) }
                }
            }
        }
        onDispose {
            /* 离开网页页一定要收回全屏状态，否则银幕会一直贴着面板纹理 */
            WebPageHub.setFullscreen(false)
            WebPageHub.clear()
            /* 网页层收摊：把"视频画面"清掉，免得银幕还按上一块画面裁（父亲 2026-10-15） */
            pushVideoRect(null)
            WebRuntime.stopBridge()
            webRef?.let { wv ->
                runCatching { wv.stopLoading() }
                runCatching { wv.loadUrl("about:blank") }
                runCatching { wv.destroy() }
            }
            webRef = null
            /* 火狐内核已拆（2026-10-20）：这里不再有会话要停、引用要放 */
        }
    }
}

/*
 * 这一页原来还有一个私有 composable `TopBarButton`（顶栏「返回海报墙」小按钮）。
 * 顶栏那份「返回海报墙」按钮随详情页改版一起去掉了 —— 电影 / 剧集详情页也没有它，
 * 返回统一靠 B 键，于是它成了没人调用的死代码，父亲 2026-10-13 要求不留未使用的私有函数，删掉。
 */

/**
 * 网络视频详情页（父亲 2026-10-13 定：**做成媒体库页的样子**）。
 *
 * 父亲这一页要的东西只有两样：左上角一个标题「网络视频」，标题下面三张站点卡。
 * 原来那版是照电影详情页拼的（渐变底 + 下半屏压暗 + 元数据行 + 站点名行 + 简介 +
 * 三颗小按钮 + 右侧大图 + 「站点」分区标题），可这一页背后没有"一部片子"可介绍 ——
 * 它就是三个站点入口，堆上去的字都是废话，还把三张卡挤到下半屏。
 * 现在砍到只剩标题 + 三张卡，卡片的规格与首页「我的媒体库」那一行的库卡一致
 * （260 宽、图区 16:9、图区圆角 12dp、图下 4dp 居中标题），
 * 观感上就是"进了又一个媒体库"。
 *
 * 顶栏仍然没有「返回海报墙」按钮：媒体库页也没有，返回统一靠 B 键。
 */
@Composable
fun WebSiteDetailScreen(onOpen: (Int) -> Unit, onBack: () -> Unit) {
    val sites = listOf(
        "哔哩哔哩" to R.drawable.web_bilibili_icon,
        "腾讯视频" to R.drawable.web_tencent_icon,
        "爱奇艺" to R.drawable.web_iqiyi_icon,
    )
    /* 底色用全站暗色体系的底（0xFF0B0C0F），不再走原来那条"详情页专用"的渐变 */
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C0F)),
    ) {
        Column(
            /* 页边距 40dp，与媒体库页 / 详情页一致（父亲 2026-10-13） */
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
        ) {
            Text(
                text = "网络视频",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            /* 标题到卡片行的间距：媒体库页是"标题 → 排序行 → 网格"，这里没有排序行，留一段呼吸 */
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                /* 卡片间距 20dp：与首页「我的媒体库」那一行一致 */
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                sites.forEachIndexed { i, (name, icon) ->
                    NetVideoSiteCard(
                        name = name,
                        icon = icon,
                        key = "netvideo:big:$i",
                        onActivate = { onOpen(i) },
                    )
                }
            }
        }
    }
}

/**
 * 站点卡（父亲 2026-10-13 定：规格与首页「我的媒体库」的库卡一字不差）。
 *
 * 结构照 `ui/components/BuildItem.kt` 的库卡：Column = 图区 Box + 文字区 Column(padding 4dp, 居中)。
 * 卡片名字写白色而不是各站品牌色 —— 原来压在近白底上用品牌色，黄绿两家的对比度都不够，
 * 现在底是 0xFF101216 的暗块，白字才清楚（父亲要求 ≥3:1）。
 *
 * 站点 logo 是方形图标（447/225/216 见方），放在 16:9 的图区里用 Fit 保住整枚图标，
 * 用 Crop 会把上下切掉半个（爸爸要的是"图标"不是"截图"）。
 */
@Composable
private fun NetVideoSiteCard(
    name: String,
    icon: Int,
    key: String,
    onActivate: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101216))
            /* 圆角 12dp，与卡面一致：白块的圆角跟控件对不上时四个角会露出来（父亲 2026-10-13 实测） */
            .vrClickTarget(key = key, hoverRadius = 12.dp, onActivate = onActivate),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101216)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = name,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 「网络视频」卡片（父亲 2026-10-13 定）：放在海报墙首页「我的媒体库」那一行里，
 * 和普通媒体库并排；图用父亲发来的那张网络视频图标拼图（web_netvideo_card）。
 * 点它进网络视频详情页（三个站点卡）。
 *
 * 父亲原话：「网络视频库卡的说明文字和其他库卡说明文字的大小和位置都不一样」——
 * 原来这里把「网络视频」压在图上（左下角、22sp、底下垫一条 44dp 黑条），
 * 而同一行其他库卡的名字都在**图下面的居中位置**（16sp 粗体）。
 * 现在照 `ui/components/BuildItem.kt` 的库卡结构来：Column = 图区 Box + 文字区
 * Column(padding 4dp, 居中)；圆角也从 14dp 收到 12dp（全站圆角只有 8/12/16/28 四档）。
 */
@Composable
fun NetVideoCard(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101216))
            .vrClickTarget(key = "netvideo:card", hoverRadius = 12.dp, onActivate = onClick),
    ) {
        /* 图区 260×146 = 16:9，与「我的媒体库」那一行的库卡图区一样大 */
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF101216)),
        ) {
            Image(
                painter = painterResource(R.drawable.web_netvideo_card),
                contentDescription = "网络视频",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        /* 文字区：图下留 4dp，白字 16sp 粗体居中一行（与同排库卡的标题同规格） */
        Column(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "网络视频",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/*
 * ============ 2026-10-20 17:24：往页面里注入抓取脚本（换内核后重建这条通道） ============
 *
 * 火狐时代由扩展自动干这件事，火狐拆掉后必须我们自己注。两段：
 *   ① 转接头：把 main.js 用 window.postMessage({__b0b:1,payload}) 发出的数据，
 *      改成调用原生接口 window.b0bHost.post(JSON字符串)。
 *   ② 脚本本体：assets/webx/main.js（页面世界跑，量画面尺寸 + 抓播放地址 + 攒分片）。
 *
 * 为什么分段：main.js 两千多行、接近 200KB，一次传给页面容易失败，
 * 按 60KB 切开逐段追加到 window.__b0bSrc，最后统一 eval 一次。
 */
private const val B0B_SHIM_JS = """
(function(){
  if (window.__b0bShim) return; window.__b0bShim = 1;
  var op;
  try { op = window.postMessage ? window.postMessage.bind(window) : null; } catch (e) { op = null; }
  window.postMessage = function (m, t) {
    try {
      if (m && m.__b0b && window.b0bHost && window.b0bHost.post) {
        window.b0bHost.post(JSON.stringify(m.payload));
      }
    } catch (e) {}
    try { if (op) return op(m, t); } catch (e) {}
  };
  console.log('[b0b] 转接头已装（页面 → 原生）');
})();
"""

/*
 * 取件脚本：把页面收件箱里的数据全部取出并清空，返回 JSON 数组（字符串）。
 * 页面那边的投递函数会往 window.__b0bOutbox 里塞字符串。
 */
private const val B0B_TAKE_OUTBOX_JS =
    "(function(){try{var a=window.__b0bOutbox||[];window.__b0bOutbox=[];return JSON.stringify(a);}catch(e){return '[]';}})();"

private fun injectPageScripts(view: android.view.View) {
    val wv = view as? WebView ?: return
    injectPageScriptsAt(wv, 0)
}

/**
 * 【2026-10-22 修 P2：注入失败没人管】
 *
 * main.js 有 5000 多行，按 6 万字符分块塞进页面再 eval。页面正在跳转、渲染进程刚重建时，
 * 中途某一块会失败 —— 结果是"脚本只装了一半"：整站抓不到地址，界面上完全看不出来。
 * 现在注入后 1.5 秒回读页面里的注入凭证，没写上就再注入一次（最多两次）。
 */
private fun injectPageScriptsAt(wv: WebView, attempt: Int) {
    runCatching { wv.evaluateJavascript(B0B_SHIM_JS, null) }
    val src = runCatching {
        wv.context.assets.open("webx/main.js").bufferedReader().use { it.readText() }
    }.getOrNull()
    if (src.isNullOrBlank()) {
        android.util.Log.w("B0BEmbyVR", "注入失败：assets/webx/main.js 读不到")
        return
    }
    runCatching { wv.evaluateJavascript("window.__b0bSrc='';", null) }
    val chunk = 60000
    var i = 0
    while (i < src.length) {
        val end = minOf(i + chunk, src.length)
        val js = "window.__b0bSrc += " + org.json.JSONObject.quote(src.substring(i, end)) + ";"
        runCatching { wv.evaluateJavascript(js, null) }
        i = end
    }
    runCatching {
        wv.evaluateJavascript(
            "(function(){try{eval(window.__b0bSrc);window.__b0bSrc='';" +
                "console.log('[b0b] 抓取脚本已注入');}catch(e){" +
                "console.log('[b0b] 抓取脚本注入失败：'+e);}})();",
            null,
        )
    }
    if (attempt >= 2) return
    runCatching {
        wv.postDelayed({
            runCatching {
                wv.evaluateJavascript(
                    "String((function(){try{return window.__b0bInjectedAt||0}catch(e){return 0}})())",
                ) { v ->
                    val ok = (v ?: "").trim('"').toLongOrNull() ?: 0L
                    if (ok <= 0L) {
                        android.util.Log.w("B0BEmbyVR", "抓取脚本没装上（第 ${attempt + 1} 次），重试注入")
                        runCatching { injectPageScriptsAt(wv, attempt + 1) }
                    } else {
                        android.util.Log.i("B0BEmbyVR", "抓取脚本已确认装上（第 ${attempt + 1} 次）")
                    }
                }
            }
        }, 1500L)
    }
}

/**
 * 【2026-10-20 17:20 新内核的页面 → 原生桥】
 *
 * 火狐时代靠扩展的 nativeMessaging 回传；换 Chromium 后没有扩展了，改用系统网页视图自带的
 * JavaScript 接口：页面里的脚本调 window.b0bHost.post(字符串)，这里的 post 被调用。
 *
 * 注意：方法必须带 @JavascriptInterface，且被 release 的代码压缩保住（见 proguard-rules）。
 */
class WebPageBridge {
    @android.webkit.JavascriptInterface
    fun post(json: String?): String {
        if (json.isNullOrBlank()) return "empty"
        return WebRuntime.acceptFromPage(json)
    }
}
