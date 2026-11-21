package com.xfilter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File

class XFilterService : AccessibilityService() {

    companion object {
        const val PKG = "com.twitter.android"
        private val CARET_DESC = setOf("mais opcoes", "mais", "more", "more options", "mas opciones")
        private val NOT_INT = listOf("nao tenho interesse", "not interested", "no me interesa", "nao me interessa")
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
    private var stateSince = 0L
    private var target: Target? = null
    private var lastXEvent = 0L
    private var lastAction = 0L
    private var scanQueued = false
    private val handled = LinkedHashSet<String>()
    private val actionTimes = mutableListOf<Long>()

    private val scanRunnable = Runnable {
        scanQueued = false
        tick()
    }

    private val poll = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastXEvent < 15000 || Prefs.dumpAt(this@XFilterService) > 0) tick()
            handler.postDelayed(this, 2000)
        }
    }

    private val timeout = Runnable {
        if (state == State.WAIT_MENU) {
            Prefs.addLog(this@XFilterService, "⚠ menu não abriu / opção não encontrada")
            reset()
        }
    }

    override fun onServiceConnected() {
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 2000)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != PKG) return
        lastXEvent = SystemClock.elapsedRealtime()
        if (!scanQueued) {
            scanQueued = true
            handler.postDelayed(scanRunnable, 300)
        }
    }

    // ---------------------------------------------------------------- núcleo

    private fun tick() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != PKG) return
        maybeDump(root)
        if (!Prefs.enabled(this)) return
        when (state) {
            State.IDLE -> scanFeed(root)
            State.WAIT_MENU -> handleMenu(root)
        }
    }

    private fun scanFeed(root: AccessibilityNodeInfo) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAction < 2000) return

        val cfg = Prefs.config(this)
        val hasRules = cfg.words.isNotEmpty() || cfg.accounts.isNotEmpty() ||
            (cfg.interestsMode && cfg.interests.isNotEmpty())
        if (!hasRules) return

        val dry = Prefs.dryRun(this)
        actionTimes.removeAll { now - it > 3_600_000L }
        if (!dry && actionTimes.size >= Prefs.maxPerHour(this)) return

        val carets = ArrayList<AccessibilityNodeInfo>()
        walk(root) { if (isCaret(it)) carets.add(it) }
        val r = Rect()
        carets.sortBy { n -> n.getBoundsInScreen(r); r.top }
        val h = resources.displayMetrics.heightPixels

        for (caret in carets) {
            caret.getBoundsInScreen(r)
            // só age em posts bem visíveis (evita clicar em algo cortado/atrás de barra)
            if (r.centerY() < h * 0.12 || r.centerY() > h * 0.88) continue

            val row = rowOf(caret) ?: continue
            val text = rowText(row)
            if (text.length < 5) continue

            val key = text.filter { it.isLetter() }.take(90).hashCode().toString()
            if (key in handled) continue

            val hit = Matcher.check(text, cfg) ?: continue
            remember(key)
            val preview = text.replace('\n', ' ').take(90)

            if (dry) {
                Prefs.addLog(this, "[TESTE] ${hit.reason}: $preview")
                continue
            }
            if (clickNode(caret)) {
                target = Target(key, hit, preview)
                state = State.WAIT_MENU
                stateSince = now
                lastAction = now
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, 3500)
                return
            }
        }
    }

    private fun handleMenu(root: AccessibilityNodeInfo) {
        val t = target ?: run { reset(); return }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        walk(root) { if (!it.text.isNullOrBlank()) nodes.add(it) }
        val labels = nodes.map { Matcher.norm(it.text.toString()).trim() }

        val notInt = labels.indexOfFirst { l -> NOT_INT.any { l.startsWith(it) } }
        val mute = if (t.hit.kind == Kind.ACCOUNT)
            labels.indexOfFirst { l -> MUTE.any { l.startsWith(it) } && l.endsWith(t.hit.reason) }
        else -1
        val menuOpen = notInt >= 0 || mute >= 0 ||
            labels.any { l -> MENU_HINTS.any { l.startsWith(it) } }
        if (!menuOpen) return // ainda abrindo; o timeout cuida do resto

        handler.removeCallbacks(timeout)
        val pick = if (mute >= 0) mute else notInt
        if (pick >= 0 && clickNode(nodes[pick])) {
            Prefs.incTotal(this)
            actionTimes.add(SystemClock.elapsedRealtime())
            val icon = if (pick == mute) "🔇 silenciou" else "🚫"
            Prefs.addLog(this, "$icon ${t.hit.reason}: ${t.preview}")
        } else {
            Prefs.addLog(this, "⚠ menu sem a opção (${t.hit.reason}) — fechando")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        reset()
        lastAction = SystemClock.elapsedRealtime()
    }

    private fun reset() {
        state = State.IDLE
        target = null
        handler.removeCallbacks(timeout)
    }

    private fun remember(k: String) {
        handled.add(k)
        if (handled.size > 600) handled.remove(handled.first())
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

    // sobe a partir do ⋮ enquanto o ancestral contiver só 1 ⋮ → esse é o "post"
    private fun rowOf(caret: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var cur: AccessibilityNodeInfo = caret
        for (i in 0 until 14) {
            val p = cur.parent ?: break
            val cls = p.className?.toString() ?: ""
            if (cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("ScrollView")) break
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

    private fun maybeDump(root: AccessibilityNodeInfo) {
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
        rec(root, 0)

        File(filesDir, "dump.txt").writeText(sb.toString())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("dump", sb.toString()))
        Toast.makeText(this, "Dump copiado (${sb.length} caracteres)", Toast.LENGTH_LONG).show()
    }
}
