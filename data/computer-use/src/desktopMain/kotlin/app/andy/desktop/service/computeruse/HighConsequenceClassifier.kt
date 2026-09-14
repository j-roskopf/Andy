package app.andy.desktop.service.computeruse

/**
 * Label-first high-consequence classification (§9).
 * Role is a weak secondary signal only — never gates the match.
 */
object HighConsequenceClassifier {
    val DefaultLabels: List<String> = listOf(
        "delete",
        "send",
        "pay",
        "purchase",
        "approve",
        "allow",
        "trust",
        "move to trash",
        "empty trash",
        "uninstall",
        "erase",
        "format",
        "destroy",
        "remove account",
        "confirm payment",
    )

    data class Verdict(
        val highConsequence: Boolean,
        val matchedLabel: String? = null,
        val reason: String? = null,
        /** Role boosted confidence but is not required. */
        val roleHint: String? = null,
    )

    fun classify(
        label: String?,
        role: String? = null,
        extraLabels: List<String> = emptyList(),
        /** Unlabeled pressable control — refuse unattended, HUD covers attended. */
        unlabeledUnattended: Boolean = false,
    ): Verdict {
        if (unlabeledUnattended && label.isNullOrBlank()) {
            return Verdict(
                highConsequence = true,
                reason = "Unlabeled control refused in unattended mode",
            )
        }
        val normalized = label?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) {
            return Verdict(highConsequence = false)
        }
        val needles = (DefaultLabels + extraLabels.map { it.lowercase() }).distinct()
        val matched = needles.firstOrNull { needle ->
            normalized == needle || normalized.contains(needle)
        }
        if (matched != null) {
            val roleHint = role?.takeIf {
                it.contains("button", ignoreCase = true) ||
                    it.contains("menu", ignoreCase = true) ||
                    it == "AXButton" ||
                    it == "AXMenuItem"
            }
            return Verdict(
                highConsequence = true,
                matchedLabel = matched,
                reason = "Label matched high-consequence pattern \"$matched\"",
                roleHint = roleHint,
            )
        }
        // Modal/sheet roles alone are not enough without a label match, but note them.
        if (role == "AXSheet" || role == "AXDialog") {
            return Verdict(
                highConsequence = false,
                roleHint = role,
                reason = "Modal detected without high-consequence label",
            )
        }
        return Verdict(highConsequence = false)
    }

    fun isSecureField(role: String?, subrole: String?): Boolean =
        role == "AXSecureTextField" || subrole == "AXSecureTextField"
}
