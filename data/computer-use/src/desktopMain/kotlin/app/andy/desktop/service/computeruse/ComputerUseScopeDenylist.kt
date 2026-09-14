package app.andy.desktop.service.computeruse

/**
 * Hard, non-overridable scope denylist (§8).
 * Match against localized app name or bundle id (case-insensitive).
 */
object ComputerUseScopeDenylist {
    private val deniedExact = setOf(
        // Terminals — total consent bypass via shell
        "terminal",
        "iterm",
        "iterm2",
        "alacritty",
        "kitty",
        "warp",
        "hyper",
        "ghostty",
        "wezterm",
        "com.apple.terminal",
        "com.googlecode.iterm2",
        "org.alacritty",
        "net.kovidgoyal.kitty",
        "dev.warp.warp-stable",
        "co.zeit.hyper",
        "com.mitchellh.ghostty",
        "com.github.wez.wezterm",
        // Password managers
        "1password",
        "bitwarden",
        "lastpass",
        "keychain access",
        "com.1password.1password",
        "com.bitwarden.desktop",
        "com.lastpass.LastPass",
        "com.apple.keychainaccess",
        // Andy / andyd — self-escalation
        "andy",
        "andyd",
        "com.joetr.andy",
    )

    private val deniedContains = listOf(
        "1password",
        "bitwarden",
        "lastpass",
        "password manager",
    )

    fun isDenied(appNameOrBundleId: String): Boolean {
        val key = appNameOrBundleId.trim().lowercase()
        if (key.isEmpty()) return false
        if (key in deniedExact) return true
        return deniedContains.any { it in key }
    }

    fun deniedReason(appNameOrBundleId: String): String =
        "App \"$appNameOrBundleId\" is on the computer-use denylist " +
            "(terminals, password managers, and Andy itself cannot be scoped)."
}
