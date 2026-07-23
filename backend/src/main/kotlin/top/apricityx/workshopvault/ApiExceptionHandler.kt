package top.apricityx.workshopvault

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(error: ApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(error.status).body(ApiError(error.code, error.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationError(error: MethodArgumentNotValidException): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(
            ApiError(
                code = "invalid_request",
                message = error.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "请求参数无效。",
            ),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableRequest(): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError("invalid_request", "请求 JSON 格式无效。"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedError(request: HttpServletRequest, error: Exception): ResponseEntity<ApiError> {
        if (request.requestURI.startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError("internal_error", "服务暂时无法完成该请求。"))
        }
        throw error
    }
}
