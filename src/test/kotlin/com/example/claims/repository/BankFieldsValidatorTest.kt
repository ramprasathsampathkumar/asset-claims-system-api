package com.example.claims.repository

import com.example.claims.validation.BankFieldsValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class BankFieldsValidatorTest {

    companion object {
        @JvmStatic
        fun validBankFields(): Stream<Arguments> = Stream.of(
            Arguments.of("USD", mapOf("account_number" to "123456789", "routing_number" to "021000021")),
            Arguments.of("GBP", mapOf("sort_code" to "12-34-56", "account_number" to "12345678")),
            Arguments.of("JPY", mapOf("bank_code" to "0001", "branch_code" to "123", "account_number" to "1234567")),
            Arguments.of("CAD", mapOf("institution_number" to "001", "transit_number" to "12345")),
            Arguments.of("CHF", mapOf("iban" to "CH5604835012345678009")),
            Arguments.of("AUD", mapOf("bsb" to "062000", "account_number" to "12345678")),
            Arguments.of(
                "NZD",
                mapOf("bank_code" to "01", "branch_code" to "0142", "account_number" to "0000035", "suffix" to "00"),
            ),
        )

        @JvmStatic
        fun invalidBankFields(): Stream<Arguments> = Stream.of(
            Arguments.of("USD", mapOf("account_number" to "123", "routing_number" to "0210000211"), "routing_number"), // routing too long
            Arguments.of("GBP", mapOf("sort_code" to "123456", "account_number" to "12345678"), "sort_code"), // no dashes
            Arguments.of("JPY", mapOf("bank_code" to "01", "branch_code" to "123", "account_number" to "1234567"), "bank_code"), // bank_code too short
            Arguments.of("CHF", mapOf("iban" to "DE89370400440532013000"), "iban"), // not CH prefix
        )
    }

    @ParameterizedTest(name = "valid {0} bank fields")
    @MethodSource("validBankFields")
    fun `valid bank fields pass validation`(currency: String, fields: Map<String, Any?>) {
        val result = BankFieldsValidator.validate(currency, fields, "en")
        assertTrue(result.isEmpty(), "Expected valid but got errors: $result")
    }

    @ParameterizedTest(name = "invalid {0} bank fields — {2}")
    @MethodSource("invalidBankFields")
    fun `invalid bank fields fail validation`(currency: String, fields: Map<String, Any?>, expectedField: String) {
        val result = BankFieldsValidator.validate(currency, fields, "en")
        assertTrue(result.isNotEmpty(), "Expected invalid for $currency field $expectedField")
        assertTrue(
            result.any { it.field.contains(expectedField) },
            "Expected error for field '$expectedField' but got: ${result.map { it.field }}",
        )
    }

    @Test
    fun `USD missing routing_number fails with required code`() {
        val result = BankFieldsValidator.validate("USD", mapOf("account_number" to "123456789"), "en")
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.field.contains("routing_number") && it.code == "required" })
    }

    @Test
    fun `USD missing account_number fails with required code`() {
        val result = BankFieldsValidator.validate("USD", mapOf("routing_number" to "021000021"), "en")
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.field.contains("account_number") && it.code == "required" })
    }

    @Test
    fun `unknown currency returns no errors`() {
        val result = BankFieldsValidator.validate("XYZ", mapOf("whatever" to "value"), "en")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `EUR IBAN optional BIC passes without BIC`() {
        val result = BankFieldsValidator.validate("EUR", mapOf("iban" to "DE89370400440532013000"), "en")
        assertTrue(result.isEmpty(), "Expected valid but got errors: $result")
    }

    @Test
    fun `EUR invalid BIC fails with invalid_pattern code`() {
        val result = BankFieldsValidator.validate("EUR", mapOf("iban" to "DE89370400440532013000", "bic" to "INVALID!!!"), "en")
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.field.contains("bic") && it.code == "invalid_pattern" })
    }

    @Test
    fun `GBP valid fields pass`() {
        val result = BankFieldsValidator.validate(
            "GBP",
            mapOf("sort_code" to "40-30-20", "account_number" to "12345678"),
            "en",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `CHF valid IBAN starting with CH passes`() {
        val result = BankFieldsValidator.validate("CHF", mapOf("iban" to "CH5604835012345678009"), "en")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `CHF IBAN must be exactly 21 chars`() {
        // Too short (20 chars): CH + 56 + 04835 + 01234567800 (11 account chars instead of 12)
        val result = BankFieldsValidator.validate("CHF", mapOf("iban" to "CH560483501234567800"), "en")
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.field.contains("iban") && it.code == "invalid_pattern" })
    }

    @Test
    fun `CHF with valid swift passes`() {
        val result = BankFieldsValidator.validate(
            "CHF",
            mapOf("iban" to "CH5604835012345678009", "swift" to "UBSWCHZH80A"),
            "en",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `CHF with invalid swift fails`() {
        val result = BankFieldsValidator.validate(
            "CHF",
            mapOf("iban" to "CH5604835012345678009", "swift" to "INVALID!!!"),
            "en",
        )
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.field.contains("swift") && it.code == "invalid_pattern" })
    }

    @Test
    fun `AUD bsb with dash passes`() {
        val result = BankFieldsValidator.validate("AUD", mapOf("bsb" to "062-000", "account_number" to "1234567890"), "en")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `AUD account at 6-digit boundary passes`() {
        val result = BankFieldsValidator.validate("AUD", mapOf("bsb" to "062000", "account_number" to "123456"), "en")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `AUD account at 10-digit boundary passes`() {
        val result = BankFieldsValidator.validate("AUD", mapOf("bsb" to "062000", "account_number" to "1234567890"), "en")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `AUD account with 5 digits fails`() {
        val result = BankFieldsValidator.validate("AUD", mapOf("bsb" to "062000", "account_number" to "12345"), "en")
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.field.contains("account_number") && it.code == "invalid_pattern" })
    }

    @Test
    fun `NZD 4-field format passes`() {
        val result = BankFieldsValidator.validate(
            "NZD",
            mapOf("bank_code" to "01", "branch_code" to "0142", "account_number" to "0000035", "suffix" to "00"),
            "en",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `NZD with 3-digit suffix passes`() {
        val result = BankFieldsValidator.validate(
            "NZD",
            mapOf("bank_code" to "01", "branch_code" to "0142", "account_number" to "0000035", "suffix" to "002"),
            "en",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `NZD missing suffix fails with required code`() {
        val result = BankFieldsValidator.validate(
            "NZD",
            mapOf("bank_code" to "01", "branch_code" to "0142", "account_number" to "0000035"),
            "en",
        )
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.field.contains("suffix") && it.code == "required" })
    }

    @Test
    fun `CNH account minimum 10 digits passes`() {
        val result = BankFieldsValidator.validate(
            "CNH",
            mapOf("account_number" to "1234567890", "bank_name" to "Bank of China"),
            "en",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `CNH account with 8 digits fails with invalid_pattern`() {
        val result = BankFieldsValidator.validate(
            "CNH",
            mapOf("account_number" to "12345678", "bank_name" to "Bank of China"),
            "en",
        )
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.field.contains("account_number") && it.code == "invalid_pattern" })
    }

    @Test
    fun `JPY account_holder_kana is optional`() {
        val result = BankFieldsValidator.validate(
            "JPY",
            mapOf("bank_code" to "0001", "branch_code" to "123", "account_number" to "1234567"),
            "en",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple errors are returned when multiple fields invalid`() {
        val result = BankFieldsValidator.validate(
            "USD",
            mapOf("account_number" to "12", "routing_number" to "123"), // both invalid patterns
            "en",
        )
        assertTrue(result.isNotEmpty())
        assertEquals(2, result.size)
        assertTrue(result.all { it.code == "invalid_pattern" })
    }
}
