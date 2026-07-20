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

    @Test
    fun `reroll steps by a bounded amount instead of jumping anywhere in range`() {
        val maxMagnitude = 15f
        val wind = Wind(11f)
        val rng = Random(7)
        repeat(200) {
            val before = wind.velocity
            wind.reroll(maxMagnitude, rng)
            val step = kotlin.math.abs(wind.velocity - before)
            assertTrue(
                "step $step from $before to ${wind.velocity} exceeds a stable turn-to-turn change",
                step <= maxMagnitude * 0.35f + 0.001f,
            )
        }
    }

    @Test
    fun `reroll never fully flips from a strong reading to the opposite direction`() {
        // Regression guard for the exact complaint that motivated bounded stepping: a
        // strong left gust should never be immediately followed by a strong right one.
        val wind = Wind(11f)
        repeat(200) { seed ->
            wind.reroll(15f, Random(seed.toLong()))
            assertTrue("expected wind to stay non-negative after a strong positive reading", wind.velocity > -5f)
            wind.velocity = 11f // reset for the next independent trial
        }
    }
}
