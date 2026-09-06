package com.raqi.irnfclab.nfc

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.raqi.irnfclab.util.Hex

/**
 * محاكاة بطاقة (Host Card Emulation).
 * يجعل الجوال نفسه يظهر لقارئ NFC آخر كبطاقة:
 *   • تطبيق NDEF Type-4 كامل (SELECT, Capability Container, NDEF file).
 *   • تطبيق AID مخصص يردّ ردًا قابلًا للتعديل من داخل الواجهة.
 *
 * النص المُحاكى يُضبط من واجهة التطبيق عبر HceService.ndefText و customResponseHex.
 */
class HceService : HostApduService() {

    companion object {
        // يُضبط من الواجهة (SharedState) — قيمة افتراضية آمنة
        @Volatile var ndefText: String = "IR & NFC Lab — بطاقة مُحاكاة"
        @Volatile var customResponseHex: String = "9000"
        @Volatile var lastLog: String = ""
        var onApdu: ((String) -> Unit)? = null

        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val CLA_NOT_SUPPORTED = byteArrayOf(0x6E.toByte(), 0x00)

        private val NDEF_AID = Hex.decode("D2760000850101")
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)

        // Capability Container ثابت يشير لملف NDEF حجمه 0x0400 وأقصى قراءة/كتابة
        private val CC = Hex.decode("000F20003B00340406E10400FF00FF")
    }

    private var selectedFile: ByteArray? = null

    private fun buildNdefFile(): ByteArray {
        val msg = TagWriter.textRecord(ndefText, "ar")
        val ndef = android.nfc.NdefMessage(arrayOf(msg)).toByteArray()
        val len = ndef.size
        val out = ByteArray(2 + len)
        out[0] = ((len shr 8) and 0xFF).toByte()
        out[1] = (len and 0xFF).toByte()
        System.arraycopy(ndef, 0, out, 2, len)
        return out
    }

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray {
        if (apdu == null) return CLA_NOT_SUPPORTED
        val hexIn = Hex.encode(apdu)
        log("→ $hexIn")

        // SELECT بالاسم (AID)
        if (apdu.size >= 5 && apdu[0].toInt() and 0xFF == 0x00 &&
            apdu[1].toInt() and 0xFF == 0xA4 && apdu[2].toInt() and 0xFF == 0x04) {
            val lc = apdu[4].toInt() and 0xFF
            if (apdu.size >= 5 + lc) {
                val aid = apdu.copyOfRange(5, 5 + lc)
                return if (aid.contentEquals(NDEF_AID)) {
                    selectedFile = null
                    respond(OK)
                } else {
                    // AID المخصص → الرد القابل للتعديل
                    respondCustom()
                }
            }
        }

        // SELECT بمعرّف ملف (P1=0x00 0x0C أو 0x02 ...)
        if (apdu.size >= 7 && apdu[0].toInt() and 0xFF == 0x00 &&
            apdu[1].toInt() and 0xFF == 0xA4 && (apdu[2].toInt() and 0xFF) == 0x00) {
            val fid = apdu.copyOfRange(5, 7)
            return when {
                fid.contentEquals(CC_FILE_ID) -> { selectedFile = CC_FILE_ID; respond(OK) }
                fid.contentEquals(NDEF_FILE_ID) -> { selectedFile = NDEF_FILE_ID; respond(OK) }
                else -> respond(FILE_NOT_FOUND)
            }
        }

        // READ BINARY
        if (apdu.size >= 5 && apdu[0].toInt() and 0xFF == 0x00 && apdu[1].toInt() and 0xFF == 0xB0) {
            val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            val le = apdu[4].toInt() and 0xFF
            val src = when (selectedFile) {
                CC_FILE_ID -> CC
                NDEF_FILE_ID -> buildNdefFile()
                else -> return respond(FILE_NOT_FOUND)
            }
            if (offset > src.size) return respond(FILE_NOT_FOUND)
            val end = minOf(offset + (if (le == 0) src.size - offset else le), src.size)
            val slice = src.copyOfRange(offset, end)
            return respond(slice + OK)
        }

        return respondCustom()
    }

    private fun respondCustom(): ByteArray {
        val resp = try { Hex.decode(customResponseHex) } catch (e: Exception) { OK }
        return respond(resp)
    }

    private fun respond(data: ByteArray): ByteArray {
        log("← ${Hex.encode(data)}")
        return data
    }

    private fun log(line: String) {
        lastLog = (lastLog + line + "\n").takeLast(4000)
        onApdu?.invoke(line)
    }

    override fun onDeactivated(reason: Int) {
        val r = if (reason == DEACTIVATION_LINK_LOSS) "فقدان الاتصال" else "اختيار تطبيق آخر"
        log("── انتهى: $r ──")
        selectedFile = null
    }
}
