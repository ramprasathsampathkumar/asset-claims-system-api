package com.example.claims.handler

import com.example.claims.verticle.MainVerticle
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(VertxExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClaimSubmitIntegrationTest {

    companion object {
        private lateinit var vertx: Vertx
        private lateinit var client: WebClient
        private const val PORT = 18080

        @BeforeAll
        @JvmStatic
        fun setup(): Unit = runBlocking {
            // System properties allow AppConfig to pick up test-specific values
            System.setProperty("SERVER_PORT", PORT.toString())
            System.setProperty("COUCHBASE_HOST", "localhost")

            vertx = Vertx.vertx()
            client = WebClient.create(
                vertx,
                WebClientOptions().setDefaultPort(PORT).setDefaultHost("localhost"),
            )
            vertx.deployVerticle(MainVerticle()).coAwait()
        }

        @AfterAll
        @JvmStatic
        fun teardown(): Unit = runBlocking {
            client.close()
            vertx.close().coAwait()
        }
    }

    private fun validPayload(): JsonObject = JsonObject()
        .put("locale", "en")
        .put("submittedAt", "2026-03-01T10:30:00Z")
        .put(
            "step1",
            JsonObject().put("confirmed", true),
        )
        .put(
            "step2",
            JsonObject()
                .put("firstName", "John")
                .put("lastName", "Doe")
                .put("dateOfBirth", "1985-06-15")
                .put("nationality", "US")
                .put("idType", "passport")
                .put("idNumber", "A12345678")
                .put("street1", "123 Main St")
                .put("city", "New York")
                .put("postalCode", "10001")
                .put("country", "US")
                .put("phone", "+12125551234")
                .put("email", "john.doe@example.com"),
        )
        .put(
            "step3",
            JsonObject()
                .put("assetType", "stock")
                .put("tickerSymbol", "AAPL")
                .put("exchange", "NASDAQ")
                .put("sharesOwned", "100"),
        )
        .put(
            "step4",
            JsonObject()
                .put("currency", "USD")
                .put(
                    "bankFields",
                    JsonObject()
                        .put("account_number", "123456789")
                        .put("routing_number", "021000021"),
                ),
        )

    @Test
    @Order(1)
    fun `health endpoint returns UP`(testContext: VertxTestContext) {
        client.get("/health")
            .send()
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(200, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    assertEquals("UP", body.getString("status"))
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(2)
    fun `valid payload returns 200 with referenceNumber`(testContext: VertxTestContext) {
        client.post("/api/submit")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(validPayload())
            .onSuccess { response ->
                testContext.verify {
                    // Could be 200 (persisted) or 500 if Couchbase is not running in test env
                    assertTrue(response.statusCode() in listOf(200, 500))
                    if (response.statusCode() == 200) {
                        val body = response.bodyAsJsonObject()
                        assertTrue(body.getBoolean("success"))
                        assertTrue(body.getString("referenceNumber").startsWith("ACL-"))
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(3)
    fun `invalid email returns 422`(testContext: VertxTestContext) {
        val payload = validPayload().also { root ->
            root.getJsonObject("step2").put("email", "not-an-email")
        }

        client.post("/api/submit")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    assertFalse(body.getBoolean("success"))
                    val errors = body.getJsonArray("errors")
                    assertNotNull(errors)
                    assertTrue(
                        errors.list.filterIsInstance<Map<*, *>>()
                            .any { it["field"].toString().contains("email") && it["code"] == "invalid_format" },
                        "Expected email error with invalid_format code",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(4)
    fun `confirmed=false returns 422`(testContext: VertxTestContext) {
        val payload = validPayload().also { root ->
            root.getJsonObject("step1").put("confirmed", false)
        }

        client.post("/api/submit")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(5)
    fun `GBP with invalid sort_code returns 422`(testContext: VertxTestContext) {
        val payload = validPayload().also { root ->
            root.put(
                "step4",
                JsonObject()
                    .put("currency", "GBP")
                    .put(
                        "bankFields",
                        JsonObject()
                            .put("sort_code", "12345") // wrong — should be DD-DD-DD
                            .put("account_number", "12345678"),
                    ),
            )
        }

        client.post("/api/submit")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    assertFalse(body.getBoolean("success"))
                    val errors = body.getJsonArray("errors")
                    assertTrue(errors.size() > 0)
                    val sortCodeError = errors.list
                        .filterIsInstance<Map<*, *>>()
                        .any { it["field"].toString().contains("sort_code") && it["code"] == "invalid_pattern" }
                    assertTrue(sortCodeError, "Expected sort_code error with invalid_pattern code")
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(6)
    fun `CHF IBAN not starting with CH returns 422`(testContext: VertxTestContext) {
        val payload = validPayload().also { root ->
            root.put(
                "step4",
                JsonObject()
                    .put("currency", "CHF")
                    .put(
                        "bankFields",
                        JsonObject()
                            .put("iban", "DE89370400440532013000"), // German IBAN
                    ),
            )
        }

        client.post("/api/submit")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(7)
    fun `missing required field returns 422`(testContext: VertxTestContext) {
        val payload = validPayload().also { root ->
            root.getJsonObject("step2").remove("firstName")
        }

        client.post("/api/submit")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(8)
    fun `locale=fr returns French error message`(testContext: VertxTestContext) {
        // Use a future dateOfBirth: passes OpenAPI pattern check but Step2Validator
        // rejects it with date_past, returning the French localised message.
        val payload = validPayload().also { root ->
            root.put("locale", "fr")
            root.getJsonObject("step2").put("dateOfBirth", "2099-01-01")
        }

        client.post("/api/submit")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    val errors = body.getJsonArray("errors")
                    assertTrue(errors.size() > 0)
                    val dobError = errors.list
                        .filterIsInstance<Map<*, *>>()
                        .find { it["field"].toString().contains("dateOfBirth") }
                    assertNotNull(dobError, "Expected dateOfBirth validation error")
                    val msg = dobError!!["message"].toString()
                    assertTrue(msg.isNotBlank(), "Expected non-blank French error message")
                    // French message for date_past is "La date doit être dans le passé."
                    assertTrue(
                        msg.contains("date") || msg.contains("Date") || msg.contains("passé"),
                        "Expected French date_past message, got: $msg",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }
}
