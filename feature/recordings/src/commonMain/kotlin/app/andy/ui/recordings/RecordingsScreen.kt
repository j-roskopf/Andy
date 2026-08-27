package app.andy.ui.recordings

import androidx.compose.runtime.Composable
import app.andy.service.AndyServices
import app.andy.ui.bugs.BugsScreen

/** Recordings destination — same library UI as bugs, filtered to screen captures. */
@Composable
fun RecordingsScreen(services: AndyServices) {
    BugsScreen(services, recordings = true)
}
