package com.example.claims.handler

import com.example.claims.client.AnthropicClient
import io.vertx.core.json.Json
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ClaimsChatHandler(
    private val anthropicClient: AnthropicClient,
    private val scope: CoroutineScope,
) {

    private val logger = LoggerFactory.getLogger(ClaimsChatHandler::class.java)

    private val systemPromptBase =
        "You are a helpful assistant for an asset claims service. You can help users with general " +
        "inquiries, find branch locations and ATMs, check claim status, and view documents. " +
        "Be concise and friendly. For store/branch/ATM lookups you can search by location, type, " +
        "or services offered. For claim-specific actions (status, documents) you need the user's " +
        "reference number and last name — if not provided, politely ask for them."

    fun chat(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val body = ctx.body().asJsonObject() ?: run {
                    respond400(ctx, "Request body is required.")
                    return@launch
                }

                val message = body.getString("message")
                val referenceNumber = body.getString("referenceNumber")?.takeIf { it.isNotBlank() }
                val lastName = body.getString("lastName")?.takeIf { it.isNotBlank() }

                if (message.isNullOrBlank()) {
                    respond400(ctx, "message is required.")
                    return@launch
                }

                // Build conversation history from the history array, then append the new user message
                val history = mutableListOf<Pair<String, String>>()
                val historyArray = body.getJsonArray("history")
                if (historyArray != null) {
                    for (i in 0 until historyArray.size()) {
                        val entry = historyArray.getJsonObject(i) ?: continue
                        val role = entry.getString("role") ?: continue
                        val content = entry.getString("content") ?: continue
                        if (role == "user" || role == "assistant") {
                            history.add(role to content)
                        }
                    }
                }
                history.add("user" to message)

                val systemPrompt = buildString {
                    append(systemPromptBase)
                    if (referenceNumber != null && lastName != null) {
                        append("\n\nThe user's reference number is $referenceNumber and last name is $lastName.")
                    } else if (referenceNumber != null) {
                        append("\n\nThe user's reference number is $referenceNumber.")
                    }
                }

                logger.info("Chat request referenceNumber={} lastName={} historySize={}", referenceNumber, lastName, history.size)

                val reply = anthropicClient.chat(systemPrompt, history)

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end("""{"reply":${Json.encode(reply)}}""")

            } catch (e: Exception) {
                logger.error("Chat request failed", e)
                val errMsg = esc(e.message ?: "Unexpected error").take(200)
                ctx.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end("""{"error":"$errMsg"}""")
            }
        }
    }

    private fun respond400(ctx: RoutingContext, message: String) {
        ctx.response()
            .setStatusCode(400)
            .putHeader("Content-Type", "application/json")
            .end("""{"error":"${esc(message)}"}""")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
