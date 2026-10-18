package com.tencent.qqlive.ck;

/**
 * 借用腾讯客户端的签名小库（2026-10-18 父亲定「走签名路线」）。
 *
 * 背景：腾讯视频网页端把整片切成十几秒一段、一段一签，无法快进；
 * 客户端走的是另一个接口（三元组：checktime → ckey → getvkey），返回的是
 * 每十分钟一段、可任意偏移的地址，能快进能跳。要拿到那条路必须算出 ckey，
 * 而 ckey 的算法在官方客户端的 libckeygenerator.so 里。
 *
 * 做法（父亲 2026-10-18 拍板）：**只借这一个算签名的小库**，界面、请求、
 * 播放全是我们自己的；库不进公开仓库，装机时推进 APK 再签。
 *
 * 下面是按官方客户端里同名类（`com.tencent.qqlive.ck.CKeyFacade`）的
 * native 声明**照抄**的最小门面：类名/方法名必须与库里的导出符号一模一样
 * （`Java_com_tencent_qqlive_ck_CKeyFacade_GenCKey` 等），换名字链接就找不到。
 * 参数顺序来自官方类的 `getCKey(...)` 包装（`(i>>8)&255` = 81 是签名版本号，
 * `i&255` = 1 是命令号；20737 = 0x5101）。
 */
public class CKeyFacade {

    static {
        System.loadLibrary("ckeygenerator");
    }

    /** 算 ckey 主体：返回 UTF-8 字节数组。 */
    public static native byte[] GenCKey(String guid, long time, String vid,
                                        int encryptVerHi, int platform, int cmd,
                                        String s3, String s4, String appVer, String sdtfrom,
                                        int[] extra, int extraLen);

    /** 库自报版本，用来确认库真的加载起来了。 */
    public static native String getCKeyVersion();

    private static native String getFlag();

    /** 库自己的服务器配置（可空）。 */
    public static native int SetIpPort(String host, int port, String host6, int port6);

    /**
     * 初始化；官方类的 init 方法是这么调的（三个字符串 + Context）：
     * `CkeyMoudleInit(应用私有目录, guid, "", context)`。
     */
    public native void CkeyMoudleInit(String a, String b, String c, android.content.Context ctx);

    private native void CkeyMoudleUnit();

    /**
     * 注册设备信息；官方调用形状：
     * `Registration(<设备标识>, <版本>, <SDK版本>, <平台号>, <设备标识>, context)`。
     */
    public native int Registration(String a, String b, String c, int platform, String d,
                                   android.content.Context ctx);

    /**
     * 按官方客户端 tvkplayer 那一路的参数形状算一次 ckey。
     *
     * 官方调用：`CKeyFacade.getCKey(guid, serverTime, vid, appVer, platform, sdtfrom, "", "")`
     * → `GenCKey(guid, time, vid, 81, platform, 1, "", "", appVer, sdtfrom, null, 0)`。
     */
    public static String ckey(String guid, long time, String vid,
                              int platform, String appVer, String sdtfrom) {
        byte[] r = GenCKey(guid, time, vid, 81, platform, 1, "", "", appVer, sdtfrom, null, 0);
        if (r == null) return "";
        try {
            return new String(r, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
