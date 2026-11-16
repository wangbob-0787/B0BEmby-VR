package com.xxxx.emby_vr.player

/**
 * 从码流里解出杜比 Profile 5 的逐帧还原参数。
 *
 * 为什么必须有它：杜比 P5 的画面要先按每帧自带的映射曲线还原颜色才正确。这些曲线
 * 参数在码流里，**每一帧都可能不同**（2026-11-15 实测：同一部片相隔 12 秒取两次参数，
 * 内容不同），所以不能固定成一组常量，只能逐帧解。
 *
 * 数据在哪：杜比把 RPU 藏在类型 62 的 NAL 里（ffmpeg 源码注释原话是
 * "Dolby Vision RPUs masquerade as unregistered NALs of type 62"），
 * 跳过 2 字节 NAL 头就是 RPU 数据本身。
 *
 * 输出格式与内核交出来的那份完全一致（渲染层的 `dvLoadFromFile` 按这个读）：
 * ```
 * [0] 版本 = 1
 * [1] 通道数 = 3
 * 之后每个通道 26 个 float：
 *   +0      分段数
 *   +1..+8  分段点
 *   +9 起   每段 3 个多项式系数（段 i 从 +9+i*3 开始）
 * ```
 */
object DoviRpu {

    private const val CHANNELS = 3
    private const val PER_CHANNEL = 26
    private const val NAL_TYPE_DOVI_RPU = 62

    /**
     * 从一帧码流里解出参数。
     *
     * @param data 一帧的原始数据（MediaExtractor 给的 sample / MediaCodec 的输入缓冲）
     * @param len  有效长度
     * @return 参数数组；这一帧没有 RPU 或格式不认识时返回 null
     */
    fun parseFrame(data: ByteArray, len: Int): FloatArray? {
        val rpu = findRpu(data, len) ?: return null
        return try {
            parseRpu(rpu)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 扫 NAL 找类型 62，返回去掉防竞争字节之后的 RPU 数据。
     *
     * 两种封装都要认（2026-11-16 实测「一帧都没解出来」的根源）：
     *   · **长度前缀**：MP4/MKV 里的常见形态，每段是 4 字节大端长度 + NAL
     *   · **起始码**：Annex-B 流，每段前是 00 00 01
     * 之前只按起始码找，而解复用器交出来的是长度前缀，于是 size 几千几万的帧
     * 一个都匹配不上。
     */
    private fun findRpu(data: ByteArray, len: Int): ByteArray? {
        findLengthPrefixed(data, len)?.let { return it }
        return findStartCode(data, len)
    }

    /** 长度前缀封装：一路读 4 字节长度往下走，遇到类型 62 就取出来。 */
    private fun findLengthPrefixed(data: ByteArray, len: Int): ByteArray? {
        var p = 0
        var guard = 0
        while (p + 5 <= len && guard++ < 256) {
            val n = ((data[p].toInt() and 0xFF) shl 24) or
                ((data[p + 1].toInt() and 0xFF) shl 16) or
                ((data[p + 2].toInt() and 0xFF) shl 8) or
                (data[p + 3].toInt() and 0xFF)
            if (n <= 0 || p + 4 + n > len) return null
            val type = (data[p + 4].toInt() shr 1) and 0x3F
            if (type == NAL_TYPE_DOVI_RPU && n > 3) {
                // NAL 头 2 字节，之后是 RPU 位流（可能要再去防竞争字节）
                return stripEmulation(data, p + 6, p + 4 + n)
            }
            p += 4 + n
        }
        return null
    }

    private fun findStartCode(data: ByteArray, len: Int): ByteArray? {
        var i = 0
        while (i + 4 < len) {
            // 起始码是 00 00 01（四字节的 00 00 00 01 也走这条，多出来的 0 不影响）
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val nalStart = i + 3
                val type = (data[nalStart].toInt() shr 1) and 0x3F
                // 找这个 NAL 的结尾：下一个起始码或帧尾
                var next = nalStart + 1
                while (next + 2 < len) {
                    if (data[next].toInt() == 0 && data[next + 1].toInt() == 0 &&
                        data[next + 2].toInt() == 1
                    ) {
                        break
                    }
                    next++
                }
                val end = if (next + 2 < len) next else len
                if (type == NAL_TYPE_DOVI_RPU && end - (nalStart + 2) > 2) {
                    // 跳 2 字节 NAL 头，剩下的去掉防竞争字节才是干净的 RPU 位流
                    return stripEmulation(data, nalStart + 2, end)
                }
                i = if (end > i) end else i + 3
            } else {
                i++
            }
        }
        return null
    }

    /** 去掉 00 00 03 里的 03（编码器为防止起始码被误认为 NAL 边界而插的）。 */
    private fun stripEmulation(data: ByteArray, from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        var o = 0
        var zeros = 0
        for (i in from until to) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out[o++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.copyOf(o)
    }

    /** 解析 RPU 位流，取出三个通道的分段多项式。 */
    private fun parseRpu(rpu: ByteArray): FloatArray? {
        val r = BitReader(rpu)

        val rpuType = r.u(6)
        if (rpuType != 2) return null
        val rpuFormat = r.u(11)
        r.u(4)                                  // vdr_rpu_profile
        r.u(4)                                  // vdr_rpu_level
        val seqInfo = r.bit()

        var blBitDepth = 10
        var coeffDenomLen = 0
        if (seqInfo != 0) {
            r.bit()                             // chroma_resampling_explicit_filter_flag
            val coeffDataType = r.u(2)
            if (coeffDataType == 0) {
                coeffDenomLen = r.ue()
            } else if (coeffDataType == 1) {
                coeffDenomLen = 32
            } else {
                return null
            }
            r.u(2)                              // vdr_rpu_normalized_idc
            r.bit()                             // bl_video_full_range_flag
            if (rpuFormat and 0x700 == 0) {
                blBitDepth = r.ue() + 8
                val elDepth = r.ue()
                r.u(5)                          // ext_mapping_idc 低 5 位
                r.u(3)                          // ext_mapping_idc 高 3 位
                r.ue()                          // vdr_bit_depth_minus8
                r.bit()                         // spatial_resampling_filter_flag
                r.u(3)                          // reserved_zero_3bits
                r.bit()                         // el_spatial_resampling_filter_flag
                r.bit()                         // disable_residual_flag
                if (elDepth <= 0) return null
            }
        }
        r.bit()                                 // vdr_dm_metadata_present_flag
        if (r.bit() != 0) r.ue()                // use_prev_vdr_rpu_flag → prev_vdr_rpu_id

        /*
         * 到这里是 rpu_data_mapping。分段点按「底图层位深」给，系数按 coefficient_log2_denom_len 给。
         */
        r.ue()                                  // vdr_rpu_id
        r.ue()                                  // mapping_color_space
        r.ue()                                  // mapping_chroma_format_idc

        val pivots = Array(CHANNELS) { IntArray(0) }
        for (c in 0 until CHANNELS) {
            val n = r.ue() + 2                  // num_pivots_minus2
            if (n < 2 || n > 9) return null
            val p = IntArray(n)
            for (i in 0 until n) p[i] = r.v(blBitDepth)
            pivots[c] = p
        }

        // Profile 7 才有的 NLQ 段；P5 走到这里时 disable_residual_flag 为真，跳过
        if (rpuFormat and 0x700 == 0) {
            // 该分支只在有残留层时才有内容，P5 不进入；这里保守地不做任何读取
        }

        r.ue()                                  // num_x_partitions_minus1
        r.ue()                                  // num_y_partitions_minus1

        val out = FloatArray(2 + CHANNELS * PER_CHANNEL)
        out[0] = 1f
        out[1] = CHANNELS.toFloat()

        val maxPivots = (pivots.maxOfOrNull { it.size } ?: 0).coerceAtMost(9)
        for (c in 0 until CHANNELS) {
            val base = 2 + c * PER_CHANNEL
            val p = pivots[c]
            val pieces = p.size - 1
            out[base] = p.size.toFloat()
            for (i in 0 until maxPivots.coerceAtMost(8)) {
                out[base + 1 + i] =
                    if (i < p.size) (p[i].toFloat() / ((1 shl blBitDepth) - 1).toFloat())
                    else 1e9f
            }
            for (seg in 0 until pieces) {
                val idc = r.ue()
                if (idc != 0) return null      // MMR 暂不支持，P5 实际用的是多项式
                // 多项式：order_minus1 + 三个系数（带符号，精度由 coefficient_log2_denom_len 决定）
                val order = r.ue()
                if (order > 2) return null
                val c0 = signedVar(r, coeffDenomLen)
                val c1 = signedVar(r, coeffDenomLen)
                var c2 = 0.0f
                if (order >= 1) c2 = signedVar(r, coeffDenomLen)
                val o = base + 9 + seg * 3
                out[o] = c0
                out[o + 1] = c1
                out[o + 2] = c2
            }
        }
        return out
    }

    /** 带符号的定长整数：最高位是符号。 */
    private fun signedVar(r: BitReader, bits: Int): Float {
        if (bits <= 0) return 0f
        val raw = r.v(bits)
        val signBit = 1 shl (bits - 1)
        val v = if (raw and signBit != 0) raw - (1 shl bits) else raw
        return v.toFloat() / (1L shl bits).toFloat()
    }

    /** 大端位读取器（杜比的位流就是 MSB 优先）。 */
    private class BitReader(private val buf: ByteArray) {
        private var pos = 0

        fun bit(): Int {
            val byteIdx = pos ushr 3
            if (byteIdx >= buf.size) throw IndexOutOfBoundsException("位流已读完")
            val b = (buf[byteIdx].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return b
        }

        fun u(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        fun v(n: Int): Int = u(n)

        /** 无符号指数哥伦布。 */
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                if (zeros > 31) throw IllegalStateException("ue 过长")
            }
            var v = 1
            repeat(zeros) { v = (v shl 1) or bit() }
            return v - 1
        }
    }
}
