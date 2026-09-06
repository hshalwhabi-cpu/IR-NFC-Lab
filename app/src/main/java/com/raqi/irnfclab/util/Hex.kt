package com.raqi.irnfclab.util

/** أدوات تحويل بين النص السداسي (HEX) والبايتات. */
object Hex {

    fun encode(data: ByteArray?, separator: String = " "): String {
        if (data == null) return ""
        val sb = StringBuilder()
        for (b in data) {
            if (sb.isNotEmpty() && separator.isNotEmpty()) sb.append(separator)
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** يقبل أي فواصل: مسافات، شرطات، أسطر جديدة، 0x ... */
    fun decode(text: String): ByteArray {
        val clean = text.replace("0x", "").replace("0X", "")
            .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (clean.length % 2 != 0) throw IllegalArgumentException("عدد خانات HEX فردي: ${clean.length}")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun ascii(data: ByteArray): String {
        val sb = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        return sb.toString()
    }

    /** عرض بنمط hexdump: العنوان + HEX + ASCII */
    fun dump(data: ByteArray, bytesPerLine: Int = 16): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val end = minOf(i + bytesPerLine, data.size)
            val chunk = data.copyOfRange(i, end)
            sb.append(String.format("%04X  ", i))
            sb.append(encode(chunk).padEnd(bytesPerLine * 3 - 1))
            sb.append("  |").append(ascii(chunk)).append("|\n")
            i = end
        }
        return sb.toString()
    }

    fun toInt(data: ByteArray): Long {
        var v = 0L
        for (b in data) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }

    /** يعكس ترتيب البايتات (Little Endian <-> Big Endian) */
    fun reversed(data: ByteArray): ByteArray = data.reversedArray()
}
