package io.middlepoint.mcponandroid.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.middlepoint.mcponandroid.mcp.McpAccess
import io.middlepoint.mcponandroid.mcp.McpServer
import io.middlepoint.mcponandroid.mcp.ServerState
import io.middlepoint.mcponandroid.utils.DnsDiscover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ServerViewModel(application: Application) : AndroidViewModel(application) {

  private val mcpServer: McpServer = McpServer.getInstance(application)
  private val dnsDiscover: DnsDiscover = DnsDiscover.getInstance(application)

  private val _uiState = MutableStateFlow(ServerState())
  val uiState: StateFlow<ServerState> = _uiState.asStateFlow()

  init {
    // Observe the server's running state and update the UI state accordingly.
    mcpServer.isRunning
      .onEach { isRunning ->
        val serverAddress = if (isRunning) {
          val ipAddress = dnsDiscover.getLocalIpAddress()
          if (ipAddress != null) {
            McpAccess.url(getApplication(), ipAddress)
          } else {
            null // Or some error/fallback address
          }
        } else {
          null
        }
        _uiState.value = ServerState(isRunning = isRunning, serverAddress = serverAddress,
          authKey = if (isRunning) McpAccess.token(getApplication()) else null)
      }
      .launchIn(viewModelScope)
  }

}
