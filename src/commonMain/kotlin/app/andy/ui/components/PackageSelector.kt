package app.andy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AndroidApp
import app.andy.service.AppService
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Green
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun PackageSelector(
    appsService: AppService,
    serial: String?,
    selectedPackage: String?,
    onSelectedPackageChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    allowAll: Boolean = true,
    placeholder: String = "All",
    buttonPrefix: String = "Pkg: ",
) {
    var expanded by remember { mutableStateOf(false) }
    var installedApps by remember(serial) { mutableStateOf<List<AndroidApp>>(emptyList()) }
    var searchAppQuery by remember { mutableStateOf("") }

    LaunchedEffect(serial, expanded, selectedPackage) {
        if (serial == null) {
            installedApps = emptyList()
            return@LaunchedEffect
        }
        if (expanded || selectedPackage != null) {
            runCatching { appsService.listApps(serial) }
                .onSuccess { apps ->
                    installedApps = apps.sortedWith(
                        compareBy({ it.label?.lowercase() ?: "" }, { it.packageName }),
                    )
                }
        }
    }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = TextPrimary,
            ),
            shape = RoundedCornerShape(AndyRadius.R2),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
            Text("▼", color = TextSecondary, fontSize = 10.sp)
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
                        "Search packages...",
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
                    .height(48.dp),
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
