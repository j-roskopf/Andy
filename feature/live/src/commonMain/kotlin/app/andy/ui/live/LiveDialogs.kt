package app.andy.ui.live

import androidx.compose.runtime.Composable
import app.andy.model.BugCaptureDraft

/**
 * Platform-hosted modal for bug capture. Desktop uses a real OS dialog window so Metal/Swing
 * Live surfaces cannot cover it; web keeps an in-page AlertDialog.
 */
@Composable
internal expect fun BugCaptureDialog(onDismiss: () -> Unit, onSave: (BugCaptureDraft) -> Unit)

@Composable
internal expect fun ClipTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit)
