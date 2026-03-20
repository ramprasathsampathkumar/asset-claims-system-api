package com.example.claims.repository

import com.couchbase.client.core.error.DocumentNotFoundException
import com.couchbase.client.java.Cluster
import com.couchbase.client.java.ClusterOptions
import com.couchbase.client.java.json.JsonObject as CouchbaseJsonObject
import com.couchbase.client.java.kv.InsertOptions
import com.couchbase.client.java.kv.RemoveOptions
import com.couchbase.client.java.query.QueryOptions
import com.example.claims.config.CouchbaseConfig
import com.example.claims.model.DocumentMetadata
import io.vertx.core.json.JsonObject as VertxJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class DocumentRepository(private val config: CouchbaseConfig) {

    private val logger = LoggerFactory.getLogger(DocumentRepository::class.java)
    private val connected = AtomicBoolean(false)

    @Volatile
    private var cluster: Cluster? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        logger.info("DocumentRepository connecting to Couchbase host={}", config.host)
        val c = Cluster.connect(
            config.host,
            ClusterOptions
                .clusterOptions(config.username, config.password)
                .environment { env ->
                    env.timeoutConfig { timeout ->
                        timeout.connectTimeout(Duration.ofSeconds(10))
                        timeout.kvTimeout(Duration.ofSeconds(5))
                        timeout.queryTimeout(Duration.ofSeconds(10))
                    }
                    env.ioConfig { io ->
                        io.numKvConnections(2)
                    }
                },
        )
        c.waitUntilReady(Duration.ofSeconds(15))
        cluster = c
        connected.set(true)
        logger.info("DocumentRepository connected to Couchbase")
    }

    fun disconnect() {
        cluster?.let {
            logger.info("DocumentRepository disconnecting from Couchbase")
            it.disconnect()
            connected.set(false)
        }
    }

    fun isConnected() = connected.get()

    suspend fun save(metadata: DocumentMetadata) = withContext(Dispatchers.IO) {
        val c = cluster ?: throw IllegalStateException("DocumentRepository not connected")

        val cbDoc = CouchbaseJsonObject.create()
            .put("id", metadata.id)
            .put("type", "document")
            .put("originalFileName", metadata.originalFileName)
            .put("contentType", metadata.contentType)
            .put("size", metadata.size)
            .put("storageBucket", metadata.storageBucket)
            .put("storageKey", metadata.storageKey)
            .put("uploadedBy", metadata.uploadedBy)
            .put("uploadedAt", metadata.uploadedAt)
            .put("status", metadata.status)
            .put("referenceNumber", metadata.referenceNumber)
            .put("documentType", metadata.documentType)

        val collection = c.bucket(config.bucket).also { it.waitUntilReady(Duration.ofSeconds(10)) }
            .defaultCollection()

        collection.insert(
            metadata.id,
            cbDoc,
            InsertOptions.insertOptions().timeout(Duration.ofSeconds(5)),
        )
        logger.info("Document metadata saved id={}", metadata.id)
    }

    suspend fun findById(id: String): DocumentMetadata? = withContext(Dispatchers.IO) {
        val c = cluster ?: throw IllegalStateException("DocumentRepository not connected")
        try {
            val collection = c.bucket(config.bucket).also { it.waitUntilReady(Duration.ofSeconds(10)) }
                .defaultCollection()
            val result = collection.get(id)
            val json = VertxJsonObject(result.contentAsObject().toString())
            if (json.getString("type") == "document") json.toDocumentMetadata() else null
        } catch (e: DocumentNotFoundException) {
            null
        }
    }

    suspend fun findByReferenceNumber(referenceNumber: String): List<DocumentMetadata> = withContext(Dispatchers.IO) {
        val c = cluster ?: throw IllegalStateException("DocumentRepository not connected")

        val queryStr = """
            SELECT META().id AS _id, d.*
            FROM `${config.bucket}` d
            WHERE d.type = ${'$'}docType
              AND d.referenceNumber = ${'$'}ref
            ORDER BY d.uploadedAt DESC
        """.trimIndent()

        val result = c.query(
            queryStr,
            QueryOptions.queryOptions()
                .parameters(
                    CouchbaseJsonObject.create()
                        .put("docType", "document")
                        .put("ref", referenceNumber),
                )
                .timeout(Duration.ofSeconds(10)),
        )

        result.rowsAsObject().mapNotNull { row ->
            runCatching { VertxJsonObject(row.toString()).toDocumentMetadata() }.getOrNull()
        }
    }

    suspend fun findAll(): List<DocumentMetadata> = withContext(Dispatchers.IO) {
        val c = cluster ?: throw IllegalStateException("DocumentRepository not connected")

        val queryStr = """
            SELECT META().id AS _id, d.*
            FROM `${config.bucket}` d
            WHERE d.type = ${'$'}docType
            ORDER BY d.uploadedAt DESC
        """.trimIndent()

        val result = c.query(
            queryStr,
            QueryOptions.queryOptions()
                .parameters(CouchbaseJsonObject.create().put("docType", "document"))
                .timeout(Duration.ofSeconds(10)),
        )

        result.rowsAsObject().mapNotNull { row ->
            runCatching { VertxJsonObject(row.toString()).toDocumentMetadata() }.getOrNull()
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val c = cluster ?: throw IllegalStateException("DocumentRepository not connected")
        try {
            val collection = c.bucket(config.bucket).also { it.waitUntilReady(Duration.ofSeconds(10)) }
                .defaultCollection()
            collection.remove(
                id,
                RemoveOptions.removeOptions().timeout(Duration.ofSeconds(5)),
            )
            logger.info("Document metadata deleted id={}", id)
            true
        } catch (e: DocumentNotFoundException) {
            false
        }
    }

    // ── conversion ───────────────────────────────────────────────────────────

    private fun VertxJsonObject.toDocumentMetadata() = DocumentMetadata(
        id = getString("id") ?: getString("_id") ?: "",
        originalFileName = getString("originalFileName") ?: "",
        contentType = getString("contentType") ?: "",
        size = getLong("size") ?: 0L,
        storageBucket = getString("storageBucket") ?: "",
        storageKey = getString("storageKey") ?: "",
        uploadedBy = getString("uploadedBy") ?: "anonymous",
        uploadedAt = getString("uploadedAt") ?: "",
        status = getString("status") ?: "active",
        referenceNumber = getString("referenceNumber"),
        documentType = getString("documentType"),
    )
}
