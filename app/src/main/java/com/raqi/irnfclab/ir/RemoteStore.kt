package com.raqi.irnfclab.ir

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class IrButton(
    var label: String,
    var carrier: Int,
    var pattern: IntArray,
    var repeat: IntArray? = null
) {
    fun toCode(): IrCode = IrCode(carrier, pattern, repeat, label)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("label", label)
        o.put("carrier", carrier)
        o.put("pattern", JSONArray().apply { pattern.forEach { put(it) } })
        repeat?.let { r -> o.put("repeat", JSONArray().apply { r.forEach { put(it) } }) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): IrButton {
            fun arr(name: String): IntArray? {
                val a = o.optJSONArray(name) ?: return null
                return IntArray(a.length()) { a.getInt(it) }
            }
            return IrButton(
                o.optString("label", "زر"),
                o.optInt("carrier", 38000),
                arr("pattern") ?: IntArray(0),
                arr("repeat")
            )
        }
    }
}

class IrRemote(var name: String) {
    val buttons = ArrayList<IrButton>()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("buttons", JSONArray().apply { buttons.forEach { put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): IrRemote {
            val r = IrRemote(o.optString("name", "ريموت"))
            val a = o.optJSONArray("buttons")
            if (a != null) for (i in 0 until a.length()) r.buttons.add(IrButton.fromJson(a.getJSONObject(i)))
            return r
        }
    }
}

/** تخزين الريموتات المخصصة في ملف JSON داخل مساحة التطبيق الخاصة. */
class RemoteStore(ctx: Context) {

    private val file = File(ctx.filesDir, "remotes.json")
    val remotes = ArrayList<IrRemote>()

    init { load() }

    fun load() {
        remotes.clear()
        if (!file.exists()) return
        try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) remotes.add(IrRemote.fromJson(a.getJSONObject(i)))
        } catch (e: Exception) {
            // ملف تالف — نتجاهله بدل أن ينهار التطبيق
        }
    }

    fun save() {
        val a = JSONArray()
        remotes.forEach { a.put(it.toJson()) }
        file.writeText(a.toString(2))
    }

    fun remote(name: String): IrRemote {
        remotes.firstOrNull { it.name == name }?.let { return it }
        val r = IrRemote(name)
        remotes.add(r)
        return r
    }

    fun addButton(remoteName: String, button: IrButton) {
        remote(remoteName).buttons.add(button)
        save()
    }

    fun deleteButton(remoteName: String, index: Int) {
        val r = remotes.firstOrNull { it.name == remoteName } ?: return
        if (index in r.buttons.indices) r.buttons.removeAt(index)
        save()
    }

    fun deleteRemote(name: String) {
        remotes.removeAll { it.name == name }
        save()
    }

    fun exportJson(): String {
        val a = JSONArray()
        remotes.forEach { a.put(it.toJson()) }
        return a.toString(2)
    }

    fun importJson(text: String): Int {
        val a = JSONArray(text)
        var n = 0
        for (i in 0 until a.length()) {
            remotes.add(IrRemote.fromJson(a.getJSONObject(i)))
            n++
        }
        save()
        return n
    }
}
