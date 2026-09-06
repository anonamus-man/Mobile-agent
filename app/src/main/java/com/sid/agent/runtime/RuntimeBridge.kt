package com.sid.agent.runtime

import com.sid.agent.model.ChatMessage
import com.sid.agent.model.ChangeItem
import com.sid.agent.model.ProviderProfile
import com.sid.agent.model.ProjectKind
import com.sid.agent.model.RuntimeEvent
import com.sid.agent.model.ToolRequest
import kotlinx.coroutines.flow.Flow

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>
    suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String
    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}

object RuntimeLaunchConfigBuilder {
    fun build(profile: ProviderProfile, authToken: String? = null, localGatewayUrl: String? = null): RuntimeLaunchConfig {
        val environment = linkedMapOf("DISABLE_AUTOUPDATER" to "1")
        when (profile.kind.protocol) {
            com.sid.agent.model.ProviderProtocol.CLAUDE_LOGIN -> Unit
            com.sid.agent.model.ProviderProtocol.ANTHROPIC -> {
                environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.sid.agent.model.ProviderProtocol.ANTHROPIC_GATEWAY -> {
                environment["ANTHROPIC_BASE_URL"] = profile.baseUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.sid.agent.model.ProviderProtocol.OPENAI_RESPONSES,
            com.sid.agent.model.ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = profile.model
            }
        }
        if (profile.kind.protocol != com.sid.agent.model.ProviderProtocol.CLAUDE_LOGIN) {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = profile.model
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = profile.model
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = profile.model
            environment["ANTHROPIC_SMALL_MODEL"] = profile.model
            environment["ANTHROPIC_FAST_MODEL"] = profile.model
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = profile.model
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            environment["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            environment["DISABLE_TELEMETRY"] = "1"
            if (!authToken.isNullOrBlank()) {
                environment["ANTHROPIC_API_KEY"] = authToken
                environment["ANTHROPIC_AUTH_TOKEN"] = authToken
                environment["OPENROUTER_API_KEY"] = authToken
            }
        }
        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
