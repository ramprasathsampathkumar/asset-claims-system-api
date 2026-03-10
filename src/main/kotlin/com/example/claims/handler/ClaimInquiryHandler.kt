package com.example.claims.handler

import com.example.claims.repository.ClaimRepository
import com.example.claims.validation.FieldError
import com.example.claims.validation.InquiryValidator
import com.example.claims.validation.Messages
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ClaimInquiryHandler(
    private val repository: ClaimRepository,
    private val scope: CoroutineScope,
) {

    private val logger = LoggerFactory.getLogger(ClaimInquiryHandler::class.java)

    fun inquireClaim(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val body = ctx.body().asJsonObject() ?: run {
                    respond422(ctx, listOf(FieldError("request", "missing_body", "Request body is required.")))
                    return@launch
                }

                val locale = body.getString("locale") ?: "en"
                val errors = InquiryValidator.validate(body, locale)

                if (errors.isNotEmpty()) {
                    respond422(ctx, errors)
                    return@launch
                }

                if (!repository.isConnected()) {
                    logger.error("Couchbase not connected — cannot query claim")
                    respond500(ctx)
                    return@launch
                }

                val referenceNumber = body.getString("referenceNumber")
                val lastName = body.getString("lastName")
                val dateOfBirth = body.getString("dateOfBirth")

                val claim = repository.findByReferenceAndLastName(referenceNumber, lastName, dateOfBirth)

                if (claim == null) {
                    logger.info("Inquiry: claim not found referenceNumber={}", referenceNumber)
                    respond404(ctx, Messages.get(locale, "not_found"))
                    return@launch
                }

                logger.info("Inquiry: claim found referenceNumber={}", referenceNumber)

                val status = esc(claim.getString("status") ?: "UNKNOWN")
                val submittedAt = esc(claim.getString("submittedAt") ?: "")
                val createdAt = esc(claim.getString("createdAt") ?: "")
                val updatedAt = esc(claim.getString("updatedAt") ?: "")
                val claimLocale = esc(claim.getString("locale") ?: "")
                val assetType = esc(
                    claim.getJsonObject("step3")?.getString("assetType") ?: "",
                )
                val currency = esc(
                    claim.getJsonObject("step4")?.getString("currency") ?: "",
                )

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(
                        """{"success":true,"referenceNumber":"$referenceNumber","status":"$status","locale":"$claimLocale","submittedAt":"$submittedAt","assetType":"$assetType","currency":"$currency","createdAt":"$createdAt","updatedAt":"$updatedAt"}""",
                    )

            } catch (e: Exception) {
                logger.error("Unexpected error processing inquiry", e)
                respond500(ctx)
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun respond404(ctx: RoutingContext, message: String) {
        val msg = esc(message)
        ctx.response()
            .setStatusCode(404)
            .putHeader("Content-Type", "application/json")
            .end("""{"success":false,"message":"$msg"}""")
    }

    private fun respond422(ctx: RoutingContext, errors: List<FieldError>) {
        val errorsJson = errors.joinToString(",") { e ->
            """{"field":"${esc(e.field)}","code":"${esc(e.code)}","message":"${esc(e.message)}"}"""
        }
        ctx.response()
            .setStatusCode(422)
            .putHeader("Content-Type", "application/json")
            .end("""{"success":false,"message":"Validation failed. Please review your request.","errors":[$errorsJson]}""")
    }

    private fun respond500(ctx: RoutingContext) {
        ctx.response()
            .setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end("""{"success":false,"message":"An unexpected server error occurred. Please try again."}""")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
