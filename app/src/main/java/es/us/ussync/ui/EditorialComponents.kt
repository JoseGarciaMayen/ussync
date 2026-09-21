package es.us.ussync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val EditorialCardShape = RoundedCornerShape(16.dp)
val EditorialButtonShape = RoundedCornerShape(12.dp)

@Composable
fun EditorialCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = EditorialCardShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        content = { Column(content = content) },
    )
}

@Composable
fun EditorialSection(title: String, caption: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action?.invoke()
    }
}

@Composable
fun SubjectBadge(text: String, lavender: Boolean = false) {
    val background = if (lavender) Color(0xFFEDE9FE) else MaterialTheme.colorScheme.tertiaryContainer
    val foreground = if (lavender) Color(0xFF6D28D9) else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(shape = RoundedCornerShape(99.dp), color = background) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

@Composable
fun FileTypeAvatar(filename: String) {
    val label = filename.substringAfterLast('.', "DOC").uppercase().take(4)
    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFEDE9FE), modifier = Modifier.size(42.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF6D28D9), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun EditorialPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = EditorialButtonShape) { Text(text) }
}
