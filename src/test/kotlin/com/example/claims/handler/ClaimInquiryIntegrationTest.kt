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
class ClaimInquiryIntegrationTest {

    companion object {
        private lateinit var vertx: Vertx
        private lateinit var client: WebClient
        private const val PORT = 18081

        @BeforeAll
        @JvmStatic
        fun setup(): Unit = runBlocking {
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

    private fun validInquiry(): JsonObject = JsonObject()
        .put("referenceNumber", "ACL-M5X2K1-AB3C")
        .put("lastName", "Doe")
        .put("locale", "en")

    // ── Validation tests (no DB needed) ──────────────────────────────────────

    @Test
    @Order(1)
    fun `missing referenceNumber returns 422`(testContext: VertxTestContext) {
        val payload = JsonObject()
            .put("lastName", "Doe")
            .put("locale", "en")

        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    assertFalse(body.getBoolean("success"))
                    val errors = body.getJsonArray("errors")
                    assertTrue(
                        errors.list.filterIsInstance<Map<*, *>>()
                            .any { it["field"] == "referenceNumber" && it["code"] == "required" },
                        "Expected referenceNumber required error",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(2)
    fun `missing lastName returns 422`(testContext: VertxTestContext) {
        val payload = JsonObject()
            .put("referenceNumber", "ACL-M5X2K1-AB3C")
            .put("locale", "en")

        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    val errors = body.getJsonArray("errors")
                    assertTrue(
                        errors.list.filterIsInstance<Map<*, *>>()
                            .any { it["field"] == "lastName" && it["code"] == "required" },
                        "Expected lastName required error",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(3)
    fun `invalid referenceNumber format returns 422`(testContext: VertxTestContext) {
        val payload = JsonObject()
            .put("referenceNumber", "INVALID-FORMAT")
            .put("lastName", "Doe")
            .put("locale", "en")

        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    val errors = body.getJsonArray("errors")
                    assertTrue(
                        errors.list.filterIsInstance<Map<*, *>>()
                            .any { it["field"] == "referenceNumber" && it["code"] == "invalid_pattern" },
                        "Expected referenceNumber invalid_pattern error",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(4)
    fun `invalid lastName with digits returns 422`(testContext: VertxTestContext) {
        val payload = JsonObject()
            .put("referenceNumber", "ACL-M5X2K1-AB3C")
            .put("lastName", "Doe123")
            .put("locale", "en")

        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    val errors = body.getJsonArray("errors")
                    assertTrue(
                        errors.list.filterIsInstance<Map<*, *>>()
                            .any { it["field"] == "lastName" && it["code"] == "invalid_pattern" },
                        "Expected lastName invalid_pattern error",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(5)
    fun `invalid dateOfBirth format returns 422`(testContext: VertxTestContext) {
        val payload = JsonObject()
            .put("referenceNumber", "ACL-M5X2K1-AB3C")
            .put("lastName", "Doe")
            .put("dateOfBirth", "15-06-1985") // wrong format
            .put("locale", "en")

        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    val errors = body.getJsonArray("errors")
                    assertTrue(
                        errors.list.filterIsInstance<Map<*, *>>()
                            .any { it["field"] == "dateOfBirth" && it["code"] == "invalid_format" },
                        "Expected dateOfBirth invalid_format error",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(6)
    fun `future dateOfBirth returns 422`(testContext: VertxTestContext) {
        val payload = JsonObject()
            .put("referenceNumber", "ACL-M5X2K1-AB3C")
            .put("lastName", "Doe")
            .put("dateOfBirth", "2099-01-01")
            .put("locale", "en")

        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    val errors = body.getJsonArray("errors")
                    assertTrue(
                        errors.list.filterIsInstance<Map<*, *>>()
                            .any { it["field"] == "dateOfBirth" && it["code"] == "date_past" },
                        "Expected dateOfBirth date_past error",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(7)
    fun `valid format but non-existent claim returns 404 or 500`(testContext: VertxTestContext) {
        // Without a live DB this will 500; with DB it returns 404 for a non-existent ref
        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(validInquiry())
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(404, 500),
                        "Expected 404 (claim not found) or 500 (no DB), got ${response.statusCode()}",
                    )
                    if (response.statusCode() == 404) {
                        val body = response.bodyAsJsonObject()
                        assertFalse(body.getBoolean("success"))
                        assertTrue(body.getString("message").isNotBlank())
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(8)
    fun `locale=fr returns French error for missing lastName`(testContext: VertxTestContext) {
        val payload = JsonObject()
            .put("referenceNumber", "ACL-M5X2K1-AB3C")
            .put("locale", "fr")
        // lastName deliberately omitted

        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(422, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    val errors = body.getJsonArray("errors")
                    val lastNameError = errors.list
                        .filterIsInstance<Map<*, *>>()
                        .find { it["field"] == "lastName" }
                    assertNotNull(lastNameError, "Expected lastName error")
                    // French 'required' message contains "obligatoire"
                    assertTrue(
                        lastNameError!!["message"].toString().contains("obligatoire"),
                        "Expected French required message, got: ${lastNameError["message"]}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(9)
    fun `Unicode lastName is accepted`(testContext: VertxTestContext) {
        val payload = JsonObject()
            .put("referenceNumber", "ACL-M5X2K1-AB3C")
            .put("lastName", "García-López")
            .put("locale", "es")

        client.post("/api/inquiry")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(payload)
            .onSuccess { response ->
                testContext.verify {
                    // Passes validation — 404 or 500 depending on DB availability
                    assertTrue(
                        response.statusCode() in listOf(404, 500),
                        "Unicode lastName should pass validation, got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }
}
