package com.zhousl.aether.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface VoiceAudioSink {
    suspend fun write(audio: VoiceAudio)

    suspend fun drain()
}

interface VoiceAudioPlayer {
    suspend fun playSequence(block: suspend VoiceAudioSink.() -> Unit)

    fun stop()

    fun release()
}

class VoiceAudioPlaybackException(message: String) : Exception(message)

class AndroidVoiceAudioPlayer(context: Context) : VoiceAudioPlayer {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val activeTrack = AtomicReference<AudioTrack?>()
    private val sessionMutex = Mutex()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> activeTrack.get()?.setVolume(1f)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> activeTrack.get()?.setVolume(DUCK_VOLUME)
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> stop()
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(focusListener)
        .build()
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override suspend fun playSequence(block: suspend VoiceAudioSink.() -> Unit) = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            stop()
            if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                throw VoiceAudioPlaybackException("Another app currently owns audio playback")
            }
            var track: AudioTrack? = null
            var sampleRate = 0
            var writtenSamples = 0L
            val sink = object : VoiceAudioSink {
            override suspend fun write(audio: VoiceAudio) {
                if (audio.samples.isEmpty()) return
                val active = track ?: createTrack(audio.sampleRate).also { created ->
                    track = created
                    sampleRate = audio.sampleRate
                    activeTrack.set(created)
                    created.play()
                }
                if (audio.sampleRate != sampleRate) {
                    throw VoiceAudioPlaybackException(
                        "The voice server changed sample rate during one message",
                    )
                }
                var offset = 0
                while (offset < audio.samples.size) {
                    currentCoroutineContext().ensureActive()
                    if (activeTrack.get() !== active) throw CancellationException("Voice playback stopped")
                    val count = minOf(STREAM_BUFFER_SAMPLES, audio.samples.size - offset)
                    val written = active.write(audio.samples, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) throw VoiceAudioPlaybackException("AudioTrack write failed: $written")
                    if (written == 0) continue
                    offset += written
                    writtenSamples += written
                }
            }

            override suspend fun drain() {
                val active = track ?: return
                awaitDrain(active, writtenSamples, sampleRate)
            }
            }
            try {
                block(sink)
                sink.drain()
            } finally {
                track?.let { active ->
                    activeTrack.compareAndSet(active, null)
                    runCatching { active.stop() }
                    runCatching { active.flush() }
                    active.release()
                }
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw VoiceAudioPlaybackException("The device rejected the voice audio format")
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * BUFFER_SECONDS * Short.SIZE_BYTES))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw VoiceAudioPlaybackException("The device could not initialize speech playback")
        }
        return track
    }

    private suspend fun awaitDrain(track: AudioTrack, writtenSamples: Long, sampleRate: Int) {
        val played = Integer.toUnsignedLong(track.playbackHeadPosition)
        val remainingSamples = (writtenSamples - played).coerceAtLeast(0L)
        val expectedMs = remainingSamples * 1_000L / sampleRate.coerceAtLeast(1)
        val deadline = System.nanoTime() + (expectedMs * 2 + COMPLETION_GRACE_MS) * 1_000_000L
        while (Integer.toUnsignedLong(track.playbackHeadPosition) < writtenSamples) {
            currentCoroutineContext().ensureActive()
            if (activeTrack.get() !== track) throw CancellationException("Voice playback stopped")
            if (System.nanoTime() >= deadline) {
                throw VoiceAudioPlaybackException("Timed out while finishing speech playback")
            }
            delay(PLAYBACK_POLL_MS)
        }
    }

    override fun stop() {
        activeTrack.getAndSet(null)?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
        }
    }

    override fun release() {
        stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
        runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
    }

    private companion object {
        const val STREAM_BUFFER_SAMPLES = 8_192
        const val BUFFER_SECONDS = 1
        const val PLAYBACK_POLL_MS = 20L
        const val COMPLETION_GRACE_MS = 5_000L
        const val DUCK_VOLUME = 0.2f
    }
}
