package com.example.claims.model

data class DocumentMetadata(
    val id: String,
    val originalFileName: String,
    val contentType: String,
    val size: Long,
    val storageBucket: String,
    val storageKey: String,
    val uploadedBy: String,
    val uploadedAt: String,
    val status: String,
    val referenceNumber: String?,  // links document to a submitted claim
    val documentType: String?,     // e.g. "passport", "bank_statement" — UI-supplied label
) {
    fun toJson(): String {
        val refJson = if (referenceNumber != null) """"referenceNumber":"${esc(referenceNumber)}"""" else """"referenceNumber":null"""
        val docTypeJson = if (documentType != null) """"documentType":"${esc(documentType)}"""" else """"documentType":null"""
        return """{"id":"${esc(id)}","originalFileName":"${esc(originalFileName)}","contentType":"${esc(contentType)}","size":$size,"storageBucket":"${esc(storageBucket)}","storageKey":"${esc(storageKey)}","uploadedBy":"${esc(uploadedBy)}","uploadedAt":"${esc(uploadedAt)}","status":"${esc(status)}",$refJson,$docTypeJson}"""
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
