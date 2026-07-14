package com.zhousl.aether.voice

data class VoiceAudio(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    init {
        require(sampleRate in 8_000..96_000) { "Unsupported voice sample rate: $sampleRate" }
    }
}

data class VoiceServerConnection(
    val baseUrl: String,
    val bearerToken: String,
)

data class RemoteVoice(
    val id: String,
    val name: String,
    val language: String,
    val modelVersion: String,
)

data class VoiceServerHealth(
    val status: String,
    val engineStatus: String,
    val registryStatus: String,
    val voiceCount: Int,
    val queueActive: Int,
    val queueWaiting: Int,
    val queueCapacity: Int,
)

data class VoiceSynthesisRequest(
    val connection: VoiceServerConnection,
    val voiceId: String,
    val text: String,
    val language: String = "zh",
    val speed: Float = 1f,
)

data class VoicePlaybackConfig(
    val connection: VoiceServerConnection,
    val voiceId: String,
    val language: String = "zh",
)

fun interface VoicePlaybackConfigProvider {
    fun current(): VoicePlaybackConfig?
}

enum class VoiceSynthesisErrorCode {
    SERVER_NOT_CONFIGURED,
    INSECURE_SERVER_URL,
    AUTHENTICATION_FAILED,
    VOICE_NOT_FOUND,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    RESPONSE_TOO_LARGE,
    INVALID_RESPONSE,
    INVALID_AUDIO,
    TEXT_UNSUPPORTED,
    CANCELLED,
    SYNTHESIS_FAILED,
}

class VoiceSynthesisException(
    val code: VoiceSynthesisErrorCode,
    message: String,
    val requestId: String? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

interface SpeechSynthesisEngine {
    suspend fun checkHealth(connection: VoiceServerConnection): VoiceServerHealth

    suspend fun listVoices(connection: VoiceServerConnection): List<RemoteVoice>

    suspend fun synthesize(request: VoiceSynthesisRequest): VoiceAudio

    fun cancel()

    suspend fun release()
}
