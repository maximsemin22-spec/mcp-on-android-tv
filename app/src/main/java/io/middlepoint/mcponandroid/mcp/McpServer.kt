package io.middlepoint.mcponandroid.mcp

import android.annotation.SuppressLint
import android.content.Context
import co.touchlab.kermit.Logger
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.cio.CIO
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.middlepoint.mcponandroid.utils.ADB
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private class InvalidMcpAccessKey : RuntimeException()

class McpServer private constructor(
  private val context: Context
) {

  private val _isRunning = MutableStateFlow(false)
  private val _lastMcpRequest = MutableStateFlow("No MCP request received")
  private val _lastMcpResponse = MutableStateFlow("No MCP response yet")
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
        name = "Android TV Admin MCP",
        version = "0.2.0",
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
          install(ContentNegotiation) {
            json(McpJson)
          }

          // A new random key is generated on the TV on first launch.
          // Every MCP request, including tool discovery, must present that key.
          // The same URL works in Joey and other Streamable HTTP MCP clients.
          install(StatusPages) {
            exception<InvalidMcpAccessKey> { call, _ ->
              call.respondText(
                "MCP access denied. Use the authenticated connection URL shown on the TV.",
                status = HttpStatusCode.Unauthorized,
              )
            }
          }

          install(createApplicationPlugin(name = "McpAccessGuard") {
            onCall { call ->
              if (call.request.path().startsWith("/mcp")) {
                val queryKey = call.request.queryParameters["key"]
                val headerKey = call.request.headers["X-MCP-Key"]
                val bearer = call.request.headers[HttpHeaders.Authorization]
                  ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                  ?.substringAfter(" ")
                if (!McpAccess.accepts(context, queryKey ?: headerKey ?: bearer)) {
                  throw InvalidMcpAccessKey()
                }
              }
            }
          })

          install(CORS) {
            // The per-device access key is required even when CORS permits a client.
            // Do not expose port 8081 to the internet; use only on a trusted LAN.
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

          // Local diagnostics for inspecting Joey's incoming MCP requests.
          install(createApplicationPlugin(name = "McpRequestDiagnostics") {
            onCall { call ->
              if (call.request.path() == "/mcp") {
                val accept = call.request.headers[HttpHeaders.Accept] ?: "<missing>"
                val contentType = call.request.headers[HttpHeaders.ContentType] ?: "<missing>"
                val session = if (call.request.headers["Mcp-Session-Id"] == null) "no" else "yes"
                _lastMcpRequest.value =
                  "${call.request.httpMethod.value} /mcp | Accept=$accept | Content-Type=$contentType | Session=$session"
                logger.d { "MCP incoming: ${_lastMcpRequest.value}" }
              }
            }
            onCallRespond { call ->
              if (call.request.path() == "/mcp") {
                _lastMcpResponse.value = "HTTP ${call.response.status()?.value ?: "unknown"}"
                logger.d { "MCP response: ${_lastMcpResponse.value}" }
              }
            }
          })

          routing {
            get("/health") {
              call.respondText(
                "Android TV MCP server is running\n" +
                  "Last request: ${_lastMcpRequest.value}\n" +
                  "Last response: ${_lastMcpResponse.value}\n"
              )
            }
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
