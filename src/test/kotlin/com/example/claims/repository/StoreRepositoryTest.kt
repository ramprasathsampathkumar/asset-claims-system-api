package com.example.claims.repository

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StoreRepositoryTest {

    // ── seed data ────────────────────────────────────────────────────────────

    @Test
    fun `all returns 16 stores`() {
        assertEquals(16, StoreRepository.all.size)
    }

    @Test
    fun `store types are valid`() {
        val valid = setOf("branch", "service_center", "atm")
        StoreRepository.all.forEach { store ->
            assertTrue(store.storeType in valid, "Invalid storeType '${store.storeType}' for store ${store.id}")
        }
    }

    @Test
    fun `all stores have non-blank required fields`() {
        StoreRepository.all.forEach { store ->
            assertTrue(store.id.isNotBlank(), "Blank id")
            assertTrue(store.name.isNotBlank(), "Blank name for ${store.id}")
            assertTrue(store.address.city.isNotBlank(), "Blank city for ${store.id}")
            assertTrue(store.address.state.isNotBlank(), "Blank state for ${store.id}")
            assertTrue(store.timezone.isNotBlank(), "Blank timezone for ${store.id}")
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = StoreRepository.all.map { it.id }
        assertEquals(ids.distinct().size, ids.size, "Duplicate store IDs found")
    }

    // ── findById ─────────────────────────────────────────────────────────────

    @Test
    fun `findById returns store for valid id`() {
        val store = StoreRepository.findById("nyc-001")
        assertNotNull(store)
        assertEquals("Manhattan Financial District Branch", store!!.name)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(StoreRepository.findById("does-not-exist"))
    }

    // ── search — no filters ───────────────────────────────────────────────────

    @Test
    fun `search with no filters returns all 16 stores`() {
        val results = StoreRepository.search(null, null, null, null, null, null, null)
        assertEquals(16, results.size)
    }

    @Test
    fun `search with no coords returns stores sorted by name`() {
        val results = StoreRepository.search(null, null, null, null, null, null, null)
        val names = results.map { it.name }
        assertEquals(names.sorted(), names)
    }

    // ── search — text query ───────────────────────────────────────────────────

    @Test
    fun `search by city name returns matching stores`() {
        val results = StoreRepository.search("Chicago", null, null, null, null, null, null)
        assertEquals(4, results.size)
        results.forEach { assertTrue(it.address.city == "Chicago") }
    }

    @Test
    fun `search by store name substring is case-insensitive`() {
        val results = StoreRepository.search("beverly", null, null, null, null, null, null)
        assertEquals(1, results.size)
        assertEquals("lax-002", results[0].id)
    }

    @Test
    fun `search by postal code returns matching store`() {
        val results = StoreRepository.search("10005", null, null, null, null, null, null)
        assertEquals(1, results.size)
        assertEquals("nyc-001", results[0].id)
    }

    @Test
    fun `search with query that matches nothing returns empty list`() {
        val results = StoreRepository.search("zzznomatch", null, null, null, null, null, null)
        assertTrue(results.isEmpty())
    }

    // ── search — storeType filter ─────────────────────────────────────────────

    @Test
    fun `filter by atm returns only atm stores`() {
        val results = StoreRepository.search(null, null, null, null, listOf("atm"), null, null)
        assertTrue(results.isNotEmpty())
        results.forEach { assertEquals("atm", it.storeType) }
    }

    @Test
    fun `filter by branch returns only branch stores`() {
        val results = StoreRepository.search(null, null, null, null, listOf("branch"), null, null)
        assertTrue(results.isNotEmpty())
        results.forEach { assertEquals("branch", it.storeType) }
    }

    @Test
    fun `filter by branch and service_center excludes atm`() {
        val results = StoreRepository.search(null, null, null, null, listOf("branch", "service_center"), null, null)
        results.forEach { assertNotEquals("atm", it.storeType) }
    }

    // ── search — services filter (AND logic) ──────────────────────────────────

    @Test
    fun `filter by single service returns stores offering that service`() {
        val results = StoreRepository.search(null, null, null, null, null, listOf("financial_advisor"), null)
        assertTrue(results.isNotEmpty())
        results.forEach { assertTrue("financial_advisor" in it.services, "Store ${it.id} missing financial_advisor") }
    }

    @Test
    fun `filter by multiple services uses AND logic`() {
        val results = StoreRepository.search(
            null, null, null, null, null,
            listOf("notary", "wire_transfer", "financial_advisor"),
            null,
        )
        assertTrue(results.isNotEmpty())
        results.forEach { store ->
            assertTrue("notary" in store.services)
            assertTrue("wire_transfer" in store.services)
            assertTrue("financial_advisor" in store.services)
        }
    }

    @Test
    fun `filter by service not offered by any store returns empty`() {
        val results = StoreRepository.search(null, null, null, null, null, listOf("nonexistent_service"), null)
        assertTrue(results.isEmpty())
    }

    // ── search — distance and radius ─────────────────────────────────────────

    @Test
    fun `search with coordinates sorts results by distance ascending`() {
        // Times Square coordinates — NYC stores should come first
        val results = StoreRepository.search(null, 40.7580, -73.9855, null, null, null, null)
        // First result should be closer than last
        assertTrue(results.first().distance <= results.last().distance)
    }

    @Test
    fun `search with coordinates assigns non-zero distance`() {
        val results = StoreRepository.search(null, 40.7580, -73.9855, null, null, null, null)
        results.forEach { assertTrue(it.distance >= 0.0) }
        // At least some stores should have distance > 0 (Houston stores should be far)
        assertTrue(results.any { it.distance > 100 })
    }

    @Test
    fun `search with tight radius filters distant stores`() {
        // From downtown NYC, 10 km radius — should only return NY-area stores
        val results = StoreRepository.search(null, 40.7128, -74.0060, 10.0, null, null, null)
        assertTrue(results.isNotEmpty())
        results.forEach { assertTrue(it.distance <= 10.0, "Store ${it.id} is ${it.distance} km away, expected <= 10") }
    }

    @Test
    fun `search without coordinates ignores radius filter`() {
        // radiusKm with no lat/lng should return all stores
        val results = StoreRepository.search(null, null, null, 10.0, null, null, null)
        assertEquals(16, results.size)
    }

    @Test
    fun `haversine distance between NYC and Chicago is approximately correct`() {
        // NYC (~40.71, -74.01) to Chicago (~41.88, -87.64) ≈ 1144 km
        val results = StoreRepository.search(null, 40.7128, -74.0060, null, listOf("branch"), null, null)
        val chicago = results.firstOrNull { it.address.city == "Chicago" }
        assertNotNull(chicago)
        // Accept range 1100–1250 km
        assertTrue(chicago!!.distance in 1100.0..1250.0, "NYC-Chicago distance ${chicago.distance} km out of expected range")
    }

    // ── withDistance (get-all / get-by-id) ───────────────────────────────────

    @Test
    fun `withDistance returns zero distance when no coords provided`() {
        val store = StoreRepository.findById("nyc-001")!!
        val result = StoreRepository.withDistance(store, null, null)
        assertEquals(0.0, result.distance)
    }

    @Test
    fun `withDistance computes distance when coords provided`() {
        val store = StoreRepository.findById("nyc-001")!! // Manhattan ~40.71, -74.01
        val result = StoreRepository.withDistance(store, 40.7128, -74.0060)
        assertTrue(result.distance < 5.0, "Expected < 5 km within Manhattan, got ${result.distance}")
    }

    // ── openNow ───────────────────────────────────────────────────────────────

    @Test
    fun `ATM stores are always open`() {
        // ATM hours are 00:00-23:59 every day — openNow should always be true
        val atmStores = StoreRepository.all.filter { it.storeType == "atm" }
        assertTrue(atmStores.isNotEmpty())
        atmStores.forEach { store ->
            val result = StoreRepository.withDistance(store, null, null)
            assertTrue(result.openNow, "ATM ${store.id} should always be open")
        }
    }
}
