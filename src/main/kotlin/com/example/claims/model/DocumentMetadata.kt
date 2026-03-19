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
) {
    fun toJson(): String {
        val safeFileName = esc(originalFileName)
        val safeContentType = esc(contentType)
        val safeBucket = esc(storageBucket)
        val safeKey = esc(storageKey)
        val safeUploadedBy = esc(uploadedBy)
        val safeUploadedAt = esc(uploadedAt)
        val safeStatus = esc(status)
        return """{"id":"${esc(id)}","originalFileName":"$safeFileName","contentType":"$safeContentType","size":$size,"storageBucket":"$safeBucket","storageKey":"$safeKey","uploadedBy":"$safeUploadedBy","uploadedAt":"$safeUploadedAt","status":"$safeStatus"}"""
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
