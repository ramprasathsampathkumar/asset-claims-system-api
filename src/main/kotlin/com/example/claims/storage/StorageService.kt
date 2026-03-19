package com.example.claims.storage

/** Pluggable storage abstraction — swap MinIO for AWS S3 or any S3-compatible service
 *  by providing a different implementation without touching any other layer. */
interface StorageService {

    /** Create the bucket if it does not already exist. Called once at startup. */
    suspend fun ensureBucket()

    /**
     * Upload [data] under [key] in the configured bucket.
     * @throws StorageException on any storage-layer failure
     */
    suspend fun putObject(key: String, data: ByteArray, contentType: String)

    /**
     * Download the object identified by [key].
     * @throws StorageException on any storage-layer failure
     * @throws StorageNotFoundException when the key does not exist
     */
    suspend fun getObject(key: String): ByteArray

    /**
     * Delete the object identified by [key]. No-op if the key does not exist.
     * @throws StorageException on any storage-layer failure
     */
    suspend fun deleteObject(key: String)

    /** Name of the bucket this service operates on. */
    fun bucketName(): String

    /** Close any underlying HTTP/SDK resources. */
    fun close()
}

class StorageException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class StorageNotFoundException(key: String) :
    RuntimeException("Object not found in storage: $key")
