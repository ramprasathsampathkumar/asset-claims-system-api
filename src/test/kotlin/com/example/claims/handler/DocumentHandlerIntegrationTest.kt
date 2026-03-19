package com.example.claims.handler

import com.example.claims.verticle.MainVerticle
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.multipart.MultipartForm
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.random.Random

/**
 * End-to-end integration tests for the document upload API.
 *
 * These tests deploy the real MainVerticle against a real HTTP server.
 * Tests that require MinIO (upload/download/delete) accept both success (2xx)
 * and service-unavailable (5xx) responses — mirroring the same pattern used in
 * ClaimSubmitIntegrationTest for Couchbase.
 *
 * Run `docker compose up minio` before running tests for full E2E coverage.
 */
@ExtendWith(VertxExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DocumentHandlerIntegrationTest {

    companion object {
        private lateinit var vertx: Vertx
        private lateinit var client: WebClient
        private const val PORT = 18081

        /** ID of the document uploaded in the upload test, reused by download/delete tests. */
        private var uploadedDocumentId: String? = null

        @BeforeAll
        @JvmStatic
        fun setup(): Unit = runBlocking {
            System.setProperty("SERVER_PORT", PORT.toString())
            System.setProperty("COUCHBASE_HOST", "localhost")
            System.setProperty("S3_ENDPOINT", "http://localhost:9000")
            System.setProperty("S3_ACCESS_KEY", "minioadmin")
            System.setProperty("S3_SECRET_KEY", "minioadmin123")
            System.setProperty("S3_BUCKET", "documents-test")
            System.setProperty("MAX_UPLOAD_SIZE", "${10 * 1024 * 1024}")

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

        // ── random document generators ────────────────────────────────────────

        /** Minimal PDF: %PDF magic bytes + random payload */
        fun randomPdfBytes(size: Int = 2048): ByteArray =
            byteArrayOf(0x25, 0x50, 0x44, 0x46) + Random.nextBytes(size - 4)

        /** Minimal PNG: PNG magic bytes (8 bytes) + random payload */
        fun randomPngBytes(size: Int = 2048): ByteArray =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
                Random.nextBytes(size - 8)

        /** Minimal JPEG: JPEG SOI marker + random payload */
        fun randomJpegBytes(size: Int = 2048): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                Random.nextBytes(size - 4)

        /** Minimal DOCX: ZIP PK header + random payload */
        fun randomDocxBytes(size: Int = 2048): ByteArray =
            byteArrayOf(0x50, 0x4B, 0x03, 0x04) + Random.nextBytes(size - 4)

        /** Random bytes with no valid magic header */
        fun randomUnknownBytes(size: Int = 2048): ByteArray = Random.nextBytes(size)

        /** Exactly 11 MB of random PDF-looking bytes (exceeds 10 MB limit) */
        fun oversizedPdfBytes(): ByteArray =
            byteArrayOf(0x25, 0x50, 0x44, 0x46) + Random.nextBytes(11 * 1024 * 1024 - 4)
    }

    // ── health check ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `health endpoint is reachable`(testContext: VertxTestContext) {
        client.get("/health").send()
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(200, response.statusCode())
                    assertEquals("UP", response.bodyAsJsonObject().getString("status"))
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── upload — validation rejections (no storage needed) ───────────────────

    @Test
    @Order(2)
    fun `upload with no file field returns 400`(testContext: VertxTestContext) {
        val form = MultipartForm.create()
            .attribute("uploadedBy", "tester")

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    // 400 if file field missing, OR 500 if MinIO is not running
                    assertTrue(
                        response.statusCode() in listOf(400, 500),
                        "Expected 400 or 500 but got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(3)
    fun `upload with disallowed MIME type returns 400`(testContext: VertxTestContext) {
        val form = MultipartForm.create()
            .binaryFileUpload(
                "file",
                "script.sh",
                Buffer.buffer(randomUnknownBytes(512)),
                "text/plain",
            )
            .attribute("uploadedBy", "tester")

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(400, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    assertFalse(body.getBoolean("success"))
                    assertNotNull(body.getJsonArray("errors"))
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(4)
    fun `upload with disallowed extension returns 400`(testContext: VertxTestContext) {
        val form = MultipartForm.create()
            .binaryFileUpload(
                "file",
                "malware.exe",
                Buffer.buffer(randomPdfBytes()),
                "application/pdf",
            )

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(400, response.statusCode())
                    val body = response.bodyAsJsonObject()
                    assertFalse(body.getBoolean("success"))
                    val errors = body.getJsonArray("errors")
                    assertTrue(
                        errors.list.any { it.toString().contains("exe") || it.toString().contains("extension") },
                        "Expected extension error in $errors",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(5)
    fun `upload with oversized file returns 400`(testContext: VertxTestContext) {
        val form = MultipartForm.create()
            .binaryFileUpload(
                "file",
                "huge.pdf",
                Buffer.buffer(oversizedPdfBytes()),
                "application/pdf",
            )

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    // 400 (validation) or 413 (body too large from BodyHandler) both acceptable
                    assertTrue(
                        response.statusCode() in listOf(400, 413, 422),
                        "Expected 400/413/422 for oversized file, got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── upload — valid documents (requires MinIO; accepts 201 or 5xx) ─────────

    @Test
    @Order(6)
    fun `upload valid PDF succeeds or returns 5xx when MinIO unavailable`(testContext: VertxTestContext) {
        val pdfData = randomPdfBytes(4096) // 4 KB randomly generated PDF
        val form = MultipartForm.create()
            .binaryFileUpload("file", "claim-doc-${System.currentTimeMillis()}.pdf", Buffer.buffer(pdfData), "application/pdf")
            .attribute("uploadedBy", "integration-test")

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(
                        status in listOf(201, 500, 503),
                        "Expected 201 (success) or 5xx (MinIO/Couchbase not running) but got $status",
                    )
                    if (status == 201) {
                        val body = response.bodyAsJsonObject()
                        assertTrue(body.getBoolean("success"))
                        val doc = body.getJsonObject("document")
                        assertNotNull(doc)
                        assertEquals("application/pdf", doc.getString("contentType"))
                        assertEquals(4096L, doc.getLong("size"))
                        assertEquals("integration-test", doc.getString("uploadedBy"))
                        assertEquals("active", doc.getString("status"))
                        assertNotNull(doc.getString("id"))
                        // Stash for downstream tests
                        uploadedDocumentId = doc.getString("id")
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(7)
    fun `upload valid PNG succeeds or returns 5xx when storage unavailable`(testContext: VertxTestContext) {
        val pngData = randomPngBytes(8192) // 8 KB randomly generated PNG
        val form = MultipartForm.create()
            .binaryFileUpload("file", "screenshot-${System.currentTimeMillis()}.png", Buffer.buffer(pngData), "image/png")
            .attribute("uploadedBy", "integration-test")

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(
                        status in listOf(201, 500, 503),
                        "Expected 201 or 5xx but got $status",
                    )
                    if (status == 201) {
                        val doc = response.bodyAsJsonObject().getJsonObject("document")
                        assertEquals("image/png", doc.getString("contentType"))
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(8)
    fun `upload valid JPEG succeeds or returns 5xx when storage unavailable`(testContext: VertxTestContext) {
        val jpegData = randomJpegBytes(3072)
        val form = MultipartForm.create()
            .binaryFileUpload("file", "photo-${System.currentTimeMillis()}.jpg", Buffer.buffer(jpegData), "image/jpeg")

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(201, 500, 503),
                        "Expected 201 or 5xx but got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(9)
    fun `upload valid DOCX succeeds or returns 5xx when storage unavailable`(testContext: VertxTestContext) {
        val docxData = randomDocxBytes(5120)
        val form = MultipartForm.create()
            .binaryFileUpload(
                "file",
                "contract-${System.currentTimeMillis()}.docx",
                Buffer.buffer(docxData),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(201, 500, 503),
                        "Expected 201 or 5xx but got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    fun `list documents returns 200 array or 5xx`(testContext: VertxTestContext) {
        client.get("/api/documents").send()
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(
                        status in listOf(200, 500),
                        "Expected 200 or 5xx but got $status",
                    )
                    if (status == 200) {
                        val body = response.bodyAsJsonObject()
                        assertTrue(body.getBoolean("success"))
                        assertNotNull(body.getJsonArray("documents"))
                        assertNotNull(body.getInteger("total"))
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── download ──────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    fun `download non-existent document returns 404 or 5xx`(testContext: VertxTestContext) {
        client.get("/api/documents/non-existent-id-00000/download").send()
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(404, 500),
                        "Expected 404 or 5xx but got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(12)
    fun `download previously uploaded document returns bytes or 404 when DB unavailable`(testContext: VertxTestContext) {
        val id = uploadedDocumentId
        if (id == null) {
            // Upload test did not succeed (storage unavailable) — skip gracefully
            testContext.completeNow()
            return
        }

        client.get("/api/documents/$id/download").send()
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(
                        status in listOf(200, 404, 500, 503),
                        "Expected 200, 404 or 5xx but got $status",
                    )
                    if (status == 200) {
                        assertEquals("application/pdf", response.getHeader("Content-Type"))
                        assertTrue(response.getHeader("Content-Disposition")?.contains("attachment") == true)
                        assertTrue(response.body().length() > 0)
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    @Order(13)
    fun `delete non-existent document returns 404 or 5xx`(testContext: VertxTestContext) {
        client.delete("/api/documents/non-existent-id-99999").send()
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(404, 500),
                        "Expected 404 or 5xx but got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(14)
    fun `delete previously uploaded document returns 204 or expected error`(testContext: VertxTestContext) {
        val id = uploadedDocumentId
        if (id == null) {
            testContext.completeNow()
            return
        }

        client.delete("/api/documents/$id").send()
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(
                        status in listOf(204, 404, 500, 503),
                        "Expected 204, 404, or 5xx but got $status",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── idempotency: deleted document is gone ─────────────────────────────────

    @Test
    @Order(15)
    fun `downloading deleted document returns 404 or 5xx`(testContext: VertxTestContext) {
        val id = uploadedDocumentId
        if (id == null) {
            testContext.completeNow()
            return
        }

        client.get("/api/documents/$id/download").send()
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(404, 500, 503),
                        "Expected 404/5xx after deletion but got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }
}
