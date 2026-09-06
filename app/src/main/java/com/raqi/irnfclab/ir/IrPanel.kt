package com.raqi.irnfclab.ir

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.raqi.irnfclab.ui.Ui

/** واجهة التحكم الكامل بالأشعة تحت الحمراء. */
class IrPanel(private val act: Activity) {

    private val tx = IrTx(act)
    private val store = RemoteStore(act)

    private lateinit var status: TextView
    private lateinit var protoSpinner: Spinner
    private lateinit var addrIn: EditText
    private lateinit var cmdIn: EditText
    private lateinit var repeatIn: EditText
    private lateinit var toggleBox: android.widget.CheckBox
    private lateinit var prontoIn: EditText
    private lateinit var rawCarrierIn: EditText
    private lateinit var rawPatternIn: EditText
    private lateinit var scanInfo: TextView
    private lateinit var remotesBox: LinearLayout
    private lateinit var lastInfo: TextView

    private var lastCode: IrCode? = null
    @Volatile private var scanning = false

    fun build(): View {
        val root = Ui.vertical(act)

        // ---------------------------------------------------- حالة الجهاز
        root.addView(Ui.card(act, "١ · حالة عتاد الأشعة تحت الحمراء").apply {
            status = Ui.mono(act, tx.diagnostics())
            addView(status)
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "تحديث الفحص") { status.text = tx.diagnostics() })
                addView(Ui.button(act, "نسخ التقرير") { copy(status.text.toString()) })
            })
        })

        // -------------------------------------------- الإرسال بالبروتوكول
        root.addView(Ui.card(act, "٢ · إرسال بكود بروتوكول").apply {
            addView(Ui.label(act, "اختر البروتوكول ثم اكتب العنوان والأمر بصيغة HEX (بدون 0x)"))
            protoSpinner = Ui.spinner(act, IrProtocols.NAMES)
            addView(protoSpinner)
            addrIn = Ui.input(act, "العنوان / القيمة الخام مثال: 04", "04")
            cmdIn = Ui.input(act, "الأمر مثال: 08", "08")
            addView(Ui.label(act, "العنوان (Address)"))
            addView(addrIn)
            addView(Ui.label(act, "الأمر (Command)"))
            addView(cmdIn)
            toggleBox = Ui.check(act, "بت التبديل Toggle (لـ RC5 / RC6)")
            addView(toggleBox)
            repeatIn = Ui.input(act, "عدد التكرارات", "2")
            addView(Ui.label(act, "عدد إطارات التكرار بعد الإطار الأول"))
            addView(repeatIn)
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "إرسال") { sendProtocol() })
                addView(Ui.button(act, "حفظ كزر") { saveLast() })
            })
        })

        // -------------------------------------------------------- Pronto
        root.addView(Ui.card(act, "٣ · كود Pronto HEX (أشهر صيغة على الإنترنت)").apply {
            addView(Ui.label(act, "الصق الكود كاملًا مثل: 0000 006D 0022 0002 0155 00AA ..."))
            prontoIn = Ui.input(act, "0000 006D ...", "", 5)
            addView(prontoIn)
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "إرسال") { sendPronto() })
                addView(Ui.button(act, "تحليل ← خام") { prontoToRaw() })
                addView(Ui.button(act, "حفظ كزر") { saveLast() })
            })
        })

        // ----------------------------------------------------- النمط الخام
        root.addView(Ui.card(act, "٤ · نمط خام (توقيتات بالميكروثانية)").apply {
            addView(Ui.label(act, "التناوب: تشغيل، إطفاء، تشغيل، إطفاء ... مفصولة بفواصل"))
            rawCarrierIn = Ui.input(act, "التردد الحامل Hz", "38000")
            addView(Ui.label(act, "التردد الحامل"))
            addView(rawCarrierIn)
            rawPatternIn = Ui.input(act, "9000,4500,560,560,560,1690,...", "", 6)
            addView(rawPatternIn)
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "إرسال") { sendRaw() })
                addView(Ui.button(act, "← Pronto") { rawToPronto() })
                addView(Ui.button(act, "حفظ كزر") { saveLast() })
            })
        })

        // ------------------------------------------------- المسح التلقائي
        root.addView(Ui.card(act, "٥ · المسح التلقائي للأكواد").apply {
            addView(Ui.label(act,
                "بما أن قراءة IR غير متاحة، هذه أسرع طريقة لاكتشاف كود جهاز مجهول:\n" +
                "وجّه الجوال نحو الجهاز، وشغّل المسح، وراقب متى يستجيب ثم أوقف المسح."))
            scanFrom = Ui.input(act, "من أمر (HEX)", "00")
            scanTo = Ui.input(act, "إلى أمر (HEX)", "FF")
            scanDelay = Ui.input(act, "الفاصل بالمللي ثانية", "300")
            addView(Ui.label(act, "نطاق الأمر (يستخدم البروتوكول والعنوان من القسم ٢)"))
            addView(scanFrom)
            addView(scanTo)
            addView(Ui.label(act, "الفاصل الزمني بين كل كود والذي يليه"))
            addView(scanDelay)
            scanInfo = Ui.mono(act, "المسح متوقف")
            addView(scanInfo)
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "ابدأ المسح") { startScan() })
                addView(Ui.button(act, "إيقاف") { scanning = false })
            })
        })

        // -------------------------------------------------------- ريموتاتي
        root.addView(Ui.card(act, "٦ · ريموتاتي المحفوظة").apply {
            lastInfo = Ui.mono(act, "لا يوجد كود محضّر بعد")
            addView(Ui.label(act, "آخر كود تم تجهيزه (هو الذي يُحفظ عند الضغط على «حفظ كزر»)"))
            addView(lastInfo)
            remotesBox = Ui.vertical(act, 0)
            addView(remotesBox)
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "تصدير JSON") { copy(store.exportJson()) })
                addView(Ui.button(act, "استيراد JSON") { importDialog() })
            })
        })

        refreshRemotes()
        return Ui.scroll(act, root)
    }

    private lateinit var scanFrom: EditText
    private lateinit var scanTo: EditText
    private lateinit var scanDelay: EditText

    // ------------------------------------------------------------ الإرسال

    private fun send(code: IrCode, repeats: Int) {
        try {
            tx.transmit(code, repeats)
            lastCode = code
            lastInfo.text = describe(code)
            Ui.toast(act, "تم الإرسال")
        } catch (e: Throwable) {
            showError(e)
        }
    }

    private fun describe(c: IrCode): String =
        "التسمية : ${c.label}\nالحامل  : ${c.carrierHz} Hz\nالطول   : ${c.pattern.size} قيمة / ${c.totalMicros()} µs\n" +
        c.patternCsv().chunked(72).joinToString("\n")

    private fun hex(e: EditText, def: Long = 0): Long {
        val t = e.text.toString().trim().removePrefix("0x").removePrefix("0X")
        if (t.isEmpty()) return def
        return t.toLongOrNull(16) ?: throw IllegalArgumentException("قيمة HEX غير صالحة: $t")
    }

    private fun sendProtocol() {
        try {
            val idx = protoSpinner.selectedItemPosition
            val code = IrProtocols.encode(idx, hex(addrIn), hex(cmdIn), toggleBox.isChecked)
            send(code, repeatIn.text.toString().trim().toIntOrNull() ?: 0)
        } catch (e: Throwable) { showError(e) }
    }

    private fun sendPronto() {
        try {
            val code = Pronto.parse(prontoIn.text.toString())
            send(code, repeatIn.text.toString().trim().toIntOrNull() ?: 0)
        } catch (e: Throwable) { showError(e) }
    }

    private fun sendRaw() {
        try {
            val carrier = rawCarrierIn.text.toString().trim().toIntOrNull() ?: 38000
            val code = IrProtocols.fromCsv(carrier, rawPatternIn.text.toString())
            send(code, repeatIn.text.toString().trim().toIntOrNull() ?: 0)
        } catch (e: Throwable) { showError(e) }
    }

    private fun prontoToRaw() {
        try {
            val code = Pronto.parse(prontoIn.text.toString())
            rawCarrierIn.setText(code.carrierHz.toString())
            rawPatternIn.setText(code.patternCsv())
            lastCode = code
            lastInfo.text = describe(code)
            Ui.toast(act, "تم التحويل إلى نمط خام")
        } catch (e: Throwable) { showError(e) }
    }

    private fun rawToPronto() {
        try {
            val carrier = rawCarrierIn.text.toString().trim().toIntOrNull() ?: 38000
            val code = IrProtocols.fromCsv(carrier, rawPatternIn.text.toString())
            prontoIn.setText(Pronto.format(code))
            lastCode = code
            lastInfo.text = describe(code)
            Ui.toast(act, "تم التحويل إلى Pronto")
        } catch (e: Throwable) { showError(e) }
    }

    // -------------------------------------------------------------- المسح

    private fun startScan() {
        if (scanning) { Ui.toast(act, "المسح يعمل بالفعل"); return }
        val idx = protoSpinner.selectedItemPosition
        val addr: Long
        val from: Long
        val to: Long
        val delay: Long
        try {
            addr = hex(addrIn)
            from = hex(scanFrom)
            to = hex(scanTo)
            delay = scanDelay.text.toString().trim().toLongOrNull() ?: 300L
        } catch (e: Throwable) { showError(e); return }
        if (to < from) { Ui.toast(act, "النطاق غير صحيح"); return }

        scanning = true
        Thread {
            var v = from
            while (scanning && v <= to) {
                try {
                    val code = IrProtocols.encode(idx, addr, v, false)
                    tx.transmit(code, 1)
                    val text = "جارٍ الإرسال: cmd = 0x%02X  (%d من %d)".format(v, v - from + 1, to - from + 1)
                    act.runOnUiThread { scanInfo.text = text }
                } catch (e: Throwable) {
                    val msg = "توقف: ${e.message}"
                    act.runOnUiThread { scanInfo.text = msg }
                    break
                }
                try { Thread.sleep(delay) } catch (e: InterruptedException) { break }
                v++
            }
            scanning = false
            val last = v
            act.runOnUiThread {
                scanInfo.text = "انتهى المسح عند 0x%02X — إن استجاب الجهاز فالكود قريب من هذه القيمة".format(last)
            }
        }.start()
    }

    // ------------------------------------------------------------ الحفظ

    private fun saveLast() {
        val code = lastCode
        if (code == null) { Ui.toast(act, "أرسل أو حلّل كودًا أولًا"); return }
        val box = Ui.vertical(act)
        val remoteIn = Ui.input(act, "اسم الريموت", if (store.remotes.isEmpty()) "تلفزيوني" else store.remotes[0].name)
        val labelIn = Ui.input(act, "اسم الزر", "زر " + (store.remotes.sumOf { it.buttons.size } + 1))
        box.addView(Ui.label(act, "اسم الريموت")); box.addView(remoteIn)
        box.addView(Ui.label(act, "اسم الزر")); box.addView(labelIn)
        AlertDialog.Builder(act)
            .setTitle("حفظ الكود كزر")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                store.addButton(
                    remoteIn.text.toString().ifBlank { "ريموت" },
                    IrButton(labelIn.text.toString().ifBlank { "زر" }, code.carrierHz, code.pattern, code.repeatPattern)
                )
                refreshRemotes()
                Ui.toast(act, "تم الحفظ")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun importDialog() {
        val input = Ui.input(act, "الصق محتوى JSON هنا", "", 6)
        AlertDialog.Builder(act)
            .setTitle("استيراد ريموتات")
            .setView(input)
            .setPositiveButton("استيراد") { _, _ ->
                try {
                    val n = store.importJson(input.text.toString())
                    refreshRemotes()
                    Ui.toast(act, "تم استيراد $n ريموت")
                } catch (e: Throwable) { showError(e) }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun refreshRemotes() {
        remotesBox.removeAllViews()
        if (store.remotes.isEmpty()) {
            remotesBox.addView(Ui.label(act, "لا توجد ريموتات محفوظة بعد."))
            return
        }
        for (r in store.remotes) {
            remotesBox.addView(Ui.label(act, "▸ ${r.name}", Ui.OK))
            var row: LinearLayout? = null
            for ((i, btn) in r.buttons.withIndex()) {
                if (i % 3 == 0) {
                    row = Ui.horizontal(act)
                    remotesBox.addView(row)
                }
                val b = Ui.button(act, btn.label) { send(btn.toCode(), 1) }
                b.setOnLongClickListener {
                    AlertDialog.Builder(act)
                        .setMessage("حذف الزر «${btn.label}»؟")
                        .setPositiveButton("حذف") { _, _ ->
                            store.deleteButton(r.name, r.buttons.indexOf(btn)); refreshRemotes()
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                    true
                }
                row?.addView(b)
            }
            remotesBox.addView(Ui.wideButton(act, "حذف الريموت «${r.name}»") {
                store.deleteRemote(r.name); refreshRemotes()
            })
        }
    }

    // ------------------------------------------------------------ أدوات

    private fun copy(text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ir", text))
        Ui.toast(act, "تم النسخ")
    }

    private fun showError(e: Throwable) {
        Ui.toast(act, e.message ?: e.toString())
    }

    fun onPause() { scanning = false }
}
