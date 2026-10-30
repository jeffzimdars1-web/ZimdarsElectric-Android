package com.zimdarselectric.app.ui

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.Nav
import com.zimdarselectric.app.Page
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.QuoteLine
import com.zimdarselectric.app.data.Settings
import com.zimdarselectric.app.data.computeTotals
import com.zimdarselectric.app.data.money
import com.zimdarselectric.app.data.qtyText
import com.zimdarselectric.app.data.scheduleText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun Long.toLocal(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), zone)
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val shortTime = DateTimeFormatter.ofPattern("h:mma", Locale.US)

/** Date + start time + hours on site, on the quote. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePicker(q: Quote, onSave: (Quote) -> Unit) {
    val context = LocalContext.current
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<LocalDate?>(null) }
    fun withStart(start: Long?, hours: Double = q.hoursOnSite, hasTime: Boolean = q.hasTime) =
        q.copy(startAt = start, hoursOnSite = hours, hasTime = hasTime, scheduled = scheduleText(start, if (hours > 0) hours else q.laborHours, hasTime))

    Row(Modifier.fillMaxWidth().background(Z.raised).clickable { pickDate = true }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label("Job date + start time")
            Text(q.startAt?.let { scheduleText(it, q.blockHours, q.hasTime) } ?: "Tap to schedule", color = if (q.startAt == null) Z.muted else Z.text, fontSize = 16.sp)
        }
        Text("▾", color = Z.neon, fontSize = 18.sp)
    }
    if (q.startAt != null) {
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField("Hours on site", q.hoursOnSite, "hos${q.id}", Modifier.weight(1f)) { onSave(withStart(q.startAt, it)) }
            Column(Modifier.weight(1f)) {
                Label("Add to phone calendar", color = Z.neon, modifier = Modifier.clickable {
                    val start = q.startAt ?: return@clickable
                    val end = start + (q.blockHours.takeIf { it > 0 } ?: 1.0).times(3_600_000).toLong()
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                            .putExtra(CalendarContract.Events.TITLE, "${q.number} · ${q.title.ifBlank { "Job" }} · ${q.customerName}")
                            .putExtra(CalendarContract.Events.EVENT_LOCATION, q.site)
                            .putExtra(CalendarContract.Events.DESCRIPTION, q.notes)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }.padding(vertical = 8.dp))
                Label("Clear date", color = Z.warn, modifier = Modifier.clickable { onSave(withStart(null)) }.padding(vertical = 8.dp))
            }
        }
        if (q.hoursOnSite == 0.0 && q.laborHours > 0) Text("Using the ${qtyText(q.laborHours)} labor hours until you set this.", color = Z.muted, fontSize = 12.sp)
    }

    if (pickDate) {
        // The date picker works in UTC midnight; convert to the local day.
        val initial = (q.startAt?.toLocal()?.toLocalDate() ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { pickTime = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                pickDate = false
            }) { Text("NEXT: TIME", color = Z.neon) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("CANCEL", color = Z.muted) } }) {
            DatePicker(state = state)
        }
    }
    pickTime?.let { day ->
        val now = q.startAt?.takeIf { q.hasTime }?.toLocal()?.toLocalTime() ?: LocalTime.of(8, 0)
        val state = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = false)
        androidx.compose.material3.AlertDialog(onDismissRequest = { pickTime = null },
            containerColor = Z.raised,
            title = { Text("START TIME · ${day.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)).uppercase()}", fontFamily = Condensed, fontSize = 20.sp, color = Z.text) },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = {
                val start = day.atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli()
                onSave(withStart(start, hasTime = true)); pickTime = null
                if (android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("SAVE", color = Z.neon) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { pickTime = null }) { Text("CANCEL", color = Z.muted) }
                    TextButton(onClick = {
                        onSave(withStart(day.atStartOfDay(zone).toInstant().toEpochMilli(), hasTime = false)); pickTime = null
                        if (android.os.Build.VERSION.SDK_INT >= 33) askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("NO SET TIME", color = Z.text) }
                }
            })
    }
}

private fun statusColorFor(s: String) = statusColor(s)

/** Month grid with jobs marked on their days, plus the chosen day's jobs underneath. */
fun LazyListScope.calendarItems(vm: AppViewModel, nav: Nav, quotes: List<Quote>, lines: List<QuoteLine>, settings: Settings) {
    val ui = vm.quotesUi
    val scheduled = quotes.filter { it.startAt != null && it.status != com.zimdarselectric.app.data.Status.DECLINED }
    val byDay = scheduled.groupBy { it.startAt!!.toLocal().toLocalDate() }
    item {
        CalendarMonth(ui.month, ui.day, byDay, onMonth = { ui.month = it }, onDay = { ui.day = it })
        Spacer(Modifier.height(14.dp))
        val title = ui.day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US))
        SectionHeader("", title, (byDay[ui.day]?.size ?: 0).let { if (it == 0) null else "$it job${if (it == 1) "" else "s"}" })
        if (byDay[ui.day].isNullOrEmpty()) Text("Nothing scheduled this day.", color = Z.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        GhostButton("+ New job on ${ui.day.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US))}", Modifier.fillMaxWidth()) {
            vm.newQuoteAt(ui.day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()) { nav.open(Page.EditQuote(it)) }
        }
        Spacer(Modifier.height(6.dp))
    }
    items(byDay[ui.day].orEmpty().sortedWith(compareBy({ it.hasTime }, { it.startAt })), key = { "cal${it.id}" }) { q ->
        val t = computeTotals(q, lines.filter { it.quoteId == q.id }, settings)
        val start = q.startAt!!.toLocal()
        val end = start.plusMinutes((q.blockHours * 60).toLong())
        Row(Modifier.fillMaxWidth().clickable { nav.open(Page.EditQuote(q.id)) }.padding(vertical = 12.dp)) {
            Column(Modifier.width(78.dp)) {
                Text(if (q.hasTime) start.format(timeFmt) else "Any time", color = Z.text, fontFamily = Condensed, fontSize = 18.sp)
                if (q.hasTime && q.blockHours > 0) Label("to ${end.format(timeFmt)}")
                else if (q.blockHours > 0) Label("about ${qtyText(q.blockHours)} hr")
            }
            Box(Modifier.width(3.dp).height(54.dp).background(statusColorFor(q.status)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(q.title.ifBlank { "Untitled job" }, color = Z.text, fontSize = 16.sp)
                Label("${q.number} · ${q.customerName.ifBlank { "No customer" }} · ${money(t.total)}")
                if (q.site.isNotBlank()) Text(q.site, color = Z.muted, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                StatusDot(q.status, statusColorFor(q.status))
            }
        }
        Divider()
    }
    item { Spacer(Modifier.height(24.dp)) }
}

@Composable
private fun CalendarMonth(month: YearMonth, selected: LocalDate, byDay: Map<LocalDate, List<Quote>>, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("‹", color = Z.neon, fontSize = 26.sp, modifier = Modifier.clickable { onMonth(month.minusMonths(1)) }.padding(horizontal = 14.dp, vertical = 4.dp))
        Text("${month.month.getDisplayName(TextStyle.FULL, Locale.US)} ${month.year}".uppercase(), fontFamily = Condensed, fontSize = 22.sp,
            color = Z.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Text("›", color = Z.neon, fontSize = 26.sp, modifier = Modifier.clickable { onMonth(month.plusMonths(1)) }.padding(horizontal = 14.dp, vertical = 4.dp))
    }
    if (month != YearMonth.from(today)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Label("Back to today", color = Z.neon, modifier = Modifier.clickable { onMonth(YearMonth.from(today)); onDay(today) }.padding(vertical = 4.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, color = Z.muted, fontSize = 11.sp, textAlign = TextAlign.Center, fontFamily = Mono, modifier = Modifier.weight(1f)) }
    }
    Spacer(Modifier.height(4.dp))
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value % 7 // Sunday first
    val cells = lead + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (c in 0 until 7) {
                    val n = r * 7 + c - lead + 1
                    if (n < 1 || n > month.lengthOfMonth()) { Spacer(Modifier.weight(1f)); continue }
                    val day = month.atDay(n)
                    val jobs = byDay[day].orEmpty().sortedBy { it.startAt }
                    val isSel = day == selected
                    Column(
                        Modifier.weight(1f).aspectRatio(0.78f)
                            .background(if (isSel) Z.neonTint else Z.raised)
                            .border(1.dp, if (isSel) Z.neon else if (day == today) Z.lineStrong else Z.raised)
                            .clickable { onDay(day) }.padding(3.dp),
                    ) {
                        Text("$n", color = if (day == today) Z.neon else Z.text, fontSize = 12.sp, fontFamily = Condensed)
                        jobs.take(2).forEach { q ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(5.dp).background(statusColor(q.status)))
                                Spacer(Modifier.width(2.dp))
                                Text(if (!q.hasTime) "job" else q.startAt!!.toLocal().format(shortTime).lowercase().replace(":00", "").removeSuffix("m"),
                                    color = Z.text, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                        if (jobs.size > 2) Text("+${jobs.size - 2}", color = Z.muted, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}
