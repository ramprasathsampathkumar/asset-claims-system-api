package com.example.claims.service

import com.example.claims.verticle.MainVerticle
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.junit5.VertxExtension
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Integration-level tests for the geocoding search path.
 *
 * These tests deploy the full verticle and send POST /api/store-locator/search
 * payloads that exercise the three coordinate-resolution branches:
 *
 *   Case 1 — lat/lng provided (GPS): geocoding skipped, coordinates used as-is
 *   Case 2 — query + radiusKm, no lat/lng: server geocodes query (live Nominatim call)
 *   Case 3 — query only, no radius: text-only search, no geocoding
 *
 * Case 2 makes a real outbound call to nominatim.openstreetmap.org.
 * Tests are tagged @Tag("geocoding") so they can be excluded in CI environments
 * without network access:  ./gradlew test -x geocoding
 */
@ExtendWith(VertxExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GeocodingServiceTest {

    companion object {
        private lateinit var vertx: Vertx
        private lateinit var client: WebClient
        private const val PORT = 18083

        @BeforeAll
        @JvmStatic
        fun setup(): Unit = runBlocking {
            System.setProperty("SERVER_PORT", PORT.toString())
            System.setProperty("COUCHBASE_HOST", "localhost")

            vertx = Vertx.vertx()
            client = WebClient.create(
                vertx,
                WebClientOptions().setDefaultPort(PORT).setDefaultHost("localhost"),
            )
            vertx.deployVerticle(MainVerticle()).coAwait()
        }

        @AfterAll
        @JvmStatic
        fun teardown(): Unit = runBlocking {
            client.close()
            vertx.close().coAwait()
        }
    }

    // ── Case 1: lat/lng provided (GPS) — geocoding skipped ───────────────────

    @Test
    @Order(1)
    fun `search with explicit lat-lng uses coordinates directly`() = runBlocking {
        // Times Square — only NYC stores should be within 20 km
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("latitude", 40.7580)
                    .put("longitude", -73.9855)
                    .put("radiusKm", 20.0),
            )
            .coAwait()

        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonObject()
        val stores = body.getJsonArray("stores")
        assertTrue(stores.size() > 0, "Expected NYC stores within 20 km of Times Square")
        // All returned stores must be within 20 km
        for (i in 0 until stores.size()) {
            val dist = stores.getJsonObject(i).getDouble("distance")
            assertTrue(dist <= 20.0, "Store at index $i is ${dist} km away, expected <= 20")
        }
    }

    @Test
    @Order(2)
    fun `search with lat-lng and query applies text filter on top of radius`() = runBlocking {
        // From Times Square with query "Brooklyn" — should only return stores whose
        // name/city contains "Brooklyn" that are also within 20 km
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("latitude", 40.7580)
                    .put("longitude", -73.9855)
                    .put("radiusKm", 20.0)
                    .put("query", "Brooklyn"),
            )
            .coAwait()

        assertEquals(200, resp.statusCode())
        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        for (i in 0 until stores.size()) {
            val s = stores.getJsonObject(i)
            val haystack = listOf(
                s.getString("name") ?: "",
                s.getJsonObject("address")?.getString("city") ?: "",
            ).joinToString(" ").lowercase()
            assertTrue(haystack.contains("brooklyn"), "Store '${s.getString("name")}' does not match 'brooklyn'")
        }
    }

    // ── Case 3: text-only search (no radius, no lat/lng) ─────────────────────

    @Test
    @Order(3)
    fun `search with query only performs text match without geocoding`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("query", "Loop"))
            .coAwait()

        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonObject()
        assertTrue(body.getInteger("totalCount") > 0)
        // All results should mention "Loop" in name or city
        val stores = body.getJsonArray("stores")
        for (i in 0 until stores.size()) {
            val s = stores.getJsonObject(i)
            val haystack = (s.getString("name") ?: "").lowercase()
            assertTrue(haystack.contains("loop"), "Unexpected store '${s.getString("name")}' in text-only results")
        }
    }

    @Test
    @Order(4)
    fun `search with query only returns all results sorted alphabetically`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("query", "Branch"))
            .coAwait()

        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        assertTrue(stores.size() > 1)
        val names = (0 until stores.size()).map { stores.getJsonObject(it).getString("name") }
        assertEquals(names.sorted(), names, "Text-only results should be sorted alphabetically")
    }

    // ── Case 2: query + radiusKm, no lat/lng — live geocoding ────────────────

    @Test
    @Order(5)
    @Tag("geocoding")
    fun `search with query and radius geocodes query and returns nearby stores`() = runBlocking {
        // "Manhattan" should geocode to ~40.78, -73.97 — NYC stores should be within 25 km
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("query", "Manhattan")
                    .put("radiusKm", 25.0),
            )
            .coAwait()

        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonObject()
        val stores = body.getJsonArray("stores")

        assertTrue(stores.size() > 0, "Expected stores near Manhattan, got 0")
        // All results must be within 25 km
        for (i in 0 until stores.size()) {
            val dist = stores.getJsonObject(i).getDouble("distance")
            assertTrue(dist <= 25.0, "Store at index $i has distance $dist km, expected <= 25")
        }
        // Distances should be sorted ascending
        val distances = (0 until stores.size()).map { stores.getJsonObject(it).getDouble("distance") }
        for (i in 1 until distances.size) {
            assertTrue(distances[i] >= distances[i - 1], "Results not sorted by distance at index $i")
        }
    }

    @Test
    @Order(6)
    @Tag("geocoding")
    fun `search with city query and radius excludes distant stores`() = runBlocking {
        // "Chicago" → geocodes to ~41.88, -87.63; 50 km radius should exclude NY/LA/Houston
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("query", "Chicago")
                    .put("radiusKm", 50.0),
            )
            .coAwait()

        assertEquals(200, resp.statusCode())
        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        assertTrue(stores.size() > 0, "Expected Chicago stores")
        for (i in 0 until stores.size()) {
            val s = stores.getJsonObject(i)
            assertEquals(
                "Chicago",
                s.getJsonObject("address").getString("city"),
                "Expected only Chicago stores within 50 km of Chicago",
            )
        }
    }

    @Test
    @Order(7)
    @Tag("geocoding")
    fun `search with unresolvable query and explicit radius falls back to text search`() = runBlocking {
        // "zzznomatch" will not geocode — backend falls back to text search, which also returns 0
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("query", "zzznomatch")
                    .put("radiusKm", 25.0),
            )
            .coAwait()

        assertEquals(200, resp.statusCode())
        assertEquals(0, resp.bodyAsJsonObject().getInteger("totalCount"))
    }

    // ── Auto-expanding radius (no radiusKm supplied) ──────────────────────────

    @Test
    @Order(8)
    @Tag("geocoding")
    fun `search with query and no radius auto-expands to find stores`() = runBlocking {
        // "Manhattan" geocodes to ~40.78, -73.97; all NYC stores are within 25 km
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("query", "Manhattan"))
            .coAwait()

        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonObject()
        assertTrue(body.getInteger("totalCount") > 0, "Expected stores found via auto-expanding radius")
        // Results should be sorted by distance
        val distances = body.getJsonArray("stores").map { (it as JsonObject).getDouble("distance") }
        for (i in 1 until distances.size) {
            assertTrue(distances[i] >= distances[i - 1], "Results not sorted by distance at index $i")
        }
    }

    @Test
    @Order(9)
    @Tag("geocoding")
    fun `search with query and no radius returns stores within expanded radius`() = runBlocking {
        // "Los Angeles" — all LA stores should be within 25 km; none from other cities
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("query", "Los Angeles"))
            .coAwait()

        assertEquals(200, resp.statusCode())
        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        assertTrue(stores.size() > 0)
        for (i in 0 until stores.size()) {
            val dist = stores.getJsonObject(i).getDouble("distance")
            assertTrue(dist <= 25.0, "Store at index $i has distance $dist km, expected within first radius tier (25 km)")
        }
    }

    @Test
    @Order(10)
    fun `search with no query and no coords returns all stores`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject())
            .coAwait()

        assertEquals(200, resp.statusCode())
        assertEquals(16, resp.bodyAsJsonObject().getInteger("totalCount"))
    }
}
