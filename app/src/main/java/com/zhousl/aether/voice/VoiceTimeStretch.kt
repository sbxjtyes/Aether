package com.zhousl.aether.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Small, dependency-free WSOLA/SOLA time stretcher for mono PCM16 speech.
 *
 * This is retained as a fallback for models that emit a fixed duration.
 * Changing AudioTrack playback rate would change pitch as well, so such models
 * can use this overlap/add implementation instead. The implementation has a
 * bounded search and checks cancellation while processing.
 */
internal object VoiceTimeStretch {
    const val MIN_SPEED = 0.75f
    const val MAX_SPEED = 1.25f

    /**
     * Stretches [audio] by [speed] while retaining approximately the original
     * pitch.  A speed greater than one produces fewer samples; a speed below
     * one produces more samples.  [ensureActive] may throw CancellationException.
     */
    fun stretch(
        audio: VoiceAudio,
        speed: Float,
        ensureActive: () -> Unit = {},
    ): VoiceAudio {
        return VoiceAudio(stretch(audio.samples, audio.sampleRate, speed, ensureActive), audio.sampleRate)
    }

    fun stretch(
        samples: ShortArray,
        sampleRate: Int,
        speed: Float,
        ensureActive: () -> Unit = {},
    ): ShortArray {
        require(speed.isFinite() && speed in MIN_SPEED..MAX_SPEED) {
            "Speech speed must be between $MIN_SPEED and $MAX_SPEED"
        }
        ensureActive()
        if (samples.isEmpty() || speed == 1f) return samples.copyOf()

        val targetLengthLong = (samples.size.toDouble() / speed.toDouble()).roundToLong()
        require(targetLengthLong in 1..Int.MAX_VALUE.toLong()) { "Stretched audio is too large" }
        val targetLength = targetLengthLong.toInt()
        if (targetLength == samples.size) return samples.copyOf()

        // A very short clip has no stable period from which to infer a SOLA
        // match.  Keep its sample spacing (and therefore its pitch) and only
        // trim/pad the tail.  This also avoids invalid overlap/window sizes.
        val nominalFrame = max(32, (sampleRate * FRAME_MILLIS / 1_000f).roundToInt())
        if (samples.size < MIN_STABLE_SAMPLES || targetLength <= 1) {
            return shortClipFallback(samples, targetLength, ensureActive)
        }

        // Use a shorter frame for clips that are only a few frames long.  The
        // adaptive frame still leaves enough source material for a search.
        val frameLength = min(nominalFrame, max(MIN_FRAME_SAMPLES, samples.size / 2))
            .coerceAtMost(samples.size)
        if (frameLength < MIN_FRAME_SAMPLES || targetLength <= frameLength) {
            return shortClipFallback(samples, targetLength, ensureActive)
        }

        val overlap = min(
            frameLength - 1,
            max(MIN_OVERLAP_SAMPLES, (sampleRate * OVERLAP_MILLIS / 1_000f).roundToInt()),
        ).coerceAtMost(frameLength / 2)
        val synthesisHop = frameLength - overlap
        if (synthesisHop <= 0 || overlap < 2) {
            return shortClipFallback(samples, targetLength, ensureActive)
        }

        val output = ShortArray(targetLength)
        val firstCount = min(frameLength, targetLength)
        samples.copyInto(output, destinationOffset = 0, startIndex = 0, endIndex = firstCount)
        if (firstCount < frameLength) return output

        val maxInputStart = samples.size - frameLength
        val maxOutputStart = targetLength - 1
        val sourceSpan = max(1, samples.size - frameLength)
        val outputSpan = max(1, targetLength - frameLength)
        val analysisScale = sourceSpan.toDouble() / outputSpan.toDouble()
        val searchRadius = min(
            maxInputStart,
            max(1, (sampleRate * SEARCH_MILLIS / 1_000f).roundToInt()),
        )
        // Searching every fourth sample at high sample rates keeps CPU use
        // bounded; a fine pass around the best candidate recovers sample-level
        // alignment and avoids an audible phase jump.
        val coarseStep = max(1, sampleRate / COARSE_SAMPLES_PER_SECOND)
        val correlationStride = max(1, coarseStep)

        var outputStart = synthesisHop
        var previousInputStart = 0
        var frameNumber = 0
        while (outputStart < maxOutputStart && outputStart < targetLength) {
            ensureActive()
            val expectedInput = (outputStart.toDouble() * analysisScale)
                .roundToInt()
                .coerceIn(0, maxInputStart)
            val candidate = findBestCandidate(
                samples = samples,
                previousInputStart = previousInputStart,
                expectedInput = expectedInput,
                maxInputStart = maxInputStart,
                searchRadius = searchRadius,
                synthesisHop = synthesisHop,
                overlap = overlap,
                coarseStep = coarseStep,
                correlationStride = correlationStride,
                ensureActive = ensureActive,
            )

            val copyCount = min(frameLength, targetLength - outputStart)
            val actualOverlap = min(overlap, copyCount)
            blendFrame(
                output = output,
                outputStart = outputStart,
                source = samples,
                sourceStart = candidate,
                overlap = actualOverlap,
                copyCount = copyCount,
            )
            previousInputStart = candidate
            outputStart += synthesisHop
            frameNumber++
            // The explicit counter makes cancellation responsive even if a
            // caller supplies a very cheap/no-op checker in production.
            if ((frameNumber and 0x0f) == 0) ensureActive()
        }

        // The loop always writes the last overlap, but a defensive fill keeps
        // the method safe if integer rounding leaves a one-sample tail.
        val lastWritten = min(targetLength, max(0, outputStart))
        if (lastWritten < targetLength) {
            val sourceStart = max(0, samples.size - (targetLength - lastWritten))
            val count = min(targetLength - lastWritten, samples.size - sourceStart)
            if (count > 0) samples.copyInto(output, lastWritten, sourceStart, sourceStart + count)
            if (lastWritten + count < targetLength) {
                output.fill(0, lastWritten + count, targetLength)
            }
        }
        ensureActive()
        return output
    }

    private fun shortClipFallback(
        samples: ShortArray,
        targetLength: Int,
        ensureActive: () -> Unit,
    ): ShortArray {
        ensureActive()
        if (targetLength <= 0) return ShortArray(0)
        val output = ShortArray(targetLength)
        samples.copyInto(output, endIndex = min(samples.size, targetLength))
        // For a slower tiny clip, zero-padding the tail retains its original
        // sample spacing.  It is preferable to a pitch-shifting resampler for
        // clips too short to estimate a period safely.
        ensureActive()
        return output
    }

    private fun findBestCandidate(
        samples: ShortArray,
        previousInputStart: Int,
        expectedInput: Int,
        maxInputStart: Int,
        searchRadius: Int,
        synthesisHop: Int,
        overlap: Int,
        coarseStep: Int,
        correlationStride: Int,
        ensureActive: () -> Unit,
    ): Int {
        val minimum = max(previousInputStart + 1, expectedInput - searchRadius)
            .coerceAtMost(maxInputStart)
        val maximum = min(maxInputStart, expectedInput + searchRadius)
        if (minimum > maximum) return expectedInput.coerceIn(0, maxInputStart)

        var best = expectedInput.coerceIn(minimum, maximum)
        var bestScore = Double.NEGATIVE_INFINITY
        var candidate = minimum
        var iterations = 0
        while (candidate <= maximum) {
            val score = correlation(
                samples = samples,
                referenceStart = previousInputStart + synthesisHop,
                candidateStart = candidate,
                overlap = overlap,
                stride = correlationStride,
            )
            if (isBetter(score, candidate, bestScore, best, expectedInput)) {
                bestScore = score
                best = candidate
            }
            candidate += coarseStep
            iterations++
            if ((iterations and 0x3f) == 0) ensureActive()
        }
        if (candidate - coarseStep != maximum) {
            val score = correlation(samples, previousInputStart + synthesisHop, maximum, overlap, correlationStride)
            if (isBetter(score, maximum, bestScore, best, expectedInput)) {
                bestScore = score
                best = maximum
            }
        }

        // Refine around the coarse winner at single-sample resolution.  A
        // stride of two is sufficient for the fine pass and halves work.
        val refineStart = max(minimum, best - coarseStep)
        val refineEnd = min(maximum, best + coarseStep)
        bestScore = correlation(samples, previousInputStart + synthesisHop, best, overlap, 2)
        candidate = refineStart
        while (candidate <= refineEnd) {
            val score = correlation(samples, previousInputStart + synthesisHop, candidate, overlap, 2)
            if (isBetter(score, candidate, bestScore, best, expectedInput)) {
                bestScore = score
                best = candidate
            }
            candidate++
        }
        ensureActive()
        return best
    }

    private fun isBetter(
        score: Double,
        candidate: Int,
        bestScore: Double,
        best: Int,
        expected: Int,
    ): Boolean {
        if (score > bestScore + SCORE_EPSILON) return true
        return abs(score - bestScore) <= SCORE_EPSILON &&
            abs(candidate - expected) < abs(best - expected)
    }

    private fun correlation(
        samples: ShortArray,
        referenceStart: Int,
        candidateStart: Int,
        overlap: Int,
        stride: Int,
    ): Double {
        var dot = 0.0
        var referenceEnergy = 0.0
        var candidateEnergy = 0.0
        var count = 0
        var index = 0
        while (index < overlap) {
            val reference = samples[referenceStart + index].toDouble()
            val candidate = samples[candidateStart + index].toDouble()
            dot += reference * candidate
            referenceEnergy += reference * reference
            candidateEnergy += candidate * candidate
            count++
            index += stride
        }
        if (count == 0 || referenceEnergy <= ENERGY_EPSILON || candidateEnergy <= ENERGY_EPSILON) return 0.0
        return dot / sqrt(referenceEnergy * candidateEnergy)
    }

    private fun blendFrame(
        output: ShortArray,
        outputStart: Int,
        source: ShortArray,
        sourceStart: Int,
        overlap: Int,
        copyCount: Int,
    ) {
        if (copyCount <= 0) return
        if (overlap <= 0) {
            source.copyInto(output, outputStart, sourceStart, sourceStart + copyCount)
            return
        }
        val denominator = (overlap + 1).toLong()
        for (index in 0 until overlap) {
            val oldWeight = (overlap - index).toLong()
            val newWeight = (index + 1).toLong()
            val oldValue = output[outputStart + index].toLong()
            val newValue = source[sourceStart + index].toLong()
            output[outputStart + index] = ((oldValue * oldWeight + newValue * newWeight) / denominator)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toShort()
        }
        if (copyCount > overlap) {
            source.copyInto(
                output,
                destinationOffset = outputStart + overlap,
                startIndex = sourceStart + overlap,
                endIndex = sourceStart + copyCount,
            )
        }
    }

    private const val FRAME_MILLIS = 40
    private const val OVERLAP_MILLIS = 12
    private const val SEARCH_MILLIS = 8
    private const val COARSE_SAMPLES_PER_SECOND = 8_000
    private const val MIN_STABLE_SAMPLES = 64
    private const val MIN_FRAME_SAMPLES = 32
    private const val MIN_OVERLAP_SAMPLES = 8
    private const val SCORE_EPSILON = 1e-9
    private const val ENERGY_EPSILON = 1e-6
}
