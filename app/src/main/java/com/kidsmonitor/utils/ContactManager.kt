package com.kidsmonitor.utils

import android.content.Context
import android.provider.ContactsContract

data class ContactData(val name: String, val phoneNumber: String)

class ContactManager(private val context: Context) {

    fun getContacts(): List<ContactData> {
        val contacts = ArrayList<ContactData>()
        val contentResolver = context.contentResolver
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                if (nameIndex >= 0 && numberIndex >= 0) {
                    val name = it.getString(nameIndex) ?: "Unknown"
                    val number = it.getString(numberIndex) ?: ""
                    contacts.add(ContactData(name, number))
                }
            }
        }
        return contacts
    }
}
