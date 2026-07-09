package app.andy.model

enum class IntentMode { Activity, DeepLink, Service, Broadcast }
enum class ExtraType { StringValue, BooleanValue, IntValue, LongValue, FloatValue }

data class IntentExtra(
    val key: String,
    val type: ExtraType,
    val value: String,
)

data class IntentDraft(
    val mode: IntentMode = IntentMode.DeepLink,
    val action: String = "android.intent.action.VIEW",
    val component: String = "",
    val dataUri: String = "",
    val categories: List<String> = listOf("android.intent.category.DEFAULT"),
    val flags: List<String> = emptyList(),
    val extras: List<IntentExtra> = emptyList(),
)
