package com.example.claims.handler

import com.example.claims.repository.ClaimRepository
import com.example.claims.repository.DocumentRepository
import com.example.claims.service.DocumentService
import com.example.claims.service.DocumentValidationException
import io.vertx.core.buffer.Buffer
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class DocumentHandler(
    private val service: DocumentService,
    private val claimRepository: ClaimRepository,
    private val documentRepository: DocumentRepository,
    private val scope: CoroutineScope,
) {

    private val logger = LoggerFactory.getLogger(DocumentHandler::class.java)

    // POST /api/documents/upload  (multipart/form-data)
    fun upload(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val fileUploads = ctx.fileUploads()
                val fileUpload = fileUploads.find { it.name() == "file" }
                    ?: run {
                        respond400(ctx, "No 'file' field found in the multipart form. Please attach a file.")
                        return@launch
                    }

                val originalFileName = fileUpload.fileName()?.takeIf { it.isNotBlank() } ?: "upload"
                val claimedContentType = fileUpload.contentType() ?: "application/octet-stream"
                val uploadedBy = ctx.request().getFormAttribute("uploadedBy") ?: "anonymous"
                val referenceNumber = ctx.request().getFormAttribute("referenceNumber")
                val documentType = ctx.request().getFormAttribute("documentType")
                val tempPath = fileUpload.uploadedFileName()

                // Read file bytes from the temp upload path
                val buffer = ctx.vertx().fileSystem().readFile(tempPath).coAwait()
                val data = buffer.bytes

                // Clean up temp file regardless of outcome
                ctx.vertx().fileSystem().delete(tempPath)
                    .onFailure { logger.warn("Could not delete temp file {}: {}", tempPath, it.message) }

                val metadata = service.upload(
                    originalFileName, claimedContentType, data, uploadedBy,
                    referenceNumber, documentType,
                )
                logger.info("Document uploaded id={} fileName={}", metadata.id, metadata.originalFileName)

                ctx.response()
                    .setStatusCode(201)
                    .putHeader("Content-Type", "application/json")
                    .end("""{"success":true,"document":${metadata.toJson()}}""")

            } catch (e: DocumentValidationException) {
                logger.warn("Document upload validation failed: {}", e.message)
                val errorsJson = e.errors.joinToString(",") { "\"${esc(it)}\"" }
                ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json")
                    .end("""{"success":false,"message":"Validation failed.","errors":[$errorsJson]}""")
            } catch (e: Exception) {
                logger.error("Unexpected error during document upload", e)
                respond500(ctx)
            }
        }
    }

    // GET /api/documents
    fun list(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val documents = service.listAll()
                val docsJson = documents.joinToString(",") { it.toJson() }
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end("""{"success":true,"documents":[$docsJson],"total":${documents.size}}""")
            } catch (e: Exception) {
                logger.error("Unexpected error listing documents", e)
                respond500(ctx)
            }
        }
    }

    // POST /api/documents/by-claim
    // Verifies identity (referenceNumber + lastName, same as /api/inquiry) then returns
    // only documents tagged with that referenceNumber.
    fun listByClaim(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val body = ctx.body().asJsonObject() ?: run {
                    respond400(ctx, "Request body is required.")
                    return@launch
                }

                val referenceNumber = body.getString("referenceNumber")?.trim()
                val lastName = body.getString("lastName")?.trim()

                if (referenceNumber.isNullOrBlank()) {
                    respond400(ctx, "referenceNumber is required.")
                    return@launch
                }
                if (lastName.isNullOrBlank()) {
                    respond400(ctx, "lastName is required.")
                    return@launch
                }

                if (!claimRepository.isConnected()) {
                    logger.error("Couchbase not connected — cannot verify claim")
                    respond500(ctx)
                    return@launch
                }

                val dateOfBirth = body.getString("dateOfBirth")
                val claim = claimRepository.findByReferenceAndLastName(referenceNumber, lastName, dateOfBirth)

                if (claim == null) {
                    logger.info("Document list by claim: not found or identity mismatch referenceNumber={}", referenceNumber)
                    respond404(ctx, "Claim not found. Please check your reference number and last name.")
                    return@launch
                }

                val documents = documentRepository.findByReferenceNumber(referenceNumber)
                logger.info("Document list by claim: found {} document(s) referenceNumber={}", documents.size, referenceNumber)

                val docsJson = documents.joinToString(",") { it.toJson() }
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end("""{"success":true,"referenceNumber":"${esc(referenceNumber)}","documents":[$docsJson],"total":${documents.size}}""")

            } catch (e: Exception) {
                logger.error("Unexpected error listing documents by claim", e)
                respond500(ctx)
            }
        }
    }

    // GET /api/documents/:id/download
    fun download(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val id = ctx.pathParam("id")
                val result = service.getDocument(id)
                if (result == null) {
                    respond404(ctx, "Document not found.")
                    return@launch
                }

                val (metadata, bytes) = result
                val safeFileName = metadata.originalFileName
                    .replace("\\", "").replace("\"", "").replace("\n", "").replace("\r", "")

                ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", metadata.contentType)
                    .putHeader("Content-Disposition", "attachment; filename=\"$safeFileName\"")
                    .putHeader("Content-Length", bytes.size.toString())
                    .end(Buffer.buffer(bytes))

            } catch (e: Exception) {
                logger.error("Unexpected error downloading document", e)
                respond500(ctx)
            }
        }
    }

    // DELETE /api/documents/:id
    fun delete(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val id = ctx.pathParam("id")
                val deleted = service.delete(id)
                if (!deleted) {
                    respond404(ctx, "Document not found.")
                    return@launch
                }
                ctx.response().setStatusCode(204).end()
            } catch (e: Exception) {
                logger.error("Unexpected error deleting document", e)
                respond500(ctx)
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun respond400(ctx: RoutingContext, message: String) {
        ctx.response()
            .setStatusCode(400)
            .putHeader("Content-Type", "application/json")
            .end("""{"success":false,"message":"${esc(message)}"}""")
    }

    private fun respond404(ctx: RoutingContext, message: String) {
        ctx.response()
            .setStatusCode(404)
            .putHeader("Content-Type", "application/json")
            .end("""{"success":false,"message":"${esc(message)}"}""")
    }

    private fun respond500(ctx: RoutingContext) {
        ctx.response()
            .setStatusCode(500)
            .putHeader("Content-Type", "application/json")
            .end("""{"success":false,"message":"An unexpected server error occurred. Please try again."}""")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
