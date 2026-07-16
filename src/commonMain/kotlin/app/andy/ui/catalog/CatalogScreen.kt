package app.andy.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.andy.ui.components.ConfirmationDialog
import app.andy.ui.components.PendingConfirmation
import app.andy.model.AvdProfile
import app.andy.model.SystemImage
import app.andy.model.SystemImageBadge
import app.andy.model.VirtualDevice
import app.andy.service.AvdService
import app.andy.ui.components.Button
import app.andy.ui.components.MonoCell
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.TableHeader
import app.andy.ui.components.TableRow
import app.andy.ui.components.TextField
import app.andy.ui.components.Toolbar
import app.andy.ui.components.fieldColors
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Panel
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CatalogScreen(avd: AvdService) {
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
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Panel, RoundedCornerShape(AndyRadius.R3))
                    .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Rust)
                    Text("Loading system image catalog…", color = TextSecondary, fontFamily = MonoFont, fontSize = 12.sp)
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
                    TextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search package, variant, api", color = TextSecondary) }, modifier = Modifier.fillMaxWidth().height(54.dp), textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace), colors = fieldColors())
                    Text(
                        if (loading) "Refreshing… · ${filtered.size} of ${images.size} images" else "${filtered.size} of ${images.size} images",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    TableHeader(listOf("API" to 70.dp, "Variant" to 340.dp, "ABI" to 130.dp, "State" to 120.dp, "Action" to 100.dp, "Package" to 1.dp))
                    LazyColumn {
                        items(filtered.take(240)) { image ->
                            TableRow {
                                MonoCell(image.api, 70.dp, TextPrimary)
                                Row(Modifier.width(340.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(image.variant, color = TextPrimary, fontFamily = MonoFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    image.badges.forEach { badge -> SystemImageBadgeChip(badge) }
                                }
                                MonoCell(image.abi, 130.dp, TextSecondary)
                                MonoCell(if (image.installed) "Installed" else "Available", 120.dp, if (image.installed) Green else TextSecondary)
                                Box(Modifier.width(100.dp)) {
                                    if (image.installed) {
                                        OutlinedButton(
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
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) { Text("Delete", fontSize = 11.sp) }
                                    } else {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    status = "Downloading ${image.packageId}..."
                                                    val result = avd.installSystemImage(image.packageId)
                                                    status = if (result.isSuccess) result.stdout.ifBlank { "Installed ${image.packageId}" } else result.stderr.ifBlank { result.stdout }
                                                    refresh()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            colors = primaryButtonColors(),
                                        ) { Text("Download", fontSize = 11.sp) }
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
            .background(color.copy(alpha = 0.22f), RoundedCornerShape(AndyRadius.Pill))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(AndyRadius.Pill))
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
            .background(Panel, RoundedCornerShape(AndyRadius.R3))
            .border(1.dp, Border, RoundedCornerShape(AndyRadius.R3))
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
        TextButton(onClick = onReset) { Text("Reset filters", color = AndyColors.Orange, fontFamily = MonoFont, fontSize = 12.sp) }
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
                Checkbox(checked = option in selected, onCheckedChange = { checked -> onChange(if (checked) selected + option else selected - option) }, modifier = Modifier.size(28.dp))
                Text(option, color = TextPrimary, fontFamily = MonoFont, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}

private fun VirtualDevice.referencesImage(image: SystemImage): Boolean {
    val haystack = (listOfNotNull(target, abi, path) + config.values).joinToString(" ").lowercase()
    return image.packageId.lowercase() in haystack ||
        ("android-${image.api}" in haystack && image.variant.lowercase() in haystack && image.abi.lowercase() in haystack)
}
