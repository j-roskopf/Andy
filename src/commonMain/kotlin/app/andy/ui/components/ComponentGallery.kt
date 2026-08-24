package app.andy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.model.AgentKind
import app.andy.ui.agents.AgentMark
import app.andy.ui.theme.AndySpace
import app.andy.ui.theme.DisplayFont
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

@Composable
internal fun ComponentGallery(modifier: Modifier = Modifier) {
    var tabIndex by remember { mutableIntStateOf(0) }
    var segmentIndex by remember { mutableIntStateOf(0) }
    var fieldValue by remember { mutableStateOf("Sample input") }
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AndySpace.Space5),
    ) {
        GallerySection("Buttons") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {}) { Text("Primary") }
                OutlinedButton(onClick = {}) { Text("Secondary") }
                TextButton(onClick = {}) { Text("Ghost") }
            }
        }
        GallerySection("Status") {
            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                StatusTag("working", StatusDotVariant.Info, pulsing = true)
                StatusTag("done", StatusDotVariant.Success)
                StatusTag("blocked", StatusDotVariant.Warning)
                StatusTag("error", StatusDotVariant.Error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                Badge("Beta", variant = BadgeVariant.Info)
                Badge("Offline", variant = BadgeVariant.Neutral)
            }
        }
        GallerySection("Avatar") {
            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                Avatar(size = AvatarSize.Xsm, name = "Andy")
                Avatar(size = AvatarSize.Sm, name = "Jo")
                AgentMark(AgentKind.Cursor)
                Avatar(size = AvatarSize.Md, name = "On")
            }
        }
        GallerySection("Cards") {
            Row(horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
                Card(
                    modifier = Modifier.weight(1f),
                    elevation = CardElevation.Low,
                ) {
                    Text("Default card", color = TextPrimary, fontFamily = DisplayFont, fontSize = 14.sp)
                    Text("Border + surface raised", color = TextSecondary, fontFamily = DisplayFont, fontSize = 12.sp)
                }
                Card(
                    modifier = Modifier.weight(1f),
                    variant = CardVariant.Muted,
                ) {
                    Text("Muted card", color = TextPrimary, fontFamily = DisplayFont, fontSize = 14.sp)
                }
            }
        }
        GallerySection("Empty state") {
            EmptyState(
                title = "No items yet",
                description = "Create one to populate this pane.",
                compact = true,
                actions = {
                    OutlinedButton(onClick = {}) { Text("Create item") }
                },
            )
        }
        GallerySection("Inputs") {
            TextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
                placeholder = { Text("Placeholder", color = TextSecondary) },
                colors = fieldColors(),
            )
        }
        GallerySection("Tabs") {
            TabBar(
                tabs = listOf("Overview", "Sessions", "Settings"),
                selectedIndex = tabIndex,
                onSelect = { tabIndex = it },
            )
            SegmentedControl(
                options = listOf("Auto", "720p", "1080p"),
                selectedIndex = segmentIndex,
                onSelect = { segmentIndex = it },
            )
        }
        GallerySection("Chat") {
            Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space1 + ChatBubbleGroupPullUp)) {
                ChatMessageBubble(
                    sender = ChatBubbleSender.User,
                    group = ChatBubbleGroup.First,
                ) {
                    ChatBubbleText("Grouped user bubble")
                }
                ChatMessageBubble(
                    sender = ChatBubbleSender.User,
                    group = ChatBubbleGroup.Last,
                ) {
                    ChatBubbleText("Second message in stack")
                }
                ChatMessageBubble(sender = ChatBubbleSender.Assistant, variant = ChatBubbleVariant.Ghost) {
                    ChatBubbleText("Assistant reply without heavy chrome")
                }
            }
        }
        GallerySection("Feedback") {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AndySpace.Space3),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AndySpace.Space3),
                ) {
                    Spinner(spinnerSize = SpinnerSize.Md)
                    ProgressBar(
                        modifier = Modifier.weight(1f),
                        value = 62f,
                        showValueLabel = true,
                    )
                }
                ProgressBar(modifier = Modifier.fillMaxWidth(), indeterminate = true)
                Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space2)) {
                    Skeleton(Modifier.fillMaxWidth().height(12.dp))
                    Skeleton(Modifier.fillMaxWidth(0.6f).height(12.dp))
                }
            }
        }
        GallerySection("Tooltip") {
            Tooltip(text = "Schedule runs while you sleep") {
                Text(
                    "Hover for tooltip",
                    color = TextSecondary,
                    fontFamily = DisplayFont,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(AndySpace.Space2),
                )
            }
        }
    }
}

@Composable
private fun GallerySection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AndySpace.Space3)) {
        Text(
            title,
            color = TextPrimary,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        content()
    }
}
