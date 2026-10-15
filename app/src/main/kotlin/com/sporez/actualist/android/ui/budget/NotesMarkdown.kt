package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.util.Locale

private val directivePrefixes = listOf("#template", "#goal", "#cleanup")

private fun isDirective(line: String): Boolean {
    val normalized = line.trim().lowercase(Locale.ROOT)
    return directivePrefixes.any { normalized.startsWith(it) }
}

/**
 * Basic Markdown for notes: `**bold**`, `*italic*` / `_italic_`, `` `code` ``, `[links](https://…)` and `-` / `*` / `1.`
 * lists. Template directive lines (`#template …`) are shown exactly as written, never interpreted.
 */
fun markdownText(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    lines.forEachIndexed { index, line ->
        if (index > 0) append("\n")
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        when {
            isDirective(line) -> append(line)
            trimmed.length > 2 && (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) -> {
                append(" ".repeat(minOf(indent, 8)))
                append("• ")
                appendInline(trimmed.substring(2), linkColor)
            }
            else -> {
                val number = Regex("^(\\d{1,3})\\. (.+)$").find(trimmed)
                if (number != null) {
                    append(" ".repeat(minOf(indent, 8)))
                    append(number.groupValues[1] + ". ")
                    appendInline(number.groupValues[2], linkColor)
                } else {
                    appendInline(line, linkColor)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInline(s: String, linkColor: Color) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '*' && s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(s.substring(i + 2, end), linkColor) }
                i = end + 2
                continue
            }
        } else if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit())) {
            val end = s.indexOf(c, i + 1)
            if (end > i + 1 && !s[end - 1].isWhitespace() && (c == '*' || end + 1 >= s.length || !s[end + 1].isLetterOrDigit())) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(s.substring(i + 1, end), linkColor) }
                i = end + 1
                continue
            }
        } else if (c == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        } else if (c == '[') {
            val close = s.indexOf("](", i + 1)
            val end = if (close > i) s.indexOf(')', close + 2) else -1
            if (close > i + 1 && end > close + 2) {
                val url = s.substring(close + 2, end).trim()
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    withLink(LinkAnnotation.Url(url, styles)) { append(s.substring(i + 1, close)) }
                    i = end + 1
                    continue
                }
            }
        }
        append(c)
        i++
    }
}

/** A note rendered with [markdownText]. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = MaterialTheme.colorScheme.onSurface) {
    val link = MaterialTheme.colorScheme.primary
    val rendered = remember(text, link) { markdownText(text, link) }
    Text(rendered, modifier = modifier, style = style, color = color)
}

/** Actualist's collapsible "Supported Markdown" help under a note editor. */
@Composable
fun MarkdownHelp(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp).testTag("notes-markdown-help"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Supported Markdown", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Hide Markdown help" else "Show Markdown help")
        }
        if (expanded) {
            Column(Modifier.testTag("notes-markdown-examples")) {
                Text("**bold** → Bold", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("*italic* → Italic", style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("- item → • item (lists)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("[text](https://example.com) → link", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
