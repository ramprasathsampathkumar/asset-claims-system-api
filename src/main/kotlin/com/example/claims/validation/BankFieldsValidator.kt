package com.example.claims.validation

data class FieldError(
    val field: String,
    val code: String,
    val message: String,
)

object BankFieldsValidator {

    private val currencyRules: Map<String, List<BankFieldRule>> = mapOf(
        "USD" to listOf(
            BankFieldRule("account_number", required = true,  pattern = Regex("""^\d{4,17}$""")),
            BankFieldRule("routing_number", required = true,  pattern = Regex("""^\d{9}$""")),
        ),
        "EUR" to listOf(
            BankFieldRule("iban", required = true,  pattern = Regex("""^[A-Z]{2}\d{2}[A-Z0-9]{4,30}$""")),
            BankFieldRule("bic",  required = false, pattern = Regex("""^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$""")),
        ),
        "GBP" to listOf(
            BankFieldRule("sort_code",      required = true, pattern = Regex("""^\d{2}-\d{2}-\d{2}$""")),
            BankFieldRule("account_number", required = true, pattern = Regex("""^\d{8}$""")),
        ),
        "JPY" to listOf(
            BankFieldRule("bank_code",           required = true,  pattern = Regex("""^\d{4}$""")),
            BankFieldRule("branch_code",         required = true,  pattern = Regex("""^\d{3}$""")),
            BankFieldRule("account_number",      required = true,  pattern = Regex("""^\d{7}$""")),
            BankFieldRule("account_holder_kana", required = false, pattern = Regex("""^[\u30A1-\u30F3\s]{0,50}$""")),
        ),
        "AUD" to listOf(
            BankFieldRule("bsb",            required = true, pattern = Regex("""^\d{3}-?\d{3}$""")),
            BankFieldRule("account_number", required = true, pattern = Regex("""^\d{6,10}$""")),
        ),
        "CAD" to listOf(
            BankFieldRule("institution_number", required = true,  pattern = Regex("""^\d{3}$""")),
            BankFieldRule("transit_number",     required = true,  pattern = Regex("""^\d{5}$""")),
            BankFieldRule("account_number",     required = false, pattern = Regex("""^\d{7,12}$""")),
        ),
        "CHF" to listOf(
            BankFieldRule("iban",  required = true,  pattern = Regex("""^CH\d{2}[0-9]{5}[A-Z0-9]{12}$""")),
            BankFieldRule("swift", required = false, pattern = Regex("""^[A-Z]{4}CH[A-Z0-9]{2}([A-Z0-9]{3})?$""")),
        ),
        "CNH" to listOf(
            BankFieldRule("account_number", required = true,  pattern = Regex("""^\d{10,20}$""")),
            BankFieldRule("bank_name",      required = true,  pattern = Regex("""^[\p{L}\p{M}\s'.\-&,]{2,100}$""")),
            BankFieldRule("swift",          required = false, pattern = Regex("""^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$""")),
        ),
        "HKD" to listOf(
            BankFieldRule("account_number", required = true,  pattern = Regex("""^\d{6,12}$""")),
            BankFieldRule("bank_code",      required = true,  pattern = Regex("""^\d{3}$""")),
            BankFieldRule("swift",          required = false, pattern = Regex("""^[A-Z]{4}HK[A-Z0-9]{2}([A-Z0-9]{3})?$""")),
        ),
        "NZD" to listOf(
            BankFieldRule("bank_code",      required = true, pattern = Regex("""^\d{2}$""")),
            BankFieldRule("branch_code",    required = true, pattern = Regex("""^\d{4}$""")),
            BankFieldRule("account_number", required = true, pattern = Regex("""^\d{7}$""")),
            BankFieldRule("suffix",         required = true, pattern = Regex("""^\d{2,3}$""")),
        ),
    )

    fun validate(currency: String, bankFields: Map<String, Any?>, locale: String): List<FieldError> {
        val rules = currencyRules[currency] ?: return emptyList()
        val errors = mutableListOf<FieldError>()

        for (rule in rules) {
            val value = bankFields[rule.field]

            if (rule.required && (value == null || value.toString().isBlank())) {
                errors += FieldError(
                    field = "step4.bankFields.${rule.field}",
                    code = "required",
                    message = Messages.get(locale, "required"),
                )
                continue
            }

            if (value != null && value.toString().isNotBlank() && rule.pattern != null) {
                if (!rule.pattern.matches(value.toString())) {
                    errors += FieldError(
                        field = "step4.bankFields.${rule.field}",
                        code = "invalid_pattern",
                        message = Messages.get(locale, "invalid_pattern"),
                    )
                }
            }
        }

        return errors
    }
}

data class BankFieldRule(
    val field: String,
    val required: Boolean,
    val pattern: Regex?,
)
