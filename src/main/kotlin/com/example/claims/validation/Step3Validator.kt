package com.example.claims.validation

import io.vertx.core.json.JsonObject

object Step3Validator {

    private val rules: Map<String, List<String>> = mapOf(
        "stock"       to listOf("tickerSymbol", "exchange", "sharesOwned"),
        "etf"         to listOf("tickerSymbol", "exchange", "sharesOwned"),
        "bond"        to listOf("isin", "faceValue", "maturityDate"),
        "mutual_fund" to listOf("fundName", "fundCode", "unitsHeld"),
        "crypto"      to listOf("cryptoExchange", "cryptoAccountId", "cryptoAssetSymbol", "cryptoAmount"),
        "savings"     to listOf("bankName", "savingsAccountNumber", "savingsBalance"),
    )

    private val validTypes = rules.keys
    private val tickerRegex = Regex("""^[A-Z0-9.\-]{1,10}$""")
    private val cryptoSymbolRegex = Regex("""^[A-Z0-9]{1,10}$""")

    fun validate(step3: JsonObject, locale: String): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        val assetType = step3.getString("assetType")

        if (assetType == null) {
            errors += FieldError("step3.assetType", "required", Messages.get(locale, "required"))
            return errors
        }

        if (assetType !in validTypes) {
            errors += FieldError(
                "step3.assetType", "invalid_enum",
                Messages.get(locale, "invalid_enum"),
            )
            return errors
        }

        val required = rules[assetType]!!
        for (field in required) {
            val value = step3.getValue(field)
            if (value == null || value.toString().isBlank()) {
                errors += FieldError("step3.$field", "required", Messages.get(locale, "required"))
            }
        }

        // Pattern validation for specific fields (only when field is present and non-blank)
        if (assetType in setOf("stock", "etf")) {
            val ticker = step3.getString("tickerSymbol")
            if (ticker != null && ticker.isNotBlank() && !tickerRegex.matches(ticker)) {
                errors += FieldError("step3.tickerSymbol", "invalid_pattern", Messages.get(locale, "invalid_pattern"))
            }
        }

        if (assetType == "crypto") {
            val symbol = step3.getString("cryptoAssetSymbol")
            if (symbol != null && symbol.isNotBlank() && !cryptoSymbolRegex.matches(symbol)) {
                errors += FieldError("step3.cryptoAssetSymbol", "invalid_pattern", Messages.get(locale, "invalid_pattern"))
            }
        }

        if (assetType == "mutual_fund") {
            val fundCode = step3.getString("fundCode")
            if (fundCode != null && fundCode.length > 20) {
                errors += FieldError("step3.fundCode", "max_length", Messages.get(locale, "max_length"))
            }
        }

        return errors
    }
}
