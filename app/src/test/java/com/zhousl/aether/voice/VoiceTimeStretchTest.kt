package com.zhousl.aether.voice

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTimeStretchTest {
    @Test fun `unity speed returns an independent unchanged buffer`() {
        val input = shortArrayOf(1, -2, 3, -4)

        val output = VoiceTimeStretch.stretch(input, 16_000, 1f)

        assertEquals(input.toList(), output.toList())
        assertNotEquals(input, output)
    }

    @Test fun `slower speech produces the requested duration`() {
        val input = sineWave(sampleRate = 16_000, frequency = 440.0, seconds = 1.0)

        val output = VoiceTimeStretch.stretch(input, 16_000, 0.75f)

        assertEquals(kotlin.math.round(input.size / 0.75).toInt(), output.size)
        assertTrue(output.any { it != 0.toShort() })
    }

    @Test fun `faster speech produces the requested duration`() {
        val input = sineWave(sampleRate = 16_000, frequency = 440.0, seconds = 1.0)

        val output = VoiceTimeStretch.stretch(input, 16_000, 1.25f)

        assertEquals(kotlin.math.round(input.size / 1.25).toInt(), output.size)
        assertTrue(output.any { it != 0.toShort() })
    }

    @Test fun `periodic input keeps approximately the same pitch`() {
        val sampleRate = 16_000
        val frequency = 440.0
        val input = sineWave(sampleRate, frequency, 1.0)
        val output = VoiceTimeStretch.stretch(input, sampleRate, 0.8f)

        val measured = zeroCrossingFrequency(output, sampleRate)

        // WSOLA should preserve the source period. Allow a modest tolerance
        // for frame boundaries and the linear cross-fades.
        assertTrue("measured frequency=$measured", kotlin.math.abs(measured - frequency) < 18.0)
    }

    @Test fun `tiny clips are safe and retain their sample spacing`() {
        val input = shortArrayOf(120, 240, 360, 480, 600, 720, 840, 960)

        val slower = VoiceTimeStretch.stretch(input, 16_000, 0.75f)
        val faster = VoiceTimeStretch.stretch(input, 16_000, 1.25f)

        assertEquals(kotlin.math.round(input.size / 0.75).toInt(), slower.size)
        assertEquals(kotlin.math.round(input.size / 1.25).toInt(), faster.size)
        assertEquals(input[0], slower[0])
        assertEquals(input[0], faster[0])
    }

    @Test fun `cancellation is observed while searching and processing`() {
        val checks = AtomicInteger()
        val input = sineWave(sampleRate = 32_000, frequency = 220.0, seconds = 2.0)

        try {
            VoiceTimeStretch.stretch(input, 32_000, 0.75f) {
                if (checks.incrementAndGet() > 2) throw CancellationException("cancelled")
            }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(checks.get() > 2)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `speed outside supported range is rejected`() {
        VoiceTimeStretch.stretch(shortArrayOf(1, 2, 3), 16_000, 1.5f)
    }

    private fun sineWave(sampleRate: Int, frequency: Double, seconds: Double): ShortArray {
        val count = (sampleRate * seconds).toInt()
        return ShortArray(count) { index ->
            (sin(2.0 * PI * frequency * index / sampleRate) * 24_000.0).toInt().toShort()
        }
    }

    private fun zeroCrossingFrequency(samples: ShortArray, sampleRate: Int): Double {
        var crossings = 0
        for (index in 1 until samples.size) {
            if (samples[index - 1] <= 0 && samples[index] > 0) crossings++
        }
        return crossings.toDouble() * sampleRate / samples.size.toDouble()
    }
}
