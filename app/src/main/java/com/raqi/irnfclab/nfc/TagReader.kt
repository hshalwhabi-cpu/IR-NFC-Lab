package com.raqi.irnfclab.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.*
import com.raqi.irnfclab.util.Hex
import java.io.IOException

/** قراءة كل ما يمكن استخراجه من تاق NFC بلا صلاحيات خاصة. */
object TagReader {

    fun describe(tag: Tag): String {
        val sb = StringBuilder()
        sb.append("═══ بطاقة NFC ═══\n")
        sb.append("UID        : ").append(Hex.encode(tag.id)).append("\n")
        sb.append("UID (عكسي) : ").append(Hex.encode(Hex.reversed(tag.id))).append("\n")
        sb.append("UID طول    : ").append(tag.id.size).append(" بايت\n")
        sb.append("التقنيات   :\n")
        for (t in tag.techList) sb.append("   • ").append(t.substringAfterLast('.')).append("\n")
        sb.append("\n")

        for (tech in tag.techList) {
            try {
                when (tech) {
                    NfcA::class.java.name -> sb.append(readNfcA(tag))
                    NfcB::class.java.name -> sb.append(readNfcB(tag))
                    NfcF::class.java.name -> sb.append(readNfcF(tag))
                    NfcV::class.java.name -> sb.append(readNfcV(tag))
                    IsoDep::class.java.name -> sb.append(readIsoDep(tag))
                    MifareClassic::class.java.name -> sb.append(readMifareClassic(tag))
                    MifareUltralight::class.java.name -> sb.append(readMifareUltralight(tag))
                    Ndef::class.java.name -> sb.append(readNdef(tag))
                    NdefFormatable::class.java.name ->
                        sb.append("── NdefFormatable ──\nالبطاقة فارغة وقابلة لتنسيق NDEF.\n\n")
                }
            } catch (e: Exception) {
                sb.append("خطأ في قراءة ").append(tech.substringAfterLast('.')).append(": ").append(e.message).append("\n\n")
            }
        }
        return sb.toString()
    }

    private fun readNfcA(tag: Tag): String {
        val a = NfcA.get(tag) ?: return ""
        return "── NfcA (ISO 14443-3A) ──\n" +
            "ATQA : ${Hex.encode(a.atqa)}\n" +
            "SAK  : 0x%02X\n".format(a.sak) +
            "أقصى طول إطار: ${a.maxTransceiveLength} بايت\n\n"
    }

    private fun readNfcB(tag: Tag): String {
        val b = NfcB.get(tag) ?: return ""
        return "── NfcB (ISO 14443-3B) ──\n" +
            "بيانات التطبيق : ${Hex.encode(b.applicationData)}\n" +
            "معلومات البروتوكول: ${Hex.encode(b.protocolInfo)}\n\n"
    }

    private fun readNfcF(tag: Tag): String {
        val f = NfcF.get(tag) ?: return ""
        return "── NfcF (FeliCa) ──\n" +
            "رمز النظام: ${Hex.encode(f.systemCode)}\n" +
            "PMm      : ${Hex.encode(f.manufacturer)}\n\n"
    }

    private fun readNfcV(tag: Tag): String {
        val v = NfcV.get(tag) ?: return ""
        return "── NfcV (ISO 15693) ──\n" +
            "DSFID: 0x%02X\n".format(v.dsfId) +
            "RespFlags: 0x%02X\n\n".format(v.responseFlags)
    }

    private fun readIsoDep(tag: Tag): String {
        val iso = IsoDep.get(tag) ?: return ""
        val sb = StringBuilder("── IsoDep (ISO 14443-4) ──\n")
        sb.append("Historical Bytes : ${Hex.encode(iso.historicalBytes)}\n")
        sb.append("Hi-Layer Response: ${Hex.encode(iso.hiLayerResponse)}\n")
        sb.append("أقصى طول إطار    : ${iso.maxTransceiveLength} بايت\n")
        sb.append("للأوامر اليدوية استخدم تبويب APDU.\n\n")
        return sb.toString()
    }

    private fun readMifareClassic(tag: Tag): String {
        val mc = MifareClassic.get(tag) ?: return ""
        val sb = StringBuilder("── MIFARE Classic ──\n")
        val type = when (mc.type) {
            MifareClassic.TYPE_CLASSIC -> "Classic"
            MifareClassic.TYPE_PLUS -> "Plus"
            MifareClassic.TYPE_PRO -> "Pro"
            else -> "غير معروف"
        }
        sb.append("النوع    : $type\n")
        sb.append("الحجم    : ${mc.size} بايت\n")
        sb.append("القطاعات : ${mc.sectorCount}  |  الكتل: ${mc.blockCount}\n")
        try {
            mc.connect()
            mc.timeout = 800
            val keys = listOf(
                MifareClassic.KEY_DEFAULT,
                MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY,
                MifareClassic.KEY_NFC_FORUM,
                Hex.decode("A0A1A2A3A4A5"),
                Hex.decode("FFFFFFFFFFFF"),
                Hex.decode("000000000000"),
                Hex.decode("D3F7D3F7D3F7")
            )
            for (s in 0 until mc.sectorCount) {
                var authed = false
                var usedKey = ""
                for (k in keys) {
                    if (mc.authenticateSectorWithKeyA(s, k)) { authed = true; usedKey = "A:" + Hex.encode(k, ""); break }
                    if (mc.authenticateSectorWithKeyB(s, k)) { authed = true; usedKey = "B:" + Hex.encode(k, ""); break }
                }
                sb.append("قطاع %02d : ".format(s))
                if (!authed) { sb.append("تعذّرت المصادقة (مفتاح غير معروف)\n"); continue }
                sb.append("مفتاح $usedKey\n")
                val first = mc.sectorToBlock(s)
                for (blk in first until first + mc.getBlockCountInSector(s)) {
                    try {
                        val data = mc.readBlock(blk)
                        sb.append("   كتلة %02d : %s\n".format(blk, Hex.encode(data)))
                    } catch (e: IOException) {
                        sb.append("   كتلة %02d : خطأ قراءة\n".format(blk))
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("تعذّر الاتصال: ${e.message}\n")
        } finally {
            try { mc.close() } catch (e: Exception) {}
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun readMifareUltralight(tag: Tag): String {
        val mu = MifareUltralight.get(tag) ?: return ""
        val sb = StringBuilder("── MIFARE Ultralight ──\n")
        try {
            mu.connect()
            // كل قراءة تُرجع 4 صفحات (16 بايت). نمر بخطوة 4.
            var page = 0
            while (page < 64) {
                try {
                    val data = mu.readPages(page)
                    for (i in 0 until 4) {
                        val off = i * 4
                        if (off + 4 <= data.size)
                            sb.append("صفحة %02d : %s\n".format(page + i, Hex.encode(data.copyOfRange(off, off + 4))))
                    }
                } catch (e: IOException) {
                    break // تجاوزنا نهاية الذاكرة
                }
                page += 4
            }
        } catch (e: Exception) {
            sb.append("تعذّر الاتصال: ${e.message}\n")
        } finally {
            try { mu.close() } catch (e: Exception) {}
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun readNdef(tag: Tag): String {
        val ndef = Ndef.get(tag) ?: return ""
        val sb = StringBuilder("── NDEF ──\n")
        sb.append("النوع        : ${ndef.type}\n")
        sb.append("السعة        : ${ndef.maxSize} بايت\n")
        sb.append("قابل للكتابة : ${if (ndef.isWritable) "نعم" else "لا"}\n")
        val msg: NdefMessage? = ndef.cachedNdefMessage
        if (msg == null) { sb.append("لا توجد رسالة NDEF مخزّنة.\n\n"); return sb.toString() }
        sb.append("عدد السجلات  : ${msg.records.size}\n")
        for ((i, rec) in msg.records.withIndex()) {
            sb.append("  سجل #${i + 1}: ").append(recordSummary(rec)).append("\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    fun recordSummary(rec: NdefRecord): String {
        val tnf = when (rec.tnf) {
            NdefRecord.TNF_WELL_KNOWN -> "معروف"
            NdefRecord.TNF_MIME_MEDIA -> "MIME"
            NdefRecord.TNF_ABSOLUTE_URI -> "URI مطلق"
            NdefRecord.TNF_EXTERNAL_TYPE -> "خارجي"
            NdefRecord.TNF_EMPTY -> "فارغ"
            else -> "أخرى(${rec.tnf})"
        }
        val type = String(rec.type)
        return when {
            rec.tnf == NdefRecord.TNF_WELL_KNOWN && type == "T" -> "نص = \"${parseText(rec)}\""
            rec.tnf == NdefRecord.TNF_WELL_KNOWN && type == "U" -> "رابط = ${parseUri(rec)}"
            else -> "[$tnf] type=$type payload=${Hex.encode(rec.payload)}"
        }
    }

    private fun parseText(rec: NdefRecord): String {
        val p = rec.payload
        if (p.isEmpty()) return ""
        val langLen = p[0].toInt() and 0x3F
        val enc = if ((p[0].toInt() and 0x80) == 0) Charsets.UTF_8 else Charsets.UTF_16
        return String(p, 1 + langLen, p.size - 1 - langLen, enc)
    }

    private fun parseUri(rec: NdefRecord): String {
        val p = rec.payload
        if (p.isEmpty()) return ""
        val prefixes = arrayOf("", "http://www.", "https://www.", "http://", "https://",
            "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://",
            "sftp://", "smb://", "nfs://", "ftp://", "dav://", "news:", "telnet://",
            "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:", "btspp://",
            "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://",
            "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:")
        val idx = p[0].toInt() and 0xFF
        val prefix = if (idx < prefixes.size) prefixes[idx] else ""
        return prefix + String(p, 1, p.size - 1, Charsets.UTF_8)
    }
}
