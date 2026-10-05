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
uniform samplerExternalOES uTexture;
out vec4 fragColor;
void main() {
    if (uUseTexture == 1) {
        fragColor = texture(uTexture, vUv);
    } else if (uCircle == 1) {
        // 圆形光点：方形面片上按 UV 半径裁掉四角，边缘做 1 像素软化
        float d = length(vUv - vec2(0.5));
        if (d > 0.5) discard;
        fragColor = vec4(uColor.rgb, uColor.a * smoothstep(0.5, 0.44, d));
    } else {
        fragColor = uColor;
    }
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

// ---------------------------------------------------------------- 会话上下文
struct EyeSwapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    int32_t width = 0;
    int32_t height = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    std::vector<GLuint> fbos;
};

/** 一块虚拟屏的摆位：中心、朝向（偏航 + 仰角）、宽度（米） */
struct ScreenPlacement {
    float cx, cy, cz;
    float yawDeg;    // 绕 Y 轴偏航（左右）
    float pitchDeg;  // 绕 X 轴仰角（上下；拖动时用来始终正对人）
    float width;
};

/** 正前方那块屏（银幕）：播放时贴视频，没播时是暗色空屏 */
constexpr ScreenPlacement kFrontScreen{0.f, 0.f, -3.2f, 0.f, 0.f, 3.5f};
/**
 * 海报墙的**初始**摆位：左前方、斜着正对观影者。
 *
 * 父亲 2026-10-05 定：进 VR 空间就是「前方一块银幕、左侧斜放海报墙」。
 * 之后可以用光柱按住扳机把它拖走（球面移动，见 pushInput），
 * 所以真正的位置记在 VrContext 的 panelPos* 里，这里只是起点。
 */
constexpr ScreenPlacement kSideScreen{-2.25f, -0.05f, -2.05f, 46.f, 0.f, 2.8f};

struct VrContext {
    JavaVM *jvm = nullptr;
    jobject activity = nullptr;   // 全局引用

    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace localSpace = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool sessionRunning = false;

    EGLDisplay eglDisplay = EGL_NO_DISPLAY;
    EGLConfig eglConfig = nullptr;
    EGLContext eglContext = EGL_NO_CONTEXT;
    EGLSurface eglSurface = EGL_NO_SURFACE;

    std::vector<EyeSwapchain> eyes;
    std::vector<XrViewConfigurationView> viewConfigs;

    GLuint program = 0;
    GLint mvpLoc = -1;
    GLint colorLoc = -1;
    GLint useTexLoc = -1;
    GLint texLoc = -1;
    GLint circleLoc = -1;          // uCircle：纯色画成圆点还是方块
    GLuint vbo = 0;                // 单位方块（面板 / 光点）
    GLuint rayVbo = 0;             // 手柄射线网格（圆锥）
    int rayVertexCount = 0;

    // ---- VR 输入回推给 Java（光柱 → 面板点击/滚动，2026-10-05）----
    jobject inputSink = nullptr;        // VrNative.InputSink 的全局引用
    jmethodID sinkPointer = nullptr;
    jmethodID sinkClick = nullptr;
    jmethodID sinkStick = nullptr;
    jmethodID sinkBack = nullptr;
    jmethodID sinkOsdPointer = nullptr; // 控制条上的指针
    jmethodID sinkOsdClick = nullptr;   // 控制条上的点击
    jmethodID sinkToggleOsd = nullptr;  // 播放中扣扳机 = 开关控制条
    jmethodID sinkPanelFocus = nullptr; // 光柱是否落在海报墙上（决定 B 键给谁）
    bool sinkPointerValid = false;      // 上一次回推的指针位置（只在明显移动时回推）
    float sinkPointerX = 0.f;
    float sinkPointerY = 0.f;
    double sinkLastToggleMs = 0.0;      // 上一次开关控制条的时刻（去抖）
    bool sinkOsdPointerValid = false;   // 控制条上的指针位置
    float sinkOsdPointerX = 0.f;
    float sinkOsdPointerY = 0.f;
    bool sinkLastTrigger[2] = {false, false};
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

    /** 拖海报墙的手感状态：是否抓着、是否真拖动过、按下那一刻的球面半径与起点 */
    bool panelDragActive[2] = {false, false};
    bool panelDragMoved[2] = {false, false};
    float panelDragRadius[2] = {0.f, 0.f};
    float panelDragStartX[2] = {0.f, 0.f};
    float panelDragStartY[2] = {0.f, 0.f};
    float panelDragStartZ[2] = {0.f, 0.f};
    bool sinkStickPushed[2] = {false, false};   // 摇杆是否处在"推着"的状态（回中要补一帧零值）
    bool sinkLastBack[2] = {false, false};
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
    jobject textureSink = nullptr;
    jmethodID sinkPanelTex = nullptr;
    jmethodID sinkVideoTex = nullptr;
    jmethodID sinkOsdTex = nullptr;

    // ---- 手柄输入 ----
    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction aimPoseAction = XR_NULL_HANDLE;      // 手柄指向（激光方向）
    XrAction triggerAction = XR_NULL_HANDLE;      // 扳机（布尔按下）
    XrAction triggerValueAction = XR_NULL_HANDLE; // 扳机（浮点力度）
    XrAction squeezeAction = XR_NULL_HANDLE;      // 侧握
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
    XrVector2f thumbstick[2] = {{0.f, 0.f}, {0.f, 0.f}};
    bool aDown[2] = {false, false};
    bool bDown[2] = {false, false};
    bool menuDown[2] = {false, false};

    // 平面放在正前方：3.2m 远，3.2m 宽（约 53° 视场），16:9
    float panelDistance = 3.2f;
    // 面板宽 3.5m ≈ 水平 58° 视角，3.2m 远，与影院前排观感接近
    float panelWidth = 3.5f;
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
std::function<void()> gVideoUpdate;   // 播放画面取帧（同面板：必须在 VR 渲染线程调）
std::function<void()> gOsdUpdate;     // 控制条取帧（同上）
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
            EGL_DEPTH_SIZE, 0,
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
    int64_t chosen = formats[0];
    for (int64_t f : formats) {
        if (f == GL_SRGB8_ALPHA8) { chosen = f; break; }
        if (f == GL_RGBA8) chosen = f;
    }
    LOGI("swapchain 格式选定 0x%llx（候选 %u 个）", (unsigned long long) chosen, count);

    c.eyes.resize(c.viewConfigs.size());
    for (size_t i = 0; i < c.viewConfigs.size(); i++) {
        const auto &vc = c.viewConfigs[i];
        EyeSwapchain &eye = c.eyes[i];
        eye.width = (int32_t) vc.recommendedImageRectWidth;
        eye.height = (int32_t) vc.recommendedImageRectHeight;

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
        for (uint32_t k = 0; k < imgCount; k++) {
            glGenFramebuffers(1, &eye.fbos[k]);
            glBindFramebuffer(GL_FRAMEBUFFER, eye.fbos[k]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                                   static_cast<GLuint>(eye.images[k].image), 0);
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
            {"/user/hand/left/input/squeeze/value", "/user/hand/right/input/squeeze/value", c.squeezeAction},
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

/**
 * 海报墙常驻左前方（浏览、播放都在那儿；前方那块留给银幕）。
 * 父亲可以用光柱按住扳机把它拖走，位移量记在 VrContext 里。
 */
ScreenPlacement panelPlacement(const VrContext &c) {
    ScreenPlacement p = kSideScreen;
    p.cx = c.panelPosX.load();
    p.cy = c.panelPosY.load();
    p.cz = c.panelPosZ.load();
    p.yawDeg = c.panelYawDeg.load();
    p.pitchDeg = c.panelPitchDeg.load();
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
    return poseScaleModel(pose, p.width, p.width * 9.f / 16.f, 1.f);
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
 * 银幕上的加载转圈（父亲 2026-10-06 定：绿色、带缺口的圆环箭头、顺时针旋转）。
 *
 * 用一圈小方块拼出细环，缺口处不画 —— 缺口就是"箭头"，转起来方向一眼能看出。
 * 只在「已开播但第一帧还没到」这段时间画（换片清屏后的等待）。
 */
void drawSpinner(VrContext &c, const Mat4 &proj, const Mat4 &view4) {
    if (c.vbo == 0 || c.program == 0) return;
    const double now = std::chrono::duration<double>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    constexpr int kSegs = 24;         // 整圈分 24 段
    constexpr int kGapSegs = 5;       // 缺口占 5 段（约 75°）
    constexpr float kRadius = 0.16f;  // 环半径（米）
    constexpr float kThick = 0.018f;  // 环的粗细（米）
    const float segLen = 2.f * 3.14159265358979f * kRadius / (float) kSegs;
    const float step = 2.f * 3.14159265358979f / (float) kSegs;
    const float base = (float) (-now * 2.6);   // 角度递减 = 顺时针
    for (int i = 0; i < kSegs - kGapSegs; i++) {
        const float ang = base + (float) i * step;
        // 尾巴暗、缺口那头亮：看起来像个箭头在转
        const float fade = 0.45f + 0.55f * (1.f - (float) i / (float) (kSegs - kGapSegs));
        XrPosef seg{};
        seg.position = {kFrontScreen.cx + cosf(ang) * kRadius,
                        kFrontScreen.cy + sinf(ang) * kRadius,
                        kFrontScreen.cz + 0.012f};   // 稍微抬出来，别和银幕抢像素
        const float half = ang * 0.5f;
        seg.orientation = {0.f, 0.f, sinf(half), cosf(half)};   // 绕 Z 轴摆到这一段
        const Mat4 m = poseScaleModel(seg, segLen, kThick, 1.f);
        drawMesh(c, c.vbo, 6, multiply(multiply(proj, view4), m),
                 0.16f * fade, 0.85f * fade, 0.32f * fade, false);
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
constexpr float kOsdPxW = 1920.f;
constexpr float kOsdPxH = 300.f;
constexpr float kOsdWidth = 1.45f;                        // 米（父亲：再宽一点）
constexpr float kOsdHeight = kOsdWidth * kOsdPxH / kOsdPxW;
constexpr float kOsdDistance = 0.85f;                     // 正前方距离（米，父亲：再近些）
constexpr float kOsdCenterY = -0.62f;                     // 视线下方（米，父亲：再靠下）
constexpr float kOsdTiltDeg = -24.f;                      // 上仰角（度），正对观影者

/** 控制条平面：中心与两条轴（含仰角）。返回法线 n 与面内 x/y 轴 */
void osdBasis(float *cx, float *cy, float *cz, float *nx, float *ny, float *nz, float *ux,
              float *uy, float *uz, float *vx, float *vy, float *vz) {
    const float th = kOsdTiltDeg * 3.14159265358979f / 180.f;
    const float ct = cosf(th), st = sinf(th);
    *cx = 0.f; *cy = kOsdCenterY; *cz = -kOsdDistance;
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

        if (!c.aimValid[h]) continue;

        /*
         * ① 控制条优先：指着控制条时，指针与点击都给控制条，不碰主面板。
         *    （控制条是近场小面板，尺寸 1920×270，坐标单独换算。）
         */
        if (c.osdVisible.load() && c.osdTex != 0 && c.osdHasFrame.load()) {
            float osdT = 0.f, ou = 0.f, ov = 0.f;
            if (rayHitsOsd(c, c.aimPose[h], &osdT, &ou, &ov)) {
                const float opx = (ou / kOsdWidth + 0.5f) * kOsdPxW;
                const float opy = (0.5f - ov / kOsdHeight) * kOsdPxH;
                if (!c.sinkOsdPointerValid || fabsf(opx - c.sinkOsdPointerX) > 2.f ||
                    fabsf(opy - c.sinkOsdPointerY) > 2.f) {
                    if (c.sinkOsdPointer != nullptr) {
                        env->CallVoidMethod(c.inputSink, c.sinkOsdPointer, opx, opy);
                        clearJavaException(env, "输入回调 onOsdPointer");
                    }
                    c.sinkOsdPointerX = opx;
                    c.sinkOsdPointerY = opy;
                    c.sinkOsdPointerValid = true;
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
         * ② 海报墙优先（2026-10-06 父亲：播放期间海报墙照样能操作）：
         *    光柱指在海报墙上 → 指针 / 点击 / 摇杆 / 拖动都给它，与是否正在播放无关。
         *    命中判定按摆位求交（含朝向），否则点击坐标会整体错位。
         */
        const ScreenPlacement place = panelPlacement(c);
        float planeT = 0.f, hu = 0.f, hv = 0.f, wx = 0.f, wy = 0.f, wz = 0.f;
        const bool onPanel = c.panelShown.load() &&
                             rayHitsPlacement(c.aimPose[h], place, &planeT, &hu, &hv,
                                              &wx, &wy, &wz);

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
             * 按住扳机拖海报墙（父亲 2026-10-06 定：以手柄为球心，海报墙在球面上挪）。
             *
             * 球心 = 手柄位置，半径 = 按下那一刻手柄到海报墙中心的距离；按住期间
             * 海报墙中心 = 球心 + 半径 × 光柱方向 —— 光柱扫到哪儿它跟到哪儿，
             * 朝向同时反解成"正对球心"（也就是正对观影者）。
             * 松手时若中心几乎没动，才算一次点击（不然"想拖一下"会顺手点开片子）。
             */
            if (c.triggerDown[h]) {
                const XrVector3f hand = c.aimPose[h].position;
                if (!c.panelDragActive[h]) {
                    c.panelDragActive[h] = true;
                    c.panelDragMoved[h] = false;
                    const float dx = c.panelPosX.load() - hand.x;
                    const float dy = c.panelPosY.load() - hand.y;
                    const float dz = c.panelPosZ.load() - hand.z;
                    c.panelDragRadius[h] = sqrtf(dx * dx + dy * dy + dz * dz);
                    c.panelDragStartX[h] = c.panelPosX.load();
                    c.panelDragStartY[h] = c.panelPosY.load();
                    c.panelDragStartZ[h] = c.panelPosZ.load();
                } else {
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
                        // 法线指回球心（= 光柱方向的反向），反解偏航与仰角
                        const float kRad2Deg = 180.f / 3.14159265358979f;
                        c.panelPitchDeg = asinf(ddy) * kRad2Deg;
                        c.panelYawDeg = atan2f(-ddx, -ddz) * kRad2Deg;
                    }
                }
            } else if (c.panelDragActive[h]) {
                const bool moved = c.panelDragMoved[h];
                c.panelDragActive[h] = false;
                c.panelDragMoved[h] = false;
                if (moved) {
                    LOGI("海报墙：挪到 (%.2f, %.2f, %.2f) 朝向 %.0f°/%.0f°", c.panelPosX.load(),
                         c.panelPosY.load(), c.panelPosZ.load(), c.panelYawDeg.load(),
                         c.panelPitchDeg.load());
                } else if (c.sinkClick != nullptr) {
                    LOGI("VR 输入：%s 扳机 → 面板点击 (%d, %d)", handName[h], (int) px, (int) py);
                    env->CallVoidMethod(c.inputSink, c.sinkClick, px, py);
                    clearJavaException(env, "输入回调 onClick");
                }
            }
            c.sinkLastTrigger[h] = c.triggerDown[h];

            // 摇杆 → 连状态上报（30Hz）；回中补一帧零值，Java 侧据此进入惯性滑行
            const float sx = c.thumbstick[h].x;
            const float sy = c.thumbstick[h].y;
            if (fabsf(sx) > kStickDeadzone || fabsf(sy) > kStickDeadzone) {
                if (t - c.sinkStickAt[h] >= kStickStateMs) {
                    c.sinkStickAt[h] = t;
                    c.sinkStickPushed[h] = true;
                    if (c.sinkStick != nullptr) {
                        // 坐标 + 摇杆量（x 右正、y 上正，与 OpenXR 一致；方向语义在 Java 侧翻）
                        env->CallVoidMethod(c.inputSink, c.sinkStick, px, py, sx, sy);
                        clearJavaException(env, "输入回调 onStick");
                    }
                }
            } else if (c.sinkStickPushed[h] && c.sinkStick != nullptr) {
                c.sinkStickPushed[h] = false;
                c.sinkStickAt[h] = 0.0;
                env->CallVoidMethod(c.inputSink, c.sinkStick, px, py, 0.f, 0.f);
                clearJavaException(env, "输入回调 onStick(回中)");
            }
            continue;
        }

        /*
         * ③ 播放中、光柱不在海报墙上：扳机 = 开关控制条，而且**只有指着银幕**才算
         *    （父亲 2026-10-06：指别处扣扳机，控制条保持现状）。
         */
        if (c.videoActive.load()) {
            float vT = 0.f, vu = 0.f, vv = 0.f, vwx = 0.f, vwy = 0.f, vwz = 0.f;
            const bool onScreen = rayHitsPlacement(c.aimPose[h], kFrontScreen, &vT, &vu, &vv,
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

bool renderEye(VrContext &c, int eyeIndex, const XrView &view) {
    EyeSwapchain &eye = c.eyes[eyeIndex];
    uint32_t imageIndex = 0;
    XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    const XrResult ar = api.AcquireSwapchainImage(eye.handle, &ai, &imageIndex);
    if (XR_FAILED(ar)) {
        LOGE("取图失败（眼 %d）：%d", eyeIndex, (int) ar);
        return false;
    }
    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wi.timeout = XR_INFINITE_DURATION;
    const XrResult wr = api.WaitSwapchainImage(eye.handle, &wi);
    if (XR_FAILED(wr)) {
        LOGE("等图失败（眼 %d）：%d", eyeIndex, (int) wr);
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        api.ReleaseSwapchainImage(eye.handle, &ri);
        return false;
    }

    if (imageIndex >= eye.fbos.size() || eye.fbos[imageIndex] == 0) {
        LOGE("FBO 下标越界（眼 %d，index=%u，共 %zu）", eyeIndex, imageIndex, eye.fbos.size());
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        api.ReleaseSwapchainImage(eye.handle, &ri);
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, eye.fbos[imageIndex]);
    glViewport(0, 0, eye.width, eye.height);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    const Mat4 proj = perspectiveFromFov(view.fov, 0.05f, 100.f);
    const Mat4 view4 = viewMatrixFromPose(view.pose);
    if (c.program != 0 && c.mvpLoc >= 0) {
        /*
         * 一块屏：摆位 → 位置/朝向/尺寸，贴 tex（tex = 0 就画底色）。
         *
         * 播放画面与海报墙走同一条绘制路径，只有摆位不同 —— 2026-10-05 父亲要求
         * 「海报墙与播放屏分开」：以前是同一块屏来回换贴图，播放一开海报墙就被顶掉。
         */
        auto drawScreen = [&](const ScreenPlacement &place, unsigned tex) {
            const Mat4 mvp = multiply(multiply(proj, view4), placementModel(place));
            glUseProgram(c.program);
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
                glUniform4f(c.colorLoc, 1.f, 1.f, 1.f, 1.f);
            } else {
                glUniform1i(c.useTexLoc, 0);
                glUniform4f(c.colorLoc, 0.15f, 0.16f, 0.20f, 1.f);
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

        // 前方银幕：播放时贴视频；没播时是一块空屏（暗色），空间里有"银幕"在
        const bool videoReady = c.videoActive.load() && c.videoTex != 0 && c.videoHasFrame.load();
        drawScreen(kFrontScreen, videoReady ? c.videoTex : 0);

        // 海报墙：常驻左前方斜放；收起时不画，没出帧也先不画（不闪也不串）
        const bool panelReady = c.panelActive.load() && c.panelTex != 0 && c.panelHasFrame.load();
        if (panelReady && c.panelShown.load()) drawScreen(panelPlacement(c), c.panelTex);

        // 起播 / 换片到第一帧之间：银幕上转圈，别留上一部的画面（父亲 2026-10-05 要求）
        if (c.videoActive.load() && !videoReady) drawSpinner(c, proj, view4);

        /*
         * 控制条（近场小面板，2026-10-05）：贴在观影者正前方偏下、上仰一点，
         * 与主画面同一套着色器与属性布局，只是换一张纹理、换一个模型矩阵。
         */
        if (c.osdVisible.load() && c.osdTex != 0 && c.osdHasFrame.load()) {
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
    if (c.program != 0 && c.mvpLoc >= 0 && c.rayVbo != 0) {
        const float kRayNear = 0.001f;    // 近端半径 1mm（官方 handScale 的 x/y 分量）
        const float kNoHitDistance = 100.f;
        const float kDotSize = 0.022f;    // 光点直径 2.2cm ≈ 面板上 12px

        for (int h = 0; h < 2; h++) {
            if (!c.aimValid[h]) continue;

            const ScreenPlacement place = panelPlacement(c);
            float planeT = 0.f, hu = 0.f, hv = 0.f;
            float hitX = 0.f, hitY = 0.f, hitZ = 0.f;
            const bool hit = c.panelShown.load() &&
                             rayHitsPlacement(c.aimPose[h], place, &planeT, &hu, &hv,
                                              &hitX, &hitY, &hitZ);

            /*
             * 控制条挡在面板前面，射线也得在它上面收住（父亲：光线穿过控制条了）。
             * 取两者里更近的那个命中点：控制条命中 → 光线与控制条齐平，光点落在控制条上。
             */
            float osdT = 0.f, osdU = 0.f, osdV = 0.f;
            const bool osdHit = c.osdVisible.load() && c.osdTex != 0 && c.osdHasFrame.load() &&
                                rayHitsOsd(c, c.aimPose[h], &osdT, &osdU, &osdV);

            float rayLength = kNoHitDistance;
            bool dotOnPanel = false, dotOnOsd = false;
            if (hit && planeT > 0.f) {
                rayLength = planeT;
                dotOnPanel = true;
            }
            if (osdHit && osdT > 0.f && osdT < rayLength) {
                rayLength = osdT;
                dotOnPanel = false;
                dotOnOsd = true;
            }

            // 光线：从手柄沿指向射出
            const Mat4 rayModel = poseScaleModel(c.aimPose[h], kRayNear, kRayNear, rayLength);
            drawMesh(c, c.rayVbo, c.rayVertexCount,
                     multiply(multiply(proj, view4), rayModel),
                     0.30f, 0.82f, 0.22f, false);   // 与 TV 版强调色一致的绿

            // 光点：贴在命中点上（朝眼睛方向抬几毫米，避免和面抢像素）
            if (dotOnPanel) {
                // 屏可能斜着（播放时的海报墙在左边、朝右前方）：光点跟着屏的朝向转
                const float pth = place.yawDeg * 3.14159265358979f / 180.f;
                XrPosef dotPose{};
                dotPose.position = {hitX + sinf(pth) * 0.005f, hitY, hitZ + cosf(pth) * 0.005f};
                dotPose.orientation = {0.f, sinf(pth * 0.5f), 0.f, cosf(pth * 0.5f)};
                const Mat4 dotModel = poseScaleModel(dotPose, kDotSize, kDotSize, 1.f);
                drawMesh(c, c.vbo, 6, multiply(multiply(proj, view4), dotModel),
                         0.55f, 0.98f, 0.45f, true);
            } else if (dotOnOsd) {
                // 控制条是斜的：光点跟着斜，落在命中点上
                float dx = 0.f, dy = 0.f, dz = 0.f;
                aimDirection(c.aimPose[h], &dx, &dy, &dz);
                float cx, cy, cz, nx, ny, nz, ux, uy, uz, vx, vy, vz;
                osdBasis(&cx, &cy, &cz, &nx, &ny, &nz, &ux, &uy, &uz, &vx, &vy, &vz);
                const float hxw = c.aimPose[h].position.x + dx * osdT + nx * 0.006f;
                const float hyw = c.aimPose[h].position.y + dy * osdT + ny * 0.006f;
                const float hzw = c.aimPose[h].position.z + dz * osdT + nz * 0.006f;
                const float thd = kOsdTiltDeg * 3.14159265358979f / 180.f;
                XrPosef dotPose{};
                dotPose.position = {hxw, hyw, hzw};
                dotPose.orientation = {sinf(thd * 0.5f), 0.f, 0.f, cosf(thd * 0.5f)};
                const Mat4 dotModel = poseScaleModel(dotPose, kDotSize, kDotSize, 1.f);
                drawMesh(c, c.vbo, 6, multiply(multiply(proj, view4), dotModel),
                         0.55f, 0.98f, 0.45f, true);
            }
        }
    }

    // 画完必须解绑 FBO，否则下一只眼/下一帧会画进同一个附件
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    const XrResult rr = api.ReleaseSwapchainImage(eye.handle, &ri);
    if (XR_FAILED(rr)) {
        LOGE("交回图失败（眼 %d）：%d", eyeIndex, (int) rr);
        return false;
    }
    return true;
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

    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    layer.space = c.localSpace;
    layer.viewCount = (uint32_t) projViews.size();
    layer.views = projViews.data();

    const XrCompositionLayerBaseHeader *layerPtrs[1] = {
            reinterpret_cast<const XrCompositionLayerBaseHeader *>(&layer),
    };

    int loggedFrames = 0;
    while (!gRequestStop) {
        pumpEvents(c);
        if (gRequestStop) break;

        if (!c.sessionRunning) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        XrFrameWaitInfo fwi{XR_TYPE_FRAME_WAIT_INFO};
        XrFrameState fs{XR_TYPE_FRAME_STATE};
        if (XR_FAILED(api.WaitFrame(c.session, &fwi, &fs))) {
            LOGE("xrWaitFrame 失败，退出循环");
            break;
        }
        XrFrameBeginInfo fbi{XR_TYPE_FRAME_BEGIN_INFO};
        api.BeginFrame(c.session, &fbi);

        /*
         * 取面板新一帧。必须在渲染线程做（与面板纹理同一个 GL 上下文），
         * 且要在画之前 —— 否则贴上去的永远是上一帧。
         */
        if (c.panelActive.load() && gPanelUpdate != nullptr) {
            gPanelUpdate();
        }

        // 播放画面：同样必须在渲染线程取帧（与视频纹理同一个 GL 上下文）
        if (c.videoActive.load() && gVideoUpdate != nullptr) {
            gVideoUpdate();
        }

        // 控制条：近场小面板，每帧取一次（与面板/视频同一套 SurfaceTexture 机制）
        if (c.osdVisible.load() && gOsdUpdate != nullptr) {
            gOsdUpdate();
        }

        // 手柄状态（诊断阶段：变化即打日志，先看清 PICO 到底发哪些事件）
        c.frameDisplayTime = fs.predictedDisplayTime;
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
        if (fs.shouldRender && c.sessionRunning) {
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
        fei.layerCount = rendered ? 1 : 0;
        fei.layers = rendered ? layerPtrs : nullptr;
        api.EndFrame(c.session, &fei);
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
        c.mvpLoc = glGetUniformLocation(c.program, "uMvp");
        c.colorLoc = glGetUniformLocation(c.program, "uColor");
        c.useTexLoc = glGetUniformLocation(c.program, "uUseTexture");
        c.texLoc = glGetUniformLocation(c.program, "uTexture");
        c.circleLoc = glGetUniformLocation(c.program, "uCircle");
        makeQuadBuffers(c);
        makeRayBuffer(c);
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
                       std::atomic<bool> &hasFrame, const char *what) {
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
    out = [globalRef, updateTexImage, getTimestamp, &hasFrame, what]() {
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
            if (ts > 0) {
                hasFrame = true;
                LOGI("%s首帧到位（timestamp=%lld）—— 可以画了", what, (long long) ts);
            }
        }
    };
    return globalRef;
}

/** 把已经建好的三张画面推给 Java 侧（注册回调时补推也走这里） */
void pushTexturesToJava(VrContext &c, JNIEnv *env) {
    if (env == nullptr || c.textureSink == nullptr || c.sinkPanelTex == nullptr) return;
    if (c.panelSt != nullptr) {
        env->CallVoidMethod(c.textureSink, c.sinkPanelTex, c.panelSt);
        clearJavaException(env, "回调 onPanelTexture");
    }
    if (c.sinkVideoTex != nullptr && c.videoSt != nullptr) {
        env->CallVoidMethod(c.textureSink, c.sinkVideoTex, c.videoSt);
        clearJavaException(env, "回调 onVideoTexture");
    }
    if (c.sinkOsdTex != nullptr && c.osdSt != nullptr) {
        env->CallVoidMethod(c.textureSink, c.sinkOsdTex, c.osdSt);
        clearJavaException(env, "回调 onOsdTexture");
    }
    LOGI("三张画面已推给界面层（面板 / 播放画面 / 控制条）");
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
    if (c.panelTex == 0 || c.videoTex == 0 || c.osdTex == 0) {
        LOGE("建画面纹理失败：panel=%u video=%u osd=%u", c.panelTex, c.videoTex, c.osdTex);
        return false;
    }

    c.panelSt = makeOesSurface(env, c.panelTex, gPanelUpdate, c.panelHasFrame, "面板");
    c.videoSt = makeOesSurface(env, c.videoTex, gVideoUpdate, c.videoHasFrame, "播放画面");
    c.osdSt = makeOesSurface(env, c.osdTex, gOsdUpdate, c.osdHasFrame, "控制条");
    if (c.panelSt == nullptr || c.videoSt == nullptr || c.osdSt == nullptr) {
        LOGE("画面纹理不完整，VR 贴图不可用");
        return false;
    }

    c.panelActive = false;
    c.videoActive = false;
    c.osdVisible = false;
    LOGI("三张画面纹理已在本渲染线程的上下文里创建：panel=%u video=%u osd=%u",
         c.panelTex, c.videoTex, c.osdTex);

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
    LOGI("播放画面状态 → %s", g.videoActive.load() ? "true" : "false");
}


/** 控制条显示/隐藏（播放中扣扳机切换，由 Java 侧决定） */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetOsdVisible(JNIEnv *env, jobject /* this */,
                                                      jboolean visible) {
    g.osdVisible = (visible == JNI_TRUE);
    LOGI("控制条状态 → %s", g.osdVisible.load() ? "显示" : "隐藏");
}

/** 海报墙显示/隐藏（控制条上的「选片」按钮切换，由 Java 侧决定） */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetPanelShown(JNIEnv *env, jobject /* this */,
                                                       jboolean shown) {
    g.panelShown = (shown == JNI_TRUE);
    LOGI("海报墙状态 → %s", g.panelShown.load() ? "摆出来" : "收起");
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
    if (sink == nullptr) {
        LOGI("VR 输入回调已注销");
        return;
    }
    g.inputSink = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(sink);
    g.sinkPointer = env->GetMethodID(cls, "onPointer", "(FF)V");
    g.sinkClick = env->GetMethodID(cls, "onClick", "(FF)V");
    g.sinkStick = env->GetMethodID(cls, "onStick", "(FFFF)V");
    g.sinkOsdPointer = env->GetMethodID(cls, "onOsdPointer", "(FF)V");
    g.sinkOsdClick = env->GetMethodID(cls, "onOsdClick", "(FF)V");
    g.sinkToggleOsd = env->GetMethodID(cls, "onToggleOsd", "()V");
    g.sinkBack = env->GetMethodID(cls, "onBack", "()V");
    g.sinkPanelFocus = env->GetMethodID(cls, "onPanelFocus", "(Z)V");
    env->DeleteLocalRef(cls);
    LOGI("VR 输入回调已注册（指针=%d 点击=%d 摇杆=%d 返回=%d 控制条=%d/%d 开关=%d 面板焦点=%d）",
         g.sinkPointer != nullptr ? 1 : 0, g.sinkClick != nullptr ? 1 : 0,
         g.sinkStick != nullptr ? 1 : 0, g.sinkBack != nullptr ? 1 : 0,
         g.sinkOsdPointer != nullptr ? 1 : 0, g.sinkOsdClick != nullptr ? 1 : 0,
         g.sinkToggleOsd != nullptr ? 1 : 0, g.sinkPanelFocus != nullptr ? 1 : 0);
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
    g.sinkPanelTex = g.sinkVideoTex = g.sinkOsdTex = nullptr;
    if (sink == nullptr) {
        LOGI("纹理回调已注销");
        return;
    }
    g.textureSink = env->NewGlobalRef(sink);
    jclass cls = env->GetObjectClass(sink);
    g.sinkPanelTex = env->GetMethodID(cls, "onPanelTexture",
                                      "(Landroid/graphics/SurfaceTexture;)V");
    g.sinkVideoTex = env->GetMethodID(cls, "onVideoTexture",
                                      "(Landroid/graphics/SurfaceTexture;)V");
    g.sinkOsdTex = env->GetMethodID(cls, "onOsdTexture",
                                    "(Landroid/graphics/SurfaceTexture;)V");
    env->DeleteLocalRef(cls);
    LOGI("纹理回调已注册（面板=%d 播放画面=%d 控制条=%d）",
         g.sinkPanelTex != nullptr ? 1 : 0, g.sinkVideoTex != nullptr ? 1 : 0,
         g.sinkOsdTex != nullptr ? 1 : 0);

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
