package com.joetr.andy.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayCropTest {
    @Test
    fun infersTwoDisplaysForUltrawideSpan() {
        assertEquals(2, inferDisplayCount(6896, 2346))
        assertEquals(1, inferDisplayCount(1920, 1080))
        assertEquals(3, inferDisplayCount(7680, 2160))
    }

    @Test
    fun cropsEqualTiles() {
        val left = displayCrop(6896, 2346, 0)
        val right = displayCrop(6896, 2346, 1)
        val all = displayCrop(6896, 2346, null)
        assertEquals(0, left.x)
        assertEquals(3448, left.width)
        assertEquals(3448, right.x)
        assertEquals(3448, right.width)
        assertEquals(6896, all.width)
    }
}
