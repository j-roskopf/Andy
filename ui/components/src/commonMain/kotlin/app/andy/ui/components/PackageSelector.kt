package app.andy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AndroidApp
import app.andy.service.AppService
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyLayout
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun PackageSelector(
    appsService: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowAll: Boolean = true,
    placeholder: String = "All",
    buttonPrefix: String = "Pkg: ",
    autoSelectForeground: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var installedApps by remember(serial) { mutableStateOf<List<AndroidApp>>(emptyList()) }
    var searchAppQuery by remember { mutableStateOf("") }
    var loadingPackages by remember(serial) { mutableStateOf(false) }
    var resolvingCurrent by remember { mutableStateOf(false) }
    var autoSelectAttempted by remember(serial) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = loadingPackages || resolvingCurrent

    LaunchedEffect(serial, autoSelectForeground) {
        if (!autoSelectForeground || serial == null || autoSelectAttempted) return@LaunchedEffect
        autoSelectAttempted = true
        if (selectedPackage != null) return@LaunchedEffect
        resolvingCurrent = true
        try {
            val focused = runCatching { appsService.focusedPackage(serial) }.getOrNull()
                ?.takeUnless { it.isNoiseForegroundPackage() }
            if (focused != null) onSelectedPackageChange(focused)
        } finally {
            resolvingCurrent = false
        }
    }

    LaunchedEffect(serial, expanded) {
        if (serial == null) {
            installedApps = emptyList()
            loadingPackages = false
            return@LaunchedEffect
        }
        if (!expanded) return@LaunchedEffect
        loadingPackages = true
        try {
            runCatching { appsService.listApps(serial) }
                .onSuccess { apps ->
                    installedApps = apps.sortedWith(
                        compareBy({ it.label?.lowercase() ?: "" }, { it.packageName }),
                    )
                }
        } finally {
            loadingPackages = false
        }
    }

    fun selectCurrentApp() {
        val currentSerial = serial ?: return
        scope.launch {
            resolvingCurrent = true
            try {
                val focused = runCatching { appsService.focusedPackage(currentSerial) }.getOrNull()
                if (focused != null) {
                    onSelectedPackageChange(focused)
                    expanded = false
                    searchAppQuery = ""
                }
            } finally {
                resolvingCurrent = false
            }
        }
    }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AndyRadius.Control),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            val selectedApp = installedApps.firstOrNull { it.packageName == selectedPackage }
            val label = selectedApp?.label ?: selectedPackage ?: placeholder
            Text(
                "$buttonPrefix$label",
                color = TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            if (busy) {
                Spinner(spinnerSize = SpinnerSize.Sm)
            } else {
                Text("▼", color = TextSecondary, fontSize = 10.sp)
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AndyColors.Neutral750,
            modifier = Modifier.width(320.dp),
        ) {
            TextField(
                value = searchAppQuery,
                onValueChange = { searchAppQuery = it },
                placeholder = {
                    Text(
                        if (loadingPackages) "Loading packages…" else "Search packages...",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .defaultMinSize(minHeight = AndyLayout.FieldHeight),
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 13.sp),
                colors = fieldColors(),
            )

            Spacer(Modifier.height(4.dp))

            val filteredApps = installedApps.filter {
                searchAppQuery.isBlank() ||
                    it.packageName.contains(searchAppQuery, true) ||
                    it.label?.contains(searchAppQuery, true) == true
            }

            Box(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column {
                    if (serial != null) {
                        DropdownMenuItem(
                            enabled = !resolvingCurrent,
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "Current app",
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (resolvingCurrent) {
                                        Spinner(spinnerSize = SpinnerSize.Sm)
                                    }
                                }
                            },
                            onClick = { selectCurrentApp() },
                        )
                    }

                    if (allowAll) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "All Packages",
                                    color = if (selectedPackage == null) Green else TextPrimary,
                                    fontWeight = if (selectedPackage == null) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                onSelectedPackageChange(null)
                                expanded = false
                                searchAppQuery = ""
                            },
                        )
                    }

                    if (loadingPackages && installedApps.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Spinner(spinnerSize = SpinnerSize.Md)
                            Text("Loading packages…", color = TextSecondary, fontSize = 12.sp)
                        }
                    }

                    filteredApps.forEach { app ->
                        val isSelected = app.packageName == selectedPackage
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        app.label ?: app.packageName,
                                        color = if (isSelected) Green else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                    )
                                    if (app.label != null) {
                                        Text(
                                            app.packageName,
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                            fontFamily = MonoFont,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSelectedPackageChange(app.packageName)
                                expanded = false
                                searchAppQuery = ""
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun String.isNoiseForegroundPackage(): Boolean =
    startsWith("com.android.systemui")
