package com.example.claims.verticle

import com.example.claims.config.AppConfig
import com.example.claims.handler.ClaimHandler
import com.example.claims.handler.ClaimInquiryHandler
import com.example.claims.repository.ClaimRepository
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.openapi.RouterBuilder
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

class MainVerticle : CoroutineVerticle() {

    private val logger = LoggerFactory.getLogger(MainVerticle::class.java)
    private lateinit var repository: ClaimRepository
    private lateinit var appConfig: AppConfig

    override suspend fun start() {
        appConfig = AppConfig.fromEnvironment()
        repository = ClaimRepository(appConfig.couchbase)

        // Connect to Couchbase in the background — never on the event loop.
        // The HTTP server starts regardless; claims will fail gracefully until DB is ready.
        launch {
            try {
                repository.connect()   // suspend fun — runs on Dispatchers.IO internally
            } catch (e: Exception) {
                logger.warn("Couchbase unavailable at startup — will retry on first request: {}", e.message)
            }
        }

        val claimHandler = ClaimHandler(repository, this)
        val claimInquiryHandler = ClaimInquiryHandler(repository, this)

        // ── OpenAPI router ──────────────────────────────────────────────────────
        val routerBuilder = try {
            RouterBuilder.create(vertx, "openapi/claims-api.yaml").coAwait()
        } catch (e: Exception) {
            logger.error("Failed to load OpenAPI spec — check claims-api.yaml: {}", e.message)
            throw e
        }

        routerBuilder.operation("submitClaim").handler { ctx -> claimHandler.submitClaim(ctx) }
        routerBuilder.operation("inquireClaim").handler { ctx -> claimInquiryHandler.inquireClaim(ctx) }
        routerBuilder.operation("healthCheck").handler { ctx ->
            val dbStatus = if (repository.isConnected()) "UP" else "DEGRADED"
            ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end("""{"status":"UP","db":"$dbStatus","timestamp":"${Instant.now()}"}""")
        }

        val apiRouter = routerBuilder.createRouter()

        // ── Main router ─────────────────────────────────────────────────────────
        val mainRouter = Router.router(vertx)

        // BodyHandler and CORS must be on the main router, not the OpenAPI sub-router
        mainRouter.route().handler(
            BodyHandler.create()
                .setBodyLimit(appConfig.server.maxBodySize)
                .setHandleFileUploads(false),
        )
        mainRouter.route().handler(corsHandler())

        mainRouter.errorHandler(400) { ctx ->
            logger.warn("OpenAPI validation error: {}", ctx.failure()?.message)
            ctx.response()
                .setStatusCode(422)
                .putHeader("Content-Type", "application/json")
                .end(validationErrorJson(ctx.failure()))
        }
        mainRouter.errorHandler(422) { ctx ->
            logger.warn("Validation error: {}", ctx.failure()?.message)
            ctx.response()
                .setStatusCode(422)
                .putHeader("Content-Type", "application/json")
                .end(validationErrorJson(ctx.failure()))
        }
        mainRouter.errorHandler(500) { ctx ->
            logger.error("Internal server error", ctx.failure())
            ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json")
                .end("""{"success":false,"message":"An unexpected server error occurred. Please try again."}""")
        }

        // Serve the OpenAPI spec so Swagger UI can fetch it
        mainRouter.get("/openapi/claims-api.yaml").handler { ctx ->
            vertx.fileSystem().readFile("openapi/claims-api.yaml")
                .onSuccess { buf ->
                    ctx.response()
                        .putHeader("Content-Type", "application/yaml")
                        .end(buf)
                }
                .onFailure { ctx.fail(500, it) }
        }

        // Swagger UI — served from classpath under swagger-ui/
        mainRouter.get("/docs/*").handler(
            StaticHandler.create("swagger-ui").setIndexPage("index.html"),
        )
        mainRouter.get("/docs").handler { ctx -> ctx.redirect("/docs/") }

        mainRouter.route("/*").subRouter(apiRouter)

        // ── HTTP server ─────────────────────────────────────────────────────────
        val server = vertx.createHttpServer()
            .requestHandler(mainRouter)
            .listen(appConfig.server.port, appConfig.server.host)
            .coAwait()

        logger.info(
            "Server ready  port={}  docs=http://{}:{}/docs  health=http://{}:{}/health",
            server.actualPort(),
            appConfig.server.host, server.actualPort(),
            appConfig.server.host, server.actualPort(),
        )
    }

    override suspend fun stop() {
        logger.info("Stopping MainVerticle")
        repository.disconnect()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun corsHandler(): CorsHandler {
        val allowedHeaders = setOf("Content-Type", "Authorization", "Accept", "X-Requested-With")
        val allowedMethods = setOf(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.DELETE, HttpMethod.OPTIONS, HttpMethod.HEAD,
        )
        return CorsHandler.create()
            .apply {
                appConfig.server.corsAllowedOrigins.forEach { o ->
                    if (o == "*") addRelativeOrigin(".*") else addOrigin(o)
                }
            }
            .allowedHeaders(allowedHeaders)
            .allowedMethods(allowedMethods)
            .allowCredentials(false)
            .maxAgeSeconds(3600)
    }

    private fun validationErrorJson(failure: Throwable?): String {
        val msg = (failure?.message ?: "Validation failed.")
            .replace("\\", "\\\\").replace("\"", "\\\"").take(500)
        return """{"success":false,"message":"Validation failed. Please review your submission.","errors":[{"field":"request","code":"validation_error","message":"$msg"}]}"""
    }
}
