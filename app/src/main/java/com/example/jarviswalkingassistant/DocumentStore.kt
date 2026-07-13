package com.example.jarviswalkingassistant

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Lets Dale point Jarvis at a real folder of reference documents (same idea as the
 * MichalCall folder used by live_transcribe.py on the laptop). No on-device embedding
 * model exists here, so retrieval is keyword-overlap scoring over paragraph chunks —
 * same architecture as the jarvis.py web pivot, not the laptop's FAISS vectors. That's
 * a real limitation, not full parity with the laptop tool: good enough for "does this
 * chunk mention what was asked", not semantic similarity.
 */
object DocumentStore {

    private const val PREFS = "jarvis_prefs"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val MAX_CONTEXT_CHARS = 3000
    private const val MAX_CHUNKS = 4

    data class Chunk(val fileName: String, val text: String, val tokens: Set<String>)

    @Volatile private var chunks: List<Chunk> = emptyList()
    @Volatile var folderLabel: String = "No folder selected"
        private set

    private val stopWords = setOf(
        "the","a","an","is","are","was","were","and","or","of","to","in","on",
        "for","with","this","that","it","as","at","by","be","has","have","not"
    )

    fun restorePersistedFolder(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_FOLDER_URI, null) ?: return
        loadFromUri(context, Uri.parse(saved))
    }

    fun selectFolder(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FOLDER_URI, treeUri.toString())
            .apply()
        loadFromUri(context, treeUri)
    }

    private fun loadFromUri(context: Context, treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        val newChunks = mutableListOf<Chunk>()

        root.listFiles().forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name ?: return@forEach
            val lower = name.lowercase()
            if (!(lower.endsWith(".txt") || lower.endsWith(".md"))) return@forEach

            try {
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    // Paragraph-boundary chunking, same principle as live_transcribe.py
                    // (blank-line splits instead of blind character cuts).
                    text.split(Regex("\\n\\s*\\n"))
                        .map { it.trim() }
                        .filter { it.length > 20 }
                        .forEach { para ->
                            newChunks.add(Chunk(name, para, tokenize(para)))
                        }
                }
            } catch (_: Exception) {
                // Skip unreadable file, don't crash retrieval for the rest of the folder
            }
        }

        chunks = newChunks
        folderLabel = "${root.name ?: "folder"} (${newChunks.size} chunks, " +
            "${root.listFiles().count { it.isFile }} files)"
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
    }

    /** Returns concatenated top-matching chunks for the query, or empty string if no folder/no match. */
    fun retrieveContext(query: String): String {
        if (chunks.isEmpty()) return ""
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return ""

        val scored = chunks
            .map { chunk ->
                val overlap = chunk.tokens.intersect(queryTokens).size
                chunk to overlap
            }
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

    fun hasDocuments(): Boolean = chunks.isNotEmpty()
}
