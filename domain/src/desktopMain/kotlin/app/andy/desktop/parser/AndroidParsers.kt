package app.andy.desktop.parser

import app.andy.model.*
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

object AndroidParsers {
    fun parseAdbDevices(output: String): List<AndroidDevice> {
        return output.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val parts = trimmed.split(Regex("\\s+"))
                val serial = parts.getOrNull(0) ?: return@mapNotNull null
                val stateRaw = parts.getOrNull(1) ?: "unknown"
                val fields = parts.drop(2)
                    .mapNotNull { part -> part.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                    .toMap()
                AndroidDevice(
                    serial = serial,
                    displayName = fields["model"]?.replace("_", " ") ?: serial,
                    kind = classifyDeviceKind(serial),
                    state = when (stateRaw) {
                        "device" -> DeviceConnectionState.Online
                        "offline" -> DeviceConnectionState.Offline
                        "unauthorized" -> DeviceConnectionState.Unauthorized
                        else -> DeviceConnectionState.Unknown
                    },
                    transport = classifyDeviceTransport(serial),
                    model = fields["model"],
                    product = fields["product"],
                    hardwareId = extractMdnsHardwareId(serial),
                )
            }
            .toList()
            .let(::dedupeWifiDeviceAliases)
    }

    fun parseMdnsServices(output: String): List<MdnsService> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("List of discovered", ignoreCase = true) }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) return@mapNotNull null
                val instanceName = parts[0]
                val serviceType = parts[1]
                val endpoint = parts[2]
                val host = endpoint.substringBeforeLast(':')
                val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
                if (host.isBlank()) return@mapNotNull null
                MdnsService(instanceName = instanceName, serviceType = serviceType, host = host, port = port)
            }
            .toList()
    }

    fun parseBatteryPercent(output: String): Int? {
        return Regex("""level:\s*(\d+)""").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun parseThermalStatus(output: String): String? =
        app.andy.domain.parseThermalStatus(output)

    fun parseSensorStatus(output: String): Map<String, List<Float>> =
        app.andy.domain.parseSensorStatus(output)

    fun parseGpxTrack(xml: String): List<app.andy.model.GeoFix> =
        app.andy.domain.parseGpxTrack(xml)

    fun parseKmlLineString(xml: String): List<app.andy.model.GeoFix> =
        app.andy.domain.parseKmlLineString(xml)

    fun parseNetworkTotals(output: String): Pair<Long, Long>? {
        var rxBytes = 0L
        var txBytes = 0L
        var found = false
        output.lineSequence().forEach { line ->
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@forEach
            val iface = line.substring(0, separatorIndex).trim()
            if (iface.isBlank() || iface == "lo" || iface.startsWith("face")) return@forEach
            val values = line.substring(separatorIndex + 1).trim().split(Regex("\\s+"))
            if (values.size < 9) return@forEach
            val rx = values[0].toLongOrNull() ?: return@forEach
            val tx = values[8].toLongOrNull() ?: return@forEach
            rxBytes += rx
            txBytes += tx
            found = true
        }
        return if (found) rxBytes to txBytes else null
    }

    /**
     * Current display size for mirroring. Prefer Override when present — that is what
     * SurfaceFlinger / scrcpy capture — and fall back to Physical.
     */
    fun parseWmSize(output: String): String? {
        Regex("""Override size:\s*([0-9]+x[0-9]+)""").find(output)?.groupValues?.getOrNull(1)?.let { return it }
        return parseWmPhysicalSize(output)
    }

    /** Native panel mode, which remains in natural orientation when the logical display rotates. */
    fun parseWmPhysicalSize(output: String): String? =
        Regex("""Physical size:\s*([0-9]+x[0-9]+)""").find(output)?.groupValues?.getOrNull(1)

    /**
     * Logical size of display 0 from `dumpsys window displays` (`cur=WxH`), which reflects
     * rotation. Prefer this over [parseWmSize] for capture aspect after device rotate.
     */
    fun parseDisplay0CurrentSize(output: String): String? {
        val display0 = Regex(
            """Display:\s*mDisplayId=0\b[\s\S]*?(?=Display:\s*mDisplayId=|\z)""",
            RegexOption.IGNORE_CASE,
        ).find(output)?.value ?: return null
        val match = Regex("""\bcur=(\d+)x(\d+)\b""", RegexOption.IGNORE_CASE).find(display0) ?: return null
        val width = match.groupValues[1]
        val height = match.groupValues[2]
        return "${width}x${height}"
    }

    fun parseStorage(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.contains("/data") || it.contains("/sdcard") }
            ?: output.lineSequence().drop(1).firstOrNull()
        val parts = line?.trim()?.split(Regex("\\s+")) ?: return null
        return if (parts.size >= 4) "${parts[3]} free / ${parts[1]}" else null
    }

    fun parseLogcatLine(line: String): LogcatEntry? {
        val threadtime = Regex("""^(\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+([^:]+):\s?(.*)$""")
        val compact = Regex("""^(\d\d:\d\d:\d\d\.\d+)\s+([VDIWEFS])/([^:]+):\s?(.*)$""")
        threadtime.find(line)?.let { match ->
            val level = parseLevel(match.groupValues[4])
            return LogcatEntry(match.groupValues[1], match.groupValues[2], match.groupValues[3], level, match.groupValues[5].trim(), match.groupValues[6])
        }
        compact.find(line)?.let { match ->
            return LogcatEntry(match.groupValues[1], null, null, parseLevel(match.groupValues[2]), match.groupValues[3].trim(), match.groupValues[4])
        }
        return null
    }

    fun extractPackageFilter(search: String): Pair<String?, String> {
        val match = Regex("""(?:^|\s)package:([A-Za-z0-9_.]+)""").find(search)
        val packageName = match?.groupValues?.getOrNull(1)
        val cleaned = if (match == null) {
            search
        } else {
            (search.removeRange(match.range)).trim().replace(Regex("\\s+"), " ")
        }
        return packageName to cleaned
    }

    fun parsePidList(output: String): Set<String> =
        output.split(Regex("\\s+")).filter { it.isNotBlank() && it.all(Char::isDigit) }.toSet()

    private const val PS_PROCESS_NAME_MAX_LEN = 15

    /** Matches a process [name] column value to an Android package (handles 15-char truncation). */
    fun processNameMatchesPackage(processName: String, packageName: String): Boolean =
        when {
            processName == packageName -> true
            processName.startsWith("$packageName:") -> true
            processName.length == PS_PROCESS_NAME_MAX_LEN &&
                packageName.length > PS_PROCESS_NAME_MAX_LEN &&
                processName == packageName.take(PS_PROCESS_NAME_MAX_LEN) -> true
            else -> false
        }

    fun packagePidsFromPs(output: String, packageName: String): Set<String> =
        output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("PID", ignoreCase = true)) return@mapNotNull null
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) return@mapNotNull null
                val pid = parts[0]
                if (!pid.all(Char::isDigit)) return@mapNotNull null
                val name = parts[1]
                if (processNameMatchesPackage(name, packageName)) pid else null
            }
            .toSet()

    fun packagePidsFromPsArgs(output: String, packageName: String): Set<String> =
        output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("PID", ignoreCase = true)) return@mapNotNull null
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) return@mapNotNull null
                val pid = parts[0]
                if (!pid.all(Char::isDigit)) return@mapNotNull null
                val args = parts[1]
                if (argsMatchPackage(args, packageName)) pid else null
            }
            .toSet()

    private fun argsMatchPackage(args: String, packageName: String): Boolean {
        val head = args.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        if (head.isEmpty()) return false
        if (processNameMatchesPackage(head, packageName)) return true
        return head.split('/').any { segment ->
            segment == packageName || segment.startsWith("$packageName:")
        }
    }

    fun parseSystemImages(output: String): List<SystemImage> {
        return output.lineSequence()
            .filter { it.contains("system-images;android-") }
            .mapNotNull { line ->
                val packageId = line.substringBefore("|").trim().takeIf { it.startsWith("system-images;") } ?: return@mapNotNull null
                val parts = packageId.split(";")
                val api = parts.getOrNull(1)?.removePrefix("android-") ?: "-"
                val variant = parts.getOrNull(2) ?: "-"
                val abi = parts.getOrNull(3) ?: "-"
                SystemImage(
                    packageId = packageId,
                    api = api,
                    variant = variant,
                    abi = abi,
                    displayName = "$api $variant $abi",
                    installed = line.contains("Installed", ignoreCase = true),
                )
            }
            .distinctBy { it.packageId }
            .sortedWith(compareByDescending<SystemImage> { it.api.toDoubleOrNull() ?: 0.0 }.thenBy { it.variant })
            .toList()
    }

    fun parseAvdList(output: String): List<VirtualDevice> {
        val blocks = output
            .replace(Regex("""(?m)^\s*-{3,}\s*$"""), "\n")
            .split(Regex("""\n\s*\n"""))
        return blocks.mapNotNull { block ->
            val name = Regex("""Name:\s*(.+)""").find(block)?.groupValues?.getOrNull(1)?.trim() ?: return@mapNotNull null
            VirtualDevice(
                name = name,
                path = Regex("""Path:\s*(.+)""").find(block)?.groupValues?.getOrNull(1)?.trim(),
                target = Regex("""Target:\s*(.+)""").find(block)?.groupValues?.getOrNull(1)?.trim(),
                abi = Regex("""ABI:\s*(.+)""").find(block)?.groupValues?.getOrNull(1)?.trim(),
                running = false,
                apiLevel = Regex("""API\s+level\s*:\s*(\d+)""").find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""android-(\d+)""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""Android\s+(\d+)""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.getOrNull(1)?.toIntOrNull(),
                deviceType = classifyVirtualDevice(name, block),
            )
        }
    }

    fun parseProfiles(output: String): List<AvdProfile> {
        val blocks = output.split(Regex("""id:\s+""")).drop(1)
        return blocks.mapNotNull { block ->
            val id = block.lineSequence().firstOrNull()?.trim()?.substringBefore(" ") ?: return@mapNotNull null
            val name = Regex("""Name:\s*(.+)""").find(block)?.groupValues?.getOrNull(1)?.trim() ?: id
            AvdProfile(
                id = id,
                name = name,
                oem = Regex("""OEM\s*:\s*(.+)""").find(block)?.groupValues?.getOrNull(1)?.trim(),
                tag = null,
                resolution = Regex("""Screen:\s*(.+)""").find(block)?.groupValues?.getOrNull(1)?.trim(),
                density = Regex("""dpis\s*:\s*(.+)""").find(block)?.groupValues?.getOrNull(1)?.trim(),
                category = classifyProfile(name, block),
            )
        }
    }

    fun classifyProfile(name: String, raw: String = ""): AvdProfileCategory {
        val value = "$name $raw".replace('_', ' ').lowercase()
        return when {
            "automotive" in value || Regex("""\bcar\b""").containsMatchIn(value) -> AvdProfileCategory.Automotive
            "desktop" in value -> AvdProfileCategory.Desktop
            "tv" in value -> AvdProfileCategory.Tv
            "wear" in value || "watch" in value -> AvdProfileCategory.Watch
            "fold" in value || "foldable" in value -> AvdProfileCategory.Foldable
            "tablet" in value || "pixel c" in value || "nexus 10" in value || "nexus 9" in value -> AvdProfileCategory.Tablet
            "phone" in value || "pixel" in value || "nexus" in value -> AvdProfileCategory.Phone
            else -> AvdProfileCategory.Other
        }
    }

    fun classifyVirtualDevice(name: String, raw: String = "", config: Map<String, String> = emptyMap()): VirtualDeviceType {
        val value = (listOf(name, raw) + config.values).joinToString(" ").replace('_', ' ').lowercase()
        return when {
            "automotive" in value || Regex("""\bcar\b""").containsMatchIn(value) -> VirtualDeviceType.Automotive
            "desktop" in value -> VirtualDeviceType.Desktop
            "tv" in value -> VirtualDeviceType.Tv
            "wear" in value || "watch" in value -> VirtualDeviceType.Watch
            "fold" in value || "foldable" in value -> VirtualDeviceType.Foldable
            "tablet" in value || "pixel_c" in value || "nexus_10" in value || "nexus_9" in value -> VirtualDeviceType.Tablet
            "phone" in value || "pixel" in value || "nexus" in value -> VirtualDeviceType.Phone
            else -> VirtualDeviceType.Unknown
        }
    }

    fun parseSnapshots(output: String, avdName: String): List<EmulatorSnapshot> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("OK", ignoreCase = true) &&
                    !line.startsWith("KO", ignoreCase = true) &&
                    !line.startsWith("List of", ignoreCase = true) &&
                    !line.startsWith("Snapshot", ignoreCase = true) &&
                    !line.startsWith("ID ", ignoreCase = true) &&
                    !line.matches(Regex("^-+$"))
            }
            .mapNotNull { line ->
                val columns = line.split(Regex("\\s+")).filter(String::isNotBlank)
                when {
                    columns.isEmpty() -> null
                    columns.size >= 2 && columns[0].all(Char::isDigit) -> columns[1]
                    else -> columns[0]
                }?.trim()
            }
            .filter { it.isNotBlank() && it != "Name" && it != "Tag" && it != "Snapshots:" }
            .distinct()
            .map { EmulatorSnapshot(name = it, avdName = avdName, source = "emulator") }
            .toList()
    }

    fun parseFileListing(path: String, output: String): List<DeviceFile> {
        return output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 8)
            if (parts.size < 7) return@mapNotNull null
            val permissions = parts[0]
            val size = parts.getOrNull(4)?.toLongOrNull()
            val modified = parts.drop(5).take(2).joinToString(" ")
            val name = parts.getOrNull(7) ?: return@mapNotNull null
            if (name == "." || name == "..") return@mapNotNull null
            DeviceFile(
                path = if (path.endsWith("/")) path + name else "$path/$name",
                name = name,
                isDirectory = permissions.startsWith("d"),
                sizeBytes = size,
                permissions = permissions,
                modified = modified,
            )
        }.toList()
    }

    fun parsePackagePermissions(output: String): List<AndroidPermission> {
        val requested = LinkedHashSet<String>()
        val granted = mutableMapOf<String, Boolean?>()
        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("android.permission.")) {
                requested += trimmed.substringBefore(":").substringBefore(" ")
            }
            val runtime = Regex("""(android\.permission\.[^:\s]+):\s+granted=(true|false)""").find(trimmed)
            if (runtime != null) {
                val name = runtime.groupValues[1]
                requested += name
                granted[name] = runtime.groupValues[2].toBoolean()
            }
        }
        return requested.map { AndroidPermission(it, granted[it]) }
    }

    fun parsePackageActivities(packageName: String, output: String): List<AndroidActivity> {
        val activities = LinkedHashSet<String>()
        output.lineSequence().forEach { line ->
            Regex("""$packageName/[^\s}]+""").findAll(line).forEach { match ->
                activities += match.value.substringAfter('/')
            }
        }
        return activities.map { AndroidActivity(it, null) }
    }

    fun parseAppDetails(output: String): AndroidAppDetails {
        if (output.isBlank() || output.contains("Unable to find package", ignoreCase = true)) return AndroidAppDetails()

        fun field(name: String): String? = Regex("""\b$name=([^\s]+)""").find(output)?.groupValues?.getOrNull(1)
        val flagLines = output.lineSequence().filter { line ->
            Regex("""\b(?:pkgFlags|flags)=\[""", RegexOption.IGNORE_CASE).containsMatchIn(line)
        }.toList()
        val signingVersion = Regex("""(?i)signatures=.*?\bversion:\s*(\d+)""").find(output)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)\bsignatureSchemeVersion=(\d+)""").find(output)?.groupValues?.getOrNull(1)

        return AndroidAppDetails(
            versionName = field("versionName"),
            versionCode = field("versionCode"),
            minSdk = field("minSdk"),
            targetSdk = field("targetSdk"),
            signingScheme = signingVersion?.let { "v$it" },
            debuggable = flagLines.takeIf { it.isNotEmpty() }?.any { it.contains("DEBUGGABLE") },
        )
    }

    fun parseProcessMetrics(output: String): List<ProcessMetric> {
        return output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("PID") || trimmed.startsWith("Tasks:")) return@mapNotNull null
                val parts = trimmed.split(Regex("\\s+"))
                val pid = parts.firstOrNull()?.takeIf { it.all(Char::isDigit) } ?: return@mapNotNull null
                val cpuIndex = parts.indexOfFirst { it.endsWith("%") || it.toFloatOrNull() != null }.takeIf { it > 0 } ?: -1
                val cpu = parts.drop(1).firstNotNullOfOrNull { part ->
                    part.removeSuffix("%").toFloatOrNull()?.takeIf { it <= 1000f }
                }
                val memoryToken = parts.firstOrNull { it.endsWith("K") || it.endsWith("M") || it.endsWith("G") }
                    ?: parts.drop(1).firstOrNull { it.toLongOrNull() != null && it != pid && parts.indexOf(it) != cpuIndex }
                val memoryMb = parseMemoryToken(memoryToken)
                val name = parts.drop(1).lastOrNull().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                ProcessMetric(pid = pid, name = name, cpuPercent = cpu, memoryMb = memoryMb)
            }
            .distinctBy { it.pid }
            .sortedWith(compareByDescending<ProcessMetric> { it.cpuPercent ?: -1f }.thenByDescending { it.memoryMb ?: -1f })
            .take(120)
            .toList()
    }

    fun parseFocusedPackage(output: String): String? {
        val patterns = listOf(
            Regex("""mCurrentFocus=.*\s([a-zA-Z0-9_.]+)/"""),
            Regex("""mFocusedApp=.*\s([a-zA-Z0-9_.]+)/"""),
            Regex("""topResumedActivity=.*\s([a-zA-Z0-9_.]+)/"""),
        )
        return patterns.firstNotNullOfOrNull { pattern -> pattern.find(output)?.groupValues?.getOrNull(1) }
    }

    fun parseFrameStats(output: String): List<FrameRenderMetric> {
        val header = output.lineSequence().firstOrNull { it.startsWith("Flags,") } ?: return emptyList()
        val columns = header.split(",")
        val intendedIndex = columns.indexOf("IntendedVsync").takeIf { it >= 0 } ?: return emptyList()
        val completedIndex = columns.indexOf("FrameCompleted").takeIf { it >= 0 } ?: return emptyList()
        val rows = output.lineSequence()
            .dropWhile { it != header }
            .drop(1)
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size <= maxOf(intendedIndex, completedIndex)) return@mapNotNull null
                val intended = parts.getOrNull(intendedIndex)?.toLongOrNull() ?: return@mapNotNull null
                val completed = parts.getOrNull(completedIndex)?.toLongOrNull() ?: return@mapNotNull null
                val millis = (completed - intended) / 1_000_000f
                if (millis > 0f && millis < 10_000f) intended to millis else null
            }
            .toList()
            .sortedBy { (intended, _) -> intended }
            .takeLast(120)
        return rows.mapIndexed { index, (intended, millis) ->
            val previousIntended = rows.getOrNull(index - 1)?.first
            val vsyncGapMillis = previousIntended?.let { (intended - it) / 1_000_000f }?.takeIf { it > 0f && it < 2_000f }
            FrameRenderMetric("#${index + 1}", millis, vsyncGapMillis)
        }
    }

    fun parseAccessibilityXml(xml: String): AccessibilityNode? {
        val cleanXml = xml.substringAfter("<?xml", xml).let { if (it.startsWith(" version")) "<?xml$it" else it }
        if (cleanXml.isBlank()) return null
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(InputSource(StringReader(cleanXml)))
        val root = doc.documentElement ?: return null
        val firstNode = root.getElementsByTagName("node").item(0) as? Element ?: return null
        return parseNode(firstNode, "0")
    }

    private fun parseNode(element: Element, id: String): AccessibilityNode {
        val attributes = buildMap {
            val attrs = element.attributes
            for (index in 0 until attrs.length) {
                val attr = attrs.item(index)
                put(attr.nodeName, attr.nodeValue)
            }
        }
        val children = buildList {
            val nodes = element.childNodes
            for (index in 0 until nodes.length) {
                val child = nodes.item(index)
                if (child is Element && child.tagName == "node") add(parseNode(child, "$id.$index"))
            }
        }
        return AccessibilityNode(
            id = id,
            className = element.attr("class"),
            packageName = element.attr("package"),
            resourceId = element.attr("resource-id"),
            text = element.attr("text"),
            contentDescription = element.attr("content-desc"),
            hint = element.attr("hint"),
            bounds = element.attr("bounds"),
            clickable = element.attr("clickable").toBoolean(),
            longClickable = element.attr("long-clickable").toBoolean(),
            focusable = element.attr("focusable").toBoolean(),
            focused = element.attr("focused").toBoolean(),
            enabled = element.attr("enabled").toBoolean(),
            selected = element.attr("selected").toBoolean(),
            checkable = element.attr("checkable").toBoolean(),
            checked = element.attr("checked").toBoolean(),
            scrollable = element.attr("scrollable").toBoolean(),
            password = element.attr("password").toBoolean(),
            visible = (element.attr("visible-to-user")?.ifBlank { "true" } ?: "true").toBoolean(),
            attributes = attributes,
            children = children,
        )
    }

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { it.isNotBlank() }

    // ---- B.2: dropbox crash/ANR index ----------------------------------------------------

    data class DropboxParseResult(
        val records: List<CrashRecord>,
        val bodiesById: Map<String, String>,
    )

    private val dropboxEntryHeaderRegex =
        Regex("""^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+(\S+)\s+\((?:text|data|compressed text),\s*(\d+)\s*bytes\)\s*$""")

    /**
     * Parses `dumpsys dropbox --print` into crash records plus full entry bodies keyed by [CrashRecord.id].
     * IDs use `dropbox|<timestamp>|<tag>` so [app.andy.desktop.service.DesktopCrashInspectorService]
     * can reload via `dumpsys dropbox --print <date> <time> <tag>` when the cache is cold.
     */
    fun parseDropboxIndex(output: String): DropboxParseResult {
        if (output.isBlank()) return DropboxParseResult(emptyList(), emptyMap())
        val chunks = output.split(Regex("""(?m)^={5,}\s*$"""))
        val records = mutableListOf<CrashRecord>()
        val bodiesById = mutableMapOf<String, String>()
        chunks.forEach { chunk ->
            val headerLine = chunk.lineSequence().map { it.trim() }.firstOrNull { dropboxEntryHeaderRegex.matches(it) }
                ?: return@forEach
            val header = dropboxEntryHeaderRegex.find(headerLine) ?: return@forEach
            val (timestampText, tag, _) = header.destructured
            val timestampMillis = parseDropboxTimestamp(timestampText)
            val body = chunk.substringAfter(headerLine, "")
            val packageName = Regex("""Process:\s*([\w.]+)""").find(body)?.groupValues?.getOrNull(1)
            val summary = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { line ->
                    line.isNotBlank() && !line.startsWith("Process:") && !line.startsWith("Flags:") &&
                        !line.startsWith("Package:") && !line.startsWith("Foreground:") && !line.startsWith("Build:")
                }
                ?: tag
            val baseId = "dropbox|$timestampText|$tag"
            val duplicateIndex = records.count { it.id == baseId || it.id.startsWith("$baseId#") }
            val id = if (duplicateIndex == 0) baseId else "$baseId#$duplicateIndex"
            records += CrashRecord(
                id = id,
                kind = classifyDropboxTag(tag),
                packageName = packageName,
                timestampMillis = timestampMillis,
                summary = summary.take(200),
            )
            bodiesById[id] = parseDropboxEntry(chunk)
        }
        return DropboxParseResult(records, bodiesById)
    }

    /** Strips the `========` chunk wrapper (if present) from a single `--file` entry's raw text. */
    fun parseDropboxEntry(output: String): String {
        val withoutHeaderRule = output.replace(Regex("""(?m)^={5,}\s*$"""), "").trim()
        val withoutTimestampHeader = withoutHeaderRule.lineSequence().firstOrNull()
            ?.let { first -> if (dropboxEntryHeaderRegex.matches(first.trim())) withoutHeaderRule.substringAfter(first).trimStart('\n') else withoutHeaderRule }
            ?: withoutHeaderRule
        return withoutTimestampHeader.trim()
    }

    private fun classifyDropboxTag(tag: String): CrashKind = when {
        tag.contains("native_crash") -> CrashKind.NativeCrash
        tag.contains("anr") -> CrashKind.Anr
        tag.contains("wtf") -> CrashKind.Watchdog
        tag.startsWith("system_app_crash") -> CrashKind.SystemAppCrash
        tag.contains("crash") -> CrashKind.JavaCrash
        else -> CrashKind.JavaCrash
    }

    private fun parseDropboxTimestamp(text: String): Long {
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text).time
        }.getOrDefault(0L)
    }

    // ---- B.3: dumpsys meminfo breakdown ---------------------------------------------------

    /** `dumpsys meminfo <pkg>` "App Summary" Pss(KB) breakdown, converted to MB. */
    fun parseMeminfoBreakdown(output: String, packageName: String): MeminfoBreakdown? {
        fun valueFor(label: String): Float? =
            Regex("""(?m)^\s*$label:\s*(\d+)""").find(output)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.div(1024f)

        val totalPss = Regex("""(?m)^\s*TOTAL\s+PSS:\s*(\d+)""").find(output)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.div(1024f)
            ?: Regex("""(?m)^\s*TOTAL:\s*(\d+)""").find(output)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.div(1024f)
        val javaHeap = valueFor("Java Heap")
        val nativeHeap = valueFor("Native Heap")
        val code = valueFor("Code")
        val stack = valueFor("Stack")
        val graphics = valueFor("Graphics")
        val privateOther = valueFor("Private Other")
        val system = valueFor("System")
        if (javaHeap == null && nativeHeap == null && totalPss == null) return null
        return MeminfoBreakdown(
            packageName = packageName,
            javaHeapMb = javaHeap,
            nativeHeapMb = nativeHeap,
            codeMb = code,
            stackMb = stack,
            graphicsMb = graphics,
            privateOtherMb = privateOther,
            systemMb = system,
            totalPssMb = totalPss,
        )
    }

    // ---- B.4: dumpsys batterystats summary -------------------------------------------------

    private val wakelockRegex = Regex("""Wake lock\s+(\S+).*?:\s*([\w. ]+?)\s+realtime(?:\s*\((\d+)\s*times?\))?""", RegexOption.IGNORE_CASE)
    private val alarmRegex = Regex("""Alarm\s+(\S+):\s*(\d+)\s*times?""", RegexOption.IGNORE_CASE)
    private val jobRegex = Regex("""Job\s+(\S+).*?:\s*([\w. ]+?)\s+realtime(?:\s*\((\d+)\s*times?\))?""", RegexOption.IGNORE_CASE)
    private val durationRegex = Regex("""(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+)s)?\s*(?:(\d+)ms)?""", RegexOption.IGNORE_CASE)

    /**
     * Summarizes `dumpsys batterystats --charged [pkg]` into wakelock/alarm/job tables plus an
     * estimated per-package power-drain list. Does not attempt a Battery Historian-style timeline
     * — a sorted table answering "what is holding a wakelock" is the goal (§B.4).
     */
    fun parseBatteryStatsSummary(output: String, packageName: String? = null): BatteryStatsSummary {
        val wakelocks = mutableListOf<BatteryStatsWakelock>()
        val alarms = mutableListOf<BatteryStatsAlarm>()
        val jobs = mutableListOf<BatteryStatsJob>()
        val drain = mutableListOf<BatteryStatsDrain>()

        output.lineSequence().map { it.trim() }.forEach { line ->
            wakelockRegex.find(line)?.let { m ->
                wakelocks += BatteryStatsWakelock(
                    name = m.groupValues[1],
                    packageName = packageName,
                    heldMillis = parseDurationToMillis(m.groupValues[2]),
                    count = m.groupValues[3].toIntOrNull() ?: 1,
                )
                return@forEach
            }
            jobRegex.find(line)?.let { m ->
                jobs += BatteryStatsJob(
                    name = m.groupValues[1],
                    packageName = packageName,
                    durationMillis = parseDurationToMillis(m.groupValues[2]),
                    count = m.groupValues[3].toIntOrNull() ?: 1,
                )
                return@forEach
            }
            alarmRegex.find(line)?.let { m ->
                alarms += BatteryStatsAlarm(name = m.groupValues[1], packageName = packageName, count = m.groupValues[2].toIntOrNull() ?: 0)
                return@forEach
            }
        }

        var inPowerSection = false
        output.lineSequence().forEach { rawLine ->
            if (rawLine.contains("Estimated power use", ignoreCase = true)) {
                inPowerSection = true
                return@forEach
            }
            if (!inPowerSection) return@forEach
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("All ") || trimmed.startsWith("Statistics")) {
                inPowerSection = false
                return@forEach
            }
            val match = Regex("""^([\w.]+)\s*:\s*([\d.]+)""").find(trimmed) ?: return@forEach
            val label = match.groupValues[1]
            if (label.equals("Capacity", true) || label.equals("Computed", true) ||
                label.equals("Actual", true) || label.equals("Screen", true) || label.equals("Uid", true)
            ) {
                return@forEach
            }
            drain += BatteryStatsDrain(packageName = label, percent = match.groupValues[2].toFloatOrNull() ?: 0f)
        }

        return BatteryStatsSummary(wakelocks = wakelocks, alarms = alarms, jobs = jobs, drain = drain, raw = output)
    }

    private fun parseDurationToMillis(text: String): Long {
        val match = durationRegex.find(text) ?: return 0L
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: 0L
        val millis = match.groupValues[4].toLongOrNull() ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun parseLevel(value: String): LogLevel = when (value) {
        "V" -> LogLevel.Verbose
        "D" -> LogLevel.Debug
        "I" -> LogLevel.Info
        "W" -> LogLevel.Warn
        "E" -> LogLevel.Error
        "F" -> LogLevel.Fatal
        else -> LogLevel.Silent
    }

    private fun parseMemoryToken(token: String?): Float? {
        if (token.isNullOrBlank()) return null
        val value = token.dropLastWhile { it.isLetter() }.toFloatOrNull() ?: return null
        return when (token.lastOrNull()?.uppercaseChar()) {
            'G' -> value * 1024f
            'M' -> value
            'K' -> value / 1024f
            else -> value / 1024f
        }
    }

    // ---- D: view hierarchy inspector -----------------------------------------------------

    /**
     * One raw `View.toString()` line under `dumpsys activity top`'s "View Hierarchy:" section,
     * e.g. `android.widget.TextView{97c2680 V.ED..... ........ 60,50-605,120 #1020016 android:id/title}`.
     * [left]/[top]/[right]/[bottom] are in the *parent's* coordinate space, matching
     * `View.mLeft/mTop/mRight/mBottom` — [parseActivityTopHierarchy] accumulates them into
     * screen-absolute bounds while walking the indentation-derived tree.
     */
    private data class RawActivityViewLine(
        val className: String,
        val hashCode: String,
        val flags1: String,
        val flags2: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val resourceId: String?,
        val resourceName: String?,
        val aid: String?,
        val trailing: String?,
    )

    private class RawActivityViewNode(val line: RawActivityViewLine) {
        val children = mutableListOf<RawActivityViewNode>()
    }

    private val activityTopBoundsRegex = Regex("""^(-?\d+),(-?\d+)-(-?\d+),(-?\d+)$""")

    private fun parseActivityTopViewLine(trimmed: String): RawActivityViewLine? {
        val braceStart = trimmed.indexOf('{')
        val braceEnd = trimmed.lastIndexOf('}')
        if (braceStart <= 0 || braceEnd <= braceStart) return null
        val className = trimmed.substring(0, braceStart)
        val inner = trimmed.substring(braceStart + 1, braceEnd)
        val trailingRaw = trimmed.substring(braceEnd + 1).trim()
        val trailing = trailingRaw.removePrefix("[").removeSuffix("]").takeIf { it.isNotBlank() }

        val tokens = inner.split(' ').filter { it.isNotBlank() }
        if (tokens.size < 4) return null
        val hashCode = tokens[0]
        val flags1 = tokens[1]
        val flags2 = tokens[2]
        val bounds = activityTopBoundsRegex.find(tokens[3]) ?: return null
        val (left, top, right, bottom) = bounds.destructured

        var resourceId: String? = null
        var resourceName: String? = null
        var aid: String? = null
        var index = 4
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token.startsWith("aid=") -> {
                    aid = token.removePrefix("aid=")
                    index++
                }
                token.startsWith("#") -> {
                    resourceId = token.removePrefix("#")
                    val next = tokens.getOrNull(index + 1)
                    if (next != null && !next.startsWith("aid=")) {
                        resourceName = next
                        index += 2
                    } else {
                        index++
                    }
                }
                else -> index++
            }
        }

        return RawActivityViewLine(
            className = className,
            hashCode = hashCode,
            flags1 = flags1,
            flags2 = flags2,
            left = left.toInt(),
            top = top.toInt(),
            right = right.toInt(),
            bottom = bottom.toInt(),
            resourceId = resourceId,
            resourceName = resourceName,
            aid = aid,
            trailing = trailing,
        )
    }

    /**
     * Parses the first "View Hierarchy:" block of `dumpsys activity top` into an [AccessibilityNode]
     * tree carrying view classes, ids, and flags that `uiautomator dump` collapses out of Compose
     * semantics trees (§D.3, tier 2). Node identity, text, and content-description are not
     * available from this source — [mergeViewHierarchy] enriches a uiautomator tree with it instead
     * of replacing it, except when the caller explicitly asks for the unmerged tree.
     */
    fun parseActivityTopHierarchy(output: String): AccessibilityNode? {
        val lines = output.lines()
        val headerIndex = lines.indexOfFirst { it.trim() == "View Hierarchy:" }
        if (headerIndex == -1) return null
        val headerIndent = lines[headerIndex].indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }

        val roots = mutableListOf<RawActivityViewNode>()
        val stack = ArrayDeque<Pair<Int, RawActivityViewNode>>()
        for (lineIndex in (headerIndex + 1) until lines.size) {
            val rawLine = lines[lineIndex]
            if (rawLine.isBlank()) continue
            val indent = rawLine.indexOfFirst { it != ' ' }.let { if (it < 0) rawLine.length else it }
            if (indent <= headerIndent) break
            val parsed = parseActivityTopViewLine(rawLine.trim()) ?: continue
            val node = RawActivityViewNode(parsed)
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
            if (stack.isEmpty()) roots += node else stack.last().second.children += node
            stack.addLast(indent to node)
        }
        val rawRoot = roots.firstOrNull() ?: return null

        var idCounter = 0
        fun build(raw: RawActivityViewNode, parentAbsLeft: Int, parentAbsTop: Int): AccessibilityNode {
            val line = raw.line
            val absLeft = parentAbsLeft + line.left
            val absTop = parentAbsTop + line.top
            val absRight = parentAbsLeft + line.right
            val absBottom = parentAbsTop + line.bottom
            val id = "activity-top.${idCounter++}"
            return AccessibilityNode(
                id = id,
                className = line.className,
                resourceId = line.resourceName,
                text = null,
                contentDescription = null,
                bounds = "[$absLeft,$absTop][$absRight,$absBottom]",
                clickable = line.flags1.getOrNull(6) == 'C',
                longClickable = line.flags1.getOrNull(7) == 'L',
                focusable = line.flags1.getOrNull(1) == 'F',
                focused = line.flags2.getOrNull(1) == 'F',
                enabled = line.flags1.getOrNull(2) == 'E',
                visible = line.flags1.getOrNull(0) == 'V',
                attributes = buildMap {
                    put("view-hash", line.hashCode)
                    put("view-flags1", line.flags1)
                    put("view-flags2", line.flags2)
                    line.resourceId?.let { put("view-resource-id", it) }
                    line.aid?.let { put("view-aid", it) }
                    line.trailing?.let { put("view-activity", it) }
                },
                children = raw.children.map { build(it, absLeft, absTop) },
            )
        }
        return build(rawRoot, 0, 0)
    }

    /**
     * Parses `mScrollY` values from `dumpsys activity top`, keyed by the view instance hash
     * (`ScrollView{abc123` → `abc123`). Used to correct Compose scroll-container overlays.
     */
    fun parseActivityTopScrollOffsets(output: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        var currentHash: String? = null
        val viewLine = Regex("""\b([\w.$]+)\{([0-9a-f]+)""")
        val scrollYRegex = Regex("""mScrollY=(\d+)""")
        for (line in output.lines()) {
            viewLine.find(line)?.let { currentHash = it.groupValues[2] }
            scrollYRegex.find(line)?.let { match ->
                currentHash?.let { hash -> result[hash] = match.groupValues[1].toInt() }
            }
        }
        return result
    }

    fun attachScrollOffsetsFromActivityTop(output: String, root: AccessibilityNode?): AccessibilityNode? {
        if (root == null) return null
        val offsetsByHash = parseActivityTopScrollOffsets(output)
        fun attach(node: AccessibilityNode): AccessibilityNode {
            val hash = node.attributes["view-hash"]
            val scrollY = hash?.let(offsetsByHash::get)
            val attrs = if (scrollY != null) node.attributes + ("scroll-y" to scrollY.toString()) else node.attributes
            return node.copy(attributes = attrs, children = node.children.map(::attach))
        }
        return attach(root)
    }

    /**
     * Merges a `uiautomator dump` tree with a [parseActivityTopHierarchy] tree by matching nodes
     * on exact bounds + class name (§D.3's "technical core"). Matched view-tree attributes (raw
     * flags, native hash, `aid`) are copied into the uiautomator node's `attributes` map with a
     * `view-` prefix, leaving [AccessibilityNode]'s typed fields (from uiautomator) untouched. A
     * uiautomator node with no bounds/class match keeps its own attributes unchanged. Pure and
     * order-independent: each activity-top node is used for at most one match.
     */
    fun mergeViewHierarchy(uiautomatorRoot: AccessibilityNode?, activityTopRoot: AccessibilityNode?): AccessibilityNode? {
        if (activityTopRoot == null) return uiautomatorRoot
        if (uiautomatorRoot == null) return activityTopRoot

        val flatActivityNodes = mutableListOf<AccessibilityNode>()
        fun flatten(node: AccessibilityNode) {
            flatActivityNodes += node
            node.children.forEach(::flatten)
        }
        flatten(activityTopRoot)
        val used = BooleanArray(flatActivityNodes.size)

        fun bestMatchIndex(node: AccessibilityNode): Int {
            var bestIndex = -1
            for (candidateIndex in flatActivityNodes.indices) {
                if (used[candidateIndex]) continue
                val candidate = flatActivityNodes[candidateIndex]
                if (candidate.bounds != node.bounds) continue
                if (bestIndex == -1) bestIndex = candidateIndex
                if (candidate.className == node.className) {
                    bestIndex = candidateIndex
                    break
                }
            }
            return bestIndex
        }

        fun mergeNode(node: AccessibilityNode): AccessibilityNode {
            val mergedChildren = node.children.map(::mergeNode)
            val matchIndex = bestMatchIndex(node)
            if (matchIndex == -1) return node.copy(children = mergedChildren)
            used[matchIndex] = true
            val extraAttrs = flatActivityNodes[matchIndex].attributes.filterKeys { it.startsWith("view-") || it == "scroll-y" }
            return node.copy(
                attributes = node.attributes + extraAttrs + mapOf("view-matched" to "true"),
                children = mergedChildren,
            )
        }
        return mergeNode(uiautomatorRoot)
    }

    private val dumpsysWindowHeaderRegex = Regex("""^\s*Window #(\d+) Window\{[0-9a-fA-F]+(?: u-?\d+)? (.*)}:\s*$""")
    private val dumpsysWindowFrameRegex = Regex("""\bframe=(\[-?\d+,-?\d+]\[-?\d+,-?\d+])""")
    private val dumpsysWindowTypeRegex = Regex("""\bty=([A-Za-z_]+)""")
    private val dumpsysWindowDisplayIdRegex = Regex("""\bmDisplayId=(-?\d+)""")
    private val dumpsysWindowVisibleRegex = Regex("""\bisVisible=(true|false)""")
    private val dumpsysWindowOnScreenRegex = Regex("""\bisOnScreen=(true|false)""")

    /**
     * Parses `dumpsys window` / `dumpsys window windows`'s window list into z-ordered
     * [WindowLayerInfo] entries for the layer view (§D.3/D.4). Entries are returned in the
     * dump's own order, which lists windows front-to-back — index 0 is topmost.
     */
    fun parseDumpsysWindow(output: String): List<WindowLayerInfo> {
        val lines = output.lines()
        val headers = lines.withIndex().mapNotNull { (index, line) -> dumpsysWindowHeaderRegex.find(line)?.let { index to it } }
        return headers.mapIndexed { position, (lineIndex, match) ->
            val index = match.groupValues[1].toIntOrNull() ?: position
            val title = match.groupValues[2].trim()
            val blockEnd = headers.getOrNull(position + 1)?.first ?: lines.size
            val block = lines.subList((lineIndex + 1).coerceAtMost(lines.size), blockEnd).joinToString("\n")
            val packageName = title.substringBefore('/', missingDelimiterValue = "")
                .takeIf { title.contains('/') && it.isNotBlank() }
            WindowLayerInfo(
                index = index,
                title = title,
                packageName = packageName,
                displayId = dumpsysWindowDisplayIdRegex.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull(),
                bounds = dumpsysWindowFrameRegex.find(block)?.groupValues?.getOrNull(1),
                windowType = dumpsysWindowTypeRegex.find(block)?.groupValues?.getOrNull(1),
                isVisible = dumpsysWindowVisibleRegex.find(block)?.groupValues?.getOrNull(1)?.toBoolean() ?: false,
                isOnScreen = dumpsysWindowOnScreenRegex.find(block)?.groupValues?.getOrNull(1)?.toBoolean() ?: false,
            )
        }
    }
}
