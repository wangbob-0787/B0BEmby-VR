# B0BEmby VR

PICO 4 上的 Emby 客户端，VR 平面虚拟屏版本。家庭自用。

## 这是什么

B0BEmby（电视端客户端）的 VR 版本。目标是在 PICO 4 头显里用**手柄**浏览 Emby 媒体库、
播放影片。电视端版本继续服务电视与投影，两者互相独立。

## 当前进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 工程骨架 + VR 场景渲染 + 手柄输入 | 进行中 |
| P1 | 平面虚拟屏（贴真实画面） | 待做 |
| P2 | 海报墙 + 射线选中 | 待做 |
| P3 | 播放（ExoPlayer → 纹理） | 待做 |
| P4 | 弹幕 | 待做 |
| P5 | 控制条与设置页，UI 对齐 TV 版 | 待做 |

## 技术路线

- **OpenGL ES 3.0** 渲染，不引 Unity/Godot（包体与编译复杂度都不划算）
- **Media3 ExoPlayer** 播放，画面输出到 Surface，后续接入 OpenXR 的 Android Surface swapchain
- **手柄输入统一成语义动作**（`InputRouter`）：方向键/摇杆 → 焦点移动，A 键/扳机 → 确认，
  B 键 → 返回。射线的悬停等价于焦点。
- 数据层（Emby 接口、DTO、会话管理）从 B0BEmby 电视版移植

设备：PICO 4（Android 10 / API 29）。

## 与电视版的关系

两个独立应用，各自仓库与编译流程。共享配色与尺寸规范、Emby 数据层代码、弹幕解析逻辑；
不共享界面渲染与输入处理（遥控器焦点模型与 VR 指针模型无法统一）。

## 许可与署名

本项目的媒体库浏览与播放客户端基于开源项目
[shareven/openemby_tv](https://github.com/shareven/openemby_tv) 改造。

上游使用 **CC BY-NC 4.0**（署名 — 非商业性使用）许可，本项目沿用同一许可：
- 保留原作者署名
- **禁止任何商业用途**

仅供家庭个人使用。

## 构建

由 GitHub Actions 云端编译，产物发布在 Releases。装机一律使用 `app-release.apk`。

## 免责声明

非 Emby 官方客户端，与 Emby LLC 无关联。仅供个人学习与家庭使用。
