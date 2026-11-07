package me.simpleHook.feature.mcp.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Minimal, defensive HTTP/1.1 request reader and response writer.
 *
 * The MCP endpoint only ever receives `POST` requests with a small JSON body from a desktop
 * client on the same device, so a full HTTP stack (Ktor, NanoHTTPD, ...) would add a
 * dependency for no benefit. This class implements exactly the subset that the Streamable
 * HTTP transport needs and rejects everything else with a well-defined status code.
 *
 * Hardening:
 *  * request line, header count, header size and body size are bounded;
 *  * `Transfer-Encoding` is not supported (clients send `Content-Length`) and is rejected
 *    with `411 Length Required`;
 *  * header names are matched case-insensitively, values are trimmed;
 *  * the connection is closed after every response (`Connection: close`).
 */
object HttpCodec {

    /** Maximum size of the request line plus all headers. */
    private const val MAX_HEAD_BYTES = 16 * 1024

    private const val MAX_HEADER_COUNT = 64

    /** Default maximum body size, large enough for `set_hook_config` payloads. */
    const val DEFAULT_MAX_BODY_BYTES = 4 * 1024 * 1024

    data class Request(
        val method: String,
        val target: String,
        val httpVersion: String,
        /** Header names lower-cased; repeated headers are joined by ", ". */
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun header(name: String): String? = headers[name.lowercase()]

        fun bodyText(): String = String(body, StandardCharsets.UTF_8)

        /** Path component of the request target, without the query string. */
        val path: String
            get() {
                // Absolute-form targets (http://host/path) are unwrapped to their path.
                val withoutScheme = target.substringAfter("://", target)
                val pathPart = if (target.contains("://")) {
                    "/" + withoutScheme.substringAfter('/', "")
                } else {
                    target
                }
                return pathPart.substringBefore('?')
            }
    }

    /** Raised when the peer sends something that is not a valid HTTP request. */
    class MalformedRequest(val status: Int, message: String) : IOException(message)

    /**
     * Reads one request. Returns `null` when the peer closes the connection before sending
     * any byte (a normal occurrence for health checks).
     */
    fun readRequest(
        input: InputStream,
        maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES
    ): Request? {
        val stream = if (input is BufferedInputStream) input else BufferedInputStream(input)

        val requestLine = readLine(stream, MAX_HEAD_BYTES) ?: return null
        if (requestLine.isEmpty()) return null

        val parts = requestLine.split(' ')
        if (parts.size != 3) {
            throw MalformedRequest(400, "Malformed request line")
        }
        val (method, target, version) = parts
        if (!version.startsWith("HTTP/1.")) {
            throw MalformedRequest(505, "Unsupported HTTP version")
        }

        val headers = LinkedHashMap<String, String>()
        var headBytes = requestLine.length
        while (true) {
            val line = readLine(stream, MAX_HEAD_BYTES - headBytes) ?: return null
            headBytes += line.length + 2
            if (headBytes > MAX_HEAD_BYTES) {
                throw MalformedRequest(431, "Request header too large")
            }
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) {
                throw MalformedRequest(400, "Malformed header line")
            }
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty()) {
                throw MalformedRequest(400, "Empty header name")
            }
            if (headers.size >= MAX_HEADER_COUNT && !headers.containsKey(name)) {
                throw MalformedRequest(431, "Too many headers")
            }
            headers[name] = if (headers.containsKey(name)) {
                headers.getValue(name) + ", " + value
            } else {
                value
            }
        }

        if (headers.containsKey("transfer-encoding")) {
            // Chunked bodies are not needed by MCP clients and complicate framing.
            throw MalformedRequest(411, "Transfer-Encoding is not supported; send Content-Length")
        }

        val contentLengthHeader = headers["content-length"]
        val contentLength = when {
            contentLengthHeader == null -> 0
            else -> contentLengthHeader.toIntOrNull()
                ?: throw MalformedRequest(400, "Invalid Content-Length")
        }
        if (contentLength < 0) {
            throw MalformedRequest(400, "Invalid Content-Length")
        }
        if (contentLength > maxBodyBytes) {
            throw MalformedRequest(413, "Request body too large")
        }

        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = stream.read(body, read, contentLength - read)
            if (count < 0) throw MalformedRequest(400, "Truncated request body")
            read += count
        }

        return Request(method.uppercase(), target, version, headers, body)
    }

    /** Reads a CRLF (or LF) terminated ASCII line, bounded by [limit] bytes. */
    private fun readLine(input: InputStream, limit: Int): String? {
        val buffer = ByteArrayOutputStream(128)
        var previous = -1
        while (true) {
            val current = try {
                input.read()
            } catch (timeout: SocketTimeoutException) {
                throw timeout
            }
            if (current < 0) {
                if (previous < 0 && buffer.size() == 0) return null
                break
            }
            if (current == '\n'.code) {
                break
            }
            if (buffer.size() >= limit.coerceAtMost(MAX_HEAD_BYTES)) {
                throw MalformedRequest(431, "Header line too long")
            }
            if (current != '\r'.code) {
                buffer.write(current)
            }
            previous = current
        }
        return String(buffer.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    /** Response written back to the client. */
    data class Response(
        val status: Int,
        val reason: String,
        val body: ByteArray = ByteArray(0),
        val contentType: String? = null,
        val extraHeaders: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun json(status: Int, reason: String, body: String) = Response(
                status = status,
                reason = reason,
                body = body.toByteArray(StandardCharsets.UTF_8),
                contentType = "application/json"
            )

            fun empty(status: Int, reason: String) = Response(status, reason)
        }
    }

    fun writeResponse(output: java.io.OutputStream, response: Response, keepAlive: Boolean = false) {
        val out = if (output is BufferedOutputStream) output else BufferedOutputStream(output)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        response.contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        response.extraHeaders.forEach { (name, value) ->
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (response.body.isNotEmpty()) out.write(response.body)
        out.flush()
    }

    /** Standard reason phrases used by the transport. */
    fun reason(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        505 -> "HTTP Version Not Supported"
        else -> "Status"
    }
}
