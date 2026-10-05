/*
 * B0BEmby VR —— OpenXR 原生层（第一步：地基）
 *
 * 2026-10-05：父亲要求把播放界面做成 VR 原生（PICO 影院模式）。
 * 走官方 OpenXR 路线的第一步先验证「原生编译链路 + 官方 loader 能链上」，
 * 所以这里暂时只有探针函数，随后再替换成真正的立体渲染与会话循环。
 *
 * 依据：
 *  - PICO OS ≥ 5.9 起支持 Khronos 官方 OpenXR SDK 与标准 Android loader
 *    https://developer.picoxr.com/zh/blog/muz6s63x/
 *  - 本机实测系统版本 5.13.7（adb getprop ro.build.display.id）
 *  - loader prefab 包：org.khronos.openxr:openxr_loader_for_android
 */
#include <cstdio>
#include <jni.h>
#include <android/log.h>
#include <openxr/openxr.h>

#define LOG_TAG "B0BEmbyVR-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeProbe(JNIEnv *env, jobject /* this */) {
    char buf[192];
    // XR_CURRENT_API_VERSION 来自 loader 的头文件；能编译通过就说明 prefab 头文件到位。
    snprintf(
            buf,
            sizeof(buf),
            "OpenXR 头文件版本 %u.%u.%u（loader prefab 链接成功）",
            XR_VERSION_MAJOR(XR_CURRENT_API_VERSION),
            XR_VERSION_MINOR(XR_CURRENT_API_VERSION),
            XR_VERSION_PATCH(XR_CURRENT_API_VERSION));
    LOGI("%s", buf);
    return env->NewStringUTF(buf);
}
