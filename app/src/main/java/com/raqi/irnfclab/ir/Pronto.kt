package com.raqi.irnfclab.ir

import kotlin.math.roundToInt

/**
 * تحويل صيغة Pronto HEX (CCF) — وهي أشهر صيغة لأكواد الريموتات على الإنترنت —
 * إلى نمط نبضات جاهز للإرسال، والعكس.
 *
 * البنية: 0000 <معامل التردد> <عدد أزواج المقدمة> <عدد أزواج التكرار> ثم الأزواج.
 */
object Pronto {

    private const val CLOCK = 0.241246  // ميكروثانية لكل وحدة

    fun parse(text: String): IrCode {
        val words = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            .map {
                it.toIntOrNull(16) ?: throw IllegalArgumentException("قيمة ليست HEX: $it")
            }
        if (words.size < 4) throw IllegalArgumentException("كود Pronto قصير جدًا")

        val type = words[0]
        if (type != 0x0000 && type != 0x0100) {
            throw IllegalArgumentException(
                "نوع Pronto غير مدعوم: %04X (المدعوم 0000 و 0100)".format(type))
        }

        val freqWord = words[1]
        if (freqWord == 0) throw IllegalArgumentException("معامل التردد = 0")
        val unitUs = freqWord * CLOCK
        val carrier = (1_000_000.0 / unitUs).roundToInt()

        val onceCount = words[2] * 2
        val repeatCount = words[3] * 2
        val body = words.drop(4)
        if (body.size < onceCount + repeatCount) {
            throw IllegalArgumentException(
                "عدد القيم لا يطابق الرأس: متوقع ${onceCount + repeatCount} ووجدت ${body.size}")
        }

        fun toMicros(list: List<Int>): IntArray =
            IntArray(list.size) { maxOf(1, (list[it] * unitUs).roundToInt()) }

        val once = toMicros(body.subList(0, onceCount))
        val repeat = if (repeatCount > 0)
            toMicros(body.subList(onceCount, onceCount + repeatCount)) else null

        val pattern = if (once.isNotEmpty()) once else (repeat ?: IntArray(0))
        if (pattern.isEmpty()) throw IllegalArgumentException("لا توجد نبضات في الكود")

        return IrCode(carrier, pattern, if (once.isNotEmpty()) repeat else null,
            "Pronto %d Hz".format(carrier))
    }

    /** تصدير IrCode إلى نص Pronto (مفيد لمشاركة الأكواد أو حفظها) */
    fun format(code: IrCode): String {
        val freqWord = (1_000_000.0 / (code.carrierHz * CLOCK)).roundToInt()
        val unitUs = freqWord * CLOCK
        val sb = StringBuilder()
        val once = code.pattern
        val rep = code.repeatPattern ?: IntArray(0)
        sb.append("0000 %04X %04X %04X".format(freqWord, once.size / 2, rep.size / 2))
        for (v in once) sb.append(" %04X".format(maxOf(1, (v / unitUs).roundToInt())))
        for (v in rep) sb.append(" %04X".format(maxOf(1, (v / unitUs).roundToInt())))
        return sb.toString()
    }
}
