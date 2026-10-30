package com.zimdarselectric.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Typeface as AndroidTypeface
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

object Z {
    val bg = Color(0xFF1C1F21)
    val raised = Color(0xFF2A2F32)
    val line = Color(0xFF3A4044)
    val lineStrong = Color(0xFF50575C)
    val text = Color(0xFFF2F3F3)
    val muted = Color(0xFFA7ADB2)
    val neon = Color(0xFFDCFF34)
    val onNeon = Color(0xFF15190A)
    val warn = Color(0xFFFF8A5B)
    val ok = Color(0xFF4FBF82)
    val info = Color(0xFF8EC5FF)
    val tab = Color(0xFF151719)
    val track = Color(0xFF30353A)
    val neonTint = Color(0xFF262B1C)
}

val Condensed = FontFamily(ComposeTypeface(AndroidTypeface.create("sans-serif-condensed", AndroidTypeface.BOLD)))
val Mono = FontFamily.Monospace

@Composable
fun ZTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Z.neon, onPrimary = Z.onNeon, background = Z.bg, surface = Z.raised,
            onBackground = Z.text, onSurface = Z.text, error = Z.warn,
        ),
        content = content,
    )
}

/** Angled cut on two corners: the "architectural" shape used for buttons and panels. */
fun cut(size: Dp = 10.dp) = CutCornerShape(topEnd = size, bottomStart = size)

val labelStyle = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.2.sp, color = Z.muted)

@Composable
fun Label(text: String, color: Color = Z.muted, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = labelStyle.copy(color = color), modifier = modifier)
}

@Composable
fun Title(text: String, size: Int = 34) {
    Text(text.uppercase(), fontFamily = Condensed, fontSize = size.sp, letterSpacing = 1.sp, color = Z.text, lineHeight = (size * 0.95).sp)
}

/** "01  SECTION NAME ———" header, like sheet numbers on a blueprint. */
@Composable
fun SectionHeader(num: String, name: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        Label(num, color = Z.neon)
        Spacer(Modifier.width(10.dp))
        Label(name)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Z.line))
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            Label(trailing, color = Z.neon, modifier = if (onTrailing != null) Modifier.clickable { onTrailing() } else Modifier)
        }
    }
}

@Composable
fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(Z.line)) }

@Composable
fun NeonButton(text: String, modifier: Modifier = Modifier, small: String? = null, arrow: Boolean = true, onClick: () -> Unit) {
    // Long labels get a smaller size so they never wrap or get cut off.
    val size = when { text.length <= 9 -> 22.sp; text.length <= 13 -> 19.sp; else -> 17.sp }
    Row(
        modifier = modifier.height(if (small != null) 64.dp else 54.dp).background(Z.neon, cut(14.dp)).clickable { onClick() }
            .padding(horizontal = if (arrow) 18.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            if (small != null) Text(small.uppercase(), fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Z.onNeon, maxLines = 1)
            Text(text.uppercase(), fontFamily = Condensed, fontSize = size, letterSpacing = 1.sp, color = Z.onNeon, maxLines = 1, softWrap = false)
        }
        if (arrow) Text("→", fontSize = 22.sp, color = Z.onNeon)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = Z.text, onClick: () -> Unit) {
    Box(
        modifier = modifier.height(48.dp).border(BorderStroke(1.dp, Z.lineStrong), cut(8.dp)).clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.2.sp, color = color) }
}

@Composable
fun StatusDot(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, color = color)
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        shape = cut(8.dp),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Z.text, unfocusedTextColor = Z.text,
            focusedBorderColor = Z.neon, unfocusedBorderColor = Z.lineStrong,
            focusedLabelColor = Z.neon, unfocusedLabelColor = Z.muted, cursorColor = Z.neon,
        ),
    )
}

/** Two-way choice like NEW INSTALL | REPAIR. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, Z.lineStrong)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).height(46.dp).background(if (i == selected) Z.neon else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o.uppercase(), fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.2.sp,
                    color = if (i == selected) Z.onNeon else Z.muted, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun MoneyRow(label: String, value: String, color: Color = Z.text, bold: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), fontFamily = Mono, fontSize = 12.sp, letterSpacing = 0.8.sp, color = if (bold) Z.text else Z.muted)
            Text(value, fontFamily = Mono, fontSize = 13.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        }
        Divider()
    }
}

/** Dropdown of suggestions shown under a text field as you type. */
@Composable
fun SuggestionList(items: List<Pair<String, String>>, onPick: (Int) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Z.bg).border(1.dp, Z.lineStrong)) {
        items.forEachIndexed { i, (title, sub) ->
            Column(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(title, color = Z.text, fontSize = 15.sp)
                if (sub.isNotBlank()) Text(sub.uppercase(), fontFamily = Mono, fontSize = 10.sp, color = Z.muted)
            }
            if (i < items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Z.line))
        }
    }
}
