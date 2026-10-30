package com.zimdarselectric.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.data.Customer
import com.zimdarselectric.app.data.PhoneContacts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "From your phone contacts" matches for [query]. Tapping one turns it into a customer (name, cell, email, address)
 * and hands it to [onPick]. Contacts already saved as customers (same phone) are left out.
 */
@Composable
fun ContactResults(query: String, customers: List<Customer>, onPick: (Customer) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(PhoneContacts.allowed(context)) }
    var results by remember { mutableStateOf(emptyList<PhoneContacts.Contact>()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }

    LaunchedEffect(query, allowed) {
        delay(200) // wait for typing to pause
        val saved = customers.map { c -> c.phone.filter { it.isDigit() }.takeLast(10) }.filter { it.isNotBlank() }.toSet()
        results = if (query.trim().length < 2) emptyList()
                  else PhoneContacts.search(context, query).filter { it.phone.filter { d -> d.isDigit() }.takeLast(10) !in saved }
    }

    if (query.trim().length < 2) return
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))
        Label("From your phone contacts", color = Z.neon)
        if (!allowed) {
            Text("Let the app look up your contacts so you don't have to retype them.", color = Z.muted, fontSize = 13.sp)
            Label("Allow contacts →", color = Z.neon, modifier = Modifier.clickable { ask.launch(android.Manifest.permission.READ_CONTACTS) }.padding(vertical = 8.dp))
            return@Column
        }
        if (results.isEmpty()) Text("No contacts match.", color = Z.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
        results.forEach { r ->
            ListRow(r.name, r.phone, onClick = { scope.launch { onPick(PhoneContacts.toCustomer(context, r)) } })
        }
    }
}
