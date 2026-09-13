package com.kaavalan.note.data.instructions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import com.kaavalan.note.data.local.DeliveryReceiptDao
import com.kaavalan.note.data.local.entities.DeliveryReceiptEntity
import com.kaavalan.note.data.person.Person
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class DeliveryService @Inject constructor(@ApplicationContext private val context: Context, private val dao: DeliveryReceiptDao) {
    data class DeliveryRequest(val instructionId: String, val title: String, val body: String, val audience: AudienceRef, val dueAtMs: Long?, val channels: Set<Channel>, val senderName: String, val senderDesignation: String?, val senderDivision: String?)
    enum class Channel { SMS, WHATSAPP }
    data class Result(val recipients: Int, val sent: Int, val failed: Int)
    suspend fun dispatch(request: DeliveryRequest, roster: RosterPicker): Result {
        val recipients = AudienceResolver.resolve(request.audience, roster)
        if (recipients.isEmpty()) return Result(0, 0, 0)
        val wrapped = HeaderTemplate.wrap(body = request.body, inputs = HeaderTemplate.Inputs(senderName = request.senderName, senderDesignation = request.senderDesignation, senderDivision = request.senderDivision, dueAtMs = request.dueAtMs, shortRef = HeaderTemplate.shortRefFor(request.instructionId)))
        val now = Instant.now(); var sent = 0; var failed = 0
        for (person in recipients) {
            val phone = person.phone?.takeIf { it.isNotBlank() }
            if (phone == null) { for (channel in request.channels) { dao.upsert(receipt(request.instructionId, person, channel, DeliveryReceipt.Status.FAILED, "No phone on file", now)); failed++ }; continue }
            for (channel in request.channels) {
                val ok = when (channel) { Channel.SMS -> sendSms(phone, wrapped); Channel.WHATSAPP -> sendWhatsApp(phone, wrapped) }
                dao.upsert(receipt(request.instructionId, person, channel, if (ok) DeliveryReceipt.Status.SENT else DeliveryReceipt.Status.FAILED, if (ok) null else channelFailureReason(channel, phone), now))
                if (ok) sent++ else failed++
            }
        }
        return Result(recipients.size, sent, failed)
    }
    private fun sendSms(phone: String, body: String): Boolean {
        // Apply the same E.164 normalisation as the WhatsApp path: a
        // local number like `9876543210` silently fails to dispatch on
        // some carriers / dual-SIM setups (common in India per AGENTS.md
        // §3.1). The `smsto:` URI accepts both E.164 and the local form,
        // but normalising at the entry point keeps the two channels in
        // lockstep.
        val e164 = normaliseToE164(phone)
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$e164")).apply { putExtra("sms_body", body); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return fireIntent(intent)
    }
    private fun sendWhatsApp(phone: String, body: String): Boolean {
        // `https://wa.me/<number>` only routes to a contact when `<number>` is in
        // E.164 format (e.g. `919876543210` for India). Contacts frequently store
        // local numbers without the country code (`9876543210`), so normalise
        // before building the URL. `PhoneNumberUtils.formatNumberToE164`
        // requires a `defaultCountryIso`; we resolve it from the SIM/network
        // country (most accurate for the user's home country), then the
        // system locale, then fall back to "IN" — the deployment's primary
        // market per AGENTS.md §3.1.
        val e164 = normaliseToE164(phone)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$e164?text=" + Uri.encode(body))).apply { setPackage("com.whatsapp"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return fireIntent(intent)
    }
    private fun normaliseToE164(phone: String): String {
        val digits = phone.filter { it.isDigit() || it == '+' }
        if (digits.startsWith("+")) return digits.drop(1) // wa.me expects digits-only after the country code
        val countryIso = resolveCountryIso()
        if (countryIso.isBlank()) return digits
        return try {
            PhoneNumberUtils.formatNumberToE164(digits, countryIso) ?: digits
        } catch (e: IllegalArgumentException) {
            // PhoneNumberUtils throws IAE for unparseable numbers (e.g. too
            // short, wrong digit count). Surface the original local
            // format so the dispatch still fires; the WhatsApp lookup
            // will just fail to resolve and we record a clear FAILED
            // receipt downstream.
            Log.d("DeliveryService", "formatNumberToE164 failed (country=$countryIso)", e)
            digits
        }
    }
    /**
     * Country-code resolution order: SIM (most accurate for the home
     * country) > network (the country the device is currently attached
     * to) > system locale (the user's preferred UI language) > the
     * deployment's primary market ("IN" per AGENTS.md §3.1).
     *
     * `TelephonyManager` is safe to instantiate without READ_PHONE_STATE;
     * both `getSimCountryIso()` and `getNetworkCountryIso()` return "" on
     * missing SIM / no service. We do NOT call `READ_PHONE_STATE`-gated
     * APIs.
     */
    private fun resolveCountryIso(): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val fromSim = tm?.simCountryIso?.uppercase().orEmpty()
        if (fromSim.isNotBlank()) return fromSim
        val fromNetwork = tm?.networkCountryIso?.uppercase().orEmpty()
        if (fromNetwork.isNotBlank()) return fromNetwork
        val fromLocale = context.resources.configuration.locales[0].country.uppercase()
        if (fromLocale.isNotBlank()) return fromLocale
        return "IN"
    }
    private fun channelFailureReason(channel: Channel, phone: String): String = when (channel) {
        Channel.WHATSAPP -> "WhatsApp not reachable at $phone (check E.164 country code)"
        Channel.SMS -> "SMS not available"
    }
    private fun fireIntent(intent: Intent): Boolean {
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try { context.startActivity(intent); true }
        catch (e: ActivityNotFoundException) { false }
        catch (_: SecurityException) {
            Log.w("DeliveryService", "startActivity rejected for ${intent.action}")
            false
        }
    }
    private fun receipt(instructionId: String, person: Person, channel: Channel, status: DeliveryReceipt.Status, error: String?, at: Instant): DeliveryReceiptEntity = DeliveryReceiptEntity(id = newDeliveryReceiptId(), instructionId = instructionId, recipientPersonId = person.id, recipientName = person.name, recipientDesignation = person.designation, recipientPhone = person.phone, channel = channel.name, status = status.name, errorMessage = error, sentAt = at.toString())
}
