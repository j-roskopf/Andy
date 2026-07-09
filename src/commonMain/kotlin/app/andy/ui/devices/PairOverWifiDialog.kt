package app.andy.ui.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.loadImageBitmap
import app.andy.model.MdnsService
import app.andy.model.PairedWifiDevice
import app.andy.service.DeviceService
import app.andy.ui.components.Button
import app.andy.ui.components.FilterPill
import app.andy.ui.components.LabeledField
import app.andy.ui.components.OutlinedButton
import app.andy.ui.components.primaryButtonColors
import app.andy.ui.theme.AndyRadius
import app.andy.ui.theme.Border
import app.andy.ui.theme.Panel
import app.andy.ui.theme.PanelSoft
import app.andy.ui.theme.Rust
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class WifiPairTab { Discover, PairingCode, Qr }

@Composable
internal fun PairOverWifiDialog(
    devices: DeviceService,
    onDismiss: () -> Unit,
    onPaired: (PairedWifiDevice, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(WifiPairTab.Discover) }
    var status by remember { mutableStateOf("Ready") }
    var busy by remember { mutableStateOf(false) }
    var mdnsReady by remember { mutableStateOf<Boolean?>(null) }
    var mdnsServices by remember { mutableStateOf<List<MdnsService>>(emptyList()) }
    var pairingEndpoint by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectEndpoint by remember { mutableStateOf("") }
    var discoverCode by remember { mutableStateOf("") }
    var selectedPairing by remember { mutableStateOf<MdnsService?>(null) }
    val qrName = remember { "Andy${(100000..999999).random()}" }
    val qrPassword = remember { (100000..999999).random().toString() }
    var qrBytes by remember { mutableStateOf<ByteArray?>(null) }
    val qrBitmap = remember(qrBytes) { qrBytes?.let { loadImageBitmap(it) } }

    LaunchedEffect(Unit) {
        mdnsReady = runCatching { devices.mdnsAvailable() }.getOrDefault(false)
        qrBytes = devices.generatePairingQr("WIFI:T:ADB;S:$qrName;P:$qrPassword;;")
    }

    LaunchedEffect(tab, mdnsReady) {
        if (mdnsReady != true) return@LaunchedEffect
        if (tab != WifiPairTab.Discover && tab != WifiPairTab.Qr) return@LaunchedEffect
        while (true) {
            mdnsServices = runCatching { devices.listMdnsServices() }.getOrDefault(emptyList())
            if (tab == WifiPairTab.Qr) {
                val pairing = mdnsServices.firstOrNull { it.isPairing && it.instanceName.equals(qrName, ignoreCase = true) }
                if (pairing != null && !busy) {
                    busy = true
                    status = "QR service found — pairing..."
                    val (result, paired) = pairThenConnect(
                        devices = devices,
                        pairHost = pairing.host,
                        pairPort = pairing.port,
                        code = qrPassword,
                        preferredInstanceName = qrName,
                    )
                    if (paired != null && result.isSuccess) {
                        onPaired(paired, result.stdout.ifBlank { "Paired via QR" })
                        return@LaunchedEffect
                    }
                    status = result.stderr.ifBlank { result.stdout }.ifBlank { "QR pairing failed" }
                    busy = false
                }
            }
            delay(2_000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Pair over Wi‑Fi", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.width(720.dp).heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("Discover", tab == WifiPairTab.Discover, Rust) { tab = WifiPairTab.Discover }
                    FilterPill("Pairing code", tab == WifiPairTab.PairingCode, Rust) { tab = WifiPairTab.PairingCode }
                    FilterPill("QR code", tab == WifiPairTab.Qr, Rust) { tab = WifiPairTab.Qr }
                }
                Text(status, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (mdnsReady == false) {
                    Text(
                        "adb mdns is unavailable on this SDK. Use the Pairing code tab with IP:port from Wireless debugging.",
                        color = Rust,
                        fontSize = 12.sp,
                    )
                }
                when (tab) {
                    WifiPairTab.Discover -> {
                        val pairing = mdnsServices.filter { it.isPairing }
                        val connectable = mdnsServices.filter { it.isConnect }
                        if (pairing.isEmpty() && connectable.isEmpty()) {
                            Text(
                                "No mDNS services yet. On the phone open Developer options → Wireless debugging, then Pair device with pairing code.",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        if (pairing.isNotEmpty()) {
                            Text("Pairable", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            pairing.forEach { service ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(PanelSoft, RoundedCornerShape(AndyRadius.R4))
                                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(service.instanceName, color = TextPrimary, fontWeight = FontWeight.Bold)
                                        Text("${service.endpoint} · ${service.serviceType}", color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { selectedPairing = service },
                                        enabled = !busy,
                                    ) { Text(if (selectedPairing == service) "Selected" else "Select") }
                                }
                            }
                            if (selectedPairing != null) {
                                LabeledField("6-digit pairing code", discoverCode, {
                                    discoverCode = it.filter { ch -> ch.isDigit() }.take(6)
                                }, Modifier.fillMaxWidth(), placeholder = "123456")
                                Button(
                                    onClick = {
                                        val service = selectedPairing ?: return@Button
                                        scope.launch {
                                            busy = true
                                            status = "Pairing ${service.instanceName}..."
                                            val (result, paired) = pairThenConnect(
                                                devices = devices,
                                                pairHost = service.host,
                                                pairPort = service.port,
                                                code = discoverCode,
                                                preferredInstanceName = service.instanceName,
                                            )
                                            if (paired != null && result.isSuccess) {
                                                onPaired(paired, result.stdout.ifBlank { "Paired ${service.instanceName}" })
                                            } else {
                                                status = result.stderr.ifBlank { result.stdout }.ifBlank { "Pairing failed" }
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = !busy && discoverCode.length == 6,
                                    colors = primaryButtonColors(),
                                ) { Text("Pair & connect") }
                            }
                        }
                        if (connectable.isNotEmpty()) {
                            Text("Already connectable", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            connectable.forEach { service ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(PanelSoft, RoundedCornerShape(AndyRadius.R4))
                                        .border(1.dp, Border, RoundedCornerShape(AndyRadius.R4))
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(service.instanceName, color = TextPrimary, fontWeight = FontWeight.Bold)
                                        Text(service.endpoint, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                busy = true
                                                status = "Connecting ${service.endpoint}..."
                                                val result = devices.connect(service.host, service.port)
                                                if (result.isSuccess) {
                                                    val paired = PairedWifiDevice(
                                                        id = "wifi-${System.currentTimeMillis()}-${service.endpoint.hashCode().toUInt()}",
                                                        displayName = service.instanceName,
                                                        mdnsInstanceName = service.instanceName,
                                                        lastEndpoint = service.endpoint,
                                                        pairedAtMillis = System.currentTimeMillis(),
                                                    )
                                                    onPaired(paired, result.stdout.ifBlank { "Connected to ${service.endpoint}" })
                                                } else {
                                                    status = result.stderr.ifBlank { result.stdout }.ifBlank { "Connect failed" }
                                                    busy = false
                                                }
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("Connect") }
                                }
                            }
                        }
                    }
                    WifiPairTab.PairingCode -> {
                        Text(
                            "On the phone: Developer options → Wireless debugging → Pair device with pairing code.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                        LabeledField("Pairing IP:port", pairingEndpoint, { pairingEndpoint = it }, Modifier.fillMaxWidth(), placeholder = "192.168.1.20:37199")
                        LabeledField("6-digit code", pairingCode, {
                            pairingCode = it.filter { ch -> ch.isDigit() }.take(6)
                        }, Modifier.fillMaxWidth(), placeholder = "123456")
                        LabeledField(
                            "Connect IP:port (optional)",
                            connectEndpoint,
                            { connectEndpoint = it },
                            Modifier.fillMaxWidth(),
                            placeholder = "192.168.1.20:5555",
                        )
                        Button(
                            onClick = {
                                val pairTarget = parseHostPort(pairingEndpoint)
                                if (pairTarget == null) {
                                    status = "Enter a valid pairing IP:port"
                                    return@Button
                                }
                                val connectTarget = connectEndpoint.trim().takeIf { it.isNotBlank() }?.let(::parseHostPort)
                                if (connectEndpoint.isNotBlank() && connectTarget == null) {
                                    status = "Enter a valid connect IP:port"
                                    return@Button
                                }
                                scope.launch {
                                    busy = true
                                    status = "Pairing ${pairTarget.first}:${pairTarget.second}..."
                                    val (result, paired) = pairThenConnect(
                                        devices = devices,
                                        pairHost = pairTarget.first,
                                        pairPort = pairTarget.second,
                                        code = pairingCode,
                                        preferredConnect = connectTarget,
                                    )
                                    if (paired != null && result.isSuccess) {
                                        onPaired(paired, result.stdout.ifBlank { "Paired and connected" })
                                    } else {
                                        status = result.stderr.ifBlank { result.stdout }.ifBlank { "Pairing failed" }
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && pairingCode.length == 6 && pairingEndpoint.isNotBlank(),
                            colors = primaryButtonColors(),
                        ) { Text("Pair & connect") }
                    }
                    WifiPairTab.Qr -> {
                        Text(
                            "On the phone: Developer options → Wireless debugging → Pair device with QR code, then scan this code.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(220.dp)
                                    .background(Color.White, RoundedCornerShape(AndyRadius.R4))
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (qrBitmap != null) {
                                    Image(bitmap = qrBitmap, contentDescription = "ADB pairing QR", modifier = Modifier.fillMaxSize())
                                } else {
                                    CircularProgressIndicator(color = Rust, strokeWidth = 2.dp)
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Name: $qrName", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text("Password: $qrPassword", color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                Text(
                                    if (busy) "Pairing..." else "Waiting for phone to advertise pairing service…",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Close") } },
    )
}
