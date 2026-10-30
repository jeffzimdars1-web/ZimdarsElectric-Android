package com.zimdarselectric.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Looks people up in the phone's contacts. Nothing is copied until you pick someone. */
object PhoneContacts {
    data class Contact(val id: Long, val name: String, val phone: String)

    fun allowed(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contacts whose name or number matches [query], one row per person (their first number, mobile preferred). */
    suspend fun search(context: Context, query: String, limit: Int = 15): List<Contact> = withContext(Dispatchers.IO) {
        if (!allowed(context) || query.isBlank()) return@withContext emptyList()
        val q = "%" + query.trim().replace("%", "") + "%"
        val digits = query.filter { it.isDigit() }
        val sel = if (digits.length >= 3) "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ?"
                  else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = if (digits.length >= 3) arrayOf(q, "%$digits%") else arrayOf(q)
        val out = LinkedHashMap<Long, Contact>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            sel, args, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit * 2) {
                val id = c.getLong(0)
                val mobile = c.getInt(3) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                val cur = out[id]
                if (cur == null || mobile) out[id] = Contact(id, c.getString(1) ?: "", c.getString(2) ?: "")
            }
        }
        out.values.take(limit)
    }

    /** Builds a customer from a contact, with their email and home address if the contact has them. */
    suspend fun toCustomer(context: Context, contact: Contact): Customer = withContext(Dispatchers.IO) {
        fun firstOf(mime: String, column: String): String =
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(column),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contact.id.toString(), mime), null)?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        Customer(
            name = contact.name,
            phone = contact.phone,
            email = firstOf(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.ADDRESS),
            address = firstOf(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                .replace("\n", ", "),
        )
    }
}
