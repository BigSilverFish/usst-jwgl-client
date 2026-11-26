package cn.edu.usst.jwgl.ui.tronclass

import cn.edu.usst.jwgl.data.model.ChaoXingSignDetail
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.model.UnifiedActiveRollcall
import org.junit.Assert.assertEquals
import org.junit.Test

class RollcallHeroCardStateTest {

    private fun sampleTronClassActive(
        status: String? = "in_progress",
        studentStatus: String? = null
    ): UnifiedActiveRollcall {
        return UnifiedActiveRollcall(
            unifiedId = "tc_1001",
            platform = LearningPlatform.TRONCLASS,
            courseName = "数字图像处理A",
            typeDisplayName = "二维码扫码签到",
            effectiveId = 1001L,
            rawTronClassRollcall = TronClassRollcall(
                id = 1001L,
                rollcallId = 1001L,
                courseId = 10L,
                courseName = "数字图像处理A",
                type = "qr_code",
                status = status,
                studentStatus = studentStatus
            )
        )
    }

    @Test
    fun noActiveRollcall_resolvesToRedNoRollcallState() {
        val state = resolveHeroCardState(
            active = null,
            currentStudentId = "2435055230",
            localConfirmedState = "confirmed",
            inMemorySignedKeys = setOf("2435055230|1001")
        )
        assertEquals(RollcallHeroCardState.NO_ROLLCALL, state)
    }

    @Test
    fun activeUnsignedOrOnlyOtherSigned_resolvesToBlueUnsignedState() {
        val active = sampleTronClassActive(status = "in_progress")
        assertEquals(
            RollcallHeroCardState.ACTIVE_UNSIGNED,
            resolveHeroCardState(
                active = active,
                currentStudentId = "2435055230",
                localConfirmedState = "rejected",
                inMemorySignedKeys = setOf("2435055999|1001"),
                latestStatsMyStatus = "absent"
            )
        )
        assertEquals(
            RollcallHeroCardState.ACTIVE_UNSIGNED,
            resolveHeroCardState(
                active = sampleTronClassActive(status = "on_call", studentStatus = null),
                currentStudentId = "2435055230",
                localConfirmedState = null,
                inMemorySignedKeys = emptySet()
            )
        )

        assertEquals(
            RollcallHeroCardState.ACTIVE_UNSIGNED,
            resolveHeroCardState(
                active = active,
                currentStudentId = "2435055230",
                localConfirmedState = "confirmed",
                inMemorySignedKeys = setOf("2435055230|1001"),
                latestStatsMyStatus = "absent"
            )
        )
    }

    @Test
    fun activeConfirmedByAnyCurrentAccountSignal_resolvesToGreenSignedState() {
        val active = sampleTronClassActive(status = "in_progress")

        assertEquals(
            RollcallHeroCardState.ACTIVE_SIGNED,
            resolveHeroCardState(
                active = active,
                currentStudentId = "2435055230",
                localConfirmedState = "confirmed",
                inMemorySignedKeys = emptySet()
            )
        )

        assertEquals(
            RollcallHeroCardState.ACTIVE_SIGNED,
            resolveHeroCardState(
                active = active,
                currentStudentId = "2435055230",
                localConfirmedState = null,
                inMemorySignedKeys = setOf("2435055230|1001")
            )
        )

        assertEquals(
            RollcallHeroCardState.ACTIVE_SIGNED,
            resolveHeroCardState(
                active = sampleTronClassActive(studentStatus = "on_call_fine"),
                currentStudentId = "2435055230",
                localConfirmedState = null,
                inMemorySignedKeys = emptySet()
            )
        )

        assertEquals(
            RollcallHeroCardState.ACTIVE_SIGNED,
            resolveHeroCardState(
                active = active,
                currentStudentId = "2435055230",
                localConfirmedState = null,
                inMemorySignedKeys = emptySet(),
                latestStatsMyStatus = "on_call"
            )
        )

        val chaoXingSigned = UnifiedActiveRollcall(
            unifiedId = "cx_2002",
            platform = LearningPlatform.CHAOXING,
            courseName = "高数",
            typeDisplayName = "位置签到",
            effectiveId = 2002L,
            chaoXingSignDetail = ChaoXingSignDetail(activeId = 2002L, isAlreadySigned = true)
        )
        assertEquals(
            RollcallHeroCardState.ACTIVE_SIGNED,
            resolveHeroCardState(
                active = chaoXingSigned,
                currentStudentId = "2435055230",
                localConfirmedState = null,
                inMemorySignedKeys = emptySet()
            )
        )
    }
}
