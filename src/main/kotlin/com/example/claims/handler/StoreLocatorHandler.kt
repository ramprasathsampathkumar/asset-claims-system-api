package com.example.claims.handler

import com.example.claims.repository.StoreRepository
import com.example.claims.service.GeocodingService
import io.vertx.core.json.Json
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class StoreLocatorHandler(
    private val scope: CoroutineScope,
    private val geocodingService: GeocodingService,
) {

    private val logger = LoggerFactory.getLogger(StoreLocatorHandler::class.java)

    private val config = mapOf(
        "styleUrl" to "https://tiles.openfreemap.org/styles/liberty",
        "defaultCenter" to mapOf("lat" to 39.8283, "lng" to -98.5795),
        "defaultZoom" to 4,
        "attribution" to "© OpenFreeMap © OpenStreetMap contributors",
    )

    private val filters = mapOf(
        "storeTypes" to listOf(
            mapOf("value" to "branch", "label" to "Branch"),
            mapOf("value" to "service_center", "label" to "Service Center"),
            mapOf("value" to "atm", "label" to "ATM"),
        ),
        "services" to listOf(
            mapOf("value" to "notary", "label" to "Notary Services"),
            mapOf("value" to "safe_deposit", "label" to "Safe Deposit Box"),
            mapOf("value" to "currency_exchange", "label" to "Currency Exchange"),
            mapOf("value" to "wire_transfer", "label" to "Wire Transfer"),
            mapOf("value" to "financial_advisor", "label" to "Financial Advisor"),
            mapOf("value" to "document_printing", "label" to "Document Printing"),
        ),
        "radiusOptions" to listOf(
            mapOf("value" to 10, "label" to "10 km"),
            mapOf("value" to 25, "label" to "25 km"),
            mapOf("value" to 50, "label" to "50 km"),
            mapOf("value" to 100, "label" to "100 km"),
        ),
    )

    fun getConfig(ctx: RoutingContext) {
        ctx.json(config)
    }

    fun getFilters(ctx: RoutingContext) {
        ctx.json(filters)
    }

    fun getAllStores(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val stores = StoreRepository.all.map { store ->
                    StoreRepository.withDistance(store, null, null)
                }
                ctx.json(stores)
            } catch (e: Exception) {
                logger.error("Error fetching all stores", e)
                ctx.fail(500, e)
            }
        }
    }

    fun getStoreById(ctx: RoutingContext) {
        val storeId = ctx.pathParam("storeId")
        val store = StoreRepository.findById(storeId)
        if (store == null) {
            ctx.response()
                .setStatusCode(404)
                .putHeader("Content-Type", "application/json")
                .end("""{"message":"Store not found."}""")
            return
        }
        ctx.json(StoreRepository.withDistance(store, null, null))
    }

    fun search(ctx: RoutingContext) {
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val body = ctx.body().asJsonObject()

                val query = body?.getString("query")
                val clientLat = body?.getDouble("latitude")
                val clientLng = body?.getDouble("longitude")
                val radiusKm = body?.getDouble("radiusKm")
                val storeTypes = body?.getJsonArray("storeTypes")?.map { it.toString() }
                val services = body?.getJsonArray("services")?.map { it.toString() }
                val openNow = body?.getBoolean("openNow")

                // ── Coordinate resolution ────────────────────────────────────
                //
                // Three cases:
                //  1. Client sent lat/lng (GPS "Use My Location") → use directly, still apply text filter
                //  2. query + explicit radiusKm, no lat/lng → geocode query; use that radius
                //  3. query only, no lat/lng, no radius → geocode then try 25 → 50 → 100 km;
                //     fall back to plain text search if all radii return 0 results
                val stores = if (clientLat != null && clientLng != null) {
                    // Case 1 — GPS coordinates supplied by the client
                    StoreRepository.search(
                        query = query,
                        latitude = clientLat,
                        longitude = clientLng,
                        radiusKm = radiusKm,
                        storeTypes = storeTypes,
                        services = services,
                        openNow = openNow,
                    )
                } else if (!query.isNullOrBlank()) {
                    // Cases 2 & 3 — geocode the query, then search spatially
                    logger.debug("Geocoding query='{}'", query)
                    val coords = geocodingService.geocode(query)

                    if (coords != null) {
                        logger.debug("Resolved '{}' → lat={} lng={} ({})", query, coords.lat, coords.lng, coords.displayName)

                        if (radiusKm != null) {
                            // Case 2 — explicit radius provided; use it directly
                            StoreRepository.search(
                                query = null,
                                latitude = coords.lat,
                                longitude = coords.lng,
                                radiusKm = radiusKm,
                                storeTypes = storeTypes,
                                services = services,
                                openNow = openNow,
                            )
                        } else {
                            // Case 3 — no radius; try 25 → 50 → 100 km, then text fallback
                            val expandingRadii = listOf(25.0, 50.0, 100.0)
                            var result = emptyList<com.example.claims.model.StoreWithDistance>()
                            for (r in expandingRadii) {
                                result = StoreRepository.search(
                                    query = null,
                                    latitude = coords.lat,
                                    longitude = coords.lng,
                                    radiusKm = r,
                                    storeTypes = storeTypes,
                                    services = services,
                                    openNow = openNow,
                                )
                                if (result.isNotEmpty()) {
                                    logger.debug("Found {} stores within {} km of '{}'", result.size, r, query)
                                    break
                                }
                                logger.debug("No stores within {} km of '{}', expanding radius", r, query)
                            }
                            if (result.isEmpty()) {
                                // All radii exhausted — fall back to plain text match
                                logger.warn("No stores found within 100 km of '{}', falling back to text search", query)
                                StoreRepository.search(
                                    query = query,
                                    latitude = null,
                                    longitude = null,
                                    radiusKm = null,
                                    storeTypes = storeTypes,
                                    services = services,
                                    openNow = openNow,
                                )
                            } else {
                                result
                            }
                        }
                    } else {
                        // Geocoding returned nothing — fall back to text-only search
                        logger.warn("Geocoding returned no result for '{}', falling back to text search", query)
                        StoreRepository.search(
                            query = query,
                            latitude = null,
                            longitude = null,
                            radiusKm = null,
                            storeTypes = storeTypes,
                            services = services,
                            openNow = openNow,
                        )
                    }
                } else {
                    // No query, no coords — return everything (with optional storeType/services/openNow filters)
                    StoreRepository.search(
                        query = null,
                        latitude = null,
                        longitude = null,
                        radiusKm = null,
                        storeTypes = storeTypes,
                        services = services,
                        openNow = openNow,
                    )
                }

                ctx.json(mapOf("stores" to stores, "totalCount" to stores.size))
            } catch (e: Exception) {
                logger.error("Error searching stores", e)
                ctx.fail(500, e)
            }
        }
    }

    private fun RoutingContext.json(value: Any) {
        response()
            .putHeader("Content-Type", "application/json")
            .end(Json.encode(value))
    }
}
