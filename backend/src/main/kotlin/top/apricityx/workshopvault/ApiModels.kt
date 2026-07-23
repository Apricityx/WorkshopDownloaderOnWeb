package top.apricityx.workshopvault

import jakarta.validation.constraints.Pattern
import java.time.Instant

data class ApiError(
    val code: String,
    val message: String,
)

data class WorkshopItemResponse(
    val appId: Long,
    val publishedFileId: String,
    val title: String,
    val fileName: String?,
    val fileSizeBytes: Long?,
    val previewUrl: String?,
    val description: String?,
    val updatedAt: Instant?,
    val workshopUrl: String,
    val availability: WorkshopAvailability,
    val availabilityMessage: String,
)

enum class WorkshopAvailability {
    PUBLIC_DOWNLOAD,
    REQUIRES_STEAM_AUTH,
    UNSUPPORTED,
    NOT_FOUND,
}

data class CreateDownloadRequest(
    @field:Pattern(regexp = "[0-9]{1,10}")
    val appId: String,
    @field:Pattern(regexp = "[0-9]{1,20}")
    val publishedFileId: String,
)

data class DownloadTaskResponse(
    val id: String,
    val appId: Long,
    val publishedFileId: String,
    val title: String,
    val fileName: String,
    val state: DownloadState,
    val writtenBytes: Long,
    val totalBytes: Long?,
    val error: String?,
    val createdAt: Instant,
    val completedAt: Instant?,
    val fileUrl: String?,
)

enum class DownloadState {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

data class AdminStatusResponse(
    val configured: Boolean,
    val authenticated: Boolean,
    val steamId: String? = null,
    val expiresAt: Instant? = null,
)

data class HealthResponse(
    val status: String = "ok",
    val publicDownloadMode: String = "file_url",
)
