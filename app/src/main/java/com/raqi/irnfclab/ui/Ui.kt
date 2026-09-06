package com.raqi.irnfclab.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** مولّدات واجهة برمجية بسيطة — لا حاجة لملفات XML للتخطيط. */
object Ui {

    const val ACCENT = 0xFF4FC3F7.toInt()
    const val CARD = 0xFF16202B.toInt()
    const val OK = 0xFF81C784.toInt()
    const val ERR = 0xFFE57373.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun vertical(c: Context, pad: Int = 12): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad))
        l.layoutParams = matchWrap()
        return l
    }

    fun horizontal(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.layoutParams = matchWrap()
        return l
    }

    /** بطاقة: صندوق عمودي بخلفية داكنة */
    fun card(c: Context, title: String): LinearLayout {
        val l = vertical(c, 12)
        l.setBackgroundColor(CARD)
        val lp = matchWrap()
        lp.topMargin = dp(c, 10)
        l.layoutParams = lp
        if (title.isNotEmpty()) l.addView(header(c, title))
        return l
    }

    fun header(c: Context, t: String): TextView {
        val tv = TextView(c)
        tv.text = t
        tv.setTextColor(ACCENT)
        tv.setTypeface(null, Typeface.BOLD)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        tv.setPadding(0, dp(c, 4), 0, dp(c, 8))
        return tv
    }

    fun label(c: Context, t: String, color: Int = Color.LTGRAY): TextView {
        val tv = TextView(c)
        tv.text = t
        tv.setTextColor(color)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.setPadding(0, dp(c, 2), 0, dp(c, 2))
        return tv
    }

    fun mono(c: Context, t: String = ""): TextView {
        val tv = TextView(c)
        tv.text = t
        tv.typeface = Typeface.MONOSPACE
        tv.setTextColor(Color.WHITE)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.setTextIsSelectable(true)
        tv.layoutDirection = View.LAYOUT_DIRECTION_LTR
        tv.textDirection = View.TEXT_DIRECTION_LTR
        tv.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6))
        tv.setBackgroundColor(0xFF0B1218.toInt())
        return tv
    }

    fun input(c: Context, hint: String, value: String = "", lines: Int = 1): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setText(value)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.GRAY)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        e.layoutParams = matchWrap()
        if (lines > 1) {
            e.setLines(lines)
            e.gravity = Gravity.TOP or Gravity.START
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            e.typeface = Typeface.MONOSPACE
            e.layoutDirection = View.LAYOUT_DIRECTION_LTR
            e.textDirection = View.TEXT_DIRECTION_LTR
        } else {
            e.isSingleLine = true
        }
        return e
    }

    fun button(c: Context, t: String, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = t
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(c, 2), dp(c, 4), dp(c, 2), dp(c, 4))
        b.layoutParams = lp
        return b
    }

    fun wideButton(c: Context, t: String, onClick: () -> Unit): Button {
        val b = button(c, t, onClick)
        b.layoutParams = matchWrap()
        return b
    }

    fun check(c: Context, t: String, checked: Boolean = false): CheckBox {
        val cb = CheckBox(c)
        cb.text = t
        cb.isChecked = checked
        cb.setTextColor(Color.LTGRAY)
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        return cb
    }

    fun spinner(c: Context, items: List<String>): Spinner {
        val s = Spinner(c)
        val ad = ArrayAdapter(c, android.R.layout.simple_spinner_item, items)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        s.adapter = ad
        s.layoutParams = matchWrap()
        return s
    }

    fun scroll(c: Context, child: View): ScrollView {
        val sv = ScrollView(c)
        sv.isFillViewport = true
        sv.addView(child)
        sv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return sv
    }

    fun toast(c: Context, m: String) {
        Toast.makeText(c, m, Toast.LENGTH_SHORT).show()
    }
}
