package com.zhousl.aether.voice

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RemoteVoiceEngineTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `health parses public service status without authorization`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"status":"ok","engine":{"status":"ready"},"registry":{"status":"ready","count":2},"queue":{"active":1,"waiting":2,"capacity":4}}""",
            ),
        )
        val engine = engine()

        val health = engine.checkHealth(connection())

        assertEquals("ok", health.status)
        assertEquals("ready", health.engineStatus)
        assertEquals(2, health.voiceCount)
        assertEquals(1, health.queueActive)
        assertEquals(2, health.queueWaiting)
        assertEquals(4, health.queueCapacity)
        val request = server.takeRequest()
        assertEquals("/v1/health", request.path)
        assertEquals(null, request.getHeader("Authorization"))
    }

    @Test
    fun `voices sends bearer token and parses safe metadata`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"voices":[{"id":"6c271f7ace20","name":"Qi Xia","language":"zh","modelVersion":"v2"}]}""",
            ),
        )
        val engine = engine()

        val voices = engine.listVoices(connection())

        assertEquals(listOf(RemoteVoice("6c271f7ace20", "Qi Xia", "zh", "v2")), voices)
        val request = server.takeRequest()
        assertEquals("/v1/voices", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertFalse(request.headers.toString().contains("C:\\"))
    }

    @Test
    fun `speech sends server speed and decodes pcm16 mono wav`() = runBlocking {
        val wav = pcm16Wav(shortArrayOf(0, 1, -2, 32767), sampleRate = 32_000)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wav)),
        )
        val engine = engine()

        val audio = engine.synthesize(
            VoiceSynthesisRequest(
                connection = connection(),
                voiceId = "6c271f7ace20",
                text = "你好",
                language = "zh",
                speed = 1.25f,
            ),
        )

        assertEquals(32_000, audio.sampleRate)
        assertTrue(shortArrayOf(0, 1, -2, 32767).contentEquals(audio.samples))
        val request = server.takeRequest()
        assertEquals("/v1/speech", request.path)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("audio/wav", request.getHeader("Accept"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("6c271f7ace20", body.getString("voiceId"))
        assertEquals("你好", body.getString("text"))
        assertEquals("zh", body.getString("language"))
        assertEquals(1.25, body.getDouble("speed"), 0.0001)
    }

    @Test
    fun `http errors map to stable client errors and preserve request id`() = runBlocking {
        val cases = listOf(
            401 to VoiceSynthesisErrorCode.AUTHENTICATION_FAILED,
            404 to VoiceSynthesisErrorCode.VOICE_NOT_FOUND,
            429 to VoiceSynthesisErrorCode.RATE_LIMITED,
            503 to VoiceSynthesisErrorCode.SERVICE_UNAVAILABLE,
            504 to VoiceSynthesisErrorCode.TIMEOUT,
        )
        for ((status, expectedCode) in cases) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(status)
                    .setHeader("Content-Type", "application/json")
                    .apply { if (status == 429) setHeader("Retry-After", "7") }
                    .setBody("""{"error":{"code":"test","message":"server message","requestId":"req-$status"}}"""),
            )
            val error = expectVoiceError {
                engine().synthesize(request())
            }
            assertEquals(expectedCode, error.code)
            assertEquals("req-$status", error.requestId)
            if (status == 429) assertEquals(7L, error.retryAfterSeconds)
        }
    }

    @Test
    fun `optional strict policy rejects http while default policy only permits local hosts`() = runBlocking {
        val strictError = expectVoiceError {
            RemoteVoiceEngine(allowInsecureLocalHttp = false).checkHealth(connection())
        }
        assertEquals(VoiceSynthesisErrorCode.INSECURE_SERVER_URL, strictError.code)

        val publicError = expectVoiceError {
            RemoteVoiceEngine().checkHealth(
                VoiceServerConnection("http://example.com", "token"),
            )
        }
        assertEquals(VoiceSynthesisErrorCode.INSECURE_SERVER_URL, publicError.code)
    }

    @Test
    fun `speech speed is limited to server supported range`() = runBlocking {
        for (speed in listOf(0.74f, 1.26f, Float.NaN)) {
            val error = expectVoiceError {
                engine().synthesize(request().copy(speed = speed))
            }
            assertEquals(VoiceSynthesisErrorCode.TEXT_UNSUPPORTED, error.code)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `speech rejects missing content type malformed wav and oversized body`() = runBlocking {
        val wav = pcm16Wav(shortArrayOf(1, 2), 32_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(wav)))
        assertEquals(
            VoiceSynthesisErrorCode.INVALID_AUDIO,
            expectVoiceError { engine().synthesize(request()) }.code,
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody("not a wav"),
        )
        assertEquals(
            VoiceSynthesisErrorCode.INVALID_AUDIO,
            expectVoiceError { engine().synthesize(request()) }.code,
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/wav")
                .setBody(Buffer().write(wav)),
        )
        assertEquals(
            VoiceSynthesisErrorCode.RESPONSE_TOO_LARGE,
            expectVoiceError { engine(maxAudioBytes = 16).synthesize(request()) }.code,
        )
    }

    @Test
    fun `cancel aborts an active request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val engine = engine()
        val result = async(Dispatchers.IO) {
            runCatching { engine.synthesize(request()) }.exceptionOrNull()
        }
        assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)

        engine.cancel()

        val error = withTimeout(5_000) { result.await() }
        assertTrue(error is CancellationException)
    }

    @Test
    fun `wav decoder rejects stereo and inconsistent riff size`() {
        val stereo = pcm16Wav(shortArrayOf(1, 2), 32_000).also { bytes ->
            bytes[22] = 2
            bytes[32] = 4
            writeIntLE(bytes, 28, 128_000)
        }
        assertEquals(
            VoiceSynthesisErrorCode.INVALID_AUDIO,
            expectVoiceErrorBlocking { WavPcm16Decoder.decode(stereo) }.code,
        )

        val invalidSize = pcm16Wav(shortArrayOf(1), 32_000).also { it[4] = 0 }
        assertEquals(
            VoiceSynthesisErrorCode.INVALID_AUDIO,
            expectVoiceErrorBlocking { WavPcm16Decoder.decode(invalidSize) }.code,
        )

        val tooLong = pcm16SilenceWav(sampleCount = 8_000 * 180 + 1, sampleRate = 8_000)
        assertEquals(
            VoiceSynthesisErrorCode.INVALID_AUDIO,
            expectVoiceErrorBlocking { WavPcm16Decoder.decode(tooLong) }.code,
        )
    }

    private fun engine(maxAudioBytes: Long = 32L * 1024 * 1024) = RemoteVoiceEngine(
        maxAudioResponseBytes = maxAudioBytes,
    )

    private fun connection() = VoiceServerConnection("http://127.0.0.1:${server.port}/", "secret-token")

    private fun request() = VoiceSynthesisRequest(
        connection = connection(),
        voiceId = "6c271f7ace20",
        text = "测试",
    )

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private suspend fun expectVoiceError(block: suspend () -> Unit): VoiceSynthesisException {
        try {
            block()
            fail("Expected VoiceSynthesisException")
        } catch (error: VoiceSynthesisException) {
            return error
        }
        error("unreachable")
    }

    private fun expectVoiceErrorBlocking(block: () -> Unit): VoiceSynthesisException {
        try {
            block()
            fail("Expected VoiceSynthesisException")
        } catch (error: VoiceSynthesisException) {
            return error
        }
        error("unreachable")
    }
}

private fun pcm16Wav(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataSize = samples.size * 2
    val output = ByteArrayOutputStream(44 + dataSize)
    output.write("RIFF".toByteArray(Charsets.US_ASCII))
    output.write(intLe(36 + dataSize))
    output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    output.write(intLe(16))
    output.write(shortLe(1))
    output.write(shortLe(1))
    output.write(intLe(sampleRate))
    output.write(intLe(sampleRate * 2))
    output.write(shortLe(2))
    output.write(shortLe(16))
    output.write("data".toByteArray(Charsets.US_ASCII))
    output.write(intLe(dataSize))
    samples.forEach { output.write(shortLe(it.toInt())) }
    return output.toByteArray()
}

private fun pcm16SilenceWav(sampleCount: Int, sampleRate: Int): ByteArray {
    val dataSize = sampleCount * 2
    return ByteArray(44 + dataSize).also { bytes ->
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        writeIntLE(bytes, 4, 36 + dataSize)
        "WAVEfmt ".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        writeIntLE(bytes, 16, 16)
        shortLe(1).copyInto(bytes, 20)
        shortLe(1).copyInto(bytes, 22)
        writeIntLE(bytes, 24, sampleRate)
        writeIntLE(bytes, 28, sampleRate * 2)
        shortLe(2).copyInto(bytes, 32)
        shortLe(16).copyInto(bytes, 34)
        "data".toByteArray(Charsets.US_ASCII).copyInto(bytes, 36)
        writeIntLE(bytes, 40, dataSize)
    }
}

private fun intLe(value: Int): ByteArray = byteArrayOf(
    value.toByte(),
    (value ushr 8).toByte(),
    (value ushr 16).toByte(),
    (value ushr 24).toByte(),
)

private fun shortLe(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
    val encoded = intLe(value)
    encoded.copyInto(bytes, offset)
}
