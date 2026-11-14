package io.middlepoint.mcponandroid.mcp

import android.annotation.SuppressLint
import android.content.Context
import co.touchlab.kermit.Logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.middlepoint.mcponandroid.utils.ADB
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class McpServer private constructor(
  private val context: Context
) {

  private val _isRunning = MutableStateFlow(false)
  val isRunning = _isRunning.asStateFlow()

  companion object {
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var instance: McpServer? = null

    fun getInstance(context: Context): McpServer =
      instance ?: synchronized(this) {
        instance ?: McpServer(context.applicationContext).also { instance = it }
      }
  }

  private val adb = ADB.getInstance(context)
  private val logger = Logger.withTag("McpServer")

  private fun configureServer(): Server {
    val server = createBaseServer()
    val tools = McpServerTools(server, context, adb)
    tools.registerTools()
    return server
  }

  private fun createBaseServer() =
    Server(
      Implementation(
        name = "Android TV MCP",
        version = "0.1.0",
      ),
      ServerOptions(
        capabilities =
          ServerCapabilities(
            prompts = ServerCapabilities.Prompts(listChanged = true),
            resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
            tools = ServerCapabilities.Tools(listChanged = true),
            logging = null,
          ),
      ),
    )

  /**
   * Starts a Streamable HTTP MCP server using Ktor on the specified port.
   *
   * The Streamable HTTP endpoint is available at [http://localhost:$port/mcp].
   *
   * @param port The port number on which the Streamable HTTP MCP server will listen.
   * @return Unit This method does not return a value.
   */
  suspend fun runStreamableHttpMcpServerUsingKtorPlugin(port: Int) {
    logger.d("Starting Streamable HTTP MCP server on port $port")
    logger.d("Use inspector to connect to the http://localhost:$port/mcp")
    _isRunning.value = true
    try {
      withContext(Dispatchers.IO) {
        embeddedServer(CIO, host = "0.0.0.0", port = port) {
          install(CORS) {
            anyHost()
            allowNonSimpleContentTypes = true
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.AcceptEncoding)
            allowHeader(HttpHeaders.Accept)
            allowHeader("Mcp-Session-Id")
            allowHeader("Mcp-Protocol-Version")
            exposeHeader("Mcp-Session-Id")
            exposeHeader("Mcp-Protocol-Version")
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Head)
            allowMethod(HttpMethod.Options)
          }

          mcpStreamableHttp(path = "/mcp") { configureServer() }
        }
          .start(wait = true)
      }
    } finally {
      _isRunning.value = false
    }
  }
}
