package com.example.claims.client

import com.example.claims.config.AnthropicConfig
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.LoggerFactory

class AnthropicClient(
    vertx: Vertx,
    private val config: AnthropicConfig,
    private val toolExecutor: ClaimsToolExecutor,
) {

    private val logger = LoggerFactory.getLogger(AnthropicClient::class.java)

    private val client: WebClient = WebClient.create(
        vertx,
        WebClientOptions()
            .setSsl(true)
            .setDefaultHost("api.anthropic.com")
            .setDefaultPort(443)
            .setConnectTimeout(10_000)
            .setIdleTimeout(60),
    )

    // Tool schemas sent to Claude on every request
    private val tools = JsonArray()
        .add(tool(
            name = "check_health",
            description = "Check the health status of the claims API",
            properties = JsonObject(),
        ))
        .add(tool(
            name = "inquire_claim",
            description = "Look up the status and details of a claim by reference number and last name",
            required = listOf("referenceNumber", "lastName"),
            properties = JsonObject()
                .put("referenceNumber", prop("string", "Claim reference number (e.g. ACL-M5X2K1-AB3C)"))
                .put("lastName", prop("string", "Claimant's last name"))
                .put("dateOfBirth", prop("string", "Optional date of birth for extra verification (YYYY-MM-DD)"))
                .put("locale", prop("string", "Language code for response messages (e.g. en, fr, es)")),
        ))
        .add(tool(
            name = "list_claim_documents",
            description = "List all documents attached to a specific claim after identity verification",
            required = listOf("referenceNumber", "lastName"),
            properties = JsonObject()
                .put("referenceNumber", prop("string", "Claim reference number"))
                .put("lastName", prop("string", "Claimant's last name"))
                .put("dateOfBirth", prop("string", "Optional date of birth (YYYY-MM-DD)")),
        ))
        .add(tool(
            name = "list_documents",
            description = "List all documents in the system",
            properties = JsonObject(),
        ))

    /**
     * Sends a conversation to Claude and handles the full tool-use loop.
     * Claude may call tools zero or more times before returning a final text reply.
     *
     * @param systemPrompt  System-level instructions including injected referenceNumber + lastName
     * @param messages      Full conversation history; last entry is the new user message
     */
    suspend fun chat(
        systemPrompt: String,
        messages: List<Pair<String, String>>,
    ): String {
        // Mutable list — assistant tool-use turns and tool results are appended during the loop
        val messagesList = messages.map { (role, content) ->
            JsonObject().put("role", role).put("content", content)
        }.toMutableList()

        repeat(10) { iteration ->
            val messagesArray = JsonArray().also { arr -> messagesList.forEach { arr.add(it) } }

            val requestBody = JsonObject()
                .put("model", "claude-haiku-4-5-20251001")
                .put("max_tokens", 4096)
                .put("system", systemPrompt)
                .put("messages", messagesArray)
                .put("tools", tools)

            logger.debug("Anthropic request iteration={} messages={}", iteration + 1, messagesList.size)

            val response = client.post("/v1/messages")
                .putHeader("x-api-key", config.apiKey)
                .putHeader("anthropic-version", "2023-06-01")
                .putHeader("content-type", "application/json")
                .sendJsonObject(requestBody)
                .coAwait()

            val statusCode = response.statusCode()
            val bodyJson = response.bodyAsJsonObject()

            if (statusCode != 200) {
                val errorMsg = bodyJson?.getJsonObject("error")?.getString("message") ?: "HTTP $statusCode"
                logger.error("Anthropic API error status={} body={}", statusCode, bodyJson)
                throw RuntimeException("Anthropic API error ($statusCode): $errorMsg")
            }

            val stopReason = bodyJson.getString("stop_reason")
            val content = bodyJson.getJsonArray("content")
                ?: throw RuntimeException("Missing content array in Anthropic response")

            when (stopReason) {
                "end_turn" -> {
                    for (i in 0 until content.size()) {
                        val block = content.getJsonObject(i) ?: continue
                        if (block.getString("type") == "text") return block.getString("text") ?: ""
                    }
                    throw RuntimeException("No text block in end_turn response")
                }

                "tool_use" -> {
                    // Append assistant turn (contains tool_use blocks)
                    messagesList.add(JsonObject().put("role", "assistant").put("content", content))

                    // Execute each tool call and collect results
                    val toolResults = JsonArray()
                    for (i in 0 until content.size()) {
                        val block = content.getJsonObject(i) ?: continue
                        if (block.getString("type") != "tool_use") continue

                        val toolId   = block.getString("id")
                        val toolName = block.getString("name")
                        val toolInput = block.getJsonObject("input") ?: JsonObject()

                        logger.info("Claude calling tool={} id={}", toolName, toolId)
                        val result = toolExecutor.execute(toolName, toolInput)

                        toolResults.add(
                            JsonObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", toolId)
                                .put("content", result),
                        )
                    }

                    // Append tool results as next user turn
                    messagesList.add(JsonObject().put("role", "user").put("content", toolResults))
                }

                else -> throw RuntimeException("Unexpected stop_reason: $stopReason")
            }
        }

        throw RuntimeException("Tool loop exceeded maximum iterations (10)")
    }

    fun close() {
        client.close()
    }

    // ── schema helpers ────────────────────────────────────────────────────────

    private fun prop(type: String, description: String) =
        JsonObject().put("type", type).put("description", description)

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String> = emptyList(),
    ) = JsonObject()
        .put("name", name)
        .put("description", description)
        .put("input_schema", JsonObject()
            .put("type", "object")
            .put("properties", properties)
            .also { if (required.isNotEmpty()) it.put("required", JsonArray(required)) })
}
