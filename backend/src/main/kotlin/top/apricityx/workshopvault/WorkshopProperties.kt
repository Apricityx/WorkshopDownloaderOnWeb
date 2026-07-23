package top.apricityx.workshopvault

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("workshop")
data class WorkshopProperties(
    val baseUrl: String = "http://localhost:8080",
    val adminSteamIds: String = "",
    val sessionSecret: String = "development-only-secret-change-me",
    val upstreamHttpProxy: String = "",
    val downloadRoot: String = "./data/downloads",
    val sessionHours: Long = 12,
) {
    val allowedAdminSteamIds: Set<String>
        get() = adminSteamIds.split(',').map(String::trim).filter(String::isNotBlank).toSet()

    val normalizedBaseUrl: String
        get() = baseUrl.trim().removeSuffix("/")

    val downloadsPath: Path
        get() = Path.of(downloadRoot).toAbsolutePath().normalize()
}
