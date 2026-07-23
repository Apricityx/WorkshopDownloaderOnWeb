package top.apricityx.workshopvault

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Service
class WorkshopService(
    private val steamHttpClient: SteamHttpClient,
    private val objectMapper: ObjectMapper,
    private val properties: WorkshopProperties,
) {
    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    private val downloadExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    fun resolve(appIdInput: String, publishedFileIdInput: String): ResolvedWorkshopItem {
        val appId = appIdInput.toLongOrNull()?.takeIf { it in 1..MAX_APP_ID }
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "invalid_app_id", "AppID 必须是正整数。")
        val publishedFileId = publishedFileIdInput.toULongOrNull()
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "invalid_published_file_id", "创意工坊条目 ID 无效。")
        val body = formEncode(
            mapOf(
                "itemcount" to "1",
                "appid" to appId.toString(),
                "publishedfileids[0]" to publishedFileId.toString(),
            ),
        )
        val request = HttpRequest.newBuilder(STEAM_PUBLISHED_FILE_DETAILS_URI)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            steamHttpClient.client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw ApiException(HttpStatus.BAD_GATEWAY, "steam_unavailable", "无法连接 Steam 元数据服务。")
        }
        if (response.statusCode() !in 200..299) {
            throw ApiException(HttpStatus.BAD_GATEWAY, "steam_metadata_failed", "Steam 元数据服务返回 ${response.statusCode()}。")
        }
        val detail = try {
            objectMapper.readTree(response.body())
                .path("response")
                .path("publishedfiledetails")
                .firstOrNull()
        } catch (error: Exception) {
            throw ApiException(HttpStatus.BAD_GATEWAY, "steam_metadata_invalid", "Steam 返回了无效的创意工坊元数据。")
        } ?: throw ApiException(HttpStatus.NOT_FOUND, "workshop_item_missing", "没有找到该创意工坊条目。")

        if (detail.path("result").asInt() != SUCCESS_RESULT) {
            return ResolvedWorkshopItem.notFound(appId, publishedFileId)
        }

        val title = detail.path("title").asText().ifBlank { "Workshop $publishedFileId" }
        val fileType = detail.path("file_type").asText().toIntOrNull() ?: COMMUNITY_FILE_TYPE
        val fileUrl = detail.path("file_url").asText().trim().ifBlank { null }
        val fileName = detail.path("filename").asText().substringAfterLast('/').ifBlank { "$publishedFileId.bin" }
        val hContentFile = detail.path("hcontent_file").asText().toULongOrNull()
        val availability = when {
            fileType == COLLECTION_FILE_TYPE -> WorkshopAvailability.UNSUPPORTED
            fileType !in SUPPORTED_FILE_TYPES -> WorkshopAvailability.UNSUPPORTED
            fileUrl != null && isTrustedContentUri(fileUrl) -> WorkshopAvailability.PUBLIC_DOWNLOAD
            hContentFile != null && hContentFile > 0uL -> WorkshopAvailability.REQUIRES_STEAM_AUTH
            else -> WorkshopAvailability.UNSUPPORTED
        }
        val availabilityMessage = when (availability) {
            WorkshopAvailability.PUBLIC_DOWNLOAD -> "该条目公开提供直连文件，可由服务器下载。"
            WorkshopAvailability.REQUIRES_STEAM_AUTH -> "该条目需要 Steam 内容服务器授权；请使用拥有访问权限的 Steam 客户端下载。"
            WorkshopAvailability.UNSUPPORTED -> "该条目不是可公开直连下载的单一工坊文件。"
            WorkshopAvailability.NOT_FOUND -> "Steam 未找到该条目，或当前账号无权查看。"
        }
        return ResolvedWorkshopItem(
            appId = appId,
            publishedFileId = publishedFileId,
            title = title,
            fileName = fileName,
            fileSizeBytes = detail.path("file_size").asLongOrNull(),
            previewUrl = detail.path("preview_url").asText().trim().ifBlank { null },
            description = detail.path("description").asText().trim().ifBlank { null },
            updatedAt = detail.path("time_updated").asLongOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            fileUrl = fileUrl?.takeIf(::isTrustedContentUri),
            availability = availability,
            availabilityMessage = availabilityMessage,
        )
    }

    fun startDownload(appIdInput: String, publishedFileIdInput: String): DownloadTaskResponse {
        val item = resolve(appIdInput, publishedFileIdInput)
        if (item.availability != WorkshopAvailability.PUBLIC_DOWNLOAD || item.fileUrl == null) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "download_not_public",
                item.availabilityMessage,
            )
        }
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            item = item,
            state = DownloadState.QUEUED,
            createdAt = Instant.now(),
        )
        tasks[task.id] = task
        downloadExecutor.submit { download(task) }
        return task.toResponse()
    }

    fun listDownloads(): List<DownloadTaskResponse> = tasks.values
        .sortedByDescending(DownloadTask::createdAt)
        .map(DownloadTask::toResponse)

    fun openCompletedFile(id: String): DownloadFile {
        val task = tasks[id] ?: throw ApiException(HttpStatus.NOT_FOUND, "download_missing", "下载任务不存在。")
        val outputPath = task.outputPath
            ?.takeIf { task.state == DownloadState.COMPLETED && Files.isRegularFile(it) }
            ?: throw ApiException(HttpStatus.CONFLICT, "download_not_ready", "文件尚未下载完成。")
        return DownloadFile(outputPath, task.safeFileName)
    }

    private fun download(task: DownloadTask) {
        try {
            task.state = DownloadState.RESOLVING
            val downloadDirectory = properties.downloadsPath
                .resolve(task.item.appId.toString())
                .resolve(task.item.publishedFileId.toString())
                .normalize()
            if (!downloadDirectory.startsWith(properties.downloadsPath)) {
                throw IllegalStateException("Invalid download output path")
            }
            Files.createDirectories(downloadDirectory)
            val outputPath = downloadDirectory.resolve(task.safeFileName)
            val partialPath = downloadDirectory.resolve("${task.safeFileName}.part")
            if (Files.isRegularFile(outputPath) &&
                (task.item.fileSizeBytes == null || Files.size(outputPath) == task.item.fileSizeBytes)
            ) {
                task.outputPath = outputPath
                task.writtenBytes = Files.size(outputPath)
                task.state = DownloadState.COMPLETED
                task.completedAt = Instant.now()
                return
            }
            val existingBytes = if (Files.isRegularFile(partialPath)) Files.size(partialPath) else 0L
            task.state = DownloadState.DOWNLOADING
            val response = openContentResponse(task.item.fileUrl!!, existingBytes)
            response.useBody { body, statusCode, headers ->
                val append = existingBytes > 0 && statusCode == 206
                if (existingBytes > 0 && !append) {
                    Files.deleteIfExists(partialPath)
                }
                val startBytes = if (append) existingBytes else 0L
                task.writtenBytes = startBytes
                task.totalBytes = responseTotalLength(headers, startBytes)
                body.use { input ->
                    val outputOptions = if (append) {
                        arrayOf(
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND,
                        )
                    } else {
                        arrayOf(
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                            java.nio.file.StandardOpenOption.WRITE,
                        )
                    }
                    Files.newOutputStream(partialPath, *outputOptions).buffered().use { output ->
                        copyWithProgress(input, output, task, startBytes)
                    }
                }
            }
            moveCompletedFile(partialPath, outputPath)
            task.outputPath = outputPath
            task.writtenBytes = Files.size(outputPath)
            task.state = DownloadState.COMPLETED
            task.completedAt = Instant.now()
        } catch (error: Exception) {
            task.state = DownloadState.FAILED
            task.error = error.message?.take(300) ?: "下载失败。"
            task.completedAt = Instant.now()
        }
    }

    private fun openContentResponse(initialUrl: String, existingBytes: Long): ContentResponse {
        var target = URI(initialUrl)
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) { redirectCount ->
            if (!isTrustedContentUri(target.toString())) {
                throw IllegalStateException("Steam returned an untrusted content URL")
            }
            val requestBuilder = HttpRequest.newBuilder(target)
                .header("User-Agent", USER_AGENT)
                .GET()
            if (existingBytes > 0) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }
            val response = steamHttpClient.client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() in REDIRECT_CODES) {
                response.body().close()
                val location = response.headers().firstValue("location").orElseThrow {
                    IllegalStateException("Steam content redirect had no destination")
                }
                target = target.resolve(location)
                if (redirectCount == MAX_DOWNLOAD_REDIRECTS) {
                    throw IllegalStateException("Steam content redirected too many times")
                }
            } else if (response.statusCode() in 200..299) {
                return ContentResponse(response)
            } else {
                response.body().close()
                throw IllegalStateException("Steam content server returned ${response.statusCode()}")
            }
        }
        throw IllegalStateException("Unable to open Steam content")
    }

    private fun copyWithProgress(input: InputStream, output: java.io.OutputStream, task: DownloadTask, startBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = startBytes
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
            written += read
            task.writtenBytes = written
        }
    }

    private fun moveCompletedFile(partialPath: Path, outputPath: Path) {
        try {
            Files.move(partialPath, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(partialPath, outputPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun responseTotalLength(headers: java.net.http.HttpHeaders, existingBytes: Long): Long? {
        val range = headers.firstValue("content-range").orElse(null)
        val rangeTotal = range?.substringAfterLast('/')?.toLongOrNull()
        if (rangeTotal != null) {
            return rangeTotal
        }
        return headers.firstValue("content-length").orElse(null)?.toLongOrNull()?.let { length ->
            if (existingBytes > 0) existingBytes + length else length
        }
    }

    private fun formEncode(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }

    private fun isTrustedContentUri(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.host?.lowercase()?.let { host -> TRUSTED_CONTENT_HOSTS.any { suffix -> host == suffix || host.endsWith(".$suffix") } } == true
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun JsonNode.asLongOrNull(): Long? =
        takeUnless { it.isMissingNode }?.asText()?.toLongOrNull()

    data class ResolvedWorkshopItem(
        val appId: Long,
        val publishedFileId: ULong,
        val title: String,
        val fileName: String,
        val fileSizeBytes: Long?,
        val previewUrl: String?,
        val description: String?,
        val updatedAt: Instant?,
        val fileUrl: String?,
        val availability: WorkshopAvailability,
        val availabilityMessage: String,
    ) {
        fun toResponse() = WorkshopItemResponse(
            appId = appId,
            publishedFileId = publishedFileId.toString(),
            title = title,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            previewUrl = previewUrl,
            description = description,
            updatedAt = updatedAt,
            workshopUrl = "https://steamcommunity.com/sharedfiles/filedetails/?id=$publishedFileId",
            availability = availability,
            availabilityMessage = availabilityMessage,
        )

        companion object {
            fun notFound(appId: Long, publishedFileId: ULong) = ResolvedWorkshopItem(
                appId = appId,
                publishedFileId = publishedFileId,
                title = "Workshop $publishedFileId",
                fileName = "$publishedFileId.bin",
                fileSizeBytes = null,
                previewUrl = null,
                description = null,
                updatedAt = null,
                fileUrl = null,
                availability = WorkshopAvailability.NOT_FOUND,
                availabilityMessage = "Steam 未找到该条目，或当前账号无权查看。",
            )
        }
    }

    private data class DownloadTask(
        val id: String,
        val item: ResolvedWorkshopItem,
        @Volatile var state: DownloadState,
        @Volatile var writtenBytes: Long = 0,
        @Volatile var totalBytes: Long? = item.fileSizeBytes,
        @Volatile var error: String? = null,
        val createdAt: Instant,
        @Volatile var completedAt: Instant? = null,
        @Volatile var outputPath: Path? = null,
    ) {
        val safeFileName: String = item.fileName
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(180)
            .ifBlank { "${item.publishedFileId}.bin" }

        fun toResponse() = DownloadTaskResponse(
            id = id,
            appId = item.appId,
            publishedFileId = item.publishedFileId.toString(),
            title = item.title,
            fileName = safeFileName,
            state = state,
            writtenBytes = writtenBytes,
            totalBytes = totalBytes,
            error = error,
            createdAt = createdAt,
            completedAt = completedAt,
            fileUrl = outputPath?.takeIf { state == DownloadState.COMPLETED }?.let { "/api/downloads/$id/file" },
        )
    }

    data class DownloadFile(val path: Path, val fileName: String)

    private class ContentResponse(private val response: HttpResponse<InputStream>) {
        fun useBody(block: (InputStream, Int, java.net.http.HttpHeaders) -> Unit) {
            response.body().use { body -> block(body, response.statusCode(), response.headers()) }
        }
    }

    private companion object {
        const val USER_AGENT = "WorkshopVault/0.1"
        const val SUCCESS_RESULT = 1
        const val MAX_APP_ID = 4_294_967_295L
        const val COMMUNITY_FILE_TYPE = 0
        const val COLLECTION_FILE_TYPE = 2
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        const val MAX_DOWNLOAD_REDIRECTS = 5
        val STEAM_PUBLISHED_FILE_DETAILS_URI = URI("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val SUPPORTED_FILE_TYPES = setOf(0, 3, 5, 10, 11, 12)
        val TRUSTED_CONTENT_HOSTS = setOf(
            "steamusercontent-a.akamaihd.net",
            "steamusercontent.com",
            "steamcontent.com",
        )
    }
}
