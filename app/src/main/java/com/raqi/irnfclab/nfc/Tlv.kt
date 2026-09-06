package com.raqi.irnfclab.nfc

import com.raqi.irnfclab.util.Hex

/** عقدة TLV بترميز BER-TLV (المستخدم في EMV). */
class Tlv(
    val tag: Int,
    val tagBytes: ByteArray,
    val value: ByteArray,
    val children: List<Tlv>
) {
    val isConstructed: Boolean get() = (tagBytes[0].toInt() and 0x20) != 0
    fun tagHex(): String = Hex.encode(tagBytes, "")

    /** بحث تكراري عن أول عقدة بالوسم المحدد */
    fun find(searchTag: Int): Tlv? {
        if (tag == searchTag) return this
        for (c in children) c.find(searchTag)?.let { return it }
        return null
    }

    fun findAll(searchTag: Int, out: MutableList<Tlv> = ArrayList()): List<Tlv> {
        if (tag == searchTag) out.add(this)
        for (c in children) c.findAll(searchTag, out)
        return out
    }
}

object TlvParser {

    fun parse(data: ByteArray): List<Tlv> = parse(data, 0, data.size)

    private fun parse(data: ByteArray, start: Int, end: Int): List<Tlv> {
        val list = ArrayList<Tlv>()
        var i = start
        while (i < end) {
            // تجاوز بايتات الحشو 0x00 و 0xFF
            if (data[i].toInt() and 0xFF == 0x00 || data[i].toInt() and 0xFF == 0xFF) { i++; continue }

            val tagStart = i
            var first = data[i].toInt() and 0xFF
            i++
            // وسم متعدد البايتات
            if ((first and 0x1F) == 0x1F) {
                while (i < end && (data[i].toInt() and 0x80) != 0) i++
                if (i < end) i++
            }
            if (i > end) break
            val tagBytes = data.copyOfRange(tagStart, i)
            var tag = 0
            for (b in tagBytes) tag = (tag shl 8) or (b.toInt() and 0xFF)

            if (i >= end) break
            // الطول
            var len = data[i].toInt() and 0xFF
            i++
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                if (n == 0 || i + n > end) break
                len = 0
                for (k in 0 until n) { len = (len shl 8) or (data[i].toInt() and 0xFF); i++ }
            }
            if (len < 0 || i + len > end) break
            val value = data.copyOfRange(i, i + len)
            i += len

            val constructed = (tagBytes[0].toInt() and 0x20) != 0
            val children = if (constructed) parse(value, 0, value.size) else emptyList()
            list.add(Tlv(tag, tagBytes, value, children))
        }
        return list
    }

    fun findFirst(nodes: List<Tlv>, tag: Int): Tlv? {
        for (n in nodes) n.find(tag)?.let { return it }
        return null
    }

    fun findAll(nodes: List<Tlv>, tag: Int): List<Tlv> {
        val out = ArrayList<Tlv>()
        for (n in nodes) n.findAll(tag, out)
        return out
    }

    /** طباعة شجرية بمسافات بادئة */
    fun dump(nodes: List<Tlv>, indent: Int = 0): String {
        val sb = StringBuilder()
        val pad = "  ".repeat(indent)
        for (n in nodes) {
            sb.append(pad).append(n.tagHex()).append(" (").append(n.value.size).append(") ")
            sb.append(EmvTags.name(n.tag))
            if (n.isConstructed) {
                sb.append("\n").append(dump(n.children, indent + 1))
            } else {
                sb.append("  = ").append(Hex.encode(n.value)).append("\n")
            }
        }
        return sb.toString()
    }
}

object EmvTags {
    private val names = mapOf(
        0x6F to "FCI Template", 0x84 to "DF Name", 0xA5 to "FCI Proprietary",
        0x50 to "اسم التطبيق", 0x87 to "Priority", 0x4F to "AID (ADF Name)",
        0x9F38 to "PDOL", 0xBF0C to "FCI Issuer Disc", 0x61 to "App Template",
        0x77 to "Response Template", 0x80 to "Response (format1)",
        0x82 to "AIP", 0x94 to "AFL", 0x57 to "Track2 Equivalent",
        0x5A to "PAN (رقم البطاقة)", 0x5F24 to "تاريخ الانتهاء",
        0x5F25 to "تاريخ السريان", 0x5F20 to "اسم حامل البطاقة",
        0x5F34 to "PAN Sequence", 0x5F28 to "رمز الدولة المُصدِرة",
        0x5F2D to "اللغة", 0x9F42 to "رمز عملة التطبيق",
        0x9F36 to "ATC", 0x9F13 to "Last Online ATC", 0x8E to "CVM List",
        0x8C to "CDOL1", 0x8D to "CDOL2", 0x9F07 to "App Usage Control",
        0x9F08 to "App Version", 0x9F0D to "IAC Default", 0x9F0E to "IAC Denial",
        0x9F0F to "IAC Online", 0x5F30 to "Service Code", 0x9F4A to "SDA Tag List"
    )
    fun name(tag: Int): String = names[tag] ?: ""
}
