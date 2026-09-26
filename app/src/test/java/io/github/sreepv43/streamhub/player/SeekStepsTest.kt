package io.github.sreepv43.streamhub.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekStepsTest {
    private var now = 0L
    private val steps = SeekSteps { now }

    private fun press(forward: Boolean = true, acceleration: String = "normal", after: Long = 300): Long {
        now += after
        return steps.next(forward, 10_000, acceleration)
    }

    @Test
    fun repeatedPressesJumpFurther() {
        val jumps = List(9) { press() }
        assertEquals(listOf(10, 10, 30, 30, 60, 60, 60, 120, 120).map { it * 1_000L }, jumps)
    }

    @Test
    fun fastModeGrowsQuicker() {
        val jumps = List(6) { press(acceleration = "fast") }
        assertEquals(listOf(10, 30, 60, 120, 120, 300).map { it * 1_000L }, jumps)
    }

    @Test
    fun offKeepsTheSameStep() {
        assertEquals(List(5) { 10_000L }, List(5) { press(acceleration = "off") })
    }

    @Test
    fun aPauseOrADirectionChangeStartsAgain() {
        repeat(4) { press() }
        assertEquals(10_000L, press(after = SeekSteps.CHAIN_MS + 1))
        repeat(3) { press() }
        assertEquals(-10_000L, press(forward = false))
        repeat(3) { press(forward = false) }
        steps.reset()
        assertEquals(-10_000L, press(forward = false))
    }

    @Test
    fun stepsAreCappedAndDescribed() {
        assertEquals(SeekSteps.MAX_STEP_MS, SeekSteps.step(60_000, 20, "fast"))
        assertEquals("+30 s", SeekSteps.describe(30_000))
        assertEquals("−1:30", SeekSteps.describe(-90_000))
    }
}
