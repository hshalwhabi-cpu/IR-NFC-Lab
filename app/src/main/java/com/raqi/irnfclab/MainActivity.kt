package com.raqi.irnfclab

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.raqi.irnfclab.ir.IrPanel
import com.raqi.irnfclab.nfc.NfcPanel
import com.raqi.irnfclab.ui.Ui

class MainActivity : Activity() {

    private lateinit var irPanel: IrPanel
    private lateinit var nfcPanel: NfcPanel

    private lateinit var container: FrameLayout
    private lateinit var irView: View
    private lateinit var nfcView: View
    private lateinit var tabIr: Button
    private lateinit var tabNfc: Button

    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF0B1218.toInt())
        root.fitsSystemWindows = true   // لا يختبئ خلف شريط الحالة/العنوان

        // ===== عنوان التطبيق =====
        val titleBar = android.widget.TextView(this)
        titleBar.text = "IR & NFC Lab"
        titleBar.setTextColor(Ui.ACCENT)
        titleBar.setTypeface(null, android.graphics.Typeface.BOLD)
        titleBar.textSize = 18f
        titleBar.gravity = Gravity.CENTER
        val tpad = (12 * resources.displayMetrics.density).toInt()
        titleBar.setPadding(tpad, tpad, tpad, tpad / 2)
        titleBar.setBackgroundColor(0xFF16202B.toInt())
        root.addView(titleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ===== شريط التبويبات =====
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setBackgroundColor(0xFF16202B.toInt())
        val tabH = (52 * resources.displayMetrics.density).toInt()
        tabIr = tabButton("📡  الأشعة IR") { showIr() }
        tabNfc = tabButton("📶  NFC") { showNfc() }
        tabs.addView(tabIr, LinearLayout.LayoutParams(0, tabH, 1f))
        tabs.addView(tabNfc, LinearLayout.LayoutParams(0, tabH, 1f))
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // خط سفلي فاصل تحت التبويبات
        val divider = View(this)
        divider.setBackgroundColor(0xFF2A3644.toInt())
        root.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (2 * resources.displayMetrics.density).toInt()))

        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        irPanel = IrPanel(this)
        nfcPanel = NfcPanel(this)
        irView = irPanel.build()
        nfcView = nfcPanel.build()
        container.addView(irView)
        container.addView(nfcView)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        showIr()

        // إن فُتح التطبيق بسبب لمس تاق
        handleIntent(intent)
    }

    private fun tabButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 15f
        b.setTypeface(null, android.graphics.Typeface.BOLD)
        b.setOnClickListener { onClick() }
        b.setBackgroundColor(Color.TRANSPARENT)
        b.setTextColor(Color.LTGRAY)
        b.gravity = Gravity.CENTER
        return b
    }

    private fun showIr() {
        irView.visibility = View.VISIBLE
        nfcView.visibility = View.GONE
        tabIr.setTextColor(Ui.ACCENT)
        tabIr.setBackgroundColor(0xFF20303F.toInt())
        tabNfc.setTextColor(Color.LTGRAY)
        tabNfc.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun showNfc() {
        irView.visibility = View.GONE
        nfcView.visibility = View.VISIBLE
        tabNfc.setTextColor(Ui.ACCENT)
        tabNfc.setBackgroundColor(0xFF20303F.toInt())
        tabIr.setTextColor(Color.LTGRAY)
        tabIr.setBackgroundColor(Color.TRANSPARENT)
    }

    // ---------------------------------------------------------- NFC dispatch

    override fun onResume() {
        super.onResume()
        enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        try { nfcAdapter?.disableForegroundDispatch(this) } catch (e: Exception) {}
        irPanel.onPause()
    }

    private fun enableForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return
        val flags = if (Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(this, 0, intent, flags)
        try {
            adapter.enableForegroundDispatch(this, pi, null, null)
        } catch (e: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val action = intent.action ?: return
        if (action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            if (tag != null) {
                showNfc()
                nfcPanel.onTag(tag)
            }
        }
    }
}
