package top.apricityx.workshopvault

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.core.io.FileSystemResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

@RestController
@RequestMapping("/api")
class ApiController(
    private val workshopService: WorkshopService,
    private val adminAuthService: AdminAuthService,
) {
    @GetMapping("/health")
    fun health() = HealthResponse()

    @GetMapping("/workshop/item")
    fun workshopItem(
        @RequestParam appId: String,
        @RequestParam publishedFileId: String,
    ): WorkshopItemResponse = workshopService.resolve(appId, publishedFileId).toResponse()

    @PostMapping("/downloads")
    fun createDownload(@Valid @RequestBody request: CreateDownloadRequest): DownloadTaskResponse =
        workshopService.startDownload(request.appId, request.publishedFileId)

    @GetMapping("/downloads")
    fun listDownloads(): List<DownloadTaskResponse> = workshopService.listDownloads()

    @GetMapping("/downloads/{id}/file")
    fun downloadFile(@PathVariable id: String): ResponseEntity<FileSystemResource> {
        val file = workshopService.openCompletedFile(id)
        val resource = FileSystemResource(file.path)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(resource.contentLength())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.fileName, StandardCharsets.UTF_8).build().toString(),
            )
            .body(resource)
    }

    @GetMapping("/admin/status")
    fun adminStatus(request: HttpServletRequest): AdminStatusResponse = adminAuthService.status(request)

    @GetMapping("/admin/steam/login")
    fun beginSteamLogin(response: HttpServletResponse): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, adminAuthService.createSteamLoginRedirect(response))
            .build()

    @GetMapping("/admin/steam/callback")
    fun completeSteamLogin(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @RequestParam(required = false) state: String?,
    ): ResponseEntity<Void> {
        adminAuthService.completeSteamLogin(request, response, state)
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, "/?admin=success")
            .build()
    }

    @PostMapping("/admin/logout")
    fun logout(response: HttpServletResponse): ResponseEntity<Void> {
        adminAuthService.logout(response)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/admin/downloads")
    fun listAdminDownloads(request: HttpServletRequest): List<DownloadTaskResponse> {
        adminAuthService.requireAdmin(request)
        return workshopService.listDownloads()
    }
}
