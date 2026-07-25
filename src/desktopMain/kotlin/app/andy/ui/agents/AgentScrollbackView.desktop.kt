package app.andy.ui.agents

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.ui.theme.MonoFont
import app.andy.ui.theme.TextPrimary

@Composable
internal fun AgentScrollbackView(
    text: String,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    SelectionContainer(modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            color = TextPrimary,
            fontFamily = MonoFont,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}
