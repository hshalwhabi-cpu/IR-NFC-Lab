package com.raqi.irnfclab.ir

/**
 * إشارة أشعة تحت حمراء جاهزة للإرسال.
 * pattern = مدد بالميكروثانية بالتناوب: [تشغيل, إطفاء, تشغيل, إطفاء ...]
 * وهو نفس التنسيق الذي تتطلبه ConsumerIrManager.transmit()
 */
data class IrCode(
    val carrierHz: Int,
    val pattern: IntArray,
    val repeatPattern: IntArray? = null,
    val label: String = ""
) {
    fun totalMicros(): Int = pattern.sum()

    fun patternCsv(): String = pattern.joinToString(",")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrCode) return false
        return carrierHz == other.carrierHz && pattern.contentEquals(other.pattern)
    }

    override fun hashCode(): Int = 31 * carrierHz + pattern.contentHashCode()
}
