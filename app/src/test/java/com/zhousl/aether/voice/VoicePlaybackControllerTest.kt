package com.zhousl.aether.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlaybackControllerTest {
    @Test
    fun `controller reads current config sends speed to server and plays at original rate`() {
        val engine = RecordingEngine()
        val player = RecordingPlayer()
        var config = config("voice-one")
        val controller = VoicePlaybackController(
            engine = engine,
            player = player,
            configProvider = VoicePlaybackConfigProvider { config },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        controller.speak("message-1", "第一句。", 1.25f)
        config = config("voice-two")
        controller.speak("message-2", "第二句。", 0.75f)

        assertEquals(listOf("voice-one", "voice-two"), engine.requests.map { it.voiceId })
        assertEquals(listOf(1.25f, 0.75f), engine.requests.map { it.speed })
        assertEquals(2, player.played.size)
        assertEquals(2, player.sequenceCount)
        assertEquals(2, player.drainCount)
        assertEquals(VoicePlaybackState.Idle, controller.state.value)
    }

    @Test
    fun `controller reports missing dynamic server configuration`() {
        val controller = VoicePlaybackController(
            engine = RecordingEngine(),
            player = RecordingPlayer(),
            configProvider = VoicePlaybackConfigProvider { null },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        controller.speak("message", "可以朗读的文本")

        val state = controller.state.value
        assertTrue(state is VoicePlaybackState.Error)
        assertEquals(
            VoicePlaybackErrorCode.SERVER_NOT_CONFIGURED,
            (state as VoicePlaybackState.Error).error.code,
        )
    }

    @Test
    fun `controller synthesizes and plays chunks in order`() {
        val engine = RecordingEngine()
        val player = RecordingPlayer()
        val controller = VoicePlaybackController(
            engine = engine,
            player = player,
            configProvider = VoicePlaybackConfigProvider { config("voice") },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val text = "甲".repeat(180) + "。" + "乙".repeat(180)

        controller.speak("message", text)

        assertTrue(engine.requests.size > 1)
        assertEquals(engine.requests.map { it.text.length }, player.played.map { it.samples.first().toInt() })
        assertEquals(1, player.sequenceCount)
    }

    @Test
    fun `controller prefetches the next chunk while the current chunk is playing`() {
        val secondChunkStarted = CompletableDeferred<Unit>()
        val allowSecondChunkToFinish = CompletableDeferred<Unit>()
        val engine = PrefetchRecordingEngine(secondChunkStarted, allowSecondChunkToFinish)
        val player = PrefetchObservingPlayer(secondChunkStarted, allowSecondChunkToFinish)
        val controller = VoicePlaybackController(
            engine = engine,
            player = player,
            configProvider = VoicePlaybackConfigProvider { config("voice") },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        controller.speak("message", "甲".repeat(180) + "。" + "乙".repeat(180))

        assertTrue(player.sawPrefetchDuringFirstPlayback)
        assertEquals(engine.requests.map { it.text.length }, player.played.map { it.samples.first().toInt() })
        assertEquals(VoicePlaybackState.Idle, controller.state.value)
    }

    @Test
    fun `prefetch failure is reported only after the current chunk finishes playing`() {
        val engine = FailingPrefetchEngine()
        val player = RecordingPlayer()
        val controller = VoicePlaybackController(
            engine = engine,
            player = player,
            configProvider = VoicePlaybackConfigProvider { config("voice") },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        controller.speak("message", "甲".repeat(180) + "。" + "乙".repeat(180))

        assertEquals(1, player.played.size)
        assertEquals(1, player.drainCount)
        val state = controller.state.value
        assertTrue(state is VoicePlaybackState.Error)
        assertEquals(
            VoicePlaybackErrorCode.SERVICE_UNAVAILABLE,
            (state as VoicePlaybackState.Error).error.code,
        )
    }

    private fun config(voiceId: String) = VoicePlaybackConfig(
        connection = VoiceServerConnection("https://voice.example.test", "token"),
        voiceId = voiceId,
    )
}

private class RecordingEngine : SpeechSynthesisEngine {
    val requests = mutableListOf<VoiceSynthesisRequest>()
    var cancelCount = 0

    override suspend fun checkHealth(connection: VoiceServerConnection) = VoiceServerHealth(
        status = "ok",
        engineStatus = "ready",
        registryStatus = "ready",
        voiceCount = 1,
        queueActive = 0,
        queueWaiting = 0,
        queueCapacity = 4,
    )

    override suspend fun listVoices(connection: VoiceServerConnection) = emptyList<RemoteVoice>()

    override suspend fun synthesize(request: VoiceSynthesisRequest): VoiceAudio {
        requests += request
        return VoiceAudio(shortArrayOf(request.text.length.toShort()), 32_000)
    }

    override fun cancel() {
        cancelCount++
    }

    override suspend fun release() = Unit
}

private class RecordingPlayer : VoiceAudioPlayer {
    val played = mutableListOf<VoiceAudio>()
    var sequenceCount = 0
    var drainCount = 0

    override suspend fun playSequence(block: suspend VoiceAudioSink.() -> Unit) {
        sequenceCount++
        val sink = object : VoiceAudioSink {
            override suspend fun write(audio: VoiceAudio) {
                played += audio
            }

            override suspend fun drain() {
                drainCount++
            }
        }
        block(sink)
        sink.drain()
    }

    override fun stop() = Unit

    override fun release() = Unit
}

private class PrefetchRecordingEngine(
    private val secondChunkStarted: CompletableDeferred<Unit>,
    private val allowSecondChunkToFinish: CompletableDeferred<Unit>,
) : SpeechSynthesisEngine {
    val requests = mutableListOf<VoiceSynthesisRequest>()

    override suspend fun checkHealth(connection: VoiceServerConnection) = error("Not used")

    override suspend fun listVoices(connection: VoiceServerConnection) = emptyList<RemoteVoice>()

    override suspend fun synthesize(request: VoiceSynthesisRequest): VoiceAudio {
        requests += request
        if (requests.size == 2) {
            secondChunkStarted.complete(Unit)
            allowSecondChunkToFinish.await()
        }
        return VoiceAudio(shortArrayOf(request.text.length.toShort()), 32_000)
    }

    override fun cancel() = Unit

    override suspend fun release() = Unit
}

private class PrefetchObservingPlayer(
    private val secondChunkStarted: CompletableDeferred<Unit>,
    private val allowSecondChunkToFinish: CompletableDeferred<Unit>,
) : VoiceAudioPlayer {
    val played = mutableListOf<VoiceAudio>()
    var sawPrefetchDuringFirstPlayback = false

    override suspend fun playSequence(block: suspend VoiceAudioSink.() -> Unit) {
        val sink = object : VoiceAudioSink {
            override suspend fun write(audio: VoiceAudio) {
                if (played.isEmpty()) {
                    sawPrefetchDuringFirstPlayback = secondChunkStarted.isCompleted
                    allowSecondChunkToFinish.complete(Unit)
                }
                played += audio
            }

            override suspend fun drain() = Unit
        }
        block(sink)
        sink.drain()
    }

    override fun stop() = Unit

    override fun release() = Unit
}

private class FailingPrefetchEngine : SpeechSynthesisEngine {
    private var requestCount = 0

    override suspend fun checkHealth(connection: VoiceServerConnection) = error("Not used")

    override suspend fun listVoices(connection: VoiceServerConnection) = emptyList<RemoteVoice>()

    override suspend fun synthesize(request: VoiceSynthesisRequest): VoiceAudio {
        requestCount++
        if (requestCount == 2) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.SERVICE_UNAVAILABLE,
                "Server unavailable",
            )
        }
        return VoiceAudio(shortArrayOf(request.text.length.toShort()), 32_000)
    }

    override fun cancel() = Unit

    override suspend fun release() = Unit
}
