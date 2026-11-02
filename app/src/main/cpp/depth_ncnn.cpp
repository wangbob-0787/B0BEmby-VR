/*
 * ncnn 深度推理（2026-11-02 接入）。
 *
 * 为什么另开一条后端：TFLite 在 CPU 上跑 ZipDepth 稳定在 110ms 上下，而参考的
 * 手机实时方案（tachi）用 ncnn + Vulkan 在 266~392 宽度上只要 18~33ms。两条路
 * 互不干扰，靠调参 depth_backend 切换，切换时上层重建、不重装。
 *
 * 模型：Depth Anything V2 Small（Apache-2.0），ncnn 格式由社区仓库导出，
 * 输入尺寸在代码里定（模型是动态输入，patch 是 14，取 14 的倍数）。
 *
 * 归一化口径与参考实现一致：ImageNet 均值方差，输出是"相对逆深度"（越大越近），
 * 极性翻转与归一化留在 Kotlin 侧，和 TFLite 那条路共用同一段后处理。
 */
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

#include <ncnn/net.h>

#define NCNN_TAG "B0BEmbyVR"
#define NCNN_LOGI(...) __android_log_print(ANDROID_LOG_INFO, NCNN_TAG, __VA_ARGS__)
#define NCNN_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, NCNN_TAG, __VA_ARGS__)

namespace {

struct NcnnDepth {
    ncnn::Net net;
    std::mutex mu;
    bool ready = false;
    bool vulkan = false;
    int inW = 0;
    int inH = 0;
    int outW = 0;
    int outH = 0;
    std::string outName;
};

NcnnDepth gNcnn;

/** 从 assets 读文本文件（.param 是纯文本，几十 KB） */
bool readAssetText(AAssetManager *mgr, const char *name, std::string *out) {
    AAsset *a = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
    if (a == nullptr) return false;
    const off64_t len = AAsset_getLength64(a);
    out->resize((size_t) len);
    const int got = AAsset_read(a, out->data(), (size_t) len);
    AAsset_close(a);
    return got == (int) len;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeNcnnInit(JNIEnv *env, jobject /*thiz*/,
                                                 jobject assetManager,
                                                 jstring paramName, jstring binName,
                                                 jboolean useVulkan,
                                                 jint inW, jint inH) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        NCNN_LOGE("ncnn：拿不到 assets 管理器");
        return -1;
    }
    const char *param = env->GetStringUTFChars(paramName, nullptr);
    const char *bin = env->GetStringUTFChars(binName, nullptr);
    if (param == nullptr || bin == nullptr) {
        if (param) env->ReleaseStringUTFChars(paramName, param);
        if (bin) env->ReleaseStringUTFChars(binName, bin);
        return -2;
    }

    std::lock_guard<std::mutex> lock(gNcnn.mu);
    gNcnn.ready = false;
    gNcnn.net.clear();

    std::string paramText;
    if (!readAssetText(mgr, param, &paramText)) {
        NCNN_LOGE("ncnn：读不到模型结构 %s", param);
        env->ReleaseStringUTFChars(paramName, param);
        env->ReleaseStringUTFChars(binName, bin);
        return -3;
    }
    /* AASSET_MODE_BUFFER：ncnn 的读取器要能来回 seek，流式模式拿不到 */
    AAsset *binAsset = AAssetManager_open(mgr, bin, AASSET_MODE_BUFFER);
    if (binAsset == nullptr) {
        NCNN_LOGE("ncnn：读不到模型权重 %s", bin);
        env->ReleaseStringUTFChars(paramName, param);
        env->ReleaseStringUTFChars(binName, bin);
        return -4;
    }

    /*
     * Vulkan 是这条后端的卖点，但建不起来（驱动/显存/机型不支持）就退回 CPU，
     * 不能让整个 3D 链路跟着挂掉。
     */
    gNcnn.vulkan = useVulkan == JNI_TRUE;
    gNcnn.net.opt.use_vulkan_compute = gNcnn.vulkan;
    gNcnn.net.opt.num_threads = 4;
    gNcnn.net.opt.lightmode = true;

    int rc = gNcnn.net.load_param_mem(paramText.c_str());
    if (rc == 0) rc = gNcnn.net.load_model(binAsset);
    AAsset_close(binAsset);
    env->ReleaseStringUTFChars(paramName, param);
    env->ReleaseStringUTFChars(binName, bin);

    if (rc != 0) {
        NCNN_LOGE("ncnn：模型加载失败 rc=%d（%s）", rc, gNcnn.vulkan ? "Vulkan" : "CPU");
        if (gNcnn.vulkan) {
            /* Vulkan 建不起来时整份重来一次，改走 CPU */
            gNcnn.vulkan = false;
            gNcnn.net.opt.use_vulkan_compute = false;
            gNcnn.net.clear();
            NCNN_LOGE("ncnn：Vulkan 加载失败，退回 CPU");
        }
        return -5;
    }

    const std::vector<const char *> &names = gNcnn.net.output_names();
    if (!names.empty() && names.back() != nullptr) gNcnn.outName = names.back();
    gNcnn.inW = inW;
    gNcnn.inH = inH;
    gNcnn.ready = true;
    NCNN_LOGI("ncnn：模型就绪 输入 %dx%d 输出 %s 后端 %s", inW, inH,
              gNcnn.outName.empty() ? "(未知)" : gNcnn.outName.c_str(),
              gNcnn.vulkan ? "Vulkan" : "CPU");
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeNcnnInfer(JNIEnv *env, jobject /*thiz*/,
                                                   jbyteArray rgba, jint w, jint h) {
    std::lock_guard<std::mutex> lock(gNcnn.mu);
    if (!gNcnn.ready || rgba == nullptr || w <= 0 || h <= 0) return nullptr;

    const jsize len = env->GetArrayLength(rgba);
    if (len < w * h * 4) return nullptr;

    jbyte *src = env->GetByteArrayElements(rgba, nullptr);
    if (src == nullptr) return nullptr;

    /* 缩放到模型输入：RGBA → RGB，双线性。预处理在 C++ 做，比 Kotlin 逐像素快一个量级 */
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(reinterpret_cast<const unsigned char *>(src),
                                                 ncnn::Mat::PIXEL_RGBA2RGB, w, h,
                                                 gNcnn.inW, gNcnn.inH);
    env->ReleaseByteArrayElements(rgba, src, JNI_ABORT);

    const float meanVals[3] = {123.675f, 116.28f, 103.53f};
    const float normVals[3] = {0.01712475f, 0.017507f, 0.01742919f};
    in.substract_mean_normalize(meanVals, normVals);

    /* Vulkan 开关在 Net 的 opt 上设，Extractor 没有这个接口（编译期实测） */
    ncnn::Extractor ex = gNcnn.net.create_extractor();
    ex.input("image", in);
    ncnn::Mat out;
    int rc = ex.extract(gNcnn.outName.empty() ? "output" : gNcnn.outName.c_str(), out);
    if (rc != 0) {
        NCNN_LOGE("ncnn：推理失败 rc=%d", rc);
        return nullptr;
    }
    /* 输出可能是多通道（不同尺度），只要第一通道 */
    ncnn::Mat first = out.channel(0);
    const int ow = first.w;
    const int oh = first.h;
    if (ow <= 0 || oh <= 0) return nullptr;
    gNcnn.outW = ow;
    gNcnn.outH = oh;

    jfloatArray result = env->NewFloatArray(ow * oh);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, ow * oh, static_cast<const jfloat *>(first.data));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xxxx_emby_1vr_vr_VrNative_nativeNcnnRelease(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gNcnn.mu);
    gNcnn.ready = false;
    gNcnn.net.clear();
    NCNN_LOGI("ncnn：模型已释放");
}
