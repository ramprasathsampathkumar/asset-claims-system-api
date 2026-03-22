package com.example.claims.client

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.LoggerFactory
import java.net.URI

class ClaimsToolExecutor(vertx: Vertx, baseUrl: String) {

    private val logger = LoggerFactory.getLogger(ClaimsToolExecutor::class.java)

    private val client: WebClient = URI(baseUrl).let { uri ->
        val ssl = uri.scheme == "https"
        val port = if (uri.port != -1) uri.port else if (ssl) 443 else 80
        WebClient.create(
            vertx,
            WebClientOptions()
                .setSsl(ssl)
                .setDefaultHost(uri.host)
                .setDefaultPort(port)
                .setConnectTimeout(10_000)
                .setIdleTimeout(30),
        )
    }

    suspend fun execute(toolName: String, input: JsonObject): String {
        logger.info("Executing tool={} input={}", toolName, input)
        return try {
            when (toolName) {
                "check_health"          -> checkHealth()
                "inquire_claim"         -> inquireClaim(input)
                "list_claim_documents"  -> listClaimDocuments(input)
                "list_documents"        -> listDocuments()
                else -> """{"error":"Unknown tool: $toolName"}"""
            }
        } catch (e: Exception) {
            logger.error("Tool execution failed tool={}", toolName, e)
            """{"error":"${e.message?.take(200)?.replace("\"", "\\\"")}"}"""
        }
    }

    private suspend fun checkHealth(): String =
        client.get("/health").send().coAwait().bodyAsString() ?: "{}"

    private suspend fun inquireClaim(input: JsonObject): String {
        val body = JsonObject()
            .put("referenceNumber", input.getString("referenceNumber"))
            .put("lastName", input.getString("lastName"))
        input.getString("dateOfBirth")?.let { body.put("dateOfBirth", it) }
        input.getString("locale")?.let { body.put("locale", it) }
        return client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(body)
            .coAwait()
            .bodyAsString() ?: "{}"
    }

    private suspend fun listClaimDocuments(input: JsonObject): String {
        val body = JsonObject()
            .put("referenceNumber", input.getString("referenceNumber"))
            .put("lastName", input.getString("lastName"))
        input.getString("dateOfBirth")?.let { body.put("dateOfBirth", it) }
        return client.post("/api/documents/by-claim")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(body)
            .coAwait()
            .bodyAsString() ?: "{}"
    }

    private suspend fun listDocuments(): String =
        client.get("/api/documents").send().coAwait().bodyAsString() ?: "{}"

    fun close() {
        client.close()
    }
}
