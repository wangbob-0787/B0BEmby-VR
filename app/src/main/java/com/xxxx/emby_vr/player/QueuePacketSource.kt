package com.xxxx.emby_vr.player

import android.media.MediaFormat

/**
 * 全包档的包来源：从 [DemuxSession] 的队列取包。
 *
 * 与系统解封装（[ExtractorVideoSource]/[ExtractorAudioSource]）的区别全在"连接归谁"：
 * 连接与读线程归 Session，本类只是两条 Pipe 伸进队列的那只手 —— 没有自己的打开、定位、关闭。
 *
 * 三条硬约束（图 §2/§3）：
 *  1. [seekTo] 只代理给 Session，不许留第二条跳转入口（Pipe 自己定位就会与 Session 各动一半）；
 *  2. [close] 空实现：连接归 Session，Pipe 停的时候不许关它；
 *  3. [next] 返回 null = 本路结束（Session 写了 EOS 或已中止），**跳转清队列一律用 flush()，
 *     用 abort() 会让 get() 永久返回 null，跳一下就被当成整片播完**。
 */
class QueuePacketSource(
    private val queue: SampleQueue,
    override val format: MediaFormat?,
    private val requestSeekMs: (Long) -> Unit,
    private val originUsProvider: () -> Long = { -1L },
) : PacketSource {

    override val lastError: String? get() = null
    override val name: String get() = "avformat-全包"

    /** 落点由 Session 的统一跳转给出；这里只做转手 */
    override val startUs: Long get() = originUsProvider()

    override fun open(): Boolean = true

    override fun next(): PacketSource.Sample? {
        val p = queue.get() ?: return null
        return PacketSource.Sample(p.data, p.ptsUs)
    }

    override fun seekTo(sec: Double) {
        requestSeekMs((sec * 1000.0).toLong())
    }

    /** 只清这一侧的本地状态；队列归 Session 所有，这里一律不碰 */
    override fun flush() = Unit

    /** 连接归 Session：Pipe 退出时不许关闭它 */
    override fun close() = Unit

    override fun configureBudget(bytes: Int) = Unit
}
