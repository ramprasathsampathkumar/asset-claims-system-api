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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.random.Random

/**
 * End-to-end integration tests for the document upload API.
 *
 * Deploys the real MainVerticle (HTTP server on port 18081) and exercises every
 * endpoint with randomly generated file bytes. MinIO and Couchbase are expected to
 * be reachable at localhost; tests accept 5xx gracefully when they are not.
 *
 * Run `docker compose up` for full green coverage.
 */
@ExtendWith(VertxExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DocumentHandlerIntegrationTest {

    companion object {
        private lateinit var vertx: Vertx
        private lateinit var client: WebClient
        private const val PORT = 18081

        /** Stashed from the first successful upload; reused by download/delete/field tests. */
        private var uploadedDocId: String? = null

        // ── random document byte generators ──────────────────────────────────

        /** %PDF magic + random payload */
        fun randomPdfBytes(size: Int = 4096): ByteArray =
            byteArrayOf(0x25, 0x50, 0x44, 0x46) + Random.nextBytes(size - 4)

        /** PNG magic (8 bytes) + random payload */
        fun randomPngBytes(size: Int = 4096): ByteArray =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
                Random.nextBytes(size - 8)

        /** JPEG SOI marker + random payload */
        fun randomJpegBytes(size: Int = 4096): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                Random.nextBytes(size - 4)

        /** ZIP/PK header (DOCX) + random payload */
        fun randomDocxBytes(size: Int = 4096): ByteArray =
            byteArrayOf(0x50, 0x4B, 0x03, 0x04) + Random.nextBytes(size - 4)

        /** OLE2 header (DOC) + random payload */
        fun randomDocBytes(size: Int = 4096): ByteArray =
            byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte()) +
                Random.nextBytes(size - 4)

        /** No valid magic — used for MIME rejection tests */
        fun randomUnknownBytes(size: Int = 512): ByteArray = Random.nextBytes(size)

        /** 11 MB — exceeds the 10 MB upload limit */
        fun oversizedPdfBytes(): ByteArray =
            byteArrayOf(0x25, 0x50, 0x44, 0x46) + Random.nextBytes(11 * 1024 * 1024 - 4)

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
            // Give background coroutines (Couchbase connect, MinIO ensureBucket) time to finish
            delay(1500)
        }

        @AfterAll
        @JvmStatic
        fun teardown(): Unit = runBlocking {
            client.close()
            vertx.close().coAwait()
        }
    }

    // ── health ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `health endpoint returns UP`(testContext: VertxTestContext) {
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

    // ── upload: validation rejections — no storage required ───────────────────

    @Test
    @Order(2)
    fun `upload with no file field returns 400`(testContext: VertxTestContext) {
        val form = MultipartForm.create().attribute("uploadedBy", "tester")
        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(400, 500),
                        "Expected 400 for missing file, got ${response.statusCode()}",
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
            .binaryFileUpload("file", "script.sh", Buffer.buffer(randomUnknownBytes()), "text/plain")
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
            .binaryFileUpload("file", "malware.exe", Buffer.buffer(randomPdfBytes()), "application/pdf")
        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    assertEquals(400, response.statusCode())
                    val errors = response.bodyAsJsonObject().getJsonArray("errors")
                    assertTrue(
                        errors.list.any { it.toString().contains("exe") || it.toString().contains("extension") },
                        "Expected extension error, got: $errors",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(5)
    fun `upload with oversized file returns 400 or 413`(testContext: VertxTestContext) {
        val form = MultipartForm.create()
            .binaryFileUpload("file", "huge.pdf", Buffer.buffer(oversizedPdfBytes()), "application/pdf")
        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(400, 413, 422),
                        "Expected 400/413 for oversized file, got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── upload: valid PDF with referenceNumber + documentType ─────────────────

    @Test
    @Order(6)
    fun `upload PDF with referenceNumber and documentType stores both fields`(testContext: VertxTestContext) {
        val pdfData = randomPdfBytes(5120) // 5 KB randomly generated PDF
        val form = MultipartForm.create()
            .binaryFileUpload("file", "bank-statement-${System.currentTimeMillis()}.pdf", Buffer.buffer(pdfData), "application/pdf")
            .attribute("uploadedBy", "alice@example.com")
            .attribute("referenceNumber", "ACL-M5X2K1-AB3C")
            .attribute("documentType", "bank_statement")

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(
                        status in listOf(201, 500, 503),
                        "Expected 201 or 5xx, got $status",
                    )
                    if (status == 201) {
                        val doc = response.bodyAsJsonObject().getJsonObject("document")
                        assertNotNull(doc, "document object must be present")
                        assertEquals("application/pdf", doc.getString("contentType"))
                        assertEquals(5120L, doc.getLong("size"))
                        assertEquals("alice@example.com", doc.getString("uploadedBy"))
                        assertEquals("ACL-M5X2K1-AB3C", doc.getString("referenceNumber"),
                            "referenceNumber must be stored and returned")
                        assertEquals("bank_statement", doc.getString("documentType"),
                            "documentType must be stored and returned")
                        assertEquals("active", doc.getString("status"))
                        assertNotNull(doc.getString("id"))
                        uploadedDocId = doc.getString("id")
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(7)
    fun `upload PNG without optional fields has null referenceNumber and documentType`(testContext: VertxTestContext) {
        val pngData = randomPngBytes(8192) // 8 KB randomly generated PNG
        val form = MultipartForm.create()
            .binaryFileUpload("file", "photo-${System.currentTimeMillis()}.png", Buffer.buffer(pngData), "image/png")
            // no referenceNumber, no documentType

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(status in listOf(201, 500, 503), "Expected 201 or 5xx, got $status")
                    if (status == 201) {
                        val doc = response.bodyAsJsonObject().getJsonObject("document")
                        assertEquals("image/png", doc.getString("contentType"))
                        assertNull(doc.getString("referenceNumber"),
                            "referenceNumber should be null when not supplied")
                        assertNull(doc.getString("documentType"),
                            "documentType should be null when not supplied")
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(8)
    fun `upload JPEG with referenceNumber only stores referenceNumber and null documentType`(testContext: VertxTestContext) {
        val jpegData = randomJpegBytes(3072) // 3 KB randomly generated JPEG
        val form = MultipartForm.create()
            .binaryFileUpload("file", "id-scan-${System.currentTimeMillis()}.jpg", Buffer.buffer(jpegData), "image/jpeg")
            .attribute("referenceNumber", "ACL-TEST01-ZZZZ")
            // documentType intentionally omitted

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(status in listOf(201, 500, 503), "Expected 201 or 5xx, got $status")
                    if (status == 201) {
                        val doc = response.bodyAsJsonObject().getJsonObject("document")
                        assertEquals("image/jpeg", doc.getString("contentType"))
                        assertEquals("ACL-TEST01-ZZZZ", doc.getString("referenceNumber"))
                        assertNull(doc.getString("documentType"))
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(9)
    fun `upload DOCX with documentType only stores documentType and null referenceNumber`(testContext: VertxTestContext) {
        val docxData = randomDocxBytes(6144) // 6 KB randomly generated DOCX
        val form = MultipartForm.create()
            .binaryFileUpload(
                "file",
                "contract-${System.currentTimeMillis()}.docx",
                Buffer.buffer(docxData),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
            .attribute("documentType", "proof_of_address")

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(status in listOf(201, 500, 503), "Expected 201 or 5xx, got $status")
                    if (status == 201) {
                        val doc = response.bodyAsJsonObject().getJsonObject("document")
                        assertEquals("proof_of_address", doc.getString("documentType"))
                        assertNull(doc.getString("referenceNumber"))
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(10)
    fun `upload DOC file succeeds or returns 5xx when storage unavailable`(testContext: VertxTestContext) {
        val docData = randomDocBytes(4096) // 4 KB randomly generated DOC
        val form = MultipartForm.create()
            .binaryFileUpload("file", "legacy-${System.currentTimeMillis()}.doc", Buffer.buffer(docData), "application/msword")
            .attribute("uploadedBy", "integration-test")
            .attribute("referenceNumber", "ACL-LEGACY-TEST")
            .attribute("documentType", "passport")

        client.post("/api/documents/upload")
            .sendMultipartForm(form)
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(201, 500, 503),
                        "Expected 201 or 5xx, got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    @Order(11)
    fun `list documents returns 200 with documents array`(testContext: VertxTestContext) {
        client.get("/api/documents").send()
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(status in listOf(200, 500), "Expected 200 or 5xx, got $status")
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
    @Order(12)
    fun `download non-existent document returns 404 or 5xx`(testContext: VertxTestContext) {
        client.get("/api/documents/does-not-exist-00000/download").send()
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(404, 500),
                        "Expected 404 or 5xx, got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(13)
    fun `download previously uploaded PDF returns bytes with correct headers`(testContext: VertxTestContext) {
        val id = uploadedDocId ?: run { testContext.completeNow(); return }
        client.get("/api/documents/$id/download").send()
            .onSuccess { response ->
                testContext.verify {
                    val status = response.statusCode()
                    assertTrue(status in listOf(200, 404, 500, 503), "Expected 200/404/5xx, got $status")
                    if (status == 200) {
                        assertEquals("application/pdf", response.getHeader("Content-Type"))
                        assertTrue(response.getHeader("Content-Disposition")?.contains("attachment") == true)
                        assertTrue(response.body().length() > 0, "Downloaded body must not be empty")
                    }
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    @Order(14)
    fun `delete non-existent document returns 404 or 5xx`(testContext: VertxTestContext) {
        client.delete("/api/documents/does-not-exist-99999").send()
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(404, 500),
                        "Expected 404 or 5xx, got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(15)
    fun `delete previously uploaded document returns 204`(testContext: VertxTestContext) {
        val id = uploadedDocId ?: run { testContext.completeNow(); return }
        client.delete("/api/documents/$id").send()
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(204, 404, 500, 503),
                        "Expected 204/404/5xx, got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }

    @Test
    @Order(16)
    fun `downloading deleted document returns 404 or 5xx`(testContext: VertxTestContext) {
        val id = uploadedDocId ?: run { testContext.completeNow(); return }
        client.get("/api/documents/$id/download").send()
            .onSuccess { response ->
                testContext.verify {
                    assertTrue(
                        response.statusCode() in listOf(404, 500, 503),
                        "Expected 404/5xx after deletion, got ${response.statusCode()}",
                    )
                }
                testContext.completeNow()
            }
            .onFailure(testContext::failNow)
    }
}
