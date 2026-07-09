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
                    kind = if (serial.startsWith("emulator-")) DeviceKind.Emulator else DeviceKind.Physical,
                    state = when (stateRaw) {
                        "device" -> DeviceConnectionState.Online
                        "offline" -> DeviceConnectionState.Offline
                        "unauthorized" -> DeviceConnectionState.Unauthorized
                        else -> DeviceConnectionState.Unknown
                    },
                    model = fields["model"],
                    product = fields["product"],
                )
            }
            .toList()
    }

    fun parseBatteryPercent(output: String): Int? {
        return Regex("""level:\s*(\d+)""").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

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

    fun parseWmSize(output: String): String? {
        return Regex("""Physical size:\s*([0-9]+x[0-9]+)""").find(output)?.groupValues?.getOrNull(1)
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
}
