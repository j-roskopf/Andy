package app.andy

/** Navigation intents sent from the address bar down into the platform browser surface. */
sealed class BrowserNavCommand {
    data object Back : BrowserNavCommand()
    data object Forward : BrowserNavCommand()
    data object Refresh : BrowserNavCommand()
    data class GoTo(val url: String) : BrowserNavCommand()
}
