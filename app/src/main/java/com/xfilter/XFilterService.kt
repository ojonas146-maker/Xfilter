package com.xfilter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class XFilterService : AccessibilityService() {

    companion object {
        const val PKG = "com.twitter.android"
        private val CARET_DESC = setOf(
            "opcoes do post", "opcoes da postagem", "post options", "more post options",
            "mais opcoes", "mais", "more", "more options", "mas opciones"
        )
        private val NOT_INT = listOf(
            "nao tenho interesse", "not interested", "no me interesa", "nao me interessa",
            "nao curto este anuncio",
            "esta publicacao nao e util", "this post isn", "esta publicacion no es util"
        )
        private val MUTE = listOf("silenciar @", "mute @", "silenciar a @")
        private val MENU_HINTS = listOf(
            "denunciar post", "report post", "bloquear @", "block @", "silenciar @", "mute @",
            "adicionar/remover das listas", "add/remove from lists"
        )
    }

    private enum class State { IDLE, WAIT_MENU }
    private class Target(val key: String, val hit: Hit, val preview: String)

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var target: Target? = null
    private var lastXEvent = 0L
    private var lastAction = 0L
    private var lastStatusAt = 0L
    private var scanQueued = false
    private var settingsVersion = -1
    private var lastSeen = ""
    private var onHome = false          // só age no feed inicial (Para você / Seguindo)
    private var coolUntil = 0L          // freio de segurança após falhas seguidas
    private var failStreak = 0
    private var bubble: TextView? = null
    private var bubbleFailed = false

    private val handled = LinkedHashSet<String>()    // posts já resolvidos de verdade
    private val dryLogged = LinkedHashSet<String>()  // posts já registrados no modo teste
    private val tries = HashMap<String, Int>()
    private val actionTimes = mutableListOf<Long>()

    private val scanRunnable = Runnable {
        scanQueued = false
        tick()
    }

    private val poll = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastXEvent < 15000 || Prefs.dumpAt(this@XFilterService) > 0) tick()
            if (bubble != null) {
                val a = rootInActiveWindow
                if (a?.packageName?.toString() != PKG) hideBubble()
            }
            handler.postDelayed(this, 2000)
        }
    }

    private val timeout = Runnable {
        if (state == State.WAIT_MENU) {
            Prefs.addLog(this@XFilterService, "⚠ menu não apareceu. Textos vistos: " + lastSeen.ifEmpty { "(nada)" })
            registerFailure()
            reset()
            lastAction = SystemClock.elapsedRealtime()
        }
    }

    override fun onServiceConnected() {
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 2000)
        Prefs.setStatus(this, "serviço conectado — abra o X")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideBubble()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != PKG) return
        lastXEvent = SystemClock.elapsedRealtime()
        showBubble()
        if (!scanQueued) {
            scanQueued = true
            handler.postDelayed(scanRunnable, 300)
        }
    }

    // ---------------------------------------------------------------- núcleo

    private fun xRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == PKG) out.add(r)
            }
        } catch (e: Exception) {
            // sem acesso às janelas: cai no fallback abaixo
        }
        val a = rootInActiveWindow
        if (a != null && a.packageName?.toString() == PKG && out.none { it.windowId == a.windowId }) out.add(a)
        return out
    }

    private fun tick() {
        val roots = xRoots()
        if (roots.isEmpty()) return
        maybeDump(roots)
        if (!Prefs.enabled(this)) {
            status("filtro desligado")
            return
        }
        val v = Prefs.version(this)
        if (v != settingsVersion) {
            settingsVersion = v
            handled.clear(); dryLogged.clear(); tries.clear()
        }
        when (state) {
            State.IDLE -> if (Prefs.paused(this)) status("PAUSADO (bolinha)") else scanFeed(roots)
            State.WAIT_MENU -> handleMenu(roots)
        }
    }

    private fun scanFeed(roots: List<AccessibilityNodeInfo>) {
        val now = SystemClock.elapsedRealtime()
        updateScreen(roots)
        if (!onHome) {
            status("fora do feed inicial (busca/post/outra tela) — parado")
            return
        }
        if (now < coolUntil) {
            status("pausa de segurança (falhas seguidas)")
            return
        }
        val cfg = Prefs.config(this)
        val hasRules = cfg.words.isNotEmpty() || cfg.accounts.isNotEmpty() ||
            (cfg.interestsMode && cfg.interests.isNotEmpty())
        if (!hasRules) {
            status("sem regras cadastradas")
            return
        }

        val dry = Prefs.dryRun(this)
        actionTimes.removeAll { now - it > 3_600_000L }
        val limited = !dry && actionTimes.size >= Prefs.maxPerHour(this)

        val carets = ArrayList<AccessibilityNodeInfo>()
        var nodeCount = 0
        for (rt in roots) walk(rt) {
            nodeCount++
            if (isCaret(it)) carets.add(it)
        }
        if (carets.isEmpty()) diagnoseNoCaret(roots, nodeCount)
        val r = Rect()
        carets.sortBy { n -> n.getBoundsInScreen(r); r.top }
        val h = resources.displayMetrics.heightPixels

        var rows = 0
        var hits = 0
        var acted = false

        for (caret in carets) {
            caret.getBoundsInScreen(r)
            if (r.centerY() < h * 0.12 || r.centerY() > h * 0.88) continue

            val row = rowOf(caret) ?: continue
            val text = rowText(row)
            if (text.length < 5) continue
            rows++

            val key = text.filter { it.isLetter() }.take(90).hashCode().toString()
            val hit = Matcher.check(text, cfg) ?: continue
            hits++
            val preview = snippet(text, hit)

            if (dry) {
                if (dryLogged.add(key)) Prefs.addLog(this, "[TESTE] ${hit.reason}: $preview")
                continue
            }
            if (acted || limited || key in handled || now - lastAction < 2000) continue

            val n = tries[key] ?: 0
            if (n >= 2) {
                handled.add(key)
                Prefs.addLog(this, "⚠ desisti após 2 tentativas: $preview")
                continue
            }
            tries[key] = n + 1

            if (clickNode(caret)) {
                target = Target(key, hit, preview)
                state = State.WAIT_MENU
                lastAction = now
                lastSeen = ""
                acted = true
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, 3500)
            } else {
                Prefs.addLog(this, "⚠ não consegui tocar no ⋮ (${describe(caret)})")
                registerFailure()
            }
        }

        status("janelas ${roots.size} · nós $nodeCount · ⋮ ${carets.size} · posts $rows · casaram $hits · " +
            (if (dry) "TESTE" else if (limited) "limite/hora" else "ativo"))
    }

    private fun handleMenu(roots: List<AccessibilityNodeInfo>) {
        val t = target ?: run { reset(); return }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        for (r in roots) walk(r) { if (!it.text.isNullOrBlank()) nodes.add(it) }
        val labels = nodes.map { Matcher.norm(it.text.toString()).trim() }

        val notInt = labels.indexOfFirst { l -> NOT_INT.any { l.startsWith(it) } }
        val mute = if (t.hit.kind == Kind.ACCOUNT)
            labels.indexOfFirst { l -> MUTE.any { l.startsWith(it) } && l.endsWith(t.hit.reason) }
        else -1
        val menuOpen = notInt >= 0 || mute >= 0 ||
            labels.any { l -> MENU_HINTS.any { l.startsWith(it) } }
        if (!menuOpen) {
            lastSeen = labels.filter { it.length in 2..40 }.take(14).joinToString(" | ")
            return
        }

        handler.removeCallbacks(timeout)
        val pick = if (mute >= 0) mute else notInt
        if (pick >= 0 && clickNode(nodes[pick])) {
            Prefs.incTotal(this)
            failStreak = 0
            actionTimes.add(SystemClock.elapsedRealtime())
            val icon = if (pick == mute) "🔇 silenciou" else "🚫"
            Prefs.addLog(this, "$icon ${t.hit.reason}: ${t.preview}")
        } else {
            val vistos = labels.filter { it.length in 2..40 }.take(10).joinToString(" | ")
            Prefs.addLog(this, "⚠ menu abriu mas sem a opção. Vi: $vistos")
            registerFailure()
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        handled.add(t.key)
        reset()
        lastAction = SystemClock.elapsedRealtime()
    }

    // ---------------------------------------------------------------- tela / segurança / bolinha

    private fun updateScreen(roots: List<AccessibilityNodeInfo>) {
        val labels = HashSet<String>()
        var editText = false
        var homeId = false
        for (rt in roots) walk(rt) {
            val cls = it.className?.toString() ?: ""
            if (cls.endsWith("EditText")) editText = true
            if (it.viewIdResourceName?.endsWith("home_navigation_bar_title") == true) homeId = true
            val t = it.text?.toString()
            if (!t.isNullOrBlank() && t.length < 40) labels.add(Matcher.norm(t).trim())
            val d = it.contentDescription?.toString()
            if (!d.isNullOrBlank() && d.length < 40) labels.add(Matcher.norm(d).trim())
        }
        val explicitNeg =
            (("relevante" in labels) && ("ver comentarios" in labels)) ||
            (("principais" in labels) && ("mais recentes" in labels)) ||
            (("top" in labels) && ("latest" in labels))
        val positive = homeId ||
            (("para voce" in labels) && ("seguindo" in labels)) ||
            (("for you" in labels) && ("following" in labels))
        if (explicitNeg) onHome = false
        else if (positive) onHome = true
        else if (editText) onHome = false
        // sem pista nova: mantém o último estado (a barra do topo some ao rolar)
    }

    private fun registerFailure() {
        failStreak++
        if (failStreak >= 3) {
            failStreak = 0
            coolUntil = SystemClock.elapsedRealtime() + 60_000L
            Prefs.addLog(this, "⚠ 3 falhas seguidas — pausa de 60 s")
        }
    }

    private fun showBubble() {
        if (bubble != null || bubbleFailed) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = (44 * resources.displayMetrics.density).toInt()
            val tv = TextView(this)
            tv.gravity = Gravity.CENTER
            tv.textSize = 18f
            tv.setTextColor(Color.WHITE)
            tv.setOnClickListener {
                Prefs.setBool(this, "paused", !Prefs.paused(this))
                updateBubble()
            }
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            wm.addView(tv, lp)
            bubble = tv
            updateBubble()
        } catch (e: Exception) {
            bubbleFailed = true
            Prefs.addLog(this, "⚠ não consegui criar a bolinha: ${e.message}")
        }
    }

    private fun updateBubble() {
        val tv = bubble ?: return
        val paused = Prefs.paused(this)
        tv.text = if (paused) "▶" else "⏸"
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(if (paused) 0xCCE0245E.toInt() else 0xCC1D9BF0.toInt())
        tv.background = bg
    }

    private fun hideBubble() {
        val tv = bubble ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(tv)
        } catch (e: Exception) {
            // já removida
        }
        bubble = null
    }

    private fun reset() {
        state = State.IDLE
        target = null
        handler.removeCallbacks(timeout)
    }

    private fun status(s: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusAt < 1000) return
        lastStatusAt = now
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        Prefs.setStatus(this, "$hora · $s")
    }

    // ---------------------------------------------------------------- árvore de nós

    private fun walk(n: AccessibilityNodeInfo?, depth: Int = 0, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null || depth > 40) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    private fun isCaret(n: AccessibilityNodeInfo): Boolean {
        if (n.viewIdResourceName?.endsWith("tweet_curation_action") == true) return true
        val d = n.contentDescription?.toString() ?: return false
        return n.isClickable && Matcher.norm(d).trim() in CARET_DESC
    }

    private var lastDiagAt = 0L

    private fun diagnoseNoCaret(roots: List<AccessibilityNodeInfo>, nodeCount: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagAt < 60000) return
        lastDiagAt = now
        val descs = LinkedHashSet<String>()
        val ids = LinkedHashSet<String>()
        for (rt in roots) walk(rt) {
            val d = it.contentDescription?.toString()
            if (it.isClickable && !d.isNullOrBlank()) descs.add(d.take(25))
            val id = it.viewIdResourceName
            if (it.isClickable && id != null) ids.add(id.substringAfter(":id/"))
        }
        Prefs.addLog(this, "⚠ 0 ⋮ em $nodeCount nós (${roots.size} janelas). " +
            "Clicáveis desc: ${descs.take(12).joinToString(" | ")} · ids: ${ids.take(12).joinToString(" | ")}")
    }

    // trecho do post em volta da palavra que casou (para o log mostrar o motivo)
    private fun snippet(text: String, hit: Hit): String {
        val flat = text.replace('\n', ' ')
        if (hit.kind == Kind.WORD && !hit.reason.startsWith("re:")) {
            val i = Matcher.norm(flat).indexOf(Matcher.norm(hit.reason))
            if (i >= 0) {
                val a = (i - 35).coerceIn(0, flat.length)
                val b = (i + hit.reason.length + 55).coerceIn(a, flat.length)
                return "…" + flat.substring(a, b)
            }
        }
        return flat.take(90)
    }

    private fun describe(n: AccessibilityNodeInfo) =
        "id=${n.viewIdResourceName?.substringAfter(":id/")} desc=${n.contentDescription} click=${n.isClickable}"

    // sobe a partir do ⋮ enquanto o ancestral tiver só 1 ⋮ e começar perto do ⋮ (não engole anúncio de cima)
    private fun rowOf(caret: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cb = Rect()
        caret.getBoundsInScreen(cb)
        val maxUp = (90 * resources.displayMetrics.density).toInt()
        val pb = Rect()
        var best: AccessibilityNodeInfo? = null
        var cur: AccessibilityNodeInfo = caret
        for (i in 0 until 14) {
            val p = cur.parent ?: break
            val cls = p.className?.toString() ?: ""
            if (cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("ScrollView")) break
            p.getBoundsInScreen(pb)
            if (pb.top < cb.top - maxUp) break
            var c = 0
            walk(p) { if (isCaret(it)) c++ }
            if (c > 1) break
            best = p
            cur = p
        }
        return best
    }

    private fun rowText(row: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        walk(row) {
            val t = it.text?.toString()
            if (!t.isNullOrBlank()) {
                sb.append(t).append('\n')
            } else {
                val d = it.contentDescription?.toString()
                if (!d.isNullOrBlank() && !isCaret(it)) sb.append(d).append('\n')
            }
        }
        return if (sb.length > 3000) sb.substring(0, 3000) else sb.toString()
    }

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var d = 0
        while (c != null && d < 6) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
            d++
        }
        return false
    }

    // ---------------------------------------------------------------- diagnóstico

    private fun maybeDump(roots: List<AccessibilityNodeInfo>) {
        val at = Prefs.dumpAt(this)
        if (at <= 0 || System.currentTimeMillis() < at) return
        Prefs.setDumpAt(this, 0L)

        val sb = StringBuilder()
        fun rec(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40 || sb.length > 60000) return
            val t = n.text?.toString()
            val cd = n.contentDescription?.toString()
            val id = n.viewIdResourceName
            if (t != null || cd != null || id != null || n.isClickable) {
                val b = Rect()
                n.getBoundsInScreen(b)
                sb.append("  ".repeat(d))
                sb.append(n.className?.toString()?.substringAfterLast('.') ?: "?").append(' ')
                if (id != null) sb.append("id=").append(id.substringAfter(":id/")).append(' ')
                if (t != null) sb.append("text=\"").append(t.replace('\n', ' ').take(120)).append("\" ")
                if (cd != null) sb.append("desc=\"").append(cd.replace('\n', ' ').take(120)).append("\" ")
                if (n.isClickable) sb.append("[click] ")
                sb.append(b.flattenToString()).append('\n')
            }
            for (i in 0 until n.childCount) rec(n.getChild(i), d + 1)
        }
        for ((i, rt) in roots.withIndex()) {
            sb.append("=== janela ").append(i).append(" ===\n")
            rec(rt, 0)
        }

        File(filesDir, "dump.txt").writeText(sb.toString())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dump", sb.toString()))
        Toast.makeText(this, "Dump copiado (${sb.length} caracteres)", Toast.LENGTH_LONG).show()
    }
}
