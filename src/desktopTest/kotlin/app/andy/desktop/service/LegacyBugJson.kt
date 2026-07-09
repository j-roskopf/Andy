package app.andy.desktop.service

import app.andy.desktop.updates.SimpleJsonParser
import app.andy.model.BugAction
import app.andy.model.BugArtifact
import app.andy.model.BugReport

/**
 * Frozen copy of the hand-rolled [BugJson] writer/reader as of Phase 0.
 * Used by [BugJsonGoldenTest] to prove bidirectional compatibility across the
 * kotlinx.serialization swap: old files decode via the new path, and new output
 * still decodes via this legacy reader.
 */
internal object LegacyBugJson {
    fun writeActions(actions: List<BugAction>): String {
        return actions.joinToString(prefix = "{\"actions\":[", postfix = "]}\n") { action ->
            buildString {
                append("{")
                field("id", action.id)
                append(",\"timestampMillis\":${action.timestampMillis}")
                append(",")
                field("kind", action.kind)
                append(",")
                field("label", action.label)
                append(",\"detail\":")
                append(action.detail?.let(::quote) ?: "null")
                append("}")
            }
        }
    }

    fun writeReport(report: BugReport): String {
        return buildString {
            append("{")
            field("id", report.id)
            append(",")
            field("title", report.title)
            append(",")
            field("notes", report.notes)
            append(",")
            field("deviceSerial", report.deviceSerial)
            append(",\"deviceModel\":")
            append(report.deviceModel?.let(::quote) ?: "null")
            append(",\"apiLevel\":")
            append(report.apiLevel?.let(::quote) ?: "null")
            append(",\"abi\":")
            append(report.abi?.let(::quote) ?: "null")
            append(",\"resolution\":")
            append(report.resolution?.let(::quote) ?: "null")
            append(",\"capturedAtMillis\":${report.capturedAtMillis}")
            append(",\"windowStartedAtMillis\":${report.windowStartedAtMillis}")
            append(",\"windowEndedAtMillis\":${report.windowEndedAtMillis}")
            append(",\"videoStartedAtMillis\":")
            append(report.videoStartedAtMillis?.toString() ?: "null")
            append(",\"videoEndedAtMillis\":")
            append(report.videoEndedAtMillis?.toString() ?: "null")
            append(",\"videoFrameRate\":")
            append(report.videoFrameRate?.toString() ?: "null")
            append(",\"videoFrameTimestampsMillis\":[")
            append(report.videoFrameTimestampsMillis.joinToString(","))
            append("]")
            append(",\"actions\":")
            append(writeActions(report.actions).substringAfter('[').let { "[${it.substringBeforeLast(']')}]" })
            append(",\"artifacts\":[")
            append(report.artifacts.joinToString(",") { artifact ->
                buildString {
                    append("{")
                    field("name", artifact.name)
                    append(",")
                    field("relativePath", artifact.relativePath)
                    append(",")
                    field("kind", artifact.kind)
                    append(",\"sizeBytes\":")
                    append(artifact.sizeBytes?.toString() ?: "null")
                    append("}")
                }
            })
            append("]}\n")
        }
    }

    fun readReport(json: String): BugReport {
        val root = SimpleJsonParser(json).parse() as Map<*, *>
        val actions = (root["actions"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            BugAction(
                id = map["id"] as? String ?: return@mapNotNull null,
                timestampMillis = (map["timestampMillis"] as? Number)?.toLong() ?: return@mapNotNull null,
                kind = map["kind"] as? String ?: "action",
                label = map["label"] as? String ?: "Action",
                detail = map["detail"] as? String,
            )
        }
        val artifacts = (root["artifacts"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            BugArtifact(
                name = map["name"] as? String ?: return@mapNotNull null,
                relativePath = map["relativePath"] as? String ?: return@mapNotNull null,
                kind = map["kind"] as? String ?: "file",
                sizeBytes = (map["sizeBytes"] as? Number)?.toLong(),
            )
        }
        return BugReport(
            id = root["id"] as? String ?: error("Missing bug id"),
            title = root["title"] as? String ?: "Untitled bug",
            notes = root["notes"] as? String ?: "",
            deviceSerial = root["deviceSerial"] as? String ?: "unknown-device",
            deviceModel = root["deviceModel"] as? String,
            apiLevel = root["apiLevel"] as? String,
            abi = root["abi"] as? String,
            resolution = root["resolution"] as? String,
            capturedAtMillis = (root["capturedAtMillis"] as? Number)?.toLong() ?: 0L,
            windowStartedAtMillis = (root["windowStartedAtMillis"] as? Number)?.toLong() ?: 0L,
            windowEndedAtMillis = (root["windowEndedAtMillis"] as? Number)?.toLong() ?: 0L,
            actions = actions,
            artifacts = artifacts,
            videoStartedAtMillis = (root["videoStartedAtMillis"] as? Number)?.toLong(),
            videoEndedAtMillis = (root["videoEndedAtMillis"] as? Number)?.toLong(),
            videoFrameRate = (root["videoFrameRate"] as? Number)?.toDouble(),
            videoFrameTimestampsMillis = (root["videoFrameTimestampsMillis"] as? List<*>)
                .orEmpty()
                .mapNotNull { (it as? Number)?.toLong() },
        )
    }

    private fun StringBuilder.field(name: String, value: String) {
        append(quote(name))
        append(":")
        append(quote(value))
    }

    private fun quote(value: String): String {
        return buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}
