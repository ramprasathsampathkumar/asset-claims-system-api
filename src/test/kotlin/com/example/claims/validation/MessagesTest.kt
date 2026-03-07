package com.example.claims.validation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessagesTest {

    @Test
    fun `known locale returns localized string`() {
        val msg = Messages.get("fr", "required")
        assertTrue(msg.isNotBlank(), "Expected non-blank French message for 'required'")
    }

    @Test
    fun `unknown locale falls back to English`() {
        val en = Messages.get("en", "required")
        val unknown = Messages.get("xx", "required")
        assertEquals(en, unknown, "Unknown locale should fall back to English")
    }

    @Test
    fun `French required differs from English`() {
        val en = Messages.get("en", "required")
        val fr = Messages.get("fr", "required")
        assertNotEquals(en, fr, "French and English 'required' messages should differ")
    }

    @Test
    fun `all 11 locales resolve without exception`() {
        val locales = listOf("en", "fr", "es", "de", "pt", "zh", "ar", "hi", "bn", "ur", "ru")
        for (locale in locales) {
            assertDoesNotThrow {
                val msg = Messages.get(locale, "required")
                assertTrue(msg.isNotBlank(), "Locale '$locale' returned blank message for 'required'")
            }
        }
    }

    @Test
    fun `all 8 error codes are resolvable for English`() {
        val codes = listOf(
            "required", "invalid_pattern", "invalid_format", "invalid_enum",
            "min_length", "max_length", "date_past", "age_minimum",
        )
        for (code in codes) {
            val msg = Messages.get("en", code)
            assertTrue(msg.isNotBlank(), "Code '$code' returned blank message")
        }
    }
}
