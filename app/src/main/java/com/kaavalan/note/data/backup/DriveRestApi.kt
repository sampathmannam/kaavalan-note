package com.kaavalan.note.data.backup

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * v2.1.0 (PM rating): a thin wrapper around the Google
 * Drive REST v3 API. The wrapper hides the OAuth Bearer
 * header, the JSON envelope, and the multipart upload
 * dance. The caller passes an access token (typically
 * obtained via [GoogleOAuthClient]); the wrapper does
 * not refresh the token. The caller is responsible for
 * passing a fresh token.
 *
 * **Why a thin wrapper.** The Drive REST API is
 * well-documented; the value of this class is the
 * "DriveAppFolder + name + content → file ID" round
 * trip in one call. The wrapper is unit-tested with
 * Ktor's [io.ktor.client.engine.mock.MockEngine] so the
 * happy path + 401 / 403 / 404 / 500 error shapes are
 * pinned.
 *
 * **Why kotlinx.serialization.** The Android framework
 * ships `org.json` but Robolectric's unit-test classpath
 * does not (it would need the standalone `org.json:json`
 * artifact, which is not in the offline build cache).
 * `kotlinx.serialization.json` is in the cache and is
 * the standard Android Kotlin JSON lib, so the
 * production + test paths use the same parser.
 */
class DriveRestApi(
    private val httpClient: HttpClient,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Upload [content] as a new file in the user's
     * `appDataFolder`. [fileName] is the file's display
     * name (e.g. kaavalan-note-backup-20260824-150000.json.enc").
     * Returns the Drive file ID — the caller stores
     * this to delete the file later.
     */
    suspend fun uploadToAppFolder(
        accessToken: String,
        fileName: String,
        content: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        require(fileName.isNotBlank() && fileName.length <= MAX_FILE_NAME_LENGTH) {
            "Invalid Drive backup filename"
        }
        // v3 multipart upload: POST
        // /upload/drive/v3/files?uploadType=multipart
        // with a "metadata" part + a "media" part.
        val metadata = buildString {
            append("{")
            append("\"name\":\"").append(jsonEscape(fileName)).append("\",")
            append("\"parents\":[\"appDataFolder\"]")
            append("}")
        }
        val boundary = "kaavalan-note-drive-${System.nanoTime()}"
        val body = buildMultipartBody(boundary, metadata, fileName, content)

        val response: HttpResponse = httpClient.post(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        ) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $accessToken")
                append(
                    HttpHeaders.ContentType,
                    ContentType.MultiPart.FormData.withParameter("boundary", boundary).toString(),
                )
            }
            setBody(body)
        }
        require(response.status == HttpStatusCode.OK) {
            "Drive upload failed: ${response.status}"
        }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        require("id" in obj) { "Drive upload response missing file id" }
        obj.getValue("id").jsonPrimitive.content
    }

    /**
     * List Kaavalan note backup files in the user's appDataFolder.
     * Returns a list of [DriveFile] sorted by createdTime
     * DESC (newest first) — same shape as the on-device
     * [com.kaavalan.note.data.export.BackupManager.listBackups].
     */
    suspend fun listBackups(
        accessToken: String,
        pageSize: Int = 50,
    ): List<DriveFile> = withContext(Dispatchers.IO) {
        require(pageSize in 1..MAX_PAGE_SIZE) { "Drive page size must be 1..$MAX_PAGE_SIZE" }
        val response: HttpResponse = httpClient.get(
            "https://www.googleapis.com/drive/v3/files"
        ) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            url.parameters.append("spaces", "appDataFolder")
            url.parameters.append(
                "q",
                "name contains 'kaavalan-note-backup-' and mimeType != 'application/vnd.google-apps.folder'",
            )
            url.parameters.append("orderBy", "createdTime desc")
            url.parameters.append("pageSize", pageSize.toString())
            url.parameters.append(
                "fields",
                "files(id,name,size,createdTime,modifiedTime)",
            )
        }
        require(response.status == HttpStatusCode.OK) {
            "Drive list failed: ${response.status}"
        }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val files = obj["files"]?.jsonArray ?: return@withContext emptyList()
        files.map { f ->
            val json = f.jsonObject
            DriveFile(
                id = json.getValue("id").jsonPrimitive.content,
                name = json.getValue("name").jsonPrimitive.content,
                sizeBytes = json["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                createdTimeMs = parseRfc3339(json["createdTime"]?.jsonPrimitive?.contentOrNull),
            )
        }
    }

    /**
     * Download the bytes of a Drive file by ID.
     */
    suspend fun downloadFile(
        accessToken: String,
        fileId: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        requireValidFileId(fileId)
        val response: HttpResponse = httpClient.get(
            "https://www.googleapis.com/drive/v3/files/$fileId"
        ) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            url.parameters.append("alt", "media")
        }
        require(response.status == HttpStatusCode.OK) {
            "Drive download failed: ${response.status}"
        }
        // Encrypted backups are arbitrary bytes, not UTF-8 text. Decoding
        // and re-encoding corrupts non-text byte sequences on restore.
        response.body<ByteArray>()
    }

    /**
     * Delete a Drive file by ID. Idempotent: a 404 is
     * treated as success.
     */
    suspend fun deleteFile(
        accessToken: String,
        fileId: String,
    ): Unit = withContext(Dispatchers.IO) {
        requireValidFileId(fileId)
        val response: HttpResponse = httpClient.delete(
            "https://www.googleapis.com/drive/v3/files/$fileId",
        ) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $accessToken")
            }
        }
        check(response.status == HttpStatusCode.NoContent || response.status == HttpStatusCode.NotFound) {
            "Drive delete failed: ${response.status}"
        }
    }

    /**
     * Build a multipart/related body for the Drive
     * upload. The format is:
     *
     *   --boundary
     *   Content-Type: application/json; charset=UTF-8
     *
     *   <metadata JSON>
     *   --boundary
     *   Content-Type: application/octet-stream
     *   Content-Disposition: attachment; filename="..."
     *
     *   <file bytes>
     *   --boundary--
     */
    private fun buildMultipartBody(
        boundary: String,
        metadataJson: String,
        fileName: String,
        content: ByteArray,
    ): ByteArray {
        val nl = "\r\n"
        val sb = StringBuilder()
        sb.append("--").append(boundary).append(nl)
        sb.append("Content-Type: application/json; charset=UTF-8").append(nl)
        sb.append(nl)
        sb.append(metadataJson).append(nl)
        sb.append("--").append(boundary).append(nl)
        sb.append("Content-Type: application/octet-stream").append(nl)
        sb.append("Content-Disposition: attachment; filename=\"")
            .append(sanitizeHeaderFileName(fileName))
            .append("\"")
            .append(nl)
        sb.append(nl)
        val head = sb.toString().toByteArray(Charsets.UTF_8)
        val tail = (nl + "--" + boundary + "--" + nl).toByteArray(Charsets.UTF_8)
        return head + content + tail
    }

    /**
     * Escape a string for embedding in JSON. Used by
     * the multipart body builder.
     */
    private fun jsonEscape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /** Prevent CRLF/header injection in the multipart filename parameter. */
    private fun sanitizeHeaderFileName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")

    private fun requireValidFileId(fileId: String) {
        require(DRIVE_FILE_ID_RE.matches(fileId)) { "Invalid Drive file id" }
    }

    /**
     * Parse Google's RFC 3339 timestamp ("2026-08-24T15:00:00.000Z")
     * into epoch millis. Hand-rolled to avoid pulling in
     * java.time on the test classpath.
     */
    private fun parseRfc3339(text: String?): Long {
        if (text.isNullOrEmpty()) return 0L
        return try {
            val year = text.substring(0, 4).toInt()
            val month = text.substring(5, 7).toInt()
            val day = text.substring(8, 10).toInt()
            val hour = text.substring(11, 13).toInt()
            val minute = text.substring(14, 16).toInt()
            val second = text.substring(17, 19).toInt()
            val msStr = text.substringAfter('.', "").substringBefore('Z', "")
            val ms = msStr.toIntOrNull() ?: 0
            java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(year, month - 1, day, hour, minute, second)
                set(java.util.Calendar.MILLISECOND, ms)
            }.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    data class DriveFile(
        val id: String,
        val name: String,
        val sizeBytes: Long,
        val createdTimeMs: Long,
    )

    private companion object {
        const val MAX_FILE_NAME_LENGTH = 255
        const val MAX_PAGE_SIZE = 1000
        val DRIVE_FILE_ID_RE = Regex("[A-Za-z0-9_-]{1,200}")
    }
}
