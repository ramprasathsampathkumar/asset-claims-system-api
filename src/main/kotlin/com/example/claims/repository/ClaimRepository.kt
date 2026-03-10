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
import java.security.MessageDigest
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

        // Hash lastName and optional DOB for inquiry verification — raw PII never stored
        val step2 = document.getJsonObject("step2")
        val lastNameHash = step2?.getString("lastName")?.let { sha256(it) }
        val dobHash = step2?.getString("dateOfBirth")?.let { sha256(it) }

        val cbDoc = JsonObject.create()
            .put("id", docId)
            .put("referenceNumber", referenceNumber)
            .put("status", "SUBMITTED")
            .put("createdAt", now)
            .put("updatedAt", now)
            .put("locale", document.getString("locale"))
            .put("submittedAt", document.getString("submittedAt"))
            .put("lastNameHash", lastNameHash)
            .put("dobHash", dobHash)
            .put("step1", JsonObject.fromJson(document.getJsonObject("step1").encode()))
            .put("step3", JsonObject.fromJson(document.getJsonObject("step3").encode()))
            .put("step4", JsonObject.fromJson(document.getJsonObject("step4").encode()))
            // step2 (PII) not stored — only hashes above for inquiry verification

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

    /**
     * Looks up a claim by referenceNumber and verifies the lastName hash.
     * If dateOfBirth is provided it is also verified against the stored hash.
     * Returns the claim document on success, null if not found or verification fails.
     */
    suspend fun findByReferenceAndLastName(
        referenceNumber: String,
        lastName: String,
        dateOfBirth: String?,
    ): VertxJsonObject? = withContext(Dispatchers.IO) {
        val c = cluster ?: throw IllegalStateException("Couchbase not connected")
        logger.info("Inquiry lookup referenceNumber={}", referenceNumber)

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
            logger.info("Inquiry: not found referenceNumber={}", referenceNumber)
            return@withContext null
        }

        val doc = VertxJsonObject(rows.first().toString())

        // Verify lastName hash
        val storedLastNameHash = doc.getString("lastNameHash")
        if (storedLastNameHash == null || storedLastNameHash != sha256(lastName)) {
            logger.info("Inquiry: lastName mismatch referenceNumber={}", referenceNumber)
            return@withContext null
        }

        // Verify DOB hash if caller supplied one
        if (dateOfBirth != null) {
            val storedDobHash = doc.getString("dobHash")
            if (storedDobHash == null || storedDobHash != sha256(dateOfBirth)) {
                logger.info("Inquiry: DOB mismatch referenceNumber={}", referenceNumber)
                return@withContext null
            }
        }

        doc
    }

    fun isConnected() = connected.get()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.trim().lowercase().toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun generateReferenceNumber(): String {
        val base36Ts = java.lang.Long.toString(System.currentTimeMillis() / 1000, 36).uppercase()
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random4 = (1..4).map { chars.random() }.joinToString("")
        return "ACL-$base36Ts-$random4"
    }
}
