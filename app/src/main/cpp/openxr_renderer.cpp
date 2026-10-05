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
uniform samplerExternalOES uTexture;
out vec4 fragColor;
void main() {
    if (uUseTexture == 1) {
        fragColor = texture(uTexture, vUv);
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
    GLuint vbo = 0;

    /*
     * 面板（现有 Compose 界面）纹理：由 Java 侧的 SurfaceTexture 提供。
     * 画面来源链路与 2D 模式下完全一样，只是"贴到哪"变了：
     *   面板 SurfaceTexture（Kotlin 建）→ 这里 updateTexImage 取帧 → 贴到 VR 平面。
     */
    GLuint panelTex = 0;
    std::atomic<bool> panelActive{false};

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
 * 激光的模型矩阵（2026-10-05 run 104 重写：run 103 实机看不到激光）。
 *
 * run 103 的写法把四边形沿 -Z 缩放成"细长条"，再整体平移 -len/2。
 * 问题：先缩放后平移，且缩放矩阵的 z 分量带负号，等于把片子翻到身后，
 * 加上 vbo 顶点本来就是 ±0.5 的方形，最终几何落在手柄背后、朝向也反了，
 * 所以视野里什么都没有。
 *
 * 现在改成几何上无歧义的做法：
 *   - 顶点数据用 ±0.5 的方形（vbo 里本来就是）
 *   - 模型矩阵直接给"宽 half*2、高 half*2、长 len"的缩放（**不加负号**）
 *   - 平移把它推到手柄前方 len/2 处，让光束从手柄出发向前伸
 *   - 光线朝向由手柄姿态的旋转矩阵决定
 * 这样无论 vbo 里是朝 +Z 还是 -Z 的片子，光束都从手柄沿指向射出。
 */
Mat4 laserModelFrom(const XrPosef &pose) {
    const float len = 6.0f;      // 6 米，足够指到面前的银幕
    const float half = 0.012f;   // 加粗一点，细线在 VR 里容易看不见

    // 姿态四元数 → 旋转矩阵（列主序）
    const float x = pose.orientation.x, y = pose.orientation.y;
    const float z = pose.orientation.z, w = pose.orientation.w;
    Mat4 rot = identity();
    rot.m[0] = 1 - 2 * (y * y + z * z);
    rot.m[1] = 2 * (x * y + z * w);
    rot.m[2] = 2 * (x * z - y * w);
    rot.m[4] = 2 * (x * y - z * w);
    rot.m[5] = 1 - 2 * (x * x + z * z);
    rot.m[6] = 2 * (y * z + x * w);
    rot.m[8] = 2 * (x * z + y * w);
    rot.m[9] = 2 * (y * z - x * w);
    rot.m[10] = 1 - 2 * (x * x + y * y);

    // 缩放：细长条（宽度 half*2，长度 len）
    Mat4 scale = identity();
    scale.m[0] = half * 2.f;
    scale.m[5] = half * 2.f;
    scale.m[10] = len;

    /*
     * 平移：先沿手柄本地 -Z 前进 len/2（光束中心在前方半个长度处），
     * 再把手柄位置加上去。旋转体现在 rot 里，所以本地 -Z 就是"手柄指向"。
     *
     * OpenXR 的手柄姿态：-Z 是手柄指向（与 OpenGL 相机朝向一致）。
     */
    const float fwdX = -rot.m[8] * (len * 0.5f);
    const float fwdY = -rot.m[9] * (len * 0.5f);
    const float fwdZ = -rot.m[10] * (len * 0.5f);

    Mat4 trans = identity();
    trans.m[12] = pose.position.x + fwdX;
    trans.m[13] = pose.position.y + fwdY;
    trans.m[14] = pose.position.z + fwdZ;

    return multiply(trans, multiply(rot, scale));
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
        const Mat4 model = translateScale(
                0.f, 0.f, -c.panelDistance, c.panelWidth, c.panelWidth * 9.f / 16.f);
        const Mat4 mvp = multiply(multiply(proj, view4), model);

        glUseProgram(c.program);
        glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, mvp.m);
        /*
         * 有面板纹理就贴纹理（现有界面），没有就画纯色（证明能出画面）。
         * 用外部纹理（OES）：面板来自 SurfaceTexture，与 2D 模式同一套链路。
         */
        const bool usePanel = c.panelActive.load() && c.panelTex != 0;
        if (usePanel) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, c.panelTex);
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
    }

    /*
     * 激光：从手柄 aim 出发点沿朝向画一条细线。
     * 诊断阶段先"有激光可看"，交互逻辑（指到哪、扣扳机干什么）随后接。
     */
    if (c.program != 0 && c.mvpLoc >= 0) {
        for (int h = 0; h < 2; h++) {
            if (!c.aimValid[h]) continue;
            const Mat4 laserModel = laserModelFrom(c.aimPose[h]);
            const Mat4 laserMvp = multiply(multiply(proj, view4), laserModel);
            glUseProgram(c.program);
            glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, laserMvp.m);
            glUniform1i(c.useTexLoc, 0);
            glUniform4f(c.colorLoc, 0.30f, 0.82f, 0.22f, 1.f);   // 与 TV 版强调色一致的绿
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

        // 手柄状态（诊断阶段：变化即打日志，先看清 PICO 到底发哪些事件）
        c.frameDisplayTime = fs.predictedDisplayTime;
        syncInput(c);
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

        c.program = buildProgram();
        c.mvpLoc = glGetUniformLocation(c.program, "uMvp");
        c.colorLoc = glGetUniformLocation(c.program, "uColor");
        c.useTexLoc = glGetUniformLocation(c.program, "uUseTexture");
        c.texLoc = glGetUniformLocation(c.program, "uTexture");
        makeQuadBuffers(c);
        LOGI("GL 资源就绪（program=%u）", c.program);

        frameLoop(c);
    } while (false);

    teardown(c);
    LOGI("VR 渲染线程结束");
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

/**
 * 绑定面板纹理（现有 Compose 界面）。
 *
 * Java 侧建好 SurfaceTexture 后，在 GL 线程里把它的纹理 id 传进来；
 * 之后每帧 updateTexImage 取最新帧，贴到 VR 平面。
 * 传 0 表示解绑（回到纯色）。
 */
/**
 * 创建面板纹理与 SurfaceTexture（**必须在 VR 渲染线程的 GL 上下文里做**）。
 *
 * run 97 实机踩坑：最初由 Java 侧的 GLSurfaceView 线程建纹理、把纹理 id 传进来，
 * 结果 VR 里全黑，日志刷 `checkAndUpdateEglState: invalid current EGLContext`。
 * 根因：GL 纹理 id 只在**创建它的 EGL 上下文**里有效，VR 渲染用的是另一个上下文。
 * 因此这里自己建纹理 + SurfaceTexture，再交回 Java 侧去建虚拟显示器和界面。
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeCreatePanelSurfaceTexture(JNIEnv *env,
                                                                  jobject /* this */) {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    if (tex == 0) {
        LOGE("创建面板纹理失败");
        return nullptr;
    }
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    jclass stClass = env->FindClass("android/graphics/SurfaceTexture");
    if (stClass == nullptr) {
        LOGE("找不到 SurfaceTexture 类");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(stClass, "<init>", "(I)V");
    if (ctor == nullptr) {
        LOGE("找不到 SurfaceTexture 构造方法");
        return nullptr;
    }
    jobject st = env->NewObject(stClass, ctor, (jint) tex);
    if (st == nullptr) {
        LOGE("创建 SurfaceTexture 失败");
        return nullptr;
    }

    g.panelTex = tex;
    g.panelActive = false;   // 等界面真的画上来了再置 true

    auto *globalRef = env->NewGlobalRef(st);
    jmethodID updateTexImage = env->GetMethodID(stClass, "updateTexImage", "()V");
    if (updateTexImage != nullptr) {
        gPanelUpdate = [globalRef, updateTexImage]() {
            JNIEnv *e = nullptr;
            if (g.jvm == nullptr) return;
            if (g.jvm->GetEnv(reinterpret_cast<void **>(&e), JNI_VERSION_1_6) != JNI_OK) return;
            e->CallVoidMethod(globalRef, updateTexImage);
        };
    }
    LOGI("面板纹理与 SurfaceTexture 已创建（在 VR 上下文里）：tex=%u", tex);
    return st;
}

/** 界面开始往面板 Surface 上画了，可以贴纹理了 */
extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeSetPanelActive(JNIEnv *env, jobject /* this */,
                                                       jboolean active) {
    g.panelActive = (active == JNI_TRUE);
    LOGI("面板激活状态 → %s", g.panelActive.load() ? "true" : "false");
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
