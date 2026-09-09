package com.kaavalan.note.data.person

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only device directory. Never limits results or writes to the phone's contacts. */
@Singleton
open class ContactSyncService @Inject constructor(@ApplicationContext private val context: Context) {
    data class ContactCandidate(val key: String, val displayName: String, val phone: String) {
        private val phoneDigits = phone.filter(Char::isDigit)

        fun matches(query: String): Boolean {
            val text = query.trim()
            val digits = text.filter(Char::isDigit)
            return text.isEmpty() || displayName.contains(text, ignoreCase = true) ||
                phone.contains(text, ignoreCase = true) ||
                (digits.isNotEmpty() && text.all { it.isDigit() || it in "+()- ." } && phoneDigits.contains(digits))
        }
    }

    sealed interface LoadResult {
        data class Loaded(val contacts: List<ContactCandidate>) : LoadResult
        data object PermissionRequired : LoadResult
        data object Failed : LoadResult
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun fetchContactCandidates(): LoadResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext LoadResult.PermissionRequired
        try {
            val resolver = context.contentResolver
            // Phone alone omits every name-only/email-only contact.
            val names = linkedMapOf<Long, String>()
            val contacts = resolver.query(Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY),
                null, null, "${Contacts.SORT_KEY_PRIMARY} ASC") ?: return@withContext LoadResult.Failed
            contacts.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Contacts._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(Contacts.DISPLAY_NAME_PRIMARY)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    names[cursor.getLong(idIndex)] = cursor.getString(nameIndex)?.trim().orEmpty()
                }
            }
            val numbers = mutableMapOf<Long, LinkedHashMap<String, String>>()
            val phones = resolver.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER),
                null, null, "${Phone._ID} ASC") ?: return@withContext LoadResult.Failed
            phones.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)
                val phoneIndex = cursor.getColumnIndexOrThrow(Phone.NUMBER)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(idIndex)
                    val phone = cursor.getString(phoneIndex)?.trim().orEmpty()
                    if (id in names && phone.isNotEmpty()) {
                        // Deduplicate only within a contact. Different people may share a station number.
                        val canonical = phone.filterNot { it.isWhitespace() || it in "()- ." }
                        numbers.getOrPut(id) { linkedMapOf() }.putIfAbsent(canonical, phone)
                    }
                }
            }
            LoadResult.Loaded(names.flatMap { (id, name) ->
                val choices = numbers[id]
                if (choices.isNullOrEmpty()) listOf(ContactCandidate("$id:none", name, ""))
                else choices.map { (canonical, number) -> ContactCandidate("$id:$canonical", name.ifBlank { number }, number) }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            LoadResult.PermissionRequired
        } catch (_: Exception) {
            // Never expose partial results as success or log errors containing contact data.
            LoadResult.Failed
        }
    }
}
