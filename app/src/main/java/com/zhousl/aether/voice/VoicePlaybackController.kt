package com.zhousl.aether.voice

import android.content.Context
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

sealed class VoicePlaybackState(open val messageId: String?) {
    data object Idle : VoicePlaybackState(null)
    data class Loading(override val messageId: String) : VoicePlaybackState(messageId)
    data class Synthesizing(
        override val messageId: String,
        val chunkIndex: Int,
        val chunkCount: Int,
    ) : VoicePlaybackState(messageId)
    data class Playing(
        override val messageId: String,
        val chunkIndex: Int,
        val chunkCount: Int,
    ) : VoicePlaybackState(messageId)
    data class Error(
        override val messageId: String,
        val error: VoicePlaybackError,
    ) : VoicePlaybackState(messageId)
}

enum class VoicePlaybackErrorCode {
    EMPTY_TEXT,
    SERVER_NOT_CONFIGURED,
    INSECURE_SERVER_URL,
    AUTHENTICATION_FAILED,
    VOICE_NOT_FOUND,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    INVALID_RESPONSE,
    INVALID_AUDIO,
    TEXT_UNSUPPORTED,
    OUT_OF_MEMORY,
    SYNTHESIS_FAILED,
    PLAYBACK_FAILED,
}

data class VoicePlaybackError(
    val code: VoicePlaybackErrorCode,
    val message: String,
)

class VoicePlaybackController(
    private val engine: SpeechSynthesisEngine,
    private val player: VoiceAudioPlayer,
    private val configProvider: VoicePlaybackConfigProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Closeable {
    constructor(context: Context, configProvider: VoicePlaybackConfigProvider) : this(
        engine = RemoteVoiceEngine(),
        player = AndroidVoiceAudioPlayer(context),
        configProvider = configProvider,
    )

    private val requestGeneration = AtomicLong()
    private var activeJob: Job? = null
    private val _state = MutableStateFlow<VoicePlaybackState>(VoicePlaybackState.Idle)

    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    fun speak(
        messageId: String,
        markdown: String,
        speed: Float = 1f,
        configOverride: VoicePlaybackConfig? = null,
    ) {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        val speechText = VoiceTextProcessor.toSpeechText(markdown)
        val chunks = VoiceTextProcessor.split(speechText)
        val generation = requestGeneration.incrementAndGet()
        activeJob?.cancel()
        engine.cancel()
        player.stop()
        if (chunks.isEmpty()) {
            _state.value = VoicePlaybackState.Error(
                messageId,
                VoicePlaybackError(VoicePlaybackErrorCode.EMPTY_TEXT, "This message has no readable text"),
            )
            return
        }

        activeJob = scope.launch {
            try {
                _state.value = VoicePlaybackState.Loading(messageId)
                val config = configOverride ?: configProvider.current()
                    ?: throw VoiceSynthesisException(
                        VoiceSynthesisErrorCode.SERVER_NOT_CONFIGURED,
                        "Configure the voice server before using speech",
                    )
                supervisorScope {
                    _state.value = VoicePlaybackState.Synthesizing(messageId, 0, chunks.size)
                    var currentAudio = synthesizeChunk(config, chunks.first(), speed)
                    val pipelineScope = this
                    player.playSequence {
                        for (index in chunks.indices) {
                            val nextAudio = if (index + 1 < chunks.size) {
                                pipelineScope.async(start = CoroutineStart.UNDISPATCHED) {
                                    synthesizeChunk(config, chunks[index + 1], speed)
                                }
                            } else {
                                null
                            }

                            _state.value = VoicePlaybackState.Playing(messageId, index, chunks.size)
                            write(currentAudio)

                            if (nextAudio != null) {
                                if (!nextAudio.isCompleted) {
                                    _state.value = VoicePlaybackState.Synthesizing(messageId, index + 1, chunks.size)
                                }
                                currentAudio = try {
                                    nextAudio.await()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    drain()
                                    throw error
                                }
                            }
                        }
                    }
                }
                if (requestGeneration.get() == generation) {
                    _state.value = VoicePlaybackState.Idle
                }
            } catch (_: CancellationException) {
                if (requestGeneration.get() == generation) {
                    _state.value = VoicePlaybackState.Idle
                }
            } catch (_: OutOfMemoryError) {
                if (requestGeneration.get() == generation) {
                    _state.value = VoicePlaybackState.Error(
                        messageId,
                        VoicePlaybackError(
                            VoicePlaybackErrorCode.OUT_OF_MEMORY,
                            "There is not enough memory to synthesize this message",
                        ),
                    )
                }
            } catch (error: VoiceSynthesisException) {
                if (requestGeneration.get() == generation) {
                    _state.value = VoicePlaybackState.Error(messageId, error.toPlaybackError())
                }
            } catch (error: VoiceAudioPlaybackException) {
                if (requestGeneration.get() == generation) {
                    _state.value = VoicePlaybackState.Error(
                        messageId,
                        VoicePlaybackError(VoicePlaybackErrorCode.PLAYBACK_FAILED, error.message.orEmpty()),
                    )
                }
            } catch (error: Throwable) {
                if (requestGeneration.get() == generation) {
                    _state.value = VoicePlaybackState.Error(
                        messageId,
                        VoicePlaybackError(
                            VoicePlaybackErrorCode.SYNTHESIS_FAILED,
                            error.message ?: "Remote speech synthesis failed",
                        ),
                    )
                }
            }
        }
    }

    fun stop() {
        requestGeneration.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        engine.cancel()
        player.stop()
        _state.value = VoicePlaybackState.Idle
    }

    override fun close() {
        stop()
        player.release()
        scope.launch {
            try {
                engine.release()
            } finally {
                scope.cancel()
            }
        }
    }

    private suspend fun synthesizeChunk(
        config: VoicePlaybackConfig,
        text: String,
        speed: Float,
    ): VoiceAudio = engine.synthesize(
        VoiceSynthesisRequest(
            connection = config.connection,
            voiceId = config.voiceId,
            text = text,
            language = config.language,
            speed = speed,
        ),
    )

    private fun VoiceSynthesisException.toPlaybackError(): VoicePlaybackError {
        val mapped = when (code) {
            VoiceSynthesisErrorCode.SERVER_NOT_CONFIGURED -> VoicePlaybackErrorCode.SERVER_NOT_CONFIGURED
            VoiceSynthesisErrorCode.INSECURE_SERVER_URL -> VoicePlaybackErrorCode.INSECURE_SERVER_URL
            VoiceSynthesisErrorCode.AUTHENTICATION_FAILED -> VoicePlaybackErrorCode.AUTHENTICATION_FAILED
            VoiceSynthesisErrorCode.VOICE_NOT_FOUND -> VoicePlaybackErrorCode.VOICE_NOT_FOUND
            VoiceSynthesisErrorCode.RATE_LIMITED -> VoicePlaybackErrorCode.RATE_LIMITED
            VoiceSynthesisErrorCode.SERVICE_UNAVAILABLE -> VoicePlaybackErrorCode.SERVICE_UNAVAILABLE
            VoiceSynthesisErrorCode.NETWORK_UNAVAILABLE -> VoicePlaybackErrorCode.NETWORK_UNAVAILABLE
            VoiceSynthesisErrorCode.TIMEOUT -> VoicePlaybackErrorCode.TIMEOUT
            VoiceSynthesisErrorCode.RESPONSE_TOO_LARGE,
            VoiceSynthesisErrorCode.INVALID_RESPONSE,
            -> VoicePlaybackErrorCode.INVALID_RESPONSE
            VoiceSynthesisErrorCode.INVALID_AUDIO -> VoicePlaybackErrorCode.INVALID_AUDIO
            VoiceSynthesisErrorCode.TEXT_UNSUPPORTED -> VoicePlaybackErrorCode.TEXT_UNSUPPORTED
            VoiceSynthesisErrorCode.CANCELLED,
            VoiceSynthesisErrorCode.SYNTHESIS_FAILED,
            -> VoicePlaybackErrorCode.SYNTHESIS_FAILED
        }
        return VoicePlaybackError(mapped, message ?: "Remote speech synthesis failed")
    }
}
