package com.example.claims

import com.example.claims.verticle.MainVerticle
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.example.claims.Main")

fun main() = runBlocking {
    val options = VertxOptions().apply {
        eventLoopPoolSize = Runtime.getRuntime().availableProcessors() * 2
        workerPoolSize = 40
        maxEventLoopExecuteTime = 5_000_000_000L // 5 seconds in nanoseconds
    }

    val vertx = Vertx.vertx(options)

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutdown hook triggered — closing Vert.x")
        runBlocking {
            try {
                vertx.close().coAwait()
                logger.info("Vert.x closed gracefully")
            } catch (e: Exception) {
                logger.error("Error during Vert.x shutdown", e)
            }
        }
    })

    try {
        vertx.deployVerticle(MainVerticle()).coAwait()
        logger.info("Application started successfully")
    } catch (e: Exception) {
        logger.error("Failed to start application", e)
        vertx.close().coAwait()
        throw e
    }
}
