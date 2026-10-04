package com.xxxx.emby_vr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.xxxx.emby_vr.util.ErrorHandler
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Hashtable

object QrCodeUtils {
    fun generateQrCode(content: String, size: Int): Bitmap? {
        try {
            val hints = Hashtable<EncodeHintType, Any>()
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.MARGIN] = 1

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (bitMatrix[x, y]) {
                        pixels[y * width + x] = Color.BLACK
                    } else {
                        pixels[y * width + x] = Color.WHITE
                    }
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        } catch (e: Exception) {
            ErrorHandler.logError("QrCodeUtils", "生成二维码失败", e)
        }
        return null
    }

    /**
     * 取本机局域网地址(扫码配置用)。
     * 原实现返回"第一个非回环 IPv4",在投影/电视上会命中 WiFi-Direct 虚拟网卡 p2p0(192.168.82.x),
     * 导致二维码地址在局域网内不可达。现改为:排除虚拟网卡 → 优先 wlan/eth/ap。
     */
    fun getLocalIpAddress(): String? {
        val virtualPrefixes = listOf("p2p", "rmnet", "dummy", "tun", "ppp", "clat", "sit", "ip6tnl")
        val preferredPrefixes = listOf("wlan", "eth", "ap")
        try {
            val candidates = mutableListOf<Pair<String, String>>()
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val name = intf.name.lowercase()
                if (!intf.isUp || intf.isLoopback) continue
                if (virtualPrefixes.any { name.startsWith(it) }) continue
                for (a in intf.inetAddresses) {
                    if (!a.isLoopbackAddress && a is Inet4Address) {
                        candidates.add(name to (a.hostAddress ?: continue))
                        break
                    }
                }
            }
            preferredPrefixes.forEach { p ->
                candidates.firstOrNull { it.first.startsWith(p) }?.let { return it.second }
            }
            candidates.firstOrNull()?.let { return it.second }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }
}
