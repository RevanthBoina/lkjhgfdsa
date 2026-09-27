package com.aniob.app.bridge

import com.aniob.app.service.AniobAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Embedded Bridge Server running strictly on 127.0.0.1:8765 for automated benchmarking and harness control.
 * Bound strictly to localhost; requires constant-time session token authentication on all endpoints except PING.
 */
class AniobBridgeServer(
    private val port: Int = 8765,
    private val host: String = "127.0.0.1"
) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeSessionToken: String? = null

    /**
     * Host pushes the session token at session start. Requests without this token return 401.
     */
    fun setSessionToken(token: String?) {
        activeSessionToken = token
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                // Strictly bind to 127.0.0.1, never wildcard 0.0.0.0
                val bindAddr = InetAddress.getByName(host)
                serverSocket = ServerSocket(port, 50, bindAddr)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                // Server closed or network error
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Already closed
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val firstLine = reader.readLine().orEmpty()
                if (firstLine.isBlank()) {
                    socket.close()
                    return@launch
                }

                val responseStr: String
                if (firstLine.startsWith("GET ") || firstLine.startsWith("POST ")) {
                    // HTTP-style request
                    val parts = firstLine.split(" ")
                    val fullPath = parts.getOrNull(1).orEmpty()
                    val pathAndQuery = fullPath.split("?", limit = 2)
                    val path = pathAndQuery[0]
                    val query = pathAndQuery.getOrNull(1)

                    var headerToken: String? = null
                    var contentLength = 0
                    var line: String? = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        val lower = line.lowercase()
                        if (lower.startsWith("x-session-token:")) {
                            headerToken = line.substringAfter(":").trim()
                        } else if (lower.startsWith("authorization: bearer ")) {
                            headerToken = line.substringAfter("Bearer ").trim()
                        } else if (lower.startsWith("content-length:")) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                        line = reader.readLine()
                    }

                    var body = ""
                    if (contentLength > 0) {
                        val bodyChars = CharArray(contentLength)
                        var readTotal = 0
                        while (readTotal < contentLength) {
                            val r = reader.read(bodyChars, readTotal, contentLength - readTotal)
                            if (r == -1) break
                            readTotal += r
                        }
                        body = String(bodyChars, 0, readTotal)
                    }

                    val queryToken = query?.split("&")?.firstOrNull { it.startsWith("token=") }?.substringAfter("token=")
                    val bodyJson = try { if (body.isNotBlank()) JSONObject(body) else null } catch (_: Exception) { null }
                    val bodyToken = bodyJson?.optString("token")

                    val token = headerToken ?: queryToken ?: (if (!bodyToken.isNullOrBlank()) bodyToken else null)
                    val isPing = path.equals("/ping", ignoreCase = true) || bodyJson?.optString("cmd") == "PING"

                    if (isPing) {
                        val jsonResp = JSONObject().apply {
                            put("status", "OK")
                            put("service_connected", AniobAccessibilityService.isServiceConnected)
                        }.toString()
                        responseStr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n$jsonResp"
                    } else if (!validateToken(token)) {
                        val jsonResp = JSONObject().apply {
                            put("status", "UNAUTHORIZED")
                            put("error", "401 Unauthorized: Valid session token required")
                        }.toString()
                        responseStr = "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n\r\n$jsonResp"
                    } else {
                        val jsonResp = processCommand(path, bodyJson)
                        responseStr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n$jsonResp"
                    }
                } else {
                    // Raw JSON protocol
                    val json = try { JSONObject(firstLine) } catch (_: Exception) { null }
                    val cmd = json?.optString("cmd", "STATUS") ?: "UNKNOWN"
                    val token = json?.optString("token")

                    val isPing = cmd.equals("PING", ignoreCase = true)
                    if (isPing) {
                        responseStr = JSONObject().apply {
                            put("status", "OK")
                            put("service_connected", AniobAccessibilityService.isServiceConnected)
                        }.toString()
                    } else if (!validateToken(token)) {
                        responseStr = JSONObject().apply {
                            put("status", "UNAUTHORIZED")
                            put("code", 401)
                            put("message", "401 Unauthorized: Valid session token required")
                        }.toString()
                    } else {
                        responseStr = processCommand(cmd, json)
                    }
                }

                writer.println(responseStr)
                socket.close()
            } catch (e: Exception) {
                // Socket handling error
            }
        }
    }

    private fun validateToken(token: String?): Boolean {
        val current = activeSessionToken
        if (current.isNullOrBlank() || token.isNullOrBlank()) return false
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val currentBytes = current.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(tokenBytes, currentBytes)
    }

    private fun processCommand(cmdOrPath: String, json: JSONObject?): String {
        return try {
            val command = json?.optString("cmd") ?: cmdOrPath.trimStart('/').uppercase()

            when (command) {
                "DUMP_HIERARCHY" -> {
                    val a11y = AniobAccessibilityService.instance
                    val screenState = a11y?.captureCurrentScreenState()
                    JSONObject().apply {
                        put("status", "OK")
                        put("packageName", screenState?.packageName)
                        put("nodeCount", screenState?.nodes?.size ?: 0)
                        put("treeHash", screenState?.treeHash)
                    }.toString()
                }

                else -> JSONObject().apply {
                    put("status", "UNKNOWN_COMMAND")
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", "ERROR")
                put("message", e.message)
            }.toString()
        }
    }
}
