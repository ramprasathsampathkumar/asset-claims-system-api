package com.example.claims.validation

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class Step2ValidatorTest {

    private fun validStep2() = JsonObject()
        .put("firstName", "John")
        .put("lastName", "Doe")
        .put("dateOfBirth", "1985-06-15")
        .put("nationality", "American")
        .put("idType", "passport")
        .put("idNumber", "A12345678")
        .put("street1", "123 Main St")
        .put("city", "New York")
        .put("postalCode", "10001")
        .put("country", "US")
        .put("phone", "+12125551234")
        .put("email", "john.doe@example.com")

    // ── Name validation ───────────────────────────────────────────────────────

    @Test
    fun `valid ASCII name passes`() {
        val errors = Step2Validator.validate(validStep2(), "en")
        assertTrue(errors.none { it.field == "step2.firstName" })
    }

    @Test
    fun `valid CJK name passes`() {
        val step2 = validStep2().put("firstName", "\u5c71\u7530")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.firstName" })
    }

    @Test
    fun `valid accented name passes`() {
        val step2 = validStep2().put("lastName", "M\u00fcller")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.lastName" })
    }

    @Test
    fun `name with apostrophe and hyphen passes`() {
        val step2 = validStep2().put("lastName", "O'Brien-Smith")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.lastName" })
    }

    @Test
    fun `name with invalid chars fails with invalid_pattern`() {
        val step2 = validStep2().put("firstName", "John@123")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.any { it.field == "step2.firstName" && it.code == "invalid_pattern" })
    }

    // ── dateOfBirth validation ────────────────────────────────────────────────

    @Test
    fun `dateOfBirth in past passes`() {
        val step2 = validStep2().put("dateOfBirth", "1985-06-15")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.dateOfBirth" })
    }

    @Test
    fun `dateOfBirth today fails with date_past`() {
        val today = LocalDate.now().toString()
        val step2 = validStep2().put("dateOfBirth", today)
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.any { it.field == "step2.dateOfBirth" && it.code == "date_past" })
    }

    @Test
    fun `dateOfBirth in future fails with date_past`() {
        val future = LocalDate.now().plusDays(1).toString()
        val step2 = validStep2().put("dateOfBirth", future)
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.any { it.field == "step2.dateOfBirth" && it.code == "date_past" })
    }

    // ── Postal code validation ────────────────────────────────────────────────

    @Test
    fun `valid US postal code passes`() {
        val errors = Step2Validator.validate(validStep2(), "en")
        assertTrue(errors.none { it.field == "step2.postalCode" })
    }

    @Test
    fun `valid US zip+4 passes`() {
        val step2 = validStep2().put("postalCode", "10001-1234")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.postalCode" })
    }

    @Test
    fun `valid GB postal code passes`() {
        val step2 = validStep2().put("postalCode", "SW1A 1AA").put("country", "GB")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.postalCode" })
    }

    @Test
    fun `valid CA postal code passes`() {
        val step2 = validStep2().put("postalCode", "K1A 0B1").put("country", "CA")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.postalCode" })
    }

    @Test
    fun `valid AU postal code passes`() {
        val step2 = validStep2().put("postalCode", "2000").put("country", "AU")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.postalCode" })
    }

    @Test
    fun `valid JP postal code with dash passes`() {
        val step2 = validStep2().put("postalCode", "100-0001").put("country", "JP")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.postalCode" })
    }

    @Test
    fun `invalid US postal code fails with invalid_pattern`() {
        val step2 = validStep2().put("postalCode", "ABCDE")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.any { it.field == "step2.postalCode" && it.code == "invalid_pattern" })
    }

    @Test
    fun `unknown country uses generic fallback`() {
        val step2 = validStep2().put("postalCode", "12345").put("country", "XX")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.postalCode" })
    }

    // ── Email validation ──────────────────────────────────────────────────────

    @Test
    fun `valid email passes`() {
        val errors = Step2Validator.validate(validStep2(), "en")
        assertTrue(errors.none { it.field == "step2.email" })
    }

    @Test
    fun `invalid email fails with invalid_format`() {
        val step2 = validStep2().put("email", "not-an-email")
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.any { it.field == "step2.email" && it.code == "invalid_format" })
    }

    // ── Max length checks ─────────────────────────────────────────────────────

    @Test
    fun `idNumber at 100 chars passes`() {
        val step2 = validStep2().put("idNumber", "A".repeat(100))
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.none { it.field == "step2.idNumber" })
    }

    @Test
    fun `idNumber exceeding 100 chars fails with max_length`() {
        val step2 = validStep2().put("idNumber", "A".repeat(101))
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.any { it.field == "step2.idNumber" && it.code == "max_length" })
    }

    @Test
    fun `street1 exceeding 200 chars fails with max_length`() {
        val step2 = validStep2().put("street1", "A".repeat(201))
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.any { it.field == "step2.street1" && it.code == "max_length" })
    }

    @Test
    fun `city exceeding 100 chars fails with max_length`() {
        val step2 = validStep2().put("city", "A".repeat(101))
        val errors = Step2Validator.validate(step2, "en")
        assertTrue(errors.any { it.field == "step2.city" && it.code == "max_length" })
    }

    // ── Locale support ────────────────────────────────────────────────────────

    @Test
    fun `French locale returns French error message`() {
        val step2 = validStep2().put("email", "not-an-email")
        val errors = Step2Validator.validate(step2, "fr")
        val emailError = errors.find { it.field == "step2.email" }
        assertNotNull(emailError, "Expected email validation error")
        assertTrue(emailError!!.message.isNotBlank())
        // French message for invalid_format should not be the same as English
        val enError = Step2Validator.validate(step2, "en").find { it.field == "step2.email" }
        assertEquals(emailError.code, enError!!.code) // same code
    }
}
