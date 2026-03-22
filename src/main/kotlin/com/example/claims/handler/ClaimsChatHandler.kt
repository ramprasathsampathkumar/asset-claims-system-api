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
        "You are a helpful asset claims assistant. You help users check the status of their claims, " +
        "view attached documents, and manage their claims. Always verify the user using their reference " +
        "number and last name before performing any action. Only answer questions related to asset claims. " +
        "Be concise and friendly."

    fun chat(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val body = ctx.body().asJsonObject() ?: run {
                    respond400(ctx, "Request body is required.")
                    return@launch
                }

                val message = body.getString("message")
                val referenceNumber = body.getString("referenceNumber")
                val lastName = body.getString("lastName")

                if (message.isNullOrBlank()) {
                    respond400(ctx, "message is required.")
                    return@launch
                }
                if (referenceNumber.isNullOrBlank()) {
                    respond400(ctx, "referenceNumber is required.")
                    return@launch
                }
                if (lastName.isNullOrBlank()) {
                    respond400(ctx, "lastName is required.")
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

                val systemPrompt = "$systemPromptBase\n\n" +
                    "The user's reference number is $referenceNumber and last name is $lastName."

                logger.info("Chat request referenceNumber={} historySize={}", referenceNumber, history.size)

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
