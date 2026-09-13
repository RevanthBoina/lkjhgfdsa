package com.aniob.app.bridge

import com.aniob.app.service.AniobAccessibilityService
import com.aniob.core.domain.AniobAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Embedded Bridge Server running on localhost:8765 for automated benchmarking and ADB-less harness control.
 */
class AniobBridgeServer(private val port: Int = 8765) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
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

                val requestLine = reader.readLine().orEmpty()
                val responseJson = processRequest(requestLine)
                writer.println(responseJson)
                socket.close()
            } catch (e: Exception) {
                // Socket handling error
            }
        }
    }

    private fun processRequest(request: String): String {
        return try {
            val json = JSONObject(request)
            val command = json.optString("cmd", "STATUS")

            when (command) {
                "PING" -> JSONObject().apply {
                    put("status", "OK")
                    put("service_connected", AniobAccessibilityService.isServiceConnected)
                }.toString()

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
