/*
 * B0BEmby VR —— OpenXR 会话与立体渲染（2026-10-05 起）
 *
 * 背景：父亲要把播放界面做成 VR 原生（PICO 影院模式）。原来的「2D 面板模式」下
 * PICO 会把应用放进一块虚拟屏，只给光点与扳机；要拿⭕键、A/B 键、摇杆原始值、
 * 以及把视频/字幕/弹幕/控制条各画成独立图层，必须自己接 VR 运行时。
 *
 * 路线：官方 OpenXR（Khronos loader，PICO OS ≥ 5.9 支持；本机 5.13.7）。
 * 参考实现：PICO 官方 OpenXR_VideoPlayer_Demo 的会话骨架。
 *
 * 这一步（第一步）只做「能出画面」：
 *   黑底 + 正前方 3.2m 一块 16:9 平面（纯色），双眼立体渲染。
 * 出画面后再做：把面板纹理（现有 Compose 界面）贴上去 → 接手柄输入（第二步）。
 */
#include <atomic>
#include <cmath>
#include <cstdio>
#include <chrono>
#include <functional>
#include <cstring>
#include <string>
#include <cstring>
#include <thread>
#include <vector>

#include <android/log.h>
#include <jni.h>

/*
 * 头文件顺序有讲究（run 91 实测踩坑）：
 * OpenXR 的 openxr_platform.h 依赖 GLES 的类型定义，必须在 GLES 头**之后**；
 * 但 NDK 的 GLES2/gl2ext.h 又要求 gl2.h 先定义 GL_APIENTRYP 等宏，
 * 单独先引 gl2ext.h 会报「expected ')' / unknown type name 'GLenum'」。
 * 因此：GLES3/gl3.h（它自己引 gl2.h）→ GLES2/gl2ext.h → openxr 头。
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

/*
 * 平台扩展类型（XrInstanceCreateInfoAndroidKHR / XrGraphicsBindingOpenGLESAndroidKHR /
 * XrSwapchainImageOpenGLESKHR …）都被 openxr_platform.h 用宏开关包着，
 * 不定义这两个宏整段就被跳过 —— run 92 报「unknown type name
 * XrInstanceCreateInfoAndroidKHR」就是这个原因。必须在包含前定义。
 */
#define XR_USE_PLATFORM_ANDROID 1
#define XR_USE_GRAPHICS_API_OPENGL_ES 1

#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define TAG "B0BEmbyVR-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------- 函数指针表
// 按 Khronos 要求：用到的入口点都要先经 xrGetInstanceProcAddr 取一次
// （loader 需要知道应用用了哪些函数，运行时也可以覆盖实现）。
struct XrApi {
    // 手柄输入（XR_EXT / KHR 的标准动作集：aim + trigger + squeeze + thumbstick + 按钮）
    PFN_xrCreateActionSet CreateActionSet = nullptr;
    PFN_xrCreateAction CreateAction = nullptr;
    PFN_xrSuggestInteractionProfileBindings SuggestInteractionProfileBindings = nullptr;
    PFN_xrAttachSessionActionSets AttachSessionActionSets = nullptr;
    PFN_xrCreateActionSpace CreateActionSpace = nullptr;
    PFN_xrLocateSpace LocateSpace = nullptr;
    PFN_xrSyncActions SyncActions = nullptr;
    PFN_xrGetActionStateBoolean GetActionStateBoolean = nullptr;
    PFN_xrGetActionStateFloat GetActionStateFloat = nullptr;
    PFN_xrGetActionStateVector2f GetActionStateVector2f = nullptr;
    PFN_xrGetActionStatePose GetActionStatePose = nullptr;
    PFN_xrStringToPath StringToPath = nullptr;

    PFN_xrInitializeLoaderKHR InitializeLoaderKHR = nullptr;
    PFN_xrGetSystem GetSystem = nullptr;
    PFN_xrEnumerateViewConfigurationViews EnumerateViewConfigurationViews = nullptr;
    PFN_xrEnumerateEnvironmentBlendModes EnumerateEnvironmentBlendModes = nullptr;
    PFN_xrCreateSession CreateSession = nullptr;
    PFN_xrDestroySession DestroySession = nullptr;
    PFN_xrCreateReferenceSpace CreateReferenceSpace = nullptr;
    PFN_xrEnumerateSwapchainFormats EnumerateSwapchainFormats = nullptr;
    PFN_xrCreateSwapchain CreateSwapchain = nullptr;
    PFN_xrDestroySwapchain DestroySwapchain = nullptr;
    PFN_xrEnumerateSwapchainImages EnumerateSwapchainImages = nullptr;
    PFN_xrAcquireSwapchainImage AcquireSwapchainImage = nullptr;
    PFN_xrWaitSwapchainImage WaitSwapchainImage = nullptr;
    PFN_xrReleaseSwapchainImage ReleaseSwapchainImage = nullptr;
    PFN_xrPollEvent PollEvent = nullptr;
    PFN_xrBeginSession BeginSession = nullptr;
    PFN_xrEndSession EndSession = nullptr;
    PFN_xrWaitFrame WaitFrame = nullptr;
    PFN_xrBeginFrame BeginFrame = nullptr;
    PFN_xrEndFrame EndFrame = nullptr;
    PFN_xrLocateViews LocateViews = nullptr;
    PFN_xrGetOpenGLESGraphicsRequirementsKHR GetOpenGLESGraphicsRequirementsKHR = nullptr;
};

XrApi api;

template <typename T>
bool fetch(XrInstance instance, const char *name, T &out) {
    PFN_xrVoidFunction fn = nullptr;
    XrResult r = xrGetInstanceProcAddr(instance, name, &fn);
    if (XR_FAILED(r) || fn == nullptr) {
        LOGE("取函数失败 %s（xrResult=%d）", name, (int) r);
        return false;
    }
    out = reinterpret_cast<T>(fn);
    return true;
}

bool fetchAll(XrInstance instance) {
    bool ok = true;
    ok &= fetch(instance, "xrGetSystem", api.GetSystem);
    ok &= fetch(instance, "xrEnumerateViewConfigurationViews", api.EnumerateViewConfigurationViews);
    ok &= fetch(instance, "xrEnumerateEnvironmentBlendModes", api.EnumerateEnvironmentBlendModes);
    ok &= fetch(instance, "xrCreateSession", api.CreateSession);
    ok &= fetch(instance, "xrDestroySession", api.DestroySession);
    ok &= fetch(instance, "xrCreateReferenceSpace", api.CreateReferenceSpace);
    ok &= fetch(instance, "xrEnumerateSwapchainFormats", api.EnumerateSwapchainFormats);
    ok &= fetch(instance, "xrCreateSwapchain", api.CreateSwapchain);
    ok &= fetch(instance, "xrDestroySwapchain", api.DestroySwapchain);
    ok &= fetch(instance, "xrEnumerateSwapchainImages", api.EnumerateSwapchainImages);
    ok &= fetch(instance, "xrAcquireSwapchainImage", api.AcquireSwapchainImage);
    ok &= fetch(instance, "xrWaitSwapchainImage", api.WaitSwapchainImage);
    ok &= fetch(instance, "xrReleaseSwapchainImage", api.ReleaseSwapchainImage);
    ok &= fetch(instance, "xrPollEvent", api.PollEvent);
    ok &= fetch(instance, "xrBeginSession", api.BeginSession);
    ok &= fetch(instance, "xrEndSession", api.EndSession);
    ok &= fetch(instance, "xrWaitFrame", api.WaitFrame);
    ok &= fetch(instance, "xrBeginFrame", api.BeginFrame);
    ok &= fetch(instance, "xrEndFrame", api.EndFrame);
    ok &= fetch(instance, "xrLocateViews", api.LocateViews);
    ok &= fetch(instance, "xrGetOpenGLESGraphicsRequirementsKHR",
                api.GetOpenGLESGraphicsRequirementsKHR);

    /*
     * 手柄输入相关入口点。这些是扩展函数，某些运行时可能不提供；
     * 单独取、失败只记日志不整体失败 —— 没有它们画面照样能渲染，
     * 只是收不到手柄输入（诊断阶段这点很重要，别因为输入不可用就黑屏）。
     */
    const bool inputOk = fetch(instance, "xrCreateActionSet", api.CreateActionSet)
            && fetch(instance, "xrCreateAction", api.CreateAction)
            && fetch(instance, "xrSuggestInteractionProfileBindings",
                     api.SuggestInteractionProfileBindings)
            && fetch(instance, "xrAttachSessionActionSets", api.AttachSessionActionSets)
            && fetch(instance, "xrCreateActionSpace", api.CreateActionSpace)
            && fetch(instance, "xrLocateSpace", api.LocateSpace)
            && fetch(instance, "xrSyncActions", api.SyncActions)
            && fetch(instance, "xrGetActionStateBoolean", api.GetActionStateBoolean)
            && fetch(instance, "xrGetActionStateFloat", api.GetActionStateFloat)
            && fetch(instance, "xrGetActionStateVector2f", api.GetActionStateVector2f)
            && fetch(instance, "xrGetActionStatePose", api.GetActionStatePose)
            && fetch(instance, "xrStringToPath", api.StringToPath);
    LOGI("手柄输入入口点：%s", inputOk ? "齐备" : "部分缺失（输入不可用，渲染不受影响）");
    return ok;
}

// ---------------------------------------------------------------- 矩阵工具
// 全部按 OpenGL 列主序：m[列 * 4 + 行]
struct Mat4 {
    float m[16];
};

Mat4 identity() {
    Mat4 r{};
    r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.f;
    return r;
}

Mat4 multiply(const Mat4 &a, const Mat4 &b) {
    Mat4 r{};
    for (int c = 0; c < 4; c++) {
        for (int row = 0; row < 4; row++) {
            float s = 0.f;
            for (int k = 0; k < 4; k++) s += a.m[k * 4 + row] * b.m[c * 4 + k];
            r.m[c * 4 + row] = s;
        }
    }
    return r;
}

/*
 * 注意：OpenXR 核心头里**没有** XrMatrix4x4f（那是 OpenXR-SDK 示例工程
 * （hello_xr 的 xr_linear.h）里的工具类型）。这里自己做投影矩阵。
 */
Mat4 perspectiveFromFov(const XrFovf &fov, float nearZ, float farZ) {
    const float tanL = tanf(fov.angleLeft);
    const float tanR = tanf(fov.angleRight);
    const float tanU = tanf(fov.angleUp);
    const float tanD = tanf(fov.angleDown);
    const float tanW = tanR - tanL;
    const float tanH = tanU - tanD;

    Mat4 r{};
    r.m[0] = 2.f / tanW;                                   // 列 0
    r.m[5] = 2.f / tanH;                                   // 列 1
    r.m[8] = (tanR + tanL) / tanW;                         // 列 2
    r.m[9] = (tanU + tanD) / tanH;
    r.m[10] = -(farZ + nearZ) / (farZ - nearZ);
    r.m[11] = -1.f;
    r.m[14] = -(2.f * farZ * nearZ) / (farZ - nearZ);
    return r;
}

/** 头姿（LOCAL 空间）→ 视图矩阵（世界→相机），刚性变换直接取转置+平移 */
Mat4 viewMatrixFromPose(const XrPosef &pose) {
    const float x = pose.orientation.x, y = pose.orientation.y;
    const float z = pose.orientation.z, w = pose.orientation.w;
    const float xx = x * x, yy = y * y, zz = z * z;
    const float xy = x * y, xz = x * z, yz = y * z;
    const float wx = w * x, wy = w * y, wz = w * z;

    // 旋转矩阵 R（列主序）
    float r[9] = {
            1 - 2 * (yy + zz), 2 * (xy + wz), 2 * (xz - wy),
            2 * (xy - wz), 1 - 2 * (xx + zz), 2 * (yz + wx),
            2 * (xz + wy), 2 * (yz - wx), 1 - 2 * (xx + yy),
    };
    // 视图 = R^T | -R^T * t
    Mat4 v = identity();
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) v.m[j * 4 + i] = r[i * 3 + j];
    const float tx = pose.position.x, ty = pose.position.y, tz = pose.position.z;
    v.m[12] = -(v.m[0] * tx + v.m[4] * ty + v.m[8] * tz);
    v.m[13] = -(v.m[1] * tx + v.m[5] * ty + v.m[9] * tz);
    v.m[14] = -(v.m[2] * tx + v.m[6] * ty + v.m[10] * tz);
    return v;
}

Mat4 translateScale(float tx, float ty, float tz, float sx, float sy) {
    Mat4 r = identity();
    r.m[0] = sx;
    r.m[5] = sy;
    r.m[12] = tx;
    r.m[13] = ty;
    r.m[14] = tz;
    return r;
}

/**
 * 四元数 → 旋转矩阵（列主序）。
 * 与官方 XrQuaternionf_RotateVector3f（xr_linear.h，PICO Native OpenXR SDK 自带）同一套公式。
 */
Mat4 rotationFromQuat(const XrQuaternionf &q) {
    const float x = q.x, y = q.y, z = q.z, w = q.w;
    // 行主序的 3x3
    const float r[9] = {
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w),       2 * (x * z + y * w),
            2 * (x * y + z * w),     1 - 2 * (x * x + z * z),   2 * (y * z - x * w),
            2 * (x * z - y * w),     2 * (y * z + x * w),       1 - 2 * (x * x + y * y),
    };
    Mat4 m = identity();
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++) m.m[j * 4 + i] = r[i * 3 + j];
    return m;
}

/** 位移 × 旋转 × 缩放（官方 UpdateRay 里的 handScale 就是这么用的） */
Mat4 poseScaleModel(const XrPosef &pose, float sx, float sy, float sz) {
    Mat4 s = identity();
    s.m[0] = sx;
    s.m[5] = sy;
    s.m[10] = sz;
    Mat4 m = multiply(rotationFromQuat(pose.orientation), s);
    m.m[12] = pose.position.x;
    m.m[13] = pose.position.y;
    m.m[14] = pose.position.z;
    return m;
}

// ---------------------------------------------------------------- 着色器
const char *kQuadVs = R"(
#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uMvp;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
)";

const char *kQuadFs = R"(
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
uniform vec4 uColor;
uniform int uUseTexture;
uniform int uCircle;          // 1 = 只保留方形里的内切圆（光点用）
uniform int uExpandRange;     // 1 = 把「有限范围」(16-235) 的视频拉回全范围
uniform float uBrightness;    // 画面亮度（控制条上可调，1.0 = 原样）
uniform float uContrast;      // 对比度（1.0 = 原样）
uniform float uSaturation;    // 饱和度（1.0 = 原样）
uniform float uSharpen;       // 锐度（0 = 不锐化）
uniform float uTemperature;   // 色温（-1 冷 … 0 原样 … +1 暖）
uniform vec2 uTexel;          // 视频纹理一个像素的 UV 步长（锐化用）
uniform vec2 uScreenStep;     // 银幕上一个屏幕像素对应的 UV 步长（降采样用）
uniform int uDownsample;      // 1 = 视频降采样（4×4 盒式平均）
uniform float uJitter;        // 每帧变的抖动种子（打散 8 位量化的色带）
uniform int uTestPattern;     // 1 = 用固定测试图替代视频纹理（黑纹诊断，2026-10-09）
uniform samplerExternalOES uTexture;
out vec4 fragColor;

/* 抖动用的哈希（打散 8 位量化色带）*/
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec4 outColor;
    if (uTestPattern == 1) {
        /*
         * 固定测试图（2026-10-09 黑纹诊断）：完全不依赖视频输入链路。
         *
         * 用途：把 mpv / MediaCodec / SurfaceTexture 这条输入链整个切掉，
         * 只保留"写交换链 + 提交图层"的路径。若测试图也有黑纹 → 黑纹不需要视频链路；
         * 若测试图干净 → 视频输入链路（外部纹理/OES 采样/帧交付）是必要条件。
         * 图里带细横线与竖条，便于发现"横条状未更新"。
         */
        /*
         * 图样改版（2026-10-09）：**不再画任何细线**。
         *
         * 上一版画了 100 条 0.2 像素粗的细线，缩放后会混成摩尔纹、显出几根粗带 ——
         * 父亲把它当成黑纹，等于测试图自己制造了干扰。现在改成：
         *   · 左边 3/4：8 段大块纯色（亮，条纹最容易看见）
         *   · 右边 1/4：一条水平灰阶（最容易被"横条"破坏，一眼能看出来）
         * 这样画面上任何线条都只可能来自真实的显示问题。
         */
        vec3 col;
        if (vUv.x < 0.75) {
            float bars = floor(vUv.x / 0.75 * 8.0);
            if (bars < 1.0)      col = vec3(1.0, 1.0, 1.0);
            else if (bars < 2.0) col = vec3(1.0, 1.0, 0.0);
            else if (bars < 3.0) col = vec3(0.0, 1.0, 1.0);
            else if (bars < 4.0) col = vec3(0.0, 1.0, 0.0);
            else if (bars < 5.0) col = vec3(1.0, 0.0, 1.0);
            else if (bars < 6.0) col = vec3(1.0, 0.0, 0.0);
            else if (bars < 7.0) col = vec3(0.0, 0.0, 1.0);
            else                 col = vec3(1.0, 1.0, 1.0);
        } else {
            float g = 1.0 - vUv.y;          // 上黑下白的平滑灰阶
            col = vec3(g, g, g);
        }
        outColor = vec4(col, 1.0);
    } else if (uUseTexture == 1) {
        vec4 c;
        if (uDownsample == 1) {
            /*
             * 视频降采样（父亲 2026-10-06：画面「有失真」，并「极度怀疑采样丢了细节」）。
             *
             * 4K 画面贴到银幕上，银幕在单眼画面里只占一千多像素宽，缩到约三分之一。
             * 单点采样（双线性）只看周围 4 个像素，缩这么狠时细线条和小亮点会时有时无。
             *
             * 但单纯平均也不对：盒式平均会把星星这类极小的亮点一起摊平、变淡。
             * 所以用高斯加权取 16 点 —— 中心那个点权重最高，邻域次之，角上最低。
             * 既压住了缩小时的混叠（不再闪），又尽量把细节点状物留住。
             */
            float w[16] = float[16](
                    1.0, 2.0, 2.0, 1.0,
                    2.0, 4.0, 4.0, 2.0,
                    2.0, 4.0, 4.0, 2.0,
                    1.0, 2.0, 2.0, 1.0);
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    vec2 o = (vec2(float(i), float(j)) - vec2(1.5)) * 0.5 * uScreenStep;
                    acc += texture(uTexture, vUv + o).rgb * w[i * 4 + j];
                }
            }
            c = vec4(acc / 36.0, 1.0);
        } else {
            c = texture(uTexture, vUv);
        }
        if (uExpandRange == 1) {
            /*
             * 父亲 2026-10-06：「视频还是灰蒙蒙，像蒙了一层纱」。
             *
             * 片源是「有限范围」（黑=16、白=235），硬件按「全范围」显示时整体被压扁：
             * 黑不黑、白不白、对比度低，观感就是蒙了一层纱。这里按标准公式拉回全范围。
             * 只对视频纹理开 —— 面板/控制条是我们自己按全范围画的，动了会过曝。
             */
            c.rgb = clamp((c.rgb - 0.0625) * 1.164, 0.0, 1.0);
        }
        outColor = c;
    } else if (uCircle == 1) {
        // 圆形光点：方形面片上按 UV 半径裁掉四角，边缘做 1 像素软化
        float d = length(vUv - vec2(0.5));
        if (d > 0.5) discard;
        outColor = vec4(uColor.rgb, uColor.a * smoothstep(0.5, 0.44, d));
    } else {
        outColor = uColor;
    }
    /*
     * 画面调整（父亲 2026-10-06「画面太亮」+「调图像的功能都加上」）。
     * 顺序固定：亮度 → 对比度 → 饱和度 → 锐度，最后夹到 0~1。
     * 这些都在「显示空间」里做，跟人眼直觉一致（先调，再做输出转换）。
     */
    outColor.rgb *= uBrightness;
    outColor.rgb = (outColor.rgb - 0.5) * uContrast + 0.5;
    float luma = dot(outColor.rgb, vec3(0.2126, 0.7152, 0.0722));
    outColor.rgb = mix(vec3(luma), outColor.rgb, uSaturation);
    if (uTemperature != 0.0) {
        // 色温：暖了抬红压蓝，冷了反过来
        outColor.rgb *= vec3(1.0 + uTemperature * 0.10, 1.0, 1.0 - uTemperature * 0.10);
    }
    if (uSharpen > 0.001 && uUseTexture == 1) {
        /*
         * 锐化：中心与四邻域均值之差加回去（近似 unsharp mask）。
         * 只在视频上做 —— 面板/控制条是文字界面，锐化只会生出毛边。
         */
        vec3 nb = texture(uTexture, vUv + vec2(uTexel.x, 0.0)).rgb
                + texture(uTexture, vUv - vec2(uTexel.x, 0.0)).rgb
                + texture(uTexture, vUv + vec2(0.0, uTexel.y)).rgb
                + texture(uTexture, vUv - vec2(0.0, uTexel.y)).rgb;
        outColor.rgb += (outColor.rgb - nb * 0.25) * uSharpen;
    }
    outColor.rgb = clamp(outColor.rgb, 0.0, 1.0);

    /*
     * 输出到 sRGB 交换链（父亲 2026-10-06：画面「有色斑」）。
     *
     * 来龙去脉：先把交换链改成线性，是为了治「蒙了一层纱」—— 那时候怀疑硬件做了两遍 sRGB。
     * 改完亮度是对了，但暗部的色带冒出来了：8 位线性在暗部只有几个码值，天空、暗场景的
     * 渐变就会一条一条的（色斑）。
     * 正确做法是两头都要：交换链留在 sRGB（暗部码值多、不色带），但我们写进去的值必须先
     * 转成线性 —— 这样硬件再编码一次正好还原成原来的值，亮度不会像当初那样被抬亮。
     * 最后再撒一点点抖动，把 8 位量化剩下的色带打散成看不清的噪点。
     */
    outColor.rgb = mix(
            outColor.rgb / 12.92,
            pow((outColor.rgb + 0.055) / 1.055, vec3(2.4)),
            step(vec3(0.04045), outColor.rgb));
    outColor.rgb += (hash21(vUv * 1024.0 + uJitter) - 0.5) / 255.0;
    fragColor = outColor;
}
)";

GLuint compile(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        LOGE("着色器编译失败: %s", log);
    }
    return s;
}

GLuint buildProgram() {
    GLuint vs = compile(GL_VERTEX_SHADER, kQuadVs);
    GLuint fs = compile(GL_FRAGMENT_SHADER, kQuadFs);
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        LOGE("着色器链接失败: %s", log);
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
}

/*
 * ==================== 影厅环境 + 选座（父亲 2026-10-10 17:35「我要坐着看电影」） ====================
 *
 * 不再是"两块银幕尺寸"，而是**在影厅里换排**：近 / 中 / 远 = 影厅第 1 / 3 / 6 排。
 * 影厅是真实坡度（每排抬高 0.3 米，银幕中心固定在厅里、在座位区地面之上 2.3 米），
 * 所以换排时整间影厅要同时平移 y 与 z —— 让**选中那一排的座位正好落在父亲身上**：
 *   dy / dz = 影厅几何（assets/cinema.b0bcin）的平移量，米；
 *   distance = 那一排眼睛到银幕的距离，直接写进 gScreenDistance
 *              （视频层、弹幕层、光柱、进度环都读这个值，所以一处改、处处对）。
 *
 * 数值怎么来的（不许手改，改就重算）：
 *   影厅模型第 k 排：座位下沿 y = -0.5 + 0.3(k-1)，眼睛在座位上方 1.15 米（坐姿）；
 *   第 k 排眼睛到银幕：z 距离 3.44 / 5.14 / 7.74 米（第 1 / 3 / 6 排）。
 *   导出资产时已把"第 1 排眼睛"放在原点，所以 dy = -(该排眼高) + 1.65、dz = -(该排眼距) + 3.2。
 */
struct CinemaSeat {
    const char *name;
    float dy;         // 影厅整体上下平移（米）
    float dz;         // 影厅整体前后平移（米）
    float distance;   // 该排眼睛到银幕的距离（米）
};
constexpr CinemaSeat kCinemaSeats[3] = {
        {"近排 · 第 1 排", 0.50f, -0.24f, 3.44f},
        {"中排 · 第 3 排", -0.10f, -1.94f, 5.14f},
        {"远排 · 第 6 排", -1.00f, -4.54f, 7.74f},
};
/** 影厅这块银幕宽度：模型银幕墙 5.65 米，留边取 5.2 米（父亲 2026-10-10 之前定的那套） */
constexpr float kSeatScreenWidth = 5.2f;
/** 当前座位：0 近 / 1 中 / 2 远（父亲坐在影厅里换排） */
std::atomic<int> gSeat{0};
/** 影厅环境总开关（0 = 回到黑背景，出问题时可远程关掉） */
std::atomic<int> gCinemaOn{1};
/**
 * 坐姿眼高（米）：影厅座位区地面到眼睛的距离，vr-tuning 的 cinema_eye_height 可调。
 *
 * 父亲 2026-10-10 装机实测：「椅子太高了，座椅台面跑到我胸口了」——
 * 这个模型是按"站着的 1.65 米眼高"建的，座椅本身就偏高偏大；按真实坐姿 1.15 米摆，
 * 椅面只落在眼睛下方 0.37 米（胸口高度）。取 1.50 米把它压回大腿高度
 * （椅面约在眼睛下方 0.72 米），看上去才是"坐在椅子上"。
 */
std::atomic<float> gCinemaEyeHeight{1.50f};

// ------------------------------------------------- 影厅环境（父亲 2026-10-10）
/*
 * 影厅几何 assets/cinema.b0bcin —— 由 tools/build_cinema_asset.py 生成。
 *
 * 源模型：Sketchfab `cinema/movie theater_[interior]` by **Comicaroid**（CC-BY，须署名）。
 * 处理：烘焙节点变换（模型是毫米建的）→ 摆位（转 180°、银幕对到 -z、第 1 排眼睛落在原点）
 *      → 减面到约 18 万面 → 顶点色合并成单一缓冲。
 *
 * 为什么自带 loader：现有渲染器只有"画方片"一条路，没有 glTF 解析器；
 * 这个格式就是几段裸数组（见文件头注释），读进来直接上传，比引第三方库省事也更可控。
 *
 * 深度：影厅是 3D 几何，必须开深度测试（原来全关、纯 2D 叠层），所以眼缓冲加了深度附件；
 * 影厅画完立刻关掉深度，后面的视频/面板/弹幕照旧按图层顺序叠着画。
 */
const char *kCinemaVs = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
layout(location = 2) in vec4 aCol;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vNrm;
out vec4 vCol;
out vec3 vPos;
void main() {
    vNrm = mat3(uModel) * aNrm;
    vCol = aCol;
    vPos = (uModel * vec4(aPos, 1.0)).xyz;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
)";

const char *kCinemaFs = R"(#version 300 es
precision mediump float;
in vec3 vNrm;
in vec4 vCol;
in vec3 vPos;
uniform vec3 uScreenPos;     // 银幕中心（世界坐标）—— 影厅里唯一的主光源
uniform vec3 uScreenTint;    // 银幕发光的颜色（跟画面联动，父亲后面要"画面照亮影厅"）
uniform float uScreenGlow;   // 银幕光强
uniform float uAmbient;      // 底噪环境光（暗厅，但不至于全黑）
uniform vec3 uEye;           // 当前眼睛位置（用于双面法线）
uniform sampler2D uEnv;      // 影院 HDRI（Poly Haven CC0）降采样成 256×128 的环境光
uniform float uEnvStrength;  // 环境光强度（vr-tuning 的 cinema_env）
out vec4 fragColor;

/** 世界方向 → 等距柱状投影 UV（环境贴图是 360×180 全景） */
vec2 envUv(vec3 dir) {
    const float kPi = 3.14159265358979;
    float u = atan(dir.z, dir.x) / (2.0 * kPi) + 0.5;
    float v = acos(clamp(dir.y, -1.0, 1.0)) / kPi;
    return vec2(u, v);
}

void main() {
    vec3 n = normalize(vNrm);
    if (dot(n, normalize(uEye - vPos)) < 0.0) n = -n;   // 双面：暗厅里背面也要有亮度
    vec3 L = uScreenPos - vPos;
    float d = length(L);
    L /= max(d, 0.001);
    float lam = max(dot(n, L), 0.0);
    float atten = uScreenGlow / (1.0 + 0.06 * d * d);
    float ceiling = max(dot(n, vec3(0.0, 1.0, 0.0)), 0.0) * 0.06;   // 顶灯一点余光
    /*
     * 环境光：拿 HDRI 沿法线方向采一次（降采样后本身就非常糊，等于粗糙辐照度），
     * 给暗厅一点真实的"影院空气感"——墙面、地毯、座椅不至于纯黑。
     */
    vec3 env = texture(uEnv, envUv(n)).rgb * uEnvStrength;
    vec3 lit = vCol.rgb * (uAmbient + ceiling + env + lam * atten * uScreenTint);
    fragColor = vec4(lit, vCol.a);
}
)";

GLuint gCinemaProgram = 0;
GLint gCinemaMvpLoc = -1;
GLint gCinemaModelLoc = -1;
GLint gCinemaScreenPosLoc = -1;
GLint gCinemaTintLoc = -1;
GLint gCinemaGlowLoc = -1;
GLint gCinemaAmbientLoc = -1;
GLint gCinemaEyeLoc = -1;
GLint gCinemaEnvLoc = -1;
GLint gCinemaEnvStrengthLoc = -1;
/** 环境光贴图（影院 HDRI，256×128 RGB；由 Java 侧把 assets 里的 .b0benv 拷出来再加载） */
GLuint gEnvTex = 0;
std::atomic<float> gEnvStrength{2.6f};
/*
 * 影厅几何与环境光贴图的文件路径：由 Java 侧先把 assets 里的文件拷到应用目录，
 * 再把路径传进来（native 读不到 APK 里的 assets）。真正的读盘+上传发生在这里——
 * 在渲染线程、EGL 上下文就绪之后，GL 对象才建得对。
 */
std::string gCinemaAssetPath;
std::string gEnvAssetPath;
GLuint gCinemaVbo = 0;
GLuint gCinemaIbo = 0;
int gCinemaIndexCount = 0;
/** 法线 / 顶点色两段在 VBO 里的字节偏移（加载时算好，绘制时用） */
long gCinemaNormalOffset = 0;
long gCinemaColorOffset = 0;
std::atomic<bool> gCinemaReady{false};
/** 影厅参数（可在 vr-tuning.txt 里改：cinema_glow / cinema_ambient / cinema_on） */
std::atomic<float> gCinemaGlow{1.35f};
std::atomic<float> gCinemaAmbient{0.05f};
/** 影厅环境亮度条 0~1（父亲 2026-10-10）：同时驱动环境光强度与底光 */
std::atomic<float> gCinemaBright{0.45f};
/** 银幕发出的光色（默认中性白；后面按视频画面实时取样） */
std::atomic<float> gScreenTintR{1.f};
std::atomic<float> gScreenTintG{1.f};
std::atomic<float> gScreenTintB{1.f};

GLuint buildCinemaProgram() {
    GLuint vs = compile(GL_VERTEX_SHADER, kCinemaVs);
    GLuint fs = compile(GL_FRAGMENT_SHADER, kCinemaFs);
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        LOGE("影厅着色器链接失败: %s", log);
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
}

/** 读 cinema.b0bcin（格式见文件头注释），上传顶点/索引缓冲 */
bool loadCinemaAsset(const char *path) {
    FILE *f = fopen(path, "rb");
    if (f == nullptr) {
        LOGE("影厅几何打开失败：%s", path);
        return false;
    }
    char magic[8] = {0};
    uint32_t geomCount = 0, vcount = 0, icount = 0, matCount = 0;
    float posOffset[3] = {0, 0, 0}, bmin[3] = {0, 0, 0}, bmax[3] = {0, 0, 0};
    bool ok = fread(magic, 1, 8, f) == 8 && memcmp(magic, "B0BCIN1", 7) == 0 &&
              fread(&geomCount, sizeof(uint32_t), 1, f) == 1 &&
              fread(&vcount, sizeof(uint32_t), 1, f) == 1 &&
              fread(&icount, sizeof(uint32_t), 1, f) == 1 &&
              fread(posOffset, sizeof(float), 3, f) == 3 &&
              fread(bmin, sizeof(float), 3, f) == 3 &&
              fread(bmax, sizeof(float), 3, f) == 3 &&
              fread(&matCount, sizeof(uint32_t), 1, f) == 1;
    if (ok && matCount > 0) {
        ok = fseek(f, (long) (4 * matCount), SEEK_CUR) == 0;
    }
    std::vector<float> pos, nrm;
    std::vector<unsigned char> col;
    std::vector<uint32_t> idx;
    if (ok && vcount > 0 && icount > 0) {
        pos.resize((size_t) vcount * 3);
        nrm.resize((size_t) vcount * 3);
        col.resize((size_t) vcount * 4);
        idx.resize(icount);
        ok = fread(pos.data(), sizeof(float), pos.size(), f) == pos.size() &&
             fread(nrm.data(), sizeof(float), nrm.size(), f) == nrm.size() &&
             fread(col.data(), 1, col.size(), f) == col.size() &&
             fread(idx.data(), sizeof(uint32_t), idx.size(), f) == idx.size();
    }
    fclose(f);
    if (!ok) {
        LOGE("影厅几何解析失败（magic/长度不符）");
        return false;
    }

    if (gCinemaVbo == 0) glGenBuffers(1, &gCinemaVbo);
    if (gCinemaIbo == 0) glGenBuffers(1, &gCinemaIbo);
    glBindBuffer(GL_ARRAY_BUFFER, gCinemaVbo);
    glBufferData(GL_ARRAY_BUFFER,
                 (GLsizeiptr) ((size_t) vcount * (3 + 3) * sizeof(float) +
                               (size_t) vcount * 4), nullptr, GL_STATIC_DRAW);
    glBufferSubData(GL_ARRAY_BUFFER, 0, (GLsizeiptr) (pos.size() * sizeof(float)),
                    pos.data());
    glBufferSubData(GL_ARRAY_BUFFER, (GLintptr) (pos.size() * sizeof(float)),
                    (GLsizeiptr) (nrm.size() * sizeof(float)), nrm.data());
    glBufferSubData(GL_ARRAY_BUFFER,
                    (GLintptr) ((pos.size() + nrm.size()) * sizeof(float)),
                    (GLsizeiptr) col.size(), col.data());
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, gCinemaIbo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, (GLsizeiptr) (idx.size() * sizeof(uint32_t)),
                 idx.data(), GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    gCinemaIndexCount = (int) idx.size();
    gCinemaNormalOffset = (long) (pos.size() * sizeof(float));
    gCinemaColorOffset = (long) ((pos.size() + nrm.size()) * sizeof(float));
    gCinemaReady.store(true);
    LOGI("影厅几何已加载：%u 顶点 / %u 三角形（%s）", vcount, icount / 3, path);
    return true;
}

/** 读 cinema_env.b0benv（256×128 RGB888），上传成 GL 纹理 */
bool loadEnvAsset(const char *path) {
    FILE *f = fopen(path, "rb");
    if (f == nullptr) {
        LOGE("环境光贴图打开失败：%s", path);
        return false;
    }
    char magic[8] = {0};
    uint32_t w = 0, h = 0;
    bool ok = fread(magic, 1, 8, f) == 8 && memcmp(magic, "B0BENV1", 7) == 0 &&
              fread(&w, sizeof(uint32_t), 1, f) == 1 &&
              fread(&h, sizeof(uint32_t), 1, f) == 1 && w > 0 && h > 0 && w <= 4096 &&
              h <= 4096;
    std::vector<unsigned char> px;
    if (ok) {
        px.resize((size_t) w * h * 3);
        ok = fread(px.data(), 1, px.size(), f) == px.size();
    }
    fclose(f);
    if (!ok) {
        LOGE("环境光贴图解析失败（magic/长度不符）");
        return false;
    }
    if (gEnvTex == 0) glGenTextures(1, &gEnvTex);
    glBindTexture(GL_TEXTURE_2D, gEnvTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, (GLsizei) w, (GLsizei) h, 0, GL_RGB,
                 GL_UNSIGNED_BYTE, px.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);
    LOGI("环境光贴图已加载：%ux%u（%s）", w, h, path);
    return true;
}

/** 画影厅：模型矩阵 = 换排平移；光源就是银幕 */
void drawCinema(const Mat4 &proj, const Mat4 &view, const XrVector3f &eyePos) {
    if (!gCinemaReady.load() || gCinemaOn.load() == 0 || gCinemaProgram == 0) return;
    const int seat = gSeat.load();
    const CinemaSeat &s = kCinemaSeats[seat];

    Mat4 model = identity();
    model.m[13] = s.dy + (1.65f - gCinemaEyeHeight.load());   // 坐姿眼高可调：眼高变了，厅跟着上下挪
    model.m[14] = s.dz;
    const Mat4 mvp = multiply(multiply(proj, view), model);

    glUseProgram(gCinemaProgram);
    glUniformMatrix4fv(gCinemaMvpLoc, 1, GL_FALSE, mvp.m);
    glUniformMatrix4fv(gCinemaModelLoc, 1, GL_FALSE, model.m);
    /* 银幕中心：世界坐标里就在观影位正前方 distance 米处（影厅平移也跟着走） */
    glUniform3f(gCinemaScreenPosLoc, 0.f, 0.55f, -s.distance);
    glUniform3f(gCinemaTintLoc, gScreenTintR.load(), gScreenTintG.load(),
                gScreenTintB.load());
    glUniform1f(gCinemaGlowLoc, gCinemaGlow.load());
    glUniform1f(gCinemaAmbientLoc, gCinemaAmbient.load());
    glUniform3f(gCinemaEyeLoc, eyePos.x, eyePos.y, eyePos.z);
    /* 环境光：HDRI 放 1 号纹理单元（0 号单元是运行时的 OES 视频纹理，别抢） */
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, gEnvTex);
    if (gCinemaEnvLoc >= 0) glUniform1i(gCinemaEnvLoc, 1);
    if (gCinemaEnvStrengthLoc >= 0) glUniform1f(gCinemaEnvStrengthLoc, gEnvStrength.load());
    glActiveTexture(GL_TEXTURE0);

    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LESS);
    glDisable(GL_CULL_FACE);
    glBindBuffer(GL_ARRAY_BUFFER, gCinemaVbo);
    /*
     * 缓冲是「三段连续」而不是交错：[所有位置][所有法线][所有顶点色]，
     * 所以每个属性各自 stride = 0（紧密排列），靠偏移跳到自己的那段。
     */
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, (void *) 0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 0,
                          (void *) (size_t) gCinemaNormalOffset);
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, GL_TRUE, 0,
                          (void *) (size_t) gCinemaColorOffset);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, gCinemaIbo);
    glDrawElements(GL_TRIANGLES, gCinemaIndexCount, GL_UNSIGNED_INT, nullptr);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glDisableVertexAttribArray(2);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glDisable(GL_DEPTH_TEST);
}

// ---------------------------------------------------------------- 会话上下文
struct EyeSwapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    int32_t width = 0;
    int32_t height = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    std::vector<GLuint> fbos;
    /* 深度附件（影厅几何要深度测试；面板/视频照旧不测深度） */
    std::vector<GLuint> depthRbos;
};

/**
 * 视频独立合成层用的交换链（父亲 2026-10-06 定的大方向）。
 *
 * 原来的路子：视频先缩到银幕在单眼画面里占的那点像素（约三分之一），再随整幅画面
 * 一起被镜片校正重采样 —— 「缩小」发生在我们这里，细信息就丢在这一步。
 * 换成独立图层：我们只把视频原样搬进一块和视频同尺寸的缓冲，缩放到面板分辨率那一步
 * 交给系统合成器做，不再经过我们自己的画面缓冲。这是专业 VR 播放器的做法。
 */
/**
 * 弹幕层比银幕靠前多少米、宽度是银幕的多少倍。
 *
 * 2026-10-07 父亲定：Z 不要和银幕差太多，弹幕要**几乎贴在画面**上 ——
 * 原来靠前 0.35 米（-2.85 vs 银幕 -3.2），弹幕浮在画面前面一大截；
 * 现在只留 2 厘米，与转圈那条（+0.012）同一个量级，肉眼就是贴在屏幕上。
 * 层序仍由提交顺序保证（视频层先提交在下、弹幕层后提交在上），不靠这点 Z 差。
 * 宽度倍率 0.92 保留（弹幕层比银幕小一圈，四边各内缩 4%）。
 */
constexpr float kDanmakuNearer = 0.02f;
/*
 * 弹幕层相对银幕的宽度系数。
 *
 * 2026-10-07 父亲：弹幕层贴到银幕上（kDanmakuNearer=0.02）之后，原来靠
 * "离眼睛近 0.35m"补出来的透视放大没有了，0.92 就露出银幕四周一圈，
 * 看着像没盖住银幕。改成 1.0 —— 与银幕等宽，正好铺满。
 */
constexpr float kDanmakuScale = 1.0f;

struct VideoLayerBuf {
    bool built = false;              // 交换链建好了（尺寸匹配当前视频）
    bool submitted = false;          // 本帧有内容、可以提交
    XrSwapchain handle = XR_NULL_HANDLE;
    int32_t width = 0, height = 0;   // 缓冲尺寸 = 视频分辨率（上限 3840×2160）
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    std::vector<GLuint> fbos;
    uint32_t index = 0;              // 本帧写好待提交的那张
};

/** 一块虚拟屏的摆位：中心、朝向（偏航 + 仰角）、宽度（米） */
struct ScreenPlacement {
    float cx, cy, cz;
    float yawDeg;    // 绕 Y 轴偏航（左右）
    float pitchDeg;  // 绕 X 轴仰角（上下；拖动时用来始终正对人）
    float width;
    float aspect;    // 宽/高（银幕按片子实际比例调，父亲 2026-10-06）
};

/** 正前方那块屏（银幕）：播放时贴视频，没播时是暗色空屏 */
constexpr ScreenPlacement kFrontScreen{0.f, 0.f, -3.2f, 0.f, 0.f, 3.5f, 16.f / 9.f};

/*
 * 银幕尺寸与距离（2026-10-09 父亲定：按「标准 IMAX 影厅第 10 排」的观影几何）。
 *
 * 专业依据：IMAX 的坐席深度刻意做得很浅，行业经典规则是「最后一排到银幕 ≈ 1 个银幕高度」。
 * 以 IMAX with Laser 常见的 1.90:1 银幕（约 26 m × 13.7 m、15~18 排、总深约 25 m）为例：
 *   第 10 排（约 2/3 深度）距银幕 ≈ 15 m → 水平视角 2·atan(13/15) ≈ 82°
 * 经典 1.43:1 IMAX（22 m × 15.4 m）：第 10 排 ≈ 13 m → 2·atan(11/13) ≈ 80°
 * 取两者下限 78° 落到我们的 16:9 银幕：
 *   宽 5.2 m、距离 3.2 m → 水平 2·atan(2.6/3.2) ≈ 78°，垂直 2·atan(1.4625/3.2) ≈ 49°
 * 对照：THX/SMPTE 对普通影厅的要求是最远座 ≥36°、最佳座约 45~50° —— 所以这个值
 * 明显比普通影厅大，观感就是"巨幕压在眼前"。
 *
 * 两者都做成运行时可调（vr-tuning.txt 的 `screen_width` / `screen_distance`），
 * 免得以后调尺寸还要重新编译。
 */
/*
 * 默认 = 方案 C「照实」：26 m 宽的银幕放在 15 m 外（父亲 2026-10-09 选定）。
 *
 * 与真实 IMAX 第 10 排同一组比例（26/15），水平视角 2·atan(13/15) ≈ 81.6°、
 * 垂直（16:9 内容）≈ 52°。**画面观感与"缩放到 5.2 m / 3.2 m"完全一样**
 * （角度相同），区别只在深度感：
 *   · 眼睛会聚接近平行 —— 与真影厅一致，也是 VR 里最舒服的注视距离；
 *   · 走动一步，银幕的角大小几乎不变（真影厅行为）；小距离构型会明显"胀大"；
 *   · 与既有摆位规则天然契合：银幕/弹幕固定在世界里、控制条与海报墙跟随观影位。
 * 前提：走动的活动范围要有边界（别穿到银幕后面）。
 */
/*
 * 光柱粗细倍率（父亲 2026-10-09：银幕挪到 15 米后那根光柱"像拿着一根棍"，要细一点）。
 * 距离缩放保证角粗细不随银幕远近变，这个倍率是在此基础上再乘一档。
 * 1.0 = 与银幕 3.2 米时代同样的角粗细；0.35 = 现在采用的细光柱。
 */
std::atomic<float> gRayScale{0.35f};

std::atomic<float> gScreenWidth{26.f};
std::atomic<float> gScreenDistance{15.f};



/*
 * ==================== 观影位锚点 / 走动（父亲 2026-10-10） ====================
 *
 * 父亲原话：「改成不固定屏幕和观影位距离，我可以走近屏幕，只是不允许穿过屏幕」。
 *
 * 做法：
 *   1) 银幕、视频层、弹幕层固定在世界里（本来就是）—— 他往前走，银幕角尺寸自然变大；
 *   2) 头显位置每帧读一次，算「他离银幕还有多远」；距离小于 gMinScreenGap（默认 1.0 m）时，
 *      把整个场景往远处推同样多的量 —— 到墙根就推不动了，永远不会穿到银幕背后；
 *   3) 控制条 / 菜单 / 海报墙（panelPlacement 那套）跟随观影位（父亲 2026-10-07 的规则），
 *      他走近银幕时，控制条仍在伸手可及的位置，不会留在身后。
 *
 * 注意：起点是**开应用那一刻**的头显位置（首次读到有效位姿时记录），所有计算都用相对量。
 */
std::atomic<float> gViewerX{0.f};        // 平滑后的相对左右位移（相对起点）
std::atomic<float> gViewerZ{0.f};        // 平滑后的相对前后位移（相对起点）
std::atomic<float> gScenePush{0.f};      // 防穿越：场景整体往远处推多少米
std::atomic<float> gMinScreenGap{1.0f};  // 最近允许走到离银幕多远
std::atomic<int> gFollowViewer{1};       // 控制条/菜单/海报墙是否跟随观影位（运行时可关）
std::atomic<float> gViewerOriginX{0.f};
std::atomic<float> gViewerOriginZ{0.f};
std::atomic<bool> gViewerOriginSet{false};
/** 头部位置读数日志的节流（~1 秒一次），诊断用 */
std::chrono::steady_clock::time_point gLastHeadLog{};

/** 头部位姿 → 观影位锚点 + 防穿越推量。每帧在 LocateViews 成功之后调一次。 */
void updateViewerAnchor(const XrPosef &head) {
    const float hx = head.position.x;
    const float hz = head.position.z;
    if (!gViewerOriginSet.load()) {
        gViewerOriginX.store(hx);
        gViewerOriginZ.store(hz);
        gViewerOriginSet.store(true);
        LOGI("观影位锚点 → 起点记为 x=%.2f z=%.2f（后续所有走动都相对这个点）",
             (double) hx, (double) hz);
    }
    // 低通平滑（0.25）：位姿有轻微抖动，直接跟随会让控制条抖
    const float px = gViewerX.load(), pz = gViewerZ.load();
    const float sx = px + ((hx - gViewerOriginX.load()) - px) * 0.25f;
    const float sz = pz + ((hz - gViewerOriginZ.load()) - pz) * 0.25f;
    gViewerX.store(sx);
    gViewerZ.store(sz);

    /*
     * 防穿越：他离银幕（未推状态）的距离 = 前后位移 + 银幕距离。
     * 小于 min_screen_gap 时，场景整体后推差值 —— 效果 = 到墙根推不动。
     */
    const float dist = sz + gScreenDistance.load();
    const float gap = gMinScreenGap.load();
    gScenePush.store(dist < gap ? gap - dist : 0.f);

    // 1 秒一次的位置日志：诊断"走动到底有没有被头显报出来"就靠它
    const auto now = std::chrono::steady_clock::now();
    if (now - gLastHeadLog > std::chrono::seconds(1)) {
        gLastHeadLog = now;
        LOGI("观影位：x=%+.2f z=%+.2f（离银幕 %.2f 米，场景后推 %.3f 米）",
             (double) sx, (double) sz, (double) dist, (double) gScenePush.load());
    }
}

/** 跟随观影位的 XY 偏移（gFollowViewer=0 时为 0，退回固定世界摆位） */
float viewerOffsetX() {
    return gFollowViewer.load() != 0 ? gViewerX.load() : 0.f;
}
float viewerOffsetZ() {
    return gFollowViewer.load() != 0 ? gViewerZ.load() : 0.f;
}

/** 换排：整间影厅平移 + 银幕距离跟着变（父亲戴着时靠这行日志确认换到哪排） */
void applySeat(int seat) {
    if (seat < 0) seat = 0;
    if (seat > 2) seat = 2;
    gSeat.store(seat);
    gScreenWidth.store(kSeatScreenWidth);
    gScreenDistance.store(kCinemaSeats[seat].distance);
    LOGI("选座 → %s：屏幕距离 %.2f 米 · 影厅平移 dy=%.2f dz=%.2f（水平视角 %.1f°）",
         kCinemaSeats[seat].name, (double) kCinemaSeats[seat].distance,
         (double) kCinemaSeats[seat].dy, (double) kCinemaSeats[seat].dz,
         (double) (2.0 * atan((kSeatScreenWidth * 0.5) / kCinemaSeats[seat].distance) *
                   180.0 / 3.14159265358979));
}
/**
 * 海报墙的**初始**摆位：左前方、斜着正对观影者。
 *
 * 父亲 2026-10-05 定：进 VR 空间就是「前方一块银幕、左侧斜放海报墙」。
 * 之后可以用光柱按住扳机把它拖走（球面移动，见 pushInput），
 * 所以真正的位置记在 VrContext 的 panelPos* 里，这里只是起点。
 */
constexpr ScreenPlacement kSideScreen{-2.25f, -0.05f, -2.05f, 46.f, 0.f, 2.8f, 16.f / 9.f};

/*
 * 刷新率切换（XR_FB_display_refresh_rate，父亲 2026-10-06）：
 * 头文件（Khronos loader prefab 自带的 openxr.h）未必包含这个厂商扩展，缺了就自己声明，
 * 名字与规范一致。PICO 运行时支持它（PICO 自带播放器播片时就在跑 72Hz）。
 */
#ifndef XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME
#define XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME "XR_FB_display_refresh_rate"
#endif

typedef XrResult(XRAPI_PTR *PFN_xrRequestDisplayRefreshRateAVS)(XrSession session,
                                                               float displayRefreshRate);

struct VrContext {
    JavaVM *jvm = nullptr;
    jobject activity = nullptr;   // 全局引用

    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    /** 刷新率切换（XR_FB_display_refresh_rate）：播放中 72Hz，界面 90Hz */
    PFN_xrRequestDisplayRefreshRateAVS requestRefreshRate = nullptr;
    XrSpace localSpace = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool sessionRunning = false;

    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLConfig eglConfig = nullptr;
    EGLContext eglContext = EGL_NO_CONTEXT;
    EGLSurface eglSurface = EGL_NO_SURFACE;

    std::vector<EyeSwapchain> eyes;
    /*
     * 影厅独立底层（父亲 2026-10-10 装机实测后改的架构）。
     *
     * 图层顺序里投影层在最上面，而影厅是布满整个视野的 3D 几何 —— 画进投影层就等于
     * 把下面的视频、弹幕、海报墙、控制条、菜单全糊住（父亲实测：一转头控制条闪一下
     * 局部就没了，把影厅关掉它们立刻回来）。
     *
     * 所以影厅自己拿一对交换链，作为**最底下**的投影层提交；原来那层投影层保持
     * 透明背景，只画光柱这类近场元素。谁都不挡谁，视频与面板的独立层清晰度也不受影响。
     */
    std::vector<EyeSwapchain> cinemaEyes;
    bool cinemaEyesOk = false;
    int64_t eyeFormat = 0;
    std::vector<XrViewConfigurationView> viewConfigs;

    GLuint program = 0;
    GLint mvpLoc = -1;
    GLint colorLoc = -1;
    GLint useTexLoc = -1;
    GLint texLoc = -1;
    GLint circleLoc = -1;          // uCircle：纯色画成圆点还是方块
    GLint expandLoc = -1;          // uExpandRange：视频有限范围 → 全范围
    GLint brightLoc = -1;          // uBrightness：画面亮度（控制条上可调）
    GLint contrastLoc = -1;        // uContrast：对比度
    GLint satLoc = -1;             // uSaturation：饱和度
    GLint sharpenLoc = -1;         // uSharpen：锐度
    GLint tempLoc = -1;            // uTemperature：色温
    GLint texelLoc = -1;           // uTexel：视频纹理像素步长
    GLint screenStepLoc = -1;      // uScreenStep：屏幕像素对应的 UV 步长（降采样）
    GLint downLoc = -1;            // uDownsample：是否对视频做盒式降采样
    GLint jitterLoc = -1;          // uJitter：抖动种子
    GLint testPatternLoc = -1;     // uTestPattern：固定测试图（黑纹诊断）
    GLuint vbo = 0;                // 单位方块（面板 / 光点）
    /*
     * 手柄放着不动就把激光收起来（父亲 2026-10-07）。
     * aimLastMoveMs 记"上次判定手柄在动"的时刻，aimLastPose 是上次比较用的姿态。
     */
    double aimLastMoveMs[2] = {0.0, 0.0};
    XrPosef aimLastPose[2] = {};
    bool aimPoseInit[2] = {false, false};

    GLuint rayVbo = 0;             // 手柄射线网格（圆锥）
    int rayVertexCount = 0;
    GLuint triVbo = 0;             // 实心三角（加载转圈的箭头）

    // ---- VR 输入回推给 Java（光柱 → 面板点击/滚动，2026-10-05）----
    jobject inputSink = nullptr;        // VrNative.InputSink 的全局引用
    jmethodID sinkPointer = nullptr;
    jmethodID sinkClick = nullptr;
    jmethodID sinkStick = nullptr;
    jmethodID sinkBack = nullptr;
    jmethodID sinkOsdPointer = nullptr; // 控制条上的指针
    jmethodID sinkOsdClick = nullptr;   // 控制条上的点击
    jmethodID sinkMenuPointer = nullptr; // 展开菜单上的指针
    jmethodID sinkMenuClick = nullptr;   // 展开菜单上的点击
    jmethodID sinkToggleOsd = nullptr;  // 播放中扣扳机 = 开关控制条
    jmethodID sinkPanelFocus = nullptr; // 光柱是否落在海报墙上（决定 B 键给谁）
    jmethodID sinkVideoFrame = nullptr; // 视频画面首次到纹理层（换片黑幕收起用）
    bool sinkPointerValid = false;      // 上一次回推的指针位置（只在明显移动时回推）
    float sinkPointerX = 0.f;
    float sinkPointerY = 0.f;
    double sinkLastToggleMs = 0.0;      // 上一次开关控制条的时刻（去抖）
    bool sinkOsdPointerValid = false;   // 控制条上的指针位置
    float sinkOsdPointerX = 0.f;
    float sinkOsdPointerY = 0.f;
    bool sinkOsdPressed = false;
    // 上一次采样时，光柱是不是指着控制条（离开时补一个面板外坐标，清掉悬停高亮）
    bool sinkOsdOnPanel = false;
    bool sinkMenuPointerValid = false;  // 展开菜单上的指针位置
    bool sinkMenuPressed = false;       // 展开菜单上的扳机态（亮度条要按住拖）
    float sinkMenuPointerX = 0.f;
    float sinkMenuPointerY = 0.f;
    bool sinkLastTrigger[2] = {false, false};
    /* 扳机按住的**状态**（不是边沿）：界面要拿它做"按住连发"（父亲 2026-10-09） */
    jmethodID sinkTriggerState = nullptr;
    bool sinkLastTriggerState[2] = {false, false};
    bool sinkPanelFocusOn[2] = {false, false};   // 上一次回推的"光柱在海报墙上"状态

    /**
     * 海报墙当前位置与朝向（父亲 2026-10-06：按住扳机拖它，在以手柄为球心的球面上挪）。
     * 初值 = kSideScreen（左前方斜放）；拖动时更新位置，朝向始终对着观影者。
     */
    std::atomic<float> panelPosX{kSideScreen.cx};
    std::atomic<float> panelPosY{kSideScreen.cy};
    std::atomic<float> panelPosZ{kSideScreen.cz};
    std::atomic<float> panelYawDeg{kSideScreen.yawDeg};
    std::atomic<float> panelPitchDeg{kSideScreen.pitchDeg};

    /** 海报墙宽度（米）：握着握把键推摇杆左右可以缩放（父亲 2026-10-06） */
    std::atomic<float> panelWidth{kSideScreen.width};

    /**
     * 当前片子的宽高比（宽/高）。ExoPlayer 报一次、Java 侧推过来，
     * 银幕按它调高度 —— 否则 2.35:1 的片子会被拉成 16:9（父亲 2026-10-06）。
     */
    std::atomic<float> videoAspect{16.f / 9.f};

    /**
     * 弹幕画布的像素尺寸：宽固定 2560，高 = 2560 ÷ 影片比例（Java 侧算好推来）。
     *
     * 父亲 2026-10-07：弹幕层原来固定 16:9，贴到银幕上后遇到 4:3 或宽银幕片，
     * 画布被拉伸 → 弹幕字、片名 logo、字幕、快进快退进度条全都变形；
     * 反过来锁死 16:9 又会让弹幕跑出画面。现在画布比例 = 影片比例，
     * 层尺寸仍跟银幕（等宽），两边一致 → 不变形也不出画面。
     */
    std::atomic<int32_t> danmakuPxW{2560};
    std::atomic<int32_t> danmakuPxH{1440};
    /**
     * 画面亮度/对比度/饱和度（父亲 2026-10-06：「调图像的功能都加上」）。
     */
    std::atomic<float> brightness{1.f};
    std::atomic<float> contrast{1.f};
    std::atomic<float> saturation{1.f};
    std::atomic<float> sharpen{0.f};
    std::atomic<float> temperature{0.f};
    /** 视频纹理一个像素的 UV 步长（锐化用，随视频尺寸更新）*/
    std::atomic<float> texelX{1.f / 1920.f};
    std::atomic<float> texelY{1.f / 1080.f};
    /** 视频独立合成层（父亲 2026-10-06）：建不起来就退回老路，画进我们自己的画面 */
    VideoLayerBuf videoLayer;
    bool videoLayerOk = true;      // 运行时拒绝过就永久关掉，免得每帧报错
    /*
     * 弹幕独立合成层（父亲 2026-10-06 晚定）：弹幕与字幕离开视频画面，做成
     * 单独一层摆在银幕前面一点点。原来画在视频层里 / GL 场景里，前者会跟着
     * 视频缩放、后者被视频层整个盖住。这块缓冲与视频层同一套机制。
     */
    VideoLayerBuf danmakuLayer;
    bool danmakuLayerOk = true;    // 同上：运行时拒绝过就永久关掉
    /*
     * 海报墙独立合成层（2026-10-09 讨论后实做）：海报墙面板按纹理原始分辨率
     * （1920×1080）直接交给系统合成器，不再画进眼睛画布被降采样糊掉。
     * 海报墙是不透明面板（底色由 Compose 画好），提交时不带 alpha 混合标志。
     */
    VideoLayerBuf panelLayer;
    bool panelLayerOk = true;
    /*
     * 空屏层（2026-10-09 父亲实测）：未播放时，正前方那块暗色银幕原来画在投影层里，
     * 而投影层永远在最上面 —— 它会把海报墙独立层整个盖住（现象：一播放就不挡了，
     * 因为播放时投影层留空）。改成独立层后，它排在视频层的位置，稳在海报墙下面。
     */
    VideoLayerBuf blankLayer;
    bool blankLayerOk = true;
    /*
     * 控制条 / 菜单独立合成层（2026-10-09，第二次实做）。
     *
     * 上一次（build 318）失败的教训有两条，这次都堵住：
     *  1) 上一版拷贝纹理时开了 GL_BLEND —— 面板纹理的透明通道若为 0，
     *     混合结果就是全透明，两块面板直接消失。这次**按弹幕层那条已验证的路子**：
     *     清成透明后关混合、原样拷贝（alpha 原样带过去，交给系统合成器混合）。
     *  2) 上一版把 renderEye 里的绘制删掉了，没有兜底 —— 层一旦出问题就彻底看不见。
     *     这次**保留绘制**，只在层提交成功时跳过，层挂了自动退回老路。
     */
    VideoLayerBuf osdLayer;
    bool osdLayerOk = true;
    VideoLayerBuf menuLayer;
    bool menuLayerOk = true;
    GLuint videoLayerVao = 0;
    GLuint videoLayerVbo = 0;

    /** 握把键按住时，上一帧处理摇杆调整的时刻（算增量用，秒） */
    double panelAdjustAt[2] = {0.0, 0.0};

    /** 拖海报墙的手感状态：是否抓着、是否真拖动过、按下那一刻的球面半径与起点 */
    bool panelDragActive[2] = {false, false};
    bool panelDragMoved[2] = {false, false};
    float panelDragRadius[2] = {0.f, 0.f};
    float panelDragStartX[2] = {0.f, 0.f};
    float panelDragStartY[2] = {0.f, 0.f};
    float panelDragStartZ[2] = {0.f, 0.f};
    bool sinkStickPushed[2] = {false, false};   // 摇杆是否处在"推着"的状态（回中要补一帧零值）
    bool sinkLastBack[2] = {false, false};
    // 扳机按下的时刻：扣下去那一下手会带偏摇杆，250ms 内不把它当滚动
    double triggerDownAtMs[2] = {0.0, 0.0};
    double sinkStickAt[2] = {0.0, 0.0}; // 摇杆滚动节拍（毫秒）

    /*
     * 面板（现有 Compose 界面）纹理：由 Java 侧的 SurfaceTexture 提供。
     * 画面来源链路与 2D 模式下完全一样，只是"贴到哪"变了：
     *   面板 SurfaceTexture（Kotlin 建）→ 这里 updateTexImage 取帧 → 贴到 VR 平面。
     */
    GLuint panelTex = 0;
    std::atomic<bool> panelActive{false};
    /*
     * 这块纹理自己拿到过帧没有 —— 三块屏（面板/播放画面/控制条）都必须有这一位。
     *
     * 为什么：没拿到帧的 OES 外部纹理在 Adreno 上采样出来不是黑的，而是**上一张
     * 外部纹理的画面**（同一纹理单元的 image 被顶替）。所以"谁先有帧，另一块屏就会
     * 借它的画面"：
     *   · 2026-10-05 上半天：控制条借了视频 → 控制条上贴的是视频（那次只给控制条加了判据）
     *   · 2026-10-05 晚上：播放画面借了控制条 → 播放屏上贴的是控制条按钮
     * 两块屏是同一个根因的两面，所以判据必须三块屏都有、统一走 OesSource。
     */
    std::atomic<bool> panelHasFrame{false};

    /*
     * 播放画面（VR 原生，2026-10-05）：与面板同一个套路 —— 在 VR 上下文里建一张
     * 外部纹理 + SurfaceTexture 交给 ExoPlayer 当视频输出，播放时取代面板贴到同一块平面上。
     */
    GLuint videoTex = 0;
    std::atomic<bool> videoActive{false};
    std::atomic<bool> videoHasFrame{false};   // 见 panelHasFrame 的注释
    /*
     * 上一次见到的视频帧时间戳 —— 换片时用来识别"残留的旧帧"（父亲 2026-10-07）。
     *
     * ExoPlayer 释放旧片后，Surface 里还留着上一部的最后一帧，它的 getTimestamp
     * 与停播前相同；若把它当成新片第一帧，黑幕/转圈就会被秒收。
     */
    int64_t lastVideoFrameTs = 0;
    /*
     * 换片 / 首播的等待期：Java 明确要求银幕转圈（父亲 2026-10-07）。
     *
     * 这段时间 videoActive 是 false（银幕已经清空），所以转圈不能再以 videoActive
     * 为前提 —— 否则就是"屏幕黑了但一直不转圈"。
     */
    std::atomic<bool> spinnerWanted{false};

    /*
     * 控制条（OSD，2026-10-05）：架在视频屏下方的矮条，与面板/视频同一套
     * SurfaceTexture 机制，只是尺寸 1920×270、位置在视频下面。
     */
    GLuint osdTex = 0;
    std::atomic<bool> osdVisible{false};

    /**
     * 海报墙是否摆出来（父亲 2026-10-05：控制条上的「选片」按钮切换它）。
     * 只是"要不要画"，和 panelActive（界面有没有在画）是两回事。
     */
    std::atomic<bool> panelShown{true};
    std::atomic<int> osdFrames{0};      // 控制条 updateTexImage 的调用次数（诊断用）
    /*
     * 控制条纹理是否真的拿到过帧（2026-10-05）。
     *
     * 为什么不能只看"调用过 updateTexImage"：没画面的 OES 纹理在 Adreno 上采样出来
     * 不是黑的，而是上一张 OES 图（实测：控制条那块显示的是视频画面被压扁的副本）。
     * SurfaceTexture.getTimestamp() 在第一帧之前恒为 0 —— 用它当"有画面"的判据。
     */
    std::atomic<bool> osdHasFrame{false};

    /*
     * 展开菜单（2026-10-06 父亲定）：控制条上点开菜单时**另开一块面板**，架在控制条
     * 正上方、与控制条等宽（像素 2560×1200）。控制条那块矮条尺寸不变。
     * 窗口背景透明，只有菜单卡片有底色，其余透出影院画面。
     */
    GLuint menuTex = 0;
    std::atomic<bool> menuVisible{false};
    std::atomic<bool> menuHasFrame{false};

    /*
     * 菜单**卡片实际占的那块矩形**（归一化，相对整块菜单面板；左/上/右/下）。
     *
     * 父亲 2026-10-06：光点从卡片上移开以后，射线要继续射到后面的面上去 ——
     * 原来整块面板都算命中，透明区也在半路把射线拦住，看着像"射不出去"。
     * 卡片矩形由界面层量好上报（setMenuHitRect）。
     */
    std::atomic<float> menuHitL{0.f};
    std::atomic<float> menuHitT{0.f};
    std::atomic<float> menuHitR{1.f};
    std::atomic<float> menuHitB{1.f};

    // 弹幕层（2026-10-06）：贴银幕、比银幕小一圈，独立一层纹理
    GLuint danmakuTex = 0;
    std::atomic<bool> danmakuVisible{false};
    std::atomic<bool> danmakuHasFrame{false};

    // 片名 logo（2026-10-06）：银幕左上角那块小透明层
    GLuint logoTex = 0;
    std::atomic<bool> logoVisible{false};
    std::atomic<bool> logoHasFrame{false};

    /*
     * ---- 纹理的创建者：VR 渲染线程自己（2026-10-05 晚修）----
     *
     * 前面三块屏互相串画面的真正根因：纹理是在 Java 那条 GL 线程的上下文里
     * （Java 调 nativeCreateXxxSurfaceTexture）建的，却拿到 VR 渲染线程的
     * **另一个** EGL 上下文里采样。两个上下文不共享纹理对象，同编号在 VR 侧
     * 指向的是别的纹理 —— 于是谁先出画面、画面就串到谁身上（控制条贴视频、
     * 播放屏贴控制条，来回换）。加「有帧才贴」只换了串的方向，治不了根。
     *
     * 现在改成本线程（VR 上下文）里建纹理 + SurfaceTexture，建好再通过
     * Java 侧注册进来的 textureSink 推过去，由界面层拿去建虚拟显示器 /
     * 交给播放器。这些 SurfaceTexture 的全局引用留在下面这三个字段里。
     */
    jobject panelSt = nullptr;
    jobject videoSt = nullptr;
    jobject osdSt = nullptr;
    jobject menuSt = nullptr;
    jobject danmakuSt = nullptr;
    jobject logoSt = nullptr;
    jobject textureSink = nullptr;
    /*
     * 画面纹理回调（2026-10-06 晚改）：原来六块画面各有一个方法，实测弹幕与
     * 片名 logo 这两个方法在推送时查不到（GetMethodID 拿到空），这两块画面
     * 因此从启动起就没被绘制过。现在统一成一个 onTexture(kind, st)，
     * 以后再加层只需扩编号。
     */
    jmethodID sinkTexture = nullptr;    // onTexture(int kind, SurfaceTexture st)

    // ---- 手柄输入 ----
    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction aimPoseAction = XR_NULL_HANDLE;      // 手柄指向（激光方向）
    XrAction triggerAction = XR_NULL_HANDLE;      // 扳机（布尔按下）
    XrAction triggerValueAction = XR_NULL_HANDLE; // 扳机（浮点力度）
    XrAction squeezeAction = XR_NULL_HANDLE;      // 侧握（按键式）
    XrAction squeezeValueAction = XR_NULL_HANDLE; // 侧握力度（有些运行时只给这个）
    XrAction thumbstickAction = XR_NULL_HANDLE;   // 摇杆二维轴
    XrAction aAction = XR_NULL_HANDLE;            // A / X
    XrAction bAction = XR_NULL_HANDLE;            // B / Y
    XrAction menuAction = XR_NULL_HANDLE;         // 菜单
    XrSpace aimSpaces[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    bool inputReady = false;
    XrTime frameDisplayTime = 0;   // 本帧预测显示时间（定位手柄姿态要用它）

    // 每帧记录的手柄状态（供打日志与后续交互使用）
    XrPosef aimPose[2];
    bool aimValid[2] = {false, false};
    bool triggerDown[2] = {false, false};
    float triggerValue[2] = {0.f, 0.f};
    bool squeezeDown[2] = {false, false};
    float squeezeValue[2] = {0.f, 0.f};   // 侧握模拟量（0-1）
    double videoActiveAtMs = 0.0;         // 本次开播的时刻（转圈延迟出现用）
    XrVector2f thumbstick[2] = {{0.f, 0.f}, {0.f, 0.f}};
    bool aDown[2] = {false, false};
    bool bDown[2] = {false, false};
    bool menuDown[2] = {false, false};
};

VrContext g;
std::thread gThread;

/**
 * 面板帧更新回调。
 *
 * updateTexImage 必须在**创建该纹理的 GL 上下文**里调用，也就是本渲染线程；
 * 但 SurfaceTexture 对象在 Java 侧，所以这里存一个由 Java 提供的函数指针
 * （VrNative.attachPanelUpdater），每帧回调过去让它 updateTexImage。
 */
std::function<void()> gPanelUpdate;
std::function<void()> gVideoUpdate;

/** 统计周期内所有交换链等待（双眼/视频层/弹幕层）累计毫秒，读后清零 */
double gSwapWaitMs = 0.0;

/*
 * 运行时可调参数（父亲 2026-10-08：「做一个能随时调参数的版本，别每次都编译」）。
 *
 * 参数写在 /sdcard/Android/data/com.xxxx.emby_vr/files/vr-tuning.txt，
 * 界面层每秒读一次，变化就调这里的接口。戴着调参比反复编译划算得多。
 */
std::atomic<float> gSuperSample{1.25f};      // 双眼渲染超采样倍数（下次起播生效）
std::atomic<int> gSwapWaitTimeoutMs{4};      // 等交换链图像超时（毫秒，可运行时调）
std::atomic<int> gLayerMask{0x3F};            // bit0 视频 bit1 弹幕 bit2 logo bit3 海报墙 bit4 控制条 bit5 菜单
/*
 * 交回图像前的同步档（父亲 2026-10-08 手机拍到撕裂后加的对照开关）。
 *
 * 0 = glFlush（只把命令推进命令流，正常档）
 * 1 = glFinish（等 GPU 真正画完，重一档；用来验证「撕裂是否因为图还没画完」）
 */
std::atomic<int> gSyncMode{0};

/*
 * 刷新率档（父亲 2026-10-08：PICO 4 面板在 72Hz 下偶发横向黑线，
 * 官方论坛与 Reddit 都有同类报告，且多出现在「平面银幕 + 72Hz」场景）。
 *
 * 0 = 按原逻辑（播放 72Hz / 界面 90Hz）
 * 72 / 90 = 固定该刷新率。运行时改立即生效，用来对照黑线。
 */
std::atomic<int> gRefreshHz{0};

/*
 * 跳过提交的计数（父亲 2026-10-08：「场景里有时候会出现黑纹」）。
 *
 * 投影层（双眼）是必交层：等图超时 → 这一帧没有投影层 → 运行时就拿上一帧做
 * 时间扭曲，转头时边缘会露出黑边/黑纹。所以超时不是"省一点"那么简单，
 * 必须数出来，才能判断黑纹是不是它造成的。
 */
std::atomic<int> gEyeSkipCount{0};
std::atomic<int> gVideoLayerSkipCount{0};
std::atomic<int> gOtherLayerSkipCount{0};   // 播放画面取帧（同面板：必须在 VR 渲染线程调）
/*
 * 交换链等图的三种结果分别计数（2026-10-09 定位黑纹时补）。
 *
 * 为什么必须分开数：`XR_TIMEOUT_EXPIRED` 是**正值 1**，而 `XR_FAILED()` 只判负值 ——
 * 原来是 `if (XR_FAILED(WaitSwapchainImage(...)))`，超时会被当成"成功"，
 * 于是照样往一张**没等到**的图里写、照样 release → 合成器可能正在读它 → 横向撕裂。
 * 更糟的是超时分支不执行，跳过计数器永远是 0，日志里「视频层 0」把证据也吞了。
 */
std::atomic<int> gSwapWaitOk{0};
std::atomic<int> gSwapWaitTimeout{0};
std::atomic<int> gSwapWaitError{0};
std::atomic<int> gEyeWaitTimeout{0};    // 眼缓冲（投影层）单独计数
std::atomic<int> gLastLayerCount{0};    // 上一帧实际提交给 xrEndFrame 的图层数（阶梯实验要看这个）

/*
 * 黑纹诊断用的运行时开关（2026-10-09 父亲要求：多给参数，就能多做实验，不用反复编译）。
 * 全部可在 vr-tuning.txt 里改，每秒生效。
 */
std::atomic<int> gHideProjection{0};    // 1 = 不提交投影层（只留 quad 层）—— ChatGPT 点名的判定实验
std::atomic<int> gTestPattern{0};       // 1 = 用固定测试图替代视频纹理（切断解码输入链）
/*
 * 视频层交换链宽度上限。0 = 按视频原分辨率（4K 片源就是 3840，很贵）。
 *
 * 默认 **1280**（2026-10-09 实测定案）：
 * 按 IMAX 银幕（82°）的解析力推算，1664 才算"像素够"；但**实测 1664 会让
 * GPU 每帧涨到 15.2ms（最差 17.8）、超出 13.9ms 预算 → 黑纹复现**；
 * 而 1280 只要 6.2~8.1ms、满帧、迟到 0~2（银幕保持 IMAX 不变）。
 * 两者独立：银幕大小没缩，只是视频像素密度让了一步，画面略软换稳定。
 */
std::atomic<int> gVideoLayerMaxW{1280};
std::atomic<int> gNoDownsample{0};      // 1 = 关掉视频降采样分支（只做单次纹理采样）
std::atomic<int> gNoJitter{0};          // 1 = 抖动种子固定（排除逐帧变化输入）
std::atomic<int> gForceRgba8{0};        // 1 = 视频层交换链强制 GL_RGBA8（不试 sRGB）
std::atomic<int> gNoPostfx{0};          // 1 = 关掉全部画质增强与颜色调整
/*
 * 1 = 在每次 updateTexImage 之前先 glFinish（2026-10-09 黑纹主嫌疑开关）。
 *
 * 机制：SurfaceTexture 取帧后，我们对 OES 纹理的采样是**异步 GPU 命令**；
 * 而下一帧再调 updateTexImage 时，上一张缓冲会被还给生产者（mpv/MediaCodec），
 * 生产者可能立刻往同一块缓冲写新帧 —— 若上一帧的采样还没执行完，
 * 就会读到半新半旧的图像，表现正是**横向条带**。
 * 只 glFlush（命令入队）不保证采样已经执行完；必须在"要回缓冲之前"等 GPU 干完。
 * 这也解释了：拷贝模式能改变生产者时序、杜比还原让每帧 GPU 更重使窗口变大、
 * 亮场景更明显、软解不走共享缓冲所以干净。
 */
std::atomic<int> gFinishBeforeTexUpdate{0};

/* 无限等待的兜底定义（个别版本的 openxr.h 不带这个宏） */
#ifndef XR_INFINITE_DURATION
#define XR_INFINITE_DURATION 0x7fffffffffffffffLL
#endif
std::function<void()> gOsdUpdate;     // 控制条取帧（同上）
std::function<void()> gMenuUpdate;    // 展开菜单取帧（同上）
std::function<void()> gDanmakuUpdate; // 弹幕层取帧
std::function<void()> gLogoUpdate;    // 片名 logo 取帧
std::atomic<bool> gRunning{false};
std::atomic<bool> gRequestStop{false};

void makeQuadBuffers(VrContext &c) {
    const float hw = 0.5f, hh = 0.5f;
    // 位置 + UV：面向 +z 观察者的矩形（中心在原点）
    const float verts[] = {
            -hw, -hh, 0.f, 0.f, 1.f,
            hw, -hh, 0.f, 1.f, 1.f,
            hw, hh, 0.f, 1.f, 0.f,
            -hw, -hh, 0.f, 0.f, 1.f,
            hw, hh, 0.f, 1.f, 0.f,
            -hw, hh, 0.f, 0.f, 0.f,
    };
    glGenBuffers(1, &c.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
}

/**
 * 实心三角 —— 加载转圈的箭头（父亲 2026-10-07 给的样式）。
 * 顶点朝 +Y（尖端），底边在 -Y，位置 + UV 与方块同格式，drawMesh 直接能用。
 */
void makeTriBuffer(VrContext &c) {
    const float verts[] = {
            -0.5f, -0.5f, 0.f, 0.f, 1.f,
            0.5f, -0.5f, 0.f, 1.f, 1.f,
            0.0f, 0.9f, 0.f, 0.5f, 0.f,
    };
    glGenBuffers(1, &c.triVbo);
    glBindBuffer(GL_ARRAY_BUFFER, c.triVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
}

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

/**
 * 手柄射线网格 —— 形状**照抄官方** PICO Native OpenXR SDK：
 *   framework/src/model/objects/TruncatedCone.cpp  GenerateRayMeshAtDefaultRadius(1.0, 32, 85°)
 *   → 近端半径 1、远端半径 1 - 1/tan(85°) ≈ 0.9125、长度 1、沿局部 -Z、32 段。
 * 绘制时按官方 AndroidOpenXrProgram::UpdateRay 只给缩放 (0.001, 0.001, 命中距离)，
 * 所以到眼睛前就是一根 1mm 粗的细光柱。
 */
void makeRayBuffer(VrContext &c) {
    const int segments = 32;
    const float r1 = 1.0f;
    const float r2 = 1.0f - 1.0f / tanf(85.0f * (float) M_PI / 180.0f);

    std::vector<float> verts;
    verts.reserve(segments * 6 * 5);
    for (int i = 0; i < segments; i++) {
        const float a0 = 2.0f * (float) M_PI * (float) i / (float) segments;
        const float a1 = 2.0f * (float) M_PI * (float) (i + 1) / (float) segments;
        const float b0x = r1 * cosf(a0), b0y = r1 * sinf(a0);   // 近端（贴手柄）
        const float b1x = r1 * cosf(a1), b1y = r1 * sinf(a1);
        const float t0x = r2 * cosf(a0), t0y = r2 * sinf(a0);   // 远端
        const float t1x = r2 * cosf(a1), t1y = r2 * sinf(a1);
        const float quad[] = {
                b0x, b0y, 0.f, 0.f, 1.f,
                b1x, b1y, 0.f, 1.f, 1.f,
                t1x, t1y, -1.f, 1.f, 0.f,
                b0x, b0y, 0.f, 0.f, 1.f,
                t1x, t1y, -1.f, 1.f, 0.f,
                t0x, t0y, -1.f, 0.f, 0.f,
        };
        verts.insert(verts.end(), quad, quad + 30);
    }

    glGenBuffers(1, &c.rayVbo);
    glBindBuffer(GL_ARRAY_BUFFER, c.rayVbo);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr) (verts.size() * sizeof(float)), verts.data(),
                 GL_STATIC_DRAW);
    c.rayVertexCount = (int) (verts.size() / 5);
}

/** 用当前 program 画一个网格：纯色 / 圆点两种（都走同一个 VBO 布局：pos(3) + uv(2)） */
void drawMesh(VrContext &c, GLuint vbo, int vertexCount, const Mat4 &mvp, float r, float g, float b,
              bool circle) {
    glUseProgram(c.program);
    glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, mvp.m);
    glUniform1i(c.useTexLoc, 0);
    glUniform4f(c.colorLoc, r, g, b, 1.f);
    if (c.circleLoc >= 0) glUniform1i(c.circleLoc, circle ? 1 : 0);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void *) 0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                          (void *) (3 * sizeof(float)));
    glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
}

/**
 * 关键前置步骤（run 94 实测踩坑）：官方 Android 加载器要求应用在 xrCreateInstance
 * **之前**先调 xrInitializeLoaderKHR，把 JavaVM 与 Activity 交给它，
 * 否则报：
 *   Error [GENERAL | xrCreateInstance | OpenXR-Loader] :
 *     RuntimeInterface::LoadRuntime cannot run because xrInitializeLoaderKHR
 *     was not successfully called.
 *   xrCreateInstance 失败：-6（XR_ERROR_RUNTIME_UNAVAILABLE）
 * 注意这一步要通过 xrGetInstanceProcAddr(XR_NULL_HANDLE, ...) 取函数
 * （还没有 instance，只能从 loader 本身取）。
 */
bool initializeLoader(VrContext &c) {
    PFN_xrVoidFunction fn = nullptr;
    XrResult r = xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", &fn);
    if (XR_FAILED(r) || fn == nullptr) {
        LOGE("取不到 xrInitializeLoaderKHR：%d", (int) r);
        return false;
    }
    auto initLoader = reinterpret_cast<PFN_xrInitializeLoaderKHR>(fn);

    XrLoaderInitInfoAndroidKHR androidInit{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    androidInit.applicationVM = c.jvm;
    androidInit.applicationContext = c.activity;

    r = initLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR *>(&androidInit));
    if (XR_FAILED(r)) {
        LOGE("xrInitializeLoaderKHR 失败：%d", (int) r);
        return false;
    }
    LOGI("OpenXR loader 已初始化（JavaVM + Activity 已交付）");
    return true;
}

bool createInstance(VrContext &c) {
    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = c.jvm;
    androidInfo.applicationActivity = c.activity;

    const char *exts[] = {
            XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
            XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
            XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME,
    };

    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &androidInfo;
    ci.enabledExtensionCount = sizeof(exts) / sizeof(exts[0]);
    ci.enabledExtensionNames = exts;
    ci.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;
    snprintf(ci.applicationInfo.applicationName, XR_MAX_APPLICATION_NAME_SIZE, "B0BEmby VR");
    snprintf(ci.applicationInfo.engineName, XR_MAX_ENGINE_NAME_SIZE, "B0BEmby");

    XrResult r = xrCreateInstance(&ci, &c.instance);
    if (XR_FAILED(r)) {
        LOGE("xrCreateInstance 失败：%d", (int) r);
        return false;
    }
    LOGI("OpenXR 实例已建立（apiVersion=%u.%u.%u）",
         XR_VERSION_MAJOR(XR_CURRENT_API_VERSION),
         XR_VERSION_MINOR(XR_CURRENT_API_VERSION),
         XR_VERSION_PATCH(XR_CURRENT_API_VERSION));

    /*
     * 取刷新率切换函数（父亲 2026-10-06：学 PICO 自带播放器，播放时把刷新率降到 72Hz）。
     *
     * 实测 PICO 自带播放器播片时跑 72Hz，每帧比 90Hz 多出约三成时间，那些时间用来
     * 把画面渲染得更实。我们播放时也这么干，停播回 90Hz（界面滑动更顺）。
     * 扩展名和函数名按 XR_FB_display_refresh_rate 规范；头文件未必带，所以这里自己声明。
     */
    PFN_xrVoidFunction refreshFn = nullptr;
    if (XR_SUCCEEDED(xrGetInstanceProcAddr(c.instance, "xrRequestDisplayRefreshRateFB",
                                          &refreshFn)) &&
        refreshFn != nullptr) {
        c.requestRefreshRate = reinterpret_cast<PFN_xrRequestDisplayRefreshRateAVS>(refreshFn);
        LOGI("刷新率切换可用：播放中 72Hz / 界面 90Hz");
    } else {
        LOGW("刷新率扩展不可用，保持运行时默认刷新率");
    }
    return fetchAll(c.instance);
}

bool initEgl(VrContext &c) {
    c.eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (c.eglDisplay == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay 失败");
        return false;
    }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(c.eglDisplay, &major, &minor)) {
        LOGE("eglInitialize 失败");
        return false;
    }
    LOGI("EGL 就绪 %d.%d", major, minor);

    const EGLint configAttrs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            /* 而不是 0：影厅是 3D 几何，要深度测试才能正确遮挡（父亲 2026-10-10） */
            EGL_DEPTH_SIZE, 24,
            EGL_NONE,
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(c.eglDisplay, configAttrs, &c.eglConfig, 1, &numConfigs) || numConfigs < 1) {
        LOGE("eglChooseConfig 失败");
        return false;
    }
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    c.eglContext = eglCreateContext(c.eglDisplay, c.eglConfig, EGL_NO_CONTEXT, ctxAttrs);
    if (c.eglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext 失败");
        return false;
    }
    const EGLint pbAttrs[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    c.eglSurface = eglCreatePbufferSurface(c.eglDisplay, c.eglConfig, pbAttrs);
    if (c.eglSurface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface 失败");
        return false;
    }
    if (!eglMakeCurrent(c.eglDisplay, c.eglSurface, c.eglSurface, c.eglContext)) {
        LOGE("eglMakeCurrent 失败");
        return false;
    }
    return true;
}

bool createSwapchains(VrContext &c) {
    // 首选 GL_RGBA8 / GL_SRGB8_ALPHA8
    uint32_t count = 0;
    if (XR_FAILED(api.EnumerateSwapchainFormats(c.session, 0, &count, nullptr)) || count == 0) {
        LOGE("取不到 swapchain 格式列表");
        return false;
    }
    std::vector<int64_t> formats(count);
    api.EnumerateSwapchainFormats(c.session, count, &count, formats.data());
    /*
     * 交换链格式：**优先非 sRGB（GL_RGBA8）**，这是「灰蒙蒙」的真正根因（2026-10-06）。
     *
     * 我们喂进去的两路纹理都是 sRGB 编码值：视频是 OES 外部纹理（硬件已把 BT.709 YUV
     * 转成 sRGB 编码的 RGB），面板是 Compose 画到虚拟显示器后的 sRGB 输出。
     * 而交换链选 GL_SRGB8_ALPHA8 时，硬件会把我们写入的值当成"线性"再编码一次 ——
     * 等于做了两遍 sRGB，画面整体被抬亮、对比度被压扁，观感就是蒙了一层纱。
     * 这也解释了为什么底色一直"太亮"：设 0.018 的灰会被抬到 0.16 上下。
     * 原生 VR 播放器（Unity 系）用的也是线性交换链。
     */
    int64_t chosen = formats[0];
    for (int64_t f : formats) {
        if (f == GL_SRGB8_ALPHA8) { chosen = f; break; }
        if (f == GL_RGBA8) chosen = f;
    }
    LOGI("swapchain 格式选定 0x%llx（候选 %u 个）", (unsigned long long) chosen, count);
    c.eyeFormat = chosen;   // 影厅图层的交换链用同一个格式

    c.eyes.resize(c.viewConfigs.size());
    for (size_t i = 0; i < c.viewConfigs.size(); i++) {
        const auto &vc = c.viewConfigs[i];
        EyeSwapchain &eye = c.eyes[i];
        /*
         * 渲染分辨率超采样（父亲 2026-10-06：「感觉没有投影和小米电视清晰」）。
         *
         * 推荐值只是"够用"档，画面在银幕上会明显发软。这里按 1.6 倍渲染再让运行时
         * 降采样回去，边缘会锐一截。倍数依据：实测同期 PICO 自带 VR 播放器与 4XVR，
         * 它们的合成开销（ATWGPU 5.8ms）是我们的 5 倍多，说明它们的渲染分辨率远高于
         * 推荐值 —— 我们还有一半 GPU 预算没用。上限 3200 防显存意外。
         */
        const float kSuperSample = gSuperSample.load();
        eye.width = (int32_t) fminf(3200.f, vc.recommendedImageRectWidth * kSuperSample);
        eye.height = (int32_t) fminf(3200.f, vc.recommendedImageRectHeight * kSuperSample);
        LOGI("渲染分辨率：推荐 %ux%u → 实际 %dx%d（超采样 %.2f 倍）",
             vc.recommendedImageRectWidth, vc.recommendedImageRectHeight,
             eye.width, eye.height, (double) kSuperSample);

        XrSwapchainCreateInfo sci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        sci.arraySize = 1;
        sci.mipCount = 1;
        sci.faceCount = 1;
        sci.format = chosen;
        sci.width = eye.width;
        sci.height = eye.height;
        sci.sampleCount = 1;
        sci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        if (XR_FAILED(api.CreateSwapchain(c.session, &sci, &eye.handle))) {
            LOGE("xrCreateSwapchain 失败（眼 %zu）", i);
            return false;
        }

        uint32_t imgCount = 0;
        api.EnumerateSwapchainImages(eye.handle, 0, &imgCount, nullptr);
        eye.images.resize(imgCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        XrResult r = api.EnumerateSwapchainImages(
                eye.handle, imgCount, &imgCount,
                reinterpret_cast<XrSwapchainImageBaseHeader *>(eye.images.data()));
        if (XR_FAILED(r)) {
            LOGE("xrEnumerateSwapchainImages 失败：%d", (int) r);
            return false;
        }
        eye.fbos.resize(imgCount, 0);
        eye.depthRbos.assign(imgCount, 0);
        for (uint32_t k = 0; k < imgCount; k++) {
            glGenFramebuffers(1, &eye.fbos[k]);
            glBindFramebuffer(GL_FRAMEBUFFER, eye.fbos[k]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                   static_cast<GLuint>(eye.images[k].image), 0);
            /*
             * 深度附件（父亲 2026-10-10 影院）：每张眼图配一个深度 renderbuffer。
             * 不共用同一个 —— 交换链多图轮转，共用会导致上一张图的深度被下一张读到。
             */
            glGenRenderbuffers(1, &eye.depthRbos[k]);
            glBindRenderbuffer(GL_RENDERBUFFER, eye.depthRbos[k]);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, eye.width,
                                  eye.height);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER,
                                      eye.depthRbos[k]);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                LOGE("FBO 不完整（眼 %zu 图 %u）", i, k);
                return false;
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        LOGI("眼 %zu：swapchain %dx%d，%u 张图", i, eye.width, eye.height, imgCount);
    }
    return true;
}

bool createSession(VrContext &c) {
    XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    XrResult r = api.GetOpenGLESGraphicsRequirementsKHR(c.instance, c.systemId, &req);
    if (XR_FAILED(r)) {
        LOGE("取 GLES 图形要求失败：%d", (int) r);
        return false;
    }

    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    binding.display = c.eglDisplay;
    binding.config = c.eglConfig;
    binding.context = c.eglContext;

    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
    sci.next = &binding;
    sci.systemId = c.systemId;
    r = api.CreateSession(c.instance, &sci, &c.session);
    if (XR_FAILED(r)) {
        LOGE("xrCreateSession 失败：%d", (int) r);
        return false;
    }

    XrReferenceSpaceCreateInfo rsci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rsci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    rsci.poseInReferenceSpace = {{0, 0, 0, 1}, {0, 0, 0}};
    r = api.CreateReferenceSpace(c.session, &rsci, &c.localSpace);
    if (XR_FAILED(r)) {
        // 退一步用 STAGE（有些运行时只给 STAGE）
        rsci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
        r = api.CreateReferenceSpace(c.session, &rsci, &c.localSpace);
        if (XR_FAILED(r)) {
            LOGE("xrCreateReferenceSpace 失败：%d", (int) r);
            return false;
        }
    }
    LOGI("OpenXR 会话已建立");
    return true;
}

/* ------------------------------------------------------------------ 手柄输入
 *
 * 走 OpenXR 标准动作集（Khronos 官方 loader 那套）：
 *   动作集 emby  →  交互配置建议（simple_controller / oculus touch 等都绑一遍）
 *   →  会话附加动作集  →  每帧 SyncActions 后读状态。
 *
 * aim 姿态单独建 ActionSpace，就是激光的起点与朝向；
 * 没有它就没有激光可画。
 */

/** 交互配置里用到的路径 → XrPath，失败返回 XR_NULL_PATH */
XrPath pathOf(VrContext &c, const char *s) {
    XrPath p = XR_NULL_PATH;
    if (api.StringToPath == nullptr) return p;
    if (XR_FAILED(api.StringToPath(c.instance, s, &p))) return XR_NULL_PATH;
    return p;
}

bool setupInput(VrContext &c) {
    if (api.CreateActionSet == nullptr || api.AttachSessionActionSets == nullptr) {
        LOGW("运行时未提供动作集接口，跳过手柄输入");
        return false;
    }

    XrActionSetCreateInfo asci{XR_TYPE_ACTION_SET_CREATE_INFO};
    strncpy(asci.actionSetName, "emby", sizeof(asci.actionSetName) - 1);
    strncpy(asci.localizedActionSetName, "Emby 控制", sizeof(asci.localizedActionSetName) - 1);
    asci.priority = 0;
    XrResult r = api.CreateActionSet(c.instance, &asci, &c.actionSet);
    if (XR_FAILED(r)) {
        LOGE("创建动作集失败：%d", (int) r);
        return false;
    }

    auto makeAction = [&](const char *name, const char *label, XrActionType type,
                          XrAction *out) -> bool {
        XrActionCreateInfo aci{XR_TYPE_ACTION_CREATE_INFO};
        strncpy(aci.actionName, name, sizeof(aci.actionName) - 1);
        strncpy(aci.localizedActionName, label, sizeof(aci.localizedActionName) - 1);
        aci.actionType = type;
        aci.countSubactionPaths = 2;
        const XrPath subPaths[2] = {
                pathOf(c, "/user/hand/left"),
                pathOf(c, "/user/hand/right"),
        };
        aci.subactionPaths = subPaths;
        const XrResult ar = api.CreateAction(c.actionSet, &aci, out);
        if (XR_FAILED(ar)) {
            LOGE("创建动作失败 %s：%d", name, (int) ar);
            return false;
        }
        return true;
    };

    bool ok = true;
    ok &= makeAction("aim_pose", "手柄指向", XR_ACTION_TYPE_POSE_INPUT, &c.aimPoseAction);
    ok &= makeAction("trigger", "扳机", XR_ACTION_TYPE_BOOLEAN_INPUT, &c.triggerAction);
    ok &= makeAction("trigger_value", "扳机力度", XR_ACTION_TYPE_FLOAT_INPUT, &c.triggerValueAction);
    ok &= makeAction("squeeze", "侧握", XR_ACTION_TYPE_BOOLEAN_INPUT, &c.squeezeAction);
    ok &= makeAction("squeeze_value", "侧握力度", XR_ACTION_TYPE_FLOAT_INPUT,
                     &c.squeezeValueAction);
    ok &= makeAction("thumbstick", "摇杆", XR_ACTION_TYPE_VECTOR2F_INPUT, &c.thumbstickAction);
    ok &= makeAction("a_click", "A 键", XR_ACTION_TYPE_BOOLEAN_INPUT, &c.aAction);
    ok &= makeAction("b_click", "B 键", XR_ACTION_TYPE_BOOLEAN_INPUT, &c.bAction);
    ok &= makeAction("menu_click", "菜单键", XR_ACTION_TYPE_BOOLEAN_INPUT, &c.menuAction);
    if (!ok) return false;

    /*
     * 交互配置建议（2026-10-05 run 101 实机踩坑后重写）。
     *
     * 原来一次性绑 four 套配置、每套 16 条，运行时**全部退回 -22
     * （XR_ERROR_PATH_UNSUPPORTED）** —— 只要一条绑定的路径该配置不支持，
     * 整批建议就被拒。正确做法是每套配置只绑它真正支持的输入，逐套独立提交。
     *
     * 配置名取自 PICO 运行时的实际清单（从 /system/priv-app/XRRuntime/XRRuntime.apk
     * 里提取的字符串）：
     *   /interaction_profiles/bytedance/pico4_controller   ← PICO 4 手柄
     *   /interaction_profiles/bytedance/pico_neo3_controller
     *   /interaction_profiles/khr/simple_controller        ← 兜底（只有 select/menu）
     *   /interaction_profiles/oculus/touch_controller      ← 兼容写法
     */
    struct BindingSpec {
        const char *leftPath;
        const char *rightPath;
        XrAction action;
    };

    auto bindProfile = [&](const char *profile, const std::vector<BindingSpec> &specs) -> bool {
        const XrPath profilePath = pathOf(c, profile);
        if (profilePath == XR_NULL_PATH) return false;

        std::vector<XrActionSuggestedBinding> bindings;
        for (const auto &s : specs) {
            if (s.leftPath != nullptr) {
                const XrPath p = pathOf(c, s.leftPath);
                if (p != XR_NULL_PATH) bindings.push_back({s.action, p});
            }
            if (s.rightPath != nullptr) {
                const XrPath p = pathOf(c, s.rightPath);
                if (p != XR_NULL_PATH) bindings.push_back({s.action, p});
            }
        }
        if (bindings.empty()) return false;

        XrInteractionProfileSuggestedBinding sbi{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        sbi.interactionProfile = profilePath;
        sbi.countSuggestedBindings = (uint32_t) bindings.size();
        sbi.suggestedBindings = bindings.data();
        const XrResult sr = api.SuggestInteractionProfileBindings(c.instance, &sbi);
        LOGI("绑定交互配置 %s：%u 条 → xrResult=%d%s", profile, sbi.countSuggestedBindings,
             (int) sr, XR_SUCCEEDED(sr) ? "（成功）" : "（被拒）");
        return XR_SUCCEEDED(sr);
    };

    // PICO 4 / Neo3 手柄：完整输入（姿态、扳机、侧握、摇杆、A/B/X/Y、菜单）
    const std::vector<BindingSpec> fullSpecs = {
            {"/user/hand/left/input/aim/pose",   "/user/hand/right/input/aim/pose",   c.aimPoseAction},
            {"/user/hand/left/input/trigger/value", "/user/hand/right/input/trigger/value", c.triggerAction},
            {"/user/hand/left/input/trigger/value", "/user/hand/right/input/trigger/value", c.triggerValueAction},
            {"/user/hand/left/input/squeeze/click", "/user/hand/right/input/squeeze/click", c.squeezeAction},
            {"/user/hand/left/input/squeeze/value", "/user/hand/right/input/squeeze/value", c.squeezeValueAction},
            {"/user/hand/left/input/thumbstick", "/user/hand/right/input/thumbstick", c.thumbstickAction},
            {"/user/hand/left/input/x/click",    "/user/hand/right/input/a/click",    c.aAction},
            {"/user/hand/left/input/y/click",    "/user/hand/right/input/b/click",    c.bAction},
            {"/user/hand/left/input/menu/click", "/user/hand/right/input/menu/click", c.menuAction},
    };

    /*
     * simple_controller 只有 select / menu / aim，没有 thumbstick 与 A/B，
     * 给它绑全套同样会被整批拒 —— 这是 run 101 四套全废的关键。
     */
    const std::vector<BindingSpec> simpleSpecs = {
            {"/user/hand/left/input/aim/pose",   "/user/hand/right/input/aim/pose",   c.aimPoseAction},
            {"/user/hand/left/input/select/click", "/user/hand/right/input/select/click", c.triggerAction},
            {"/user/hand/left/input/menu/click", "/user/hand/right/input/menu/click", c.menuAction},
    };

    bool anyBound = false;
    anyBound |= bindProfile("/interaction_profiles/bytedance/pico4_controller", fullSpecs);
    anyBound |= bindProfile("/interaction_profiles/bytedance/pico_neo3_controller", fullSpecs);
    anyBound |= bindProfile("/interaction_profiles/oculus/touch_controller", fullSpecs);
    anyBound |= bindProfile("/interaction_profiles/khr/simple_controller", simpleSpecs);
    if (!anyBound) LOGE("四套交互配置都没绑上，手柄事件将收不到");

    XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attach.countActionSets = 1;
    attach.actionSets = &c.actionSet;
    r = api.AttachSessionActionSets(c.session, &attach);
    if (XR_FAILED(r)) {
        LOGE("附加动作集失败：%d", (int) r);
        return false;
    }

    // aim 姿态空间：激光的起点与朝向
    for (int i = 0; i < 2; i++) {
        XrActionSpaceCreateInfo asci2{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        asci2.action = c.aimPoseAction;
        asci2.subactionPath = pathOf(c, i == 0 ? "/user/hand/left" : "/user/hand/right");
        asci2.poseInActionSpace = {{0, 0, 0, 1}, {0, 0, 0}};
        const XrResult sr2 = api.CreateActionSpace(c.session, &asci2, &c.aimSpaces[i]);
        if (XR_FAILED(sr2)) {
            LOGE("创建手柄姿态空间失败（%d）：%d", i, (int) sr2);
        }
    }

    c.inputReady = true;
    LOGI("手柄输入已就绪（动作集 + 姿态空间）");
    return true;
}

/** 每帧同步动作并读状态；变化时打日志（诊断阶段的主要输出） */
void syncInput(VrContext &c) {
    if (!c.inputReady || api.SyncActions == nullptr) return;

    XrActiveActionSet active{c.actionSet, XR_NULL_PATH};
    XrActionsSyncInfo syncInfo{XR_TYPE_ACTIONS_SYNC_INFO};
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &active;
    if (XR_FAILED(api.SyncActions(c.session, &syncInfo))) return;

    const char *handName[2] = {"左手", "右手"};
    XrPath handPaths[2] = {pathOf(c, "/user/hand/left"), pathOf(c, "/user/hand/right")};

    for (int i = 0; i < 2; i++) {
        // --- aim 姿态 ---
        XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
        /*
         * 用本帧预测显示时间定位（原来传 0）。
         * time=0 在部分运行时下"该时刻的姿态无法确定"，会一直返回无效 ——
         * run 104 实机就是这样：按键全通，但姿态始终拿不到，画不出激光。
         */
        const bool located = api.LocateSpace != nullptr &&
                XR_SUCCEEDED(api.LocateSpace(c.aimSpaces[i], c.localSpace,
                                             c.frameDisplayTime, &loc));
        if (located) {
            const bool posValid = (loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0;
            const bool oriValid = (loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0;
            c.aimValid[i] = posValid && oriValid;
            if (c.aimValid[i]) c.aimPose[i] = loc.pose;
            if (!c.aimValid[i] && loc.locationFlags != 0) {
                // 首次拿到"有标志位但不可用"的情况，打一次便于定位原因
                static int flagLog = 0;
                if (flagLog < 4) {
                    LOGI("手柄姿态标志位（%s）=0x%x（含位置位=%d 朝向位=%d）",
                         handName[i], (unsigned) loc.locationFlags,
                         (loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) ? 1 : 0,
                         (loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) ? 1 : 0);
                    flagLog++;
                }
            }
        } else {
            c.aimValid[i] = false;
        }

        auto readBool = [&](XrAction action, bool &prev, const char *label) {
            if (action == XR_NULL_HANDLE || api.GetActionStateBoolean == nullptr) return;
            XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
            gi.action = action;
            gi.subactionPath = handPaths[i];
            XrActionStateBoolean st{XR_TYPE_ACTION_STATE_BOOLEAN};
            if (XR_FAILED(api.GetActionStateBoolean(c.session, &gi, &st))) return;
            const bool now = st.isActive && st.currentState == XR_TRUE;
            if (now != prev) {
                LOGI("手柄事件：%s %s %s", handName[i], label, now ? "按下" : "松开");
                prev = now;
            }
        };

        readBool(c.triggerAction, c.triggerDown[i], "扳机");
        readBool(c.squeezeAction, c.squeezeDown[i], "侧握");
        /*
         * 侧握力度也要读：run 128 实测握把键没反应（拖动、远近都不动），
         * 怀疑运行时只给 /input/squeeze/value、不给 click。两个通道任一过半即算按住。
         */
        if (api.GetActionStateFloat != nullptr && c.squeezeValueAction != XR_NULL_HANDLE) {
            XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
            gi.action = c.squeezeValueAction;
            gi.subactionPath = handPaths[i];
            XrActionStateFloat st{XR_TYPE_ACTION_STATE_FLOAT};
            if (XR_SUCCEEDED(api.GetActionStateFloat(c.session, &gi, &st))) {
                c.squeezeValue[i] = st.isActive ? st.currentState : 0.f;
            }
        }
        if (c.squeezeValue[i] > 0.5f) c.squeezeDown[i] = true;
        readBool(c.aAction, c.aDown[i], "A");
        readBool(c.bAction, c.bDown[i], "B");
        readBool(c.menuAction, c.menuDown[i], "菜单");

        if (api.GetActionStateFloat != nullptr && c.triggerValueAction != XR_NULL_HANDLE) {
            XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
            gi.action = c.triggerValueAction;
            gi.subactionPath = handPaths[i];
            XrActionStateFloat st{XR_TYPE_ACTION_STATE_FLOAT};
            if (XR_SUCCEEDED(api.GetActionStateFloat(c.session, &gi, &st))) {
                c.triggerValue[i] = st.isActive ? st.currentState : 0.f;
            }
        }

        if (api.GetActionStateVector2f != nullptr && c.thumbstickAction != XR_NULL_HANDLE) {
            XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
            gi.action = c.thumbstickAction;
            gi.subactionPath = handPaths[i];
            XrActionStateVector2f st{XR_TYPE_ACTION_STATE_VECTOR2F};
            if (XR_SUCCEEDED(api.GetActionStateVector2f(c.session, &gi, &st))) {
                const XrVector2f v = st.isActive ? st.currentState : XrVector2f{0.f, 0.f};
                const XrVector2f prev = c.thumbstick[i];
                if (fabsf(v.x - prev.x) > 0.25f || fabsf(v.y - prev.y) > 0.25f) {
                    LOGI("手柄事件：%s 摇杆 (%.2f, %.2f)", handName[i], v.x, v.y);
                }
                c.thumbstick[i] = v;
            }
        }
    }
}

/**
 * 手柄指向 → 世界方向。**官方做法**（PICO Native OpenXR SDK v3.0.0，
 * Samples/framework/src/model/collision/SampleCollisionDetector.cpp）：
 *   XrVector3f rayDir = {0, 0, -1};
 *   XrQuaternionf_RotateVector3f(&rayDir, &aimQuat, &rayDir);
 * 也就是把本地 -Z（手柄指向）按姿态四元数旋转出来。
 */
void aimDirection(const XrPosef &aim, float *dx, float *dy, float *dz) {
    const float vx = 0.f, vy = 0.f, vz = -1.f;
    const float x = aim.orientation.x, y = aim.orientation.y;
    const float z = aim.orientation.z, w = aim.orientation.w;
    const float tx = 2.f * (y * vz - z * vy);   // t = 2 * (q × v)
    const float ty = 2.f * (z * vx - x * vz);
    const float tz = 2.f * (x * vy - y * vx);
    *dx = vx + w * tx + (y * tz - z * ty);      // v' = v + w·t + q × t
    *dy = vy + w * ty + (z * tx - x * tz);
    *dz = vz + w * tz + (x * ty - y * tx);
}

/**
 * 一块虚拟屏的摆位（2026-10-05 父亲定：海报墙与播放屏要分开）。
 *
 * 中心点 + 绕 Y 轴偏航（度）+ 宽度（米），高按 16:9 推。
 * 想调"两块屏离多远、斜多少、多大"，只改下面两个常量。
 */
/** 拖海报墙时"算不算动了"的阈值（米，面板中心位移） */
constexpr float kPanelDragSlop = 0.02f;
/** 握着握把键推摇杆：前后推的远近速度（米/秒）与左右推的缩放速度（米/秒） */
constexpr float kPanelDistSpeed = 2.0f;
constexpr float kPanelSizeSpeed = 1.6f;
/** 海报墙宽度范围（米），防止缩没了或者糊满视野 */
constexpr float kPanelMinWidth = 1.2f;
constexpr float kPanelMaxWidth = 5.0f;

/**
 * 海报墙常驻左前方（浏览、播放都在那儿；前方那块留给银幕）。
 * 父亲可以用光柱按住扳机把它拖走，位移量记在 VrContext 里。
 */
ScreenPlacement panelPlacement(const VrContext &c) {
    ScreenPlacement p = kSideScreen;
    // 跟随观影位（父亲 2026-10-10：走近银幕时控制条/菜单不能留在身后）
    p.cx = c.panelPosX.load() + viewerOffsetX();
    p.cy = c.panelPosY.load();
    p.cz = c.panelPosZ.load() + viewerOffsetZ();
    p.yawDeg = c.panelYawDeg.load();
    p.pitchDeg = c.panelPitchDeg.load();
    p.width = c.panelWidth.load();
    return p;
}

/** 银幕当前摆位（宽高比跟着片子的实际比例走，父亲 2026-10-06） */
ScreenPlacement frontScreen(const VrContext &c) {
    ScreenPlacement p = kFrontScreen;
    // 防穿越推量（2026-10-10）：走近银幕到墙根时场景整体后推，见 updateViewerAnchor
    p.cz = -gScreenDistance.load() - gScenePush.load();
    p.width = gScreenWidth.load();      // 尺寸可运行时调
    p.aspect = c.videoAspect.load();
    return p;
}

/**
 * 摆位 → 模型矩阵（位置 + 偏航/仰角 + 尺寸；复用控制条那套 poseScaleModel）。
 * 旋转顺序：先绕 X 轴仰角、再绕 Y 轴偏航 → q = qYaw ⊗ qPitch。
 */
Mat4 placementModel(const ScreenPlacement &p) {
    const float hy = p.yawDeg * 3.14159265358979f / 360.f;
    const float hp = p.pitchDeg * 3.14159265358979f / 360.f;
    const float sy = sinf(hy), cy = cosf(hy);
    const float sp = sinf(hp), cp = cosf(hp);
    XrPosef pose{};
    pose.position = {p.cx, p.cy, p.cz};
    pose.orientation = {cy * sp, sy * cp, -sy * sp, cy * cp};
    const float asp = p.aspect > 0.1f ? p.aspect : (16.f / 9.f);
    return poseScaleModel(pose, p.width, p.width / asp, 1.f);
}

/**
 * 手柄射线 × 某块屏的平面 —— 官方 DetectRayPlaneIntersection 的等价写法
 * （t > 0 且交点落在矩形内才算命中）。
 *
 * 输出：平面距离 t（只要穿过平面就给，用于把光束收在屏上）、面内坐标 u/v
 * （米，右正 / 上正，用来换算面板像素）、世界命中点（画光点用）。
 */
bool rayHitsPlacement(const XrPosef &aim, const ScreenPlacement &p, float *outT, float *outU,
                      float *outV, float *outWx, float *outWy, float *outWz) {
    float dx = 0.f, dy = 0.f, dz = 0.f;
    aimDirection(aim, &dx, &dy, &dz);
    *outT = 0.f;
    // 屏的基向量：由偏航 + 仰角算（先绕 X 仰角、再绕 Y 偏航）
    const float yaw = p.yawDeg * 3.14159265358979f / 180.f;
    const float pitch = p.pitchDeg * 3.14159265358979f / 180.f;
    const float cyaw = cosf(yaw), syaw = sinf(yaw);
    const float cpit = cosf(pitch), spit = sinf(pitch);
    const float nx = syaw * cpit, ny = -spit, nz = cyaw * cpit;      // 法线
    const float ux = cyaw, uy = 0.f, uz = -syaw;                     // 面内 x 轴
    const float vx = syaw * spit, vy = cpit, vz = cyaw * spit;       // 面内 y 轴
    const float denom = dx * nx + dy * ny + dz * nz;
    if (fabsf(denom) < 1e-6f) return false;                        // 与屏平行，永不相交
    const float t = ((p.cx - aim.position.x) * nx + (p.cy - aim.position.y) * ny +
                     (p.cz - aim.position.z) * nz) / denom;
    if (t <= 0.f) return false;                                    // 交点在身后
    *outT = t;
    const float wx = aim.position.x + dx * t;
    const float wy = aim.position.y + dy * t;
    const float wz = aim.position.z + dz * t;
    const float rx = wx - p.cx, ry = wy - p.cy, rz = wz - p.cz;
    const float u = rx * ux + ry * uy + rz * uz;
    const float v = rx * vx + ry * vy + rz * vz;
    const float halfW = p.width * 0.5f;
    const float halfH = p.width * 9.f / 16.f * 0.5f;
    if (fabsf(u) > halfW || fabsf(v) > halfH) return false;         // 交点出了屏范围
    *outU = u;
    *outV = v;
    *outWx = wx;
    *outWy = wy;
    *outWz = wz;
    return true;
}

/**
 * 银幕上的转圈提示（父亲 2026-10-05 要求）。
 *
 * 起播/换片到第一帧之间可能有好几秒（服务端选流、转码、缓冲），这段时间银幕上
 * 要看得见"在加载"，不能是一块死屏。做法：一圈小点绕着银幕中心转，越靠"头"越亮。
 */
/**
 * 银幕上的加载转圈（父亲 2026-10-07 给的样式：**青色**圆环 + 实心三角箭头，顺时针）。
 *
 * 用一圈小方块拼出环，缺口处不画；缺口那一端放一个实心三角当箭头，尖朝转动方向。
 * 只在「已开播但第一帧还没到」这段时间画（换片清屏后的等待）。
 */
void drawSpinner(VrContext &c, const Mat4 &proj, const Mat4 &view4) {
    if (c.vbo == 0 || c.program == 0) return;
    const double now = std::chrono::duration<double>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    constexpr int kSegs = 72;         // 整圈 72 段（每段 5°；父亲 2026-10-07：48 段太糙）
    constexpr int kGapSegs = 9;       // 缺口仍占 45°（9/72），缺口那头放箭头
    /*
     * 环的半径与粗细按银幕距离等比缩放（2026-10-09）：
     * 这两个值当年是按银幕 3.2 米外调定的；现在银幕挪到 15 米外，
     * 10 厘米的环只有 0.38° 视角，等于看不见。按距离放大以保持同样的观感。
     * （这个转圈目前是关闭状态，先保持一致，免得以后打开就"没有圈"。）
     */
    const float ringScale = fmaxf(0.5f, gScreenDistance.load() / 3.2f);
    const float kRadius = 0.10f * ringScale;   // 环半径（米）
    const float kThick = 0.012f * ringScale;   // 环的粗细（米）
    /*
     * 段长 = 弧长 × 1.25：段与段首尾重叠才看不出接缝。
     * （原来按半径方向摆段、段间只剩细缝，远看就是毛糙的虚线。）
     */
    const float segLen = 2.f * 3.14159265358979f * kRadius / (float) kSegs * 1.25f;
    const float step = 2.f * 3.14159265358979f / (float) kSegs;
    const float base = (float) (-now * 2.6);   // 角度递减 = 顺时针
    for (int i = 0; i < kSegs - kGapSegs; i++) {
        /*
         * 环沿**顺时针**方向排（父亲 2026-10-07：整个图案以 Y 轴镜像）：
         * i = 0 是起笔的尾巴（最暗），i 越大越靠近缺口端（越亮），
         * 而缺口落在箭头「前方」—— 顺时针转动时，箭头正好朝着运动方向。
         */
        const float ang = base - (float) i * step;
        /*
         * 尾巴 → 缺口端：**又从暗到亮、又从细到粗**（父亲 2026-10-07：
         * 线条从暗到亮、从细到粗，箭头就落在最亮最粗的缺口端）。
         * p = 0 是起笔的尾巴，p = 1 紧挨缺口。
         */
        const float p = (float) i / (float) (kSegs - kGapSegs - 1);
        const float fade = 0.45f + 0.55f * p;
        const float thick = kThick * (0.45f + 0.9f * p);
        XrPosef seg{};
        seg.position = {kFrontScreen.cx + cosf(ang) * kRadius,
                        kFrontScreen.cy + sinf(ang) * kRadius,
                        -gScreenDistance.load() + 0.012f};   // 稍微抬出来，别和银幕抢像素
        /*
         * 段的**长边沿切线**摆（父亲 2026-10-07：线要连续、不能毛糙）：
         * 绕 Z 转 θ 时方块的 +X 指向 θ —— 取 θ = ang + 90° 就是切线方向，
         * 相邻段于是首尾相接、互相搭接，看着是一条实线。
         */
        const float half = (ang + 1.5707963268f) * 0.5f;
        seg.orientation = {0.f, 0.f, sinf(half), cosf(half)};   // 绕 Z 轴摆到这一段
        const Mat4 m = poseScaleModel(seg, segLen, thick, 1.f);
        drawMesh(c, c.vbo, 6, multiply(multiply(proj, view4), m),
                 0.13f * fade, 0.86f * fade, 0.94f * fade, false);
    }
    /*
     * 箭头（父亲 2026-10-07 的图）：一个**实心三角**落在环的头部，尖朝转动方向。
     * 原来是两撇组成的 V 形，太细，远看不像箭头。
     */
    if (c.triVbo != 0) {
        const float ang = base - (float) (kSegs - kGapSegs) * step;
        XrPosef tip{};
        tip.position = {kFrontScreen.cx + cosf(ang) * kRadius,
                        kFrontScreen.cy + sinf(ang) * kRadius,
                        kFrontScreen.cz + 0.013f};
        /*
         * 三角的尖在 +Y，要让它朝**缺口那一侧**（父亲 2026-10-07：箭尖朝着缺口方向）。
         * 镜像之后缺口在箭头顺时针前方，切向角 = ang - 90°；
         * 绕 Z 转 θ 时 +Y 指向 θ + 90°，所以 θ = ang - 180°。
         */
        const float theta = ang - 3.14159265358979f;
        tip.orientation = {0.f, 0.f, sinf(theta * 0.5f), cosf(theta * 0.5f)};
        const Mat4 m = poseScaleModel(tip, kThick * 2.8f, kThick * 3.4f, 1.f);
        drawMesh(c, c.triVbo, 3, multiply(multiply(proj, view4), m),
                 0.13f, 0.86f, 0.94f, false);
    }
}

/** 面板像素尺寸：与 PanelLayer 的常量（1920×1080）保持一致 */
constexpr float kPanelPxW = 1920.f;
constexpr float kPanelPxH = 1080.f;

/**
 * 控制条几何（2026-10-05 父亲定）：**贴近观影者**，在他正前方偏下，像一块
 * 悬在身前的手柄面板，而不是贴在远处的视频屏下面。上仰一点正对他的眼睛。
 *
 * 位置/尺寸都在这里调：距离 [kOsdDistance]、高度 [kOsdCenterY]、宽度 [kOsdWidth]、
 * 仰角 [kOsdTiltDeg]。后续「指着它扣扳机拖走」也基于这几个量。
 */
/*
 * 宽度：1.60 → 2.00 米（父亲 2026-10-06 晚：+1/3，太长还可以缩短）。
 *
 * 严格 +1/3 是 2.13m，但在 0.85m 的近场距离上水平视角已 102°，条的两头会跑出
 * 视野边缘；按钮放大 1/3 后内容最少要 1.9m 以上（12 颗按钮约 1.14m + 时长 +
 * 进度条 + 留白），所以取 2.00m（96°）作为「够放、又没那么满」的值。
 * 像素保持 1800px/米，字不会糊。
 */
constexpr float kOsdPxW = 2331.f;
/*
 * 控制条尺寸（父亲 2026-10-06 晚定稿的图，逐项算出来的）：
 *
 *   宽 2331 = 按钮行左右各 114 + 12 个正方形按钮框 1908 + 框缝 5×9 + 组间距 75×2
 *   高  474 = 上边距 75 + 标题行 50 + 行距 30 + 进度行 55 + 行距 30 + 按钮行 159 + 下边距 75
 *
 * 像素密度保持 1800px/米 → 实宽 1.828 米。Java 侧 panelW / panelH 必须同值，
 * 否则光柱点击坐标会错位。
 */
constexpr float kOsdPxH = 474.f;
constexpr float kOsdWidth = 1.295f;  // 米 = 2331px / 1800px每米（父亲 2026-10-06 晚定稿）
constexpr float kOsdHeight = kOsdWidth * kOsdPxH / kOsdPxW;
constexpr float kOsdDistance = 0.85f;                     // 正前方距离（米，父亲：再近些）
constexpr float kOsdCenterY = -0.62f;                     // 视线下方（米，父亲：再靠下）
constexpr float kOsdTiltDeg = -24.f;                      // 上仰角（度），正对观影者

/** 控制条平面：中心与两条轴（含仰角）。返回法线 n 与面内 x/y 轴 */
void osdBasis(float *cx, float *cy, float *cz, float *nx, float *ny, float *nz, float *ux,
              float *uy, float *uz, float *vx, float *vy, float *vz) {
    const float th = kOsdTiltDeg * 3.14159265358979f / 180.f;
    const float ct = cosf(th), st = sinf(th);
    /* 控制条跟随观影位（2026-10-10）：命中计算必须和实际渲染位置一致，否则光柱偏 */
    *cx = viewerOffsetX(); *cy = kOsdCenterY; *cz = -kOsdDistance + viewerOffsetZ();
    // 绕 X 轴转 th：法线 (0,0,1) → (0,-sin,cos)；面内 y 轴 (0,1,0) → (0,cos,sin)
    *nx = 0.f; *ny = -st; *nz = ct;
    *ux = 1.f; *uy = 0.f; *uz = 0.f;
    *vx = 0.f; *vy = ct;  *vz = st;
}

/** 射线与控制条矩形的交点；命中返回 true（同一套路：交点必须在矩形内） */
bool rayHitsOsd(const VrContext &c, const XrPosef &aim, float *outT, float *outX, float *outY) {
    (void) c;
    float dx = 0.f, dy = 0.f, dz = 0.f;
    aimDirection(aim, &dx, &dy, &dz);
    *outT = 0.f;
    float cx, cy, cz, nx, ny, nz, ux, uy, uz, vx, vy, vz;
    osdBasis(&cx, &cy, &cz, &nx, &ny, &nz, &ux, &uy, &uz, &vx, &vy, &vz);
    const float denom = dx * nx + dy * ny + dz * nz;
    if (fabsf(denom) < 1e-6f) return false;                 // 与平面平行
    const float t = ((cx - aim.position.x) * nx + (cy - aim.position.y) * ny +
                     (cz - aim.position.z) * nz) / denom;
    if (t <= 0.f) return false;                             // 交点在身后
    const float hx = aim.position.x + dx * t;
    const float hy = aim.position.y + dy * t;
    const float hz = aim.position.z + dz * t;
    const float relx = hx - cx, rely = hy - cy, relz = hz - cz;
    const float u = relx * ux + rely * uy + relz * uz;      // 面内横向
    const float v = relx * vx + rely * vy + relz * vz;      // 面内纵向
    if (fabsf(u) > kOsdWidth * 0.5f) return false;
    if (fabsf(v) > kOsdHeight * 0.5f) return false;
    *outT = t;
    *outX = u;
    *outY = v;
    return true;
}

/*
 * 展开菜单几何（2026-10-06 父亲定）：菜单是**另一块面板**，架在控制条正上方、
 * 与控制条等宽（像素 2560×1200）。位置：底边贴着控制条顶边留一点缝，
 * 距离与仰角跟控制条一致，看起来像同一套控件。
 */
/*
 * 菜单像素尺寸（父亲 2026-10-06 晚定）：与控制条**同一像素密度** 1800px/米，
 * 所以字和控制条一样大。宽度与控制条等宽（kMenuWidth = kOsdWidth），卡片对位与
 * 光柱点击沿用原逻辑，不需要换算系数。
 *
 *   菜单项字号 24sp（与「正在播放」同档）：项高 92、卡片高 1175、面板高 1200
 */
constexpr float kMenuPxW = 2331.f;
/*
 * 菜单面板像素高度 = 控制条面板高度（父亲 2026-10-07：菜单面板的厚度改成和控制条一样）。
 * Java 侧 PlayerMenuPanel.kt 的 MENU_PANEL_H 必须同步改，否则物理尺寸与命中判定对不上。
 */
constexpr float kMenuPxH = 760.f;
constexpr float kMenuWidth = kOsdWidth;
constexpr float kMenuHeight = kMenuWidth * kMenuPxH / kMenuPxW;
constexpr float kMenuGap = 0.02f;
constexpr float kMenuTiltDeg = kOsdTiltDeg;
/*
 * 菜单中心由「与控制条共面」推出来（父亲 2026-10-06 实测：菜单比控制条离人更近，
 * 要一样的距离）。两块面同仰角、同斜面：菜单**底边**贴控制条顶边、沿法线留 kMenuGap。
 *
 *   控制条顶边 = 中心 + v*(kOsdHeight/2)，v = (0, cos t, sin t)
 *   菜单中心   = 控制条顶边 + n*kMenuGap + v*(kMenuHeight/2)，法线 n = (0, -sin t, cos t)
 *
 * 结果：控制条与菜单在同一张斜面上连着，距离从控制条中心的 0.85m 连续到菜单中心的
 * 约 1.0m，不再出现「菜单悬在人脸前」。
 */

/** 菜单面板平面：中心与两条轴（与控制条同一仰角，中心抬到控制条上方） */
void menuBasis(float *cx, float *cy, float *cz, float *nx, float *ny, float *nz, float *ux,
               float *uy, float *uz, float *vx, float *vy, float *vz) {
    const float th = kMenuTiltDeg * 3.14159265358979f / 180.f;
    const float ct = cosf(th), st = sinf(th);
    const float half = kOsdHeight * 0.5f + kMenuHeight * 0.5f;   // 两块面沿 v 轴的半高之和
    const float menuY = kOsdCenterY + ct * half - st * kMenuGap;
    const float menuDist = kOsdDistance - st * half - ct * kMenuGap;
    *cx = viewerOffsetX(); *cy = menuY; *cz = -menuDist + viewerOffsetZ();
    *nx = 0.f; *ny = -st; *nz = ct;
    *ux = 1.f; *uy = 0.f; *uz = 0.f;
    *vx = 0.f; *vy = ct;  *vz = st;
}

/** 射线与菜单矩形的交点；命中返回 true */
bool rayHitsMenu(const VrContext &c, const XrPosef &aim, float *outT, float *outX, float *outY) {
    float dx = 0.f, dy = 0.f, dz = 0.f;
    aimDirection(aim, &dx, &dy, &dz);
    *outT = 0.f;
    float cx, cy, cz, nx, ny, nz, ux, uy, uz, vx, vy, vz;
    menuBasis(&cx, &cy, &cz, &nx, &ny, &nz, &ux, &uy, &uz, &vx, &vy, &vz);
    const float denom = dx * nx + dy * ny + dz * nz;
    if (fabsf(denom) < 1e-6f) return false;
    const float t = ((cx - aim.position.x) * nx + (cy - aim.position.y) * ny +
                     (cz - aim.position.z) * nz) / denom;
    if (t <= 0.f) return false;
    const float hx = aim.position.x + dx * t;
    const float hy = aim.position.y + dy * t;
    const float hz = aim.position.z + dz * t;
    const float relx = hx - cx, rely = hy - cy, relz = hz - cz;
    const float u = relx * ux + rely * uy + relz * uz;
    const float v = relx * vx + rely * vy + relz * vz;
    if (fabsf(u) > kMenuWidth * 0.5f) return false;
    if (fabsf(v) > kMenuHeight * 0.5f) return false;

    /*
     * 只有落在**卡片**上才算命中（父亲 2026-10-06）：
     * 卡片以外是透明区，射线要从那儿穿过去，打到后面的控制条 / 银幕 / 海报墙。
     */
    const float nu = u / kMenuWidth + 0.5f;     // 0…1，左 → 右
    const float nv = 0.5f - v / kMenuHeight;    // 0…1，上 → 下
    if (nu < c.menuHitL.load() || nu > c.menuHitR.load() ||
        nv < c.menuHitT.load() || nv > c.menuHitB.load()) {
        return false;
    }

    *outT = t;
    *outX = u;
    *outY = v;
    return true;
}

/**
 * 摇杆状态上报（2026-10-05 改）：按状态而不是按步长。
 *
 * 平滑与惯性由 Java 侧统一算（PanelLayer 的逐帧滚动 + 松手线性减速），
 * 原生只负责"把摇杆当前量送过去"。30Hz 上报，回中时补一帧零值 ——
 * 那一帧零值就是惯性滑行的触发点。
 */
constexpr double kStickStateMs = 33.0;
constexpr float kStickDeadzone = 0.15f;   // 上报阈值取得低，死区交给 Java 侧判

double nowMs() {
    using namespace std::chrono;
    return (double) duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

/**
 * 清掉 JNI 调用挂起的 Java 异常（run 109 闪退的根因）。
 *
 * Java 回调（updateTexImage / 输入回调）出错时会在渲染线程留下挂起异常；
 * 原生代码不查，它就一直挂着，直到**下一次** CallVoidMethod 才被抛出来 ——
 * 实测表现：面板偶尔 updateTexImage 报错没人管，父亲一抬手让光柱指到屏幕，
 * 异常从输入回调里炸出来，进程直接挂掉。
 *
 * 这里统一在每次 Java 回调后检查并清除；前几次把堆栈打到日志里便于定位，
 * 之后只计数（避免刷屏）。
 */
void clearJavaException(JNIEnv *env, const char *what) {
    if (env == nullptr || !env->ExceptionCheck()) return;
    static int cleared = 0;
    cleared++;
    if (cleared <= 5) {
        LOGW("Java 异常（来自 %s，第 %d 次）—— 堆栈如下，已清除：", what, cleared);
        env->ExceptionDescribe();
    } else if (cleared == 6) {
        LOGW("Java 异常继续出现，后续只计数不再打堆栈");
    }
    env->ExceptionClear();
}

/**
 * 把本帧的手柄状态回推给 Java —— VR 模式的唯一输入通道（2026-10-05）。
 *
 * VR 里没有系统合成的触摸流，所以光柱指向 / 扳机 / 摇杆 / B 键全从这里下发。
 * 坐标换算：光柱命中点（米，落在面板平面上）→ 面板像素（0..1920 / 0..1080），
 * 与 2D 面板模式共用同一套坐标，界面代码一行都不用改。
 * 语义（父亲定）：指哪儿扣扳机就点哪儿；摇杆滚光柱底下那一排；B 键返回；
 * 指到面板外只留光柱，不点不滚。
 */
void pushInput(VrContext &c) {
    if (c.inputSink == nullptr || c.jvm == nullptr) return;
    JNIEnv *env = nullptr;
    if (c.jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return;
    }

    const double t = nowMs();
    const char *handName[2] = {"左手", "右手"};

    for (int h = 0; h < 2; h++) {
        // B 键 → 返回
        if (c.bDown[h] && !c.sinkLastBack[h] && c.sinkBack != nullptr) {
            LOGI("VR 输入：%s B 键 → 返回", handName[h]);
            env->CallVoidMethod(c.inputSink, c.sinkBack);
            clearJavaException(env, "输入回调 onBack");
        }
        c.sinkLastBack[h] = c.bDown[h];

        /*
         * 扳机状态回推（父亲 2026-10-09：弹幕时间偏移要"点一下走一档、按住连续跑"）。
         *
         * 只在**按下/松开变化**时回推一次，界面据此启动 / 停止连发。
         * 用独立的上次值，不碰 sinkLastTrigger —— 那个是"按下边沿"用的，两码事。
         */
        if (c.triggerDown[h] != c.sinkLastTriggerState[h]) {
            c.sinkLastTriggerState[h] = c.triggerDown[h];
            if (c.sinkTriggerState != nullptr) {
                env->CallVoidMethod(c.inputSink, c.sinkTriggerState,
                                    (jboolean) c.triggerDown[h]);
                clearJavaException(env, "输入回调 onTriggerState");
            }
        }

        if (!c.aimValid[h]) continue;

        /*
         * ⓪ 展开菜单最优先（2026-10-06）：它架在控制条正上方，指着它时指针与点击都归菜单。
         */
        if (c.menuVisible.load() && c.menuTex != 0 && c.menuHasFrame.load()) {
            float mT = 0.f, mu = 0.f, mv = 0.f;
            if (rayHitsMenu(c, c.aimPose[h], &mT, &mu, &mv)) {
                const float mpx = (mu / kMenuWidth + 0.5f) * kMenuPxW;
                const float mpy = (0.5f - mv / kMenuHeight) * kMenuPxH;
                /*
                 * 菜单指针改成「按下 / 拖动 / 抬起」三态（父亲 2026-10-10 玩亮度条）：
                 * 与控制条同一条路子 —— 面板层发鼠标式事件，行按钮照样点得动
                 * （按下没动就抬起 = 点击），亮度条则能按住拖。
                 * 原来的单次 onMenuClick 不再发：两套一起发会让"按住拖"顺带点一次行。
                 */
                const bool menuPressed = c.triggerDown[h];
                if (!c.sinkMenuPointerValid || fabsf(mpx - c.sinkMenuPointerX) > 2.f ||
                    fabsf(mpy - c.sinkMenuPointerY) > 2.f ||
                    menuPressed != c.sinkMenuPressed) {
                    if (c.sinkMenuPointer != nullptr) {
                        env->CallVoidMethod(c.inputSink, c.sinkMenuPointer, mpx, mpy,
                                            menuPressed ? JNI_TRUE : JNI_FALSE);
                        clearJavaException(env, "输入回调 onMenuPointer");
                    }
                    c.sinkMenuPointerX = mpx;
                    c.sinkMenuPointerY = mpy;
                    c.sinkMenuPressed = menuPressed;
                    c.sinkMenuPointerValid = true;
                }
                c.sinkLastTrigger[h] = c.triggerDown[h];

                /*
                 * 菜单分支也要把摇杆推给界面层（2026-10-06 深夜根因）：
                 * 这里原来直接 continue，摇杆从未离开原生 —— 菜单开着时怎么推都
                 * 滚不动，日志里一条「收到摇杆」都没有。菜单滚动（menu.vrStick）靠它。
                 * 坐标用菜单像素坐标（mpx/mpy），滚动定位与菜单面板一致。
                 */
                const float msx = c.thumbstick[h].x;
                const float msy = c.thumbstick[h].y;
                if (fabsf(msx) > kStickDeadzone || fabsf(msy) > kStickDeadzone) {
                    if (t - c.sinkStickAt[h] >= kStickStateMs) {
                        c.sinkStickAt[h] = t;
                        c.sinkStickPushed[h] = true;
                        if (c.sinkStick != nullptr) {
                            env->CallVoidMethod(c.inputSink, c.sinkStick, mpx, mpy, msx, msy);
                            clearJavaException(env, "输入回调 onStick（菜单分支）");
                        }
                    }
                } else if (c.sinkStickPushed[h] && c.sinkStick != nullptr) {
                    c.sinkStickPushed[h] = false;
                    c.sinkStickAt[h] = 0.0;
                    env->CallVoidMethod(c.inputSink, c.sinkStick, mpx, mpy, 0.f, 0.f);
                    clearJavaException(env, "输入回调 onStick（菜单分支回中）");
                }

                continue;
            }
        }

        /*
         * ① 控制条优先：指着控制条时，指针与点击都给控制条，不碰主面板。
         *    （控制条是近场小面板，尺寸 1920×270，坐标单独换算。）
         */
        bool osdHitThisHand = false;
        if (c.osdVisible.load() && c.osdTex != 0 && c.osdHasFrame.load()) {
            float osdT = 0.f, ou = 0.f, ov = 0.f;
            if (rayHitsOsd(c, c.aimPose[h], &osdT, &ou, &ov)) {
                osdHitThisHand = true;
                const float opx = (ou / kOsdWidth + 0.5f) * kOsdPxW;
                const float opy = (0.5f - ov / kOsdHeight) * kOsdPxH;
                /*
                 * 位置变了**或扳机态变了**都要推（父亲 2026-10-06：指着进度条扣扳机
                 * 能直接拖着走）。扳机按住 = 一次"按压"，界面侧据此发 DOWN/MOVE/UP，
                 * 进度条才吃得到拖动；松开那一下即使光点没动也必须推上去，否则松手
                 * 会卡在按下态。
                 */
                const bool osdPressed = c.triggerDown[h];
                if (!c.sinkOsdPointerValid || fabsf(opx - c.sinkOsdPointerX) > 2.f ||
                    fabsf(opy - c.sinkOsdPointerY) > 2.f || osdPressed != c.sinkOsdPressed) {
                    if (c.sinkOsdPointer != nullptr) {
                        env->CallVoidMethod(c.inputSink, c.sinkOsdPointer, opx, opy, osdPressed);
                        clearJavaException(env, "输入回调 onOsdPointer");
                    }
                    c.sinkOsdPointerX = opx;
                    c.sinkOsdPointerY = opy;
                    c.sinkOsdPointerValid = true;
                    c.sinkOsdPressed = osdPressed;
                }
                if (c.triggerDown[h] && !c.sinkLastTrigger[h] && c.sinkOsdClick != nullptr) {
                    LOGI("VR 输入：%s 扳机 → 控制条点击 (%d, %d)", handName[h], (int) opx, (int) opy);
                    env->CallVoidMethod(c.inputSink, c.sinkOsdClick, opx, opy);
                    clearJavaException(env, "输入回调 onOsdClick");
                }
                c.sinkLastTrigger[h] = c.triggerDown[h];
                continue;
            }
        }

        /*
         * 光柱扫出控制条（或控制条收起）时补一次「松手」：
         * 按住扳机拖进度条，手一动光点就出界，原生不再推指针 —— 不补这一下，
         * 界面那边会一直卡在按下态（父亲 2026-10-06：拖进度条要跟手）。
         */
        if (c.sinkOsdPressed) {
            c.sinkOsdPressed = false;
            if (c.sinkOsdPointer != nullptr) {
                env->CallVoidMethod(c.inputSink, c.sinkOsdPointer,
                                    c.sinkOsdPointerX, c.sinkOsdPointerY, false);
                clearJavaException(env, "输入回调 onOsdPointer(补松手)");
            }
        }
        // 光柱本来指着控制条、这回没指着了：推一个面板外的坐标，界面清掉悬停高亮
        if (osdHitThisHand) {
            c.sinkOsdOnPanel = true;
        }
        if (c.sinkOsdOnPanel && !osdHitThisHand) {
            c.sinkOsdOnPanel = false;
            if (c.sinkOsdPointer != nullptr) {
                env->CallVoidMethod(c.inputSink, c.sinkOsdPointer, -1.f, -1.f, false);
                clearJavaException(env, "输入回调 onOsdPointer(离开)");
            }
        }

        /*
         * ② 海报墙优先（2026-10-06 父亲：播放期间海报墙照样能操作）：
         *    光柱指在海报墙上 → 指针 / 点击 / 摇杆 / 拖动都给它，与是否正在播放无关。
         *    命中判定按摆位求交（含朝向），否则点击坐标会整体错位。
         */
        const ScreenPlacement place = panelPlacement(c);
        float planeT = 0.f, hu = 0.f, hv = 0.f, wx = 0.f, wy = 0.f, wz = 0.f;
        /*
         * 光点是否落在海报墙上。**必须在握把键之前算**（父亲 2026-10-07）：
         * 起手那一下要用它决定"抓不抓"—— 之前不管光点在哪，一按握把键整块墙
         * 就按「半径 × 手柄指向」跳到光柱方向上，看着就是瞬移。
         */
        const bool onPanel = c.panelShown.load() &&
                             rayHitsPlacement(c.aimPose[h], place, &planeT, &hu, &hv,
                                              &wx, &wy, &wz);
        /*
         * 握把键（Grip / squeeze，手柄侧面中指那个）按住 = 抓住海报墙：
         *   球心 = 手柄位置，半径 = 按下那一刻手柄到面板中心的距离；按住期间
         *   面板中心 = 球心 + 半径 × 手柄指向，朝向反解成"正对球心"。
         *   松开握把键就是松手。父亲 2026-10-06 定：拖动归握把键，别和点击打架。
         *
         * 这一段**刻意不放在 onPanel 分支里**（父亲 2026-10-06 报"按住握把键推摇杆
         * 调远近没生效"）：拖的时候手一动，光柱就扫出海报墙边界，onPanel 变 false，
         * 整段被跳过 —— 摇杆调远近也跟着没了。抓取只认握把键，不认光柱落点。
         */
        if (c.squeezeDown[h]) {
            const XrVector3f hand = c.aimPose[h].position;
            /*
             * 抓住的前提：光点正落在海报墙上（父亲 2026-10-07 实测报"光点在哪，
             * 一按握把键墙就跑到光点位置"）。抓住之后不再要求 —— 手一动光柱就会
             * 扫出墙面，那也算还抓着，否则拖到一半就掉。
             */
            if (!c.panelDragActive[h] && onPanel) {
                c.panelDragActive[h] = true;
                c.panelDragMoved[h] = false;
                const float dx = c.panelPosX.load() - hand.x;
                const float dy = c.panelPosY.load() - hand.y;
                const float dz = c.panelPosZ.load() - hand.z;
                c.panelDragRadius[h] = sqrtf(dx * dx + dy * dy + dz * dz);
                c.panelDragStartX[h] = c.panelPosX.load();
                c.panelDragStartY[h] = c.panelPosY.load();
                c.panelDragStartZ[h] = c.panelPosZ.load();
                c.panelAdjustAt[h] = t;
                LOGI("海报墙：%s 握把键按住 → 抓住（半径 %.2f 米）", handName[h],
                     (double) c.panelDragRadius[h]);
            }
            /*
             * 抓住之后每帧跑：拖动（球面）+ 握着握把键推摇杆调远近 / 缩放
             * （父亲 2026-10-06 定：前后推 = 远近，左右推 = 大小）。
             */
            if (c.panelDragActive[h]) {
                float ddx = 0.f, ddy = 0.f, ddz = 0.f;
                aimDirection(c.aimPose[h], &ddx, &ddy, &ddz);
                const float r = c.panelDragRadius[h];
                const float tx = hand.x + ddx * r;
                const float ty = hand.y + ddy * r;
                const float tz = hand.z + ddz * r;
                const float mx = tx - c.panelDragStartX[h];
                const float my = ty - c.panelDragStartY[h];
                const float mz = tz - c.panelDragStartZ[h];
                if (sqrtf(mx * mx + my * my + mz * mz) > kPanelDragSlop) {
                    c.panelDragMoved[h] = true;
                }
                if (c.panelDragMoved[h]) {
                    c.panelPosX = tx;
                    c.panelPosY = ty;
                    c.panelPosZ = tz;
                    // 法线指回球心（= 手柄指向的反向），反解偏航与仰角
                    const float kRad2Deg = 180.f / 3.14159265358979f;
                    c.panelPitchDeg = asinf(ddy) * kRad2Deg;
                    c.panelYawDeg = atan2f(-ddx, -ddz) * kRad2Deg;
                }

                const float sx2 = c.thumbstick[h].x;
                const float sy2 = c.thumbstick[h].y;
                const double dt = t - c.panelAdjustAt[h];
                const float step = (float) ((dt > 0.0 && dt < 0.2) ? dt : 0.016);
                if (fabsf(sy2) > kStickDeadzone) {
                    /*
                     * 远近调的是「球面半径」，不是坐标本身 —— 父亲 2026-10-06 实测：
                     * 缩放好用、远近完全没用。原因是这一段每帧都按「半径 × 手柄指向」
                     * 重算面板位置，直接改坐标会被下一帧覆盖，所以必须改半径。
                     */
                    c.panelDragRadius[h] =
                            fmaxf(0.8f, c.panelDragRadius[h] + sy2 * kPanelDistSpeed * step);
                    float ndx = 0.f, ndy = 0.f, ndz = 0.f;
                    aimDirection(c.aimPose[h], &ndx, &ndy, &ndz);
                    const float rr = c.panelDragRadius[h];
                    c.panelPosX = hand.x + ndx * rr;
                    c.panelPosY = hand.y + ndy * rr;
                    c.panelPosZ = hand.z + ndz * rr;
                    LOGI("海报墙：摇杆远近 → 半径 %.2f 米", (double) rr);
                }
                if (fabsf(sx2) > kStickDeadzone) {
                    const float w = c.panelWidth.load() + sx2 * kPanelSizeSpeed * step;
                    c.panelWidth = fminf(kPanelMaxWidth, fmaxf(kPanelMinWidth, w));
                    LOGI("海报墙：摇杆缩放 → 宽 %.2f 米", (double) c.panelWidth.load());
                }
                c.panelAdjustAt[h] = t;
            }
        } else if (c.panelDragActive[h]) {
            c.panelDragActive[h] = false;
            c.panelDragMoved[h] = false;
            LOGI("海报墙：挪到 (%.2f, %.2f, %.2f) 朝向 %.0f°/%.0f° 宽 %.2f 米",
                 c.panelPosX.load(), c.panelPosY.load(), c.panelPosZ.load(),
                 c.panelYawDeg.load(), c.panelPitchDeg.load(), c.panelWidth.load());
        }

        // 告诉界面层：光柱在不在海报墙上（决定 B 键给谁、面板接不接输入）
        if (onPanel != c.sinkPanelFocusOn[h]) {
            c.sinkPanelFocusOn[h] = onPanel;
            if (c.sinkPanelFocus != nullptr) {
                env->CallVoidMethod(c.inputSink, c.sinkPanelFocus, (jboolean) onPanel);
                clearJavaException(env, "输入回调 onPanelFocus");
            }
        }

        if (onPanel) {
            const float px = (hu / place.width + 0.5f) * kPanelPxW;
            const float py = (0.5f - hv / (place.width * 9.f / 16.f)) * kPanelPxH;

            // 指针移动：超过 2px 才回推，避免每帧刷屏
            if (!c.sinkPointerValid || fabsf(px - c.sinkPointerX) > 2.f ||
                fabsf(py - c.sinkPointerY) > 2.f) {
                if (c.sinkPointer != nullptr) env->CallVoidMethod(c.inputSink, c.sinkPointer, px, py);
                clearJavaException(env, "输入回调 onPointer");
                c.sinkPointerX = px;
                c.sinkPointerY = py;
                c.sinkPointerValid = true;
            }

            /*
             * ① 扳机（按下那一刻）→ 在光柱位置点一下。拖动整块墙改由握把键承担。
             *
             * 父亲 2026-10-06 晚明确：**对准海报扣扳机时，海报不许上下左右滚动** ——
             * 手一抖海报就跟着滚，很难对准要点的那张。所以按住扳机期间，
             * 摇杆滚动通道在下面 ④ 被掐掉（见那里的条件）。
             */
            if (c.triggerDown[h] && !c.sinkLastTrigger[h]) {
                c.triggerDownAtMs[h] = t;
            }
            if (!c.squeezeDown[h] && c.triggerDown[h] && !c.sinkLastTrigger[h] &&
                c.sinkClick != nullptr) {
                LOGI("VR 输入：%s 扳机 → 面板点击 (%d, %d)", handName[h], (int) px, (int) py);
                env->CallVoidMethod(c.inputSink, c.sinkClick, px, py);
                clearJavaException(env, "输入回调 onClick");
            }
            c.sinkLastTrigger[h] = c.triggerDown[h];

            /*
             * ④ 摇杆滚动：连状态上报（30Hz）；回中补一帧零值，Java 侧据此进入惯性滑行。
             *
             * 两种时候不滚：
             *   · 按着握把键 —— 那时候的摇杆在调远近与大小（见 ③）；
             *   · 刚扣下扳机的 250ms 内 —— 扣扳机去点海报时手会带偏摇杆，海报会跟着跑。
             *     只掐这一小段，过了照常滚，**绝不锁死滚动**（父亲：不要点过一次海报，
             *     海报就永远不滚了）。
             */
            const bool triggerJustPressed =
                    c.triggerDown[h] && (t - c.triggerDownAtMs[h] < 250.0);
            if (!c.squeezeDown[h] && !triggerJustPressed) {
                const float sx = c.thumbstick[h].x;
                const float sy = c.thumbstick[h].y;
                /*
                 * 诊断（父亲 2026-10-06 深夜）：菜单开着推摇杆，界面层一条都收不到，
                 * 连「推给界面层」的原生日志都没有 —— 推送是无条件的，那只剩一种
                 * 可能：这里读到的摇杆值本身在死区内（比如被系统收走）。
                 * 打印原始值，一次看清。
                 */
                static int stickRawLogTick = 0;
                if ((stickRawLogTick++ % 60) == 0) {
                    LOGI("摇杆原始值：x=%.3f y=%.3f 握把=%d 扳机=%d 菜单=%d",
                         sx, sy, c.squeezeDown[h] ? 1 : 0, c.triggerDown[h] ? 1 : 0,
                         c.menuVisible.load() ? 1 : 0);
                }
                if (fabsf(sx) > kStickDeadzone || fabsf(sy) > kStickDeadzone) {
                    if (t - c.sinkStickAt[h] >= kStickStateMs) {
                        c.sinkStickAt[h] = t;
                        c.sinkStickPushed[h] = true;
                        if (c.sinkStick != nullptr) {
                            // 坐标 + 摇杆量（x 右正、y 上正，与 OpenXR 一致；方向语义在 Java 侧翻）
                            static int stickLogTick = 0;
                            if ((stickLogTick++ % 60) == 0) {
                                LOGI("摇杆推给界面层：x=%.2f y=%.2f 接收对象=%p", sx, sy,
                                     (void *) c.inputSink);
                            }
                            env->CallVoidMethod(c.inputSink, c.sinkStick, px, py, sx, sy);
                            if (env->ExceptionCheck()) {
                                LOGW("摇杆回调抛异常（界面侧 onStick 没跑完）");
                            }
                            clearJavaException(env, "输入回调 onStick");
                        }
                    }
                } else if (c.sinkStickPushed[h] && c.sinkStick != nullptr) {
                    c.sinkStickPushed[h] = false;
                    c.sinkStickAt[h] = 0.0;
                    env->CallVoidMethod(c.inputSink, c.sinkStick, px, py, 0.f, 0.f);
                    clearJavaException(env, "输入回调 onStick(回中)");
                }
            }
            continue;
        }

        /*
         * ③ 光柱指着银幕 + 扣扳机 = 开关控制条。
         *
         * 父亲 2026-10-07：**没在播放时也要能唤出** —— 刚打开 app、屏幕上没有影片时，
         * 指着空屏扣扳机，控制条就该出来（从那里可以进「选片」）。
         * 所以这里不再要求 videoActive（原来它把整段都跳过了，扣扳机毫无反应）。
         * 光柱落在海报墙上时前面已经 continue 掉，不会和"点海报"打架。
         */
        {
            float vT = 0.f, vu = 0.f, vv = 0.f, vwx = 0.f, vwy = 0.f, vwz = 0.f;
            const bool onScreen = rayHitsPlacement(c.aimPose[h], frontScreen(c), &vT, &vu, &vv,
                                                   &vwx, &vwy, &vwz);
            const double nowToggle = nowMs();
            if (onScreen && c.triggerDown[h] && !c.sinkLastTrigger[h] &&
                c.sinkToggleOsd != nullptr && nowToggle - c.sinkLastToggleMs > 300.0) {
                c.sinkLastToggleMs = nowToggle;
                LOGI("VR 输入：%s 扳机 → 控制条开关（指着银幕）", handName[h]);
                env->CallVoidMethod(c.inputSink, c.sinkToggleOsd);
                clearJavaException(env, "输入回调 onToggleOsd");
            }
            c.sinkLastTrigger[h] = c.triggerDown[h];
        }

        /*
         * ⑤ 摇杆 → 界面层（父亲 2026-10-07：光柱指着屏幕时拨摇杆没反应）。
         *
         * 原来摇杆只在"光柱落在菜单"和"光柱落在海报墙"两路推出去，指着屏幕这一路
         * 根本没有推送 —— 播放页的左右快进快退（stickSeek）因此永远收不到摇杆。
         * 这一段的位置天然是"既不在菜单、也不在海报墙"，正好补上这一路。
         * 坐标传 0：播放页只用摇杆量；面板滚动在 Java 侧另有 panelPointerOnPanel 把关。
         */
        if (c.videoActive.load()) {
            /*
             * 只在播放中补这一路：未播放时（看海报墙/首页）指着屏幕推摇杆，
             * 不该跑去滚面板 —— 那时的摇杆仍走"指着海报墙"那条既有通道。
             */
            const float sxScreen = c.thumbstick[h].x;
            const float syScreen = c.thumbstick[h].y;
            if (fabsf(sxScreen) > kStickDeadzone || fabsf(syScreen) > kStickDeadzone) {
                if (t - c.sinkStickAt[h] >= kStickStateMs) {
                    c.sinkStickAt[h] = t;
                    c.sinkStickPushed[h] = true;
                    if (c.sinkStick != nullptr) {
                        env->CallVoidMethod(c.inputSink, c.sinkStick, 0.f, 0.f,
                                            sxScreen, syScreen);
                        clearJavaException(env, "输入回调 onStick（画面分支）");
                    }
                }
            } else if (c.sinkStickPushed[h] && c.sinkStick != nullptr) {
                // 回中补一帧零值：界面侧据此清"已触发"状态（快进快退要回中才能再触发）
                c.sinkStickPushed[h] = false;
                c.sinkStickAt[h] = 0.0;
                env->CallVoidMethod(c.inputSink, c.sinkStick, 0.f, 0.f, 0.f, 0.f);
                clearJavaException(env, "输入回调 onStick（画面分支 · 回中）");
            }
        }
    }
}

/** 处理会话事件：READY→Begin，STOPPING→End，EXITING/LOSS_PENDING→退出 */
void pumpEvents(VrContext &c) {
    XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
    while (api.PollEvent(c.instance, &ev) == XR_SUCCESS) {
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto *sc = reinterpret_cast<XrEventDataSessionStateChanged *>(&ev);
            c.state = sc->state;
            LOGI("会话状态 → %d", (int) sc->state);
            switch (sc->state) {
                case XR_SESSION_STATE_READY: {
                    XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                    bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    if (XR_SUCCEEDED(api.BeginSession(c.session, &bi))) {
                        c.sessionRunning = true;
                        LOGI("会话已开始（立体渲染）");
                    } else {
                        LOGE("xrBeginSession 失败");
                    }
                    break;
                }
                case XR_SESSION_STATE_STOPPING:
                    c.sessionRunning = false;
                    api.EndSession(c.session);
                    break;
                case XR_SESSION_STATE_EXITING:
                case XR_SESSION_STATE_LOSS_PENDING:
                    gRequestStop = true;
                    break;
                default:
                    break;
            }
        }
        ev = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}

/* drawCinema 在后面定义，这里先用一声明（影厅图层要在它前面调用） */
void drawCinema(const Mat4 &proj, const Mat4 &view, const XrVector3f &eyePos);

/** 建影厅图层的交换链（分辨率按 scale 缩一点：影厅是暗场，省下来的 GPU 留给正片） */
bool createCinemaSwapchains(VrContext &c, float scale) {
    if (c.cinemaEyesOk) return true;
    c.cinemaEyes.clear();
    c.cinemaEyes.resize(c.eyes.size());
    for (size_t i = 0; i < c.eyes.size(); i++) {
        EyeSwapchain &ce = c.cinemaEyes[i];
        ce.width = (int32_t) fmaxf(64.f, (float) c.eyes[i].width * scale);
        ce.height = (int32_t) fmaxf(64.f, (float) c.eyes[i].height * scale);
        XrSwapchainCreateInfo sci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        sci.arraySize = 1;
        sci.mipCount = 1;
        sci.faceCount = 1;
        sci.format = c.eyeFormat;
        sci.width = ce.width;
        sci.height = ce.height;
        sci.sampleCount = 1;
        sci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        if (XR_FAILED(api.CreateSwapchain(c.session, &sci, &ce.handle))) {
            LOGE("影厅交换链创建失败（眼 %zu）", i);
            return false;
        }
        uint32_t imgCount = 0;
        api.EnumerateSwapchainImages(ce.handle, 0, &imgCount, nullptr);
        ce.images.resize(imgCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        if (XR_FAILED(api.EnumerateSwapchainImages(
                ce.handle, imgCount, &imgCount,
                reinterpret_cast<XrSwapchainImageBaseHeader *>(ce.images.data())))) {
            return false;
        }
        ce.fbos.resize(imgCount, 0);
        ce.depthRbos.assign(imgCount, 0);
        for (uint32_t k = 0; k < imgCount; k++) {
            glGenFramebuffers(1, &ce.fbos[k]);
            glBindFramebuffer(GL_FRAMEBUFFER, ce.fbos[k]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                   static_cast<GLuint>(ce.images[k].image), 0);
            glGenRenderbuffers(1, &ce.depthRbos[k]);
            glBindRenderbuffer(GL_RENDERBUFFER, ce.depthRbos[k]);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, ce.width, ce.height);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER,
                                      ce.depthRbos[k]);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                LOGE("影厅 FBO 不完整（眼 %zu 图 %u）", i, k);
                return false;
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        LOGI("影厅图层交换链：眼 %zu %dx%d（%u 张图）", i, ce.width, ce.height, imgCount);
    }
    c.cinemaEyesOk = true;
    return true;
}

/** 把影厅画进影厅图层（一只眼一张） */
bool renderCinemaEye(VrContext &c, int i, const XrView &view) {
    if (i < 0 || i >= (int) c.cinemaEyes.size()) return false;
    EyeSwapchain &ce = c.cinemaEyes[i];
    uint32_t imageIndex = 0;
    XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    if (XR_FAILED(api.AcquireSwapchainImage(ce.handle, &ai, &imageIndex))) return false;
    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    /*
     * 这一层**无限等**：它不是关键路径（影厅画面晚一帧没人看得出来），
     * 超时后按规范那张图仍是 acquired、不能写也不能 release，索性等到底。
     */
    wi.timeout = XR_INFINITE_DURATION;
    if (XR_FAILED(api.WaitSwapchainImage(ce.handle, &wi))) return false;

    glBindFramebuffer(GL_FRAMEBUFFER, ce.fbos[imageIndex]);
    glViewport(0, 0, ce.width, ce.height);
    /* 透明底：影厅没盖到的地方（理论上没有）也不该糊住下面 */
    glClearColor(0.f, 0.f, 0.f, 0.f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    const Mat4 proj = perspectiveFromFov(view.fov, 0.05f, 100.f);
    const Mat4 view4 = viewMatrixFromPose(view.pose);
    if (!c.cinemaEyesOk) drawCinema(proj, view4, view.pose.position);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    return XR_SUCCEEDED(api.ReleaseSwapchainImage(ce.handle, &ri));
}


/*
 * ==================== 银幕灯光联动（父亲 2026-10-10「把灯光也要加进去」） ====================
 *
 * 影厅里唯一的光源就是那块银幕 —— 所以银幕演什么，厅里就被照成什么颜色：
 * 爆炸场面偏橙、雪景偏冷白、黑场时厅里几乎全黑（只剩顶灯余光）。
 *
 * 做法（花钱最少的那条）：把这个视频帧缩到 16×16 画进一张小 FBO，readPixels 取回来
 * 算平均色与亮度，再指数平滑一下喂给影厅着色器。16×16 的绘制 + 回读，每 4 帧一次，
 * 开销可以忽略；比"在片源里插探针"稳得多，也不用改播放器。
 */
GLuint gLightFbo = 0;
GLuint gLightTex = 0;
int gLightTick = 0;
/** 影厅里的实际亮度（0~1，日志与调参用） */
std::atomic<float> gCinemaLuma{0.f};

void updateScreenLightFromVideo(VrContext &c) {
    const int N = 16;
    const bool hasVideo = c.videoTex != 0 && c.videoActive.load() && c.videoHasFrame.load();

    if (!hasVideo) {
        /* 没画面（空闲/等待中）：灯慢慢暗下去，留一点顶灯余光，别让厅里全黑 */
        const float k = 0.12f;
        gScreenTintR.store(gScreenTintR.load() + (0.55f - gScreenTintR.load()) * k);
        gScreenTintG.store(gScreenTintG.load() + (0.58f - gScreenTintG.load()) * k);
        gScreenTintB.store(gScreenTintB.load() + (0.70f - gScreenTintB.load()) * k);
        gCinemaGlow.store(gCinemaGlow.load() + (0.28f - gCinemaGlow.load()) * k);
        gCinemaLuma.store(gCinemaLuma.load() * (1.f - k));
        return;
    }
    if ((gLightTick++ % 4) != 0) return;

    if (gLightFbo == 0) {
        glGenFramebuffers(1, &gLightFbo);
        glGenTextures(1, &gLightTex);
        glBindTexture(GL_TEXTURE_2D, gLightTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, N, N, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindFramebuffer(GL_FRAMEBUFFER, gLightFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               gLightTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("灯光采样 FBO 建不起来，灯光联动关掉");
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, &gLightFbo);
            gLightFbo = 0;
            return;
        }
    }

    glBindFramebuffer(GL_FRAMEBUFFER, gLightFbo);
    glViewport(0, 0, N, N);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(c.program);
    Mat4 id = identity();
    glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, id.m);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, c.videoTex);
    if (c.texLoc >= 0) glUniform1i(c.texLoc, 0);
    if (c.useTexLoc >= 0) glUniform1i(c.useTexLoc, 1);
    if (c.circleLoc >= 0) glUniform1i(c.circleLoc, 0);
    if (c.expandLoc >= 0) glUniform1i(c.expandLoc, 0);
    if (c.downLoc >= 0) glUniform1i(c.downLoc, 0);
    if (c.testPatternLoc >= 0) glUniform1i(c.testPatternLoc, 0);
    if (c.brightLoc >= 0) glUniform1f(c.brightLoc, 0.f);
    if (c.contrastLoc >= 0) glUniform1f(c.contrastLoc, 1.f);
    if (c.satLoc >= 0) glUniform1f(c.satLoc, 1.f);
    if (c.sharpenLoc >= 0) glUniform1f(c.sharpenLoc, 0.f);
    if (c.tempLoc >= 0) glUniform1f(c.tempLoc, 0.f);
    if (c.jitterLoc >= 0) glUniform1f(c.jitterLoc, 0.f);
    glUniform4f(c.colorLoc, 1.f, 1.f, 1.f, 1.f);
    glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void *) 0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                          (void *) (3 * sizeof(float)));
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);

    unsigned char px[N * N * 4];
    glReadPixels(0, 0, N, N, GL_RGBA, GL_UNSIGNED_BYTE, px);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    float sr = 0.f, sg = 0.f, sb = 0.f;
    for (int i = 0; i < N * N; i++) {
        sr += (float) px[i * 4 + 0];
        sg += (float) px[i * 4 + 1];
        sb += (float) px[i * 4 + 2];
    }
    const float inv = 1.f / (float) (N * N * 255);
    sr *= inv;
    sg *= inv;
    sb *= inv;
    /* 亮度用 Rec.709 加权（和人眼感受一致） */
    const float luma = 0.2126f * sr + 0.7152f * sg + 0.0722f * sb;
    /* 颜色归一化成"色相"：暗场不要被噪声带偏，亮度单独走 glow */
    const float mx = fmaxf(sr, fmaxf(sg, sb));
    float tr = 1.f, tg = 1.f, tb = 1.f;
    if (mx > 0.02f) {
        tr = sr / mx;
        tg = sg / mx;
        tb = sb / mx;
    }
    /* 平滑（0.18）：灯别跟着画面闪，一帧一变的灯会晃眼 */
    const float k = 0.18f;
    gScreenTintR.store(gScreenTintR.load() + (tr - gScreenTintR.load()) * k);
    gScreenTintG.store(gScreenTintG.load() + (tg - gScreenTintG.load()) * k);
    gScreenTintB.store(gScreenTintB.load() + (tb - gScreenTintB.load()) * k);
    const float wantGlow = fminf(3.0f, 0.30f + luma * 3.2f);
    gCinemaGlow.store(gCinemaGlow.load() + (wantGlow - gCinemaGlow.load()) * k);
    gCinemaLuma.store(gCinemaLuma.load() + (luma - gCinemaLuma.load()) * k);

    /* 每 5 秒一行体检：父亲戴着时读这一行就能报"灯太亮/太暗/偏色" */
    static double lastLightLog = 0.0;
    if (nowMs() - lastLightLog > 5000.0) {
        lastLightLog = nowMs();
        LOGI("影厅灯光：画面亮度 %.2f → 光强 %.2f · 色调 (%.2f,%.2f,%.2f) · 环境光 %.2f · 座位 %d",
             (double) gCinemaLuma.load(), (double) gCinemaGlow.load(),
             (double) gScreenTintR.load(), (double) gScreenTintG.load(),
             (double) gScreenTintB.load(), (double) gEnvStrength.load(), gSeat.load());
    }
}

bool renderEye(VrContext &c, int eyeIndex, const XrView &view) {
    /* 银幕灯光联动（只算一次，两只眼共用）：把当前画面采成 16×16 喂给影厅光照 */
    if (eyeIndex == 0) updateScreenLightFromVideo(c);
    EyeSwapchain &eye = c.eyes[eyeIndex];
    uint32_t imageIndex = 0;
    XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    const XrResult ar = api.AcquireSwapchainImage(eye.handle, &ai, &imageIndex);
    if (XR_FAILED(ar)) {
        LOGE("取图失败（眼 %d）：%d", eyeIndex, (int) ar);
        return false;
    }
    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    /*
     * 原来写的是无限等待（父亲 2026-10-08 定案）：
     * 系统合成器没准备好这块图时，渲染线程就卡在这里等，一卡就 10~30ms，
     * 帧率从 72 掉到 50，头一转动就抖。
     * 改成 4ms（一帧预算的三分之一）：超时跳过这一帧的图层提交，场景照常跑。
     */
    wi.timeout = (XrDuration) (gSwapWaitTimeoutMs.load() * 1000000);
    const auto eyeWaitT0 = std::chrono::steady_clock::now();
    XrResult wr = api.WaitSwapchainImage(eye.handle, &wi);
    /*
     * 眼缓冲这条等待**有和视频层一模一样的 bug**（2026-10-09 一起修）：
     * `XR_TIMEOUT_EXPIRED` 是正值，`XR_FAILED()` 判不出来 → 超时被当成成功，
     * 于是往一张还没拿到所有权的图里画。
     *
     * 这一条比视频层更值得怀疑：眼缓冲就是**投影层**，覆盖整个视野 ——
     * 与父亲描述的"黑纹贯穿整个 VR 空间"吻合。规范要求超时后这张图仍处于
     * acquired 状态，不能写、不能 release，只能继续等同一张图。
     */
    if (wr == XR_TIMEOUT_EXPIRED) {
        gEyeWaitTimeout.fetch_add(1);
        wi.timeout = XR_INFINITE_DURATION;
        wr = api.WaitSwapchainImage(eye.handle, &wi);
    }
    gSwapWaitMs += std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - eyeWaitT0).count();
    if (XR_FAILED(wr)) {
        gEyeSkipCount.fetch_add(1);
        gSwapWaitError.fetch_add(1);
        LOGE("等图失败（眼 %d）：%d", eyeIndex, (int) wr);
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        api.ReleaseSwapchainImage(eye.handle, &ri);
        return false;
    }
    gSwapWaitOk.fetch_add(1);

    if (imageIndex >= eye.fbos.size() || eye.fbos[imageIndex] == 0) {
        LOGE("FBO 下标越界（眼 %d，index=%u，共 %zu）", eyeIndex, imageIndex, eye.fbos.size());
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        api.ReleaseSwapchainImage(eye.handle, &ri);
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, eye.fbos[imageIndex]);
    glViewport(0, 0, eye.width, eye.height);
    /*
     * 背景透明（alpha 0）：视频走独立图层之后，我们这一层必须让出银幕那块位置，
     * 不然整幅清屏色会把系统合成的视频盖住（配合投影层的源透明度标志）。
     */
    glClearColor(0.f, 0.f, 0.f, 0.f);
    /* 每帧统一刷一次画面调整（亮度/对比度/饱和度，视频与界面一起变）*/
    glUseProgram(c.program);
    if (c.brightLoc >= 0) glUniform1f(c.brightLoc, c.brightness.load());
    if (c.contrastLoc >= 0) glUniform1f(c.contrastLoc, c.contrast.load());
    if (c.satLoc >= 0) glUniform1f(c.satLoc, c.saturation.load());
    if (c.sharpenLoc >= 0) glUniform1f(c.sharpenLoc, c.sharpen.load());
    if (c.tempLoc >= 0) glUniform1f(c.tempLoc, c.temperature.load());
    if (c.texelLoc >= 0) glUniform2f(c.texelLoc, c.texelX.load(), c.texelY.load());
    /* 抖动种子每帧换一个：固定图案会被看成一层噪点纹理，随机的才是杂讯 */
    if (c.jitterLoc >= 0) {
        static uint32_t jitterTick = 0;
        glUniform1f(c.jitterLoc, (float) (jitterTick++ % 64u) * 1.7f);
    }
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    const Mat4 proj = perspectiveFromFov(view.fov, 0.05f, 100.f);
    const Mat4 view4 = viewMatrixFromPose(view.pose);
    /*
     * 影厅先画（父亲 2026-10-10）：开深度、画完立刻关掉 —— 后面的银幕/海报墙/控制条/弹幕
     * 还是原来的图层顺序，一层层叠上去，不受影厅遮挡影响。
     */
    if (!c.cinemaEyesOk) drawCinema(proj, view4, view.pose.position);
    if (c.program != 0 && c.mvpLoc >= 0) {
        /*
         * 一块屏：摆位 → 位置/朝向/尺寸，贴 tex（tex = 0 就画底色）。
         *
         * 播放画面与海报墙走同一条绘制路径，只有摆位不同 —— 2026-10-05 父亲要求
         * 「海报墙与播放屏分开」：以前是同一块屏来回换贴图，播放一开海报墙就被顶掉。
         */
        auto drawScreen = [&](const ScreenPlacement &place, unsigned tex, bool isVideo = false) {
            const Mat4 mvp = multiply(multiply(proj, view4), placementModel(place));
            glUseProgram(c.program);
            /* 这一路永远不用测试图（2026-10-09 诊断开关，只在视频独立层开） */
            if (c.testPatternLoc >= 0) glUniform1i(c.testPatternLoc, 0);
            if (gNoPostfx.load() != 0) {
                if (c.brightLoc >= 0) glUniform1f(c.brightLoc, 0.f);
                if (c.contrastLoc >= 0) glUniform1f(c.contrastLoc, 1.f);
                if (c.satLoc >= 0) glUniform1f(c.satLoc, 1.f);
                if (c.sharpenLoc >= 0) glUniform1f(c.sharpenLoc, 0.f);
                if (c.tempLoc >= 0) glUniform1f(c.tempLoc, 0.f);
            }
            glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, mvp.m);
            if (c.circleLoc >= 0) glUniform1i(c.circleLoc, 0);   // 屏是方的，不做圆形裁剪
            /*
             * 三块屏统一规矩：**只有这块纹理自己拿到过帧，才贴它**；没有帧就画底色。
             * 不这么做的后果（见 panelHasFrame 的注释）：显卡会把另一张外部纹理的画面
             * 借过来 —— 上一轮是控制条贴上视频，这一轮是播放屏贴上控制条按钮。
             */
            if (tex != 0) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
                glUniform1i(c.texLoc, 0);
                glUniform1i(c.useTexLoc, 1);
                if (c.expandLoc >= 0) glUniform1i(c.expandLoc, 0);
                /* no_downsample：强制走单次纹理采样（2026-10-09 诊断开关） */
                if (c.downLoc >= 0) {
                    glUniform1i(c.downLoc, (isVideo && gNoDownsample.load() == 0) ? 1 : 0);
                }
                if (isVideo && c.screenStepLoc >= 0) {
                    /*
                     * 银幕上一个屏幕像素对应多大一块 UV —— 按投影的水平视角、银幕宽度、
                     * 银幕距离和渲染缓冲宽度算出来。缩得越狠这个步长越大，
                     * 4×4 平均覆盖的纹理范围也越大，正好抵消缩小带来的细节丢失。
                     */
                    const float fovH = 2.f * atanf(1.f / fmaxf(0.1f, proj.m[0]));
                    const float dist = fmaxf(0.5f, fabsf(place.cz));
                    const float px = place.width * (float) c.eyes[0].width /
                                     (2.f * dist * tanf(fovH * 0.5f));
                    const float step = 1.f / fmaxf(128.f, px);
                    glUniform2f(c.screenStepLoc, step, step);
                }
                glUniform4f(c.colorLoc, 1.f, 1.f, 1.f, 1.f);
            } else {
                glUniform1i(c.useTexLoc, 0);
                if (c.downLoc >= 0) glUniform1i(c.downLoc, 0);
                glUniform4f(c.colorLoc, 0.018f, 0.019f, 0.022f, 1.f);  // 近黑微光（父亲 2026-10-06：再暗一点）
            }
            glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void *) 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                                  (void *) (3 * sizeof(float)));
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glDisableVertexAttribArray(0);
            glDisableVertexAttribArray(1);
        };

        /*
         * 一块**正对观影者**的透明覆盖层（弹幕层 / 片名 logo 用）。
         * 这两块都是平面正对着人，不需要偏航/仰角，单位四元数就够。
         */
        auto drawFlatOverlay = [&](GLuint tex, float cx, float cy, float cz,
                                   float w, float h) {
            XrPosef pose{};
            pose.position = {cx, cy, cz};
            pose.orientation = {0.f, 0.f, 0.f, 1.f};
            const Mat4 model = poseScaleModel(pose, w, h, 1.f);
            const Mat4 mvp = multiply(multiply(proj, view4), model);
            glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, mvp.m);
            if (c.circleLoc >= 0) glUniform1i(c.circleLoc, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
            glUniform1i(c.texLoc, 0);
            glUniform1i(c.useTexLoc, 1);
            if (c.expandLoc >= 0) glUniform1i(c.expandLoc, 0);
            if (c.downLoc >= 0) glUniform1i(c.downLoc, 0);
            glUniform4f(c.colorLoc, 1.f, 1.f, 1.f, 1.f);
            glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void *) 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                                  (void *) (3 * sizeof(float)));
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glDisableVertexAttribArray(0);
            glDisableVertexAttribArray(1);
        };

        // 前方银幕：播放时贴视频；没播时是一块空屏（暗色），空间里有"银幕"在
        const bool videoReady = c.videoActive.load() && c.videoTex != 0 && c.videoHasFrame.load();
        /*
         * 视频屏：交给独立图层时这里留空（背景是透明的），交给系统合成器去缩；
         * 独立层建不起来才退回老路 —— 画进我们自己的画面，带 4×4 降采样。
         */
        /*
         * 换片 / 首播的等待期（spinnerWanted）：这里也要留空。
         * 此刻弹幕层是「黑幕 + 转圈 + 片名提示」，而它压在这一层（投影层）下面 ——
         * 这里再画一块不透明的视频或黑，就把黑幕和提示全盖掉了（父亲 2026-10-07）。
         */
        if (c.videoLayer.submitted || c.spinnerWanted.load()) {
            // 留空：视频在黑幕下面那层，位置一致
        } else if (!c.blankLayer.submitted) {
            /*
             * 空屏层已提交时这里留空（2026-10-09）：那块暗色银幕改走独立层了，
             * 位置一致、层次稳在弹幕/海报墙/控制条/菜单下面；这里再画一份会把它们盖回去
             * （父亲实测：控制条菜单被黑屏遮挡，就是这个原因）。
             * 独立层没建起来（blankLayer 失败）时仍走老路，银幕不会整个消失。
             */
            drawScreen(frontScreen(c), videoReady ? c.videoTex : 0, true);
        }

        /*
         * 图层体检（2026-10-06 晚加）：父亲报「弹幕和片名 logo 看不见」。
         * 每 3 秒打一行，一眼看出是"没出帧"还是"状态没开"。
         */
        {
            static double lastLayerLog = 0.0;
            if (nowMs() - lastLayerLog > 3000.0) {
                lastLayerLog = nowMs();
                LOGI("图层体检：面板帧%d 视频帧%d 控制条帧%d/开%d 菜单帧%d/开%d 弹幕帧%d/开关%d logo帧%d/开关%d",
                     c.panelHasFrame.load() ? 1 : 0,
                     c.videoHasFrame.load() ? 1 : 0,
                     c.osdHasFrame.load() ? 1 : 0, c.osdVisible.load() ? 1 : 0,
                     c.menuHasFrame.load() ? 1 : 0, c.menuVisible.load() ? 1 : 0,
                     c.danmakuHasFrame.load() ? 1 : 0, c.danmakuVisible.load() ? 1 : 0,
                     c.logoHasFrame.load() ? 1 : 0, c.logoVisible.load() ? 1 : 0);
            }
        }

        // 海报墙：常驻左前方斜放；收起时不画，没出帧也先不画（不闪也不串）
        const bool panelReady = c.panelActive.load() && c.panelTex != 0 && c.panelHasFrame.load();
        /*
         * 海报墙走独立合成层（2026-10-09）：平时不再画进眼缓冲（否则被降采样糊掉），
         * 由系统合成器按纹理原始像素贴。仅当独立层建不出来（panelLayerOk 被永久关掉）
         * 才退回老路画进眼缓冲兜底，免得海报墙整个消失。
         */
        if (panelReady && c.panelShown.load() && !c.panelLayer.built &&
            (gLayerMask.load() & 8) != 0) {
            drawScreen(panelPlacement(c), c.panelTex);
        }

        // 起播 / 换片到第一帧之间：银幕上转圈，别留上一部的画面（父亲 2026-10-05 要求）
        // 换片时先让银幕空一拍，再出转圈 —— 父亲 2026-10-06："先清屏，再显示加载箭头"
        /*
         * 两种等待都画转圈：
         *  · videoActive 已开、第一帧还没到（起播中）；
         *  · 换片 / 首播的等待期 —— 这时 videoActive 是 false（银幕已清空），
         *    靠 Java 侧的 spinnerWanted 顶上（父亲 2026-10-07 实测：清空了但不转圈，
         *    就是因为这里只认 videoActive）。
         */
        /*
         * GL 版转圈**停用**（父亲 2026-10-07）：实测"开关一直开着、圈就是不出现"
         * （日志里 `银幕转圈 → 等第一帧` 持续数秒，银幕上却没有圈）。
         * 等待期的转圈改由弹幕层画布提供（Java 侧 drawLoadingSpinner）——
         * 那一层反复验证可见，而且黑幕、提示都在同一张画布上，层序自洽。
         * 代码保留，要回退时恢复这个 if 即可。
         */
        if (false && (c.videoActive.load() || c.spinnerWanted.load()) && !videoReady &&
            nowMs() - c.videoActiveAtMs > 250.0) {
            drawSpinner(c, proj, view4);
        }

        /*
         * 控制条（近场小面板，2026-10-05）：贴在观影者正前方偏下、上仰一点，
         * 与主画面同一套着色器与属性布局，只是换一张纹理、换一个模型矩阵。
         */
        /*
         * 控制条：独立层提交成功时这里留空（2026-10-09）——面板已按原始像素交给
         * 系统合成器，这里再画一份等于又画进眼缓冲、被降采样糊一次。
         * **独立层没建起来时仍走这条老路**：上一版把这段删掉，层一出问题控制条
         * 就整个消失（父亲实测），不再重犯。
         */
        if (!c.osdLayer.submitted && (gLayerMask.load() & 16) != 0 &&
            c.osdVisible.load() && c.osdTex != 0 && c.osdHasFrame.load()) {
            /*
             * 只给控制条开 alpha 混合（父亲 2026-10-06：「叠了两层，下层没有倒圆角」）：
             * Java 侧把控制条窗口背景清成透明，圆角外 alpha=0，这里混合后透出影院背景。
             * 上一版把这个 enable 放在全局，结果视频层也被混合成半透明 ——
             * 父亲随即报「视频屏幕像蒙了一层纱」，所以改成只在控制条这一段开。
             */
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            const float th = kOsdTiltDeg * 3.14159265358979f / 180.f;
            XrPosef osdPose{};
            osdPose.position = {0.f, kOsdCenterY, -kOsdDistance};
            osdPose.orientation = {sinf(th * 0.5f), 0.f, 0.f, cosf(th * 0.5f)};
            const Mat4 osdModel = poseScaleModel(osdPose, kOsdWidth, kOsdHeight, 1.f);
            const Mat4 osdMvp = multiply(multiply(proj, view4), osdModel);
            glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, osdMvp.m);
            if (c.circleLoc >= 0) glUniform1i(c.circleLoc, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, c.osdTex);
            glUniform1i(c.texLoc, 0);
            glUniform1i(c.useTexLoc, 1);
            if (c.expandLoc >= 0) glUniform1i(c.expandLoc, 0);   // 控制条按全范围画，不拉
            if (c.downLoc >= 0) glUniform1i(c.downLoc, 0);       // 控制条不做降采样
            glUniform4f(c.colorLoc, 1.f, 1.f, 1.f, 1.f);
            glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void *) 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                                  (void *) (3 * sizeof(float)));
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glDisableVertexAttribArray(0);
            glDisableVertexAttribArray(1);
            glDisable(GL_BLEND);
        }

        /*
         * 展开菜单（2026-10-06 父亲定）：与控制条同一套画法，只是一块更大的透明面板，
         * 架在控制条正上方。卡片画在面板哪儿由 Java 侧决定（两面板等宽，坐标直接对齐）。
         */
        /*
         * 片名 logo 的 GL 场景绘制已删（2026-10-07 00:55）：
         * 它和视频层里那份叠加，父亲实测「肉眼可见至少两层 logo」。
         * logo 现在只画在视频层的合成图里（renderQuadLayer 的 videoLayerPass 分支）。
         */

        /* 菜单同上（2026-10-09）：独立层提交成功就留空，没建起来仍走这条老路 */
        if (!c.menuLayer.submitted && (gLayerMask.load() & 32) != 0 &&
            c.menuVisible.load() && c.menuTex != 0 && c.menuHasFrame.load()) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            const float mth = kMenuTiltDeg * 3.14159265358979f / 180.f;
            float mcx, mcy, mcz, mnx, mny, mnz, mux, muy, muz, mvx, mvy, mvz;
            menuBasis(&mcx, &mcy, &mcz, &mnx, &mny, &mnz, &mux, &muy, &muz, &mvx, &mvy, &mvz);
            XrPosef menuPose{};
            menuPose.position = {mcx, mcy, mcz};
            menuPose.orientation = {sinf(mth * 0.5f), 0.f, 0.f, cosf(mth * 0.5f)};
            const Mat4 menuModel = poseScaleModel(menuPose, kMenuWidth, kMenuHeight, 1.f);
            const Mat4 menuMvp = multiply(multiply(proj, view4), menuModel);
            glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, menuMvp.m);
            if (c.circleLoc >= 0) glUniform1i(c.circleLoc, 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, c.menuTex);
            glUniform1i(c.texLoc, 0);
            glUniform1i(c.useTexLoc, 1);
            if (c.expandLoc >= 0) glUniform1i(c.expandLoc, 0);
            if (c.downLoc >= 0) glUniform1i(c.downLoc, 0);
            glUniform4f(c.colorLoc, 1.f, 1.f, 1.f, 1.f);
            glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void *) 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                                  (void *) (3 * sizeof(float)));
            glDrawArrays(GL_TRIANGLES, 0, 6);
            glDisableVertexAttribArray(0);
            glDisableVertexAttribArray(1);
            glDisable(GL_BLEND);
        }
    }

    /*
     * 手柄射线 + 光点 —— 按官方 PICO Native OpenXR SDK 的做法来（不再靠试）：
     *
     *   · 指向：aim 姿态把本地 -Z 旋转出来（SampleCollisionDetector.cpp）
     *   · 长度：射线打到面板就用命中距离；没打到用官方默认的 100m
     *     （AndroidOpenXrProgram::HandleCollisionDetection 里 distance = 100.0f）
     *   · 形状：圆锥（TruncatedCone::GenerateRayMeshAtDefaultRadius）
     *     缩放 (0.001, 0.001, 距离) —— AndroidOpenXrProgram::UpdateRay
     *   · 光点：只在**射线打到面板**时出现，画成圆点（父亲 2026-10-05 定的规范：
     *     指到空处只有光线、没有光点）
     */
    /*
     * 手柄动没动（父亲 2026-10-07）：角度变 1° 以上、或位置挪 1cm 以上就算在动。
     * 5 秒没有这种变化 → 这条激光收起来，省得举着手不动时一道光杵在画面里；
     * 一动立刻恢复。
     */
    for (int h = 0; h < 2; h++) {
        if (!c.aimValid[h]) continue;
        const double nowAim = nowMs();
        if (!c.aimPoseInit[h]) {
            c.aimPoseInit[h] = true;
            c.aimLastPose[h] = c.aimPose[h];
            c.aimLastMoveMs[h] = nowAim;
            continue;
        }
        const XrPosef &cur = c.aimPose[h];
        const XrPosef &ref = c.aimLastPose[h];
        const float ddx = cur.position.x - ref.position.x;
        const float ddy = cur.position.y - ref.position.y;
        const float ddz = cur.position.z - ref.position.z;
        const float moved = std::sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        float dot = cur.orientation.x * ref.orientation.x +
                    cur.orientation.y * ref.orientation.y +
                    cur.orientation.z * ref.orientation.z +
                    cur.orientation.w * ref.orientation.w;
        dot = std::fabs(dot);
        if (dot > 1.0f) dot = 1.0f;
        const float turned = 2.0f * std::acos(dot) * 57.29578f;   // 度
        if (moved > 0.01f || turned > 1.0f) {
            c.aimLastMoveMs[h] = nowAim;
            c.aimLastPose[h] = cur;
        }
    }

    if (c.program != 0 && c.mvpLoc >= 0 && c.rayVbo != 0) {
        const float kRayNear = 0.001f;    // 近端半径 1mm（官方 handScale 的 x/y 分量）
        const float kNoHitDistance = 100.f;
        const float kDotSize = 0.011f;    // 光点直径 1.1cm（父亲 2026-10-06：再小一半）

        for (int h = 0; h < 2; h++) {
            if (!c.aimValid[h]) continue;
            // 放着不动超过 5 秒 → 这条激光（连同光点）先收起来（父亲 2026-10-07）
            if (nowMs() - c.aimLastMoveMs[h] > 5000.0) continue;

            /*
             * 射线打到哪就在哪收住，并在那块面上画光点。
             *
             * 父亲 2026-10-06：**银幕**上要有光点，**控制条上的二级/三级菜单**也要有。
             * 四块面依次比距离，取最近的那块：
             *   ① 海报墙（左前方）  ② 银幕（正前方）  ③ 控制条  ④ 菜单面板（架在控制条上方）
             */
            float rayLength = kNoHitDistance;   // 没打中就射 100m（官方默认）
            float dotT = -1.f;
            int dotKind = 0;                    // 1=平面屏（海报墙/银幕）2=控制条 3=菜单
            ScreenPlacement dotPlace = panelPlacement(c);

            // ① 海报墙（收起时不参与）
            {
                const ScreenPlacement place = panelPlacement(c);
                float t = 0.f, u = 0.f, v = 0.f, hx = 0.f, hy = 0.f, hz = 0.f;
                if (c.panelShown.load() &&
                    rayHitsPlacement(c.aimPose[h], place, &t, &u, &v, &hx, &hy, &hz) && t > 0.f) {
                    rayLength = t; dotT = t; dotKind = 1; dotPlace = place;
                }
            }
            // ② 银幕（播放画面那块屏，收起时它也在，但没画面就别抢光点）
            {
                const ScreenPlacement front = frontScreen(c);
                float t = 0.f, u = 0.f, v = 0.f, hx = 0.f, hy = 0.f, hz = 0.f;
                if (rayHitsPlacement(c.aimPose[h], front, &t, &u, &v, &hx, &hy, &hz) &&
                    t > 0.f && t < rayLength) {
                    rayLength = t; dotT = t; dotKind = 1; dotPlace = front;
                }
            }
            // ③ 控制条
            {
                float t = 0.f, u = 0.f, v = 0.f;
                if (c.osdVisible.load() && c.osdTex != 0 && c.osdHasFrame.load() &&
                    rayHitsOsd(c, c.aimPose[h], &t, &u, &v) && t > 0.f && t < rayLength) {
                    rayLength = t; dotT = t; dotKind = 2;
                }
            }
            // ④ 菜单面板（离人最近的一块，通常最后赢）
            {
                float t = 0.f, u = 0.f, v = 0.f;
                if (c.menuVisible.load() && c.menuTex != 0 && c.menuHasFrame.load() &&
                    rayHitsMenu(c, c.aimPose[h], &t, &u, &v) && t > 0.f && t < rayLength) {
                    rayLength = t; dotT = t; dotKind = 3;
                }
            }

            // 光线：从手柄沿指向射出
            /*
             * 光柱截面粗细要按**银幕距离**等比缩放（2026-10-09）。
             *
             * kRayNear = 1mm 是"照抄官方 handScale 的 x/y 分量"，当年银幕在 3.2 米外时
             * 勉强够看（约 0.37 像素，靠抗锯齿显形）。父亲把银幕按 IMAX 几何挪到 15 米外
             * 之后，同样的 1 毫米只剩 0.08 像素 —— **光柱会直接消失**。
             * 这里按 (银幕距离 / 参考距离) 放大，保证头显里看到的粗细与以前一致
             * （也就是保持角粗细不变，这跟银幕放多远无关）。
             */
            const float kRayRefDistance = 3.2f;   // 参考距离：光柱原来按 3.2 米调的粗细
            const float rayThick = kRayNear * gRayScale.load() *
                fmaxf(0.5f, gScreenDistance.load() / kRayRefDistance);
            const Mat4 rayModel = poseScaleModel(c.aimPose[h], rayThick, rayThick, rayLength);
            drawMesh(c, c.rayVbo, c.rayVertexCount,
                     multiply(multiply(proj, view4), rayModel),
                     1.f, 1.f, 1.f, false);   // 白光（父亲 2026-10-09：绿光改白）

            /*
             * 光点：贴在命中点上，朝眼睛方向抬几毫米（避免和面抢像素）。
             * 姿态跟着那块面的朝向走：斜的屏/控制条/菜单，光点也斜着贴上去。
             */
            if (dotKind != 0 && dotT > 0.f) {
                float dx = 0.f, dy = 0.f, dz = 0.f;
                aimDirection(c.aimPose[h], &dx, &dy, &dz);
                float nx = 0.f, ny = 0.f, nz = 1.f;
                XrQuaternionf rot{0.f, 0.f, 0.f, 1.f};
                if (dotKind == 1) {
                    // 平面屏可能斜着（海报墙在左边、朝右前方）：光点跟着屏的朝向转
                    const float pth = dotPlace.yawDeg * 3.14159265358979f / 180.f;
                    nx = sinf(pth); ny = 0.f; nz = cosf(pth);
                    rot = {0.f, sinf(pth * 0.5f), 0.f, cosf(pth * 0.5f)};
                } else {
                    float bx = 0.f, by = 0.f, bz = 0.f, bnx = 0.f, bny = 0.f, bnz = 0.f;
                    float bux = 0.f, buy = 0.f, buz = 0.f, bvx = 0.f, bvy = 0.f, bvz = 0.f;
                    if (dotKind == 2) {
                        osdBasis(&bx, &by, &bz, &bnx, &bny, &bnz,
                                 &bux, &buy, &buz, &bvx, &bvy, &bvz);
                    } else {
                        menuBasis(&bx, &by, &bz, &bnx, &bny, &bnz,
                                  &bux, &buy, &buz, &bvx, &bvy, &bvz);
                    }
                    nx = bnx; ny = bny; nz = bnz;
                    const float tth = (dotKind == 2 ? kOsdTiltDeg : kMenuTiltDeg) *
                                      3.14159265358979f / 180.f;
                    rot = {sinf(tth * 0.5f), 0.f, 0.f, cosf(tth * 0.5f)};
                }
                XrPosef dotPose{};
                dotPose.position = {
                        c.aimPose[h].position.x + dx * dotT + nx * 0.006f,
                        c.aimPose[h].position.y + dy * dotT + ny * 0.006f,
                        c.aimPose[h].position.z + dz * dotT + nz * 0.006f,
                };
                dotPose.orientation = rot;
                /*
                 * 光点大小按**命中距离**缩放（2026-10-09）。
                 *
                 * kDotSize = 1.1cm 是固定"米"尺寸，当年按银幕 3.2 米外调定的；
                 * 银幕挪到 15 米外后，1.1cm 只剩 0.87 像素 —— 光点会看不见。
                 * 只对**银幕**（dotKind==1）按距离等比放大，保持角大小与以前一致；
                 * 控制条（0.85 m）与菜单（0.92 m）是近场，按老样子不动，
                 * 免得把父亲已经调好的近场手感改掉。
                 */
                const float dotScale =
                        (dotKind == 1) ? fmaxf(0.5f, dotT / 3.2f) : 1.f;
                const Mat4 dotModel = poseScaleModel(dotPose, kDotSize * dotScale,
                                                     kDotSize * dotScale, 1.f);
                drawMesh(c, c.vbo, 6, multiply(multiply(proj, view4), dotModel),
                         1.f, 1.f, 1.f, true);   // 白点（大小不变，父亲 2026-10-09）
            }

        }
    }

    // 画完必须解绑 FBO，否则下一只眼/下一帧会画进同一个附件
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    /*
     * 交回图像前必须把 GL 命令推进命令流（父亲 2026-10-08 报「整个场景横向黑纹」）。
     *
     * GL 命令是异步的：画进 FBO 之后如果直接 xrReleaseSwapchainImage，
     * 运行时有几率在命令还没执行完时就把这块图拿去合成 —— 表现就是整幅画面
     * 出现上下漂移的横向撕裂。OpenXR 用 GL 的标准做法就是 release 前 glFlush()。
     */
    if (gSyncMode.load() == 1) {
        glFinish();
    } else {
        glFlush();
    }

    XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    const XrResult rr = api.ReleaseSwapchainImage(eye.handle, &ri);
    if (XR_FAILED(rr)) {
        LOGE("交回图失败（眼 %d）：%d", eyeIndex, (int) rr);
        return false;
    }
    return true;
}

/* ===================== 视频独立合成层（父亲 2026-10-06） ===================== */

/** 单位矩阵（全屏四边形的顶点已经是 NDC 坐标，不再需要任何变换） */
Mat4 identityMat() {
    Mat4 m{};
    m.m[0] = 1.f;
    m.m[5] = 1.f;
    m.m[10] = 1.f;
    m.m[15] = 1.f;
    return m;
}

/** 全屏四边形（pos 3 分量 + uv 2 分量，与主着色器的顶点布局一致） */
void ensureVideoLayerQuad(VrContext &c) {
    if (c.videoLayerVao != 0) return;
    /*
     * 顶点约定与单位方块保持一致：v=0 在图像顶部、v=1 在底部。
     * 反过来写的话视频会上下颠倒（这层是我们自己新起的一条渲染路径，不能想当然）。
     */
    const float verts[] = {
            -1.f, -1.f, 0.f, 0.f, 1.f,
            1.f, -1.f, 0.f, 1.f, 1.f,
            -1.f, 1.f, 0.f, 0.f, 0.f,
            1.f, 1.f, 0.f, 1.f, 0.f,
    };
    glGenVertexArrays(1, &c.videoLayerVao);
    glGenBuffers(1, &c.videoLayerVbo);
    glBindVertexArray(c.videoLayerVao);
    glBindBuffer(GL_ARRAY_BUFFER, c.videoLayerVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void *) 0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                          (void *) (3 * sizeof(float)));
    glBindVertexArray(0);
}

/**
 * 建（或按新尺寸重建）视频层的交换链。
 *
 * 尺寸就用视频的真实分辨率（上限 4K）—— 这是这条路的全部意义：视频以原始尺寸
 * 交给系统合成器，缩到面板那一步由它做，我们不再先缩一次。
 */
bool buildQuadLayer(VrContext &c, VideoLayerBuf &L, int32_t w, int32_t h,
                    const char *what) {
    if (L.built && L.width == w && L.height == h) return true;

    if (L.handle != XR_NULL_HANDLE) {
        api.DestroySwapchain(L.handle);
        L.handle = XR_NULL_HANDLE;
        L.built = false;
        L.fbos.clear();
        L.images.clear();
    }

    /*
     * 格式先按 sRGB 试（与主画面输出一致：我们在着色器里已经把值转成线性，
     * 由运行时再编码回 sRGB）；不支持就退回普通 8 位。
     */
    /* force_rgba8：只试 GL_RGBA8，跳过 sRGB（2026-10-09 诊断开关） */
    const int64_t candidates[2] = {GL_SRGB8_ALPHA8, GL_RGBA8};
    const int candN = (gForceRgba8.load() != 0) ? 1 : 2;
    bool created = false;
    for (int ci = 0; ci < candN; ci++) {
        const int64_t fmt = candidates[gForceRgba8.load() != 0 ? 1 : ci];
        XrSwapchainCreateInfo sci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        sci.arraySize = 1;
        sci.mipCount = 1;
        sci.faceCount = 1;
        sci.format = fmt;
        sci.width = w;
        sci.height = h;
        sci.sampleCount = 1;
        sci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        if (XR_SUCCEEDED(api.CreateSwapchain(c.session, &sci, &L.handle))) {
            created = true;
            break;
        }
        L.handle = XR_NULL_HANDLE;
    }
    if (!created) {
        LOGE("%s交换链创建失败（%dx%d），这一版仍走老路", what, w, h);
        return false;
    }

    uint32_t imgCount = 0;
    api.EnumerateSwapchainImages(L.handle, 0, &imgCount, nullptr);
    if (imgCount == 0) {
        LOGE("%s交换链没有可用图像", what);
        api.DestroySwapchain(L.handle);
        L.handle = XR_NULL_HANDLE;
        return false;
    }
    L.images.assign(imgCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    if (XR_FAILED(api.EnumerateSwapchainImages(
                L.handle, imgCount, &imgCount,
                reinterpret_cast<XrSwapchainImageBaseHeader *>(L.images.data())))) {
        LOGE("%s图像枚举失败", what);
        api.DestroySwapchain(L.handle);
        L.handle = XR_NULL_HANDLE;
        return false;
    }
    L.fbos.assign(imgCount, 0);
    for (uint32_t k = 0; k < imgCount; k++) {
        glGenFramebuffers(1, &L.fbos[k]);
        glBindFramebuffer(GL_FRAMEBUFFER, L.fbos[k]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               static_cast<GLuint>(L.images[k].image), 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("%s FBO 不完整（图 %u）", what, k);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            api.DestroySwapchain(L.handle);
            L.handle = XR_NULL_HANDLE;
            return false;
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    L.width = w;
    L.height = h;
    L.built = true;
    LOGI("%s独立层已就绪：%dx%d，%u 张图（缩放交给系统合成器）", what, w, h, imgCount);
    return true;
}

/**
 * 把当前视频帧搬进视频层缓冲。
 *
 * 1:1 原样拷贝，只带我们自己的画面调整（亮度/对比度/饱和度/锐度/色温），
 * 不做降采样 —— 缩放由系统合成器在面板分辨率上完成，这是清晰度的关键。
 */
/**
 * 把一张 OES 纹理叠进「视频合成层」里（父亲 2026-10-06 晚）。
 *
 * 为什么必须画在这一层：视频走的是**独立合成层**，它在我们的 GL 画面之上 ——
 * 之前弹幕与片名 logo 画在主画面里，等于画在视频背后，整块被盖住，所以看不见。
 * 这里趁视频刚画完、还没 release，把两块内容叠到同一张图上。
 *
 * @param cx,cy 归一化中心（-1…1，0 是正中央）
 * @param sx,sy 占整层的宽高比例（1 = 铺满）
 */
void drawOverlayIntoVideoLayer(VrContext &c, GLuint tex,
                               float cx, float cy, float sx, float sy) {
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    const Mat4 m = translateScale(cx, cy, 0.f, sx, sy);
    glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, m.m);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
    glUniform1i(c.texLoc, 0);
    glUniform1i(c.useTexLoc, 1);
    if (c.expandLoc >= 0) glUniform1i(c.expandLoc, 0);
    if (c.downLoc >= 0) glUniform1i(c.downLoc, 0);
    glUniform4f(c.colorLoc, 1.f, 1.f, 1.f, 1.f);
    // 叠加层不做画面调整：亮度/对比度/饱和度/锐度只作用于视频本身
    if (c.brightLoc >= 0) glUniform1f(c.brightLoc, 0.f);
    if (c.contrastLoc >= 0) glUniform1f(c.contrastLoc, 1.f);
    if (c.satLoc >= 0) glUniform1f(c.satLoc, 1.f);
    if (c.sharpenLoc >= 0) glUniform1f(c.sharpenLoc, 0.f);
    if (c.tempLoc >= 0) glUniform1f(c.tempLoc, 0.f);
    if (c.texelLoc >= 0) glUniform2f(c.texelLoc, c.texelX.load(), c.texelY.load());
    if (c.jitterLoc >= 0) glUniform1f(c.jitterLoc, 0.f);
    glBindVertexArray(c.videoLayerVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
}

/**
 * 取一张"可以安全写"的交换链图像：acquire + wait，并按 OpenXR 规范处理超时。
 *
 * 2026-10-09 修（定位黑纹时发现的协议违规）：
 *
 *  `XR_TIMEOUT_EXPIRED` 是**正值 1**，而 `XR_FAILED()` 只判负值。原来写成
 *  `if (XR_FAILED(WaitSwapchainImage(...)))` → 超时被当成"成功"，代码继续
 *  往一张**没等到所有权**的图里写，末尾照常 release。合成器可能正在读这张图
 *  → 画面被半途改写 → 横向撕裂/黑纹。而且超时分支不执行，
 *  跳过计数永远是 0，日志把证据也吞了。
 *
 * 正确语义（规范要求）：超时后这张图**仍处于 acquired 状态**，
 * 应用不能写、也不能把它 release 掉，只能**继续等待同一张图**直到成功。
 * 所以这里超时后改用无限等待 —— 会真实反映"到底卡了多久"（计进等交换链耗时），
 * 而不是假装成功往下画。
 *
 * 返回 true 时 outIdx 是一张已完成 wait、可以写的图；false 表示本帧放弃该层。
 */
bool acquireWritableImage(VrContext &c, VideoLayerBuf &L, uint32_t *outIdx,
                          bool videoLayerPass) {
    (void) c;
    XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    uint32_t idx = 0;
    if (XR_FAILED(api.AcquireSwapchainImage(L.handle, &ai, &idx))) {
        gSwapWaitError.fetch_add(1);
        return false;
    }
    if (idx >= L.fbos.size()) {
        /* acquire 成功但索引异常：必须把它还回去，否则交换链状态被卡死 */
        gSwapWaitError.fetch_add(1);
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        api.ReleaseSwapchainImage(L.handle, &ri);
        return false;
    }

    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    /*
     * 原来写的是无限等待（父亲 2026-10-08 定案）：
     * 系统合成器没准备好这块图时，渲染线程就卡在这里等，一卡就 10~30ms，
     * 帧率从 72 掉到 50，头一转动就抖。
     * 改成 4ms（一帧预算的三分之一）：超时跳过这一帧的图层提交，场景照常跑。
     */
    wi.timeout = (XrDuration) (gSwapWaitTimeoutMs.load() * 1000000);
    const auto t0 = std::chrono::steady_clock::now();
    XrResult wr = api.WaitSwapchainImage(L.handle, &wi);

    if (wr == XR_TIMEOUT_EXPIRED) {
        /*
         * 真的超时了 —— 这一句以前永远走不到（被 XR_FAILED 漏掉）。
         * 计数 + 继续等同一张图（规范要求，不能 release）。
         */
        gSwapWaitTimeout.fetch_add(1);
        wi.timeout = XR_INFINITE_DURATION;
        wr = api.WaitSwapchainImage(L.handle, &wi);
    }

    gSwapWaitMs += std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - t0).count();

    if (XR_FAILED(wr)) {
        gSwapWaitError.fetch_add(1);
        if (videoLayerPass) {
            gVideoLayerSkipCount.fetch_add(1);
        } else {
            gOtherLayerSkipCount.fetch_add(1);
        }
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        api.ReleaseSwapchainImage(L.handle, &ri);
        return false;
    }
    gSwapWaitOk.fetch_add(1);
    *outIdx = idx;
    return true;
}

bool renderQuadLayer(VrContext &c, VideoLayerBuf &L, GLuint tex,
                     bool videoLayerPass) {
    // 视频层铺满整块、不透明；弹幕层是一层透明浮层，清屏必须透明，
    // 否则它会变成一块黑板把视频整个盖住（父亲 2026-10-06 晚实测现象）。
    if (!L.built || tex == 0 || c.program == 0) return false;

    uint32_t idx = 0;
    if (!acquireWritableImage(c, L, &idx, videoLayerPass)) return false;

    ensureVideoLayerQuad(c);

    glBindFramebuffer(GL_FRAMEBUFFER, L.fbos[idx]);
    glViewport(0, 0, L.width, L.height);
    /*
     * GL 状态显式清零（2026-10-09，ChatGPT 复查时指出）：
     *
     * 原来只关了深度和混合，没碰 GL_SCISSOR_TEST / GL_STENCIL_TEST / glColorMask。
     * 这几个状态都会**限制写入区域** —— 前面眼缓冲或界面 pass 留下的裁剪/写掩码
     * 一旦泄漏到这里，glClear 与 glDrawArrays 就只作用在一部分区域上，
     * 交换链里剩下的区域保留旧内容 → 表现为横条状的"没更新"。
     * 上下文是我们自己的，但**不能依赖"上一个 pass 应该恢复了状态"这种隐含约定**。
     */
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glClearColor(0.f, 0.f, 0.f, videoLayerPass ? 1.f : 0.f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(c.program);
    const Mat4 id = identityMat();
    glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, id.m);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
    glUniform1i(c.texLoc, 0);
    glUniform1i(c.useTexLoc, 1);
    if (c.circleLoc >= 0) glUniform1i(c.circleLoc, 0);
    if (c.expandLoc >= 0) glUniform1i(c.expandLoc, 0);
    if (c.downLoc >= 0) glUniform1i(c.downLoc, 0);   // 这一层不缩，不做降采样
    glUniform4f(c.colorLoc, 1.f, 1.f, 1.f, 1.f);
    /* 固定测试图只在"视频层"这一路开（2026-10-09 诊断开关） */
    if (c.testPatternLoc >= 0) {
        glUniform1i(c.testPatternLoc, (videoLayerPass && gTestPattern.load() != 0) ? 1 : 0);
    }
    /* 画面调整值与主画面保持一致；no_postfx 时全部回中性（排除画质分支） */
    const bool fxOff = gNoPostfx.load() != 0;
    if (c.brightLoc >= 0) glUniform1f(c.brightLoc, fxOff ? 0.f : c.brightness.load());
    if (c.contrastLoc >= 0) glUniform1f(c.contrastLoc, fxOff ? 1.f : c.contrast.load());
    if (c.satLoc >= 0) glUniform1f(c.satLoc, fxOff ? 1.f : c.saturation.load());
    if (c.sharpenLoc >= 0) glUniform1f(c.sharpenLoc, fxOff ? 0.f : c.sharpen.load());
    if (c.tempLoc >= 0) glUniform1f(c.tempLoc, fxOff ? 0.f : c.temperature.load());
    if (c.texelLoc >= 0) glUniform2f(c.texelLoc, c.texelX.load(), c.texelY.load());
    if (c.jitterLoc >= 0) {
        if (gNoJitter.load() != 0) {
            glUniform1f(c.jitterLoc, 0.f);
        } else {
            static uint32_t layerJitter = 0;
            glUniform1f(c.jitterLoc, (float) (layerJitter++ % 64u) * 1.7f);
        }
    }

    glBindVertexArray(c.videoLayerVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);

    /*
     * 片名 logo 叠在视频之上（同一张合成图里）—— 父亲定的：logo 与视频一层。
     * 弹幕则改走独立合成层（见渲染循环），这里只在弹幕层建不起来时兜底，
     * 免得弹幕整个消失。
     */
    if (videoLayerPass && (gLayerMask.load() & 2) != 0 && !c.danmakuLayer.submitted &&
        c.danmakuVisible.load() && c.danmakuTex != 0 && c.danmakuHasFrame.load()) {
        drawOverlayIntoVideoLayer(c, c.danmakuTex, 0.f, 0.f, 1.f, 1.f);
    }
    /*
     * 视频层里的 logo 叠加已停用（父亲 2026-10-07 01:49 定稿）：
     * logo 改画到弹幕层画布的左上角（DanmakuSurfacePainter 的 logoBitmapProvider），
     * 且最后画、压在弹幕上面 —— 视频/弹幕/字幕/logo 各归各位，不重叠。
     */
    if (false && videoLayerPass && c.logoVisible.load() && c.logoTex != 0 && c.logoHasFrame.load()) {
        /*
         * 银幕左上角：宽 7.3%、距左 2.5%、距顶 2.8%，比例与电视版一致。
         *
         * 宽高比只按 logo 面板自身算（512×220）—— 原来多乘了一次画面比例，
         * 高度被拉大 2.4 倍、图纵向拉长（父亲 2026-10-06 晚日志实测发现）。
         */
        const float lwFrac = 0.073f;
        const float lhFrac = lwFrac * (220.f / 512.f);
        const float lcx = -1.f + 2.f * 0.025f + lwFrac * 0.5f;
        const float lcy = 1.f - 2.f * 0.028f - lhFrac * 0.5f;
        static int logoDrawLogTick = 0;
        if ((logoDrawLogTick++ % 90) == 0) {
            LOGI("logo 画进视频层：中心(%.3f, %.3f) 尺寸(%.3f, %.3f) 目标缓冲 %dx%d",
                 lcx, lcy, lwFrac, lhFrac, L.width, L.height);
        }
        drawOverlayIntoVideoLayer(c, c.logoTex, lcx, lcy, lwFrac, lhFrac);
    }

    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    /*
     * 交回图像前必须把 GL 命令推进命令流（父亲 2026-10-08 报「整个场景横向黑纹」）。
     *
     * GL 命令是异步的：画进 FBO 之后如果直接 xrReleaseSwapchainImage，
     * 运行时有几率在命令还没执行完时就把这块图拿去合成 —— 表现就是整幅画面
     * 出现上下漂移的横向撕裂。OpenXR 用 GL 的标准做法就是 release 前 glFlush()。
     */
    if (gSyncMode.load() == 1) {
        glFinish();
    } else {
        glFlush();
    }

    XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    if (XR_FAILED(api.ReleaseSwapchainImage(L.handle, &ri))) return false;
    L.index = idx;
    return true;
}

/**
 * 把视频层刷成纯黑并提交（换片清屏，父亲 2026-10-07 实测）。
 *
 * 为什么不能只"停提交"：PICO 的合成器在某个 quad layer 这一帧缺席时，会把
 * 上一次提交的内容留在屏幕上（防闪烁）—— 实测现象就是"换了片，银幕上还是上一部
 * 的画面，一直留到新片出画面"。所以等待期间这一层照旧提交，只是内容刷成黑的。
 */
bool fillVideoLayerBlack(VrContext &c, VideoLayerBuf &L) {
    if (!L.built || c.program == 0) return false;
    uint32_t idx = 0;
    if (!acquireWritableImage(c, L, &idx, true)) return false;
    glBindFramebuffer(GL_FRAMEBUFFER, L.fbos[idx]);
    glViewport(0, 0, L.width, L.height);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glClearColor(0.f, 0.f, 0.f, 1.f);   // 不透明黑
    glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    /*
     * 交回图像前必须把 GL 命令推进命令流（父亲 2026-10-08 报「整个场景横向黑纹」）。
     *
     * GL 命令是异步的：画进 FBO 之后如果直接 xrReleaseSwapchainImage，
     * 运行时有几率在命令还没执行完时就把这块图拿去合成 —— 表现就是整幅画面
     * 出现上下漂移的横向撕裂。OpenXR 用 GL 的标准做法就是 release 前 glFlush()。
     */
    if (gSyncMode.load() == 1) {
        glFinish();
    } else {
        glFlush();
    }

    XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    if (XR_FAILED(api.ReleaseSwapchainImage(L.handle, &ri))) return false;
    L.index = idx;
    return true;
}

/**
 * 空屏层：未播放时正前方那块暗色银幕（2026-10-09）。
 *
 * 就是 fillVideoLayerBlack 的语义 —— 刷成不透明纯黑再提交。纯黑对色彩空间
 * 不敏感（0 在 sRGB 与线性下都是 0），所以不必担心交换链格式带来的色差。
 * 单独包一层是为了让日志与跳过计数指向「空屏」，不和视频层混在一起。
 */
bool renderBlankScreenLayer(VrContext &c, VideoLayerBuf &L) {
    return fillVideoLayerBlack(c, L);
}

void frameLoop(VrContext &c) {
    /*
     * 图层结构（run 95 实机崩溃后按官方示例重写）：
     *
     * 崩溃栈显示 #02/#04 落在 libb0bvr.so、#01/#00 落在 XRRuntime，
     * Cause: null pointer dereference。
     * 原来的写法把 projViews / layer 建在进入循环之前，并且用
     * `reinterpret_cast<const XrCompositionLayerBaseHeader *const *>(&layer)`
     * 取单元素数组地址 —— 这个取址方式与运行时对图层数组的读取方式不一致，
     * 运行时拿到无效指针就崩在它自己的图层处理里。
     *
     * 现在按官方示例（hello_xr）的写法：
     *  - 每帧把视图结构与图层结构都重置为带 type 的干净值；
     *  - 用真正的指针数组 layerPtrs 交给 xrEndFrame；
     *  - 只在 LocateViews 真的成功、且每只眼都取到图时才提交图层。
     */
    std::vector<XrView> views(c.viewConfigs.size(), {XR_TYPE_VIEW});
    std::vector<XrCompositionLayerProjectionView> projViews(c.viewConfigs.size());
    for (auto &pv : projViews) pv = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};

    /* 影厅图层（最底下那层）的视图数组 */
    std::vector<XrCompositionLayerProjectionView> cinemaViews(c.viewConfigs.size());
    for (auto &cv : cinemaViews) cv = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
    XrCompositionLayerProjection cinemaProj{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    uint32_t cinemaViewCount = 0;
    bool cinemaLayerOk = false;

    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    layer.space = c.localSpace;
    layer.viewCount = (uint32_t) projViews.size();
    layer.views = projViews.data();
    /*
     * 投影层按源透明度混合：视频改走独立图层后，我们这层在银幕位置是透明的，
     * 不声明这个标志的话运行时会把透明区当黑色，视频就被一块黑板盖住了。
     */
    layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;

    /*
     * 视频独立合成层（父亲 2026-10-06 定的方向）：
     * 视频以原始分辨率交给系统合成器，缩放到面板那一步由它做，不再经过我们的画面缓冲。
     * 位置与朝向跟我们的银幕完全一致，否则画面会和边框、控制条错位。
     */
    XrCompositionLayerQuad videoQuad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    videoQuad.space = c.localSpace;
    videoQuad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    videoQuad.layerFlags = 0;

    /*
     * 弹幕独立合成层（父亲 2026-10-06 晚定）：摆在银幕前方一点点，与视频层
     * 分开。提交顺序上放在视频之后，保证叠在画面之上；就算顺序反了，它离眼睛
     * 更近，深度上也压过去。
     */
    XrCompositionLayerQuad danmakuQuad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    danmakuQuad.space = c.localSpace;
    danmakuQuad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    // 按源透明度混合：不声明的话运行时会把这层当不透明黑板，视频被盖住
    danmakuQuad.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;

    /*
     * 海报墙独立合成层（2026-10-09）：不透明（底色由 Compose 画好），提交顺序
     * 放在视频层之后、弹幕层之前（海报墙是左侧独立屏幕，弹幕压在前方视频上）。
     */
    XrCompositionLayerQuad panelQuad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    panelQuad.space = c.localSpace;
    panelQuad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    panelQuad.layerFlags = 0;

    /*
     * 空屏层（2026-10-09）：未播放时正前方那块暗色银幕，不透明 quad。
     * 排在视频层的位置（海报墙之下）。
     */
    XrCompositionLayerQuad blankQuad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    blankQuad.space = c.localSpace;
    blankQuad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    blankQuad.layerFlags = 0;

    /*
     * 控制条 / 菜单独立层（2026-10-09）：都是透明面板（圆角外、菜单卡片外透明），
     * 必须声明按源 alpha 混合，否则运行时会当成不透明黑板。
     */
    XrCompositionLayerQuad osdQuad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    osdQuad.space = c.localSpace;
    osdQuad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    osdQuad.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;

    XrCompositionLayerQuad menuQuad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    menuQuad.space = c.localSpace;
    menuQuad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    menuQuad.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;

    const XrCompositionLayerBaseHeader *layerPtrs[8] = {nullptr, nullptr, nullptr, nullptr,
                                                        nullptr, nullptr, nullptr};

    int loggedFrames = 0;
    /*
     * 帧时间统计（父亲 2026-10-08：「头一转动场景就抖」）。
     *
     * 抖大概率是渲染没跟上 90Hz 的刷新（11ms 一帧）：渲染超时的帧交给系统
     * 做预测投影，静止时看不出，头一转动就抖。
     * 每 3 秒打一行：渲染耗时（BeginFrame 之后到 EndFrame）的平均 / 最大，
     * 以及本周期内有多少帧超过 11ms。
     */
    double statAccumMs = 0.0;
    int statFrames = 0;
    double statMaxMs = 0.0;
    int statOver = 0;
    auto statLast = std::chrono::steady_clock::now();
    /*
     * 分阶段统计（父亲 2026-10-08 晚）：只知道"帧只有 50~62"不够，
     * 要分清时间花在视频层（取帧 + 绘制 + 等交换链）、界面层、双眼渲染，
     * 还是收尾（图层组装 + xrEndFrame 等合成器）。各自留平均与峰值。
     */
    double statVideoMs = 0.0, statVideoMax = 0.0;
    double statUiMs = 0.0, statUiMax = 0.0;
    double statEyesMs = 0.0, statEyesMax = 0.0;
    double statEndMs = 0.0, statEndMax = 0.0;
    double statPeriodMs = 0.0, statPeriodMax = 0.0;
    auto statPrevFrameStart = std::chrono::steady_clock::now();
    while (!gRequestStop) {
        pumpEvents(c);
        if (gRequestStop) break;

        if (!c.sessionRunning) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        XrFrameWaitInfo fwi{XR_TYPE_FRAME_WAIT_INFO};
        XrFrameState fs{XR_TYPE_FRAME_STATE};
        /*
         * 帧调度诊断（2026-10-09，ChatGPT 复查建议）：
         * 实测帧周期在 11.5~50.2ms 之间乱跳，但我们自己每帧只渲染 0.9ms。
         * 光看 xrBeginFrame 的调用间隔不足以判断"是运行时在节流，还是我们在别处阻塞"——
         * 必须记录 xrWaitFrame 的耗时、运行时给的 predictedDisplayPeriod，
         * 以及相邻 predictedDisplayTime 之差。三者一比就能分开：
         *   · predictedDisplayPeriod ≈ 11.11ms 且 waitFrame 很快 → 运行时节奏正常，帧间隔变大是我们自己的问题
         *   · predictedDisplayPeriod 本身就变大 → 运行时（合成器）在降速，属于外部节流
         */
        const auto waitFrameT0 = std::chrono::steady_clock::now();
        if (XR_FAILED(api.WaitFrame(c.session, &fwi, &fs))) {
            LOGE("xrWaitFrame 失败，退出循环");
            break;
        }
        const double waitFrameMs = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - waitFrameT0).count();
        {
            static XrTime prevPredicted = 0;
            const double periodMs = (double) fs.predictedDisplayPeriod / 1000000.0;
            const double deltaMs = prevPredicted ? (double) (fs.predictedDisplayTime - prevPredicted) / 1000000.0 : 0.0;
            prevPredicted = fs.predictedDisplayTime;
            static double accWait = 0.0, accPeriod = 0.0, accDelta = 0.0;
            static int accN = 0;
            static double lastLog = 0.0;
            accWait += waitFrameMs; accPeriod += periodMs; accDelta += deltaMs; accN++;
            if (nowMs() - lastLog > 3000.0 && accN > 0) {
                LOGI("帧调度：%d 帧｜xrWaitFrame 均 %.2fms｜预测周期均 %.2fms（应为 11.11）"
                     "｜预测显示时刻间隔均 %.2fms",
                     accN, accWait / accN, accPeriod / accN, accDelta / accN);
                accWait = accPeriod = accDelta = 0.0;
                accN = 0;
                lastLog = nowMs();
            }
        }
        XrFrameBeginInfo fbi{XR_TYPE_FRAME_BEGIN_INFO};
        api.BeginFrame(c.session, &fbi);
        const auto frameStart = std::chrono::steady_clock::now();
        auto tVideo = frameStart;
        auto tUi = frameStart;
        auto tEyes = frameStart;

        /*
         * 取面板新一帧。必须在渲染线程做（与面板纹理同一个 GL 上下文），
         * 且要在画之前 —— 否则贴上去的永远是上一帧。
         */
        if (c.panelActive.load() && gPanelUpdate != nullptr) {
            if (gFinishBeforeTexUpdate.load() != 0) glFinish();
            gPanelUpdate();
        }

        // 播放画面：同样必须在渲染线程取帧（与视频纹理同一个 GL 上下文）
        if (c.videoActive.load() && gVideoUpdate != nullptr) {
            /*
             * 关键（2026-10-09）：必须在 updateTexImage **之前**等 GPU 把上一帧的
             * 采样做完 —— updateTexImage 会把上一张缓冲还给生产者，还早了就会被改写。
             */
            if (gFinishBeforeTexUpdate.load() != 0) glFinish();
            gVideoUpdate();
        }

        /*
         * 视频独立层：每帧把刚取到的帧原样搬进它自己的缓冲。
         * 尺寸取视频真实分辨率（上限 4K），缩放交给系统合成器做 —— 这是清晰度的关键。
         * 建不起来（运行时不支持）就退回老路，功能不受影响。
         */
        c.videoLayer.submitted = false;
        /*
         * 注意（2026-10-07 回退）：这里**不能**在"没有帧"时改走别的绘制路径。
         * 之前试过"照旧提交、把内容刷黑"来清屏，结果视频层提交直接失效
         * （日志里 `视频层=0`），于是投影层盖在弹幕层之上 —— 弹幕和片名 logo
         * 全被压到画面背后。清屏另想办法，这一段的路径保持原样。
         */
        if ((gLayerMask.load() & 1) != 0 && c.videoLayerOk && c.videoActive.load() &&
            c.videoHasFrame.load() && c.videoTex != 0) {
            int32_t vw = (int32_t) lroundf(1.f / fmaxf(1e-6f, c.texelX.load()));
            int32_t vh = (int32_t) lroundf(1.f / fmaxf(1e-6f, c.texelY.load()));
            constexpr int32_t kMaxVideoW = 3840;
            constexpr int32_t kMaxVideoH = 2160;
            int32_t capW = kMaxVideoW;
            /* video_layer_max_w：把视频层交换链压到指定宽度（0 = 按视频原分辨率）。
             * 用来验证"4K 大图层是否本身就是问题"（2026-10-09 诊断开关）。 */
            const int32_t userCap = gVideoLayerMaxW.load();
            if (userCap > 0) capW = userCap;
            if (vw > capW || vh > kMaxVideoH) {
                const float k = fminf((float) capW / (float) vw,
                                      (float) kMaxVideoH / (float) vh);
                vw = (int32_t) ((float) vw * k);
                vh = (int32_t) ((float) vh * k);
            }
            if (vw >= 64 && vh >= 64 && buildQuadLayer(c, c.videoLayer, vw, vh, "视频层")) {
                c.videoLayer.submitted = renderQuadLayer(c, c.videoLayer, c.videoTex, true);
            }
        }

        /*
         * 空屏层（2026-10-09，2026-10-09 晚扩条件）。
         *
         * 原来只在「海报墙正走独立层」时提交 —— 那天修的是"暗色银幕压住海报墙"。
         * 父亲当天又报同源的第二例：「屏幕黑时展开控制条菜单，菜单被黑屏盖住」。
         * 根子一模一样：投影层（眼缓冲）永远是最后一个提交 = 最上面，
         * 而 renderEye 在"没视频、也不在等待期"时会把那块不透明暗色银幕画进投影层 ——
         * 它是 26 米宽的大 quad，屏幕空间上正好压住近场那排控制条与菜单。
         *
         * 所以条件不再绑定海报墙：只要"没视频、也不在等待期"，空屏一律走独立层
         * （独立层排在视频层的位置 = 最下面），投影层那条分支自然留空。
         * 层次最终为：空屏 < 弹幕 < 海报墙 < 控制条 < 菜单 < 光柱。
         */
        c.blankLayer.submitted = false;
        if (c.blankLayerOk &&
            !c.videoLayer.submitted && !c.spinnerWanted.load()) {
            if (buildQuadLayer(c, c.blankLayer, 64, 64, "空屏")) {
                c.blankLayer.submitted = renderBlankScreenLayer(c, c.blankLayer);
            }
        }

        /*
         * 弹幕独立层：与视频层同一套机制，尺寸取弹幕面板的像素尺寸。
         * 先渲染它，视频层才知道要不要兜底把弹幕画回自己身上。
         */
        c.danmakuLayer.submitted = false;
        tVideo = std::chrono::steady_clock::now();

        if ((gLayerMask.load() & 2) != 0 && c.danmakuLayerOk && c.danmakuVisible.load() &&
            c.danmakuHasFrame.load() &&
            c.danmakuTex != 0) {
            static int danmakuLogTick = 0;
            if ((danmakuLogTick++ % 180) == 0) {
                LOGI("弹幕层：有帧=%d 开关=%d 已建=%d",
                     c.danmakuHasFrame.load() ? 1 : 0, c.danmakuVisible.load() ? 1 : 0,
                     c.danmakuLayer.built ? 1 : 0);
            }
            // 画布尺寸随影片比例（Java 推来，见 nativeSetDanmakuCanvas）；
            // buildQuadLayer 自带「尺寸变了就重建」，所以这里只读数值。
            const int32_t dmPxW = c.danmakuPxW.load();
            const int32_t dmPxH = c.danmakuPxH.load();
            if (buildQuadLayer(c, c.danmakuLayer, dmPxW, dmPxH, "弹幕层")) {
                c.danmakuLayer.submitted =
                        renderQuadLayer(c, c.danmakuLayer, c.danmakuTex, false);
            }
        }

        // 片名 logo：每秒报一次状态，定位"没画在视频上"（父亲 2026-10-06 晚）
        static int logoLogTick = 0;
        if ((logoLogTick++ % 90) == 0) {
            LOGI("片名 logo 状态 → 层可用=%d 开关=%d 有帧=%d 纹理=%u 视频层=%d 视频帧=%d",
                 c.logoTex != 0 ? 1 : 0, c.logoVisible.load() ? 1 : 0,
                 c.logoHasFrame.load() ? 1 : 0, (unsigned) c.logoTex,
                 c.videoLayer.submitted ? 1 : 0, c.videoHasFrame.load() ? 1 : 0);
        }

        // 控制条：近场小面板，每帧取一次（与面板/视频同一套 SurfaceTexture 机制）
        if (c.osdVisible.load() && gOsdUpdate != nullptr) {
            if (gFinishBeforeTexUpdate.load() != 0) glFinish();
            gOsdUpdate();
        }

        // 展开菜单：架在控制条正上方的透明面板，同样每帧取一次
        if (c.menuVisible.load() && gMenuUpdate != nullptr) {
            if (gFinishBeforeTexUpdate.load() != 0) glFinish();
            gMenuUpdate();
        }
        if (c.danmakuVisible.load() && gDanmakuUpdate != nullptr) {
            if (gFinishBeforeTexUpdate.load() != 0) glFinish();
            gDanmakuUpdate();
        }
        if ((gLayerMask.load() & 4) != 0 && c.logoVisible.load() && gLogoUpdate != nullptr) {
            gLogoUpdate();
        }

        /*
         * 海报墙独立合成层渲染（2026-10-09）：纹理在 renderEye 之前 updateTexImage
         * 过了，这里按纹理原始像素（1920×1080）拷进自己的交换链，交给系统合成器。
         * 摆位跟随父在运行时拖动的海报墙位置（panelPlacement）。
         * 海报墙是 UI 面板，不走 layer_mask 门控。
         */
        c.panelLayer.submitted = false;
        if (c.panelLayerOk && (gLayerMask.load() & 8) != 0 && c.panelActive.load() &&
            c.panelShown.load() && c.panelHasFrame.load() && c.panelTex != 0) {
            if (buildQuadLayer(c, c.panelLayer, 1920, 1080, "海报墙")) {
                c.panelLayer.submitted = renderQuadLayer(c, c.panelLayer, c.panelTex, false);
            }
        }

        /*
         * 控制条 / 菜单独立合成层（2026-10-09 第二次实做）。
         *
         * 走弹幕层那条**已验证**的拷贝路径（renderQuadLayer 的 videoLayerPass=false）：
         * 清成透明 → 关混合 → 原样拷贝，alpha 原样带过去，混合交给系统合成器。
         * 上次失败就是因为拷贝时开了混合，纹理 alpha 若是 0 会被混成全透明。
         *
         * 纹理刚在上面 updateTexImage 过，这里按面板原始像素尺寸建层。
         * 层建不起来或提交失败时 submitted 保持 false，renderEye 会自动退回绘制。
         */
        c.osdLayer.submitted = false;
        if (c.osdLayerOk && (gLayerMask.load() & 16) != 0 && c.osdVisible.load() &&
            c.osdHasFrame.load() && c.osdTex != 0) {
            if (buildQuadLayer(c, c.osdLayer, (int32_t) kOsdPxW, (int32_t) kOsdPxH, "控制条")) {
                c.osdLayer.submitted = renderQuadLayer(c, c.osdLayer, c.osdTex, false);
            }
        }
        c.menuLayer.submitted = false;
        if (c.menuLayerOk && (gLayerMask.load() & 32) != 0 && c.menuVisible.load() &&
            c.menuHasFrame.load() && c.menuTex != 0) {
            if (buildQuadLayer(c, c.menuLayer, (int32_t) kMenuPxW, (int32_t) kMenuPxH, "菜单")) {
                c.menuLayer.submitted = renderQuadLayer(c, c.menuLayer, c.menuTex, false);
            }
        }

        // 手柄状态（诊断阶段：变化即打日志，先看清 PICO 到底发哪些事件）
        c.frameDisplayTime = fs.predictedDisplayTime;
        tUi = std::chrono::steady_clock::now();
        syncInput(c);
        pushInput(c);   // 光柱指向 / 扳机 / 摇杆 / B 键 → Java（VR 模式的输入通道）
        if (c.aimValid[0] || c.aimValid[1]) {
            static int aimLog = 0;
            if (aimLog < 6) {
                const XrPosef &p0 = c.aimValid[0] ? c.aimPose[0] : c.aimPose[1];
                LOGI("手柄指向可用：pos=(%.2f, %.2f, %.2f) 朝向=(%.2f, %.2f, %.2f, %.2f)",
                     p0.position.x, p0.position.y, p0.position.z,
                     p0.orientation.x, p0.orientation.y, p0.orientation.z, p0.orientation.w);
                aimLog++;
            }
        }

        bool rendered = false;
        /*
         * 眼缓冲也一并跳过（2026-10-09 诊断）：`hide_projection=1` 时既然不提交投影层，
         * 就不必渲染它。目的：把我们自己的 GPU 负载压到接近零，用来验证
         * 「GPU 每帧超支 → 每帧迟到 → 系统变形补偿 → 画面横条」这条机制。
         * 副作用：光柱/光标消失（它们在投影层里），属预期。
         */
        const bool skipEyes = (gHideProjection.load() != 0);
        if (fs.shouldRender && c.sessionRunning && skipEyes) {
            rendered = true;   // 仍有 quad 层要提交
        } else if (fs.shouldRender && c.sessionRunning) {
            XrViewLocateInfo vli{XR_TYPE_VIEW_LOCATE_INFO};
            vli.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            vli.displayTime = fs.predictedDisplayTime;
            vli.space = c.localSpace;
            XrViewState vs{XR_TYPE_VIEW_STATE};
            uint32_t viewCount = 0;
            const XrResult lr = api.LocateViews(
                    c.session, &vli, &vs, (uint32_t) views.size(), &viewCount, views.data());
            /*
             * 必须确认视图状态有效（XR_VIEW_STATE_ORIENTATION_VALID_BIT /
             * POSITION_VALID_BIT），否则 pose 里的数据可能是垃圾，
             * 拿它去算 MVP 会得到 NaN 矩阵。
             */
            const bool viewsValid =
                    XR_SUCCEEDED(lr) && viewCount == c.viewConfigs.size() &&
                    (vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) != 0 &&
                    (vs.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) != 0;
            if (viewsValid) {
                /* 观影位锚点 + 防穿越（2026-10-10）：在渲染之前算好，所有图层用同一组值 */
                updateViewerAnchor(views[0].pose);
                bool eyesOk = true;
                for (uint32_t i = 0; i < viewCount; i++) {
                    if (!renderEye(c, (int) i, views[i])) {
                        eyesOk = false;
                        break;
                    }
                    projViews[i].pose = views[i].pose;
                    projViews[i].fov = views[i].fov;
                    projViews[i].subImage.swapchain = c.eyes[i].handle;
                    projViews[i].subImage.imageRect.offset = {0, 0};
                    projViews[i].subImage.imageRect.extent = {c.eyes[i].width, c.eyes[i].height};
                }
                /*
                 * 影厅图层（最底下那层）：单独渲染，与上面那层互不遮挡（父亲 2026-10-10）。
                 * 失败就整帧不提交这一层，不影响别的图层。
                 */
                cinemaLayerOk = false;
                if (eyesOk && c.cinemaEyesOk && gCinemaReady.load() && gCinemaOn.load() != 0) {
                    bool cinemaOk = true;
                    for (uint32_t i = 0; i < viewCount; i++) {
                        if (!renderCinemaEye(c, (int) i, views[i])) {
                            cinemaOk = false;
                            break;
                        }
                        cinemaViews[i].pose = views[i].pose;
                        cinemaViews[i].fov = views[i].fov;
                        cinemaViews[i].subImage.swapchain = c.cinemaEyes[i].handle;
                        cinemaViews[i].subImage.imageRect.offset = {0, 0};
                        cinemaViews[i].subImage.imageRect.extent = {c.cinemaEyes[i].width,
                                                                    c.cinemaEyes[i].height};
                    }
                    cinemaLayerOk = cinemaOk;
                    cinemaViewCount = viewCount;
                }
                tEyes = std::chrono::steady_clock::now();
                rendered = eyesOk;
                if (rendered && loggedFrames < 3) {
                    LOGI("已渲染第 %d 帧（%u 眼）", loggedFrames + 1, viewCount);
                    loggedFrames++;
                }
            } else if (loggedFrames < 3) {
                LOGI("本帧跳过：LocateViews=%d 视图数=%u 状态位=0x%x",
                     (int) lr, viewCount, (unsigned) vs.viewStateFlags);
            }
        }

        XrFrameEndInfo fei{XR_TYPE_FRAME_END_INFO};
        fei.displayTime = fs.predictedDisplayTime;
        fei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;

        /*
         * 图层顺序：视频层在下，我们的界面层在上 —— 控制条、转圈要压在画面上。
         * 视频层没有内容（没开播、还没出帧、或运行时拒绝）时只提交界面层。
         */
        uint32_t layerCount = 0;
        /*
         * 影厅第一个提交 = 最底下那层（OpenXR 按数组顺序合成，先提交的在下）。
         * 上面才是视频、弹幕、海报墙、控制条、菜单，最后是画光柱的那层投影层。
         */
        if (rendered && cinemaLayerOk && cinemaViewCount == c.viewConfigs.size()) {
            cinemaProj.space = c.localSpace;
            cinemaProj.viewCount = cinemaViewCount;
            cinemaProj.views = cinemaViews.data();
            cinemaProj.layerFlags = 0;   // 不透明：影厅是背景
            layerPtrs[layerCount++] =
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&cinemaProj);
        }
        if (rendered && c.videoLayer.submitted) {
            const ScreenPlacement sp = frontScreen(c);
            const float hy = sp.yawDeg * 3.14159265358979f / 360.f;
            const float hp = sp.pitchDeg * 3.14159265358979f / 360.f;
            const float sy2 = sinf(hy), cy2 = cosf(hy);
            const float sp2 = sinf(hp), cp2 = cosf(hp);
            videoQuad.pose.position = {sp.cx, sp.cy, sp.cz};
            videoQuad.pose.orientation = {cy2 * sp2, sy2 * cp2, -sy2 * sp2, cy2 * cp2};
            videoQuad.size = {sp.width, sp.width / fmaxf(0.1f, sp.aspect)};
            videoQuad.subImage.swapchain = c.videoLayer.handle;
            videoQuad.subImage.imageRect.offset = {0, 0};
            videoQuad.subImage.imageRect.extent = {c.videoLayer.width, c.videoLayer.height};
            videoQuad.subImage.imageArrayIndex = 0;
            layerPtrs[layerCount++] =
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&videoQuad);
        }
        /*
         * 空屏层提交（2026-10-09）：与视频层同一个位置与朝向，未播放时顶上。
         * 二者互斥（有视频就不提交空屏）。
         */
        if (rendered && c.blankLayer.submitted) {
            const ScreenPlacement sp = frontScreen(c);
            const float hy = sp.yawDeg * 3.14159265358979f / 360.f;
            const float hp = sp.pitchDeg * 3.14159265358979f / 360.f;
            const float sy2 = sinf(hy), cy2 = cosf(hy);
            const float sp2 = sinf(hp), cp2 = cosf(hp);
            blankQuad.pose.position = {sp.cx, sp.cy, sp.cz};
            blankQuad.pose.orientation = {cy2 * sp2, sy2 * cp2, -sy2 * sp2, cy2 * cp2};
            blankQuad.size = {sp.width, sp.width / fmaxf(0.1f, sp.aspect)};
            blankQuad.subImage.swapchain = c.blankLayer.handle;
            blankQuad.subImage.imageRect.offset = {0, 0};
            blankQuad.subImage.imageRect.extent = {c.blankLayer.width, c.blankLayer.height};
            blankQuad.subImage.imageArrayIndex = 0;
            layerPtrs[layerCount++] =
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&blankQuad);
        }
        /*
         * 海报墙独立合成层提交（2026-10-09 父亲实测修正层次）：
         * 摆位跟随运行时拖动（panelPlacement），不透明 quad。
         *
         * **必须放在弹幕层之后**：父亲实测——不播放时（黑屏/等待期）弹幕层是一块
         * 黑幕，它提交在最后就把海报墙整个盖住。海报墙原来画在眼缓冲（最顶层），
         * 层次本来就比视频/弹幕高，改独立层后要维持同样的高低关系。
         * 只留手柄光柱（投影层）压在它上面，点击才不会失灵。
         */
        static int danmakuSubmitLogTick = 0;
        if ((danmakuSubmitLogTick++ % 180) == 0) {
            LOGI("弹幕层提交：已提交=%d 缓冲=%dx%d 已建=%d",
                 c.danmakuLayer.submitted ? 1 : 0, c.danmakuLayer.width,
                 c.danmakuLayer.height, c.danmakuLayer.built ? 1 : 0);
        }
        if (rendered && c.danmakuLayer.submitted) {
            const ScreenPlacement sp = frontScreen(c);
            const float hy = sp.yawDeg * 3.14159265358979f / 360.f;
            const float hp = sp.pitchDeg * 3.14159265358979f / 360.f;
            const float sy2 = sinf(hy), cy2 = cosf(hy);
            const float sp2 = sinf(hp), cp2 = cosf(hp);
            danmakuQuad.pose.position = {sp.cx, sp.cy, sp.cz + kDanmakuNearer};
            danmakuQuad.pose.orientation = {cy2 * sp2, sy2 * cp2, -sy2 * sp2, cy2 * cp2};
            const float dw = sp.width * kDanmakuScale;
            danmakuQuad.size = {dw, dw / fmaxf(0.1f, sp.aspect)};
            danmakuQuad.subImage.swapchain = c.danmakuLayer.handle;
            danmakuQuad.subImage.imageRect.offset = {0, 0};
            danmakuQuad.subImage.imageRect.extent = {c.danmakuLayer.width,
                                                     c.danmakuLayer.height};
            danmakuQuad.subImage.imageArrayIndex = 0;
            layerPtrs[layerCount++] =
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&danmakuQuad);
        }
        /* 海报墙压在视频与弹幕之上（见上方注释），只让投影层的光柱盖过它 */
        if (rendered && c.panelLayer.submitted) {
            const ScreenPlacement sp = panelPlacement(c);
            const float hy = sp.yawDeg * 3.14159265358979f / 360.f;
            const float hp = sp.pitchDeg * 3.14159265358979f / 360.f;
            const float sy2 = sinf(hy), cy2 = cosf(hy);
            const float sp2 = sinf(hp), cp2 = cosf(hp);
            panelQuad.pose.position = {sp.cx, sp.cy, sp.cz};
            panelQuad.pose.orientation = {cy2 * sp2, sy2 * cp2, -sy2 * sp2, cy2 * cp2};
            panelQuad.size = {sp.width, sp.width / fmaxf(0.1f, sp.aspect)};
            panelQuad.subImage.swapchain = c.panelLayer.handle;
            panelQuad.subImage.imageRect.offset = {0, 0};
            panelQuad.subImage.imageRect.extent = {c.panelLayer.width,
                                                   c.panelLayer.height};
            panelQuad.subImage.imageArrayIndex = 0;
            layerPtrs[layerCount++] =
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&panelQuad);
        }
        /*
         * 控制条与菜单（2026-10-09）：都是近场面板（0.85m 一排），比海报墙更靠近
         * 观影者，所以排在海报墙之后提交（画面在上）。原型里它们画在投影层
         * （最上面、压过海报墙），这里维持同样的高低关系。
         * 光柱在投影层、始终最上，扣扳机点按钮才不会失灵。
         */
        if (rendered && c.osdLayer.submitted) {
            const float th = kOsdTiltDeg * 3.14159265358979f / 180.f;
            /* 控制条跟随观影位（父亲 2026-10-10：走近银幕时控制条留在伸手可及处） */
            osdQuad.pose.position = {viewerOffsetX(), kOsdCenterY,
                                     -kOsdDistance + viewerOffsetZ()};
            osdQuad.pose.orientation = {sinf(th * 0.5f), 0.f, 0.f, cosf(th * 0.5f)};
            osdQuad.size = {kOsdWidth, kOsdHeight};
            osdQuad.subImage.swapchain = c.osdLayer.handle;
            osdQuad.subImage.imageRect.offset = {0, 0};
            osdQuad.subImage.imageRect.extent = {c.osdLayer.width, c.osdLayer.height};
            osdQuad.subImage.imageArrayIndex = 0;
            layerPtrs[layerCount++] =
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&osdQuad);
        }
        if (rendered && c.menuLayer.submitted) {
            const float mth = kMenuTiltDeg * 3.14159265358979f / 180.f;
            float mcx, mcy, mcz, mnx, mny, mnz, mux, muy, muz, mvx, mvy, mvz;
            menuBasis(&mcx, &mcy, &mcz, &mnx, &mny, &mnz, &mux, &muy, &muz, &mvx, &mvy, &mvz);
            /* 菜单跟随观影位：位置改在 menuBasis 里统一做，这里原样提交即可 */
            menuQuad.pose.position = {mcx, mcy, mcz};
            menuQuad.pose.orientation = {sinf(mth * 0.5f), 0.f, 0.f, cosf(mth * 0.5f)};
            menuQuad.size = {kMenuWidth, kMenuHeight};
            menuQuad.subImage.swapchain = c.menuLayer.handle;
            menuQuad.subImage.imageRect.offset = {0, 0};
            menuQuad.subImage.imageRect.extent = {c.menuLayer.width, c.menuLayer.height};
            menuQuad.subImage.imageArrayIndex = 0;
            layerPtrs[layerCount++] =
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&menuQuad);
        }
        /* 投影层可关（2026-10-09 诊断开关）：验证"黑纹是否必须有投影层参与"。
         * 关掉后只剩 quad 层 —— 注意此时光柱/光标也会一起消失，属预期。 */
        if (rendered && gHideProjection.load() == 0) {
            layerPtrs[layerCount++] =
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&layer);
        }
        gLastLayerCount.store((int) layerCount);
        fei.layerCount = layerCount;
        fei.layers = layerCount > 0 ? layerPtrs : nullptr;
        const XrResult endRes = api.EndFrame(c.session, &fei);

        /* 帧时间统计（见循环上方的注释） */
        {
            const auto now = std::chrono::steady_clock::now();
            const double ms = std::chrono::duration<double, std::milli>(now - frameStart).count();
            const double videoMs =
                    std::chrono::duration<double, std::milli>(tVideo - frameStart).count();
            const double uiMs = std::chrono::duration<double, std::milli>(tUi - tVideo).count();
            const double eyesMs = std::chrono::duration<double, std::milli>(tEyes - tUi).count();
            const double endMs = std::chrono::duration<double, std::milli>(now - tEyes).count();
            const double periodMs =
                    std::chrono::duration<double, std::milli>(frameStart - statPrevFrameStart).count();
            statPrevFrameStart = frameStart;
            statAccumMs += ms;
            statMaxMs = fmax(statMaxMs, ms);
            statVideoMs += videoMs; statVideoMax = fmax(statVideoMax, videoMs);
            statUiMs += uiMs; statUiMax = fmax(statUiMax, uiMs);
            statEyesMs += eyesMs; statEyesMax = fmax(statEyesMax, eyesMs);
            statEndMs += endMs; statEndMax = fmax(statEndMax, endMs);
            statPeriodMs += periodMs; statPeriodMax = fmax(statPeriodMax, periodMs);
            statFrames++;
            if (ms > 11.0) statOver++;
            if (std::chrono::duration<double, std::milli>(now - statLast).count() > 3000.0 &&
                statFrames > 0) {
                const double n = (double) statFrames;
                LOGI("帧统计（均/峰 毫秒）：周期 %.1f/%.1f 视频 %.1f/%.1f 界面 %.1f/%.1f "
                     "双眼 %.1f/%.1f 收尾 %.1f/%.1f | 帧 %.1f/%.1f，%.0f 帧里 %d 帧超 11ms，"
                     "等交换链平均 %.2f｜当前参数 超采样 %.2f 超时 %dms 掩码 0x%x"
                     "｜跳过提交 眼 %d 视频层 %d 其他层 %d｜同步档 %d 刷新率档 %d"
                     "｜等图 成功 %d 超时 %d 眼超时 %d 错误 %d｜提交图层数 %d",
                     statPeriodMs / n, statPeriodMax,
                     statVideoMs / n, statVideoMax,
                     statUiMs / n, statUiMax,
                     statEyesMs / n, statEyesMax,
                     statEndMs / n, statEndMax,
                     statAccumMs / n, statMaxMs, n, statOver, gSwapWaitMs / n,
                     (double) gSuperSample.load(), gSwapWaitTimeoutMs.load(),
                     gLayerMask.load(),
                     gEyeSkipCount.load(), gVideoLayerSkipCount.load(),
                     gOtherLayerSkipCount.load(), gSyncMode.load(), gRefreshHz.load(),
                     gSwapWaitOk.load(), gSwapWaitTimeout.load(), gEyeWaitTimeout.load(),
                     gSwapWaitError.load(), gLastLayerCount.load());
                statAccumMs = 0.0;
                statMaxMs = 0.0;
                statVideoMs = statVideoMax = 0.0;
                statUiMs = statUiMax = 0.0;
                statEyesMs = statEyesMax = 0.0;
                statEndMs = statEndMax = 0.0;
                statPeriodMs = statPeriodMax = 0.0;
                statFrames = 0;
                statOver = 0;
                gSwapWaitMs = 0.0;
                gEyeSkipCount.store(0);
                gVideoLayerSkipCount.store(0);
                gOtherLayerSkipCount.store(0);
                gSwapWaitOk.store(0);
                gSwapWaitTimeout.store(0);
                gEyeWaitTimeout.store(0);
                gSwapWaitError.store(0);
                statLast = now;
            }
        }
        if (XR_FAILED(endRes) && c.videoLayer.submitted) {
            /*
             * 运行时不接受独立视频层：永久退回老路（画进我们自己的画面），
             * 否则每帧都会失败、画面直接黑掉。
             */
            LOGE("提交独立视频层失败（xrResult=%d），退回老路", (int) endRes);
            c.videoLayerOk = false;
            c.videoLayer.submitted = false;
        }
        if (XR_FAILED(endRes) && c.danmakuLayer.submitted) {
            LOGE("提交独立弹幕层失败（xrResult=%d），退回老路（画进视频层）", (int) endRes);
            c.danmakuLayerOk = false;
            c.danmakuLayer.submitted = false;
        }
        if (XR_FAILED(endRes) && c.panelLayer.submitted) {
            /*
             * 运行时不接受海报墙独立层：永久关掉它，renderEye 会自动退回
             * 「画进眼缓冲」的老路（判据是 panelLayer.built）。
             */
            LOGE("提交海报墙独立层失败（xrResult=%d），退回画进眼缓冲", (int) endRes);
            c.panelLayerOk = false;
            c.panelLayer.submitted = false;
            c.panelLayer.built = false;
        }
        if (XR_FAILED(endRes) && c.blankLayer.submitted) {
            /* 空屏层不被接受：永久关掉，暗色银幕自动退回画进眼缓冲的老路 */
            LOGE("提交空屏层失败（xrResult=%d），退回画进眼缓冲", (int) endRes);
            c.blankLayerOk = false;
            c.blankLayer.submitted = false;
        }
        if (XR_FAILED(endRes) && c.osdLayer.submitted) {
            /* 控制条层不被接受：永久关掉，自动退回画进眼缓冲（面板不会消失） */
            LOGE("提交控制条独立层失败（xrResult=%d），退回画进眼缓冲", (int) endRes);
            c.osdLayerOk = false;
            c.osdLayer.submitted = false;
        }
        if (XR_FAILED(endRes) && c.menuLayer.submitted) {
            LOGE("提交菜单独立层失败（xrResult=%d），退回画进眼缓冲", (int) endRes);
            c.menuLayerOk = false;
            c.menuLayer.submitted = false;
        }
    }
    LOGI("渲染循环结束");
}

void teardown(VrContext &c) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    for (auto &eye: c.eyes) {
        for (GLuint fbo: eye.fbos) if (fbo) glDeleteFramebuffers(1, &fbo);
        if (eye.handle != XR_NULL_HANDLE) api.DestroySwapchain(eye.handle);
    }
    c.eyes.clear();
    if (c.vbo) glDeleteBuffers(1, &c.vbo);
    if (c.rayVbo) glDeleteBuffers(1, &c.rayVbo);
    if (c.triVbo) glDeleteBuffers(1, &c.triVbo);
    if (c.program) glDeleteProgram(c.program);
    if (c.localSpace != XR_NULL_HANDLE) xrDestroySpace(c.localSpace);
    if (c.session != XR_NULL_HANDLE) api.DestroySession(c.session);
    if (c.instance != XR_NULL_HANDLE) xrDestroyInstance(c.instance);
    if (c.eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(c.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (c.eglSurface != EGL_NO_SURFACE) eglDestroySurface(c.eglDisplay, c.eglSurface);
        if (c.eglContext != EGL_NO_CONTEXT) eglDestroyContext(c.eglDisplay, c.eglContext);
        eglTerminate(c.eglDisplay);
    }
    c.eglDisplay = EGL_NO_DISPLAY;
    c.eglContext = EGL_NO_CONTEXT;
    c.eglSurface = EGL_NO_SURFACE;
    c.instance = XR_NULL_HANDLE;
    c.session = XR_NULL_HANDLE;
    c.localSpace = XR_NULL_HANDLE;
}

/** 建三张画面纹理（实现放在文件末尾，渲染线程启动时调用） */
bool createOesSources(VrContext &c);

void renderThreadMain() {
    VrContext &c = g;
    LOGI("VR 渲染线程启动");

    do {
        if (!initializeLoader(c)) break;
        if (!createInstance(c)) break;
        if (!initEgl(c)) break;

        XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
        sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        if (XR_FAILED(api.GetSystem(c.instance, &sgi, &c.systemId))) {
            LOGE("xrGetSystem 失败（设备没有 VR 运行时？）");
            break;
        }

        uint32_t count = 0;
        if (XR_FAILED(api.EnumerateViewConfigurationViews(
                c.instance, c.systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count,
                nullptr)) || count == 0) {
            LOGE("取不到视图配置");
            break;
        }
        c.viewConfigs.resize(count, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
        api.EnumerateViewConfigurationViews(c.instance, c.systemId,
                                            XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count,
                                            &count, c.viewConfigs.data());
        LOGI("视图数 = %u", count);

        if (!createSession(c)) break;
        if (!setupInput(c)) LOGW("手柄输入不可用，继续渲染（诊断阶段先保画面）");
        if (!createSwapchains(c)) break;

        /*
         * 三张画面纹理：**必须在 EGL 上下文就绪之后、本线程里建**（2026-10-05 晚修）。
         * 建好立刻推给 Java 侧的界面层（面板 / 播放画面 / 控制条）。
         */
        if (!createOesSources(c)) LOGW("画面纹理没建起来，VR 里只会看到底色");

        c.program = buildProgram();
        /*
         * 影厅环境（父亲 2026-10-10）：着色器 + 默认座位一起就位。
         * 几何本身由 Java 侧把 assets 里的 cinema.b0bcin 拷出来后再喊 nativeLoadCinema
         * （native 层读不到 assets，只有文件路径）。
         */
        gCinemaProgram = buildCinemaProgram();
        gCinemaMvpLoc = glGetUniformLocation(gCinemaProgram, "uMvp");
        gCinemaModelLoc = glGetUniformLocation(gCinemaProgram, "uModel");
        gCinemaScreenPosLoc = glGetUniformLocation(gCinemaProgram, "uScreenPos");
        gCinemaTintLoc = glGetUniformLocation(gCinemaProgram, "uScreenTint");
        gCinemaGlowLoc = glGetUniformLocation(gCinemaProgram, "uScreenGlow");
        gCinemaAmbientLoc = glGetUniformLocation(gCinemaProgram, "uAmbient");
        gCinemaEyeLoc = glGetUniformLocation(gCinemaProgram, "uEye");
        gCinemaEnvLoc = glGetUniformLocation(gCinemaProgram, "uEnv");
        gCinemaEnvStrengthLoc = glGetUniformLocation(gCinemaProgram, "uEnvStrength");
        applySeat(gSeat.load());
        /* 影厅几何 + 环境光贴图：路径由 Java 侧给（assets 已拷到应用目录） */
        if (!gEnvAssetPath.empty()) loadEnvAsset(gEnvAssetPath.c_str());
        if (!gCinemaAssetPath.empty() && loadCinemaAsset(gCinemaAssetPath.c_str())) {
            /*
             * 影厅单独占一层（0.75 倍分辨率：暗场细节看不太出来，省下的 GPU 给正片）。
             * 建失败就退回"画进主投影层"的老路（会糊住面板，但至少看得到影厅）。
             */
            createCinemaSwapchains(c, 0.75f);
        }
        c.mvpLoc = glGetUniformLocation(c.program, "uMvp");
        c.colorLoc = glGetUniformLocation(c.program, "uColor");
        c.useTexLoc = glGetUniformLocation(c.program, "uUseTexture");
        c.texLoc = glGetUniformLocation(c.program, "uTexture");
        c.circleLoc = glGetUniformLocation(c.program, "uCircle");
        c.expandLoc = glGetUniformLocation(c.program, "uExpandRange");
        c.brightLoc = glGetUniformLocation(c.program, "uBrightness");
        c.contrastLoc = glGetUniformLocation(c.program, "uContrast");
        c.satLoc = glGetUniformLocation(c.program, "uSaturation");
        c.sharpenLoc = glGetUniformLocation(c.program, "uSharpen");
        c.tempLoc = glGetUniformLocation(c.program, "uTemperature");
        c.texelLoc = glGetUniformLocation(c.program, "uTexel");
        c.screenStepLoc = glGetUniformLocation(c.program, "uScreenStep");
        c.downLoc = glGetUniformLocation(c.program, "uDownsample");
        c.jitterLoc = glGetUniformLocation(c.program, "uJitter");
        c.testPatternLoc = glGetUniformLocation(c.program, "uTestPattern");
        makeQuadBuffers(c);
        makeRayBuffer(c);
        makeTriBuffer(c);
        LOGI("GL 资源就绪（program=%u）", c.program);

        frameLoop(c);
    } while (false);

    teardown(c);
    // 建纹理时把渲染线程附加到了 JVM（之后每帧都要回调 Java），退出前摘掉
    if (c.jvm != nullptr) c.jvm->DetachCurrentThread();
    LOGI("VR 渲染线程结束");
}

/** 建一张 OES 外部纹理（用的是**当前** GL 上下文，所以必须在渲染线程调） */
GLuint genOesTexture() {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    if (tex == 0) return 0;
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    return tex;
}

/**
 * 把 SurfaceTexture 挂到这张纹理上，并装好「每帧取帧 + 首帧判定」。
 * 返回全局引用（调用方保存/释放）；失败返回 nullptr。
 */
jobject makeOesSurface(JNIEnv *env, GLuint tex, std::function<void()> &out,
                       std::atomic<bool> &hasFrame, const char *what,
                       bool notifyFirstFrame = false) {
    jclass stClass = env->FindClass("android/graphics/SurfaceTexture");
    if (stClass == nullptr) {
        LOGE("%s：找不到 SurfaceTexture 类", what);
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(stClass, "<init>", "(I)V");
    jmethodID updateTexImage = env->GetMethodID(stClass, "updateTexImage", "()V");
    jmethodID getTimestamp = env->GetMethodID(stClass, "getTimestamp", "()J");
    if (ctor == nullptr || updateTexImage == nullptr) {
        LOGE("%s：SurfaceTexture 方法缺失", what);
        return nullptr;
    }
    jobject local = env->NewObject(stClass, ctor, (jint) tex);
    if (local == nullptr) {
        LOGE("%s：SurfaceTexture 创建失败", what);
        return nullptr;
    }

    hasFrame = false;
    auto *globalRef = env->NewGlobalRef(local);
    env->DeleteLocalRef(local);   // 原生线程的局部引用不会自动回收，自己删掉
    out = [globalRef, updateTexImage, getTimestamp, &hasFrame, what, notifyFirstFrame]() {
        JNIEnv *e = nullptr;
        if (g.jvm == nullptr) return;
        if (g.jvm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) != JNI_OK ||
            e == nullptr) {
            return;
        }
        e->CallVoidMethod(globalRef, updateTexImage);
        clearJavaException(e, what);
        if (!hasFrame.load() && getTimestamp != nullptr) {
            const jlong ts = e->CallLongMethod(globalRef, getTimestamp);
            clearJavaException(e, "getTimestamp");
            /*
             * 只有**新的一帧**才算首帧（父亲 2026-10-07 实测）。
             *
             * 换片后 105 毫秒就报"首帧到位"，而那个 timestamp 与上一部完全相同 ——
             * 那是 ExoPlayer 释放后残留在 Surface 里的旧帧。一旦把它当成新片第一帧，
             * 黑幕 / 转圈 / 片名提示就会刚亮起就被收掉，肉眼等于"没出现"。
             */
            const bool staleFrame =
                    notifyFirstFrame && ts > 0 && ts == g.lastVideoFrameTs;
            if (ts > 0 && !staleFrame) {
                if (notifyFirstFrame) g.lastVideoFrameTs = ts;
                hasFrame = true;
                LOGI("%s首帧到位（timestamp=%lld）—— 可以画了", what, (long long) ts);
                /*
                 * 视频画面**第一次真正到纹理层**：通知 Java 侧可以收黑幕了。
                 *
                 * 换片等待期的黑幕 / 转圈必须等这个信号 —— ExoPlayer 自己报的
                 * onRenderedFirstFrame 会早那么一点点（帧还没被我们取进纹理），
                 * 黑幕一收就露出下一层里残留的上一部画面（父亲 2026-10-07：
                 * 「后面的旧图像和弹幕没被收走，只是被前面的盖住了」）。
                 */
                if (notifyFirstFrame && g.inputSink != nullptr &&
                    g.sinkVideoFrame != nullptr) {
                    e->CallVoidMethod(g.inputSink, g.sinkVideoFrame);
                    clearJavaException(e, "视频首帧回调");
                }
            }
        }
    };
    return globalRef;
}

/** 把已经建好的三张画面推给 Java 侧（注册回调时补推也走这里） */
void pushTexturesToJava(VrContext &c, JNIEnv *env) {
    if (env == nullptr || c.textureSink == nullptr || c.sinkTexture == nullptr) return;
    // 编号必须与 Java 侧 VrNative.TEXTURE_* 常量一致
    struct Item {
        jobject st;
        jint kind;
        const char *what;
    };
    const Item items[] = {
        {c.panelSt, 0, "面板"},
        {c.videoSt, 1, "播放画面"},
        {c.osdSt, 2, "控制条"},
        {c.menuSt, 3, "展开菜单"},
        {c.danmakuSt, 4, "弹幕层"},
        {c.logoSt, 5, "片名 logo"},
    };
    int pushed = 0;
    for (const Item &it : items) {
        if (it.st == nullptr) continue;
        env->CallVoidMethod(c.textureSink, c.sinkTexture, it.kind, it.st);
        clearJavaException(env, it.what);
        pushed++;
    }
    LOGI("画面纹理已推给界面层：%d 块（面板/播放画面/控制条/菜单/弹幕/片名 logo）", pushed);
}

/**
 * 在渲染线程（VR 自己的 EGL 上下文）里建三张画面纹理。
 *
 * 这是 2026-10-05 晚修的关键：纹理必须建在**用它的那个上下文**里。
 * 之前建在 Java 那条 GL 线程的上下文，VR 侧按同一个编号取到的是另一张纹理，
 * 三块屏因此互相串画面（控制条贴视频 / 播放屏贴控制条）。
 */
bool createOesSources(VrContext &c) {
    JNIEnv *env = nullptr;
    if (c.jvm == nullptr) return false;
    if (c.jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        if (c.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
            LOGE("建画面纹理：渲染线程附加 JVM 失败");
            return false;
        }
        LOGI("建画面纹理：渲染线程已附加到 JVM（不摘除，之后每帧要回调 Java）");
    }

    c.panelTex = genOesTexture();
    c.videoTex = genOesTexture();
    c.osdTex = genOesTexture();
    c.menuTex = genOesTexture();
    c.danmakuTex = genOesTexture();
    c.logoTex = genOesTexture();
    if (c.panelTex == 0 || c.videoTex == 0 || c.osdTex == 0 || c.menuTex == 0 ||
        c.danmakuTex == 0 || c.logoTex == 0) {
        LOGE("建画面纹理失败：panel=%u video=%u osd=%u menu=%u danmaku=%u logo=%u",
             c.panelTex, c.videoTex, c.osdTex, c.menuTex, c.danmakuTex, c.logoTex);
        return false;
    }

    c.panelSt = makeOesSurface(env, c.panelTex, gPanelUpdate, c.panelHasFrame, "面板");
    c.videoSt = makeOesSurface(env, c.videoTex, gVideoUpdate, c.videoHasFrame, "播放画面", true);
    c.osdSt = makeOesSurface(env, c.osdTex, gOsdUpdate, c.osdHasFrame, "控制条");
    c.menuSt = makeOesSurface(env, c.menuTex, gMenuUpdate, c.menuHasFrame, "展开菜单");
    c.danmakuSt = makeOesSurface(env, c.danmakuTex, gDanmakuUpdate, c.danmakuHasFrame, "弹幕");
    c.logoSt = makeOesSurface(env, c.logoTex, gLogoUpdate, c.logoHasFrame, "片名 logo");
    if (c.panelSt == nullptr || c.videoSt == nullptr || c.osdSt == nullptr ||
        c.menuSt == nullptr || c.danmakuSt == nullptr || c.logoSt == nullptr) {
        LOGE("画面纹理不完整，VR 贴图不可用");
        return false;
    }

    c.panelActive = false;
    c.videoActive = false;
    c.osdVisible = false;
    c.menuVisible = false;
    c.danmakuVisible = false;
    c.logoVisible = false;
    LOGI("六张画面纹理已在本渲染线程的上下文里创建：panel=%u video=%u osd=%u menu=%u "
         "danmaku=%u logo=%u",
         c.panelTex, c.videoTex, c.osdTex, c.menuTex, c.danmakuTex, c.logoTex);

    pushTexturesToJava(c, env);
    return true;
}

}  // namespace

// ---------------------------------------------------------------- JNI 入口
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeStartVr(JNIEnv *env, jobject /* this */,
                                                 jobject activity) {
    if (gRunning) {
        LOGI("VR 会话已在运行");
        return JNI_TRUE;
    }
    env->GetJavaVM(&g.jvm);
    g.activity = env->NewGlobalRef(activity);
    gRequestStop = false;
    gRunning = true;
    gThread = std::thread(renderThreadMain);
    return JNI_TRUE;
}

/** 界面开始往面板 Surface 上画了，可以贴纹理了 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetPanelActive(JNIEnv *env, jobject /* this */,
                                                       jboolean active) {
    g.panelActive = (active == JNI_TRUE);
    LOGI("面板激活状态 → %s", g.panelActive.load() ? "true" : "false");
}


/** 是否正在播放：true 时贴视频纹理、收起面板，false 时回到面板 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetVideoActive(JNIEnv *env, jobject /* this */,
                                                       jboolean active) {
    g.videoActive = (active == JNI_TRUE);
    /*
     * 不管开播还是停播，都把"出过帧"的标记清掉（父亲 2026-10-05 要求换片先清屏）：
     *  · 开播 → 等这一部自己的第一帧，上一部的旧帧不会先闪一下
     *  · 停播 → 银幕立刻清空，不留上一部的画面
     * 清掉之后到第一帧到位之间，银幕上显示转圈提示（见 drawSpinner）。
     */
    g.videoHasFrame = false;
    // 记下开播时刻：转圈延迟一拍才出现，看起来是"先清屏、再显示加载箭头"
    g.videoActiveAtMs = nowMs();
    /*
     * 播放画面时切 72Hz（学 PICO 自带播放器）：视频 24/25fps，72 是它的整数倍，
     * 画面不抖，而且每帧多出的时间可以换成更高的渲染分辨率。停播回 90Hz。
     */
    if (g.requestRefreshRate != nullptr && g.session != XR_NULL_HANDLE) {
        const int fixed = gRefreshHz.load();
        const float hz = fixed > 0 ? (float) fixed : (g.videoActive.load() ? 72.f : 90.f);
        const XrResult rr = g.requestRefreshRate(g.session, hz);
        LOGI("刷新率 → %.0fHz（结果 %d，档位 %d）", (double) hz, (int) rr, fixed);
    }
    LOGI("播放画面状态 → %s", g.videoActive.load() ? "true" : "false");
}

/**
 * 换片 / 首播等待期：让银幕转圈（父亲 2026-10-07）。
 *
 * 与 nativeSetVideoActive 是两条独立通道：换片时银幕已经清空（videoActive=false），
 * 这时候仍然要显示转圈，所以由 Java 侧显式开关这个标志。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetSpinnerWanted(JNIEnv *env, jobject /* this */,
                                                          jboolean wanted) {
    const bool w = (wanted == JNI_TRUE);
    if (w && !g.spinnerWanted.load()) {
        // 空一拍再出转圈：看起来是"先清屏、再显示加载箭头"（父亲 2026-10-06 定）
        g.videoActiveAtMs = nowMs();
    }
    g.spinnerWanted = w;
    LOGI("银幕转圈 → %s", w ? "等第一帧（转圈）" : "收起");
}


/** 控制条显示/隐藏（播放中扣扳机切换，由 Java 侧决定） */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetOsdVisible(JNIEnv *env, jobject /* this */,
                                                      jboolean visible) {
    g.osdVisible = (visible == JNI_TRUE);
    LOGI("控制条状态 → %s", g.osdVisible.load() ? "显示" : "隐藏");
}

/** 展开菜单显示/隐藏（控制条上的按钮点开菜单时由 Java 侧决定） */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetMenuVisible(JNIEnv *env, jobject /* this */,
                                                       jboolean visible) {
    g.menuVisible = (visible == JNI_TRUE);
    LOGI("展开菜单状态 → %s", g.menuVisible.load() ? "显示" : "隐藏");
}

/**
 * 菜单卡片实际占的那块矩形（归一化 0…1，左 / 上 / 右 / 下，相对整块菜单面板）。
 *
 * 界面层量好卡片位置后上报；光柱落在矩形外（透明区）时射线直接穿过去，
 * 继续打到后面的控制条 / 银幕上（父亲 2026-10-06）。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetMenuHitRect(JNIEnv * /* env */, jobject /* this */,
                                                       jfloat l, jfloat t, jfloat r, jfloat b) {
    g.menuHitL = l;
    g.menuHitT = t;
    g.menuHitR = r;
    g.menuHitB = b;
}

/** 弹幕层显隐（父亲 2026-10-06：弹幕要接进来） */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetDanmakuVisible(JNIEnv * /* env */, jobject /* this */,
                                                          jboolean visible) {
    g.danmakuVisible = (visible == JNI_TRUE);
    LOGI("弹幕层状态 → %s", g.danmakuVisible.load() ? "显示" : "隐藏");
}

/** 片名 logo 显隐（播放中出现，停止后收起） */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetLogoVisible(JNIEnv * /* env */, jobject /* this */,
                                                       jboolean visible) {
    g.logoVisible = (visible == JNI_TRUE);
    LOGI("片名 logo 状态 → %s", g.logoVisible.load() ? "显示" : "隐藏");
}

/** 海报墙显示/隐藏（控制条上的「选片」按钮切换，由 Java 侧决定） */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetPanelShown(JNIEnv *env, jobject /* this */,
                                                       jboolean shown) {
    g.panelShown = (shown == JNI_TRUE);
    LOGI("海报墙状态 → %s", g.panelShown.load() ? "摆出来" : "收起");
}

/*
 * 海报墙的摆放（位置 / 朝向 / 宽度）读写。
 *
 * 父亲 2026-10-06 晚：调好的海报墙位置要记住，下次打开 APP 还原到原处。
 * Java 侧退出时读一次存本地，启动时读回来。
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeGetPanelPlace(JNIEnv *env, jobject /* this */) {
    jfloatArray out = env->NewFloatArray(6);
    if (out == nullptr) return nullptr;
    const jfloat v[6] = {
        g.panelPosX.load(), g.panelPosY.load(), g.panelPosZ.load(),
        g.panelYawDeg.load(), g.panelPitchDeg.load(), g.panelWidth.load(),
    };
    env->SetFloatArrayRegion(out, 0, 6, v);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetPanelPlace(JNIEnv *env, jobject /* this */,
                                                       jfloat x, jfloat y, jfloat z,
                                                       jfloat yaw, jfloat pitch,
                                                       jfloat width) {
    g.panelPosX = x;
    g.panelPosY = y;
    g.panelPosZ = z;
    g.panelYawDeg = yaw;
    g.panelPitchDeg = pitch;
    g.panelWidth = fminf(kPanelMaxWidth, fmaxf(kPanelMinWidth, width));
    LOGI("海报墙：还原到 (%.2f, %.2f, %.2f) 朝向 %.0f°/%.0f° 宽 %.2f 米",
         (double) x, (double) y, (double) z, (double) yaw, (double) pitch, (double) width);
}

/**
 * 片子的宽高比变了 → 银幕高度跟着变。
 *
 * 父亲 2026-10-06：「有些片子长宽比不对」—— 银幕原来固定 16:9，2.35:1 或 4:3 的
 * 片子贴上去就被拉伸。ExoPlayer 报出真实尺寸后由 Java 侧推过来。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetVideoAspect(JNIEnv *env, jobject /* this */,
                                                        jfloat aspect) {
    if (aspect > 0.2f && aspect < 6.f) {
        g.videoAspect = aspect;
        LOGI("视频比例 → %.3f", (double) aspect);
    }
}

/**
 * 弹幕画布尺寸（Java 侧按影片比例算好推过来，父亲 2026-10-07）。
 *
 * 只改数值不碰 GL：buildQuadLayer 下一帧发现尺寸不同会自己销毁旧交换链重建，
 * 而 OES 纹理与 SurfaceTexture 都留着 —— Java 那边只管改缓冲尺寸继续画。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetDanmakuCanvas(JNIEnv * /* env */, jobject /* this */,
                                                         jint w, jint h) {
    if (w >= 256 && w <= 8192 && h >= 256 && h <= 8192) {
        g.danmakuPxW = (int32_t) w;
        g.danmakuPxH = (int32_t) h;
        LOGI("弹幕画布 → %dx%d", (int) w, (int) h);
    }
}

/**
 * 画面调整（父亲 2026-10-06：「画面太亮」→「调图像的功能都加上」→ 亮度/对比度/饱和度/锐度…）。
 *
 * 调好后把日志里最后那组数值抄成默认值，再把这些调节入口撤掉（父亲定的流程）。
 * 各项含义：亮度/对比度/饱和度 1.0 = 原样；锐度 0 = 不锐化；色温 -1 冷 … +1 暖。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetImageAdjust(JNIEnv *env, jobject /* this */,
                                                        jfloat brightness, jfloat contrast,
                                                        jfloat saturation, jfloat sharpen,
                                                        jfloat temperature) {
    if (brightness > 0.2f && brightness < 2.f) g.brightness.store(brightness);
    if (contrast > 0.2f && contrast < 2.f) g.contrast.store(contrast);
    if (saturation > 0.f && saturation < 2.f) g.saturation.store(saturation);
    if (sharpen >= 0.f && sharpen < 3.f) g.sharpen.store(sharpen);
    if (temperature > -1.f && temperature < 1.f) g.temperature.store(temperature);
    LOGI("画面调整 → 亮度 %.2f 对比度 %.2f 饱和度 %.2f 锐度 %.2f 色温 %+.2f",
         (double) g.brightness.load(), (double) g.contrast.load(),
         (double) g.saturation.load(), (double) g.sharpen.load(),
         (double) g.temperature.load());
}

/**
 * 运行时可调参数入口（父亲 2026-10-08）。
 *
 * key：1=超采样倍数 2=等交换链超时毫秒 3=层掩码（bit0 视频层 / bit1 弹幕层 / bit2 logo）
 * 参数由界面层从 vr-tuning.txt 读出后调用；见 VrTuning.kt。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetTuning(JNIEnv *env, jobject /* this */,
                                                   jint key, jfloat value) {
    (void) env;
    switch (key) {
        case 1:
            if (value >= 0.6f && value <= 2.0f) {
                gSuperSample.store(value);
                LOGI("调参 → 渲染超采样 %.2f 倍（下次起播生效）", (double) value);
            }
            break;
        case 2:
            if (value >= 0.f && value <= 50.f) {
                gSwapWaitTimeoutMs.store((int) value);
                LOGI("调参 → 等交换链超时 %d 毫秒", (int) value);
            }
            break;
        case 3:
            gLayerMask.store((int) value);
            /*
             * 层掩码扩展（2026-10-09，图层阶梯实验用）：现在是 6 位，
             * 每一位关掉一个图层 —— **层不提交、也不退回画进眼缓冲**（彻底不画），
             * 这样"减少合成图层数"的实验才是干净的对照。
             *   bit0 视频层 · bit1 弹幕层 · bit2 片名logo
             *   bit3 海报墙 · bit4 控制条 · bit5 菜单
             * 默认 0x3F 全开。投影层（眼缓冲）是必交层，无法关闭。
             */
            LOGI("调参 → 层掩码 0x%x（视频=%d 弹幕=%d logo=%d 海报墙=%d 控制条=%d 菜单=%d）",
                 (int) value,
                 (int) value & 1, ((int) value >> 1) & 1, ((int) value >> 2) & 1,
                 ((int) value >> 3) & 1, ((int) value >> 4) & 1, ((int) value >> 5) & 1);
            break;
        case 5: {
            const int hz = (int) value;
            gRefreshHz.store(hz);
            if (g.requestRefreshRate != nullptr && g.session != XR_NULL_HANDLE) {
                const float want = hz > 0 ? (float) hz
                                          : (g.videoActive.load() ? 72.f : 90.f);
                const XrResult rr = g.requestRefreshRate(g.session, want);
                LOGI("调参 → 刷新率档 %d，立即请求 %.0fHz（结果 %d）", hz, (double) want,
                     (int) rr);
            } else {
                LOGI("调参 → 刷新率档 %d（运行时未就绪，下次生效）", hz);
            }
            break;
        }
        case 4:
            gSyncMode.store((int) value);
            LOGI("调参 → 交回图像同步档 %d（0=glFlush 1=glFinish）", (int) value);
            break;
        /*
         * 6..12：黑纹诊断开关（2026-10-09 父亲要求"多给参数就能多做实验"）。
         * 每一档都对应 ChatGPT 复盘里一个判定性实验，改文件即生效、不用重编。
         */
        case 6:
            gHideProjection.store((int) value);
            LOGI("调参 → 投影层 %s（关掉后只剩 quad 层）",
                 (int) value ? "不提交" : "提交");
            break;
        case 7:
            gTestPattern.store((int) value);
            LOGI("调参 → 固定测试图 %s（切断 mpv/MediaCodec/SurfaceTexture 输入链）",
                 (int) value ? "开" : "关");
            break;
        case 8:
            gVideoLayerMaxW.store((int) value);
            LOGI("调参 → 视频层宽度上限 %d（0=按视频原分辨率；下次重建生效）", (int) value);
            break;
        case 9:
            gNoDownsample.store((int) value);
            LOGI("调参 → 视频降采样分支 %s", (int) value ? "关（只做单次采样）" : "开");
            break;
        case 10:
            gNoJitter.store((int) value);
            LOGI("调参 → 抖动 %s", (int) value ? "固定（排除逐帧变化输入）" : "逐帧变化");
            break;
        case 11:
            gForceRgba8.store((int) value);
            LOGI("调参 → 视频层交换链格式 %s（下次重建生效）",
                 (int) value ? "强制 GL_RGBA8" : "优先 sRGB");
            break;
        case 12:
            gNoPostfx.store((int) value);
            LOGI("调参 → 画质增强 %s", (int) value ? "全关" : "开");
            break;
        case 13:
            gFinishBeforeTexUpdate.store((int) value);
            LOGI("调参 → 取帧前先 glFinish %s（防外部纹理缓冲被提前回收）",
                 (int) value ? "开" : "关");
            break;
        case 14:
            if (value >= 1.f && value <= 40.f) {
                gScreenWidth.store(value);
                const float dist = gScreenDistance.load();
                LOGI("调参 → 银幕宽 %.2f 米（距离 %.2f 米，水平视角 %.1f°）",
                     (double) value, (double) dist,
                     (double) (2.0 * atan((value * 0.5) / dist) * 180.0 / 3.14159265358979));
            }
            break;
        case 15:
            if (value >= 1.f && value <= 40.f) {
                gScreenDistance.store(value);
                const float w = gScreenWidth.load();
                LOGI("调参 → 银幕距离 %.2f 米（宽 %.2f 米，水平视角 %.1f°）",
                     (double) value, (double) w,
                     (double) (2.0 * atan((w * 0.5) / value) * 180.0 / 3.14159265358979));
            }
            break;
        case 16:
            if (value >= 0.05f && value <= 3.f) {
                gRayScale.store(value);
                LOGI("调参 → 光柱粗细倍率 %.2f（1.0 = 银幕 3.2 米时代同样的角粗细）",
                     (double) value);
            }
            break;
        case 17:
            /* 选座（父亲 2026-10-10 17:35）：0 = 近排（第 1 排）· 1 = 中排（第 3 排）· 2 = 远排（第 6 排） */
            applySeat((int) value);
            break;
        case 20:
            /* 影厅环境开关（出问题时一键回黑背景，不用重装） */
            gCinemaOn.store(value >= 0.5f ? 1 : 0);
            LOGI("调参 → 影厅环境 %s", gCinemaOn.load() ? "开" : "关（只剩黑背景）");
            break;
        case 21:
            if (value >= 0.f && value <= 0.5f) {
                gCinemaAmbient.store(value);
                LOGI("调参 → 影厅底光 %.3f", (double) value);
            }
            break;
        case 22:
            /* 影院 HDRI 环境光强度（父亲 2026-10-10：灯光用下载的那套全景图） */
            if (value >= 0.f && value <= 12.f) {
                gEnvStrength.store(value);
                LOGI("调参 → 影院环境光强度 %.2f", (double) value);
            }
            break;
        case 24:
            /*
             * 影厅环境亮度条（父亲 2026-10-10「用亮度条操作」「调影厅环境光线」）：
             * 一条 0~1 的滑块，同时带动「影院 HDRI 环境光强度」和「底光」——
             * 银幕那块画面光不跟着变（画面亮度是影片自己的事，另有画面调整那一套）。
             */
            if (value >= 0.f && value <= 1.f) {
                gCinemaBright.store(value);
                gCinemaAmbient.store(0.02f + 0.10f * value);
                gEnvStrength.store(0.30f + 5.0f * value);
                LOGI("调参 → 影厅环境亮度条 %.2f（环境光 %.2f / 底光 %.3f）", (double) value,
                     (double) gEnvStrength.load(), (double) gCinemaAmbient.load());
            }
            break;
        case 23:
            /* 坐姿眼高（米）：父亲是坐着看电影，地面到眼睛的距离 */
            if (value >= 0.7f && value <= 1.9f) {
                gCinemaEyeHeight.store(value);
                LOGI("调参 → 坐姿眼高 %.2f 米（影厅跟着挪 %.2f 米）", (double) value,
                     (double) (1.65f - value));
            }
            break;
        case 18:
            /* 防穿越：离银幕最近允许多少米（父亲 2026-10-10：可以走近银幕，不许穿过） */
            if (value >= 0.3f && value <= 5.f) {
                gMinScreenGap.store(value);
                LOGI("调参 → 最近离银幕 %.2f 米（再走近，整个场景会跟着往远处推）", (double) value);
            }
            break;
        case 19:
            /* 控制条/菜单/海报墙是否跟随观影位（1 = 跟，0 = 固定在世界里） */
            gFollowViewer.store(value >= 0.5f ? 1 : 0);
            LOGI("调参 → 控制条跟随观影位 %s", gFollowViewer.load() != 0 ? "开" : "关");
            break;
        default:
            LOGI("调参 → 未知参数 key=%d（忽略）", (int) key);
            break;
    }
}

/*
 * 影厅资源路径（父亲 2026-10-10）：Java 侧先把 assets 里的两个文件拷到应用私有目录，
 * 再在起 VR 之前把路径塞进来。真正的读盘/上传在渲染线程里做（GL 上下文就绪之后）。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetAssetPaths(JNIEnv *env, jobject /* this */,
                                                       jstring cinemaPath,
                                                       jstring envPath) {
    if (cinemaPath != nullptr) {
        const char *p = env->GetStringUTFChars(cinemaPath, nullptr);
        if (p != nullptr) {
            gCinemaAssetPath = p;
            env->ReleaseStringUTFChars(cinemaPath, p);
        }
    }
    if (envPath != nullptr) {
        const char *p = env->GetStringUTFChars(envPath, nullptr);
        if (p != nullptr) {
            gEnvAssetPath = p;
            env->ReleaseStringUTFChars(envPath, p);
        }
    }
    LOGI("影厅资源路径：几何=%s 环境光=%s", gCinemaAssetPath.c_str(), gEnvAssetPath.c_str());
}

/** 选座（0 近 / 1 中 / 2 远）：控制条上点一下就换排 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetSeat(JNIEnv *env, jobject /* this */, jint seat) {
    (void) env;
    applySeat((int) seat);
}

/** 视频纹理尺寸（锐化的邻域步长要用真实像素）*/
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetVideoSize(JNIEnv *env, jobject /* this */,
                                                      jint width, jint height) {
    if (width > 0 && height > 0) {
        g.texelX.store(1.f / (float) width);
        g.texelY.store(1.f / (float) height);
    }
}

/**
 * 按播放路径设置「视频独立层宽度上限」（2026-10-09 父亲要求区分）。
 *
 * 两条路的 GPU 预算完全不同：
 *   · 内核路径（杜比视界 Profile 5）：杜比还原链很贵，实测视频处理宽度 1664 时
 *     GPU 涨到 15ms 超出预算 → 只能给 1280。
 *   · 系统播放器路径（Profile 8.x 等）：实测只要 1.8~2.4ms，完全吃得下更大的层。
 * 所以由 Java 侧在起播时按实际路径下发，而不是全局一个值。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetVideoLayerMaxW(JNIEnv *env, jobject /* this */,
                                                           jint width) {
    (void) env;
    if (width > 0) {
        gVideoLayerMaxW.store((int) width);
        LOGI("调参 → 视频层宽度上限 %d（按播放路径下发）", (int) width);
    }
}

/**
 * 注册 VR 输入回调（Java 侧实现 VrNative.InputSink）。
 *
 * 渲染线程每帧把光柱指向 / 扳机 / 摇杆 / B 键回推过去。持有全局引用，
 * 传 null 表示注销；重复注册会替换旧的。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeAttachInputSink(JNIEnv *env, jobject /* this */,
                                                        jobject sink) {
    if (g.inputSink != nullptr) {
        env->DeleteGlobalRef(g.inputSink);
        g.inputSink = nullptr;
    }
    g.sinkPointer = g.sinkClick = g.sinkStick = g.sinkBack = nullptr;
    g.sinkOsdPointer = g.sinkOsdClick = g.sinkToggleOsd = nullptr;
    g.sinkTriggerState = nullptr;
    g.sinkMenuPointer = g.sinkMenuClick = nullptr;
    g.sinkVideoFrame = nullptr;
    if (sink == nullptr) {
        LOGI("VR 输入回调已注销");
        return;
    }
    g.inputSink = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(sink);
    g.sinkPointer = env->GetMethodID(cls, "onPointer", "(FF)V");
    g.sinkClick = env->GetMethodID(cls, "onClick", "(FF)V");
    g.sinkStick = env->GetMethodID(cls, "onStick", "(FFFF)V");
    g.sinkOsdPointer = env->GetMethodID(cls, "onOsdPointer", "(FFZ)V");
    g.sinkOsdClick = env->GetMethodID(cls, "onOsdClick", "(FF)V");
    g.sinkToggleOsd = env->GetMethodID(cls, "onToggleOsd", "()V");
    g.sinkMenuPointer = env->GetMethodID(cls, "onMenuPointer", "(FFZ)V");
    g.sinkMenuClick = env->GetMethodID(cls, "onMenuClick", "(FF)V");
    g.sinkTriggerState = env->GetMethodID(cls, "onTriggerState", "(Z)V");
    g.sinkBack = env->GetMethodID(cls, "onBack", "()V");
    g.sinkPanelFocus = env->GetMethodID(cls, "onPanelFocus", "(Z)V");
    // 视频画面首次到纹理层（换片等待期的黑幕 / 转圈等这个信号才收）
    g.sinkVideoFrame = env->GetMethodID(cls, "onVideoFrameReady", "()V");
    env->DeleteLocalRef(cls);
    LOGI("VR 输入回调已注册（指针=%d 点击=%d 摇杆=%d 返回=%d 控制条=%d/%d 开关=%d 面板焦点=%d "
         "菜单=%d/%d）",
         g.sinkPointer != nullptr ? 1 : 0, g.sinkClick != nullptr ? 1 : 0,
         g.sinkStick != nullptr ? 1 : 0, g.sinkBack != nullptr ? 1 : 0,
         g.sinkOsdPointer != nullptr ? 1 : 0, g.sinkOsdClick != nullptr ? 1 : 0,
         g.sinkToggleOsd != nullptr ? 1 : 0, g.sinkPanelFocus != nullptr ? 1 : 0,
         g.sinkMenuPointer != nullptr ? 1 : 0, g.sinkMenuClick != nullptr ? 1 : 0);
}

/*
 * 注册纹理回调（Java 侧实现 VrNative.TextureSink）。
 *
 * 三张画面纹理由渲染线程在自己的上下文里建，建好从这里推给界面层；
 * 如果 Java 注册得晚（纹理已经建好），立刻补推一次，避免"建好了没人接"。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeAttachTextureSink(JNIEnv *env, jobject /* this */,
                                                           jobject sink) {
    if (g.textureSink != nullptr) {
        env->DeleteGlobalRef(g.textureSink);
        g.textureSink = nullptr;
    }
    g.sinkTexture = nullptr;
    if (sink == nullptr) {
        LOGI("纹理回调已注销");
        return;
    }
    g.textureSink = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(sink);
    g.sinkTexture = env->GetMethodID(cls, "onTexture",
                                     "(ILandroid/graphics/SurfaceTexture;)V");
    env->DeleteLocalRef(cls);
    LOGI("纹理回调已注册（统一通道 onTexture=%d）", g.sinkTexture != nullptr ? 1 : 0);

    if (g.panelSt != nullptr) pushTexturesToJava(g, env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeStopVr(JNIEnv *env, jobject /* this */) {
    if (!gRunning) return;
    gRequestStop = true;
    if (gThread.joinable()) gThread.join();
    gRunning = false;
    if (g.activity != nullptr) {
        env->DeleteGlobalRef(g.activity);
        g.activity = nullptr;
    }
    LOGI("VR 会话已停止");
}
