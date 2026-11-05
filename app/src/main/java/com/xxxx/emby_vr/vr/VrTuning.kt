package com.xxxx.emby_vr.vr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 运行时可调参数（父亲 2026-10-08 定：不要写死在代码里，戴着调参比反复编译划算）。
 *
 * 参数写在应用的外部私有目录，adb 可以直接改，改完 1 秒内生效（个别项要重新起播）：
 *
 * ```
 * adb shell "echo -e 'video_w=1280\nvideo_h=720' > /sdcard/Android/data/com.xxxx.emby_vr/files/vr-tuning.txt"
 * ```
 *
 * 参数表（每行 `key=value`，`#` 开头是注释）：
 * | 参数 | 说明 | 默认 |
 * |---|---|---|
 * | `video_w` / `video_h` | 视频链路画布尺寸（内核画布 + 画面缓冲），必须是两张一起写 | 1920x1080 |
 * | `super_sample` | 双眼渲染超采样倍数（0.6~2.0） | 1.25 |
 * | `swap_wait_ms` | 等交换链图像超时（毫秒，0~50） | 4 |
 * | `sync_mode` | 交回图像前的同步：0=只提交命令 1=等 GPU 画完 | 0 |
 * | `refresh_hz` | 刷新率档：0=自动（播放 72 / 界面 90）、72、90 | 0 |
 * | `layer_mask` | 层掩码 6 位：bit0 视频 / bit1 弹幕 / bit2 logo / bit3 海报墙 / bit4 控制条 / bit5 菜单 | 0x3F |
 * | `video_layer` `danmaku_layer` `logo_layer` `panel_layer` `osd_layer` `menu_layer` | 单层开关 0/1（写哪个改哪个位，其余保持默认） | — |
 * | `hide_projection` | 1=不提交投影层（只剩 quad 层），验证黑纹是否必须有投影层参与 | 0 |
 * | `test_pattern` | 1=用固定测试图替代视频纹理，切断 mpv/MediaCodec/SurfaceTexture 输入链 | 0 |
 * | `video_layer_max_w` | 视频层交换链宽度上限（0=按视频原分辨率），验证 4K 大图层是否本身是问题 | 0 |
 * | `no_downsample` | 1=视频走单次纹理采样（关掉 4×4 高斯降采样分支） | 0 |
 * | `no_jitter` | 1=抖动种子固定（排除逐帧变化的着色器输入） | 0 |
 * | `force_rgba8` | 1=视频层交换链强制 GL_RGBA8（不试 sRGB） | 0 |
 * | `no_postfx` | 1=画质增强与颜色调整全回中性 | 0 |
 * | `finish_before_texupdate` | 1=每次取帧前先 glFinish（防外部纹理缓冲被提前回收，黑纹主嫌疑开关） | 0 |
 * | `video_surface_w` | 内核视频处理宽度上限（0=1920）。杜比还原开销与像素量成正比，调小可直接砍 GPU | 0 |
 * | `screen_width` | 银幕宽度（米）。与 `screen_distance` 一起决定视角，默认按 IMAX 第10排几何 | 5.2 |
 * | `screen_distance` | 银幕距离（米）。IMAX 几何 = 宽 26 / 距 15 | 3.2 |
 * | `screen_preset` | 影院银幕两套预设（2026-10-10）：1 = 影厅小屏 5.2/3.2 · 2 = IMAX 大屏 26/15 | 1 |
 * | `log_verbose` | 排查日志总开关（1 = 全部排查日志，0 = 只留报错与警告；默认 0） | 0 |
 * | `osd_y` | 控制条中心在眼睛下方多少米（写负数，-2.0~0.5；2026-10-30） | -0.65 |
 * | `walk_path_m` | 一秒内累积路程超过多少米才算"在走动"（0.03~1.0，抗身体晃动误判） | 0.15 |
 * | `walk_still_ms` | 停走后多少毫秒内收到的"重新居中"当运行时噪声忽略 | 600 |
 * | `min_screen_gap` | 最近允许走到离银幕多少米（再走近场景整体后推，不许穿过；0.3~5.0） | 1.0 |
 * | `follow_viewer` | 控制条/菜单/海报墙是否跟随观影位（1=跟，0=固定；2026-10-10） | 1 |
 * | `ray_scale` | 光柱粗细倍率（1.0 = 银幕 3.2 米时代的角粗细） | 0.35 |
 * | `stereo3d` | 实时 2D→3D：1=视频层拆左右眼并起推理线程，0=整条链路不参与（2D 不受影响） | 0 |
 * | `stereo_disp` | 3D 视差强度：UV 小数，或千分位整数（14=0.014 标准档、9=0.009 舒适档） | 0.014 |
 * | `stereo_conv` | 3D 零视差平面（0 近 … 1 远，0.5 = 银幕所在深度） | 0.5 |
 * | `stereo_fade` | 3D 左右边缘淡出宽度（防边缘拉出竖条） | 0.04 |
 * | `depth_invert` | 深度方向：1=模型输出越大越近（用之前取反） | 0 |
 * | `stereo_ring` | 画面环形缓冲：1=画面延后一点上屏等这一帧自己的深度，0=直采 | 1 |
 * | `stereo_interval` | 抓帧的毫秒下限；0=纯按帧数（每 3 个新视频帧一次） | 0 |
 * | `stereo_up_mode` | 深度上采样：0=双线性（对照档）1=颜色引导（默认）2=双边引导 | 1 |
 * | `stereo_sharp` | 上采样的分层锐化强度（0=不锐化，1=全量；小窗口噪声大时调小） | 1.0 |
 * | `stereo_edge_sigma` | 引导回归的正则项 σe（越小越贴边缘、越大越稳，0.02~0.2） | 0.1 |
 * | `stereo_fill` | 空洞填充：1=沿扫描线找背景补（默认）0=洞也按偏移表走（出白点时对照） | 1 |
 * | `stereo_occl` | 遮挡表：1=用预计算偏移（默认）0=逐像素现算（偏移表算错时对照） | 1 |
 * | `stereo_smooth` | 深度读取：1=三次插值（默认）0=双线性（台阶对照） | 1 |
 * | `stereo_occl_div` | 遮挡表降档：1=原样（默认）2=宽高各减半（采样量 1/4）4=再减半 | 1 |
 * | `stereo_newton` | 牛顿修正轮数 0~2（每轮 6 次深度采样，每帧每眼都跑） | 2 |
 * | `stereo_fill_px` | 补洞搜索半径（画面像素 2~96；洞宽≈视差量，太小跨不过洞、太大会啃前景） | 24 |
 * | `stereo_fill_pick` | 补洞取层：1=就近取紧邻背景（默认）0=全范围内取最远（会把远景铺进人像） | 1 |
 * | `depth_smooth` | 深度时间平滑 0~1：越大越稳越迟（洞判定逐帧翻的根子之一是深度在变） | 0.8 |
 * | `stereo_edge_thr` | 真边缘判据·深度跳变阈值：越大越保守，只在明显的深度断口上补洞 | 0.03 |
 * | `stereo_ring_delay` | 环形缓冲延迟强制值（毫秒，0=自动）；环容量约 150ms，超过就永远配不上深度 | 0 |
 * | `stereo_edge_color` | 真边缘判据·两侧颜色差阈值：越大越保守，同色跳变不算边缘 | 0.06 |
 * | `depth_auto` | 显卡委托建不起来时是否自动换回 int8 小模型（1=换，默认；0=保持原模型跑 CPU） | 1 |
 * | `layer_boost` | 视频层增强（PICO 合成器）：0=关 1=超采样 2=超采样高 3=锐化 4=锐化高 5=自适应锐化 6=自适应锐化高 7=超分辨率（同一层只能开一种） | 7 |
 * | `foveation` | 固定注视点渲染：0=关 1=低 2=中 3=高（边缘降精度换 GPU） | 2 |
 * | `adaptive_res` | 自适应分辨率：0=关 1=高画质 2=平衡 3=省电（这版只上报与记录建议值） | 2 |
 * | `mpv_<属性>` | 透传给内核的同名属性，例如 `mpv_tone-mapping=spline` | — |
 * | `cmd` | 直接执行一条内核命令，例如 `cmd=screenshot-to-file <路径> video`（导出渲染画面，校色用） | — |
 *
 * 生效时机：`super_sample` 在建交换链时读，要重新起播（或重启应用）才生效；
 * 其余参数当场生效。
 */
object VrTuning {

    private const val TAG = "B0BEmbyVR"
    private const val FILE_NAME = "vr-tuning.txt"

    /**
     * 视频处理宽度上限（2026-10-09 黑纹攻坚）。
     *
     * 这是**纯 Kotlin 侧参数**（不下发原生），默认 0 = 沿用 1920。
     *
     * 为什么需要它：实测杜比还原那条链每帧吃约 15ms GPU（关掉它立刻从 22ms 掉到 6.9ms
     * 且回到满帧）。换更便宜的算法没用（tone-mapping=clip / 双线性缩放都更差）——
     * 说明开销主要跟"处理了多少像素"成正比。
     *
     * **默认值 1280（2026-10-09 父亲实测定案）**：
     *   1920 时 GPU 每帧 22ms → 远超 72Hz 的 13.9ms 预算 → 每帧迟到 →
     *   运行时用上一帧做变形补偿 → 画面出现多条横向跳动的黑纹（贯穿整个视野）。
     *   压到 1280 后 GPU 落到 10~12ms，满帧、条纹消失，且画质父亲认可。
     * 配合：播放时自动请求 72Hz（`gRefreshHz=0` 的自动档），预算 13.9ms，刚好装下。
     *
     * **默认值 1280（2026-10-09 实测定案）**：
     * 按 IMAX 银幕（82°）的解析力推算，1664 才算像素够；但实测 1664 会让 GPU
     * 每帧涨到 15.2ms、超出 13.9ms 预算 → 黑纹复现。1280 只要 6.2~8.1ms、满帧，
     * 银幕保持 IMAX 不缩，只是画面略软。
     */
    @Volatile
    var videoSurfaceMaxW: Int = 1280

    /**
     * 「原画」档与网页流的宽度上限（0 = 按片源原分辨率，最高 3840）。
     *
     * 试宽度用：改完不用重装，动一下画质菜单就按新值重建。Emby 的杜比视界片另走
     * videoSurfaceMaxW —— 那条链要单独压低保黑纹不出，不能和这里共用一个数。
     */
    @Volatile
    var rawMaxW: Int = 1920

    /** 地面坡度（2026-10-22）：每往前走 1 米视线变化多少米；默认按影厅实测 0.35（0 = 平地） */
    @Volatile
    var floorSlope: Float = 0.35f

    /**
     * 网页流要不要走零拷贝硬解（2026-10-20 23:40，句柄泄漏）。
     * 1 = 零拷贝（默认，不漏句柄）；0 = 退回 mediacodec-copy（画面若出现横向黑纹就改这个）。
     * 改 vr-tuning.txt 的 `web_zerocopy=0` 即可，无需重装。
     */
    @Volatile
    var webZeroCopy: Int = 1

    /**
     * 弹幕同轨最小间隔（秒，2026-10-26 父亲定）。
     *
     * 固定两行时，1.2 秒会让同屏两条弹幕挤在一起看不清；拉大到 2 秒后同一条轨道
     * 空出一段再放下一条。取舍已定：**宁可丢几条，也不要叠在一起**。
     * 现场改 `vr-tuning.txt` 的 `danmaku_gap=1.5` 即可，一秒内生效、不用重装。
     */
    @Volatile
    var danmakuGapSec: Float = 2.5f

    /**
     * 网页接管的最低片长门槛（秒，2026-10-26 父亲定）。
     *
     * 原来写死 300 秒（防预告/花絮被接管），结果 B站 大量一两分钟的普通视频全被挡在外面
     * （实测：112 秒的短视频，槽里地址已经齐了，原生仍拒收）。
     * 现在地址取得够准，不需要靠片长兜底，改成参数、默认 0 = 不挡。
     * 想恢复过滤就在 `vr-tuning.txt` 写 `web_min_dur=300`，一秒内生效、不用重装。
     */
    @Volatile
    var webMinDurSec: Int = 0

    /**
     * 网页禁用缓存（2026-10-21 12:50）。
     * 1 = 每次请求都走网络 —— 爱奇艺的正片取流请求被缓存住时不发，导致截不到整集片单；
     * 戴着改 `web_nocache=1` 就能让它重新发（代价是页面加载变慢）。
     */
    @Volatile
    var webNoCache: Int = 0

    /**
     * 爱奇艺网页接管总开关（2026-10-21 13:50 立，2026-10-25 改为默认开）。
     *
     * 爱奇艺的整集片单只能由原生自签（IqiyiDash2）取：页面那条只是"当前这一段的裸地址"，
     * 只能播六分钟。所以接管必须走原生这条，默认就开着。
     */
    @Volatile
    var webIqiyi: Int = 1

    /** 上一次已经应用到网页视图的缓存开关值（-1 = 还没应用过） */
    @Volatile
    var webCacheApplied: Int = -1

    /**
     * 杜比视界片要不要也走零拷贝（2026-10-21 00:12）。
     * 1 = 也零拷贝（默认，实测杜比片同样每秒漏 24 个句柄）；0 = 退回复制模式（若画面出横向黑纹就写 0）。
     */
    @Volatile
    var dvZeroCopy: Int = 0

    /**
     * 弹幕画布宽度（2026-10-14 父亲定）：默认 **1920**，与银幕本身的 1920 宽对齐。
     *
     * 原来写死 2560 —— 比银幕还大三分之一，多出来的像素在 VR 里根本看不见，
     * 却要在每帧被整张拷进图层。降到 1920 后画布像素少四成四，弹幕清晰度不变。
     * 改这一个数不用重起播，播放中每秒轮询一次、立刻重建画布。
     */
    var danmakuCanvasW: Int = 1920

    /**
     * 深度推理线程数（1~8，默认 4）。
     *
     * XR2 是 4 小核 + 3 大核 + 1 超大核。实测 6 线程反而更慢（单次推理 110→191ms，
     * 配对率 18%→12%）：多出来的线程和解码、渲染抢 CPU。改文件后要重新起播才生效，
     * 因为解释器只在开 3D 时建一次。
     */
    @Volatile
    var depthThreads: Int = 4

    /**
     * 深度推理走哪条：1 = 显卡委托（默认，参考实现的路子）0 = CPU 四线程。
     *
     * 实测（2026-11-01）：CPU 上 256 int8 单次 110ms、512 fp16 单次 229ms；
     * 参考实现的 25~60ms 不是 CPU 跑出来的。显卡委托建不起来会自动退回 CPU。
     * 改文件后下一次开 3D（或重新起播）生效，不用重装。
     */
    @Volatile
    var depthGpu: Int = 1

    /**
     * 深度模型：-1 = 自动（默认，GPU 配 512 fp16、CPU 配 256 int8）0 = 256 int8dr 1 = 512 fp16。
     *
     * 实测（2026-11-01，CPU 四线程）：256 int8 单次 110ms，512 fp16 单次 229ms。
     * fp16 那款在 CPU 上更慢（像素多 4.5 倍），参考实现的 25~60ms 是靠显卡跑的。
     * 改文件后要重新起播才生效（解释器只在开 3D 时建一次）。
     */
    @Volatile
    var depthModel: Int = -1

    /**
     * 深度在哪里算（父亲 2026-11-02）：0 = 头显本地（256 int8），1 = 点 15 远程（512x288）。
     *
     * 远程这条实测单次往返 22 毫秒（含点 15 上 18 毫秒推理），输入还大一档，
     * 是拿来跟本地对比的；切换当场生效，不用重装。
     */
    @Volatile
    var depthRemote: Int = 0

    /**
     * 远程一批发几帧（1~10）。默认 5。
     *
     * 2026-11-02 头显实测（同一部片、同一网段、每档等队列排空后取 40 秒）：
     * 两帧 345ms、三帧 371ms、四帧 383ms、五帧 405ms、六帧 413ms —— 越小越快，
     * 因为批量越大，一批要等更久才能凑齐，等待时间摊到每帧反而更长。
     */
    var depthBatch: Int = 2

    /** 远程深度服务地址（点 15 的 depth_server.py） */
    @Volatile
    var depthRemoteHost: String = "192.168.150.15:7799"

    /**
     * 网页内核选择（2026-10-14 父亲定）：0 = 系统自带网页视图（Chromium 90，B站慢），
     * 1 = 打进应用里的火狐内核（GeckoView 158）。
     *
     * 运行时切换：改了这一个数，网页那一块下一秒按新引擎整块重建，不用重装。
     */
    var webEngine: Int = 0

    /**
     * 网页点击怎么发：0 = 鼠标式（默认）、1 = 触摸式、2 = 鼠标按键式。
     *
     * 2026-10-14 头显实测记录：
     *   · 0（鼠标式 DOWN/UP）：搜索框能点上，海报点不开；
     *   · 1（触摸式）：搜索能点，海报同样不行；
     *   · 2（鼠标按键式 BUTTON_PRESS/RELEASE）：全部点不动 —— 这条通道白试了。
     * 所以默认回到 0，并把点击序列里夹着的两条"鼠标移动"去掉（那是重复的，还会让
     * 网页的设备语义来回切）。戴着改 `web_click` 可随时切，不用重装。
     */
    var webClickMode: Int = 1

    /**
     * 火狐内核要不要**保留触摸事件**：0 = 关掉（默认）、1 = 保留。
     *
     * 2026-10-15 定：腾讯网页播放页会看"这台设备有没有触摸屏"来挑播放器。头显本身有触摸屏、
     * 网页又被我们装成电脑（桌面 UA + 桌面视口），两边对不上，播放器初始化不了 ——
     * 现象就是播放页只出顶部框架、播放器一片黑、侧栏一直灰色占位。
     * 昨晚唯一一次腾讯正常（r519）就带着"关触摸"，后来因为"包有问题"被误撤，一直黑到现在。
     *
     * 关触摸的做法只能走内核启动参数 `--setpref=dom.w3c_touch_events.enabled=0`
     * （GeckoRuntimeSettings.Builder 没有设置偏好项的方法，r518 编译报过 Unresolved reference）。
     * 内核只建一次，所以改这一项**要重启应用**才生效，改完不用重装。
     */
    var webTouchKept: Int = 0

    /**
     * 腾讯播放页诊断用（2026-10-15）：网页自称的身份 0 = 电脑 Chrome（一直用的那串）、
     * 1 = 电脑 Firefox（与真实内核一致）。
     *
     * 疑点：内核是火狐，却对外自称 Chrome 120；Chrome 那一套还会带「客户端提示」头
     * （Sec-CH-UA 系列），火狐不发。若腾讯的接口按这套头判断，就会看到"自称 Chrome
     * 却没有 Chrome 的证据"，接口不返回 → 播放页只剩框架和灰色占位条。
     * 切这一项**要重启应用**才生效，不用重装（UA 只在网页块建立时读一次）。
     */
    var webUaMode: Int = 0

    /**
     * 腾讯播放页诊断用（2026-10-15）：再打开新窗口时**带不带来源页**。
     * 0 = 不带（现状，用 loadUri）；1 = 带上当前页地址（用 Loader 显式设 Referer）。
     *
     * 差异起点：正常浏览器是从首页**点进去**的，页面拿得到来源页；我们是从代码里直接
     * loadUri，来源页为空。run 528/529 的日志里，播放页那批脚本全被跨域预检拦掉
     * （400/403/405，173 条），而电脑上同一个页面这些脚本都是 200。
     * 这一项做零成本对照：改完重启应用即可，不用重装。
     */
    var webRefOn: Int = 0

    /**
     * 网页块首页地址覆盖（2026-10-15，诊断用）：非空时网页块加载它，替代默认的三个站点首页。
     * 用途：让火狐内核加载本地测试页（`file:///sdcard/preflight-test.html`）做最小复现 ——
     * 换一个试验页面不必重编一次 APK。空串 = 用默认站点首页。
     * 测试页见 `docs/diagnostics/preflight-test.html`。
     */
    var webUrl: String = ""

    /**
     * 额外的内核参数（2026-10-15）：分号分隔的 `键=值`，逐条作为 `--setpref=` 传给火狐内核。
     *
     * 为什么需要它：GeckoView 的 pref 只能在**内核创建时**设一次，照老办法每调一个 pref
     * 就得重编一次 APK。有了这个开关，以后调 pref 只改设备上的 vr-tuning.txt + 重启应用。
     *
     * 本次要用的那条是 `dom.serviceWorkers.enabled=false`：
     * 腾讯播放页注册的 Service Worker 会拦截所有跨域脚本请求再转发，转发在内核里失败，
     * 导致整页脚本加载不上（2026-10-15 日志实证：`A ServiceWorker passed a promise to
     * FetchEvent.respondWith()` 连续被拒 + 每个脚本 `Failed to load`）。关掉它让请求直发。
     */
    var webPrefs: String = ""

    /** 把 [webPrefs] 拆成参数表；不含等号的段一律丢掉（防止写出非法参数把内核创建搞挂）。 */
    val webPrefsList: List<String>
        get() = webPrefs.split(';').map { it.trim() }.filter { it.contains('=') }

    /**
     * 现读现用的 [webPrefs]（2026-10-15 加）。
     *
     * 为什么不能直接用字段：字段是靠**每秒轮询**赋值的，而内核只在创建那一刻接受 pref。
     * 用户点"网页"栏可能早于第一次轮询，那时字段还是空 —— run 533 就是这个原因，
     * 设了 `dom.serviceWorkers.enabled=false` 却完全没传进内核（日志里没有那行参数）。
     */
    fun webPrefsNow(context: Context): List<String> {
        val raw = read(context) ?: return emptyList()
        return raw["web_pref"]?.trim().orEmpty()
            .split(';').map { it.trim() }.filter { it.contains('=') }
    }

    /**
     * `<web_wipe>=1` 时清一次腾讯站的网页数据（2026-10-15）。
     *
     * 存在的理由：腾讯页面注册的 Service Worker 会持久化在 profile 里，即使内核已禁用 SW 能力，
     * 这份残留注册仍在拦截脚本请求（run 534 实证）。清站点数据是去掉它的唯一途径。
     * 清完请把这项改回 0，否则每次启动都会再清一遍、腾讯登录态反复丢。
     */
    fun webWipeNow(context: Context): Boolean {
        val raw = read(context) ?: return false
        return raw["web_wipe"]?.trim() == "1"
    }

    /** 与原生 `nativeSetTuning` 的 key 一一对应 */
    const val KEY_SUPER_SAMPLE = 1
    const val KEY_SWAP_WAIT_MS = 2
    const val KEY_LAYER_MASK = 3
    const val KEY_SYNC_MODE = 4
    const val KEY_REFRESH_HZ = 5
    /* 黑纹诊断开关（2026-10-09）：与原生 nativeSetTuning 的 case 6..12 一一对应 */
    const val KEY_HIDE_PROJECTION = 6
    const val KEY_TEST_PATTERN = 7
    const val KEY_VIDEO_LAYER_MAX_W = 8
    const val KEY_NO_DOWNSAMPLE = 9
    const val KEY_NO_JITTER = 10
    const val KEY_FORCE_RGBA8 = 11
    const val KEY_NO_POSTFX = 12
    const val KEY_FINISH_BEFORE_TEXUPDATE = 13
    /** 银幕几何（2026-10-09）：按「IMAX 第10排」定，也允许运行时调 */
    const val KEY_SCREEN_WIDTH = 14
    const val KEY_SCREEN_DISTANCE = 15
    /** 光柱粗细倍率（2026-10-09）：1.0 = 银幕 3.2 米时代同样的角粗细 */
    const val KEY_RAY_SCALE = 16
    /** 选座（2026-10-10 17:35）：0 = 近排（第 1 排）· 1 = 中排（第 3 排）· 2 = 远排（第 6 排） */
    const val KEY_SCREEN_PRESET = 17
    /** 防穿越：离银幕最近允许多少米（2026-10-10：可以走近银幕，不许穿过） */
    /** 排查日志总开关：1 = 输出全部排查日志，0 = 只留报错与警告（2026-10-30） */
    const val KEY_LOG_VERBOSE = 43
    /** 控制条高度：中心在眼睛下方多少米（负值，2026-10-30 父亲要求做成可调） */
    const val KEY_OSD_Y = 40
    /** 走动判据：一秒内累积路程超过多少米才算在走（抗身体晃动，2026-10-30） */
    const val KEY_WALK_PATH_M = 41
    /** 走动判据：停走后多少毫秒内的重新居中当噪声（2026-10-30） */
    const val KEY_WALK_STILL_MS = 42
    /**
     * 实时 2D→3D 开关：1 = 视频层拆成左右眼两条交换链，推理线程起来。
     * 0 = 整条 3D 链路不参与（分辨率、上采样、环、推理全部回默认），2D 不受影响。
     */
    const val KEY_STEREO3D = 44
    /**
     * 视差强度：UV 单位（占整幅画面宽度的比例），也可以写千分位整数。
     * 标准档 6（= 0.006，与 DepthRay 同值）、舒适档 4（= 0.004）。
     */
    const val KEY_STEREO_DISP = 45
    /** 零视差平面（0 近 … 1 远，默认 0.5 = 银幕所在深度；DepthRay 也是 50/100） */
    const val KEY_STEREO_CONV = 46
    /** 左右边缘淡出宽度（防边缘拉伸竖条） */
    const val KEY_STEREO_FADE = 47
    /** 深度图方向：1 = 模型输出越大越近（用之前取反） */
    const val KEY_DEPTH_INVERT = 48
    /**
     * 画面环形缓冲：1 = 画面延后一点上屏，等这一帧自己的深度，做到逐帧配对。
     * 0 = 直采（深度用最近一次结果，画面不延迟）。
     */
    const val KEY_STEREO_RING = 49
    /**
     * 深度节奏：每几个新视频帧算一次深度（1~6，默认 3）。
     *
     * 1 = 逐帧算，最跟手但和解码渲染抢 CPU；4~6 = 省 CPU，深度更旧、边缘更容易错位。
     */
    const val KEY_DEPTH_EVERY = 60
    /** 局部液化拉伸权重（0 = 关，退回纯偏移；默认 0.65，照参考实现 tachi） */
    const val KEY_STEREO_STRETCH = 61
    /** 局部液化羽化半径，单位是每眼 1920 源宽的像素（默认 96） */
    const val KEY_STEREO_FEATHER = 62
    /** 环延迟强制值（毫秒，0 = 自动）；远程推理迟到固定，用它一次压住 */
    const val KEY_STEREO_RING_DELAY = 63
    /** 边缘加密开关（1 = 偏移表按全分辨率深度算，0 = 老算法）；运行时切换 */
    const val KEY_STEREO_EDGE_DENSE = 64
    /* 远程批量推理「一批几帧」：调大摊薄往返、但迟到变长；调小相反。 */
    const val KEY_DEPTH_BATCH = 65
    /**
     * 遮挡表降档（1 / 2 / 4，默认 1 = 原样）。
     *
     * 遮挡表是链路里最贵的 GPU pass：每像素每只眼沿扫描线扫 2·reach+1 步，
     * 1080p 下一次约一亿九千万次纹理采样，而且每三帧就跑一次。输出宽高各 /2，
     * 采样量降到 1/4。偏移表按 UV 采样、与自身分辨率解耦，降档只损失偏移精度。
     */
    const val KEY_STEREO_OCCL_DIV = 66
    /**
     * 牛顿修正轮数（0~2，默认 2 = 参考实现）。
     *
     * 每轮 6 次深度采样，而且是每帧每眼都跑。配合 KEY_STEREO_SMOOTH 决定采样预算：
     * 三次插值 + 两轮 = 13 次/像素/眼；双线性 + 一轮 = 7 次；双线性 + 零轮 = 5 次。
     */
    const val KEY_STEREO_NEWTON = 67

    /** 补洞搜索半径（画面像素，2~96，默认 24）：洞宽≈视差量，太小跨不过洞、太大会啃到前景 */
    const val KEY_STEREO_FILL_PX = 68

    /** 补洞取层：1 = 就近取紧邻背景（默认），0 = 全范围取最远（对照，会把远景铺进人像） */
    const val KEY_STEREO_FILL_PICK = 69

    /** 深度时间平滑系数（0~1，默认 0.8）：越大越稳、越迟；补洞判定抖的根子之一是深度逐帧变 */
    const val KEY_DEPTH_SMOOTH = 70

    /** 真边缘判据：深度跳变阈值（默认 0.03，越大越保守、补洞越少） */
    const val KEY_EDGE_THR = 71

    /** 真边缘判据：两侧颜色差阈值（默认 0.06，越大越保守、同色跳变不算边缘） */
    const val KEY_EDGE_COLOR = 72

    /**
     * 环形缓冲延迟强制值（毫秒，0 = 按深度迟到自动算）。
     * 环容量 =（槽数-1）× 帧间隔 ≈ 150ms；自动值按 p90 迟到算，本地推理迟到约 200ms 会被
     * 截断到环上限、深度永远配不上帧（实测配对率 1%）。给一个能盖住常见迟到、又不超容量的值。
     */
    const val KEY_RING_DELAY = 63

    /** 面板复用开关：1=海报墙内容不变就不重画（默认）；0=每帧照画（滚动/刷新异常时用来对照） */
    const val KEY_PANEL_REUSE = 75

    /**
     * Warp 输出缩放：1.0=全分辨率；0.65=视差 Warp 只在 65% 像素上算，再边缘感知放大回全分辨率。
     *
     * 缩的只是「每像素十几份深度采样」那一段最贵的计算，交换链始终全分辨率，
     * 所以文字/UI 不受影响，画面边缘靠上采样的深度一致性权重保住。
     */
    const val KEY_WARP_SCALE = 76

    /** 上采样的深度差惩罚强度：越大越"只认同深度的邻点"，边缘更硬但可能出现锯齿 */
    const val KEY_WARP_EDGE_SIGMA = 77

    /**
     * 影厅图层的渲染比例：0.4~1.0，默认 0.75。
     *
     * 影厅是暗场低模，缩小渲染再让合成器放大，肉眼几乎看不出；实测关掉影厅能把
     * 帧率从 54~60 抬到 65~68、GPU 占用从 96% 掉到 55~60%，这一层值得单独给档位。
     */
    const val KEY_CINEMA_SCALE = 78
    /**
     * 抓帧的毫秒下限（0 = 纯按帧数，默认）。
     *
     * 主判据是视频帧数：每 3 个新帧抓一次。这一项只在片源帧率特别高、按帧数算仍然
     * 太密时才用得上，写 0 就是完全不限。
     */
    const val KEY_STEREO_INTERVAL = 51
    /**
     * 深度上采样模式（照 DepthRay 第 10 段着色器）：0 = 双线性（对照档）、1 = 颜色引导（默认）、
     * 2 = 双边引导。引导档在 5×5 窗口里对「低分亮度/低分深度」做加权线性回归，深度边缘贴住
     * 画面真实轮廓；对照档留着重现"边缘发虚"，用来判断这次全屏 pass 值不值。
     */
    const val KEY_STEREO_UP_MODE = 52
    /** 分层锐化强度（把深度按 S 曲线推向窗口两端；平坦面不锐化，避免放大噪声） */
    const val KEY_STEREO_SHARP = 53
    /** 引导回归的正则项 σe（σe² 项；越大越稳、越小越贴边缘） */
    const val KEY_STEREO_EDGE_SIGMA = 54
    /** 空洞填充开关：0 = 空洞像素也走偏移表（出白点时用来判断是不是补洞的锅） */
    const val KEY_STEREO_FILL = 55
    /** 遮挡表开关：0 = 不用预计算偏移，逐像素现算（偏移表算错时用来对照） */
    const val KEY_STEREO_OCCL = 56
    /** 深度读取方式：1 = 三次插值（默认）、0 = 双线性（判断台阶是不是插值造成的） */
    const val KEY_STEREO_SMOOTH = 57
    const val KEY_MIN_SCREEN_GAP = 18
    /** 控制条/菜单/海报墙是否跟随观影位（2026-10-10：走近银幕时控制条留在伸手可及处） */
    const val KEY_FOLLOW_VIEWER = 19
    /** 影厅环境开关（2026-10-10）：0 = 回黑背景 */
    const val KEY_CINEMA_ON = 20
    /** 影厅底光（2026-10-10） */
    const val KEY_CINEMA_AMBIENT = 21
    /** 影院 HDRI 环境光强度（2026-10-10） */
    const val KEY_CINEMA_ENV = 22
    /** 坐姿眼高（米，2026-10-10：坐着看电影） */
    const val KEY_CINEMA_EYE_HEIGHT = 23
    /** 影厅环境亮度条（0~1，2026-10-10：环境光 + 底光一起走，不含画面亮度） */
    const val KEY_CINEMA_BRIGHT = 24
    /** 影厅画在哪一层（1 = 独立底层 · 0 = 主投影层，应急对照用） */
    const val KEY_CINEMA_LAYER = 25
    /** 银幕竖直微调（米，2026-10-10） */
    const val KEY_SCREEN_UP = 26
    /** 座椅高度（米，2026-10-10：正 = 椅子相对人抬高） */
    const val KEY_SEAT_UP = 27
    /** 座椅前后（米，2026-10-10：正 = 人往椅子前部坐） */
    const val KEY_SEAT_FWD = 28
    /** 视频层增强（2026-10-14）：交给 PICO 合成器做超采样/锐化/超分辨率 */
    const val KEY_LAYER_BOOST = 29
    /** 固定注视点渲染（2026-10-14）：视野中心不动，边缘降精度换 GPU */
    const val KEY_FOVEATION = 30
    /** 自适应分辨率（2026-10-14）：档位越高越省 GPU */
    const val KEY_ADAPTIVE_RES = 31

    /** 地面坡度（2026-10-22）：每往前走 1 米，视线随之变化多少米；0 = 平地 */
    const val KEY_FLOOR_SLOPE = 32

    /** 上次应用的配置原文，只有变化才动手（避免每秒重复设置） */
    private var lastRaw: Map<String, String> = emptyMap()
    private var started = false

    fun path(context: Context): String =
        File(context.getExternalFilesDir(null), FILE_NAME).absolutePath

    /**
     * 启动时钟：每秒读一次配置文件。
     *
     * @param onVideoSize 画布尺寸变化（界面层拿它去设内核画布与画面缓冲）
     * @param onMpvOption `mpv_` 前缀的参数，透传给内核
     */
    fun start(
        context: Context,
        scope: CoroutineScope,
        onVideoSize: (Int, Int) -> Unit,
        onMpvOption: (String, String) -> Unit,
        onMpvCommand: (List<String>) -> Unit = {},
    ) {
        if (started) return
        started = true
        Log.i(TAG, "调参文件：${path(context)}（改完自动生效，无需重编）")
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { poll(context, onVideoSize, onMpvOption, onMpvCommand) }
                    .onFailure { Log.w(TAG, "调参轮询异常：${it.message}") }
                delay(1000)
            }
        }
    }

    /**
     * 起播前的同步应用（超采样必须赶在建交换链之前灌进原生层）。
     *
     * 只处理原生侧参数，不碰界面层与内核。
     */
    fun applyStartup(context: Context) {
        val raw = read(context) ?: return
        if (raw.isEmpty()) return
        /*
         * 【2026-10-21 16:10 修「开关写了不生效」】
         *
         * 这里原来只灌原生参数，却把 `lastRaw` 记成整份文件 → 紧接着的轮询看到
         * "文件没变"直接 return，网页那几个开关（`web_iqiyi` / `dv_zerocopy` /
         * `web_nocache` / `web_zerocopy`）**一次都没被解析过**，永远是默认值。
         * 头显实证：vr-tuning.txt 里写着 `web_iqiyi=1`，日志里却一条"爱奇艺自取"
         * 都没有，接管退回旧路（单段裸地址 → 只能播 6 分钟）。
         * 修法：起播时把开关一起解析掉，轮询只负责"之后的变化"。
         */
        val flags = applyFlags(raw)
        if (flags.isNotEmpty()) Log.i(TAG, "调参（起播）→ ${flags.joinToString("，")}")
        lastRaw = raw
        applyNative(raw)
    }

    /** 界面 / 网页层开关：起播与轮询共用一份解析，避免两处漂移 */
    private fun applyFlags(raw: Map<String, String>): List<String> {
        val applied = mutableListOf<String>()
        raw["dv_zerocopy"]?.toIntOrNull()?.let {
            if (it != dvZeroCopy) {
                dvZeroCopy = it
                applied += "杜比零拷贝=" + it
            }
        }
        raw["web_iqiyi"]?.toIntOrNull()?.let {
            if (it != webIqiyi) {
                webIqiyi = if (it == 1) 1 else 0
                applied += "爱奇艺接管=" + if (it == 1) "开" else "关"
            }
        }
        raw["web_nocache"]?.toIntOrNull()?.let {
            if (it != webNoCache) {
                webNoCache = it
                applied += "网页缓存=" + if (it == 1) "禁用" else "正常"
            }
        }
        raw["web_zerocopy"]?.toIntOrNull()?.let {
            if (it != webZeroCopy) {
                webZeroCopy = it
                applied += "网页零拷贝=" + it
            }
        }
        raw["web_min_dur"]?.toIntOrNull()?.let {
            val v = it.coerceIn(0, 7200)
            if (v != webMinDurSec) {
                webMinDurSec = v
                applied += "网页最低片长=" + v + "s"
            }
        }
        raw["danmaku_gap"]?.toFloatOrNull()?.let {
            val v = it.coerceIn(0.6f, 6f)
            if (v != danmakuGapSec) {
                danmakuGapSec = v
                applied += "弹幕间隔=" + v + "s"
            }
        }
        return applied
    }

    private fun poll(
        context: Context,
        onVideoSize: (Int, Int) -> Unit,
        onMpvOption: (String, String) -> Unit,
        onMpvCommand: (List<String>) -> Unit,
    ) {
        val raw = read(context) ?: return
        if (raw.isEmpty() || raw == lastRaw) return
        /*
         * 上一版（父亲 2026-10-10 实测踩坑）：文件里任何一个键变了，就把**整份**配置重发一遍，
         * 包括 mpv_ 那几个内核属性 —— 同一个属性重设会让播放器内核直接 die（当天崩了两次）。
         * 现在按"值真的变了才发"逐个比对，改一个键只动那一件事。
         */
        val prev = lastRaw
        lastRaw = raw

        val applied = mutableListOf<String>()

        val w = raw["video_w"]?.toIntOrNull()
        val h = raw["video_h"]?.toIntOrNull()
        if (w != null && h != null && w >= 64 && h >= 64) {
            onVideoSize(w, h)
            applied += "画布 ${w}x$h"
        }

        /* 四个开关（杜比零拷贝 / 爱奇艺接管 / 网页缓存 / 网页零拷贝）解析在 applyFlags */
        applied += applyFlags(raw)

        applied += applyNative(raw)

        raw.filterKeys { it.startsWith("mpv_") }.forEach { (k, v) ->
            if (prev[k] == v) return@forEach          // 值没变 → 不重发（重设内核属性会崩）
            val prop = k.removePrefix("mpv_")
            onMpvOption(prop, v)
            applied += "内核 $prop=$v"
        }

        /*
         * 调试通道：直接执行一条内核命令。
         *
         * 写法 `cmd=screenshot-to-file /sdcard/Android/data/com.xxxx.emby_vr/files/shot1.png video`
         * 轮询只在"文件原文有变化"时动手，所以同一行只执行一次；想再截一张就改一下文件名。
         */
        raw["cmd"]?.takeIf { it != prev["cmd"] }?.trim()?.takeIf { it.isNotEmpty() }?.let { line ->
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) {
                onMpvCommand(parts)
                applied += "内核命令 ${parts.joinToString(" ")}"
            }
        }

        if (applied.isEmpty()) {
            Log.i(TAG, "调参 → 文件里有内容但没有可识别的参数（键名见 VrTuning 注释）")
        } else {
            Log.i(TAG, "调参生效 → ${applied.joinToString("，")}")
        }
    }

    /** 原生侧参数：超采样 / 等交换链超时 / 层掩码 */
    private fun applyNative(raw: Map<String, String>): List<String> {
        val applied = mutableListOf<String>()
        raw["super_sample"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SUPER_SAMPLE, it)
            applied += "超采样 $it"
        }
        raw["swap_wait_ms"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SWAP_WAIT_MS, it)
            applied += "等图超时 ${it.toInt()}ms"
        }
        raw["refresh_hz"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_REFRESH_HZ, it)
            applied += if (it <= 0f) "刷新率档 自动" else "刷新率档 ${it.toInt()}Hz"
        }
        raw["layer_boost"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_LAYER_BOOST, it)
            applied += "视频层增强 ${it.toInt()}"
        }
        raw["foveation"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_FOVEATION, it)
            applied += "注视点等级 ${it.toInt()}"
        }
        raw["adaptive_res"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_ADAPTIVE_RES, it)
            applied += "自适应分辨率 ${it.toInt()}"
        }
        raw["floor_slope"]?.toFloatOrNull()?.let {
            if (it != floorSlope) {
                floorSlope = it
                VrNative.setTuning(KEY_FLOOR_SLOPE, it)
                applied += "地面坡度=$it"
            }
        }
        raw["sync_mode"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SYNC_MODE, it)
            applied += if (it >= 1f) "同步档 glFinish" else "同步档 glFlush"
        }
        /*
         * 层掩码：写了 `layer_mask` 就整份替换（默认 0x3F 全开）；
         * 只写了单层开关，就在默认值上改那一位。
         * 关掉某位 = 该层**既不提交、也不退回画进眼缓冲**（彻底不画），
         * 这样"减少合成图层数"的阶梯实验才是干净对照（2026-10-09 扩展）。
         */
        val maskText = raw["layer_mask"]
        var wrote = false
        val mask = if (maskText != null) {
            wrote = true
            maskText.toIntOrNull() ?: maskText.removePrefix("0x").toIntOrNull(16)
        } else {
            var m = 0x3F
            listOf(
                "video_layer" to 1, "danmaku_layer" to 2, "logo_layer" to 4,
                "panel_layer" to 8, "osd_layer" to 16, "menu_layer" to 32,
            ).forEach { (n, b) ->
                raw[n]?.toIntOrNull()?.let { v ->
                    m = if (v != 0) m or b else m and b.inv()
                    wrote = true
                }
            }
            m
        }
        /*
         * 黑纹诊断开关（2026-10-09）：每一条都对应 ChatGPT 复盘里的一个判定性实验。
         * 全部改文件即生效，不用重编重装 —— 用户戴着头显就能一档一档试。
         */
        fun flag(name: String, key: Int, label: String) {
            raw[name]?.toFloatOrNull()?.let {
                VrNative.setTuning(key, it)
                applied += "$label ${if (it != 0f) "开" else "关"}"
            }
        }
        flag("hide_projection", KEY_HIDE_PROJECTION, "投影层不提交（只留 quad 层）")
        flag("test_pattern", KEY_TEST_PATTERN, "固定测试图（切断解码输入链）")
        raw["raw_max_w"]?.toIntOrNull()?.let {
            rawMaxW = it.coerceIn(0, 3840)
            applied += "原画宽度上限 ${rawMaxW}（0=片源原尺寸）"
        }
        raw["video_layer_max_w"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_VIDEO_LAYER_MAX_W, it)
            applied += "视频层宽度上限 ${it.toInt()}（0=原分辨率）"
        }
        flag("no_downsample", KEY_NO_DOWNSAMPLE, "视频降采样关闭")
        flag("no_jitter", KEY_NO_JITTER, "抖动固定")
        flag("force_rgba8", KEY_FORCE_RGBA8, "视频层强制 GL_RGBA8")
        flag("no_postfx", KEY_NO_POSTFX, "画质增强全关")
        raw["video_surface_w"]?.toIntOrNull()?.let {
            videoSurfaceMaxW = it
            applied += "视频处理宽度上限 $it（0=1920）"
        }
        raw["depth_gpu"]?.toIntOrNull()?.let {
            depthGpu = if (it == 0) 0 else 1
            applied += "深度推理 ${if (depthGpu == 1) "显卡" else "CPU"}"
        }
        raw["depth_remote"]?.toIntOrNull()?.let {
            depthRemote = if (it == 1) 1 else 0
            applied += "深度计算 ${if (depthRemote == 1) "点 15 远程" else "头显本地"}"
        }
        raw["depth_remote_host"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            depthRemoteHost = it
            applied += "深度服务地址 $it"
        }
        raw["depth_model"]?.toIntOrNull()?.let {
            depthModel = when (it) {
                0 -> 0
                1 -> 1
                else -> -1
            }
            applied += "深度模型 " + when (depthModel) {
                0 -> "256 int8"
                1 -> "512 fp16"
                else -> "自动"
            }
        }
        raw["depth_threads"]?.toIntOrNull()?.let {
            depthThreads = it.coerceIn(1, 8)
            applied += "深度推理线程 $depthThreads（重新起播生效）"
        }
        raw["stereo_stretch"]?.toFloatOrNull()?.let {
            if (it >= 0f && it <= 1f) {
                VrNative.setTuning(KEY_STEREO_STRETCH, it)
                applied += "局部液化拉伸 %.2f".format(it)
            }
        }
        raw["stereo_feather"]?.toFloatOrNull()?.let {
            if (it >= 0f && it <= 300f) {
                VrNative.setTuning(KEY_STEREO_FEATHER, it)
                applied += "局部液化羽化 %.0f px".format(it)
            }
        }
        raw["stereo_edge_dense"]?.toFloatOrNull()?.let {
            if (it >= 0f && it <= 1f) {
                VrNative.setTuning(KEY_STEREO_EDGE_DENSE, it)
                applied += if (it >= 0.5f) "边缘加密开" else "边缘加密关"
            }
        }
        raw["panel_reuse"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_PANEL_REUSE, it)
        }
        raw["warp_scale"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_WARP_SCALE, it)
            applied += "warp缩放 $it"
        }
        raw["cinema_scale"]?.toFloatOrNull()?.let {
            if (it >= 0.4f && it <= 1.0f) {
                VrNative.setTuning(KEY_CINEMA_SCALE, it)
                applied += "影厅比例 $it"
            }
        }
        raw["warp_edge_sigma"]?.toFloatOrNull()?.let {
            if (it >= 0f && it <= 0.5f) {
                VrNative.setTuning(KEY_WARP_EDGE_SIGMA, it)
                applied += "上采样边缘 $it"
            }
        }
        raw["stereo_ring_delay"]?.toFloatOrNull()?.let {
            if (it >= 0f && it <= 1000f) {
                VrNative.setTuning(KEY_STEREO_RING_DELAY, it)
                applied += if (it < 1f) "环延迟自动" else "环延迟强制 %.0fms".format(it)
            }
        }
        raw["depth_batch"]?.toFloatOrNull()?.let {
            val n = it.toInt().coerceIn(1, 10)
            if (n != depthBatch) {
                depthBatch = n
                VrNative.setTuning(KEY_DEPTH_BATCH, n.toFloat())
            }
        }
        raw["depth_every"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_DEPTH_EVERY, it)
            applied += "深度节奏 每 ${it.toInt()} 帧一次"
        }
        raw["danmaku_w"]?.toIntOrNull()?.let {
            danmakuCanvasW = it.coerceIn(960, 3840)
            applied += "弹幕画布宽度 $danmakuCanvasW"
        }
        raw["web_engine"]?.toIntOrNull()?.let {
            webEngine = if (it == 1) 1 else 0
            applied += "网页内核 ${if (webEngine == 1) "火狐" else "系统"}"
        }
        raw["web_click"]?.toIntOrNull()?.let {
            /*
             * 【2026-10-21 10:47 三种语义并存】
             * 0 = 鼠标源触摸（Chromium 不认，留着对照）
             * 1 = 标准触摸（默认：海报/链接都能点，r797 起一直是它）
             * 2 = 鼠标按键式（ACTION_BUTTON_PRESS/RELEASE，走通用运动事件通道）
             *      ——爱奇艺的「清晰度/选集」弹层只认鼠标点击，这个值是为它准备的
             */
            webClickMode = if (it == 2) 2 else if (it == 1) 1 else 0
            applied += "网页点击语义 " + when (webClickMode) { 1 -> "触摸"; 2 -> "鼠标按键式"; else -> "鼠标源触摸" }
        }
        raw["web_touch"]?.toIntOrNull()?.let {
            webTouchKept = if (it == 1) 1 else 0
            applied += "网页触摸事件 ${if (webTouchKept == 1) "保留" else "关掉（改完要重启应用）"}"
        }
        raw["web_ua"]?.toIntOrNull()?.let {
            webUaMode = if (it == 1) 1 else 0
            applied += "网页身份 ${if (webUaMode == 1) "火狐" else "Chrome"}（改完要重启应用）"
        }
        raw["web_ref"]?.toIntOrNull()?.let {
            webRefOn = if (it == 1) 1 else 0
            applied += "网页来源页 ${if (webRefOn == 1) "带上" else "不带"}（改完要重启应用）"
        }
        raw["web_url"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            webUrl = it
            applied += "网页首页改成 $it（改完要重启应用）"
        }
        raw["web_pref"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            webPrefs = it
            applied += "网页内核参数 $it（改完要重启应用）"
        }
        flag("finish_before_texupdate", KEY_FINISH_BEFORE_TEXUPDATE, "取帧前先 glFinish")
        raw["screen_width"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SCREEN_WIDTH, it)
            applied += "银幕宽 ${it}m"
        }
        raw["screen_distance"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SCREEN_DISTANCE, it)
            applied += "银幕距离 ${it}m"
        }
        raw["ray_scale"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_RAY_SCALE, it)
            applied += "光柱粗细倍率 $it"
        }
        /*
         * 选座（父亲 2026-10-10 17:35「我要坐着看电影」）：0 = 近排（第 1 排）·
         * 1 = 中排（第 3 排）· 2 = 远排（第 6 排）。影厅整体跟着挪，银幕距离跟着变。
         */
        raw["seat"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_SCREEN_PRESET, it.toFloat())
            applied += "选座 " + when (it) {
                0 -> "近排（第 1 排）"
                1 -> "中排（第 3 排）"
                2 -> "远排（第 6 排）"
                else -> "$it"
            }
        }
        /* 影厅环境开关（2026-10-10）：0 = 回黑背景 */
        raw["cinema_on"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_ON, it.toFloat())
            applied += "影厅环境 " + if (it != 0) "开" else "关"
        }
        /* 影厅底光（2026-10-10） */
        raw["cinema_ambient"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_AMBIENT, it)
            applied += "影厅底光 ${"%.3f".format(it)}"
        }
        /* 影院 HDRI 环境光强度（2026-10-10，用下载的那套全景图当环境光） */
        raw["cinema_env"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_ENV, it)
            applied += "影院环境光强度 ${"%.2f".format(it)}"
        }
        /* 座椅高度（米，2026-10-10） */
        raw["seat_up"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SEAT_UP, it)
            applied += "座椅高度 ${"%+.3f".format(it)} 米"
        }
        /* 座椅前后（米，2026-10-10） */
        raw["seat_fwd"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SEAT_FWD, it)
            applied += "座椅前后 ${"%+.3f".format(it)} 米"
        }
        /* 银幕竖直微调（米，2026-10-10） */
        raw["screen_up"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_SCREEN_UP, it)
            applied += "银幕竖直微调 ${"%+.2f".format(it)} 米"
        }
        /* 影厅图层模式（2026-10-10）：1 = 独立底层，0 = 画进主投影层（对照） */
        raw["cinema_layer"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_LAYER, it.toFloat())
            applied += "影厅图层 " + if (it != 0) "独立底层" else "主投影层"
        }
        /* 影厅环境亮度条（2026-10-10）：0~1，环境光与底光一起走 */
        raw["cinema_bright"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_BRIGHT, it)
            applied += "影厅环境亮度 ${"%.2f".format(it)}"
        }
        /* 坐姿眼高（2026-10-10）：坐着看电影，地面到眼睛的距离 */
        raw["cinema_eye_height"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_CINEMA_EYE_HEIGHT, it)
            applied += "坐姿眼高 ${"%.2f".format(it)} 米"
        }
        /* 防穿越最近距离（2026-10-10）：走近银幕到这么近时，场景会整体后推，不许穿过 */
        raw["log_verbose"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_LOG_VERBOSE, it)
            applied += if (it >= 1f) "排查日志全开" else "排查日志只在关键处"
        }
        raw["osd_y"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_OSD_Y, it)
            applied += "控制条在眼睛下方 ${"%.2f".format(-it)} 米"
        }
        raw["walk_path_m"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_WALK_PATH_M, it)
            applied += "走动判据 ${"%.2f".format(it)} 米/秒"
        }
        raw["walk_still_ms"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_WALK_STILL_MS, it)
            applied += "停走判定 ${"%.0f".format(it)} 毫秒"
        }
        raw["min_screen_gap"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_MIN_SCREEN_GAP, it)
            applied += "最近离银幕 ${"%.1f".format(it)} 米"
        }
        /* 控制条/菜单是否跟随观影位（2026-10-10）：0 = 固定在原地（不跟） */
        raw["follow_viewer"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_FOLLOW_VIEWER, it.toFloat())
            applied += "跟随观影位 " + if (it != 0) "开" else "关"
        }
        /* 实时 2D→3D（2026-11-01）：开关与三个手感参数 */
        raw["stereo3d"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO3D, it.toFloat())
            applied += "实时 2D→3D " + if (it != 0) "开" else "关"
        }
        raw["stereo_disp"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_DISP, it)
            /* 写 6 / 4 这种整数时它是千分位，落到原生后是 0.006 / 0.004 */
            applied += "3D 视差 ${"%.4f".format(if (it > 1f) it / 1000f else it)}"
        }
        raw["stereo_conv"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_CONV, it)
            applied += "3D 零视差 ${"%.2f".format(it)}"
        }
        raw["stereo_fade"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_FADE, it)
            applied += "3D 边缘淡出 ${"%.3f".format(it)}"
        }
        raw["depth_invert"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_DEPTH_INVERT, it.toFloat())
            applied += "深度方向 " + if (it != 0) "取反" else "原样"
        }
        raw["stereo_ring"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_RING, it.toFloat())
            applied += "环形缓冲 " + if (it != 0) "开" else "关"
        }
        raw["stereo_interval"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_INTERVAL, it.toFloat())
            applied += if (it <= 0) "抓帧按帧数（每 3 帧一次）" else "抓帧毫秒下限 ${it}ms"
        }
        /* 立体画质的六个开关：默认全按 DepthRay，其余档位只为做 A/B */
        raw["stereo_up_mode"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_UP_MODE, it.toFloat())
            applied += "深度上采样 " + when (it) {
                0 -> "双线性（对照）"
                2 -> "双边引导"
                else -> "颜色引导"
            }
        }
        raw["stereo_sharp"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_SHARP, it)
            applied += "分层锐化 ${"%.2f".format(it)}"
        }
        raw["stereo_edge_sigma"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_EDGE_SIGMA, it)
            applied += "引导正则 ${"%.3f".format(it)}"
        }
        raw["stereo_fill"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_FILL, it.toFloat())
            applied += "空洞填充 " + if (it != 0) "开" else "关"
        }
        raw["stereo_occl"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_OCCL, it.toFloat())
            applied += "遮挡表 " + if (it != 0) "用" else "不用"
        }
        raw["stereo_smooth"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_SMOOTH, it.toFloat())
            applied += "深度读取 " + if (it != 0) "三次插值" else "双线性"
        }
        /* 提速 A/B 两档（2026-11-03）：遮挡表降档、牛顿修正轮数，改了当场生效并在日志里对账 */
        raw["stereo_occl_div"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_OCCL_DIV, it.toFloat())
            applied += "遮挡表降档 " + when {
                it >= 4 -> "4（采样 1/16）"
                it >= 2 -> "2（采样 1/4）"
                else -> "关（原样）"
            }
        }
        raw["stereo_newton"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_NEWTON, it.toFloat())
        }
        raw["stereo_fill_px"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_FILL_PX, it)
        }
        raw["stereo_fill_pick"]?.toIntOrNull()?.let {
            VrNative.setTuning(KEY_STEREO_FILL_PICK, it.toFloat())
        }
        raw["stereo_edge_thr"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_EDGE_THR, it)
        }
        raw["stereo_ring_delay"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_RING_DELAY, it)
        }
        raw["stereo_edge_color"]?.toFloatOrNull()?.let {
            VrNative.setTuning(KEY_EDGE_COLOR, it)
        }
        raw["depth_smooth"]?.toFloatOrNull()?.let {
            DepthEngine.smooth = it.coerceIn(0f, 1f)
            Log.i(TAG, "调参 → 深度时间平滑 ${DepthEngine.smooth}")
            applied += "牛顿修正 $it 轮"
        }
        raw["depth_auto"]?.toIntOrNull()?.let {
            DepthEngine.autoFallback = it != 0
            applied += "显卡委托失败回退 " + if (it != 0) "开（换 int8 小模型）" else "关（保持原模型跑 CPU）"
        }

        if (!wrote) return applied
        if (mask != null) {
            VrNative.setTuning(KEY_LAYER_MASK, mask.toFloat())
            applied += "层掩码 0x${mask.toString(16)}"
        }
        return applied
    }

    /**
     * 取配置里的内核选项（去掉 `mpv_` 前缀）。
     *
     * 内核创建那一刻由 MpvBackend 调用一次：app 启动时内核还没建，
     * 那时下发 `mpv_` 选项会失败，必须在每次建后端时重新应用。
     */
    fun mpvOptions(context: Context): Map<String, String> =
        read(context).orEmpty()
            .filterKeys { it.startsWith("mpv_") }
            .mapKeys { it.key.removePrefix("mpv_") }

    /**
     * 读一个键的当前值（界面上的开关要显示"现在是开还是关"）。
     * 文件是唯一事实源：轮询每秒读一次，界面改了就写回文件，两边不会打架。
     */
    fun value(context: Context, key: String): String? = read(context)?.get(key)

    /** 改一个键并落盘（界面开关用）。轮询下一秒会读到并下发，这里同时立即生效。 */
    fun setValue(context: Context, key: String, value: String) {
        try {
            val f = File(context.getExternalFilesDir(null), FILE_NAME)
            val lines = if (f.isFile) f.readLines().toMutableList() else mutableListOf()
            var hit = false
            for (i in lines.indices) {
                val k = lines[i].substringBefore('#').substringBefore('=').trim()
                if (k == key) {
                    lines[i] = "$key=$value"
                    hit = true
                }
            }
            if (!hit) lines.add("$key=$value")
            f.writeText(lines.joinToString("\n") + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "调参文件写入失败：${t.message}")
        }
    }

    /** 读配置；文件不存在或读失败返回 null */
    private fun read(context: Context): Map<String, String>? {
        val f = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!f.isFile) return null
        return try {
            f.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.contains('=') }
                .associate {
                    it.substringBefore('=').trim() to it.substringAfter('=').trim()
                }
        } catch (t: Throwable) {
            Log.w(TAG, "调参文件读取失败：${t.message}")
            null
        }
    }
}
