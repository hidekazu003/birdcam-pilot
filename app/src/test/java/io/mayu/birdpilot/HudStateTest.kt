package io.mayu.birdpilot

import io.mayu.birdpilot.detector.Hud
import io.mayu.birdpilot.detector.hudState
import org.junit.Assert.assertEquals
import org.junit.Test

class HudStateTest {
    @Test
    fun `hudState returns YELLOW when score is null`() {
        val result = hudState(score = null, roiTh = 0.4f)
        assertEquals(Hud.YELLOW, result)
    }

    @Test
    fun `hudState returns YELLOW when score is below threshold`() {
        val result = hudState(score = 0.39f, roiTh = 0.4f)
        assertEquals(Hud.YELLOW, result)
    }

    @Test
    fun `hudState returns GREEN when score equals threshold`() {
        val result = hudState(score = 0.4f, roiTh = 0.4f)
        assertEquals(Hud.GREEN, result)
    }

    @Test
    fun `hudState returns GREEN when score is above threshold`() {
        val result = hudState(score = 0.8f, roiTh = 0.4f)
        assertEquals(Hud.GREEN, result)
    }
}
