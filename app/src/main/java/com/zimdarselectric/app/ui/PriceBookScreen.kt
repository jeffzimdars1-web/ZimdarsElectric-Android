package com.zimdarselectric.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.PriceBook
import com.zimdarselectric.app.data.PriceBookStore
import kotlinx.coroutines.launch

@Composable
fun PriceBookScreen(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var book by remember { mutableStateOf(PriceBookStore.cached(context)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try { book = PriceBookStore.refresh(context, settings.signKey) }
            catch (e: Exception) { error = e.message?.takeIf { it.isNotBlank() && e is IllegalStateException } ?: "No connection. Showing the copy saved on this phone." }
            loading = false
        }
    }
    // Always check for a newer copy when opened.
    LaunchedEffect(settings.signKey) { if (settings.signKey.isNotBlank()) refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Z.raised, cut()).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("←", color = Z.text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(if (loading) "Checking for updates…" else book?.updated?.takeIf { it.isNotBlank() }?.let { "Updated $it" } ?: "Price book")
                Text((book?.title ?: "Price Book").uppercase(), fontFamily = Condensed, fontSize = 24.sp, color = Z.text, maxLines = 1)
            }
            Label(if (loading) "…" else "Refresh", color = Z.neon, modifier = Modifier.clickable { refresh() }.padding(8.dp))
        }
        Divider()
        val b = book
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = Z.warn, fontSize = 13.sp) }
            if (b == null) {
                Spacer(Modifier.height(40.dp))
                Text(if (loading) "Loading your price book…" else "Your price book hasn't downloaded yet. Make sure the signing key is in Settings and you have signal, then tap Refresh.",
                    color = Z.muted, fontSize = 15.sp)
                return@Column
            }
            if (b.intro.isNotBlank()) { Spacer(Modifier.height(14.dp)); Text(b.intro, color = Z.text, fontSize = 15.sp) }

            if (b.rates.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    b.rates.forEach { r ->
                        Column(Modifier.weight(1f).background(Z.raised).padding(10.dp)) {
                            Text(r.value, fontFamily = Condensed, fontSize = 24.sp, color = Z.neon)
                            Text(r.label, color = Z.muted, fontSize = 12.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
            if (b.steps.isNotEmpty()) {
                SectionHeader("", "How to charge")
                b.steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Box(Modifier.size(22.dp).background(Z.neon), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Z.onNeon, fontFamily = Condensed, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(s, color = Z.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                }
            }

            b.sections.forEach { sec ->
                SectionHeader("", sec.title)
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Spacer(Modifier.weight(1f))
                    sec.columns.forEach { c -> Label(c, modifier = Modifier.widthIn(min = 64.dp)) }
                }
                sec.items.forEach { item -> PriceRow(item, sec.columns.size) }
            }

            b.callouts.forEach { c ->
                Spacer(Modifier.height(18.dp))
                val color = if (c.kind == "warn") Z.warn else Z.neon
                Column(Modifier.fillMaxWidth().border(1.dp, Z.line).background(Z.raised).padding(12.dp)) {
                    Text(c.title, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    c.bullets.forEach { Text("•  $it", color = Z.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                }
            }

            if (b.examples.isNotEmpty()) SectionHeader("", "Examples")
            b.examples.forEach { ex ->
                Column(Modifier.fillMaxWidth().background(Z.raised).padding(12.dp)) {
                    Label(ex.title, color = Z.neon)
                    Spacer(Modifier.height(6.dp))
                    ex.lines.forEach { (k, v) -> MoneyRow(k, v) }
                    ex.total?.let { (k, v) -> MoneyRow(k, v, Z.neon, bold = true) }
                    if (ex.note.isNotBlank()) Text(ex.note, color = Z.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(10.dp))
            }
            if (b.footer.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(b.footer, color = Z.muted, fontSize = 13.sp) }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun PriceRow(item: PriceBook.Item, columns: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Z.text, fontSize = 16.sp)
            if (item.note.isNotBlank()) Text(item.note, color = Z.muted, fontSize = 12.sp, lineHeight = 15.sp)
        }
        (0 until maxOf(columns, item.prices.size)).forEach { i ->
            Text(item.prices.getOrNull(i) ?: "", color = if (i == 0) Z.text else Z.muted, fontFamily = Condensed, fontSize = 19.sp,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 64.dp))
        }
    }
    Divider()
}
