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
) {
    internal val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && bearerToken.isNotBlank()

    companion object {
        internal val Unconfigured = VoiceServerConnection(baseUrl = "", bearerToken = "")
    }
}

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
    val voiceId: String,
    val text: String,
    val language: String = "zh",
    val speed: Float = 1f,
    val connection: VoiceServerConnection = VoiceServerConnection.Unconfigured,
)

data class VoicePlaybackConfig(
    val voiceId: String,
    val language: String = "zh",
    val connection: VoiceServerConnection = VoiceServerConnection.Unconfigured,
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

open class VoiceSynthesisException(
    val code: VoiceSynthesisErrorCode,
    message: String,
    val requestId: String? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

interface SpeechSynthesisEngine {
    suspend fun checkHealth(connection: VoiceServerConnection): VoiceServerHealth {
        throw UnsupportedOperationException("This speech engine does not expose server health")
    }

    suspend fun listVoices(connection: VoiceServerConnection): List<RemoteVoice> = emptyList()

    suspend fun synthesize(request: VoiceSynthesisRequest): VoiceAudio

    fun cancel()

    suspend fun release()
}
