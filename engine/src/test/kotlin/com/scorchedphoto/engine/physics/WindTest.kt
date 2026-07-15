package com.scorchedphoto.engine.physics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WindTest {

    @Test
    fun `reroll stays within the requested magnitude`() {
        val wind = Wind()
        repeat(200) { seed ->
            wind.reroll(15f, Random(seed.toLong()))
            assertTrue(wind.velocity in -15f..15f)
        }
    }

    @Test
    fun `same seed produces the same reroll`() {
        val a = Wind()
        val b = Wind()
        a.reroll(20f, Random(42))
        b.reroll(20f, Random(42))
        assertEquals(a.velocity, b.velocity, 0.0001f)
    }
}
