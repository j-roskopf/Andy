package app.andy.model

data class AndroidApp(
    val packageName: String,
    val label: String? = null,
    val system: Boolean = false,
    val enabled: Boolean = true,
    val versionName: String? = null,
    val versionCode: String? = null,
)

data class AndroidPermission(
    val name: String,
    val granted: Boolean?,
)

data class AndroidActivity(
    val name: String,
    val exported: Boolean?,
)
