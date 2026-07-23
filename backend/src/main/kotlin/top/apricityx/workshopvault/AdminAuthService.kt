package top.apricityx.workshopvault

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class AdminAuthService(
    private val properties: WorkshopProperties,
    private val steamHttpClient: SteamHttpClient,
) {
    private val random = SecureRandom()

    init {
        if (properties.allowedAdminSteamIds.isNotEmpty()) {
            require(properties.sessionSecret != DEFAULT_SESSION_SECRET) {
                "SESSION_SECRET must be configured before enabling Steam administrator login"
            }
            require(properties.allowedAdminSteamIds.all { STEAM_ID_REGEX.matches(it) }) {
                "ADMIN_STEAM_IDS must contain comma-separated SteamID64 values"
            }
            val baseUri = runCatching { URI(properties.normalizedBaseUrl) }.getOrNull()
            require(baseUri?.scheme in setOf("http", "https") && !baseUri?.host.isNullOrBlank()) {
                "APP_BASE_URL must be an absolute http(s) URL before enabling Steam administrator login"
            }
        }
    }

    fun isConfigured(): Boolean = properties.allowedAdminSteamIds.isNotEmpty()

    fun status(request: HttpServletRequest): AdminStatusResponse {
        val session = readSignedValue(request, ADMIN_SESSION_COOKIE)
            ?.takeIf { it.kind == "session" && it.expiresAt.isAfter(Instant.now()) }
            ?.takeIf { it.steamId in properties.allowedAdminSteamIds }
        return AdminStatusResponse(
            configured = isConfigured(),
            authenticated = session != null,
            steamId = session?.steamId,
            expiresAt = session?.expiresAt,
        )
    }

    fun createSteamLoginRedirect(response: HttpServletResponse): String {
        if (!isConfigured()) {
            throw ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "admin_not_configured",
                "管理员 SteamID 白名单尚未配置。",
            )
        }
        val state = createState()
        val returnToUrl = "$callbackUrl?state=${encode(state.value)}"
        writeSignedCookie(
            response = response,
            name = OPENID_STATE_COOKIE,
            value = state,
            maxAgeSeconds = OPENID_STATE_SECONDS,
        )
        return buildUrl(
            STEAM_OPENID_LOGIN_URI,
            mapOf(
                "openid.ns" to OPENID_NAMESPACE,
                "openid.mode" to "checkid_setup",
                "openid.return_to" to returnToUrl,
                "openid.realm" to "${properties.normalizedBaseUrl}/",
                "openid.identity" to OPENID_IDENTIFIER_SELECT,
                "openid.claimed_id" to OPENID_IDENTIFIER_SELECT,
            ),
        )
    }

    fun completeSteamLogin(
        request: HttpServletRequest,
        response: HttpServletResponse,
        state: String?,
    ) {
        val expectedState = readSignedValue(request, OPENID_STATE_COOKIE)
            ?.takeIf { it.kind == "state" && it.expiresAt.isAfter(Instant.now()) }
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "openid_state_missing", "Steam 登录状态已失效，请重试。")
        clearCookie(response, OPENID_STATE_COOKIE)
        if (state.isNullOrBlank() || !MessageDigest.isEqual(state.toByteArray(), expectedState.value.toByteArray())) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "openid_state_invalid", "Steam 登录状态验证失败，请重试。")
        }

        val expectedCallback = "$callbackUrl?state=${encode(expectedState.value)}"
        val returnTo = request.getParameter("openid.return_to")
        if (returnTo != expectedCallback) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "openid_return_to_invalid", "Steam 回调地址验证失败。")
        }
        val claimedId = request.getParameter("openid.claimed_id") ?: request.getParameter("openid.identity")
        val steamId = claimedId?.let(::extractSteamId)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "openid_identity_invalid", "Steam 未返回有效的身份标识。")
        if (steamId !in properties.allowedAdminSteamIds) {
            throw ApiException(HttpStatus.FORBIDDEN, "admin_not_allowed", "该 Steam 账号未被授权为管理员。")
        }
        if (!verifySteamAssertion(request)) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "openid_verification_failed", "Steam OpenID 声明验证失败。")
        }
        writeSignedCookie(
            response = response,
            name = ADMIN_SESSION_COOKIE,
            value = SignedValue(
                kind = "session",
                value = randomValue(),
                steamId = steamId,
                expiresAt = Instant.now().plusSeconds(properties.sessionHours.coerceIn(1, 168) * 3600),
            ),
            maxAgeSeconds = properties.sessionHours.coerceIn(1, 168) * 3600,
        )
    }

    fun requireAdmin(request: HttpServletRequest): String {
        val status = status(request)
        return status.steamId ?: throw ApiException(HttpStatus.UNAUTHORIZED, "admin_auth_required", "请先使用已授权的 Steam 账号登录。")
    }

    fun logout(response: HttpServletResponse) {
        clearCookie(response, ADMIN_SESSION_COOKIE)
    }

    private fun verifySteamAssertion(request: HttpServletRequest): Boolean {
        val fields = request.parameterMap
            .filterKeys { it.startsWith("openid.") }
            .mapValues { (_, values) -> values.firstOrNull().orEmpty() }
            .toMutableMap()
        if (fields["openid.ns"] != OPENID_NAMESPACE || fields["openid.mode"] != "id_res") {
            return false
        }
        fields["openid.mode"] = "check_authentication"
        val body = formEncode(fields)
        val verificationRequest = HttpRequest.newBuilder(STEAM_OPENID_LOGIN_URI)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val verificationResponse = try {
            steamHttpClient.client.send(verificationRequest, HttpResponse.BodyHandlers.ofString())
        } catch (_: Exception) {
            return false
        }
        return verificationResponse.statusCode() in 200..299 &&
            verificationResponse.body().lineSequence().any { it.trim() == "is_valid:true" }
    }

    private fun extractSteamId(claimedId: String): String? {
        val match = STEAM_CLAIMED_ID_REGEX.matchEntire(claimedId) ?: return null
        return match.groupValues[1]
    }

    private fun createState() = SignedValue(
        kind = "state",
        value = randomValue(),
        steamId = "",
        expiresAt = Instant.now().plusSeconds(OPENID_STATE_SECONDS),
    )

    private fun randomValue(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun writeSignedCookie(
        response: HttpServletResponse,
        name: String,
        value: SignedValue,
        maxAgeSeconds: Long,
    ) {
        val payload = listOf(value.kind, value.value, value.steamId, value.expiresAt.epochSecond).joinToString("|")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val token = "$encoded.${signature(encoded)}"
        response.addHeader(
            "Set-Cookie",
            buildCookie(name, token, maxAgeSeconds),
        )
    }

    private fun readSignedValue(request: HttpServletRequest, cookieName: String): SignedValue? {
        val token = request.cookies?.firstOrNull { it.name == cookieName }?.value ?: return null
        val separator = token.lastIndexOf('.')
        if (separator <= 0 || !MessageDigest.isEqual(signature(token.substring(0, separator)).toByteArray(), token.substring(separator + 1).toByteArray())) {
            return null
        }
        val fields = try {
            String(Base64.getUrlDecoder().decode(token.substring(0, separator)), StandardCharsets.UTF_8).split('|')
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (fields.size != 4) {
            return null
        }
        return SignedValue(
            kind = fields[0],
            value = fields[1],
            steamId = fields[2],
            expiresAt = fields[3].toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null,
        )
    }

    private fun clearCookie(response: HttpServletResponse, name: String) {
        response.addHeader("Set-Cookie", buildCookie(name, "", 0))
    }

    private fun buildCookie(name: String, value: String, maxAgeSeconds: Long): String = buildString {
        append(name)
        append('=')
        append(value)
        append("; Path=/; Max-Age=")
        append(maxAgeSeconds)
        append("; HttpOnly; SameSite=Lax")
        if (properties.normalizedBaseUrl.startsWith("https://")) {
            append("; Secure")
        }
    }

    private fun signature(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.sessionSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun formEncode(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
    }

    private fun buildUrl(base: URI, parameters: Map<String, String>): String = "$base?${formEncode(parameters)}"

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private data class SignedValue(
        val kind: String,
        val value: String,
        val steamId: String,
        val expiresAt: Instant,
    )

    private companion object {
        const val OPENID_STATE_COOKIE = "workshop_openid_state"
        const val ADMIN_SESSION_COOKIE = "workshop_admin_session"
        const val OPENID_NAMESPACE = "http://specs.openid.net/auth/2.0"
        const val OPENID_IDENTIFIER_SELECT = "http://specs.openid.net/auth/2.0/identifier_select"
        const val OPENID_STATE_SECONDS = 10 * 60L
        const val DEFAULT_SESSION_SECRET = "development-only-secret-change-me"
        const val USER_AGENT = "WorkshopVault/0.1"
        val STEAM_OPENID_LOGIN_URI = URI("https://steamcommunity.com/openid/login")
        val STEAM_CLAIMED_ID_REGEX = Regex("https://steamcommunity\\.com/openid/id/(\\d{17})")
        val STEAM_ID_REGEX = Regex("\\d{17}")
    }

    private val callbackUrl: String
        get() = "${properties.normalizedBaseUrl}/api/admin/steam/callback"
}
