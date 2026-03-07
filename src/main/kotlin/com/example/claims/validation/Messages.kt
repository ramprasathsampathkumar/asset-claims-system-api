package com.example.claims.validation

import java.util.Properties

object Messages {
    private val cache = mutableMapOf<String, Properties>()
    private val supported = setOf("en", "fr", "es", "de", "pt", "zh", "ar", "hi", "bn", "ur", "ru")

    fun get(locale: String, code: String): String {
        val lang = if (locale in supported) locale else "en"
        val props = cache.getOrPut(lang) { load(lang) }
        return props.getProperty(code)
            ?: cache.getOrPut("en") { load("en") }.getProperty(code)
            ?: code
    }

    private fun load(locale: String): Properties {
        val props = Properties()
        val stream = Messages::class.java.classLoader
            .getResourceAsStream("messages/messages_$locale.properties")
        if (stream != null) {
            stream.use { props.load(it.reader(Charsets.UTF_8)) }
        }
        return props
    }
}
