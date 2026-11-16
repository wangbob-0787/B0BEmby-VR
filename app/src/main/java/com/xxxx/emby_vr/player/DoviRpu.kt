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
    /**
     * 每通道占多少 float：1 个分段数 + 8 个分段点 + 8 段各 3 个系数 = 33。
     *
     * 这里原来写 26 —— 装不下 8 段系数，分段数到 7 以上时第三通道直接数组越界，
     * 异常被上层吞掉，结果「参数一帧都没写出去」，着色器自然还原不了。
     */
    private const val PER_CHANNEL = 33
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

    /**
     * 解析 RPU 位流，取出三个通道的分段多项式。
     *
     * 字段顺序与取位规则全部照 ffmpeg 的 `dovi_rpudec.c`（我们的内核就是用它还原的，
     * 颜色正确）。三处容易写错、且都已经在 2026-11-16 的离线验证里踩过：
     *
     *  1. 位流第一个字节是 **0x19 前缀**（ffmpeg 的 `VALIDATE(rpu[0], 25, 25)`），
     *     必须跳过它再读，否则 rpu_type 会读成 6，整段错位。
     *  2. `ext_mapping_idc` **不是独立字段**，它藏在 `el_bit_depth_minus8` 的高 8 位里。
     *  3. 分段点是**差分**的，每读一个要累加；系数是「有符号指数哥伦布 + 定长小数位」，
     *     不是定长有符号整数。
     */
    private fun parseRpu(rpu: ByteArray): FloatArray? {
        // 0x19 前缀：没有它说明这不是我们认识的 RPU，直接放弃
        if (rpu.isEmpty() || (rpu[0].toInt() and 0xFF) != 0x19) return null

        val r = BitReader(rpu, 8)

        val rpuType = r.u(6)
        if (rpuType != 2) return null
        val rpuFormat = r.u(11)
        r.u(4)                                  // vdr_rpu_profile
        r.u(4)                                  // vdr_rpu_level
        val seqInfo = r.bit()

        var blBitDepth = 10
        var coeffDenomLen = 0
        if (seqInfo == 0) return null           // ffmpeg 对缺 seq info 的流直接不支持
        r.bit()                                 // chroma_resampling_explicit_filter_flag
        val coeffDataType = r.u(2)
        when (coeffDataType) {
            0 -> coeffDenomLen = r.ue()
            1 -> coeffDenomLen = 32             // 浮点系数，分母取满精度
            else -> return null
        }
        if (coeffDenomLen <= 0 || coeffDenomLen > 32) return null
        r.u(2)                                  // vdr_rpu_normalized_idc
        r.bit()                                 // bl_video_full_range_flag
        if (rpuFormat and 0x700 != 0) return null   // P7/P8 的格式，这条路不处理
        blBitDepth = r.ue() + 8
        val elRaw = r.ue()
        val extMappingIdc = elRaw ushr 8
        val elDepthMinus8 = elRaw and 0xFF
        r.ue()                                  // vdr_bit_depth_minus8
        r.bit()                                 // spatial_resampling_filter_flag
        r.u(3)                                  // dm_compression
        r.bit()                                 // el_spatial_resampling_filter_flag
        val disableResidual = r.bit()
        if (blBitDepth !in 8..16 || elDepthMinus8 !in 0..8 || extMappingIdc > 0xFF) return null

        val dmMetaPresent = r.bit()
        if (r.bit() != 0) r.ue()                // use_prev_vdr_rpu → prev_vdr_rpu_id
        if (disableResidual == 0) return null   // 有残留层（P7）不在这条路上处理

        r.ue()                                  // vdr_rpu_id
        r.ue()                                  // mapping_color_space
        r.ue()                                  // mapping_chroma_format_idc

        val maxPivots = 9
        val pivots = Array(3) { IntArray(0) }
        for (c in 0 until 3) {
            val n = r.ue() + 2
            if (n !in 2..maxPivots) return null
            val p = IntArray(n)
            var acc = 0
            for (i in 0 until n) {
                acc += r.u(blBitDepth)          // 差分累加
                p[i] = acc
            }
            pivots[c] = p
        }

        r.ue()                                  // num_x_partitions_minus1
        r.ue()                                  // num_y_partitions_minus1

        val out = FloatArray(2 + CHANNELS * PER_CHANNEL)
        out[0] = 1f
        out[1] = CHANNELS.toFloat()
        val scale = ((1 shl blBitDepth) - 1).toFloat()

        for (c in 0 until 3) {
            val base = 2 + c * PER_CHANNEL
            val p = pivots[c]
            val pieces = p.size - 1
            out[base] = p.size.toFloat()
            for (i in 0 until 8) {
                out[base + 1 + i] = if (i < p.size) p[i] / scale else 1e9f
            }
            for (seg in 0 until pieces) {
                val idc = r.ue()
                if (idc != 0) return null       // 多项式之外（MMR）不走这条路
                val orderMinus1 = r.ue()
                if (orderMinus1 > 1) return null
                if (orderMinus1 == 0 && r.bit() != 0) return null   // 线性插值标志，未支持
                // 多项式阶 = orderMinus1 + 1；系数个数 = 阶 + 1（最多 3 个）
                val nCoef = orderMinus1 + 2
                val co = FloatArray(3)
                for (k in 0 until nCoef.coerceAtMost(3)) {
                    co[k] = seCoef(r, coeffDenomLen)
                }
                val o = base + 9 + seg * 3
                out[o] = co[0]
                out[o + 1] = co[1]
                out[o + 2] = co[2]
            }
        }
        return out
    }

    /**
     * 有符号系数：指数哥伦布给整数部分，再跟 `denom` 位小数，整体按 2^denom 缩放。
     * 与 ffmpeg 的 `get_se_coef` 一致。
     */
    private fun seCoef(r: BitReader, denom: Int): Float {
        val ipart = r.se()
        val fpart = if (denom > 0) r.v(denom.toLong()) else 0L
        val v = (ipart shl denom) or fpart
        return v.toFloat() / (1L shl denom).toFloat()
    }

    /** 大端位读取器（杜比的位流就是 MSB 优先）。 */
    private class BitReader(private val buf: ByteArray, startBit: Int = 0) {
        private var pos = startBit

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

        /** 定长取值，最长 32 位（系数的小数位可能一次给到 32 位）。 */
        fun v(n: Long): Long {
            var v = 0L
            var left = n
            while (left > 0) {
                val take = if (left > 24) 24 else left.toInt()
                v = (v shl take) or (u(take).toLong() and ((1L shl take) - 1))
                left -= take
            }
            return v
        }

        /** 无符号指数哥伦布。 */
        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                if (zeros > 31) throw IllegalStateException("ue 过长")
            }
            var v = 1L
            repeat(zeros) { v = (v shl 1) or bit().toLong() }
            return (v - 1).toInt()
        }

        /** 有符号指数哥伦布：偶数取负、奇数取正（ffmpeg 的 get_se_golomb）。 */
        fun se(): Long {
            val k = ue().toLong()
            return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2)
        }
    }
}
