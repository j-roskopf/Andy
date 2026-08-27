package app.andy.desktop.service

import app.andy.model.EvidenceArtifactManifestEntry
import app.andy.model.RedactionReport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val EvidenceJsonFormat = Json {
    encodeDefaults = true
    prettyPrint = true
}

/** Writes the human-readable manifest/redaction sidecars for a materialized evidence bundle. */
internal object EvidenceJson {
    fun writeManifest(file: File, manifest: List<EvidenceArtifactManifestEntry>) {
        file.writeText(EvidenceJsonFormat.encodeToString(manifest) + "\n")
    }

    fun writeRedactionReport(file: File, report: RedactionReport) {
        file.writeText(EvidenceJsonFormat.encodeToString(report) + "\n")
    }
}
