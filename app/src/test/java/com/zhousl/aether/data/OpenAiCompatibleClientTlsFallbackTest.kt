package com.zhousl.aether.data

import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleClientTlsFallbackTest {
    @Test
    fun detectsClientHelloHandshakeFailureSignals() {
        assertTrue(
            isClientHelloTlsHandshakeFailure(
                SSLHandshakeException(
                    "Read error: SSLV3_ALERT_HANDSHAKE_FAILURE; HANDSHAKE_FAILURE_ON_CLIENT_HELLO",
                )
            )
        )
        assertTrue(
            isClientHelloTlsHandshakeFailure(
                RuntimeException(
                    "wrapper",
                    SSLProtocolException("Failure in SSL library, usually a protocol error: handshake failure"),
                )
            )
        )
        assertFalse(
            isClientHelloTlsHandshakeFailure(
                SSLHandshakeException("CertPathValidatorException: Trust anchor for certification path not found.")
            )
        )
    }

    @Test
    fun compatibilityClientsRestrictProtocolsAndTlsVersion() {
        val defaultClient = buildDefaultLlmHttpClient()
        assertTrue(defaultClient.protocols.contains(Protocol.HTTP_2))
        assertTrue(defaultClient.protocols.contains(Protocol.HTTP_1_1))

        val http1Client = buildHttp1LlmHttpClient(defaultClient)
        assertEquals(listOf(Protocol.HTTP_1_1), http1Client.protocols)

        val tls12Http1Client = buildTls12Http1LlmHttpClient(defaultClient)
        assertEquals(listOf(Protocol.HTTP_1_1), tls12Http1Client.protocols)
        assertEquals(listOf(TlsVersion.TLS_1_2), tls12Http1Client.connectionSpecs.first().tlsVersions)
        assertTrue(tls12Http1Client.connectionSpecs.contains(ConnectionSpec.CLEARTEXT))
    }

    @Test
    fun retriesClientHelloFailuresThroughCompatibilityClients() = runBlocking {
        val baseClient = buildDefaultLlmHttpClient()
        val request = Request.Builder()
            .url("https://relay.example.test/v1/models")
            .build()
        val clients = mutableListOf<OkHttpClient>()

        val result = executeLlmCallWithTlsFallback(baseClient, request) { client, _ ->
            clients += client
            if (clients.size < 3) {
                throw SSLProtocolException("HANDSHAKE_FAILURE_ON_CLIENT_HELLO")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, clients.size)
        assertTrue(clients[0].protocols.contains(Protocol.HTTP_2))
        assertEquals(listOf(Protocol.HTTP_1_1), clients[1].protocols)
        assertEquals(listOf(Protocol.HTTP_1_1), clients[2].protocols)
        assertEquals(listOf(TlsVersion.TLS_1_2), clients[2].connectionSpecs.first().tlsVersions)
    }

    @Test
    fun reportsFriendlyErrorAfterCompatibilityRetriesFail() = runBlocking {
        val baseClient = buildDefaultLlmHttpClient()
        val request = Request.Builder()
            .url("https://relay.example.test/v1/models")
            .build()
        var failure: Throwable? = null

        try {
            executeLlmCallWithTlsFallback(baseClient, request) { _, _ ->
                throw SSLProtocolException("SSLV3_ALERT_HANDSHAKE_FAILURE")
            }
        } catch (throwable: Throwable) {
            failure = throwable
        }

        val tlsFailure = failure as LlmTlsCompatibilityException
        assertEquals(3, tlsFailure.attemptCount)
        assertTrue(tlsFailure.message.orEmpty().contains("HTTP/1.1"))
        assertTrue(tlsFailure.message.orEmpty().contains("TLS 1.2"))
    }
}
