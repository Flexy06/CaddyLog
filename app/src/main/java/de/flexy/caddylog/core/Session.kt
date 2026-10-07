package de.flexy.caddylog.core

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal JSON serializer (Map / List / String / Number / Boolean / null) – keeps the core dependency-free. */
object Json {
    fun write(v: Any?, indent: String = ""): String = buildString { append(this@Json, v, indent) }

    private fun StringBuilder.append(j: Json, v: Any?, ind: String) {
        val next = "$ind  "
        when (v) {
            null -> append("null")
            is String -> appendStr(v)
            is Double -> append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Float -> append(if (v.isNaN() || v.isInfinite()) "null" else v.toString())
            is Number, is Boolean -> append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) { append("{}"); return }
                append("{\n")
                v.entries.forEachIndexed { i, (k, value) ->
                    append(next); appendStr(k.toString()); append(": "); append(j, value, next)
                    if (i < v.size - 1) append(','); append('\n')
                }
                append(ind).append('}')
            }
            is Iterable<*> -> {
                val l = v.toList()
                if (l.isEmpty()) { append("[]"); return }
                append("[\n")
                l.forEachIndexed { i, value ->
                    append(next); append(j, value, next)
                    if (i < l.size - 1) append(','); append('\n')
                }
                append(ind).append(']')
            }
            is IntArray -> append(j, v.toList(), ind)
            else -> appendStr(v.toString())
        }
    }

    private fun StringBuilder.appendStr(s: String) {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}

/**
 * One logging session = one folder:
 *  - meta.json       adapter / car / timing info
 *  - live.csv        all readings, long format (t_ms,time,channel,name,value,text,unit,raw)
 *  - raw.log         every ELM command and response with timestamp (for later re-decoding)
 *  - scan_obd.json / scan_vw.json   full scan reports
 */
class SessionWriter(val dir: File) : RawLog {
    private val raw: BufferedWriter
    private val csv: BufferedWriter
    private var lastFlush = System.currentTimeMillis()
    var readingCount = 0L; private set
    val startedMs = System.currentTimeMillis()

    init {
        dir.mkdirs()
        raw = File(dir, "raw.log").bufferedWriter()
        val csvFile = File(dir, "live.csv")
        val isNew = !csvFile.exists()
        csv = csvFile.bufferedWriter()
        if (isNew) csv.write("t_ms,time,channel,name,value,text,unit,raw\n")
    }

    @Synchronized
    override fun log(direction: Char, text: String) {
        raw.write("${System.currentTimeMillis()}\t$direction\t$text\n")
        maybeFlush()
    }

    @Synchronized
    fun write(r: Reading) {
        csv.write(listOf(
            r.timeMs.toString(), Instant.ofEpochMilli(r.timeMs).toString(), r.channel, r.name,
            r.value?.toString() ?: "", r.text ?: "", r.unit, r.raw
        ).joinToString(",") { csvField(it) })
        csv.write("\n")
        readingCount++
        maybeFlush()
    }

    @Synchronized
    fun writeJson(name: String, content: Any?) {
        File(dir, name).writeText(Json.write(content))
    }

    @Synchronized
    fun note(text: String) = log('#', text)

    private fun maybeFlush() {
        val now = System.currentTimeMillis()
        if (now - lastFlush > 2000) { raw.flush(); csv.flush(); lastFlush = now }
    }

    @Synchronized
    fun flush() { raw.flush(); csv.flush() }

    @Synchronized
    fun close() { runCatching { raw.close() }; runCatching { csv.close() } }

    companion object {
        fun newDir(root: File, label: String): File {
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            return File(root, "${ts}_$label")
        }

        private fun csvField(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        fun zip(dir: File, out: OutputStream) {
            ZipOutputStream(out).use { z ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    z.putNextEntry(ZipEntry("${dir.name}/${f.relativeTo(dir).path}"))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }

        fun zip(dir: File, target: File) = FileOutputStream(target).use { zip(dir, it) }
    }
}
