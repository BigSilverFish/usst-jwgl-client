package cn.edu.usst.jwgl.data.local

import cn.edu.usst.jwgl.data.model.RollcallAttendanceStats
import org.junit.Assert.*
import org.junit.Test

class RollcallAutoSignStrategyTest {

    @Test
    fun testStrategyConstantsAndDefaults() {
        assertEquals("count_10", TronClassPreferences.DEFAULT_AUTO_SIGN_STRATEGY)
        assertEquals("no_delay", TronClassPreferences.STRATEGY_NO_DELAY)
        assertEquals("delay_10s", TronClassPreferences.STRATEGY_DELAY_10S)
        assertEquals("delay_30s", TronClassPreferences.STRATEGY_DELAY_30S)
        assertEquals("count_1", TronClassPreferences.STRATEGY_COUNT_1)
        assertEquals("count_10", TronClassPreferences.STRATEGY_COUNT_10)
        assertEquals("half_class", TronClassPreferences.STRATEGY_HALF_CLASS)
        assertEquals("custom_time", TronClassPreferences.STRATEGY_CUSTOM_TIME)
        assertEquals("custom_ratio", TronClassPreferences.STRATEGY_CUSTOM_RATIO)
    }

    @Test
    fun testIsHeadcountStrategy() {
        assertFalse(TronClassPreferences.isHeadcountStrategy(TronClassPreferences.STRATEGY_NO_DELAY))
        assertFalse(TronClassPreferences.isHeadcountStrategy(TronClassPreferences.STRATEGY_DELAY_10S))
        assertFalse(TronClassPreferences.isHeadcountStrategy(TronClassPreferences.STRATEGY_DELAY_30S))
        assertFalse(TronClassPreferences.isHeadcountStrategy(TronClassPreferences.STRATEGY_CUSTOM_TIME))

        assertTrue(TronClassPreferences.isHeadcountStrategy(TronClassPreferences.STRATEGY_COUNT_1))
        assertTrue(TronClassPreferences.isHeadcountStrategy(TronClassPreferences.STRATEGY_COUNT_10))
        assertTrue(TronClassPreferences.isHeadcountStrategy(TronClassPreferences.STRATEGY_HALF_CLASS))
        assertTrue(TronClassPreferences.isHeadcountStrategy(TronClassPreferences.STRATEGY_CUSTOM_RATIO))
    }

    private fun evaluateCondition(
        strategy: String,
        present: Int,
        total: Int,
        customRatio: Int = 30
    ): Boolean {
        val targetCount = when (strategy) {
            TronClassPreferences.STRATEGY_COUNT_1 -> 1
            TronClassPreferences.STRATEGY_COUNT_10 -> 10
            else -> 0
        }
        val targetRatio = when (strategy) {
            TronClassPreferences.STRATEGY_HALF_CLASS -> 50
            TronClassPreferences.STRATEGY_CUSTOM_RATIO -> customRatio
            else -> 0
        }

        return when {
            targetCount > 0 -> {
                if (total in 1 until targetCount) {
                    present >= total || present >= (total + 1) / 2
                } else {
                    present >= targetCount
                }
            }
            targetRatio > 0 -> {
                if (total > 0) {
                    (present * 100 / total) >= targetRatio
                } else {
                    present >= 1
                }
            }
            else -> present >= 1
        }
    }

    @Test
    fun testCount1Condition() {
        assertFalse(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_1, 0, 40))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_1, 1, 40))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_1, 5, 40))
    }

    @Test
    fun testCount10DefaultCondition() {
        assertFalse(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_10, 0, 40))
        assertFalse(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_10, 9, 40))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_10, 10, 40))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_10, 25, 40))

        // 小班容错保护（总人数小于 10 人）
        assertFalse(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_10, 1, 6))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_10, 3, 6)) // 达到了总人数的一半
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_COUNT_10, 6, 6))
    }

    @Test
    fun testHalfClassCondition() {
        assertFalse(evaluateCondition(TronClassPreferences.STRATEGY_HALF_CLASS, 19, 40))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_HALF_CLASS, 20, 40))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_HALF_CLASS, 25, 40))

        // 奇数班级人数 (41 人时，50% 为 21 人)
        assertFalse(evaluateCondition(TronClassPreferences.STRATEGY_HALF_CLASS, 20, 41)) // 20/41 = 48% < 50%
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_HALF_CLASS, 21, 41))  // 21/41 = 51% >= 50%
    }

    @Test
    fun testCustomRatioCondition() {
        // 自定义比例 25% (40人中需要 10 人)
        assertFalse(evaluateCondition(TronClassPreferences.STRATEGY_CUSTOM_RATIO, 9, 40, customRatio = 25))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_CUSTOM_RATIO, 10, 40, customRatio = 25))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_CUSTOM_RATIO, 15, 40, customRatio = 25))

        // 自定义比例 75% (40人中需要 30 人)
        assertFalse(evaluateCondition(TronClassPreferences.STRATEGY_CUSTOM_RATIO, 29, 40, customRatio = 75))
        assertTrue(evaluateCondition(TronClassPreferences.STRATEGY_CUSTOM_RATIO, 30, 40, customRatio = 75))
    }
}
