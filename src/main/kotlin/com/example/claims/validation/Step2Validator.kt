package com.example.claims.validation

import io.vertx.core.json.JsonObject
import java.time.LocalDate

object Step2Validator {

    // \p{L} = any Unicode letter, \p{M} = Unicode combining mark
    private val nameRegex = Regex("""^[\p{L}\p{M}\s'\-.]{1,100}$""")
    private val emailRegex = Regex("""^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$""")

    private val postalPatterns: Map<String, Regex> = mapOf(
        "US" to Regex("""^\d{5}(-\d{4})?$"""),
        "GB" to Regex("""^[A-Z]{1,2}\d[A-Z\d]? ?\d[A-Z]{2}$"""),
        "CA" to Regex("""^[A-Z]\d[A-Z] ?\d[A-Z]\d$"""),
        "AU" to Regex("""^\d{4}$"""),
        "DE" to Regex("""^\d{5}$"""),
        "FR" to Regex("""^\d{5}$"""),
        "JP" to Regex("""^\d{3}-?\d{4}$"""),
        "CN" to Regex("""^\d{6}$"""),
        "IN" to Regex("""^\d{6}$"""),
        "BR" to Regex("""^\d{5}-?\d{3}$"""),
    )
    private val genericPostalRegex = Regex("""^.{1,20}$""")

    fun validate(step2: JsonObject, locale: String): List<FieldError> {
        val errors = mutableListOf<FieldError>()

        // Name fields: Unicode-aware letter/mark/space/punctuation
        for (field in listOf("firstName", "lastName", "nationality")) {
            val value = step2.getString(field) ?: continue
            if (!nameRegex.matches(value)) {
                errors += FieldError("step2.$field", "invalid_pattern", Messages.get(locale, "invalid_pattern"))
            }
        }

        // Email
        val email = step2.getString("email")
        if (email != null && !emailRegex.matches(email)) {
            errors += FieldError("step2.email", "invalid_format", Messages.get(locale, "invalid_format"))
        }

        // dateOfBirth must be strictly in the past
        val dob = step2.getString("dateOfBirth")
        if (dob != null) {
            try {
                val date = LocalDate.parse(dob)
                if (!date.isBefore(LocalDate.now())) {
                    errors += FieldError("step2.dateOfBirth", "date_past", Messages.get(locale, "date_past"))
                }
            } catch (_: Exception) {
                errors += FieldError("step2.dateOfBirth", "invalid_format", Messages.get(locale, "invalid_format"))
            }
        }

        // Postal code: country-keyed patterns, generic fallback
        val country = step2.getString("country")
        val postalCode = step2.getString("postalCode")
        if (postalCode != null) {
            val pattern = postalPatterns[country] ?: genericPostalRegex
            if (!pattern.matches(postalCode)) {
                errors += FieldError("step2.postalCode", "invalid_pattern", Messages.get(locale, "invalid_pattern"))
            }
        }

        // Max length checks
        val idNumber = step2.getString("idNumber")
        if (idNumber != null && idNumber.length > 100) {
            errors += FieldError("step2.idNumber", "max_length", Messages.get(locale, "max_length"))
        }

        val street1 = step2.getString("street1")
        if (street1 != null && street1.length > 200) {
            errors += FieldError("step2.street1", "max_length", Messages.get(locale, "max_length"))
        }

        val city = step2.getString("city")
        if (city != null && city.length > 100) {
            errors += FieldError("step2.city", "max_length", Messages.get(locale, "max_length"))
        }

        return errors
    }
}
