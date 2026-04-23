package com.example.claims.handler

import com.example.claims.verticle.MainVerticle
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import io.vertx.junit5.VertxExtension

@ExtendWith(VertxExtension::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StoreLocatorIntegrationTest {

    companion object {
        private lateinit var vertx: Vertx
        private lateinit var client: WebClient
        private const val PORT = 18082

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

    // ── GET /api/store-locator/config ─────────────────────────────────────────

    @Test
    @Order(1)
    fun `GET config returns 200 with expected shape`() = runBlocking {
        val resp = client.get("/api/store-locator/config").send().coAwait()
        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonObject()
        assertNotNull(body.getString("styleUrl"))
        assertNotNull(body.getJsonObject("defaultCenter"))
        assertEquals(39.8283, body.getJsonObject("defaultCenter").getDouble("lat"))
        assertEquals(-98.5795, body.getJsonObject("defaultCenter").getDouble("lng"))
        assertNotNull(body.getInteger("defaultZoom"))
        assertNotNull(body.getString("attribution"))
    }

    // ── GET /api/store-locator/filters ────────────────────────────────────────

    @Test
    @Order(2)
    fun `GET filters returns 200 with storeTypes, services, radiusOptions`() = runBlocking {
        val resp = client.get("/api/store-locator/filters").send().coAwait()
        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonObject()

        val storeTypes = body.getJsonArray("storeTypes")
        assertNotNull(storeTypes)
        assertEquals(3, storeTypes.size())

        val services = body.getJsonArray("services")
        assertNotNull(services)
        assertEquals(6, services.size())

        val radii = body.getJsonArray("radiusOptions")
        assertNotNull(radii)
        assertEquals(4, radii.size())
    }

    @Test
    @Order(3)
    fun `GET filters storeTypes contain expected values`() = runBlocking {
        val resp = client.get("/api/store-locator/filters").send().coAwait()
        val types = resp.bodyAsJsonObject().getJsonArray("storeTypes")
            .map { (it as JsonObject).getString("value") }
        assertTrue("branch" in types)
        assertTrue("service_center" in types)
        assertTrue("atm" in types)
    }

    // ── GET /api/store-locator/stores ─────────────────────────────────────────

    @Test
    @Order(4)
    fun `GET stores returns 200 with all 16 stores`() = runBlocking {
        val resp = client.get("/api/store-locator/stores").send().coAwait()
        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonArray()
        assertEquals(16, body.size())
    }

    @Test
    @Order(5)
    fun `GET stores each store has required fields`() = runBlocking {
        val resp = client.get("/api/store-locator/stores").send().coAwait()
        val stores = resp.bodyAsJsonArray()
        for (i in 0 until stores.size()) {
            val store = stores.getJsonObject(i)
            assertNotNull(store.getString("id"), "Missing id at index $i")
            assertNotNull(store.getString("name"), "Missing name at index $i")
            assertNotNull(store.getString("storeType"), "Missing storeType at index $i")
            assertNotNull(store.getJsonObject("address"), "Missing address at index $i")
            assertNotNull(store.getDouble("latitude"), "Missing latitude at index $i")
            assertNotNull(store.getDouble("longitude"), "Missing longitude at index $i")
            assertNotNull(store.getJsonObject("hours"), "Missing hours at index $i")
            assertNotNull(store.getJsonArray("services"), "Missing services at index $i")
            assertNotNull(store.getValue("distance"), "Missing distance at index $i")
            assertNotNull(store.getValue("openNow"), "Missing openNow at index $i")
        }
    }

    @Test
    @Order(6)
    fun `GET stores distance is zero when no origin provided`() = runBlocking {
        val resp = client.get("/api/store-locator/stores").send().coAwait()
        val stores = resp.bodyAsJsonArray()
        for (i in 0 until stores.size()) {
            assertEquals(0.0, stores.getJsonObject(i).getDouble("distance"), "Expected distance=0 at index $i")
        }
    }

    // ── GET /api/store-locator/stores/:storeId ────────────────────────────────

    @Test
    @Order(7)
    fun `GET store by id returns correct store`() = runBlocking {
        val resp = client.get("/api/store-locator/stores/nyc-001").send().coAwait()
        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonObject()
        assertEquals("nyc-001", body.getString("id"))
        assertEquals("Manhattan Financial District Branch", body.getString("name"))
        assertEquals("branch", body.getString("storeType"))
    }

    @Test
    @Order(8)
    fun `GET store by id returns address fields`() = runBlocking {
        val resp = client.get("/api/store-locator/stores/chi-001").send().coAwait()
        val address = resp.bodyAsJsonObject().getJsonObject("address")
        assertEquals("Chicago", address.getString("city"))
        assertEquals("IL", address.getString("state"))
        assertEquals("US", address.getString("country"))
    }

    @Test
    @Order(9)
    fun `GET store by unknown id returns 404 with message`() = runBlocking {
        val resp = client.get("/api/store-locator/stores/does-not-exist").send().coAwait()
        assertEquals(404, resp.statusCode())
        val body = resp.bodyAsJsonObject()
        assertEquals("Store not found.", body.getString("message"))
    }

    // ── POST /api/store-locator/search — empty body ────────────────────────────

    @Test
    @Order(10)
    fun `POST search with empty body returns all 16 stores`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject())
            .coAwait()
        assertEquals(200, resp.statusCode())
        val body = resp.bodyAsJsonObject()
        assertEquals(16, body.getInteger("totalCount"))
        assertEquals(16, body.getJsonArray("stores").size())
    }

    // ── POST search — text query ──────────────────────────────────────────────

    @Test
    @Order(11)
    fun `POST search by city returns matching stores`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("query", "Houston"))
            .coAwait()
        val body = resp.bodyAsJsonObject()
        assertEquals(3, body.getInteger("totalCount"))
        val stores = body.getJsonArray("stores")
        for (i in 0 until stores.size()) {
            assertEquals("Houston", stores.getJsonObject(i).getJsonObject("address").getString("city"))
        }
    }

    @Test
    @Order(12)
    fun `POST search by name substring with lat-lng applies case-insensitive text filter`() = runBlocking {
        // Provide GPS coords (Case 1) so text filter is applied, not geocoding
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("latitude", 34.0736)
                    .put("longitude", -118.4004)
                    .put("query", "beverly hills"),
            )
            .coAwait()
        val body = resp.bodyAsJsonObject()
        assertEquals(1, body.getInteger("totalCount"))
        assertEquals("lax-002", body.getJsonArray("stores").getJsonObject(0).getString("id"))
    }

    @Test
    @Order(13)
    fun `POST search with non-matching query returns 0 results`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("query", "zzznomatch"))
            .coAwait()
        val body = resp.bodyAsJsonObject()
        assertEquals(0, body.getInteger("totalCount"))
        assertEquals(0, body.getJsonArray("stores").size())
    }

    // ── POST search — storeType filter ────────────────────────────────────────

    @Test
    @Order(14)
    fun `POST search filtered by atm returns only ATMs`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("storeTypes", JsonArray().add("atm")))
            .coAwait()
        val body = resp.bodyAsJsonObject()
        val stores = body.getJsonArray("stores")
        assertTrue(stores.size() > 0)
        for (i in 0 until stores.size()) {
            assertEquals("atm", stores.getJsonObject(i).getString("storeType"))
        }
    }

    @Test
    @Order(15)
    fun `POST search filtered by branch and service_center excludes ATMs`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject().put("storeTypes", JsonArray().add("branch").add("service_center")),
            )
            .coAwait()
        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        for (i in 0 until stores.size()) {
            assertNotEquals("atm", stores.getJsonObject(i).getString("storeType"))
        }
    }

    // ── POST search — services filter ─────────────────────────────────────────

    @Test
    @Order(16)
    fun `POST search by single service returns only stores offering it`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("services", JsonArray().add("financial_advisor")))
            .coAwait()
        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        assertTrue(stores.size() > 0)
        for (i in 0 until stores.size()) {
            val services = stores.getJsonObject(i).getJsonArray("services").map { it.toString() }
            assertTrue("financial_advisor" in services, "Store at index $i missing financial_advisor")
        }
    }

    @Test
    @Order(17)
    fun `POST search by multiple services uses AND logic`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject().put("services", JsonArray().add("notary").add("wire_transfer").add("financial_advisor")),
            )
            .coAwait()
        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        assertTrue(stores.size() > 0)
        for (i in 0 until stores.size()) {
            val services = stores.getJsonObject(i).getJsonArray("services").map { it.toString() }
            assertTrue("notary" in services)
            assertTrue("wire_transfer" in services)
            assertTrue("financial_advisor" in services)
        }
    }

    // ── POST search — coordinates + radius ────────────────────────────────────

    @Test
    @Order(18)
    fun `POST search with coordinates sorts by distance ascending`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("latitude", 40.7580)
                    .put("longitude", -73.9855),
            )
            .coAwait()
        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        assertTrue(stores.size() > 1)
        val distances = (0 until stores.size()).map { stores.getJsonObject(it).getDouble("distance") }
        for (i in 1 until distances.size) {
            assertTrue(distances[i] >= distances[i - 1], "Distances not sorted at index $i")
        }
    }

    @Test
    @Order(19)
    fun `POST search with radius filters to nearby stores only`() = runBlocking {
        // From downtown NYC, 15 km — should include NY stores, exclude Chicago/LA/Houston
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("latitude", 40.7128)
                    .put("longitude", -74.0060)
                    .put("radiusKm", 15.0),
            )
            .coAwait()
        val body = resp.bodyAsJsonObject()
        val stores = body.getJsonArray("stores")
        assertTrue(stores.size() > 0, "Expected some NYC stores")
        for (i in 0 until stores.size()) {
            val dist = stores.getJsonObject(i).getDouble("distance")
            assertTrue(dist <= 15.0, "Store at index $i has distance $dist km, expected <= 15")
        }
    }

    @Test
    @Order(20)
    fun `POST search with large radius returns stores across all cities`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("latitude", 39.8283)
                    .put("longitude", -98.5795)
                    .put("radiusKm", 3000.0),
            )
            .coAwait()
        assertEquals(16, resp.bodyAsJsonObject().getInteger("totalCount"))
    }

    // ── POST search — combined filters ────────────────────────────────────────

    @Test
    @Order(21)
    fun `POST search combining query, storeType, and services`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(
                JsonObject()
                    .put("query", "New York")
                    .put("storeTypes", JsonArray().add("branch"))
                    .put("services", JsonArray().add("wire_transfer")),
            )
            .coAwait()
        val stores = resp.bodyAsJsonObject().getJsonArray("stores")
        assertTrue(stores.size() > 0)
        for (i in 0 until stores.size()) {
            val s = stores.getJsonObject(i)
            assertEquals("branch", s.getString("storeType"))
            assertTrue("wire_transfer" in s.getJsonArray("services").map { it.toString() })
        }
    }

    @Test
    @Order(22)
    fun `POST search response always includes totalCount matching stores array size`() = runBlocking {
        val resp = client.post("/api/store-locator/search")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(JsonObject().put("storeTypes", JsonArray().add("branch")))
            .coAwait()
        val body = resp.bodyAsJsonObject()
        assertEquals(body.getInteger("totalCount"), body.getJsonArray("stores").size())
    }
}
