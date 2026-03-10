package com.example.claims.validation

import io.vertx.core.json.JsonObject
import java.time.LocalDate

object InquiryValidator {

    // Same pattern used in Step2Validator for name fields
    private val nameRegex = Regex("""^[\p{L}\p{M}\s'\-.]{1,100}$""")
    private val referenceRegex = Regex("""^ACL-[A-Z0-9]+-[A-Z0-9]{4}$""")
    private val dobRegex = Regex("""^\d{4}-\d{2}-\d{2}$""")

    fun validate(body: JsonObject, locale: String): List<FieldError> {
        val errors = mutableListOf<FieldError>()

        // referenceNumber — required, must match ACL format
        val ref = body.getString("referenceNumber")
        if (ref.isNullOrBlank()) {
            errors += FieldError("referenceNumber", "required", Messages.get(locale, "required"))
        } else if (!referenceRegex.matches(ref)) {
            errors += FieldError("referenceNumber", "invalid_pattern", Messages.get(locale, "invalid_pattern"))
        }

        // lastName — required, Unicode name characters
        val lastName = body.getString("lastName")
        if (lastName.isNullOrBlank()) {
            errors += FieldError("lastName", "required", Messages.get(locale, "required"))
        } else if (!nameRegex.matches(lastName)) {
            errors += FieldError("lastName", "invalid_pattern", Messages.get(locale, "invalid_pattern"))
        }

        // dateOfBirth — optional, extra identity verification; must be valid past date if provided
        val dob = body.getString("dateOfBirth")
        if (dob != null) {
            if (!dobRegex.matches(dob)) {
                errors += FieldError("dateOfBirth", "invalid_format", Messages.get(locale, "invalid_format"))
            } else {
                try {
                    val parsed = LocalDate.parse(dob)
                    if (!parsed.isBefore(LocalDate.now())) {
                        errors += FieldError("dateOfBirth", "date_past", Messages.get(locale, "date_past"))
                    }
                } catch (_: Exception) {
                    errors += FieldError("dateOfBirth", "invalid_format", Messages.get(locale, "invalid_format"))
                }
            }
        }

        return errors
    }
}
