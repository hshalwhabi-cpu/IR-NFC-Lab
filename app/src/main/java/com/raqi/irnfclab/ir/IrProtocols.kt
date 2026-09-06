package com.raqi.irnfclab.ir

/**
 * مولّدات البروتوكولات الشائعة. كل دالة تُرجع IrCode جاهزًا للإرسال.
 * القيم الزمنية بالميكروثانية وهي التوقيتات القياسية المنشورة لكل بروتوكول.
 */
object IrProtocols {

    val NAMES = listOf(
        "NEC (عنوان 8 + أمر 8)",
        "NEC موسّع (عنوان 16 + أمر 8)",
        "NEC خام 32-bit",
        "Samsung 32",
        "Sony SIRC 12-bit",
        "Sony SIRC 15-bit",
        "Sony SIRC 20-bit",
        "Philips RC5",
        "Philips RC6 mode-0",
        "JVC 16-bit",
        "Kaseikyo / Panasonic خام 48-bit"
    )

    /**
     * @param index فهرس البروتوكول من NAMES
     * @param a العنوان (أو القيمة الخام الكاملة في البروتوكولات الخام)
     * @param c الأمر
     * @param toggle بت التبديل (RC5/RC6 فقط)
     */
    fun encode(index: Int, a: Long, c: Long, toggle: Boolean): IrCode = when (index) {
        0 -> nec(a and 0xFF, c and 0xFF)
        1 -> necExtended(a and 0xFFFF, c and 0xFF)
        2 -> necRaw32(a and 0xFFFFFFFFL)
        3 -> samsung32(a and 0xFF, c and 0xFF)
        4 -> sirc(12, a, c)
        5 -> sirc(15, a, c)
        6 -> sirc(20, a, c)
        7 -> rc5(a and 0x1F, c and 0x3F, toggle)
        8 -> rc6(a and 0xFF, c and 0xFF, toggle)
        9 -> jvc(a and 0xFF, c and 0xFF)
        10 -> kaseikyoRaw48(a)
        else -> throw IllegalArgumentException("بروتوكول غير معروف")
    }

    fun usesToggle(index: Int): Boolean = index == 7 || index == 8

    // ------------------------------------------------------------------ NEC

    private const val NEC_HDR_MARK = 9000
    private const val NEC_HDR_SPACE = 4500
    private const val NEC_BIT_MARK = 560
    private const val NEC_ZERO = 560
    private const val NEC_ONE = 1690

    private fun necBody(value: Long, bits: Int): IntArray {
        val b = PulseBuilder()
        b.mark(NEC_HDR_MARK); b.space(NEC_HDR_SPACE)
        // تُرسل البايتات من الأقل أهمية داخل كل بايت، والبايت الأول أولًا
        for (byteIndex in (bits / 8 - 1) downTo 0) {
            val byte = (value shr (byteIndex * 8)) and 0xFF
            for (i in 0 until 8) {
                val bit = ((byte shr i) and 1L) == 1L
                b.pulseDistance(bit, NEC_BIT_MARK, NEC_ZERO, NEC_ONE)
            }
        }
        b.mark(NEC_BIT_MARK)
        return b.build(40000)
    }

    /** إطار التكرار في NEC (يُرسل عند الضغط المستمر) */
    fun necRepeatFrame(): IntArray = intArrayOf(NEC_HDR_MARK, 2250, NEC_BIT_MARK, 40000)

    fun nec(address: Long, command: Long): IrCode {
        val value = (address shl 24) or ((address.inv() and 0xFF) shl 16) or
                (command shl 8) or (command.inv() and 0xFF)
        return IrCode(38000, necBody(value, 32), necRepeatFrame(),
            "NEC addr=0x%02X cmd=0x%02X".format(address, command))
    }

    fun necExtended(address16: Long, command: Long): IrCode {
        // العنوان 16-bit يُرسل كما هو: البايت المنخفض أولًا
        val lo = address16 and 0xFF
        val hi = (address16 shr 8) and 0xFF
        val value = (lo shl 24) or (hi shl 16) or (command shl 8) or (command.inv() and 0xFF)
        return IrCode(38000, necBody(value, 32), necRepeatFrame(),
            "NECext addr=0x%04X cmd=0x%02X".format(address16, command))
    }

    fun necRaw32(value: Long): IrCode =
        IrCode(38000, necBody(value, 32), necRepeatFrame(), "NEC raw 0x%08X".format(value))

    // -------------------------------------------------------------- Samsung

    fun samsung32(address: Long, command: Long): IrCode {
        val b = PulseBuilder()
        b.mark(4500); b.space(4500)
        val value = (address shl 24) or (address shl 16) or (command shl 8) or (command.inv() and 0xFF)
        for (byteIndex in 3 downTo 0) {
            val byte = (value shr (byteIndex * 8)) and 0xFF
            for (i in 0 until 8) b.pulseDistance(((byte shr i) and 1L) == 1L, 560, 560, 1690)
        }
        b.mark(560)
        return IrCode(38000, b.build(40000), intArrayOf(4500, 4500, 560, 40000),
            "Samsung addr=0x%02X cmd=0x%02X".format(address, command))
    }

    // ------------------------------------------------------------ Sony SIRC

    fun sirc(bits: Int, address: Long, command: Long): IrCode {
        val addrBits = bits - 7
        val b = PulseBuilder()
        b.mark(2400); b.space(600)
        for (i in 0 until 7) b.pulseWidth(((command shr i) and 1L) == 1L, 600, 1200, 600)
        for (i in 0 until addrBits) b.pulseWidth(((address shr i) and 1L) == 1L, 600, 1200, 600)
        // دورة سوني 45 مللي ثانية؛ نكمل الباقي صمتًا
        val used = b.build().sum()
        val gap = maxOf(45000 - used, 10000)
        return IrCode(40000, b.build(gap), null,
            "SIRC$bits addr=0x%02X cmd=0x%02X".format(address, command))
    }

    // ------------------------------------------------------------------ RC5

    fun rc5(address: Long, command: Long, toggle: Boolean): IrCode {
        val half = 889
        val b = PulseBuilder()
        // بت البداية الأول دائمًا 1، والثاني هو معكوس البت السادس من الأمر (RC5X)
        val fieldBit = ((command shr 6) and 1L) == 0L
        b.manchester(true, half, false)
        b.manchester(fieldBit, half, false)
        b.manchester(toggle, half, false)
        for (i in 4 downTo 0) b.manchester(((address shr i) and 1L) == 1L, half, false)
        for (i in 5 downTo 0) b.manchester(((command shr i) and 1L) == 1L, half, false)
        return IrCode(36000, b.build(50000), null,
            "RC5 addr=0x%02X cmd=0x%02X".format(address, command))
    }

    // ------------------------------------------------------------------ RC6

    fun rc6(address: Long, command: Long, toggle: Boolean): IrCode {
        val t = 444
        val b = PulseBuilder()
        b.mark(6 * t); b.space(2 * t)          // الرأس
        b.manchester(true, t, true)            // بت البداية
        for (i in 0 until 3) b.manchester(false, t, true)  // نمط 0
        // بت التبديل بعرض مضاعف
        if (toggle) { b.mark(2 * t); b.space(2 * t) } else { b.space(2 * t); b.mark(2 * t) }
        for (i in 7 downTo 0) b.manchester(((address shr i) and 1L) == 1L, t, true)
        for (i in 7 downTo 0) b.manchester(((command shr i) and 1L) == 1L, t, true)
        return IrCode(36000, b.build(2666 + 40000), null,
            "RC6 addr=0x%02X cmd=0x%02X".format(address, command))
    }

    // ------------------------------------------------------------------ JVC

    fun jvc(address: Long, command: Long): IrCode {
        val b = PulseBuilder()
        b.mark(8400); b.space(4200)
        for (i in 0 until 8) b.pulseDistance(((address shr i) and 1L) == 1L, 525, 525, 1575)
        for (i in 0 until 8) b.pulseDistance(((command shr i) and 1L) == 1L, 525, 525, 1575)
        b.mark(525)
        return IrCode(38000, b.build(45000), null,
            "JVC addr=0x%02X cmd=0x%02X".format(address, command))
    }

    // --------------------------------------------------------- Kaseikyo 48

    fun kaseikyoRaw48(value48: Long): IrCode {
        val b = PulseBuilder()
        b.mark(3456); b.space(1728)
        for (byteIndex in 5 downTo 0) {
            val byte = (value48 shr (byteIndex * 8)) and 0xFF
            for (i in 0 until 8) b.pulseDistance(((byte shr i) and 1L) == 1L, 432, 432, 1296)
        }
        b.mark(432)
        return IrCode(37000, b.build(75000), null, "Kaseikyo 0x%012X".format(value48))
    }

    // -------------------------------------------------------------- خام حر

    /** بناء IrCode من نص "9000,4500,560,1690,..." */
    fun fromCsv(carrier: Int, csv: String): IrCode {
        val parts = csv.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw IllegalArgumentException("النمط فارغ")
        val arr = IntArray(parts.size) { parts[it].toInt() }
        return IrCode(carrier, arr, null, "RAW ${arr.size} قيمة")
    }
}
