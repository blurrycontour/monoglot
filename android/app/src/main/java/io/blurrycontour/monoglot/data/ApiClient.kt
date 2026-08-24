package io.blurrycontour.monoglot.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

/**
 * Thin OkHttp client for the Go API. Single static bearer token; there is only
 * ever one user.
 */
class ApiClient(private val settings: SettingsStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private suspend fun baseUrl(): String = settings.serverUrl().trimEnd('/')
    private suspend fun token(): String = settings.authToken()

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl() + path)
            .header("Authorization", "Bearer ${token()}")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException("HTTP ${resp.code}: ${body.take(200)}")
            }
            body
        }
    }

    /** Minimal JSON string escaping; lemmas can contain quotes and backslashes. */
    private fun jsonString(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()

    private suspend fun post(path: String, payload: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl() + path)
            .header("Authorization", "Bearer ${token()}")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException("HTTP ${resp.code}: ${body.take(200)}")
            }
            body
        }
    }

    suspend fun items(source: String? = null, status: String = "ready"): List<ItemSummary> {
        val q = buildString {
            append("/api/items?status=$status&limit=200")
            if (!source.isNullOrBlank()) append("&source=$source")
        }
        return json.decodeFromString<ItemsResponse>(get(q)).items
    }

    suspend fun bundle(itemId: Int): Bundle =
        json.decodeFromString(get("/api/items/$itemId/bundle"))

    /**
     * [record] is false when re-reading a word you already collected: the
     * lookup count means "times you failed to parse this while listening".
     */
    suspend fun lookup(word: String, itemId: Int? = null, record: Boolean = false): LookupResult {
        val q = StringBuilder("/api/lookup?w=").append(java.net.URLEncoder.encode(word, "UTF-8"))
        if (itemId != null) q.append("&item_id=").append(itemId)
        if (!record) q.append("&record=0")
        return json.decodeFromString(get(q.toString()))
    }

    /**
     * Logs a tap. Kept separate from [lookup] because the common case never
     * calls lookup at all: definitions are inlined into the bundle so a tap
     * resolves locally, which meant only words the dictionary was missing ever
     * reached the word list.
     */
    suspend fun recordLookup(lemma: String, itemId: Int?, tokenId: Int?) {
        val body = buildString {
            append("""{"lemma":${jsonString(lemma)}""")
            if (itemId != null) append(""","item_id":$itemId""")
            if (tokenId != null) append(""","token_id":$tokenId""")
            append("}")
        }
        post("/api/lookup/record", body)
    }

    suspend fun itemSummary(itemId: Int): EpisodeSummary =
        json.decodeFromString(get("/api/items/$itemId/summary"))

    suspend fun setWordStatus(lemma: String, status: String) {
        val encoded = java.net.URLEncoder.encode(lemma, "UTF-8")
        post("/api/words/$encoded/status", """{"status":"$status"}""")
    }

    suspend fun words(status: String? = null): List<WordRow> {
        val q = if (status.isNullOrBlank()) "/api/words" else "/api/words?status=$status"
        return json.decodeFromString<WordsResponse>(get(q)).words
    }

    suspend fun postProgress(itemId: Int, positionMs: Int, completed: Boolean) {
        post("/api/items/$itemId/progress", """{"position_ms":$positionMs,"completed":$completed}""")
    }

    /** One page of never-fetched items, newest first. Paged rather than
     *  fetched whole: a dormant archive can be hundreds of episodes. */
    suspend fun archivedItems(source: String? = null, offset: Int = 0, limit: Int = 10):
        List<ItemSummary> {
        val q = buildString {
            append("/api/items?status=archived&limit=$limit&offset=$offset")
            if (!source.isNullOrBlank()) append("&source=$source")
        }
        return json.decodeFromString<ItemsResponse>(get(q)).items
    }

    suspend fun cancelItem(itemId: Int) { post("/api/items/$itemId/cancel", "{}") }

    suspend fun resetProgress(itemId: Int) { post("/api/items/$itemId/progress/reset", "{}") }

    suspend fun deleteWords(lemmas: List<String>) {
        val payload = lemmas.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }
        post("/api/words/delete", """{"lemmas":[$payload]}""")
    }

    suspend fun deleteAllWords(status: String?) {
        val body = if (status.isNullOrBlank()) """{"all":true}"""
                   else """{"all":true,"status":"$status"}"""
        post("/api/words/delete", body)
    }

    suspend fun system(): SystemInfo =
        json.decodeFromString(get("/api/system"))

    suspend fun archiveItem(itemId: Int) { post("/api/items/$itemId/archive", "{}") }

    suspend fun restoreItem(itemId: Int) { post("/api/items/$itemId/restore", "{}") }

    suspend fun cleanup(days: Int): Int {
        val body = post("/api/admin/cleanup?days=$days", "{}")
        return Regex("\"archived\":(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** [source] scopes the counts to one source, matching the chip in use. */
    suspend fun status(source: String? = null): PipelineStatus =
        json.decodeFromString(
            get(if (source.isNullOrBlank()) "/api/status" else "/api/status?source=$source")
        )

    suspend fun sources(): List<SourceRow> =
        json.decodeFromString<SourcesResponse>(get("/api/sources")).sources

    suspend fun setSourceEnabled(id: Int, enabled: Boolean) {
        post("/api/sources/$id/enabled", """{"enabled":$enabled}""")
    }

    suspend fun triggerIngest() {
        post("/api/admin/ingest", "{}")
    }

    /** Streaming URL for ExoPlayer. The token goes in the query string because
     *  the player issues its own requests and cannot set headers reliably. */
    suspend fun audioUrl(itemId: Int): String =
        "${baseUrl()}/api/items/$itemId/audio?token=${token()}"

    /** Downloads audio to [dest] for offline playback. */
    suspend fun downloadAudio(itemId: Int, dest: File): Long = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl()}/api/items/$itemId/audio")
            .header("Authorization", "Bearer ${token()}")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("audio download: HTTP ${resp.code}")
            val body = resp.body ?: throw ApiException("audio download: empty body")
            // Write to a temp file and rename, so an interrupted download can
            // never look like a complete one.
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.parentFile?.mkdirs()
            body.byteStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dest)) throw ApiException("audio download: rename failed")
            dest.length()
        }
    }

    suspend fun health(): Boolean = try {
        get("/api/health"); true
    } catch (e: Exception) {
        false
    }
}
