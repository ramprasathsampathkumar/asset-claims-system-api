package com.example.claims.repository

import com.couchbase.client.java.Cluster
import com.couchbase.client.java.ClusterOptions
import com.couchbase.client.java.json.JsonObject
import com.couchbase.client.java.kv.InsertOptions
import com.couchbase.client.java.query.QueryOptions
import com.example.claims.config.CouchbaseConfig
import io.vertx.core.json.JsonObject as VertxJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ClaimRepository(private val config: CouchbaseConfig) {

    private val logger = LoggerFactory.getLogger(ClaimRepository::class.java)
    private val connected = AtomicBoolean(false)

    @Volatile
    private var cluster: Cluster? = null

    /** Called on an IO thread — never on the Vert.x event loop. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        logger.info("Connecting to Couchbase at host={}", config.host)
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
                        io.numKvConnections(4)
                    }
                },
        )
        c.waitUntilReady(Duration.ofSeconds(15))
        cluster = c
        connected.set(true)
        logger.info("Couchbase connected successfully")
    }

    fun disconnect() {
        cluster?.let {
            logger.info("Disconnecting from Couchbase")
            it.disconnect()
            connected.set(false)
        }
    }

    suspend fun saveClaim(document: VertxJsonObject): String = withContext(Dispatchers.IO) {
        val c = cluster ?: throw IllegalStateException("Couchbase not connected")
        val docId = UUID.randomUUID().toString()
        val referenceNumber = generateReferenceNumber()
        val now = Instant.now().toString()

        val cbDoc = JsonObject.create()
            .put("id", docId)
            .put("referenceNumber", referenceNumber)
            .put("status", "SUBMITTED")
            .put("createdAt", now)
            .put("updatedAt", now)
            .put("locale", document.getString("locale"))
            .put("submittedAt", document.getString("submittedAt"))
            .put("step1", JsonObject.fromJson(document.getJsonObject("step1").encode()))
            .put("step3", JsonObject.fromJson(document.getJsonObject("step3").encode()))
            .put("step4", JsonObject.fromJson(document.getJsonObject("step4").encode()))
            // step2 (PII) stored separately; never logged

        logger.info("Saving claim referenceNumber={} docId={}", referenceNumber, docId)

        val bucket = c.bucket(config.bucket)
        bucket.waitUntilReady(Duration.ofSeconds(10))
        val collection = bucket.defaultCollection()

        collection.insert(
            docId,
            cbDoc,
            InsertOptions.insertOptions().timeout(Duration.ofSeconds(5)),
        )

        logger.info("Claim saved referenceNumber={}", referenceNumber)
        referenceNumber
    }

    suspend fun findByReference(referenceNumber: String): VertxJsonObject? = withContext(Dispatchers.IO) {
        val c = cluster ?: throw IllegalStateException("Couchbase not connected")
        logger.info("Looking up claim referenceNumber={}", referenceNumber)

        val queryStr = """
            SELECT META().id, c.*
            FROM `${config.bucket}` c
            WHERE c.referenceNumber = ${'$'}ref
            LIMIT 1
        """.trimIndent()

        val result = c.query(
            queryStr,
            QueryOptions.queryOptions()
                .parameters(JsonObject.create().put("ref", referenceNumber))
                .timeout(Duration.ofSeconds(10)),
        )

        val rows = result.rowsAsObject()
        if (rows.isEmpty()) {
            logger.info("Claim not found referenceNumber={}", referenceNumber)
            null
        } else {
            VertxJsonObject(rows.first().toString())
        }
    }

    fun isConnected() = connected.get()

    private fun generateReferenceNumber(): String {
        val base36Ts = java.lang.Long.toString(System.currentTimeMillis() / 1000, 36).uppercase()
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random4 = (1..4).map { chars.random() }.joinToString("")
        return "ACL-$base36Ts-$random4"
    }
}
