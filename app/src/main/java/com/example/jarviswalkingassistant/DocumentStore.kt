package com.example.jarviswalkingassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Add/drop individual files OR whole folders, freely, at any time — not a single
 * whole-tree replace anymore. Persists across restarts. Retrieval is still
 * keyword-overlap scoring over paragraph chunks (no on-device embedding model
 * exists here), same limitation as before, just applied over a flexible file set.
 */
object DocumentStore {

    private const val PREFS = "jarvis_prefs"
    private const val KEY_SOURCES = "doc_sources"
    private const val MAX_CONTEXT_CHARS = 3000
    private const val MAX_CHUNKS = 4

    data class Source(val uri: Uri, val isFolder: Boolean, val label: String)
    data class Chunk(val fileName: String, val text: String, val tokens: Set<String>)

    @Volatile var sources: List<Source> = emptyList()
        private set
    @Volatile private var chunks: List<Chunk> = emptyList()

    private val stopWords = setOf(
        "the","a","an","is","are","was","were","and","or","of","to","in","on",
        "for","with","this","that","it","as","at","by","be","has","have","not"
    )

    fun restorePersisted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SOURCES, null) ?: return
        try {
            val arr = JSONArray(json)
            val restored = mutableListOf<Source>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                restored.add(
                    Source(
                        Uri.parse(obj.getString("uri")),
                        obj.getBoolean("isFolder"),
                        obj.optString("label", "file")
                    )
                )
            }
            sources = restored
            rebuildChunks(context)
        } catch (_: Exception) {
            // Corrupt/old prefs format — start clean rather than crash
        }
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        sources.forEach {
            arr.put(JSONObject().apply {
                put("uri", it.uri.toString())
                put("isFolder", it.isFolder)
                put("label", it.label)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCES, arr.toString())
            .apply()
    }

    fun addFile(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* some providers don't support persistable grants; still try to use it now */ }
        val name = queryDisplayName(context, uri) ?: (uri.lastPathSegment ?: "file")
        sources = sources + Source(uri, false, name)
        persist(context)
        rebuildChunks(context)
    }

    fun addFolder(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = DocumentFile.fromTreeUri(context, treeUri)?.name ?: "folder"
        sources = sources + Source(treeUri, true, name)
        persist(context)
        rebuildChunks(context)
    }

    fun removeSource(context: Context, source: Source) {
        sources = sources.filterNot { it.uri == source.uri && it.isFolder == source.isFolder }
        persist(context)
        rebuildChunks(context)
    }

    fun clearAll(context: Context) {
        sources = emptyList()
        chunks = emptyList()
        persist(context)
    }

    private fun rebuildChunks(context: Context) {
        val newChunks = mutableListOf<Chunk>()
        sources.forEach { source ->
            try {
                if (source.isFolder) {
                    DocumentFile.fromTreeUri(context, source.uri)?.listFiles()?.forEach { file ->
                        if (file.isFile) file.name?.let { readIntoChunks(context, file.uri, it, newChunks) }
                    }
                } else {
                    readIntoChunks(context, source.uri, source.label, newChunks)
                }
            } catch (_: Exception) {
                // Skip a source that failed (revoked permission, deleted file, etc.)
                // rather than losing every other loaded document
            }
        }
        chunks = newChunks
    }

    private fun readIntoChunks(context: Context, uri: Uri, name: String, out: MutableList<Chunk>) {
        val lower = name.lowercase()
        if (!(lower.endsWith(".txt") || lower.endsWith(".md"))) return
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val text = stream.bufferedReader().readText()
            text.split(Regex("\\n\\s*\\n"))
                .map { it.trim() }
                .filter { it.length > 20 }
                .forEach { para -> out.add(Chunk(name, para, tokenize(para))) }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
    }

    fun retrieveContext(query: String): String {
        if (chunks.isEmpty()) return ""
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return ""

        val scored = chunks
            .map { it to it.tokens.intersect(queryTokens).size }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_CHUNKS)

        if (scored.isEmpty()) return ""

        val builder = StringBuilder()
        for ((chunk, _) in scored) {
            val addition = "[From ${chunk.fileName}]\n${chunk.text}\n\n"
            if (builder.length + addition.length > MAX_CONTEXT_CHARS) break
            builder.append(addition)
        }
        return builder.toString().trim()
    }

    fun summaryLabel(): String =
        if (sources.isEmpty()) "No documents added" else "${sources.size} source(s), ${chunks.size} chunks loaded"
}
