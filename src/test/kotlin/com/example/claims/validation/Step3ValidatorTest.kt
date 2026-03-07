package com.example.claims.validation

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Step3ValidatorTest {

    // ── Valid asset types ─────────────────────────────────────────────────────

    @Test
    fun `valid stock asset passes`() {
        val step3 = JsonObject()
            .put("assetType", "stock")
            .put("tickerSymbol", "AAPL")
            .put("exchange", "NASDAQ")
            .put("sharesOwned", "100")
        assertTrue(Step3Validator.validate(step3, "en").isEmpty())
    }

    @Test
    fun `valid etf asset passes`() {
        val step3 = JsonObject()
            .put("assetType", "etf")
            .put("tickerSymbol", "SPY")
            .put("exchange", "NYSE")
            .put("sharesOwned", "50.5")
        assertTrue(Step3Validator.validate(step3, "en").isEmpty())
    }

    @Test
    fun `valid bond asset passes`() {
        val step3 = JsonObject()
            .put("assetType", "bond")
            .put("isin", "US0231351067")
            .put("faceValue", "1000")
            .put("maturityDate", "2030-01-01")
        assertTrue(Step3Validator.validate(step3, "en").isEmpty())
    }

    @Test
    fun `valid mutual_fund asset passes`() {
        val step3 = JsonObject()
            .put("assetType", "mutual_fund")
            .put("fundName", "Vanguard 500")
            .put("fundCode", "VFIAX")
            .put("unitsHeld", "10.5")
        assertTrue(Step3Validator.validate(step3, "en").isEmpty())
    }

    @Test
    fun `valid crypto asset passes`() {
        val step3 = JsonObject()
            .put("assetType", "crypto")
            .put("cryptoExchange", "Binance")
            .put("cryptoAccountId", "acc123")
            .put("cryptoAssetSymbol", "BTC")
            .put("cryptoAmount", "0.5")
        assertTrue(Step3Validator.validate(step3, "en").isEmpty())
    }

    @Test
    fun `valid savings asset passes`() {
        val step3 = JsonObject()
            .put("assetType", "savings")
            .put("bankName", "Chase")
            .put("savingsAccountNumber", "123456789")
            .put("savingsBalance", "5000")
        assertTrue(Step3Validator.validate(step3, "en").isEmpty())
    }

    // ── Required field checks ─────────────────────────────────────────────────

    @Test
    fun `missing assetType returns required code`() {
        val errors = Step3Validator.validate(JsonObject(), "en")
        assertTrue(errors.any { it.field == "step3.assetType" && it.code == "required" })
    }

    @Test
    fun `missing required field returns required code`() {
        val step3 = JsonObject()
            .put("assetType", "stock")
            .put("exchange", "NASDAQ")
            .put("sharesOwned", "100")
        // tickerSymbol is missing
        val errors = Step3Validator.validate(step3, "en")
        assertTrue(errors.any { it.field == "step3.tickerSymbol" && it.code == "required" })
    }

    // ── Enum validation ───────────────────────────────────────────────────────

    @Test
    fun `invalid assetType returns invalid_enum code`() {
        val step3 = JsonObject().put("assetType", "unknown_type")
        val errors = Step3Validator.validate(step3, "en")
        assertTrue(errors.any { it.field == "step3.assetType" && it.code == "invalid_enum" })
    }

    // ── Pattern validation ────────────────────────────────────────────────────

    @Test
    fun `tickerSymbol with lowercase fails with invalid_pattern`() {
        val step3 = JsonObject()
            .put("assetType", "stock")
            .put("tickerSymbol", "aapl")
            .put("exchange", "NASDAQ")
            .put("sharesOwned", "100")
        val errors = Step3Validator.validate(step3, "en")
        assertTrue(errors.any { it.field == "step3.tickerSymbol" && it.code == "invalid_pattern" })
    }

    @Test
    fun `tickerSymbol too long fails with invalid_pattern`() {
        val step3 = JsonObject()
            .put("assetType", "stock")
            .put("tickerSymbol", "TOOLONGTICKERX") // > 10 chars
            .put("exchange", "NASDAQ")
            .put("sharesOwned", "100")
        val errors = Step3Validator.validate(step3, "en")
        assertTrue(errors.any { it.field == "step3.tickerSymbol" && it.code == "invalid_pattern" })
    }

    @Test
    fun `tickerSymbol with valid special chars passes`() {
        val step3 = JsonObject()
            .put("assetType", "etf")
            .put("tickerSymbol", "BRK.B")
            .put("exchange", "NYSE")
            .put("sharesOwned", "10")
        val errors = Step3Validator.validate(step3, "en")
        assertTrue(errors.none { it.field == "step3.tickerSymbol" })
    }

    @Test
    fun `cryptoAssetSymbol with lowercase fails with invalid_pattern`() {
        val step3 = JsonObject()
            .put("assetType", "crypto")
            .put("cryptoExchange", "Binance")
            .put("cryptoAccountId", "acc123")
            .put("cryptoAssetSymbol", "btc")
            .put("cryptoAmount", "0.5")
        val errors = Step3Validator.validate(step3, "en")
        assertTrue(errors.any { it.field == "step3.cryptoAssetSymbol" && it.code == "invalid_pattern" })
    }

    @Test
    fun `fundCode at exactly 20 chars passes`() {
        val step3 = JsonObject()
            .put("assetType", "mutual_fund")
            .put("fundName", "Some Fund")
            .put("fundCode", "A".repeat(20))
            .put("unitsHeld", "10")
        assertTrue(Step3Validator.validate(step3, "en").isEmpty())
    }

    @Test
    fun `fundCode too long fails with max_length`() {
        val step3 = JsonObject()
            .put("assetType", "mutual_fund")
            .put("fundName", "Some Fund")
            .put("fundCode", "A".repeat(21))
            .put("unitsHeld", "10")
        val errors = Step3Validator.validate(step3, "en")
        assertTrue(errors.any { it.field == "step3.fundCode" && it.code == "max_length" })
    }
}
