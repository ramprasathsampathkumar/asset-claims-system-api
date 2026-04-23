package com.example.claims.model

data class DayHours(
    val open: String,
    val close: String,
    val closed: Boolean = false,
)

data class StoreHours(
    val monday: DayHours,
    val tuesday: DayHours,
    val wednesday: DayHours,
    val thursday: DayHours,
    val friday: DayHours,
    val saturday: DayHours,
    val sunday: DayHours,
)

data class StoreAddress(
    val line1: String,
    val line2: String? = null,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String,
)

data class Store(
    val id: String,
    val name: String,
    val storeType: String,
    val address: StoreAddress,
    val latitude: Double,
    val longitude: Double,
    val phone: String,
    val hours: StoreHours,
    val services: List<String>,
    val timezone: String,
)

data class StoreWithDistance(
    val id: String,
    val name: String,
    val storeType: String,
    val address: StoreAddress,
    val latitude: Double,
    val longitude: Double,
    val phone: String,
    val hours: StoreHours,
    val services: List<String>,
    val distance: Double,
    val openNow: Boolean,
)
