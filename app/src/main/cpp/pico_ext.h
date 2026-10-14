#pragma once
/*
 * PICO 专有 OpenXR 扩展的最小定义（B0BEmby VR，2026-10-14）。
 *
 * 为什么不直接 include PICO 的 openxr.h：
 *   本工程编译时用的是官方 Khronos loader 自带的 openxr.h（gradle 里是
 *   org.khronos.openxr:openxr_loader_for_android:1.1.49，纯官方头，不含厂商扩展），
 *   而 PICO 的 openxr.h 是超集（1.1.53 + 全部 PICO 扩展）。两份头同时进 include
 *   路径会让 XrStructureType / XrResult 这些公共类型重定义，而且云端 CI 只拿到本
 *   仓库、拿不到 kernel/OpenXR_Native_SDK。所以这里只把用到的扩展抄过来，
 *   仓库自包含。
 *
 * 抄录来源（逐字核对，PICO Native XR SDK 3.0.0）：
 *   kernel/OpenXR_Native_SDK/OpenXR/openxr/openxr.h
 *     · XR_FB_swapchain_update_state ... 行 3003-3020
 *     · XR_FB_foveation ................ 行 4120-4168
 *     · XR_FB_foveation_configuration .. 行 4170-4198
 *     · XR_PICO_layer_settings ......... 行 8917-8938
 *     · XR_PICO_adaptive_resolution .... 行 9610-9630
 * 规范：The OpenXR 1.1.53 Specification (with all PICO extensions)
 *   https://lf3-static.bytednsdoc.com/obj/eden-cn/sliheh7phbozbps/NativeSDK/3.0.0/NativeSdkSpec.html
 * 设备支持矩阵：https://developer.picoxr.com/document/native/about-extensions/
 *
 * 每个扩展块用官方同名的 include guard 包住：万一将来换了带这些扩展的头文件，
 * 以先定义者为准，不会重定义冲突。
 */

#include <openxr/openxr.h>

/* ============================ XR_FB_swapchain_update_state ====================
 * 运行期改交换链状态（注视点就靠它下发）。PICO 支持 Meta 这套扩展。
 */
#ifndef XR_FB_swapchain_update_state
#define XR_FB_swapchain_update_state 1
#define XR_FB_SWAPCHAIN_UPDATE_STATE_EXTENSION_NAME "XR_FB_swapchain_update_state"

typedef struct XrSwapchainStateBaseHeaderFB {
    XrStructureType type;
    void *next;
} XrSwapchainStateBaseHeaderFB;

typedef XrResult (XRAPI_PTR *PFN_xrUpdateSwapchainFB)(
        XrSwapchain swapchain,
        const XrSwapchainStateBaseHeaderFB *state);
#endif /* XR_FB_swapchain_update_state */

/* ============================ XR_FB_foveation =================================
 * 固定注视点渲染：视野中心全分辨率，边缘降采样。省 GPU。
 */
#ifndef XR_FB_foveation
#define XR_FB_foveation 1
#define XR_FB_FOVEATION_EXTENSION_NAME "XR_FB_foveation"

XR_DEFINE_HANDLE(XrFoveationProfileFB)

static const XrStructureType XR_TYPE_FOVEATION_PROFILE_CREATE_INFO_FB = (XrStructureType) 1000114000;
static const XrStructureType XR_TYPE_SWAPCHAIN_STATE_FOVEATION_FB = (XrStructureType) 1000114002;

typedef XrFlags64 XrSwapchainCreateFoveationFlagsFB;
static const XrSwapchainCreateFoveationFlagsFB XR_SWAPCHAIN_CREATE_FOVEATION_SCALED_BIN_BIT_FB = 0x00000001;
static const XrSwapchainCreateFoveationFlagsFB XR_SWAPCHAIN_CREATE_FOVEATION_FRAGMENT_DENSITY_MAP_BIT_FB = 0x00000002;

typedef XrFlags64 XrSwapchainStateFoveationFlagsFB;

typedef struct XrFoveationProfileCreateInfoFB {
    XrStructureType type;
    void *next;
} XrFoveationProfileCreateInfoFB;

typedef struct XrSwapchainStateFoveationFB {
    XrStructureType type;
    void *next;
    XrSwapchainStateFoveationFlagsFB flags;
    XrFoveationProfileFB profile;
} XrSwapchainStateFoveationFB;

typedef XrResult (XRAPI_PTR *PFN_xrCreateFoveationProfileFB)(
        XrSession session,
        const XrFoveationProfileCreateInfoFB *createInfo,
        XrFoveationProfileFB *profile);

typedef XrResult (XRAPI_PTR *PFN_xrDestroyFoveationProfileFB)(XrFoveationProfileFB profile);
#endif /* XR_FB_foveation */

/* ============================ XR_FB_foveation_configuration ===================
 * 注视点等级配置（XR_FB_foveation 的配套扩展）。
 */
#ifndef XR_FB_foveation_configuration
#define XR_FB_foveation_configuration 1
#define XR_FB_FOVEATION_CONFIGURATION_EXTENSION_NAME "XR_FB_foveation_configuration"

static const XrStructureType XR_TYPE_FOVEATION_LEVEL_PROFILE_CREATE_INFO_FB = (XrStructureType) 1000115000;

typedef enum XrFoveationLevelFB {
    XR_FOVEATION_LEVEL_NONE_FB = 0,
    XR_FOVEATION_LEVEL_LOW_FB = 1,
    XR_FOVEATION_LEVEL_MEDIUM_FB = 2,
    XR_FOVEATION_LEVEL_HIGH_FB = 3,
    XR_FOVEATION_LEVEL_MAX_ENUM_FB = 0x7FFFFFFF
} XrFoveationLevelFB;

typedef enum XrFoveationDynamicFB {
    XR_FOVEATION_DYNAMIC_DISABLED_FB = 0,
    XR_FOVEATION_DYNAMIC_LEVEL_ENABLED_FB = 1,
    XR_FOVEATION_DYNAMIC_MAX_ENUM_FB = 0x7FFFFFFF
} XrFoveationDynamicFB;

typedef struct XrFoveationLevelProfileCreateInfoFB {
    XrStructureType type;
    void *next;
    XrFoveationLevelFB level;
    float verticalOffset;
    XrFoveationDynamicFB dynamic;
} XrFoveationLevelProfileCreateInfoFB;
#endif /* XR_FB_foveation_configuration */

/* ============================ XR_PICO_layer_settings ============================
 * 合成层增强：超采样 / 锐化 / 超分辨率。挂在合成层的 next 链上，由合成器执行。
 * 约束（官方文档）：同一合成层只能开启其中一种。
 *   https://developer-cn.picoxr.com/document/unity/rendering/enable-supersampling-sharpening-super-resolution-for-composition-layers/
 */
#ifndef XR_PICO_layer_settings
#define XR_PICO_layer_settings 1
#define XR_PICO_LAYER_SETTINGS_EXTENSION_NAME "XR_PICO_layer_settings"

static const XrStructureType XR_TYPE_LAYER_SETTINGS_PICO = (XrStructureType) 1010004000;

typedef XrFlags64 XrLayerSettingsFlagsPICO;

static const XrLayerSettingsFlagsPICO XR_LAYER_SETTINGS_FIXED_FOVEATED_NORMAL_SUPER_SAMPLING_BIT_PICO = 0x00000001;
static const XrLayerSettingsFlagsPICO XR_LAYER_SETTINGS_FIXED_FOVEATED_QUALITY_SUPER_SAMPLING_BIT_PICO = 0x00000002;
static const XrLayerSettingsFlagsPICO XR_LAYER_SETTINGS_FIXED_FOVEATED_NORMAL_SHARPENING_BIT_PICO = 0x00000004;
static const XrLayerSettingsFlagsPICO XR_LAYER_SETTINGS_FIXED_FOVEATED_QUALITY_SHARPENING_BIT_PICO = 0x00000008;
static const XrLayerSettingsFlagsPICO XR_LAYER_SETTINGS_SELF_ADAPTIVE_NORMAL_SHARPENING_BIT_PICO = 0x00000010;
static const XrLayerSettingsFlagsPICO XR_LAYER_SETTINGS_SELF_ADAPTIVE_QUALITY_SHARPENING_BIT_PICO = 0x00000020;
static const XrLayerSettingsFlagsPICO XR_LAYER_SETTINGS_SUPER_RESOLUTION_BIT_PICO = 0x00000040;
static const XrLayerSettingsFlagsPICO XR_LAYER_SETTINGS_NEUR_SUPER_RESOLUTION_BIT_PICO = 0x00000080;

/* extends XrCompositionLayerBaseHeader（挂在合成层的 next 上） */
typedef struct XrLayerSettingsPICO {
    XrStructureType type;
    const void *next;
    XrLayerSettingsFlagsPICO layerFlags;
} XrLayerSettingsPICO;
#endif /* XR_PICO_layer_settings */

/* ============================ XR_PICO_adaptive_resolution =====================
 * 自适应分辨率：把当前渲染分辨率与偏好交给运行时，运行时回一份建议值。
 */
#ifndef XR_PICO_adaptive_resolution
#define XR_PICO_adaptive_resolution 1
#define XR_PICO_ADAPTIVE_RESOLUTION_EXTENSION_NAME "XR_PICO_adaptive_resolution"

typedef enum XrAdaptiveResolutionSettingPICO {
    XR_ADAPTIVE_RESOLUTION_SETTING_HIGH_QUALITY_PICO = 0,
    XR_ADAPTIVE_RESOLUTION_SETTING_BALANCED_PICO = 1,
    XR_ADAPTIVE_RESOLUTION_SETTING_POWER_SAVING_PICO = 2,
    XR_ADAPTIVE_RESOLUTION_SETTING_MAX_ENUM_PICO = 0x7FFFFFFF
} XrAdaptiveResolutionSettingPICO;

typedef XrResult (XRAPI_PTR *PFN_xrUpdateAdaptiveResolutionPICO)(
        XrSession session,
        XrAdaptiveResolutionSettingPICO setting,
        XrExtent2Di currentResolution,
        XrExtent2Di *lastResolution);
#endif /* XR_PICO_adaptive_resolution */
