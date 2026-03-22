package com.example.claims.repository

import com.example.claims.model.DayHours
import com.example.claims.model.Store
import com.example.claims.model.StoreAddress
import com.example.claims.model.StoreHours
import com.example.claims.model.StoreWithDistance
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object StoreRepository {

    val all: List<Store> = buildList {
        // ── New York ──────────────────────────────────────────────────────────
        add(
            Store(
                id = "nyc-001",
                name = "Manhattan Financial District Branch",
                storeType = "branch",
                address = StoreAddress("100 Broadway", null, "New York", "NY", "10005", "US"),
                latitude = 40.7074,
                longitude = -74.0113,
                phone = "+1-212-555-0101",
                hours = weekdayHours("09:00", "17:00", sat = "10:00" to "14:00", sunClosed = true),
                services = listOf("notary", "safe_deposit", "wire_transfer", "financial_advisor"),
                timezone = "America/New_York",
            ),
        )
        add(
            Store(
                id = "nyc-002",
                name = "Midtown Manhattan Branch",
                storeType = "branch",
                address = StoreAddress("350 Fifth Avenue", "Suite 200", "New York", "NY", "10118", "US"),
                latitude = 40.7484,
                longitude = -73.9967,
                phone = "+1-212-555-0202",
                hours = weekdayHours("08:30", "18:00", sat = "09:00" to "15:00", sunClosed = true),
                services = listOf("notary", "currency_exchange", "wire_transfer", "financial_advisor", "document_printing"),
                timezone = "America/New_York",
            ),
        )
        add(
            Store(
                id = "nyc-003",
                name = "Brooklyn Downtown Service Center",
                storeType = "service_center",
                address = StoreAddress("1 MetroTech Center", null, "Brooklyn", "NY", "11201", "US"),
                latitude = 40.6928,
                longitude = -73.9903,
                phone = "+1-718-555-0303",
                hours = weekdayHours("09:00", "17:00", sat = "10:00" to "13:00", sunClosed = true),
                services = listOf("notary", "document_printing", "wire_transfer"),
                timezone = "America/New_York",
            ),
        )
        add(
            Store(
                id = "nyc-004",
                name = "Upper West Side ATM",
                storeType = "atm",
                address = StoreAddress("2400 Broadway", null, "New York", "NY", "10024", "US"),
                latitude = 40.7870,
                longitude = -73.9796,
                phone = "+1-212-555-0404",
                hours = allDayHours(),
                services = emptyList(),
                timezone = "America/New_York",
            ),
        )
        add(
            Store(
                id = "nyc-005",
                name = "Flushing Branch",
                storeType = "branch",
                address = StoreAddress("136-20 Roosevelt Ave", null, "Flushing", "NY", "11354", "US"),
                latitude = 40.7596,
                longitude = -73.8330,
                phone = "+1-718-555-0505",
                hours = weekdayHours("09:00", "17:00", sat = "09:00" to "14:00", sunClosed = true),
                services = listOf("notary", "currency_exchange", "wire_transfer", "document_printing"),
                timezone = "America/New_York",
            ),
        )

        // ── Chicago ───────────────────────────────────────────────────────────
        add(
            Store(
                id = "chi-001",
                name = "The Loop Branch",
                storeType = "branch",
                address = StoreAddress("77 W Wacker Dr", null, "Chicago", "IL", "60601", "US"),
                latitude = 41.8827,
                longitude = -87.6364,
                phone = "+1-312-555-0601",
                hours = weekdayHours("08:30", "17:30", sat = "09:00" to "13:00", sunClosed = true),
                services = listOf("notary", "safe_deposit", "wire_transfer", "financial_advisor", "currency_exchange"),
                timezone = "America/Chicago",
            ),
        )
        add(
            Store(
                id = "chi-002",
                name = "Lincoln Park Service Center",
                storeType = "service_center",
                address = StoreAddress("2001 N Clark St", null, "Chicago", "IL", "60614", "US"),
                latitude = 41.9214,
                longitude = -87.6363,
                phone = "+1-312-555-0702",
                hours = weekdayHours("09:00", "17:00", sat = "10:00" to "14:00", sunClosed = true),
                services = listOf("notary", "document_printing", "safe_deposit"),
                timezone = "America/Chicago",
            ),
        )
        add(
            Store(
                id = "chi-003",
                name = "River North ATM",
                storeType = "atm",
                address = StoreAddress("430 N Michigan Ave", null, "Chicago", "IL", "60611", "US"),
                latitude = 41.8919,
                longitude = -87.6244,
                phone = "+1-312-555-0803",
                hours = allDayHours(),
                services = emptyList(),
                timezone = "America/Chicago",
            ),
        )
        add(
            Store(
                id = "chi-004",
                name = "Wicker Park Branch",
                storeType = "branch",
                address = StoreAddress("1500 N Milwaukee Ave", null, "Chicago", "IL", "60622", "US"),
                latitude = 41.9083,
                longitude = -87.6773,
                phone = "+1-773-555-0904",
                hours = weekdayHours("09:00", "17:00", sat = "09:00" to "13:00", sunClosed = true),
                services = listOf("notary", "wire_transfer", "document_printing"),
                timezone = "America/Chicago",
            ),
        )

        // ── Los Angeles ───────────────────────────────────────────────────────
        add(
            Store(
                id = "lax-001",
                name = "Downtown Los Angeles Branch",
                storeType = "branch",
                address = StoreAddress("633 W 5th St", null, "Los Angeles", "CA", "90071", "US"),
                latitude = 34.0515,
                longitude = -118.2575,
                phone = "+1-213-555-1001",
                hours = weekdayHours("09:00", "17:00", sat = "10:00" to "14:00", sunClosed = true),
                services = listOf("notary", "safe_deposit", "wire_transfer", "financial_advisor", "currency_exchange"),
                timezone = "America/Los_Angeles",
            ),
        )
        add(
            Store(
                id = "lax-002",
                name = "Beverly Hills Branch",
                storeType = "branch",
                address = StoreAddress("9400 Wilshire Blvd", null, "Beverly Hills", "CA", "90212", "US"),
                latitude = 34.0736,
                longitude = -118.4004,
                phone = "+1-310-555-1102",
                hours = weekdayHours("09:00", "17:00", sat = "10:00" to "15:00", sunClosed = true),
                services = listOf("notary", "safe_deposit", "wire_transfer", "financial_advisor", "currency_exchange", "document_printing"),
                timezone = "America/Los_Angeles",
            ),
        )
        add(
            Store(
                id = "lax-003",
                name = "Santa Monica Service Center",
                storeType = "service_center",
                address = StoreAddress("1454 4th St", null, "Santa Monica", "CA", "90401", "US"),
                latitude = 34.0195,
                longitude = -118.4912,
                phone = "+1-310-555-1203",
                hours = weekdayHours("09:00", "17:00", sat = "10:00" to "13:00", sunClosed = true),
                services = listOf("notary", "document_printing", "wire_transfer"),
                timezone = "America/Los_Angeles",
            ),
        )
        add(
            Store(
                id = "lax-004",
                name = "Hollywood ATM",
                storeType = "atm",
                address = StoreAddress("6801 Hollywood Blvd", null, "Hollywood", "CA", "90028", "US"),
                latitude = 34.1016,
                longitude = -118.3396,
                phone = "+1-323-555-1304",
                hours = allDayHours(),
                services = emptyList(),
                timezone = "America/Los_Angeles",
            ),
        )

        // ── Houston ───────────────────────────────────────────────────────────
        add(
            Store(
                id = "hou-001",
                name = "Houston Downtown Branch",
                storeType = "branch",
                address = StoreAddress("600 Travis St", null, "Houston", "TX", "77002", "US"),
                latitude = 29.7604,
                longitude = -95.3698,
                phone = "+1-713-555-1401",
                hours = weekdayHours("09:00", "17:00", sat = "10:00" to "14:00", sunClosed = true),
                services = listOf("notary", "safe_deposit", "wire_transfer", "financial_advisor"),
                timezone = "America/Chicago",
            ),
        )
        add(
            Store(
                id = "hou-002",
                name = "Houston Midtown Service Center",
                storeType = "service_center",
                address = StoreAddress("3401 Louisiana St", null, "Houston", "TX", "77002", "US"),
                latitude = 29.7396,
                longitude = -95.3838,
                phone = "+1-713-555-1502",
                hours = weekdayHours("09:00", "17:00", sat = "10:00" to "13:00", sunClosed = true),
                services = listOf("notary", "document_printing", "wire_transfer"),
                timezone = "America/Chicago",
            ),
        )
        add(
            Store(
                id = "hou-003",
                name = "Houston Medical Center Branch",
                storeType = "branch",
                address = StoreAddress("6650 Main St", null, "Houston", "TX", "77030", "US"),
                latitude = 29.7099,
                longitude = -95.3987,
                phone = "+1-713-555-1603",
                hours = weekdayHours("08:00", "18:00", sat = "09:00" to "15:00", sunClosed = false),
                services = listOf("notary", "safe_deposit", "wire_transfer", "currency_exchange", "document_printing"),
                timezone = "America/Chicago",
            ),
        )
    }

    private val byId: Map<String, Store> = all.associateBy { it.id }

    fun findById(id: String): Store? = byId[id]

    fun search(
        query: String?,
        latitude: Double?,
        longitude: Double?,
        radiusKm: Double?,
        storeTypes: List<String>?,
        services: List<String>?,
        openNow: Boolean?,
    ): List<StoreWithDistance> {
        val now = ZonedDateTime.now()

        return all
            .asSequence()
            .filter { store ->
                // Text search
                if (!query.isNullOrBlank()) {
                    val q = query.lowercase()
                    val haystack = listOf(
                        store.name,
                        store.address.city,
                        store.address.state,
                        store.address.postalCode,
                        store.address.line1,
                    ).joinToString(" ").lowercase()
                    if (!haystack.contains(q)) return@filter false
                }
                true
            }
            .filter { store ->
                // Store type filter (AND — caller passes desired types, store must match one)
                if (!storeTypes.isNullOrEmpty()) store.storeType in storeTypes else true
            }
            .filter { store ->
                // Services filter (AND — store must offer ALL selected services)
                if (!services.isNullOrEmpty()) store.services.containsAll(services) else true
            }
            .map { store ->
                val dist = if (latitude != null && longitude != null) {
                    haversineKm(latitude, longitude, store.latitude, store.longitude)
                } else {
                    0.0
                }
                store to dist
            }
            .filter { (_, dist) ->
                // Radius filter — only when coordinates are provided
                if (latitude != null && longitude != null && radiusKm != null) {
                    dist <= radiusKm
                } else {
                    true
                }
            }
            .map { (store, dist) ->
                store.withDistance(dist, isOpenNow(store, now))
            }
            .filter { storeWithDistance ->
                // openNow filter
                if (openNow == true) storeWithDistance.openNow else true
            }
            .sortedWith(
                if (latitude != null && longitude != null) {
                    compareBy { it.distance }
                } else {
                    compareBy { it.name }
                },
            )
            .toList()
    }

    fun withDistance(store: Store, latitude: Double?, longitude: Double?): StoreWithDistance {
        val dist = if (latitude != null && longitude != null) {
            haversineKm(latitude, longitude, store.latitude, store.longitude)
        } else {
            0.0
        }
        return store.withDistance(dist, isOpenNow(store, ZonedDateTime.now()))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun Store.withDistance(distance: Double, openNow: Boolean) =
        StoreWithDistance(
            id = id,
            name = name,
            storeType = storeType,
            address = address,
            latitude = latitude,
            longitude = longitude,
            phone = phone,
            hours = hours,
            services = services,
            distance = distance,
            openNow = openNow,
        )

    private fun isOpenNow(store: Store, now: ZonedDateTime): Boolean {
        val storeZone = runCatching { ZoneId.of(store.timezone) }.getOrDefault(ZoneId.systemDefault())
        val local = now.withZoneSameInstant(storeZone)
        val day = local.dayOfWeek
        val time = local.toLocalTime()

        val dayHours = when (day) {
            java.time.DayOfWeek.MONDAY -> store.hours.monday
            java.time.DayOfWeek.TUESDAY -> store.hours.tuesday
            java.time.DayOfWeek.WEDNESDAY -> store.hours.wednesday
            java.time.DayOfWeek.THURSDAY -> store.hours.thursday
            java.time.DayOfWeek.FRIDAY -> store.hours.friday
            java.time.DayOfWeek.SATURDAY -> store.hours.saturday
            java.time.DayOfWeek.SUNDAY -> store.hours.sunday
        }

        if (dayHours.closed) return false
        val open = LocalTime.parse(dayHours.open)
        val close = LocalTime.parse(dayHours.close)
        return !time.isBefore(open) && time.isBefore(close)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // ── hour builders ────────────────────────────────────────────────────────

    private fun weekdayHours(
        open: String,
        close: String,
        sat: Pair<String, String>? = null,
        sunClosed: Boolean = true,
    ): StoreHours {
        val weekday = DayHours(open, close)
        val saturday = if (sat != null) DayHours(sat.first, sat.second) else DayHours("00:00", "00:00", closed = true)
        val sunday = if (sunClosed) DayHours("00:00", "00:00", closed = true) else DayHours(open, close)
        return StoreHours(weekday, weekday, weekday, weekday, weekday, saturday, sunday)
    }

    private fun allDayHours(): StoreHours {
        val h = DayHours("00:00", "23:59")
        return StoreHours(h, h, h, h, h, h, h)
    }
}
