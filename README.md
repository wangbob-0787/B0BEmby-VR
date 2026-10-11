# B0BEmby VR

PICO 4 上的 Emby 客户端，VR 虚拟影院版本。家庭自用。

## 这是什么

在 PICO 4 头显里用**手柄**浏览 Emby 媒体库，并在虚拟影厅里看片。

- 应用名 `B0BEmby VR`，包名 `com.xxxx.emby_vr`（`app/src/main/res/values/strings.xml`、`app/build.gradle.kts`）。
- 界面层复用电视端 B0BEmby 的整套 Compose 界面（登录 / 首页 / 媒体库 / 搜索 / 详情 / 演员 / 账号 / 设置），
  办法是把它们渲染进一块虚拟显示器，再把画面贴成 VR 里的面板（`app/src/main/java/com/xxxx/emby_vr/panel/PanelLayer.kt`、
  `panel/PanelApp.kt`）。播放屏不用电视版那套，是 VR 原生实现（视频 / 弹幕 / 字幕 / 控制条各占独立图层）。
- 与电视端 B0BEmby 是两个独立应用、两个仓库、两条编译流程，互不影响；复用 Emby 数据层代码与配色规范，
  不共用渲染与输入处理（遥控器焦点模型与 VR 光柱模型无法统一）。

## 设备与运行要求

| 项 | 值 | 依据 |
|---|---|---|
| 设备 | PICO 4（Android 10 / API 29）；实测系统 5.13.7 | `app/build.gradle.kts`、`app/src/main/cpp/native_bridge.cpp` |
| VR 运行时 | 官方 OpenXR（Khronos loader 1.1.49），要求 PICO OS ≥ 5.9；原生库或会话起不来时退化为老的 2D 面板管线，应用不崩 | `gradle/libs.versions.toml`、`app/src/main/cpp/CMakeLists.txt`、`vr/VrNative.kt` |
| 应用类型 | `pvr.app.type=vr`，由系统按 VR 应用合成 | `app/src/main/AndroidManifest.xml` |
| minSdk / targetSdk / compileSdk | 29 / 33 / 36 | `app/build.gradle.kts` |
| ABI | 仅 arm64-v8a | `app/build.gradle.kts` |
| NDK / CMake | 27.0.12077973 / 3.22.1 | `app/build.gradle.kts` |
| JDK | 17 | `app/build.gradle.kts`、`.github/workflows/build.yml` |

## 已实现能力

### 界面与数据

| 能力 | 说明 | 关键实现 |
|---|---|---|
| 登录 | 服务器地址 + 账号密码；另可**手机扫码录入** | `ui/LoginScreen.kt`、`ui/viewmodel/LoginViewModel.kt` |
| 扫码录入 | 头显上显示二维码，手机扫码后打开本地网页填表提交（登录信息 / 搜索词 / 代理配置三种），数据回传头显 | `QrCodeUtils.kt`、`LocalServer.kt`（nanohttpd，三种模式） |
| 首页 | 顶部状态栏、大片头轮播、我的媒体库、继续观看、直播行、搜索浮层 | `ui/HomeScreen.kt`、`ui/components/HomeHero.kt`、`LiveTvRow.kt`、`TopStatusBar.kt`、`SearchOverlay.kt` |
| 媒体库 | 排序与筛选（名称 / 上映日期 / 添加日期 / 评分 / 时长 / 分辨率等，已看 / 未看 / 收藏） | `ui/LibraryScreen.kt`、`ui/viewmodel/LibraryViewModel.kt` |
| 搜索 | 拼音与首字母索引（打 `qyn` 出「庆余年」），也可直接打英文名 | `ui/SearchScreen.kt`、`ui/components/SearchOverlay.kt`、`data/PinyinIndex.kt`、`assets/pinyin_table.txt` |
| 详情页 | 简介、季集、选集、续播、收藏、播放 | `ui/MediaDetailScreen.kt`、`ui/viewmodel/DetailViewModel.kt` |
| 演员页 | 按演员查作品 | `ui/ActorScreen.kt` |
| 账号页 | 多账号保存与切换、退出登录 | `ui/AccountScreen.kt`、`data/session/SessionManager.kt` |
| 代理设置 | HTTP / SOCKS5，可仅代理 Emby 流量，可扫码配置 | `ui/ProxySettingsScreen.kt`、`data/remote/HttpClient.kt` |
| 虚拟键盘 | VR 内输入（英文直打，中文靠拼音候选） | `ui/components/VrKeyboard.kt`、`TvInputDialog.kt` |
| 数据层 | Emby 接口、DTO、会话管理，与电视版同源 | `data/remote/EmbyApi.kt`、`data/remote/HttpClient.kt`、`data/repository/EmbyRepository.kt` |

### VR 与影院

| 能力 | 说明 | 关键实现 |
|---|---|---|
| OpenXR 会话与双眼立体渲染 | 原生层建会话、建双眼交换链、逐帧提交多个图层；面板 / 视频 / 弹幕 / 控制条 / 菜单的纹理由原生建好后回调给 Kotlin（视频走 `GL_TEXTURE_EXTERNAL_OES`） | `app/src/main/cpp/openxr_renderer.cpp`（约 6000 行）、`vr/VrNative.kt`、`vr/VrRenderer.kt` |
| 虚拟影厅 | 3D 几何影厅 + 环境光 + 座椅材质，单独画在底层（不挡住视频 / 海报墙 / 控制条） | `assets/cinema.b0bcin`、`assets/cinema_env.b0benv`、`assets/seat_fabrics.png` |
| 独立图层 | 影厅底层、银幕、视频、弹幕（字幕与片名 logo 同层）、海报墙、控制条、菜单各自成层，互不遮挡 | `app/src/main/cpp/openxr_renderer.cpp` |
| 海报墙（选片） | 播放中也能调出海报墙换片，位置可调且下次打开还原 | `vr/VrNative.kt`（`setPanelPlace` / `getPanelPlace`）、`MainActivity.kt` |
| 控制条（OSD） | 12 颗按钮：字幕、弹幕、快退 10、播放/暂停、快进 10、速度、选集、选片、信息、演职人员、更多、退出 | `panel/PlayerOsd.kt` |
| 播放菜单 | 窄菜单对齐所点按钮，宽菜单（信息 / 演职人员）通栏；「更多」为二级入口（音频 / 质量 / 播放模式 / 缓冲） | `panel/PlayerMenuPanel.kt` |
| 光柱悬停反馈 | 光柱扫过的控件统一点亮 | `panel/VrHover.kt` |
| 光柱点击 | 自建控件坐标表，扣扳机时按坐标命中并触发（Compose 无公开命中测试接口） | `panel/ClickTargets.kt` |
| 手柄输入 | 方向键 / 摇杆 → 焦点，A / 扳机 → 确认，B → 返回，射线 → 悬停即焦点 | `vr/InputRouter.kt`、`MainActivity.kt` |
| 运行时调参 | 参数写在应用私有目录的 `vr-tuning.txt`，adb 可改、约 1 秒生效（画面尺寸、超采样、刷新率、影厅亮度、座椅前后高低等） | `vr/VrTuning.kt` |

### 播放

| 能力 | 说明 | 关键实现 |
|---|---|---|
| 双解码内核 | 默认 Media3 ExoPlayer；杜比视界 Profile 5（以及版本判不出来的杜比视界）、或用户选了图形字幕时，切自带 libmpv 内核 | `player/MpvBackend.kt`、`player/PlaybackFlags.kt`、`data/remote/EmbyApi.kt` |
| 统一播放控制层 | 控制条数据、快进快退、拖进度、暂停续播、倍速都走这一层，界面不直接碰播放器对象 | `player/PlaybackControl.kt` |
| 杜比视界 | 自带内核带 DV RPU 补丁（解决 Profile 5 偏色）；AAR 由本仓库内核工作流编译 | `app/libs/libmpv-dovi.aar`、`kernel/libmpv-dovi/` |
| 音频软解 | 系统不给第三方应用 AC3 / EAC3 / DTS 解码器，靠 FFmpeg 扩展软解，服务端因此不必转码 | `app/libs/media3-ffmpeg-decoder-1.5.0.aar` |
| HLS | 电视直播与需转码片源走 HLS 播放列表 | `gradle/libs.versions.toml`（`media3-exoplayer-hls`） |
| 直通 / 转码 | 由客户端 DeviceProfile（直通 / 转码 / 编解码约束）声明能力，服务端据此决定 | `data/remote/EmbyApi.kt` |
| 字幕 | 外挂 ASS / SSA / SRT / VTT 与内嵌字幕；图形字幕（PGS / VobSub）改走内核自绘 | `data/remote/EmbyApi.kt`、`MainActivity.kt` |
| 音轨切换 | 内核模式交给内核原地切换（不重起播）；系统播放器模式带位置重起播 | `player/MpvBackend.kt`、`MainActivity.kt` |
| 倍速 / 缓冲 | 倍速一档一档调；缓冲四项（起播 / 最小 / 最大 / 重缓冲）可调并落盘 | `ui/components/BufferSettings.kt`、`player/MpvBackend.kt` |
| 选集 / 播放模式 | 选集切换、列表循环 / 单集循环 / 不循环 | `panel/PlayerMenuPanel.kt` |
| 播放进度回传 | 起播 / 进度 / 停止三件套上报服务端 | `data/remote/EmbyApi.kt`（`/Sessions/Playing`、`/Sessions/Playing/Progress`、`/Sessions/Playing/Stopped`） |
| 播完刷新界面 | 退出播放后首页 / 详情页重新拉数据，避免「播放历史没同步」 | `data/PlaybackSync.kt` |

### 弹幕

| 能力 | 说明 | 关键实现 |
|---|---|---|
| 自写弹幕层 | 弹幕与普通字幕分属两层，互不干扰；每帧按播放时间重算坐标，不累加位移 | `danmaku/DanmakuView.kt`、`danmaku/DanmakuSurfacePainter.kt` |
| ASS 解析 | 自己解析，因为 Media3 内置 SSA 解析器不支持 `\move`（滚动弹幕会被堆到底部） | `danmaku/AssDanmakuParser.kt` |
| 弹幕轨识别 | 按文件名（含「弹幕 / danmaku / danmu」）或内容（大量位移标记）判定，避免把普通字幕当弹幕 | `MainActivity.kt` |
| 时间偏移 | 弹幕整体提前 / 延后，最多 ±2 分半，画面与真实进度不受影响 | `MainActivity.kt` |
| 暂停与快进 | 暂停时弹幕停在原地，快进快退期间弹幕位置冻结 | `MainActivity.kt` |
| 画笔随影片比例 | 弹幕画布比例跟影片走，弹幕 / 字幕 / logo 不被拉伸 | `MainActivity.kt` |
| 片名 logo | 画在右下角，从视频层分离成独立图层 | `danmaku/DanmakuSurfacePainter.kt` |

## 仓库结构

```
app/                         Android 模块（Kotlin + Compose + 原生层）
  src/main/java/com/xxxx/emby_vr/
    data/                    Emby 接口、DTO、仓库、会话、拼音索引、播放同步
    ui/                      各屏界面与组件、主题、ViewModel
    vr/                      OpenXR 接入、场景渲染、手柄输入、运行时调参
    panel/                   虚拟显示器 + Presentation 承载的电视版界面、控制条、菜单、光柱交互
    player/                  播放内核封装（ExoPlayer 统一控制层、libmpv 内核、播放标记）
    danmaku/                 弹幕解析、绘制层、字幕行
    scene/                   几何（矩形面片）
    util/                    诊断日志、错误处理
  src/main/cpp/              OpenXR + OpenGL ES 原生渲染（CMake 工程）
  src/main/assets/           影厅几何与环境贴图、拼音表
  libs/                      本地 AAR：DV 内核、FFmpeg 音频软解
kernel/libmpv-dovi/          DV 内核源码（含 ffmpeg RPU 补丁），由仓库内工作流单独编译
.github/workflows/           build.yml（客户端 APK）、kernel.yml（内核 AAR，手动触发）
```

## 构建与装机

- **客户端 APK**：推 `main` 触发 `.github/workflows/build.yml`，同时产 debug 与 release，
  发布到 Release，标签形如 `vbuild-<run_number>`，产物 `app-release.apk`（另有 `app-debug.apk`）。
  Emby 连接参数由 GitHub Secrets 注入（`EMBY_API_KEY` 必填，`EMBY_SERVER`、`EMBY_USER_ID` 选填），
  CI 写入 `local.properties`，不进源码。
- **内核 AAR**：`.github/workflows/kernel.yml` **手动触发**（改 DV 补丁或 FFmpeg 侧时才跑，编 4 个 ABI 很吃额度），
  产物发布到 `kernel-dovi` Release，取回后放 `app/libs/libmpv-dovi.aar`。
- **装机**：一律用 release 包，且**必须带来源参数**，否则 PICO 会把应用标成「未知来源」：

  ```bash
  adb install -r -i com.android.packageinstaller app-release.apk
  ```

  签名：debug 与 release 使用同一套固定证书（`keystore/b0bemby.p12`，与电视版 B0BEmby 相同），
  因此可以覆盖安装，CI 不需要额外配置密钥。

## 已知限制

- 只针对 PICO 4 与 PICO 的 VR 模式做过实机验证；需要 PICO OS ≥ 5.9（OpenXR 支持）。
- 只出 arm64-v8a 包。
- `targetSdk` 固定 33（PICO OS 兼容；本项目不上架 Google Play，release 构建关闭了 lint 致命检查）。
- 应用内不做更新检查，更新走云端编译 + 装机（面板层去掉了更新路由）。
- 字幕位置与字幕同步只有字符串资源残留，VR 版菜单未接入（见下节待办）。
- 图形字幕（PGS / VobSub）只能走自带内核自绘，系统播放器这条路不处理图片字幕。

## 待办

- 字幕位置 / 字幕同步接入 VR 播放菜单（文案已在 `app/src/main/res/values-zh/strings.xml`，代码无引用）。
- 影厅与座椅材质目前一套（`assets/seat_fabrics.png` 为四宫格贴图）。

## 许可与署名

本项目的媒体库浏览与播放客户端基于开源项目
[shareven/openemby_tv](https://github.com/shareven/openemby_tv) 改造（经本项目的电视端 B0BEmby 移植），
上游为 **CC BY-NC 4.0**（署名 — 非商业性使用）。本项目沿用同一许可：

- 保留原作者署名；
- **禁止任何商业用途**。

仅供家庭个人使用。

## 免责声明

非 Emby 官方客户端，与 Emby LLC 无关联。仅供个人学习与家庭使用。
