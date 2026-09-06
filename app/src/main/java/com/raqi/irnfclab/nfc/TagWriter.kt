package com.raqi.irnfclab.nfc

import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.nio.charset.Charset

/** كتابة رسائل NDEF إلى التاقات، بالإضافة إلى القفل والتنسيق. */
object TagWriter {

    fun textRecord(text: String, lang: String = "ar"): NdefRecord {
        val langBytes = lang.toByteArray(Charsets.US_ASCII)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + langBytes.size + textBytes.size)
        payload[0] = langBytes.size.toByte() // بت التشفير 0 = UTF-8
        System.arraycopy(langBytes, 0, payload, 1, langBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + langBytes.size, textBytes.size)
        return NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload)
    }

    fun uriRecord(uri: String): NdefRecord = NdefRecord.createUri(Uri.parse(uri))

    fun mimeRecord(mime: String, data: ByteArray): NdefRecord =
        NdefRecord.createMime(mime, data)

    fun externalRecord(domain: String, type: String, data: ByteArray): NdefRecord =
        NdefRecord.createExternal(domain, type, data)

    /** يشغّل تطبيقًا معينًا عند اللمس (Android Application Record) */
    fun appRecord(packageName: String): NdefRecord =
        NdefRecord.createApplicationRecord(packageName)

    /**
     * يكتب الرسالة على التاق. يُنسّق التاق تلقائيًا إن كان فارغًا.
     * @return رسالة النجاح
     */
    fun write(tag: Tag, message: NdefMessage): String {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) throw IllegalStateException("التاق محمي ضد الكتابة")
                val size = message.toByteArray().size
                if (size > ndef.maxSize)
                    throw IllegalStateException("الرسالة $size بايت تتجاوز سعة التاق ${ndef.maxSize} بايت")
                ndef.writeNdefMessage(message)
                return "تمت الكتابة بنجاح ($size بايت من ${ndef.maxSize})"
            } finally {
                try { ndef.close() } catch (e: Exception) {}
            }
        }
        val formatable = NdefFormatable.get(tag)
            ?: throw IllegalStateException("التاق لا يدعم NDEF")
        formatable.connect()
        try {
            formatable.format(message)
            return "تم تنسيق التاق والكتابة عليه بنجاح"
        } finally {
            try { formatable.close() } catch (e: Exception) {}
        }
    }

    /** قفل التاق نهائيًا (لا رجعة!) — يجعله للقراءة فقط. */
    fun makeReadOnly(tag: Tag): String {
        val ndef = Ndef.get(tag) ?: throw IllegalStateException("التاق لا يدعم NDEF")
        ndef.connect()
        try {
            if (!ndef.canMakeReadOnly()) throw IllegalStateException("هذا التاق لا يدعم القفل الدائم")
            return if (ndef.makeReadOnly()) "تم قفل التاق نهائيًا (للقراءة فقط)"
                   else "فشل القفل"
        } finally {
            try { ndef.close() } catch (e: Exception) {}
        }
    }
}
