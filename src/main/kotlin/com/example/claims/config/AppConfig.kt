package com.example.claims.config

data class CouchbaseConfig(
    val host: String,
    val username: String,
    val password: String,
    val bucket: String,
)

data class ServerConfig(
    val port: Int,
    val host: String,
    val maxBodySize: Long,
    val corsAllowedOrigins: List<String>,
)

data class AppConfig(
    val server: ServerConfig,
    val couchbase: CouchbaseConfig,
) {
    companion object {
        // Reads env var first, falls back to JVM system property (enables test overrides)
        private fun cfg(key: String): String? = System.getenv(key) ?: System.getProperty(key)

        fun fromEnvironment(): AppConfig {
            return AppConfig(
                server = ServerConfig(
                    port = cfg("SERVER_PORT")?.toIntOrNull() ?: 8080,
                    host = cfg("SERVER_HOST") ?: "0.0.0.0",
                    maxBodySize = cfg("MAX_BODY_SIZE")?.toLongOrNull() ?: (1024 * 1024), // 1MB
                    corsAllowedOrigins = cfg("CORS_ALLOWED_ORIGINS")
                        ?.split(",")
                        ?.map { it.trim() }
                        ?: listOf("*"),
                ),
                couchbase = CouchbaseConfig(
                    host = cfg("COUCHBASE_HOST") ?: "localhost",
                    username = cfg("COUCHBASE_USERNAME") ?: "Administrator",
                    password = cfg("COUCHBASE_PASSWORD") ?: "password",
                    bucket = cfg("COUCHBASE_BUCKET") ?: "claims",
                ),
            )
        }
    }
}
