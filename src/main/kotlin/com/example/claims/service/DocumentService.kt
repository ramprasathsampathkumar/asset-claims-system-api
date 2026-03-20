package com.example.claims.service

import com.example.claims.model.DocumentMetadata
import com.example.claims.repository.DocumentRepository
import com.example.claims.storage.StorageNotFoundException
import com.example.claims.storage.StorageService
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class DocumentService(
    private val storage: StorageService,
    private val repository: DocumentRepository,
) {

    private val logger = LoggerFactory.getLogger(DocumentService::class.java)

    // ── validation constants ─────────────────────────────────────────────────

    private val allowedMimeTypes = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "image/png",
        "image/jpeg",
    )

    private val allowedExtensions = setOf("pdf", "doc", "docx", "png", "jpg", "jpeg")

    private val maxFileSize = 10 * 1024 * 1024L // 10 MB

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Validate, store, and record metadata for a new document upload.
     * @param referenceNumber optional claim reference (e.g. ACL-XXXXX-XXXX) for traceability
     * @param documentType    optional UI-supplied label (e.g. "passport", "bank_statement")
     * @throws DocumentValidationException if the file fails any validation rule
     */
    suspend fun upload(
        originalFileName: String,
        claimedContentType: String,
        data: ByteArray,
        uploadedBy: String,
        referenceNumber: String? = null,
        documentType: String? = null,
    ): DocumentMetadata {
        val errors = validate(originalFileName, claimedContentType, data)
        if (errors.isNotEmpty()) throw DocumentValidationException(errors)

        val sanitizedName = sanitizeFileName(originalFileName)
        val detectedType = detectMimeFromBytes(data) ?: claimedContentType
        val id = UUID.randomUUID().toString()
        val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE).replace("-", "/")
        val storageKey = "$date/$id-$sanitizedName"

        storage.putObject(storageKey, data, detectedType)
        logger.info("Uploaded file to storage key={} size={} referenceNumber={}", storageKey, data.size, referenceNumber)

        val metadata = DocumentMetadata(
            id = id,
            originalFileName = sanitizedName,
            contentType = detectedType,
            size = data.size.toLong(),
            storageBucket = storage.bucketName(),
            storageKey = storageKey,
            uploadedBy = uploadedBy.ifBlank { "anonymous" },
            uploadedAt = Instant.now().toString(),
            status = "active",
            referenceNumber = referenceNumber?.takeIf { it.isNotBlank() },
            documentType = documentType?.takeIf { it.isNotBlank() },
        )

        repository.save(metadata)
        return metadata
    }

    suspend fun listAll(): List<DocumentMetadata> = repository.findAll()

    /**
     * Retrieve metadata and file bytes for the given document ID.
     * @return Pair of metadata + bytes, or null if document not found
     */
    suspend fun getDocument(id: String): Pair<DocumentMetadata, ByteArray>? {
        val metadata = repository.findById(id) ?: return null
        return try {
            val bytes = storage.getObject(metadata.storageKey)
            Pair(metadata, bytes)
        } catch (e: StorageNotFoundException) {
            logger.warn("Object missing from storage for document id={} key={}", id, metadata.storageKey)
            null
        }
    }

    /**
     * Delete a document from storage and remove its metadata.
     * @return true if deleted, false if document was not found
     */
    suspend fun delete(id: String): Boolean {
        val metadata = repository.findById(id) ?: return false
        try {
            storage.deleteObject(metadata.storageKey)
        } catch (e: StorageNotFoundException) {
            logger.warn("Object already missing from storage key={}", metadata.storageKey)
        }
        repository.delete(id)
        logger.info("Document deleted id={}", id)
        return true
    }

    // ── validation ───────────────────────────────────────────────────────────

    internal fun validate(fileName: String, claimedContentType: String, data: ByteArray): List<String> {
        val errors = mutableListOf<String>()

        if (data.size > maxFileSize) {
            errors += "File size ${data.size} bytes exceeds the maximum allowed size of $maxFileSize bytes (10 MB)"
        }

        val normalizedMime = claimedContentType.substringBefore(";").trim().lowercase()
        if (normalizedMime !in allowedMimeTypes) {
            errors += "Content type '$normalizedMime' is not allowed. Allowed types: ${allowedMimeTypes.joinToString(", ")}"
        }

        val ext = fileName.substringAfterLast(".", "").lowercase()
        if (ext !in allowedExtensions) {
            errors += "File extension '.$ext' is not allowed. Allowed extensions: ${allowedExtensions.map { ".$it" }.joinToString(", ")}"
        }

        // Magic-byte check: confirms the actual file content matches a known safe type
        if (data.isNotEmpty()) {
            val detected = detectMimeFromBytes(data)
            if (detected != null && detected !in allowedMimeTypes) {
                errors += "File content does not match any allowed file type (detected: $detected)"
            }
        }

        return errors
    }

    internal fun sanitizeFileName(name: String): String {
        // Remove path separators and null bytes, collapse to safe chars
        val base = name
            .substringAfterLast("/")
            .substringAfterLast("\\")
            .replace("\u0000", "")
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .trimStart('.')
            .trim('_')
            .take(200)
        return base.ifBlank { "upload" }
    }

    internal fun detectMimeFromBytes(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        val b = bytes
        return when {
            // %PDF
            b[0] == 0x25.toByte() && b[1] == 0x50.toByte() &&
                b[2] == 0x44.toByte() && b[3] == 0x46.toByte() -> "application/pdf"
            // PNG: \x89PNG
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> "image/png"
            // JPEG: \xFF\xD8\xFF
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
            // ZIP / DOCX: PK\x03\x04
            b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                b[2] == 0x03.toByte() && b[3] == 0x04.toByte() ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            // OLE2 / DOC: \xD0\xCF\x11\xE0
            b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() &&
                b[2] == 0x11.toByte() && b[3] == 0xE0.toByte() -> "application/msword"
            else -> null
        }
    }
}

class DocumentValidationException(val errors: List<String>) :
    RuntimeException("Document validation failed: ${errors.joinToString("; ")}")
