package com.xfilter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var scan: TextView
    private lateinit var total: TextView
    private lateinit var logView: TextView
    private lateinit var swInterests: Switch
    private lateinit var maxField: EditText
    private val fields = LinkedHashMap<String, EditText>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(p: LinearLayout, txt: String, size: Float = 14f): TextView =
        TextView(this).apply {
            text = txt
            textSize = size
            setPadding(0, dp(12), 0, dp(4))
        }.also { p.addView(it) }

    private fun button(p: LinearLayout, txt: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = txt
            setOnClickListener { onClick() }
        }.also { p.addView(it) }

    private fun sw(p: LinearLayout, txt: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = txt
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
            setPadding(0, dp(8), 0, dp(8))
        }.also { p.addView(it) }

    private fun field(p: LinearLayout, key: String, hintTxt: String): EditText =
        EditText(this).apply {
            setText(Prefs.text(this@MainActivity, key))
            hint = hintTxt
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }.also {
            p.addView(it)
            fields[key] = it
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        label(root, "X Filter 1.2", 26f)
        status = label(root, "")
        scan = label(root, "")
        total = label(root, "", 16f)
        button(root, "ATUALIZAR status e log") { refresh() }
        logView = label(root, "", 12f)

        button(root, "1) Abrir Acessibilidade e ativar o X Filter") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(root, "Se estiver bloqueado: Info do app → ⋮ → Permitir configurações restritas") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        sw(root, "Filtro ligado", Prefs.enabled(this)) { Prefs.setBool(this, "enabled", it) }
        sw(root, "MODO TESTE (só registra no log, não toca em nada)", Prefs.dryRun(this)) {
            Prefs.setBool(this, "dry", it)
        }

        label(root, "Palavras / hashtags a esconder (uma por linha). Use re:padrão para regex.")
        field(root, "words", "gta\nrockstar\nre:gta\\s?(6|vi)")
        label(root, "Contas a silenciar (@handle, uma por linha)")
        field(root, "accounts", "@exemplo")
        label(root, "Exceções: se o post tiver isto, NÃO filtra")
        field(root, "allow", "")

        swInterests = sw(root, "Só mostrar meus interesses (agressivo: tudo fora da lista vira \"não tenho interesse\")",
            Prefs.interestsMode(this)) { }
        label(root, "Interesses (uma por linha)")
        field(root, "interests", "warframe\nandroid\nkotlin")

        label(root, "Máximo de ações por hora")
        maxField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.maxPerHour(this@MainActivity).toString())
        }
        root.addView(maxField)

        button(root, "SALVAR") { save() }

        label(root, "Diagnóstico", 18f)
        button(root, "Capturar tela do X em 8s (copia o dump)") {
            Prefs.setDumpAt(this, System.currentTimeMillis() + 8000)
            Toast.makeText(this, "Abra o X agora e deixe o post/menu na tela", Toast.LENGTH_LONG).show()
        }
        button(root, "Limpar log") { Prefs.clearLog(this); refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun save() {
        fields.forEach { (k, v) -> Prefs.setText(this, k, v.text.toString()) }
        Prefs.setBool(this, "interestsMode", swInterests.isChecked)
        Prefs.setInt(this, "maxPerHour", maxField.text.toString().toIntOrNull()?.coerceIn(1, 500) ?: 60)
        Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
    }

    private fun serviceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains("$packageName/") && s.contains("XFilterService")
    }

    private fun refresh() {
        status.text = if (serviceOn()) "✅ Serviço ativo" else "❌ Serviço desligado"
        scan.text = "Última leitura: " + Prefs.status(this)
        total.text = "Posts filtrados: ${Prefs.total(this)}"
        logView.text = Prefs.log(this).take(8).joinToString("\n\n").ifEmpty { "(log vazio)" }
    }
}
