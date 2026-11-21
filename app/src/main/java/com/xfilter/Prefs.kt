package com.xfilter

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private val DEFAULTS = mapOf("words" to "gta\nrockstar")

    private fun sp(c: Context) = c.getSharedPreferences("xfilter", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun dryRun(c: Context) = sp(c).getBoolean("dry", true) // começa em modo teste
    fun interestsMode(c: Context) = sp(c).getBoolean("interestsMode", false)
    fun maxPerHour(c: Context) = sp(c).getInt("maxPerHour", 60)
    fun total(c: Context) = sp(c).getInt("total", 0)
    fun dumpAt(c: Context) = sp(c).getLong("dumpAt", 0L)

    fun setBool(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()
    fun setInt(c: Context, key: String, v: Int) = sp(c).edit().putInt(key, v).apply()
    fun setDumpAt(c: Context, v: Long) = sp(c).edit().putLong("dumpAt", v).apply()
    fun incTotal(c: Context) = sp(c).edit().putInt("total", total(c) + 1).apply()

    fun text(c: Context, key: String): String = sp(c).getString(key, DEFAULTS[key] ?: "") ?: ""
    fun setText(c: Context, key: String, v: String) = sp(c).edit().putString(key, v).apply()
    fun list(c: Context, key: String): List<String> =
        text(c, key).split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun config(c: Context) = Config(
        list(c, "words"), list(c, "accounts"), list(c, "allow"), list(c, "interests"), interestsMode(c)
    )

    @Synchronized
    fun addLog(c: Context, line: String) {
        val old = JSONArray(sp(c).getString("log", "[]"))
        val out = JSONArray()
        out.put(SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date()) + "  " + line)
        for (i in 0 until minOf(old.length(), 59)) out.put(old.getString(i))
        sp(c).edit().putString("log", out.toString()).apply()
    }

    fun log(c: Context): List<String> {
        val a = JSONArray(sp(c).getString("log", "[]"))
        return (0 until a.length()).map { a.getString(it) }
    }

    fun clearLog(c: Context) = sp(c).edit().putString("log", "[]").apply()
}
