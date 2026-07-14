package app.andy.model

import kotlinx.serialization.Serializable

@Serializable
enum class IntentMode { Activity, DeepLink, Service, Broadcast }
@Serializable
enum class ExtraType { StringValue, BooleanValue, IntValue, LongValue, FloatValue }

@Serializable
data class IntentExtra(
    val key: String,
    val type: ExtraType,
    val value: String,
)

@Serializable
data class IntentDraft(
    val mode: IntentMode = IntentMode.DeepLink,
    val action: String = "android.intent.action.VIEW",
    val component: String = "",
    val dataUri: String = "",
    val categories: List<String> = listOf("android.intent.category.DEFAULT"),
    val flags: List<String> = emptyList(),
    val extras: List<IntentExtra> = emptyList(),
)
