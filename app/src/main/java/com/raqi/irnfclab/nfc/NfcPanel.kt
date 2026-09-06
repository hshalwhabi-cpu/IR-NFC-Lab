package com.raqi.irnfclab.nfc

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.raqi.irnfclab.ui.Ui
import com.raqi.irnfclab.util.Hex

/**
 * واجهة التحكم الكامل بتقنية NFC.
 * الوضع يحدّد ما يحدث عند لمس تاق:
 *   قراءة / كتابة NDEF / APDU خام / لا شيء (أثناء HCE).
 */
class NfcPanel(private val act: Activity) {

    enum class Mode { READ, WRITE, APDU, EMV }
    @Volatile var mode = Mode.READ
        private set

    private var adapter: NfcAdapter? = null

    private lateinit var statusTv: TextView
    private lateinit var output: TextView
    private lateinit var emvResult: TextView
    private var lastEmvTechnical: String = ""

    // كتابة
    private lateinit var writeTypeSpinner: Spinner
    private lateinit var writeValue: EditText
    private lateinit var writeLang: EditText
    private var pendingLock = false

    // APDU
    private lateinit var apduPresetSpinner: Spinner
    private lateinit var apduInput: EditText

    // HCE
    private lateinit var hceText: EditText
    private lateinit var hceResp: EditText
    private lateinit var hceLog: TextView

    fun build(): View {
        adapter = NfcAdapter.getDefaultAdapter(act)
        val root = Ui.vertical(act)

        // ------------------------------------------------ الحالة
        root.addView(Ui.card(act, "حالة NFC").apply {
            statusTv = Ui.mono(act, nfcStatus())
            addView(statusTv)
            addView(Ui.wideButton(act, "تحديث + فتح إعدادات NFC") {
                statusTv.text = nfcStatus()
                if (adapter?.isEnabled != true) {
                    try { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) }
                    catch (e: Exception) { Ui.toast(act, "تعذّر فتح الإعدادات") }
                }
            })
        })

        // ------------------------------------------------ زر البطاقة البنكية السريع
        root.addView(Ui.card(act, "💳 قراءة بطاقة بنكية (المعلومات العلنية)").apply {
            addView(Ui.label(act,
                "اضغط الزر ثم قرّب بطاقتك من ظهر الجوال وثبّتها ٢-٣ ثوانٍ.\n" +
                "يقرأ التطبيق تلقائيًا: رقم البطاقة، تاريخ الانتهاء، والشبكة (Visa/مدى...).\n" +
                "ملاحظة أمنية: الرقم السري (PIN) و CVV والمفاتيح لا يمكن قراءتها إطلاقًا — محمية داخل الشريحة."))
            addView(Ui.wideButton(act, "💳 فعّل قراءة البطاقة — ثم قرّبها") {
                mode = Mode.EMV
                emvResult.text = "الوضع جاهز ✓ — قرّب البطاقة الآن..."
                Ui.toast(act, "قرّب البطاقة من ظهر الجوال")
            })
            emvResult = Ui.mono(act, "لم تُقرأ بطاقة بعد.")
            addView(emvResult)
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "نسخ النتيجة") { copy(emvResult.text.toString()) })
                addView(Ui.button(act, "عرض التفاصيل التقنية") {
                    output.text = lastEmvTechnical.ifBlank { "لا توجد تفاصيل بعد" }
                })
            })
        })

        // ------------------------------------------------ اختيار الوضع
        root.addView(Ui.card(act, "الوضع الحالي عند لمس تاق").apply {
            val modeSpinner = Ui.spinner(act, listOf(
                "قراءة كل شيء (Dump كامل)",
                "كتابة NDEF على التاق",
                "إرسال أوامر APDU خام",
                "قراءة بطاقة بنكية (EMV) 💳"
            ))
            addView(modeSpinner)
            addView(Ui.label(act, "غيّر الوضع ثم قرّب التاق من الجوال."))
            modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    mode = when (pos) { 1 -> Mode.WRITE; 2 -> Mode.APDU; 3 -> Mode.EMV; else -> Mode.READ }
                    Ui.toast(act, "الوضع: ${listOf("قراءة","كتابة","APDU","بطاقة بنكية")[pos.coerceIn(0,3)]}")
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        })

        // ------------------------------------------------ الكتابة
        root.addView(Ui.card(act, "إعداد الكتابة (NDEF)").apply {
            writeTypeSpinner = Ui.spinner(act, listOf(
                "نص (Text)", "رابط (URI)", "MIME", "تشغيل تطبيق (AAR)", "خارجي (External)"))
            addView(writeTypeSpinner)
            writeValue = Ui.input(act, "القيمة (نص، رابط، اسم حزمة...)", "https://claude.ai")
            addView(Ui.label(act, "القيمة"))
            addView(writeValue)
            writeLang = Ui.input(act, "رمز اللغة للنص", "ar")
            addView(Ui.label(act, "لغة النص / نوع MIME / نوع السجل الخارجي"))
            addView(writeLang)
            val lockBox = Ui.check(act, "قفل التاق نهائيًا بعد الكتابة (لا رجعة!)")
            addView(lockBox)
            addView(Ui.label(act,
                "فعّل وضع «كتابة NDEF» بالأعلى ثم قرّب التاق ليُكتب عليه فورًا."))
            lockBox.setOnCheckedChangeListener { _, c -> pendingLock = c }
        })

        // ------------------------------------------------ APDU
        root.addView(Ui.card(act, "أوامر APDU الخام (IsoDep)").apply {
            addView(Ui.label(act, "اختر أمرًا جاهزًا أو اكتب أوامرك (كل سطر أمر مستقل بصيغة HEX)"))
            apduPresetSpinner = Ui.spinner(act, ApduRunner.PRESETS.keys.toList())
            addView(apduPresetSpinner)
            addView(Ui.wideButton(act, "أضف الأمر الجاهز للأسفل ↓") {
                val key = apduPresetSpinner.selectedItem as String
                val cur = apduInput.text.toString()
                apduInput.setText((if (cur.isBlank()) "" else cur.trimEnd() + "\n") + ApduRunner.PRESETS[key])
            })
            apduInput = Ui.input(act, "00A4040007D276000085010100", "00A404000E325041592E5359532E444446303100", 5)
            addView(apduInput)
            addView(Ui.label(act, "فعّل وضع «APDU» بالأعلى ثم قرّب البطاقة لتنفيذ كل الأوامر بالتسلسل."))
        })

        // ------------------------------------------------ HCE
        root.addView(Ui.card(act, "محاكاة بطاقة (HCE) — جوالك يصير بطاقة").apply {
            addView(Ui.label(act,
                "الجوال يردّ على أي قارئ NFC آخر. النص أدناه يُقرأ كتاق NDEF، " +
                "والرد المخصص يُرسل لأي أمر على الـ AID المخصص F0524151492D4C4142."))
            hceText = Ui.input(act, "نص البطاقة المُحاكاة", HceService.ndefText)
            addView(Ui.label(act, "نص NDEF المُحاكى"))
            addView(hceText)
            hceResp = Ui.input(act, "9000", HceService.customResponseHex)
            addView(Ui.label(act, "الرد المخصص (HEX) على الـ AID المخصص"))
            addView(hceResp)
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "تطبيق الإعدادات") {
                    HceService.ndefText = hceText.text.toString()
                    HceService.customResponseHex = hceResp.text.toString().ifBlank { "9000" }
                    Ui.toast(act, "تم — قرّب قارئًا آخر من جوالك")
                })
                addView(Ui.button(act, "تحديث السجل") { hceLog.text = HceService.lastLog.ifBlank { "لا يوجد نشاط بعد" } })
            })
            hceLog = Ui.mono(act, "سجل تبادل HCE سيظهر هنا")
            addView(hceLog)
        })

        // ------------------------------------------------ المخرجات
        root.addView(Ui.card(act, "المخرجات").apply {
            addView(Ui.horizontal(act).apply {
                addView(Ui.button(act, "نسخ") { copy(output.text.toString()) })
                addView(Ui.button(act, "مسح") { output.text = "" })
            })
            output = Ui.mono(act, "قرّب تاق NFC للبدء...")
            addView(output)
        })

        return Ui.scroll(act, root)
    }

    private fun nfcStatus(): String {
        val a = adapter
        val sb = StringBuilder()
        sb.append("عتاد NFC : ").append(if (a == null) "غير موجود ✗" else "موجود ✓").append("\n")
        if (a != null) {
            sb.append("مُفعّل    : ").append(if (a.isEnabled) "نعم ✓" else "لا — فعّله من الإعدادات ✗").append("\n")
            val hce = act.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
            sb.append("HCE      : ").append(if (hce) "مدعوم ✓" else "غير مدعوم ✗").append("\n")
        }
        return sb.toString()
    }

    /** يُستدعى من MainActivity عند اكتشاف تاق */
    fun onTag(tag: Tag) {
        Thread {
            val text: String = try {
                when (mode) {
                    Mode.READ -> TagReader.describe(tag)
                    Mode.WRITE -> doWrite(tag)
                    Mode.APDU -> doApdu(tag)
                    Mode.EMV -> doEmv(tag)
                }
            } catch (e: Throwable) {
                "خطأ: ${e.message}"
            }
            val m = mode
            act.runOnUiThread {
                if (m == Mode.EMV) {
                    emvResult.text = text
                } else {
                    output.text = text
                }
            }
        }.start()
    }

    private fun doWrite(tag: Tag): String {
        val value = writeValue.text.toString()
        val extra = writeLang.text.toString()
        val record: NdefRecord = when (writeTypeSpinner.selectedItemPosition) {
            0 -> TagWriter.textRecord(value, extra.ifBlank { "ar" })
            1 -> TagWriter.uriRecord(value)
            2 -> TagWriter.mimeRecord(extra.ifBlank { "text/plain" }, value.toByteArray())
            3 -> TagWriter.appRecord(value)
            4 -> TagWriter.externalRecord("raqi.com", extra.ifBlank { "data" }, value.toByteArray())
            else -> TagWriter.textRecord(value)
        }
        val msg = NdefMessage(arrayOf(record))
        val res = TagWriter.write(tag, msg)
        var out = "$res\nالسجل: ${TagReader.recordSummary(record)}"
        if (pendingLock) {
            out += "\n" + try { TagWriter.makeReadOnly(tag) } catch (e: Exception) { "فشل القفل: ${e.message}" }
        }
        return out
    }

    private fun doEmv(tag: Tag): String {
        val c = EmvReader.read(tag)
        lastEmvTechnical = "═══ سجل الأوامر ═══\n" + c.log.toString() +
            "\n═══ السجلات (TLV) ═══\n" + c.raw.toString()
        if (c.pan.isEmpty()) {
            return "لم أتمكّن من قراءة رقم البطاقة.\n" +
                "الأسباب المحتملة:\n" +
                "• البطاقة تلامسية غير مفعّلة أو أُبعدت بسرعة — ثبّتها أطول.\n" +
                "• بطاقة تتطلب معطيات طرفية إضافية (بعض البطاقات لا تكشف الرقم بلا جهاز دفع حقيقي).\n" +
                "اضغط «عرض التفاصيل التقنية» لرؤية ردود البطاقة."
        }
        val sb = StringBuilder()
        sb.append("✅ تمت القراءة\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("الشبكة       : ").append(c.scheme).append("\n")
        if (c.appLabel.isNotEmpty()) sb.append("التطبيق      : ").append(c.appLabel).append("\n")
        sb.append("رقم البطاقة  : ").append(EmvReader.formatPan(c.pan)).append("\n")
        if (c.expiry.isNotEmpty())    sb.append("تاريخ الانتهاء: ").append(c.expiry).append("  (شهر/سنة)\n")
        if (c.cardholder.isNotEmpty())sb.append("حامل البطاقة : ").append(c.cardholder).append("\n")
        if (c.panSeq.isNotEmpty())    sb.append("تسلسل البطاقة: ").append(c.panSeq).append("\n")
        if (c.aid.isNotEmpty())       sb.append("AID          : ").append(c.aid).append("\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("🔒 لم يُقرأ (ولا يمكن): PIN، CVV، المفتاح السري.")
        return sb.toString()
    }

    private fun doApdu(tag: Tag): String {
        val lines = apduInput.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "لا توجد أوامر — اكتب أمرًا واحدًا على الأقل"
        val results = ApduRunner.runIsoDep(tag, lines)
        return results.joinToString("\n") { it.pretty() }
    }

    private fun copy(text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("nfc", text))
        Ui.toast(act, "تم النسخ")
    }
}
