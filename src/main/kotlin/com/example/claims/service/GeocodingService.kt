package com.example.claims.service

import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.LoggerFactory

data class GeocodedCoords(val lat: Double, val lng: Double, val displayName: String)

/**
 * Server-side geocoding via Nominatim (OpenStreetMap).
 *
 * Used by the store-locator search endpoint to resolve a text query (e.g. "Manhattan")
 * into coordinates when the frontend sends a radius search without lat/lng.
 *
 * Nominatim fair-use policy: max 1 request/second, must supply a descriptive User-Agent.
 * Set NOMINATIM_USER_AGENT to identify your deployment (e.g. "MyApp/1.0 admin@example.com").
 */
class GeocodingService(vertx: Vertx, private val userAgent: String) {

    private val logger = LoggerFactory.getLogger(GeocodingService::class.java)

    private val client = WebClient.create(
        vertx,
        WebClientOptions()
            .setSsl(true)
            .setDefaultHost("nominatim.openstreetmap.org")
            .setDefaultPort(443)
            .setConnectTimeout(5_000)
            .setIdleTimeout(10),
    )

    /**
     * Geocodes [query] to coordinates using the Nominatim search API.
     *
     * Returns the best match, or null if no result was found or the call failed.
     * Failures are logged as warnings and suppressed so the caller can fall back gracefully.
     */
    suspend fun geocode(query: String): GeocodedCoords? {
        return try {
            val response = client.get("/search")
                .addQueryParam("q", query)
                .addQueryParam("format", "json")
                .addQueryParam("limit", "1")
                .addQueryParam("addressdetails", "0")
                .putHeader("User-Agent", userAgent)
                .putHeader("Accept-Language", "en")
                .send()
                .coAwait()

            val body = response.bodyAsJsonArray()
            if (body == null || body.isEmpty) {
                logger.debug("Nominatim returned no results for query='{}'", query)
                return null
            }

            val first = body.getJsonObject(0)
            val lat = first.getString("lat")?.toDoubleOrNull()
            val lon = first.getString("lon")?.toDoubleOrNull()
            val displayName = first.getString("display_name") ?: query

            if (lat == null || lon == null) {
                logger.warn("Nominatim result missing lat/lon for query='{}'", query)
                return null
            }

            logger.debug("Geocoded '{}' → lat={} lng={} ({})", query, lat, lon, displayName)
            GeocodedCoords(lat, lon, displayName)
        } catch (e: Exception) {
            logger.warn("Geocoding failed for query='{}': {}", query, e.message)
            null
        }
    }

    fun close() {
        client.close()
    }
}
