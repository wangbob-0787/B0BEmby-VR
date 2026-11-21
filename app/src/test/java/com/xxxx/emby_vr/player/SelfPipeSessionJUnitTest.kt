package com.doudou.p5.sm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 把豆豆那 11 条断言接进 Gradle 测试任务（2026-11-21）。
 *
 * 这样每次编译都会跑一遍；「标志没交还 / 排队目标丢了 / 闸门没开 / 强制放行没起音频」
 * 这几类回归会在编译阶段就红，不用等装到头显上才发现。
 */
class SelfPipeSessionJUnitTest {
    @Test
    fun allAssertionsPass() {
        val failed = runAll()
        assertEquals("状态机断言有失败（详见 stdout 的 FAIL 行）", 0, failed)
    }
}
