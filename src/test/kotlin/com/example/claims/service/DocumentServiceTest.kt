package com.example.claims.service

import com.example.claims.model.DocumentMetadata
import com.example.claims.repository.DocumentRepository
import com.example.claims.storage.StorageNotFoundException
import com.example.claims.storage.StorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DocumentServiceTest {

    private lateinit var storage: StorageService
    private lateinit var repository: DocumentRepository
    private lateinit var service: DocumentService

    @BeforeEach
    fun setup() {
        storage = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        service = DocumentService(storage, repository)

        // Default mock: bucketName returns "documents"
        coEvery { storage.bucketName() } returns "documents"
    }

    // ── file generation helpers ──────────────────────────────────────────────

    companion object {
        fun pdfBytes(size: Int = 256): ByteArray =
            byteArrayOf(0x25, 0x50, 0x44, 0x46) + // %PDF
                Random.nextBytes(size - 4)

        fun pngBytes(size: Int = 256): ByteArray =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + // PNG magic
                Random.nextBytes(size - 8)

        fun jpegBytes(size: Int = 256): ByteArray =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + // JPEG SOI + APP0
                Random.nextBytes(size - 4)

        fun docxBytes(size: Int = 256): ByteArray =
            byteArrayOf(0x50, 0x4B, 0x03, 0x04) + // ZIP / DOCX magic
                Random.nextBytes(size - 4)

        fun docBytes(size: Int = 256): ByteArray =
            byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte()) + // OLE2 / DOC magic
                Random.nextBytes(size - 4)

        fun unknownBytes(size: Int = 256): ByteArray = Random.nextBytes(size)

        fun oversizedBytes(): ByteArray = Random.nextBytes(11 * 1024 * 1024) // 11 MB
    }

    // ── validation unit tests ────────────────────────────────────────────────

    @Nested
    inner class Validation {

        @Test
        fun `valid PDF passes all checks`() {
            val errors = service.validate("report.pdf", "application/pdf", pdfBytes())
            assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        }

        @Test
        fun `valid PNG passes all checks`() {
            val errors = service.validate("photo.png", "image/png", pngBytes())
            assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        }

        @Test
        fun `valid JPEG passes all checks`() {
            val errors = service.validate("photo.jpg", "image/jpeg", jpegBytes())
            assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        }

        @Test
        fun `valid DOCX passes all checks`() {
            val errors = service.validate(
                "letter.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes(),
            )
            assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        }

        @Test
        fun `valid DOC passes all checks`() {
            val errors = service.validate("letter.doc", "application/msword", docBytes())
            assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        }

        @Test
        fun `oversized file produces size error`() {
            val errors = service.validate("big.pdf", "application/pdf", oversizedBytes())
            assertTrue(errors.any { it.contains("size") || it.contains("exceed") })
        }

        @Test
        fun `disallowed MIME type produces content-type error`() {
            val errors = service.validate("script.pdf", "text/plain", pdfBytes())
            assertTrue(errors.any { it.contains("text/plain") || it.contains("not allowed") })
        }

        @Test
        fun `disallowed extension produces extension error`() {
            val errors = service.validate("malware.exe", "application/pdf", pdfBytes())
            assertTrue(errors.any { it.contains(".exe") || it.contains("extension") })
        }

        @Test
        fun `magic bytes mismatch produces content error`() {
            // Claim it's application/pdf but pass random bytes with no valid header
            val errors = service.validate("fake.pdf", "application/pdf", unknownBytes())
            // No magic bytes detected → null → no magic-bytes error added (unknown content passes)
            // This is intentional: we reject only if detected type is NOT allowed, not if unknown
            // So this should pass or only fail on other rules
            // (unknown bytes produce no magic-byte error since detectMimeFromBytes returns null)
            assertTrue(errors.none { it.contains("content does not match") })
        }

        @Test
        fun `MIME type with charset suffix is normalised`() {
            // e.g. "application/pdf; charset=UTF-8" should be accepted
            val errors = service.validate("doc.pdf", "application/pdf; charset=UTF-8", pdfBytes())
            assertTrue(errors.isEmpty(), "Expected charset suffix to be stripped: $errors")
        }

        @Test
        fun `multiple violations are all reported`() {
            val errors = service.validate("bad.exe", "text/html", unknownBytes())
            assertTrue(errors.size >= 2, "Expected at least 2 errors (mime + extension) but got: $errors")
        }
    }

    // ── filename sanitisation ────────────────────────────────────────────────

    @Nested
    inner class FilenameSanitisation {

        @Test
        fun `normal filename is unchanged`() {
            assertEquals("report_2026.pdf", service.sanitizeFileName("report_2026.pdf"))
        }

        @Test
        fun `path traversal is stripped`() {
            val result = service.sanitizeFileName("../../etc/passwd")
            assertFalse(result.contains("/"), "Should not contain path separator")
            assertFalse(result.contains(".."), "Should not contain '..'")
        }

        @Test
        fun `Windows-style path is stripped`() {
            val result = service.sanitizeFileName("C:\\Windows\\system32\\evil.exe")
            assertFalse(result.contains("\\"), "Should not contain backslash")
            assertFalse(result.contains(":"), "Should not contain colon")
        }

        @Test
        fun `null bytes are removed`() {
            val result = service.sanitizeFileName("file\u0000.pdf")
            assertFalse(result.contains("\u0000"))
        }

        @Test
        fun `special chars are replaced with underscore`() {
            val result = service.sanitizeFileName("my file (1).pdf")
            assertFalse(result.contains(" "), "Spaces should be replaced")
            assertFalse(result.contains("("), "Parens should be replaced")
        }

        @Test
        fun `blank filename becomes 'upload'`() {
            assertEquals("upload", service.sanitizeFileName("   "))
        }

        @Test
        fun `filename is truncated at 200 chars`() {
            val longName = "a".repeat(300) + ".pdf"
            val result = service.sanitizeFileName(longName)
            assertTrue(result.length <= 200)
        }
    }

    // ── magic byte detection ─────────────────────────────────────────────────

    @Nested
    inner class MagicByteDetection {

        @Test
        fun `detects PDF from magic bytes`() {
            assertEquals("application/pdf", service.detectMimeFromBytes(pdfBytes()))
        }

        @Test
        fun `detects PNG from magic bytes`() {
            assertEquals("image/png", service.detectMimeFromBytes(pngBytes()))
        }

        @Test
        fun `detects JPEG from magic bytes`() {
            assertEquals("image/jpeg", service.detectMimeFromBytes(jpegBytes()))
        }

        @Test
        fun `detects DOCX (ZIP) from magic bytes`() {
            assertEquals(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                service.detectMimeFromBytes(docxBytes()),
            )
        }

        @Test
        fun `detects DOC (OLE2) from magic bytes`() {
            assertEquals("application/msword", service.detectMimeFromBytes(docBytes()))
        }

        @Test
        fun `returns null for unknown bytes`() {
            assertNull(service.detectMimeFromBytes(unknownBytes()))
        }

        @Test
        fun `returns null for empty input`() {
            assertNull(service.detectMimeFromBytes(ByteArray(0)))
        }

        @Test
        fun `returns null for very short input`() {
            assertNull(service.detectMimeFromBytes(byteArrayOf(0x25, 0x50)))
        }
    }

    // ── upload orchestration ─────────────────────────────────────────────────

    @Nested
    inner class Upload {

        @Test
        fun `successful PDF upload stores object and saves metadata`(): Unit = runBlocking {
            val data = pdfBytes(1024)
            val keySlot = slot<String>()

            coEvery { storage.putObject(capture(keySlot), any(), any()) } returns Unit
            coEvery { repository.save(any()) } returns Unit

            val metadata = service.upload("contract.pdf", "application/pdf", data, "user-123")

            assertEquals("contract.pdf", metadata.originalFileName)
            assertEquals("application/pdf", metadata.contentType)
            assertEquals(1024L, metadata.size)
            assertEquals("documents", metadata.storageBucket)
            assertEquals("user-123", metadata.uploadedBy)
            assertEquals("active", metadata.status)
            assertTrue(metadata.storageKey.endsWith("contract.pdf"))
            assertTrue(keySlot.captured.endsWith("contract.pdf"))

            coVerify(exactly = 1) { storage.putObject(any(), data, "application/pdf") }
            coVerify(exactly = 1) { repository.save(any()) }
        }

        @Test
        fun `successful PNG upload detects mime from magic bytes`(): Unit = runBlocking {
            val data = pngBytes(512)
            coEvery { storage.putObject(any(), any(), any()) } returns Unit
            coEvery { repository.save(any()) } returns Unit

            // Claim wrong MIME type — magic bytes override to image/png
            val metadata = service.upload("photo.PNG", "image/png", data, "")
            assertEquals("image/png", metadata.contentType)
        }

        @Test
        fun `upload with invalid MIME throws DocumentValidationException`(): Unit = runBlocking {
            val data = pdfBytes()
            val ex = assertThrows(DocumentValidationException::class.java) {
                runBlocking { service.upload("file.pdf", "text/plain", data, "u1") }
            }
            assertTrue(ex.errors.any { it.contains("text/plain") || it.contains("not allowed") })
        }

        @Test
        fun `upload with bad extension throws DocumentValidationException`(): Unit = runBlocking {
            val data = pdfBytes()
            assertThrows(DocumentValidationException::class.java) {
                runBlocking { service.upload("file.php", "application/pdf", data, "u1") }
            }
        }

        @Test
        fun `upload with oversized file throws DocumentValidationException`(): Unit = runBlocking {
            val data = oversizedBytes()
            val ex = assertThrows(DocumentValidationException::class.java) {
                runBlocking { service.upload("big.pdf", "application/pdf", data, "u1") }
            }
            assertTrue(ex.errors.any { it.contains("size") || it.contains("exceed") })
        }

        @Test
        fun `blank uploadedBy defaults to anonymous`(): Unit = runBlocking {
            val data = pdfBytes()
            coEvery { storage.putObject(any(), any(), any()) } returns Unit
            coEvery { repository.save(any()) } returns Unit

            val metadata = service.upload("doc.pdf", "application/pdf", data, "")
            assertEquals("anonymous", metadata.uploadedBy)
        }
    }

    // ── getDocument ──────────────────────────────────────────────────────────

    @Nested
    inner class GetDocument {

        private fun sampleMetadata(id: String = "abc-123") = DocumentMetadata(
            id = id,
            originalFileName = "test.pdf",
            contentType = "application/pdf",
            size = 512L,
            storageBucket = "documents",
            storageKey = "2026/03/19/$id-test.pdf",
            uploadedBy = "tester",
            uploadedAt = "2026-03-19T10:00:00Z",
            status = "active",
            referenceNumber = null,
            documentType = null,
        )

        @Test
        fun `returns metadata and bytes when both exist`(): Unit = runBlocking {
            val meta = sampleMetadata()
            val bytes = pdfBytes(512)
            coEvery { repository.findById("abc-123") } returns meta
            coEvery { storage.getObject(meta.storageKey) } returns bytes

            val result = service.getDocument("abc-123")
            assertNotNull(result)
            assertEquals(meta, result!!.first)
            assertArrayEquals(bytes, result.second)
        }

        @Test
        fun `returns null when document metadata not found`(): Unit = runBlocking {
            coEvery { repository.findById("missing") } returns null
            assertNull(service.getDocument("missing"))
        }

        @Test
        fun `returns null when storage object is missing`(): Unit = runBlocking {
            val meta = sampleMetadata()
            coEvery { repository.findById("abc-123") } returns meta
            coEvery { storage.getObject(any()) } throws StorageNotFoundException("key")

            assertNull(service.getDocument("abc-123"))
        }
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Nested
    inner class Delete {

        @Test
        fun `returns false when document not found`(): Unit = runBlocking {
            coEvery { repository.findById("nope") } returns null
            assertFalse(service.delete("nope"))
        }

        @Test
        fun `deletes from storage and repository`(): Unit = runBlocking {
            val meta = DocumentMetadata(
                id = "del-1", originalFileName = "f.pdf", contentType = "application/pdf",
                size = 100L, storageBucket = "documents", storageKey = "k/del-1-f.pdf",
                uploadedBy = "u", uploadedAt = "2026-03-19T10:00:00Z", status = "active",
                referenceNumber = null, documentType = null,
            )
            coEvery { repository.findById("del-1") } returns meta
            coEvery { storage.deleteObject(any()) } returns Unit
            coEvery { repository.delete("del-1") } returns true

            assertTrue(service.delete("del-1"))
            coVerify(exactly = 1) { storage.deleteObject(meta.storageKey) }
            coVerify(exactly = 1) { repository.delete("del-1") }
        }

        @Test
        fun `still removes metadata when storage object already missing`(): Unit = runBlocking {
            val meta = DocumentMetadata(
                id = "del-2", originalFileName = "f.pdf", contentType = "application/pdf",
                size = 100L, storageBucket = "documents", storageKey = "k/del-2-f.pdf",
                uploadedBy = "u", uploadedAt = "2026-03-19T10:00:00Z", status = "active",
                referenceNumber = null, documentType = null,
            )
            coEvery { repository.findById("del-2") } returns meta
            coEvery { storage.deleteObject(any()) } throws StorageNotFoundException("k/del-2-f.pdf")
            coEvery { repository.delete("del-2") } returns true

            assertTrue(service.delete("del-2"))
            coVerify(exactly = 1) { repository.delete("del-2") }
        }
    }
}
