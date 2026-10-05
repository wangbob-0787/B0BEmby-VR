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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------- 函数指针表
// 按 Khronos 要求：用到的入口点都要先经 xrGetInstanceProcAddr 取一次
// （loader 需要知道应用用了哪些函数，运行时也可以覆盖实现）。
struct XrApi {
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

Mat4 fromXrMatrix(const XrMatrix4x4f &x) {
    Mat4 r{};
    memcpy(r.m, x.m, sizeof(r.m));
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
precision mediump float;
in vec2 vUv;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
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
    GLuint vbo = 0;

    // 平面放在正前方：3.2m 远，3.2m 宽（约 53° 视场），16:9
    float panelDistance = 3.2f;
    float panelWidth = 3.2f;
};

VrContext g;
std::thread gThread;
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
                                   eye.images[k].colorTexture, 0);
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

void renderEye(VrContext &c, int eyeIndex, const XrView &view) {
    EyeSwapchain &eye = c.eyes[eyeIndex];
    uint32_t imageIndex = 0;
    XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    if (XR_FAILED(api.AcquireSwapchainImage(eye.handle, &ai, &imageIndex))) return;
    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wi.timeout = XR_INFINITE_DURATION;
    if (XR_FAILED(api.WaitSwapchainImage(eye.handle, &wi))) {
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        api.ReleaseSwapchainImage(eye.handle, &ri);
        return;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, eye.fbos[imageIndex]);
    glViewport(0, 0, eye.width, eye.height);
    glClearColor(0.f, 0.f, 0.f, 1.f);   // 黑底
    glClear(GL_COLOR_BUFFER_BIT);

    // 投影 × 头姿 × 平面位置
    Mat4 proj = fromXrMatrix(view.projectionMatrix);
    Mat4 view4 = viewMatrixFromPose(view.pose);
    Mat4 model = translateScale(0.f, 0.f, -c.panelDistance, c.panelWidth, c.panelWidth * 9.f / 16.f);
    Mat4 mvp = multiply(multiply(proj, view4), model);

    glUseProgram(c.program);
    glUniformMatrix4fv(c.mvpLoc, 1, GL_FALSE, mvp.m);
    glUniform4f(c.colorLoc, 0.15f, 0.16f, 0.20f, 1.f);   // 深灰平面：先证明能出画面
    glBindBuffer(GL_ARRAY_BUFFER, c.vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void *) 0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 5 * sizeof(float),
                          (void *) (3 * sizeof(float)));
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);

    XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    api.ReleaseSwapchainImage(eye.handle, &ri);
}

void frameLoop(VrContext &c) {
    std::vector<XrCompositionLayerProjectionView> projViews(c.viewConfigs.size());
    std::vector<XrView> views(c.viewConfigs.size(), {XR_TYPE_VIEW});
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    layer.space = c.localSpace;
    layer.viewCount = (uint32_t) projViews.size();
    layer.views = projViews.data();

    int loggedFrames = 0;
    while (!gRequestStop) {
        pumpEvents(c);
        if (gRequestStop) break;

        /*
         * 会话没进入 Running 之前不能调 xrWaitFrame（会直接返回错误，
         * 那样一上来就退出循环、黑屏）。这里等 READY → xrBeginSession。
         */
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

        bool rendered = false;
        if (fs.shouldRender && c.sessionRunning) {
            XrViewLocateInfo vli{XR_TYPE_VIEW_LOCATE_INFO};
            vli.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            vli.displayTime = fs.predictedDisplayTime;
            vli.space = c.localSpace;
            XrViewState vs{XR_TYPE_VIEW_STATE};
            uint32_t viewCount = 0;
            if (XR_SUCCEEDED(api.LocateViews(c.session, &vli, &vs, (uint32_t) views.size(),
                                             &viewCount, views.data()))) {
                for (uint32_t i = 0; i < viewCount; i++) {
                    renderEye(c, (int) i, views[i]);
                    projViews[i] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
                    projViews[i].pose = views[i].pose;
                    projViews[i].fov = views[i].fov;
                    projViews[i].subImage.swapchain = c.eyes[i].handle;
                    projViews[i].subImage.imageRect.offset = {0, 0};
                    projViews[i].subImage.imageRect.extent = {c.eyes[i].width, c.eyes[i].height};
                }
                rendered = true;
                if (loggedFrames < 3) {
                    LOGI("已渲染第 %d 帧（%u 眼）", loggedFrames + 1, viewCount);
                    loggedFrames++;
                }
            }
        }

        XrFrameEndInfo fei{XR_TYPE_FRAME_END_INFO};
        fei.displayTime = fs.predictedDisplayTime;
        fei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        if (rendered) {
            fei.layerCount = 1;
            fei.layers = reinterpret_cast<const XrCompositionLayerBaseHeader *const *>(&layer);
        } else {
            fei.layerCount = 0;
            fei.layers = nullptr;
        }
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
        if (!createSwapchains(c)) break;

        c.program = buildProgram();
        c.mvpLoc = glGetUniformLocation(c.program, "uMvp");
        c.colorLoc = glGetUniformLocation(c.program, "uColor");
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
