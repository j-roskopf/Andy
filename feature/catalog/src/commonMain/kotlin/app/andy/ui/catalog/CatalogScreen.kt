package app.andy.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.andy.ui.components.AndyCheckbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import app.andy.ui.components.TextButton
import app.andy.ui.components.accentTextButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.LabeledField
import app.andy.ui.components.PendingConfirmation
import app.andy.model.AvdProfile
import app.andy.model.IosDeviceType
import app.andy.model.IosRuntime
import app.andy.model.SystemImage
import app.andy.model.SystemImageBadge
import app.andy.model.VirtualDevice
import app.andy.service.AvdService
import app.andy.service.IosDeviceService
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.mutedTextButtonColors
import app.andy.ui.components.PanelCard
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
import app.andy.ui.components.ThinkingOrb
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyShape
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.PaneDividerTint
import app.andy.ui.theme.Cyan
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CatalogScreen(avd: AvdService, iosDevices: IosDeviceService? = null, iosMode: Boolean = false) {
    if (iosMode && iosDevices != null) {
        IosCatalogScreen(iosDevices)
        return
    }
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<SystemImage>>(emptyList()) }
    var avds by remember { mutableStateOf<List<VirtualDevice>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<AvdProfile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var hasLoaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }

    var selectedVariants by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedAbis by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedStates by remember { mutableStateOf<Set<String>>(emptySet()) }
    var apiRange by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            status = ""
            try {
                images = avd.listSystemImages()
                avds = avd.listVirtualDevices()
                profiles = avd.listProfiles()
                hasLoaded = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.message?.takeIf { it.isNotBlank() } ?: "Failed to refresh catalog"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    val showInitialLoading = loading && !hasLoaded

    val apiBounds = remember(images) {
        val levels = images.map { it.apiLevel }
        (levels.minOrNull() ?: 10)..(levels.maxOrNull() ?: 36)
    }
    val availableVariants = remember(images) { images.map { it.variant }.distinct().sorted() }
    val availableAbis = remember(images) { images.map { it.abi }.distinct().sorted() }
    val activeRange = apiRange ?: apiBounds.first.toFloat()..apiBounds.last.toFloat()

    val filtered = images.filter { image ->
        val inRange = image.apiLevel.toFloat() in activeRange
        val variantOk = selectedVariants.isEmpty() || image.variant in selectedVariants
        val abiOk = selectedAbis.isEmpty() || image.abi in selectedAbis
        val stateOk = selectedStates.isEmpty() || (if (image.installed) "Installed" in selectedStates else "Available" in selectedStates)
        val queryOk = query.isBlank() || image.packageId.contains(query, true) || image.variant.contains(query, true) || image.api.contains(query, true)
        inRange && variantOk && abiOk && stateOk && queryOk
    }

    fun resetFilters() {
        selectedVariants = emptySet()
        selectedAbis = emptySet()
        selectedStates = emptySet()
        apiRange = null
        query = ""
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar(
            title = "System images",
            subtitle = when {
                showInitialLoading -> "Loading system images…"
                else -> "${images.count { it.installed }} installed · ${avds.size} AVDs · ${profiles.size} profiles"
            },
            onPrimary = { refresh() },
            primaryLabel = if (loading) "Loading" else "Refresh catalog",
            primaryEnabled = !loading,
        )
        if (status.isNotBlank()) Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (showInitialLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThinkingOrb(
                        size = 48.dp,
                        color = Cyan,
                        contentDescription = "Loading catalog",
                    )
                    Text(
                        "Loading system image catalog…",
                        color = TextSecondary,
                        fontFamily = MonoFont,
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CatalogFilterSidebar(
                    apiBounds = apiBounds,
                    activeRange = activeRange,
                    onRangeChange = { apiRange = it },
                    availableVariants = availableVariants,
                    selectedVariants = selectedVariants,
                    onVariantsChange = { selectedVariants = it },
                    availableAbis = availableAbis,
                    selectedAbis = selectedAbis,
                    onAbisChange = { selectedAbis = it },
                    selectedStates = selectedStates,
                    onStatesChange = { selectedStates = it },
                    onReset = { resetFilters() },
                )
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search package, variant, api", color = TextSecondary) }, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AndyLayout.FieldHeight), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    Text(
                        if (loading) "Refreshing… · ${filtered.size} of ${images.size} images" else "${filtered.size} of ${images.size} images",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    TableHeader(listOf("API" to 70.dp, "Variant" to 340.dp, "ABI" to 130.dp, "State" to 120.dp, "Action" to 116.dp, "Package" to 1.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered.take(240)) { image ->
                            TableRow {
                                MonoCell(image.api, 70.dp, TextPrimary)
                                Row(Modifier.width(340.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(image.variant, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    image.badges.forEach { badge -> SystemImageBadgeChip(badge) }
                                }
                                MonoCell(image.abi, 130.dp, TextSecondary)
                                MonoCell(if (image.installed) "Installed" else "Available", 120.dp, if (image.installed) Green else TextSecondary)
                                Box(Modifier.width(116.dp), contentAlignment = Alignment.CenterStart) {
                                    if (image.installed) {
                                        TextButton(
                                            onClick = {
                                                val refs = avds.filter { it.referencesImage(image) }
                                                if (refs.isNotEmpty()) {
                                                    status = "Blocked: used by ${refs.joinToString { it.name }}"
                                                } else {
                                                    pendingConfirmation = PendingConfirmation("Delete system image?", image.packageId) {
                                                        scope.launch {
                                                            val result = avd.uninstallSystemImage(image.packageId)
                                                            status = if (result.isSuccess) result.stdout.ifBlank { "Deleted ${image.packageId}" } else result.stderr.ifBlank { result.stdout }
                                                            refresh()
                                                        }
                                                    }
                                                }
                                            },
                                            colors = mutedTextButtonColors(),
                                        ) { Text("Delete", fontSize = 12.sp) }
                                    } else {
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    status = "Downloading ${image.packageId}..."
                                                    val result = avd.installSystemImage(image.packageId)
                                                    status = if (result.isSuccess) result.stdout.ifBlank { "Installed ${image.packageId}" } else result.stderr.ifBlank { result.stdout }
                                                    refresh()
                                                }
                                            },
                                            colors = accentTextButtonColors(),
                                        ) { Text("Download", fontSize = 12.sp) }
                                    }
                                }
                                MonoCell(image.packageId, 1.dp, TextSecondary, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        pendingConfirmation?.let { confirmation ->
            ConfirmationDialog(confirmation, onDismiss = { pendingConfirmation = null }, onConfirm = {
                pendingConfirmation = null
                confirmation.onConfirm()
            })
        }
    }
}

@Composable
private fun SystemImageBadgeChip(badge: SystemImageBadge) {
    val color = when (badge) {
        SystemImageBadge.PlayStore, SystemImageBadge.Tv -> AndyColors.Blue
        SystemImageBadge.Wear, SystemImageBadge.Automotive -> AndyColors.Orange
    }
    Box(
        Modifier.height(18.dp)
            .background(color.copy(alpha = 0.22f), AndyShape.Interactive)
            .border(1.dp, color.copy(alpha = 0.55f), AndyShape.Interactive)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(badge.label, color = color, fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 9.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun CatalogFilterSidebar(
    apiBounds: IntRange,
    activeRange: ClosedFloatingPointRange<Float>,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    availableVariants: List<String>,
    selectedVariants: Set<String>,
    onVariantsChange: (Set<String>) -> Unit,
    availableAbis: List<String>,
    selectedAbis: Set<String>,
    onAbisChange: (Set<String>) -> Unit,
    selectedStates: Set<String>,
    onStatesChange: (Set<String>) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.width(240.dp).fillMaxHeight()
            .background(Panel, AndyShape.Interactive)
            .border(1.dp, PaneDividerTint, AndyShape.Interactive)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val isAll = activeRange.start <= apiBounds.first && activeRange.endInclusive >= apiBounds.last
            Text("API LEVEL${if (isAll) "  (all)" else "  (${activeRange.start.toInt()} – ${activeRange.endInclusive.toInt()})"}", color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            if (apiBounds.first < apiBounds.last) {
                RangeSlider(
                    value = activeRange,
                    onValueChange = onRangeChange,
                    valueRange = apiBounds.first.toFloat()..apiBounds.last.toFloat(),
                    steps = (apiBounds.last - apiBounds.first - 1).coerceAtLeast(0),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${apiBounds.first}", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                    Text("${apiBounds.last}", color = TextSecondary, fontFamily = MonoFont, fontSize = 10.sp)
                }
            }
        }
        FilterCheckboxGroup("VARIANT", availableVariants, selectedVariants, onVariantsChange)
        FilterCheckboxGroup("ABI", availableAbis, selectedAbis, onAbisChange)
        FilterCheckboxGroup("STATE", listOf("Installed", "Available"), selectedStates, onStatesChange)
        TextButton(onClick = onReset, colors = accentTextButtonColors()) {
            Text("Reset filters", fontFamily = MonoFont, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FilterCheckboxGroup(
    title: String,
    options: List<String>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = TextSecondary, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().height(26.dp).clickable {
                    onChange(if (option in selected) selected - option else selected + option)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AndyCheckbox(
                    checked = option in selected,
                    onCheckedChange = { checked -> onChange(if (checked) selected + option else selected - option) },
                )
                Text(option, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}

@Composable
private fun IosCatalogScreen(iosDevices: IosDeviceService) {
    val scope = rememberCoroutineScope()
    var deviceTypes by remember { mutableStateOf<List<IosDeviceType>>(emptyList()) }
    var runtimes by remember { mutableStateOf<List<IosRuntime>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                deviceTypes = iosDevices.listDeviceTypes()
                runtimes = iosDevices.listRuntimes()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.message ?: "Failed to load iOS catalog"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Toolbar(
            title = "iOS runtimes & device types",
            subtitle = if (loading) "Loading…" else "${runtimes.count { it.isAvailable }} runtimes installed · ${deviceTypes.size} device types",
            onPrimary = { refresh() },
            primaryLabel = if (loading) "Loading" else "Refresh",
            primaryEnabled = !loading,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        downloading = true
                        status = "Downloading iOS platform… this can take several minutes"
                        val result = iosDevices.downloadPlatform()
                        status = if (result.isSuccess) result.stdout.ifBlank { "Platform download complete" } else result.stderr.ifBlank { result.stdout }
                        downloading = false
                        refresh()
                    }
                },
                enabled = !downloading,
                colors = primaryButtonColors(),
            ) { Text(if (downloading) "Downloading…" else "Download iOS platform") }
            OutlinedButton(onClick = { showCreateDialog = true }) { Text("Create simulator") }
        }
        if (status.isNotBlank()) {
            Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runtimes", color = TextPrimary, fontWeight = FontWeight.Bold)
                TableHeader(listOf("Name" to 160.dp, "Version" to 90.dp, "State" to 100.dp, "Identifier" to 1.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(runtimes) { runtime ->
                        TableRow {
                            MonoCell(runtime.name, 160.dp, TextPrimary)
                            MonoCell(runtime.version ?: "-", 90.dp, TextSecondary)
                            MonoCell(if (runtime.isAvailable) "Installed" else "Unavailable", 100.dp, if (runtime.isAvailable) Green else TextSecondary)
                            MonoCell(runtime.identifier, 1.dp, TextSecondary, Modifier.weight(1f))
                        }
                    }
                    if (runtimes.isEmpty() && !loading) {
                        item { Text("No runtimes found. Install Xcode or download a platform above.", color = TextSecondary, fontSize = 12.sp) }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Device types", color = TextPrimary, fontWeight = FontWeight.Bold)
                TableHeader(listOf("Name" to 160.dp, "Family" to 100.dp, "Identifier" to 1.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(deviceTypes.take(300)) { type ->
                        TableRow {
                            MonoCell(type.name, 160.dp, TextPrimary)
                            MonoCell(type.productFamily ?: "-", 100.dp, TextSecondary)
                            MonoCell(type.identifier, 1.dp, TextSecondary, Modifier.weight(1f))
                        }
                    }
                    if (deviceTypes.isEmpty() && !loading) {
                        item { Text("No device types found.", color = TextSecondary, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
    if (showCreateDialog) {
        CreateSimulatorDialog(
            deviceTypes = deviceTypes,
            runtimes = runtimes,
            iosDevices = iosDevices,
            onDismiss = { showCreateDialog = false },
            onCreated = { message ->
                status = message
                showCreateDialog = false
            },
        )
    }
}

@Composable
internal fun CreateSimulatorDialog(
    deviceTypes: List<IosDeviceType>,
    runtimes: List<IosRuntime>,
    iosDevices: IosDeviceService,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Andy iPhone") }
    var selectedType by remember { mutableStateOf(deviceTypes.firstOrNull()) }
    var selectedRuntime by remember { mutableStateOf(runtimes.firstOrNull { it.isAvailable }) }
    var status by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PanelCard(
            modifier = Modifier.width(480.dp),
            contentPadding = PaddingValues(AndySpace.Space7),
            verticalArrangement = Arrangement.spacedBy(AndySpace.Space5),
        ) {
            Text("Create iOS simulator", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            LabeledField("Name", name, { name = it }, Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Device type", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                LazyColumn(Modifier.heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(deviceTypes) { type ->
                        FilterPill(type.name, type == selectedType, Rust) { selectedType = type }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Runtime", color = TextSecondary, fontFamily = MonoFont, fontSize = 11.sp)
                LazyColumn(Modifier.heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(runtimes.filter { it.isAvailable }) { runtime ->
                        FilterPill(runtime.name, runtime == selectedRuntime, Rust) { selectedRuntime = runtime }
                    }
                }
            }
            if (status.isNotBlank()) {
                Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = {
                        val type = selectedType ?: return@Button
                        scope.launch {
                            creating = true
                            val result = iosDevices.createSimulator(name, type.identifier, selectedRuntime?.identifier)
                            creating = false
                            if (result.isSuccess) {
                                onCreated("Created $name".let { result.stdout.ifBlank { it } })
                            } else {
                                status = result.stderr.ifBlank { result.stdout }
                            }
                        }
                    },
                    enabled = !creating && selectedType != null && name.isNotBlank(),
                    colors = primaryButtonColors(),
                ) { Text(if (creating) "Creating…" else "Create") }
            }
        }
    }
}

private fun VirtualDevice.referencesImage(image: SystemImage): Boolean {
    val haystack = (listOfNotNull(target, abi, path) + config.values).joinToString(" ").lowercase()
    val packageId = image.packageId.lowercase()
    val slashPackageId = packageId.replace(';', '/')
    return packageId in haystack ||
        slashPackageId in haystack ||
        ("android-${image.api}" in haystack && image.variant.lowercase() in haystack && image.abi.lowercase() in haystack)
}
