package com.zhousl.aether.voice

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RemoteVoiceEngine(
    private val httpClient: OkHttpClient = defaultVoiceHttpClient(),
    private val allowInsecureLocalHttp: Boolean = true,
    private val maxAudioResponseBytes: Long = MAX_AUDIO_RESPONSE_BYTES,
) : SpeechSynthesisEngine {
    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    override suspend fun checkHealth(connection: VoiceServerConnection): VoiceServerHealth {
        val request = Request.Builder()
            .url(endpoint(connection.baseUrl, HEALTH_PATH))
            .get()
            .build()
        val response = execute(request, MAX_JSON_RESPONSE_BYTES)
        if (response.code !in 200..299) throw response.toServiceException()
        return parseHealth(response.body)
    }

    override suspend fun listVoices(connection: VoiceServerConnection): List<RemoteVoice> {
        val request = authenticatedRequest(connection, VOICES_PATH)
            .get()
            .build()
        val response = execute(request, MAX_JSON_RESPONSE_BYTES)
        if (response.code !in 200..299) throw response.toServiceException()
        return parseVoices(response.body)
    }

    override suspend fun synthesize(request: VoiceSynthesisRequest): VoiceAudio {
        validateSynthesisRequest(request)
        val body = JSONObject()
            .put("voiceId", request.voiceId.trim())
            .put("text", request.text)
            .put("language", request.language.trim())
            .put("speed", request.speed.toDouble())
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = authenticatedRequest(request.connection, SPEECH_PATH)
            .post(body)
            .header("Accept", WAV_MEDIA_TYPE)
            .build()
        val response = execute(httpRequest, maxAudioResponseBytes)
        if (response.code !in 200..299) throw response.toServiceException()
        val mediaType = response.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
        if (mediaType != WAV_MEDIA_TYPE) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.INVALID_AUDIO,
                "The voice server returned an unsupported audio type",
                requestId = response.requestId,
            )
        }
        return try {
            WavPcm16Decoder.decode(response.body)
        } catch (error: VoiceSynthesisException) {
            throw error
        } catch (error: Throwable) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.INVALID_AUDIO,
                "The voice server returned invalid WAV audio",
                requestId = response.requestId,
                cause = error,
            )
        }
    }

    override fun cancel() {
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        snapshot.forEach(Call::cancel)
    }

    override suspend fun release() {
        cancel()
    }

    private fun authenticatedRequest(
        connection: VoiceServerConnection,
        path: String,
    ): Request.Builder {
        val token = connection.bearerToken.trim()
        if (token.isBlank()) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.SERVER_NOT_CONFIGURED,
                "Configure the voice server token before using speech",
            )
        }
        return Request.Builder()
            .url(endpoint(connection.baseUrl, path))
            .header("Authorization", "Bearer $token")
    }

    private fun endpoint(baseUrlValue: String, path: String): HttpUrl {
        val candidate = baseUrlValue.trim()
        if (candidate.isBlank()) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.SERVER_NOT_CONFIGURED,
                "Configure the voice server address before using speech",
            )
        }
        val baseUrl = candidate.toHttpUrlOrNull()
            ?: throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.SERVER_NOT_CONFIGURED,
                "The voice server address is invalid",
            )
        if (baseUrl.username.isNotEmpty() || baseUrl.password.isNotEmpty() ||
            baseUrl.query != null || baseUrl.fragment != null
        ) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.SERVER_NOT_CONFIGURED,
                "The voice server address must not contain credentials, a query, or a fragment",
            )
        }
        when (baseUrl.scheme) {
            "https" -> Unit
            "http" -> if (!allowInsecureLocalHttp || !isLocalNetworkHost(baseUrl.host)) {
                throw VoiceSynthesisException(
                    VoiceSynthesisErrorCode.INSECURE_SERVER_URL,
                    "Use HTTPS or a local-network HTTP address for the voice server",
                )
            }
            else -> throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.SERVER_NOT_CONFIGURED,
                "The voice server address must use HTTPS or HTTP",
            )
        }
        return baseUrl.newBuilder().addPathSegments(path).build()
    }

    private suspend fun execute(request: Request, maxSuccessBytes: Long): HttpPayload {
        try {
            return executeCall(request, maxSuccessBytes)
        } catch (error: VoiceSynthesisException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.TIMEOUT,
                "The voice server timed out",
                cause = error,
            )
        } catch (error: IOException) {
            val message = when (error) {
                is UnknownHostException -> "The voice server address could not be resolved"
                is ConnectException -> "The voice server could not be reached"
                is SSLException -> "A secure connection to the voice server could not be established"
                else -> "The voice server connection failed"
            }
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.NETWORK_UNAVAILABLE,
                message,
                cause = error,
            )
        }
    }

    private suspend fun executeCall(request: Request, maxSuccessBytes: Long): HttpPayload =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            activeCalls += call
            continuation.invokeOnCancellation {
                activeCalls -= call
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        activeCalls -= call
                        if (!continuation.isActive) return
                        if (call.isCanceled()) {
                            continuation.cancel(CancellationException("Voice request was cancelled", e))
                        } else {
                            continuation.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        activeCalls -= call
                        response.use {
                            val result = runCatching {
                                val limit = if (it.isSuccessful) maxSuccessBytes else MAX_ERROR_RESPONSE_BYTES
                                val responseBody = it.body
                                    ?: throw VoiceSynthesisException(
                                        VoiceSynthesisErrorCode.INVALID_RESPONSE,
                                        "The voice server returned an empty response",
                                    )
                                HttpPayload(
                                    code = it.code,
                                    body = readBodyWithLimit(responseBody.byteStream(), responseBody.contentLength(), limit) {
                                        if (!continuation.isActive) {
                                            throw CancellationException("Voice request was cancelled")
                                        }
                                    },
                                    contentType = responseBody.contentType()?.toString(),
                                    requestId = it.header("X-Request-ID"),
                                    retryAfterSeconds = it.header("Retry-After")?.trim()?.toLongOrNull(),
                                )
                            }
                            if (!continuation.isActive) return
                            result.fold(
                                onSuccess = continuation::resume,
                                onFailure = continuation::resumeWithException,
                            )
                        }
                    }
                },
            )
        }

    private fun validateSynthesisRequest(request: VoiceSynthesisRequest) {
        if (request.voiceId.isBlank()) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.SERVER_NOT_CONFIGURED,
                "Select a server voice before using speech",
            )
        }
        if (request.text.isBlank() || request.text.length > MAX_TEXT_LENGTH) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.TEXT_UNSUPPORTED,
                "Each speech segment must contain 1 to $MAX_TEXT_LENGTH characters",
            )
        }
        if (request.language.isBlank() || request.language.length > MAX_LANGUAGE_LENGTH) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.TEXT_UNSUPPORTED,
                "The speech language is invalid",
            )
        }
        if (!request.speed.isFinite() || request.speed !in MIN_SPEED..MAX_SPEED) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.TEXT_UNSUPPORTED,
                "Speech speed must be between $MIN_SPEED and $MAX_SPEED",
            )
        }
    }

    private fun HttpPayload.toServiceException(): VoiceSynthesisException {
        val serverError = parseServerError(body)
        val effectiveRequestId = serverError.requestId ?: requestId
        val mappedCode = when (code) {
            400, 413, 422 -> VoiceSynthesisErrorCode.TEXT_UNSUPPORTED
            401, 403 -> VoiceSynthesisErrorCode.AUTHENTICATION_FAILED
            404 -> VoiceSynthesisErrorCode.VOICE_NOT_FOUND
            408, 504 -> VoiceSynthesisErrorCode.TIMEOUT
            429 -> VoiceSynthesisErrorCode.RATE_LIMITED
            502, 503 -> VoiceSynthesisErrorCode.SERVICE_UNAVAILABLE
            else -> VoiceSynthesisErrorCode.SYNTHESIS_FAILED
        }
        val fallback = when (mappedCode) {
            VoiceSynthesisErrorCode.AUTHENTICATION_FAILED -> "The voice server rejected the token"
            VoiceSynthesisErrorCode.VOICE_NOT_FOUND -> "The selected server voice is unavailable"
            VoiceSynthesisErrorCode.RATE_LIMITED -> "The voice server queue is full; try again shortly"
            VoiceSynthesisErrorCode.SERVICE_UNAVAILABLE -> "The voice synthesis service is unavailable"
            VoiceSynthesisErrorCode.TIMEOUT -> "The voice synthesis request timed out"
            VoiceSynthesisErrorCode.TEXT_UNSUPPORTED -> "The voice server rejected this text"
            else -> "The voice server could not synthesize this message"
        }
        return VoiceSynthesisException(
            code = mappedCode,
            message = serverError.message?.takeIf(String::isNotBlank) ?: fallback,
            requestId = effectiveRequestId,
            retryAfterSeconds = retryAfterSeconds,
        )
    }

    private companion object {
        const val HEALTH_PATH = "v1/health"
        const val VOICES_PATH = "v1/voices"
        const val SPEECH_PATH = "v1/speech"
        const val WAV_MEDIA_TYPE = "audio/wav"
        const val MAX_TEXT_LENGTH = 600
        const val MAX_LANGUAGE_LENGTH = 16
        const val MIN_SPEED = 0.75f
        const val MAX_SPEED = 1.25f
        const val MAX_AUDIO_RESPONSE_BYTES = 32L * 1024 * 1024
        const val MAX_JSON_RESPONSE_BYTES = 1024L * 1024
        const val MAX_ERROR_RESPONSE_BYTES = 64L * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private data class HttpPayload(
    val code: Int,
    val body: ByteArray,
    val contentType: String?,
    val requestId: String?,
    val retryAfterSeconds: Long?,
)

private data class ServerError(
    val message: String?,
    val requestId: String?,
)

private fun parseHealth(bytes: ByteArray): VoiceServerHealth = bytes.parseJsonBody("health") { root ->
    val engine = root.requireObject("engine")
    val registry = root.requireObject("registry")
    val queue = root.requireObject("queue")
    VoiceServerHealth(
        status = root.requireNonBlankString("status"),
        engineStatus = engine.requireNonBlankString("status"),
        registryStatus = registry.requireNonBlankString("status"),
        voiceCount = registry.requireNonNegativeInt("count"),
        queueActive = queue.requireNonNegativeInt("active"),
        queueWaiting = queue.requireNonNegativeInt("waiting"),
        queueCapacity = queue.requireNonNegativeInt("capacity"),
    )
}

private fun parseVoices(bytes: ByteArray): List<RemoteVoice> = bytes.parseJsonBody("voice list") { root ->
    val array = root.optJSONArray("voices")
        ?: throw JSONException("Missing voices")
    val seen = mutableSetOf<String>()
    List(array.length()) { index ->
        val item = array.optJSONObject(index) ?: throw JSONException("Invalid voice entry")
        val voice = RemoteVoice(
            id = item.requireNonBlankString("id"),
            name = item.requireNonBlankString("name"),
            language = item.requireNonBlankString("language"),
            modelVersion = item.requireNonBlankString("modelVersion"),
        )
        if (!seen.add(voice.id)) throw JSONException("Duplicate voice id")
        voice
    }
}

private inline fun <T> ByteArray.parseJsonBody(label: String, block: (JSONObject) -> T): T {
    try {
        return block(JSONObject(toString(Charsets.UTF_8)))
    } catch (error: VoiceSynthesisException) {
        throw error
    } catch (error: Throwable) {
        throw VoiceSynthesisException(
            VoiceSynthesisErrorCode.INVALID_RESPONSE,
            "The voice server returned an invalid $label response",
            cause = error,
        )
    }
}

private fun parseServerError(bytes: ByteArray): ServerError = runCatching {
    val error = JSONObject(bytes.toString(Charsets.UTF_8)).optJSONObject("error") ?: return@runCatching ServerError(null, null)
    ServerError(
        message = error.optString("message")
            .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ")
            .trim()
            .take(240)
            .ifBlank { null },
        requestId = error.optString("requestId").trim().take(128).ifBlank { null },
    )
}.getOrDefault(ServerError(null, null))

private fun JSONObject.requireObject(name: String): JSONObject =
    optJSONObject(name) ?: throw JSONException("Missing $name")

private fun JSONObject.requireNonBlankString(name: String): String =
    optString(name).trim().takeIf(String::isNotEmpty) ?: throw JSONException("Missing $name")

private fun JSONObject.requireNonNegativeInt(name: String): Int {
    if (!has(name)) throw JSONException("Missing $name")
    return getInt(name).takeIf { it >= 0 } ?: throw JSONException("Invalid $name")
}

private fun readBodyWithLimit(
    input: java.io.InputStream,
    declaredLength: Long,
    maxBytes: Long,
    ensureActive: () -> Unit,
): ByteArray {
    if (maxBytes <= 0 || declaredLength > maxBytes) {
        throw VoiceSynthesisException(
            VoiceSynthesisErrorCode.RESPONSE_TOO_LARGE,
            "The voice server response is too large",
        )
    }
    val output = ByteArrayOutputStream(
        when {
            declaredLength in 1..Int.MAX_VALUE.toLong() -> declaredLength.toInt()
            else -> 8 * 1024
        },
    )
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.RESPONSE_TOO_LARGE,
                "The voice server response is too large",
            )
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun isLocalNetworkHost(hostValue: String): Boolean {
    val host = hostValue.lowercase(Locale.US)
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    if ('.' !in host && ':' !in host) return true
    if (host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        octets[0] == 127 ||
        (octets[0] == 169 && octets[1] == 254) ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 100 && octets[1] in 64..127)
}

private fun defaultVoiceHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(3, TimeUnit.MINUTES)
    .writeTimeout(30, TimeUnit.SECONDS)
    .callTimeout(4, TimeUnit.MINUTES)
    .retryOnConnectionFailure(false)
    .build()

internal object WavPcm16Decoder {
    private val allowedSampleRates = setOf(8_000, 12_000, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)

    fun decode(bytes: ByteArray): VoiceAudio {
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            invalidAudio()
        }
        val riffEnd = u32(bytes, 4) + 8L
        if (riffEnd != bytes.size.toLong()) invalidAudio()

        var offset = 12L
        var format: WavFormat? = null
        var dataOffset = -1
        var dataSize = -1
        while (offset < riffEnd) {
            if (offset + 8 > riffEnd) invalidAudio()
            val headerOffset = offset.toInt()
            val id = ascii(bytes, headerOffset, 4)
            val size = u32(bytes, headerOffset + 4)
            val contentOffset = offset + 8
            val contentEnd = contentOffset + size
            val paddedEnd = contentEnd + (size and 1L)
            if (contentEnd > riffEnd || paddedEnd > riffEnd || contentEnd > Int.MAX_VALUE) invalidAudio()
            when (id) {
                "fmt " -> {
                    if (format != null || size < 16) invalidAudio()
                    val at = contentOffset.toInt()
                    val audioFormat = u16(bytes, at)
                    val channels = u16(bytes, at + 2)
                    val sampleRate = u32(bytes, at + 4).toInt()
                    val byteRate = u32(bytes, at + 8).toInt()
                    val blockAlign = u16(bytes, at + 12)
                    val bitsPerSample = u16(bytes, at + 14)
                    if (audioFormat != 1 || channels != 1 || sampleRate !in allowedSampleRates ||
                        bitsPerSample != 16 || blockAlign != 2 || byteRate != sampleRate * 2
                    ) {
                        invalidAudio()
                    }
                    format = WavFormat(sampleRate)
                }
                "data" -> {
                    if (dataOffset >= 0 || size <= 0 || size % 2L != 0L) invalidAudio()
                    dataOffset = contentOffset.toInt()
                    dataSize = size.toInt()
                }
            }
            offset = paddedEnd
        }
        if (offset != riffEnd || format == null || dataOffset < 0 || dataSize <= 0) invalidAudio()
        val sampleCount = dataSize / 2
        if (sampleCount.toLong() > format.sampleRate.toLong() * MAX_AUDIO_DURATION_SECONDS) {
            throw VoiceSynthesisException(
                VoiceSynthesisErrorCode.INVALID_AUDIO,
                "The voice server returned audio longer than $MAX_AUDIO_DURATION_SECONDS seconds",
            )
        }
        val samples = ShortArray(sampleCount)
        var byteOffset = dataOffset
        for (index in samples.indices) {
            samples[index] = u16(bytes, byteOffset).toShort()
            byteOffset += 2
        }
        return VoiceAudio(samples, format.sampleRate)
    }

    private fun invalidAudio(): Nothing = throw VoiceSynthesisException(
        VoiceSynthesisErrorCode.INVALID_AUDIO,
        "The voice server returned invalid PCM16 mono WAV audio",
    )

    private data class WavFormat(val sampleRate: Int)

    private const val MAX_AUDIO_DURATION_SECONDS = 180
}

private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
    if (offset < 0 || length < 0 || offset + length > bytes.size) return ""
    return String(bytes, offset, length, Charsets.US_ASCII)
}

private fun u16(bytes: ByteArray, offset: Int): Int {
    if (offset < 0 || offset + 2 > bytes.size) return -1
    return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}

private fun u32(bytes: ByteArray, offset: Int): Long {
    if (offset < 0 || offset + 4 > bytes.size) return -1
    return (bytes[offset].toLong() and 0xff) or
        ((bytes[offset + 1].toLong() and 0xff) shl 8) or
        ((bytes[offset + 2].toLong() and 0xff) shl 16) or
        ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
