package com.example.claims.handler

import com.example.claims.repository.ClaimRepository
import com.example.claims.validation.BankFieldsValidator
import com.example.claims.validation.FieldError
import com.example.claims.validation.Step2Validator
import com.example.claims.validation.Step3Validator
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ClaimHandler(
    private val repository: ClaimRepository,
    private val scope: CoroutineScope,
) {

    private val logger = LoggerFactory.getLogger(ClaimHandler::class.java)

    fun submitClaim(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val body = ctx.body().asJsonObject() ?: run {
                    respond422(ctx, listOf(FieldError("request", "missing_body", "Request body is required.")))
                    return@launch
                }

                val locale = body.getString("locale") ?: "en"
                val errors = mutableListOf<FieldError>()

                // ── step1: confirmed must be true ────────────────────────────
                val step1 = body.getJsonObject("step1")
                if (step1 == null || step1.getBoolean("confirmed") != true) {
                    errors += FieldError("step1.confirmed", "must_be_true", "You must confirm to proceed.")
                }

                // ── step2: personal details validation ───────────────────────
                val step2 = body.getJsonObject("step2")
                if (step2 != null) {
                    errors += Step2Validator.validate(step2, locale)
                }

                // ── step3: discriminator validation ──────────────────────────
                val step3 = body.getJsonObject("step3")
                if (step3 != null) {
                    errors += Step3Validator.validate(step3, locale)
                }

                // ── step4: currency-specific bank field validation ────────────
                val step4 = body.getJsonObject("step4")
                if (step4 != null) {
                    val currency = step4.getString("currency")
                    val bankFields = step4.getJsonObject("bankFields")
                    if (currency != null && bankFields != null) {
                        val bankErrors = BankFieldsValidator.validate(
                            currency,
                            bankFields.map.mapValues { it.value },
                            locale,
                        )
                        if (bankErrors.isNotEmpty()) {
                            logger.warn("Bank fields validation failed currency={} errors={}", currency, bankErrors.size)
                            errors += bankErrors
                        }
                    }
                }

                if (errors.isNotEmpty()) {
                    respond422(ctx, errors)
                    return@launch
                }

                // ── Persist ──────────────────────────────────────────────────
                if (!repository.isConnected()) {
                    logger.error("Couchbase not connected — cannot persist claim")
                    respond500(ctx)
                    return@launch
                }

                val referenceNumber = repository.saveClaim(body)
                logger.info("Claim submitted referenceNumber={}", referenceNumber)

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end("""{"success":true,"referenceNumber":"$referenceNumber","message":"Claim submitted successfully."}""")

            } catch (e: Exception) {
                logger.error("Unexpected error processing claim", e)
                respond500(ctx)
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun respond422(ctx: RoutingContext, errors: List<FieldError>) {
        val errorsJson = errors.joinToString(",") { e ->
            """{"field":"${esc(e.field)}","code":"${esc(e.code)}","message":"${esc(e.message)}"}"""
        }
        ctx.response()
            .setStatusCode(422)
            .putHeader("Content-Type", "application/json")
            .end("""{"success":false,"message":"Validation failed. Please review your submission.","errors":[$errorsJson]}""")
    }

    private fun respond500(ctx: RoutingContext) {
        ctx.response()
            .setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end("""{"success":false,"message":"An unexpected server error occurred. Please try again."}""")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
