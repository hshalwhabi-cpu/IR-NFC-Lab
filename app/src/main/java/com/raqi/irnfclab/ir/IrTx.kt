package com.raqi.irnfclab.ir

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.os.Build
import java.io.File

/**
 * الغلاف الوحيد المسموح به رسميًا للتحكم بباعث الأشعة تحت الحمراء في أندرويد:
 * android.hardware.ConsumerIrManager  (إرسال فقط — لا يوجد API عام للاستقبال).
 */
class IrTx(private val ctx: Context) {

    private val cir: ConsumerIrManager? =
        ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    fun hasEmitter(): Boolean = try {
        cir != null && cir.hasIrEmitter()
    } catch (e: Throwable) {
        false
    }

    fun hasFeatureFlag(): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)

    /** نطاقات التردد الحامل المدعومة من العتاد */
    fun carrierRanges(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        try {
            cir?.carrierFrequencies?.forEach { out.add(Pair(it.minFrequency, it.maxFrequency)) }
        } catch (e: Throwable) {
            // بعض الأجهزة ترمي استثناء إن لم يوجد باعث
        }
        return out
    }

    fun isCarrierSupported(hz: Int): Boolean {
        val ranges = carrierRanges()
        if (ranges.isEmpty()) return true
        return ranges.any { hz >= it.first && hz <= it.second }
    }

    /**
     * الإرسال الفعلي. يرمي استثناء مع رسالة عربية عند الفشل.
     * @param repeats عدد مرات إعادة الإطار (0 = مرة واحدة)
     * @param gapMs فاصل بين التكرارات
     */
    fun transmit(code: IrCode, repeats: Int = 0, gapMs: Long = 40, useRepeatFrame: Boolean = true) {
        val m = cir ?: throw IllegalStateException("خدمة CONSUMER_IR غير متوفرة على هذا الجهاز")
        if (!m.hasIrEmitter()) throw IllegalStateException("لا يوجد باعث IR في هذا الجهاز")
        if (code.pattern.isEmpty()) throw IllegalArgumentException("النمط فارغ")
        if (code.pattern.any { it <= 0 }) throw IllegalArgumentException("النمط يحتوي على قيمة صفر أو سالبة")

        m.transmit(code.carrierHz, code.pattern)
        val rep = if (useRepeatFrame && code.repeatPattern != null) code.repeatPattern else code.pattern
        for (i in 0 until repeats) {
            Thread.sleep(gapMs)
            m.transmit(code.carrierHz, rep)
        }
    }

    /** تقرير تشخيصي كامل عن قدرات IR في الجهاز */
    fun diagnostics(): String {
        val sb = StringBuilder()
        sb.append("الجهاز        : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("أندرويد       : ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("ميزة النظام   : ").append(if (hasFeatureFlag()) "consumerir معلنة ✓" else "غير معلنة ✗").append("\n")
        sb.append("خدمة النظام   : ").append(if (cir != null) "متوفرة ✓" else "غير متوفرة ✗").append("\n")
        sb.append("باعث IR       : ").append(if (hasEmitter()) "موجود ✓" else "غير موجود ✗").append("\n")

        val ranges = carrierRanges()
        if (ranges.isEmpty()) sb.append("الترددات      : لم تُعلن (استخدم 38000 كقيمة افتراضية)\n")
        else {
            sb.append("الترددات      :\n")
            for (r in ranges) sb.append("   ").append(r.first).append(" .. ").append(r.second).append(" Hz\n")
        }

        sb.append("\n--- فحص إمكانية الاستقبال (قراءة IR) ---\n")
        val nodes = listOf(
            "/dev/lirc0", "/dev/lirc1", "/dev/lirc-rx", "/dev/ir_rx", "/dev/ttyIR",
            "/sys/class/rc", "/sys/class/lirc", "/dev/input"
        )
        var anyReadable = false
        for (n in nodes) {
            val f = File(n)
            val state = when {
                !f.exists() -> "غير موجود"
                f.canRead() -> { anyReadable = true; "موجود وقابل للقراءة ✓" }
                else -> "موجود لكن مرفوض الوصول (يتطلب root)"
            }
            sb.append(String.format("%-16s : %s%n", n, state))
        }
        sb.append("\nالخلاصة: ")
        sb.append(
            if (anyReadable) "قد يوجد مسار استقبال — جرّب تبويب «قراءة IR».\n"
            else "لا يوجد مسار استقبال متاح لتطبيق بلا صلاحيات root.\n" +
                 "أندرويد لا يوفّر أي API عام لالتقاط إشارات IR، ومعظم الأجهزة\n" +
                 "تحتوي على باعث (LED) فقط بدون مستقبل. الحلول العملية داخل التطبيق:\n" +
                 "  • استيراد أكواد Pronto / RAW جاهزة من قواعد الريموتات.\n" +
                 "  • وضع «المسح التلقائي» لتجربة الأكواد حتى يستجيب الجهاز.\n" +
                 "  • قارئ خارجي رخيص (ESP32/Arduino + مستقبل TSOP) ولصق التوقيتات هنا.\n"
        )
        return sb.toString()
    }
}
