package com.zimdarselectric.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel

/** "3 new phone contacts · tap to sync" banner, and the list to add or skip them. Shows nothing when there are none. */
@Composable
fun NewContactsBanner(vm: AppViewModel) {
    val list by vm.newContacts.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Z.neonTint).clickable { open = true }.padding(12.dp)) {
        Text("${list.size} new phone contact${if (list.size == 1) "" else "s"} · tap to sync", color = Z.neon, fontSize = 15.sp)
        Label("Add them as customers or skip the ones that aren't")
    }
    Spacer(Modifier.height(10.dp))
    if (open) ZDialog("New phone contacts", { open = false },
        confirm = "Add all ${list.size}" to { vm.addAllNewContacts { open = false } },
        dismiss = "Later" to { open = false }) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(list, key = { it.id }) { k ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.name, color = Z.text, fontSize = 16.sp)
                        Label(k.phone)
                    }
                    Label("Add", color = Z.neon, modifier = Modifier.clickable { vm.addContact(k) }.padding(8.dp))
                    Spacer(Modifier.width(4.dp))
                    Label("Skip", color = Z.muted, modifier = Modifier.clickable { vm.skipContact(k) }.padding(8.dp))
                }
                Divider()
            }
        }
        Text("Skipped contacts won't show up again.", color = Z.muted, fontSize = 12.sp)
    }
}
