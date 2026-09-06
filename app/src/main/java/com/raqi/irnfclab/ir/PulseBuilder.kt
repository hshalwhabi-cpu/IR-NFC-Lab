package com.raqi.irnfclab.ir

/**
 * يبني نمط النبضات بالتناوب mark/space مع دمج المدد المتتالية بنفس المستوى.
 * الفهارس الزوجية = mark (حامل شغّال)، الفردية = space (صمت).
 */
class PulseBuilder {

    private val d = ArrayList<Int>(200)

    fun mark(us: Int): PulseBuilder = add(true, us)
    fun space(us: Int): PulseBuilder = add(false, us)

    private fun add(isMark: Boolean, us: Int): PulseBuilder {
        if (us <= 0) return this
        val nextIsMark = d.size % 2 == 0
        if (isMark == nextIsMark) {
            d.add(us)
        } else if (d.isEmpty()) {
            // النمط يبدأ بصمت: نضع mark بطول صفر مؤقتًا ثم نحذفه في build()
            d.add(0)
            d.add(us)
        } else {
            d[d.size - 1] = d[d.size - 1] + us
        }
        return this
    }

    /** يرسل بت بترميز مانشستر. markFirst=true يعني أن المنطق 1 = حامل ثم صمت. */
    fun manchester(bit: Boolean, halfUs: Int, oneIsMarkFirst: Boolean): PulseBuilder {
        val markFirst = if (oneIsMarkFirst) bit else !bit
        if (markFirst) { mark(halfUs); space(halfUs) } else { space(halfUs); mark(halfUs) }
        return this
    }

    /** بت بترميز عرض المسافة (NEC وأشباهه) */
    fun pulseDistance(bit: Boolean, markUs: Int, zeroSpaceUs: Int, oneSpaceUs: Int): PulseBuilder {
        mark(markUs)
        space(if (bit) oneSpaceUs else zeroSpaceUs)
        return this
    }

    /** بت بترميز عرض النبضة (Sony) */
    fun pulseWidth(bit: Boolean, zeroMarkUs: Int, oneMarkUs: Int, spaceUs: Int): PulseBuilder {
        mark(if (bit) oneMarkUs else zeroMarkUs)
        space(spaceUs)
        return this
    }

    fun bitsLsbFirst(value: Long, count: Int, block: (Boolean) -> Unit) {
        for (i in 0 until count) block(((value shr i) and 1L) == 1L)
    }

    fun bitsMsbFirst(value: Long, count: Int, block: (Boolean) -> Unit) {
        for (i in count - 1 downTo 0) block(((value shr i) and 1L) == 1L)
    }

    fun build(trailingGapUs: Int = 0): IntArray {
        val out = ArrayList<Int>(d)
        if (out.isNotEmpty() && out[0] == 0) {
            // نمط بدأ بصمت — نحذف الصمت الابتدائي لأن الصمت قبل الإرسال غير مرئي للمستقبل
            out.removeAt(0)
            out.removeAt(0)
        }
        if (out.isEmpty()) return IntArray(0)
        if (trailingGapUs > 0) {
            if (out.size % 2 == 0) out[out.size - 1] = out[out.size - 1] + trailingGapUs
            else out.add(trailingGapUs)
        } else if (out.size % 2 == 1) {
            // ConsumerIrManager تتوقع أن ينتهي النمط بفترة صمت
            out.add(4000)
        }
        return out.toIntArray()
    }
}
