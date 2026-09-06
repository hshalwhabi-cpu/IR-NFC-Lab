package com.raqi.irnfclab.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import com.raqi.irnfclab.util.Hex

/** إرسال أوامر خام (APDU / إطارات منخفضة المستوى) واستقبال الردود. */
object ApduRunner {

    /** أوامر جاهزة شائعة للتعرّف على البطاقات */
    val PRESETS = linkedMapOf(
        "SELECT PPSE (Visa/MC)" to "00A404000E325041592E5359532E444446303100",
        "SELECT PSE (تلامسي)" to "00A404000E315041592E5359532E444446303100",
        "SELECT NDEF Tag App" to "00A4040007D276000085010100",
        "READ BINARY" to "00B0000000",
        "GET DATA (UID)" to "FFCA000000",
        "GET CPLC" to "80CA9F7F00",
        "SELECT MF" to "00A40000023F00"
    )

    class Result(val request: String, val response: ByteArray, val ms: Long) {
        val sw: String get() =
            if (response.size >= 2) Hex.encode(response.copyOfRange(response.size - 2, response.size), "")
            else "----"
        val data: ByteArray get() =
            if (response.size >= 2) response.copyOfRange(0, response.size - 2) else ByteArray(0)
        val ok: Boolean get() = sw == "9000"

        fun pretty(): String {
            val sb = StringBuilder()
            sb.append("→ ").append(request).append("\n")
            sb.append("← ").append(Hex.encode(response)).append("\n")
            sb.append("   SW = $sw ")
            sb.append(when (sw) {
                "9000" -> "(نجاح ✓)"
                "6A82" -> "(الملف/التطبيق غير موجود)"
                "6A86" -> "(معاملات غير صحيحة P1/P2)"
                "6D00" -> "(الأمر غير مدعوم)"
                "6E00" -> "(فئة غير مدعومة CLA)"
                "6700" -> "(طول خاطئ)"
                else -> ""
            })
            sb.append("   |  ${ms}ms\n")
            if (data.isNotEmpty()) {
                sb.append("   بيانات (${data.size} بايت):\n")
                sb.append(Hex.dump(data).prependIndent("   "))
            }
            return sb.toString()
        }
    }

    /** يفتح اتصال IsoDep وينفّذ سلسلة أوامر ثم يغلق الاتصال. */
    fun runIsoDep(tag: Tag, commandsHex: List<String>): List<Result> {
        val iso = IsoDep.get(tag) ?: throw IllegalStateException("البطاقة لا تدعم IsoDep (ISO 14443-4)")
        val results = ArrayList<Result>()
        iso.connect()
        iso.timeout = 3000
        try {
            for (cmdHex in commandsHex) {
                val cmd = Hex.decode(cmdHex)
                val t0 = System.nanoTime()
                val resp = iso.transceive(cmd)
                val ms = (System.nanoTime() - t0) / 1_000_000
                results.add(Result(Hex.encode(cmd), resp, ms))
            }
        } finally {
            try { iso.close() } catch (e: Exception) {}
        }
        return results
    }

    /** transceive خام على تقنيات أخرى غير IsoDep */
    fun runRaw(tag: Tag, tech: String, cmdHex: String): Result {
        val cmd = Hex.decode(cmdHex)
        val t0 = System.nanoTime()
        val resp: ByteArray = when (tech) {
            "NfcA" -> NfcA.get(tag).let { it.connect(); try { it.transceive(cmd) } finally { it.close() } }
            "NfcV" -> NfcV.get(tag).let { it.connect(); try { it.transceive(cmd) } finally { it.close() } }
            "NfcF" -> NfcF.get(tag).let { it.connect(); try { it.transceive(cmd) } finally { it.close() } }
            else -> throw IllegalArgumentException("تقنية غير مدعومة للإرسال الخام: $tech")
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        return Result(Hex.encode(cmd), resp, ms)
    }
}
