package com.raqi.irnfclab.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.raqi.irnfclab.util.Hex

/**
 * قارئ EMV تلقائي للمعلومات العلنية فقط من بطاقة الدفع.
 * لا يصل — ولا يمكنه الوصول — إلى الـ PIN أو CVV أو المفاتيح السرية (محبوسة داخل الشريحة الآمنة).
 * التسلسل: SELECT PPSE ← اختيار AID ← GPO ← READ RECORDS، مع مسح احتياطي للسجلات.
 */
object EmvReader {

    private val PPSE = Hex.decode("325041592E5359532E4444463031") // "2PAY.SYS.DDF01"

    class Card {
        var scheme = ""
        var appLabel = ""
        var aid = ""
        var pan = ""
        var expiry = ""      // MM/YY
        var cardholder = ""
        var panSeq = ""
        var serviceCode = ""
        val log = StringBuilder()
        var raw = StringBuilder()
    }

    private fun schemeOf(aidHex: String): String {
        val a = aidHex.uppercase()
        return when {
            a.startsWith("A000000003") -> "Visa"
            a.startsWith("A000000004") -> "Mastercard"
            a.startsWith("A000000025") -> "American Express"
            a.startsWith("A000000065") -> "JCB"
            a.startsWith("A000000152") -> "Discover"
            a.startsWith("A000000277") -> "Interac"
            a.startsWith("A0000002471001") || a.startsWith("A000000247") -> "مدى (mada)"
            a.startsWith("A000000333") -> "UnionPay"
            else -> "غير معروف"
        }
    }

    fun read(tag: Tag): Card {
        val card = Card()
        val iso = IsoDep.get(tag) ?: run {
            card.log.append("البطاقة لا تدعم IsoDep — ليست بطاقة تلامسية EMV.\n")
            return card
        }
        iso.connect()
        iso.timeout = 4000
        try {
            // 1) SELECT PPSE
            val ppse = transceive(iso, buildSelect(PPSE), card, "SELECT PPSE")
            val aids = ArrayList<String>()
            if (ppse != null && sw(ppse) == "9000") {
                val nodes = TlvParser.parse(body(ppse))
                for (t in TlvParser.findAll(nodes, 0x4F)) aids.add(Hex.encode(t.value, ""))
            }
            if (aids.isEmpty()) {
                // 1ب) جرّب PSE التلامسي القديم
                val pse = transceive(iso, buildSelect(Hex.decode("315041592E5359532E4444463031")), card, "SELECT PSE")
                if (pse != null && sw(pse) == "9000") {
                    val nodes = TlvParser.parse(body(pse))
                    for (t in TlvParser.findAll(nodes, 0x4F)) aids.add(Hex.encode(t.value, ""))
                }
            }
            if (aids.isEmpty()) {
                // 1ج) جرّب قائمة AID شائعة مباشرة
                aids.addAll(COMMON_AIDS)
            }

            // 2) لكل AID حتى ننجح
            for (aidHex in aids.distinct()) {
                val aid = Hex.decode(aidHex)
                val sel = transceive(iso, buildSelect(aid), card, "SELECT AID $aidHex") ?: continue
                if (sw(sel) != "9000") continue

                card.aid = aidHex
                card.scheme = schemeOf(aidHex)
                val selNodes = TlvParser.parse(body(sel))
                TlvParser.findFirst(selNodes, 0x50)?.let { card.appLabel = ascii(it.value) }

                // 3) GET PROCESSING OPTIONS (ببناء PDOL افتراضي)
                val pdol = TlvParser.findFirst(selNodes, 0x9F38)?.value
                val gpoData = buildGpo(pdol)
                val gpo = transceive(iso, gpoData, card, "GET PROCESSING OPTIONS")
                val afl = ArrayList<IntArray>()
                if (gpo != null && sw(gpo) == "9000") {
                    parseAfl(body(gpo), afl)
                }

                // 4) قراءة السجلات: من AFL إن وُجد، وإلا مسح احتياطي
                val toRead = if (afl.isNotEmpty()) afl else bruteForceList()
                for (seg in toRead) {
                    val sfi = seg[0]; val from = seg[1]; val to = seg[2]
                    for (rec in from..to) {
                        val p2 = (sfi shl 3) or 4
                        val cmd = byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), p2.toByte(), 0x00)
                        val r = transceive(iso, cmd, card, "READ REC sfi=$sfi rec=$rec", verbose = afl.isNotEmpty())
                        if (r != null && sw(r) == "9000") harvest(body(r), card)
                    }
                }

                if (card.pan.isNotEmpty()) break // نجحنا
            }
        } catch (e: Exception) {
            card.log.append("انقطع الاتصال: ${e.message}\n")
        } finally {
            try { iso.close() } catch (e: Exception) {}
        }
        return card
    }

    // ------------------------------------------------------------ بناء الأوامر

    private fun buildSelect(aid: ByteArray): ByteArray {
        val out = ByteArray(5 + aid.size + 1)
        out[0] = 0x00; out[1] = 0xA4.toByte(); out[2] = 0x04; out[3] = 0x00
        out[4] = aid.size.toByte()
        System.arraycopy(aid, 0, out, 5, aid.size)
        out[out.size - 1] = 0x00
        return out
    }

    /** يبني GET PROCESSING OPTIONS مع تعبئة PDOL بقيم طرفية افتراضية معقولة. */
    private fun buildGpo(pdol: ByteArray?): ByteArray {
        val pdolData = if (pdol == null || pdol.isEmpty()) ByteArray(0) else fillPdol(pdol)
        val templateLen = 2 + pdolData.size // '83' Lc <data>
        val out = ArrayList<Byte>()
        out.add(0x80.toByte()); out.add(0xA8.toByte()); out.add(0x00); out.add(0x00)
        out.add((templateLen).toByte())
        out.add(0x83.toByte()); out.add(pdolData.size.toByte())
        for (b in pdolData) out.add(b)
        out.add(0x00)
        return out.toByteArray()
    }

    /** قيم افتراضية لأشهر وسوم PDOL/DOL */
    private fun defaultFor(tag: Int, len: Int): ByteArray {
        val v = ByteArray(len)
        when (tag) {
            0x9F66 -> if (len >= 1) v[0] = 0x36            // TTQ (qVSDC)
            0x9F1A -> if (len >= 2) { v[len-1] = 0x82.toByte(); v[len-2] = 0x06 } // Terminal Country = 0682 (SA)
            0x5F2A -> if (len >= 2) { v[len-1] = 0x82.toByte(); v[len-2] = 0x06 } // Currency = 0682 (SAR)
            0x9A   -> { // Transaction Date YYMMDD
                if (len >= 3) { v[0] = 0x25; v[1] = 0x01; v[2] = 0x01 }
            }
            0x9C   -> if (len >= 1) v[0] = 0x00            // Transaction Type = purchase
            0x9F37 -> for (k in 0 until len) v[k] = (0x11 * (k + 1)).toByte() // Unpredictable Number
            0x9F35 -> if (len >= 1) v[0] = 0x22            // Terminal Type
            0x9F33 -> if (len >= 3) { v[0] = 0xE0.toByte(); v[1] = 0xF8.toByte(); v[2] = 0xC8.toByte() } // Terminal Capabilities
            0x9F40 -> {}                                   // Additional Terminal Capabilities = 0
            0x95   -> {}                                   // TVR = 0
            0x9F02, 0x9F03 -> {}                           // Amounts = 0
            0x9F45 -> {}
            else -> {}                                     // الباقي أصفار
        }
        return v
    }

    private fun fillPdol(pdol: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < pdol.size) {
            val tagStart = i
            var b = pdol[i].toInt() and 0xFF; i++
            if ((b and 0x1F) == 0x1F) { while (i < pdol.size && (pdol[i].toInt() and 0x80) != 0) i++; if (i < pdol.size) i++ }
            var tag = 0
            for (k in tagStart until i) tag = (tag shl 8) or (pdol[k].toInt() and 0xFF)
            if (i >= pdol.size) break
            val len = pdol[i].toInt() and 0xFF; i++
            for (bt in defaultFor(tag, len)) out.add(bt)
        }
        return out.toByteArray()
    }

    private fun parseAfl(gpoBody: ByteArray, out: MutableList<IntArray>) {
        val nodes = TlvParser.parse(gpoBody)
        // format 1 (tag 80): AIP(2) + AFL(rest) ؛ أو template 77 فيه 94
        var afl: ByteArray? = TlvParser.findFirst(nodes, 0x94)?.value
        if (afl == null) {
            val f1 = TlvParser.findFirst(nodes, 0x80)?.value
            if (f1 != null && f1.size > 2) afl = f1.copyOfRange(2, f1.size)
        }
        if (afl == null) return
        var i = 0
        while (i + 4 <= afl.size) {
            val sfi = (afl[i].toInt() and 0xFF) shr 3
            val first = afl[i + 1].toInt() and 0xFF
            val last = afl[i + 2].toInt() and 0xFF
            if (sfi in 1..30 && first in 1..last) out.add(intArrayOf(sfi, first, last))
            i += 4
        }
    }

    private fun bruteForceList(): List<IntArray> {
        val l = ArrayList<IntArray>()
        for (sfi in 1..4) l.add(intArrayOf(sfi, 1, 8))
        return l
    }

    // ------------------------------------------------------------ استخراج البيانات

    private fun harvest(recordBody: ByteArray, card: Card) {
        val nodes = TlvParser.parse(recordBody)
        card.raw.append(TlvParser.dump(nodes))

        // Track2 (57): أغنى مصدر — فيه PAN + تاريخ + كود خدمة
        TlvParser.findFirst(nodes, 0x57)?.let { t ->
            val hex = Hex.encode(t.value, "")
            val d = hex.indexOfFirst { it == 'D' || it == 'd' }
            if (d > 0) {
                if (card.pan.isEmpty()) card.pan = hex.substring(0, d)
                val rest = hex.substring(d + 1)
                if (rest.length >= 4 && card.expiry.isEmpty()) {
                    val yy = rest.substring(0, 2); val mm = rest.substring(2, 4)
                    card.expiry = "$mm/$yy"
                }
                if (rest.length >= 7 && card.serviceCode.isEmpty())
                    card.serviceCode = rest.substring(4, 7)
            }
        }
        // 5A: PAN صريح
        TlvParser.findFirst(nodes, 0x5A)?.let {
            if (card.pan.isEmpty()) card.pan = Hex.encode(it.value, "").trimEnd('F', 'f')
        }
        // 5F24: تاريخ الانتهاء YYMMDD
        TlvParser.findFirst(nodes, 0x5F24)?.let {
            val h = Hex.encode(it.value, "")
            if (h.length >= 4 && card.expiry.isEmpty()) card.expiry = "${h.substring(2,4)}/${h.substring(0,2)}"
        }
        TlvParser.findFirst(nodes, 0x5F20)?.let { if (card.cardholder.isEmpty()) card.cardholder = ascii(it.value).trim() }
        TlvParser.findFirst(nodes, 0x50)?.let { if (card.appLabel.isEmpty()) card.appLabel = ascii(it.value).trim() }
        TlvParser.findFirst(nodes, 0x5F34)?.let { if (card.panSeq.isEmpty()) card.panSeq = Hex.encode(it.value, "") }
        TlvParser.findFirst(nodes, 0x5F30)?.let { if (card.serviceCode.isEmpty()) card.serviceCode = Hex.encode(it.value, "") }
    }

    // ------------------------------------------------------------ أدوات

    private fun transceive(iso: IsoDep, cmd: ByteArray, card: Card, label: String, verbose: Boolean = true): ByteArray? {
        return try {
            val r = iso.transceive(cmd)
            if (verbose) card.log.append("→ $label\n   ${Hex.encode(cmd)}\n   ← ${Hex.encode(r)}  [${sw(r)}]\n")
            r
        } catch (e: Exception) {
            card.log.append("✗ $label: ${e.message}\n")
            null
        }
    }

    private fun sw(r: ByteArray): String =
        if (r.size >= 2) Hex.encode(r.copyOfRange(r.size - 2, r.size), "") else "----"

    private fun body(r: ByteArray): ByteArray =
        if (r.size >= 2) r.copyOfRange(0, r.size - 2) else ByteArray(0)

    private fun ascii(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) { val c = x.toInt() and 0xFF; if (c in 32..126) sb.append(c.toChar()) }
        return sb.toString()
    }

    fun formatPan(pan: String): String =
        pan.chunked(4).joinToString(" ")

    private val COMMON_AIDS = listOf(
        "A0000000031010",     // Visa
        "A0000000041010",     // Mastercard
        "A00000002501",       // Amex
        "A0000002471001",     // mada
        "A0000000651010",     // JCB
        "A0000003330101"      // UnionPay
    )
}
