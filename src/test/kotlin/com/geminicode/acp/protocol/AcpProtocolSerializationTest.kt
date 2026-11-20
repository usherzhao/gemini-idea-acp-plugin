package com.geminicode.acp.protocol

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpProtocolSerializationTest {

    private val gson = Gson()

    @Test
    fun `initialize params advertise fs capabilities and protocol version 1`() {
        val json = gson.toJsonTree(AcpModels.InitializeParams()).asJsonObject
        assertEquals(1, json.get("protocolVersion").asInt)
        val fs = json.getAsJsonObject("clientCapabilities").getAsJsonObject("fs")
        assertTrue(fs.get("readTextFile").asBoolean)
        assertTrue(fs.get("writeTextFile").asBoolean)
    }

    @Test
    fun `authenticate params serialize oauth-personal methodId`() {
        val json = gson.toJsonTree(AcpModels.AuthenticateParams("oauth-personal")).asJsonObject
        assertEquals("oauth-personal", json.get("methodId").asString)
    }

    @Test
    fun `parseSessionUpdate parses message chunk, thought chunk, and tool call`() {
        val msgParams = JsonParser.parseString(
            """
            {
              "sessionId": "s1",
              "update": {
                "sessionUpdate": "agent_message_chunk",
                "content": { "type": "text", "text": "Hello IDEA" }
              }
            }
            """.trimIndent()
        ).asJsonObject
        val msgEvent = AcpModels.parseSessionUpdate(msgParams)
        assertEquals(AcpModels.SessionUpdateEvent.AgentMessageChunk("Hello IDEA"), msgEvent)

        val thoughtParams = JsonParser.parseString(
            """
            {
              "sessionId": "s1",
              "update": {
                "sessionUpdate": "agent_thought_chunk",
                "content": { "type": "text", "text": "Checking files..." }
              }
            }
            """.trimIndent()
        ).asJsonObject
        val thoughtEvent = AcpModels.parseSessionUpdate(thoughtParams)
        assertEquals(AcpModels.SessionUpdateEvent.AgentThoughtChunk("Checking files..."), thoughtEvent)

        val toolParams = JsonParser.parseString(
            """
            {
              "sessionId": "s1",
              "update": {
                "sessionUpdate": "tool_call_update",
                "toolCallId": "call_1",
                "title": "Edit Foo.kt",
                "kind": "edit",
                "status": "completed",
                "locations": [{ "path": "/repo/Foo.kt", "line": 42 }]
              }
            }
            """.trimIndent()
        ).asJsonObject
        val toolEvent = AcpModels.parseSessionUpdate(toolParams) as AcpModels.SessionUpdateEvent.ToolCallEvent
        assertEquals("call_1", toolEvent.toolCallId)
        assertEquals("Edit Foo.kt", toolEvent.title)
        assertEquals("completed", toolEvent.status)
        assertEquals(listOf("/repo/Foo.kt:42"), toolEvent.locations)
    }

    @Test
    fun `permission request deserializes toolCall and options`() {
        val raw = """
            {
              "sessionId": "sess_1",
              "toolCall": {
                "toolCallId": "call_abc",
                "title": "Write Main.kt",
                "kind": "edit"
              },
              "options": [
                { "optionId": "allow-once", "name": "Allow once", "kind": "allow_once" },
                { "optionId": "reject", "name": "Reject", "kind": "reject_once" }
              ]
            }
        """.trimIndent()
        val req = gson.fromJson(raw, AcpModels.PermissionRequestParams::class.java)
        assertEquals("Write Main.kt", req.toolCall?.resolvedTitle())
        assertEquals(2, req.options.size)
        assertEquals("allow-once", req.options[0].optionId)
    }

    @Test
    fun `newSession result parses antigravity models, modes, and tier metadata`() {
        val raw = """
            {
              "sessionId": "sess_agy_1",
              "models": {
                "currentModelId": "gemini-4-neon-high",
                "availableModels": [
                  { "modelId": "gemini-4-neon-high", "name": "Gemini 4 Neon (High)" },
                  { "modelId": "gemini-3.8-flash-medium", "name": "Gemini 3.8 Flash (Medium)" }
                ]
              },
              "modes": {
                "currentModeId": "default",
                "availableModes": [
                  { "id": "default", "name": "Default" },
                  { "id": "plan", "name": "Plan" }
                ]
              },
              "_meta": {
                "antigravity": {
                  "tier": { "name": "Google AI Pro", "id": "g1-pro-tier" }
                }
              }
            }
        """.trimIndent()
        val res = gson.fromJson(raw, AcpModels.NewSessionResult::class.java)
        assertEquals("sess_agy_1", res.sessionId)
        assertEquals(2, res.models?.availableModels?.size)
        assertEquals("gemini-4-neon-high", res.models?.currentModelId)
        assertEquals(2, res.modes?.availableModes?.size)
        assertEquals("Google AI Pro", AcpModels.extractTierName(res._meta))
    }

    @Test
    fun `configOptions and config_option_update parse official model and thought_level dynamically`() {
        val rawSession = """
            {
              "sessionId": "sess_cfg_1",
              "configOptions": [
                {
                  "id": "model",
                  "name": "Model",
                  "category": "model",
                  "type": "select",
                  "currentValue": "gemini-4-neon",
                  "options": [
                    { "value": "gemini-4-neon", "name": "Gemini 4 Neon" },
                    { "value": "gemini-3.8-flash", "name": "Gemini 3.8 Flash" }
                  ]
                },
                {
                  "id": "thought_level",
                  "name": "Thinking Level",
                  "category": "thought_level",
                  "type": "select",
                  "currentValue": "high",
                  "options": [
                    { "value": "low", "name": "Low" },
                    { "value": "medium", "name": "Medium" },
                    { "value": "high", "name": "High" },
                    { "value": "xhigh", "name": "Extra" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val session = gson.fromJson(rawSession, AcpModels.NewSessionResult::class.java)
        assertEquals(2, session.configOptions.size)
        assertEquals("model", session.configOptions[0].id)
        assertEquals("gemini-4-neon", session.configOptions[0].currentValue)
        assertEquals("thought_level", session.configOptions[1].id)
        assertEquals(4, session.configOptions[1].options.size)
        assertEquals("Extra", session.configOptions[1].options[3].name)

        val setReq = gson.toJsonTree(AcpModels.SetConfigOptionParams("sess_cfg_1", "thought_level", "xhigh")).asJsonObject
        assertEquals("sess_cfg_1", setReq.get("sessionId").asString)
        assertEquals("thought_level", setReq.get("configId").asString)
        assertEquals("xhigh", setReq.get("value").asString)

        val updateParams = JsonParser.parseString(
            """
            {
              "sessionId": "sess_cfg_1",
              "update": {
                "sessionUpdate": "config_option_update",
                "configOptions": [
                  {
                    "id": "thought_level",
                    "name": "Thinking Level",
                    "category": "thought_level",
                    "type": "select",
                    "currentValue": "low",
                    "options": [
                      { "value": "low", "name": "Low" },
                      { "value": "medium", "name": "Medium" },
                      { "value": "high", "name": "High" }
                    ]
                  }
                ]
              }
            }
            """.trimIndent()
        ).asJsonObject
        val ev = AcpModels.parseSessionUpdate(updateParams) as AcpModels.SessionUpdateEvent.ConfigOptionsUpdated
        assertEquals(1, ev.configOptions.size)
        assertEquals("low", ev.configOptions[0].currentValue)
        assertEquals(3, ev.configOptions[0].options.size)
    }

    @Test
    fun `session list, load params, user_message_chunk, and session_info_update parse cleanly`() {
        val listRaw = """
            {
              "sessions": [
                {
                  "sessionId": "s_hist_1",
                  "cwd": "/D:/ideaProject/haidong",
                  "title": "Portal Affair Create Skill",
                  "updatedAt": "2026-11-20T14:31:59Z"
                }
              ]
            }
        """.trimIndent()
        val listRes = gson.fromJson(listRaw, AcpModels.ListSessionsResult::class.java)
        assertEquals(1, listRes.sessions.size)
        assertEquals("Portal Affair Create Skill", listRes.sessions[0].displayTitle())

        val loadJson = gson.toJsonTree(AcpModels.LoadSessionParams("s_hist_1", "D:/ideaProject/haidong")).asJsonObject
        assertEquals("s_hist_1", loadJson.get("sessionId").asString)
        assertEquals("D:/ideaProject/haidong", loadJson.get("cwd").asString)

        val userChunkParams = JsonParser.parseString(
            """
            {
              "sessionId": "s_hist_1",
              "update": {
                "sessionUpdate": "user_message_chunk",
                "content": { "type": "text", "text": "历史提问内容" }
              }
            }
            """.trimIndent()
        ).asJsonObject
        assertEquals(
            AcpModels.SessionUpdateEvent.UserMessageChunk("历史提问内容"),
            AcpModels.parseSessionUpdate(userChunkParams)
        )

        val infoParams = JsonParser.parseString(
            """
            {
              "sessionId": "s_hist_1",
              "update": {
                "sessionUpdate": "session_info_update",
                "title": "新生成的对话标题"
              }
            }
            """.trimIndent()
        ).asJsonObject
        assertEquals(
            AcpModels.SessionUpdateEvent.SessionInfoUpdated("新生成的对话标题"),
            AcpModels.parseSessionUpdate(infoParams)
        )

        val setTitleJson = gson.toJsonTree(AcpModels.SetSessionTitleParams("s_hist_1", "自定义对话名")).asJsonObject
        assertEquals("s_hist_1", setTitleJson.get("sessionId").asString)
        assertEquals("自定义对话名", setTitleJson.get("title").asString)

        val cmdsParams = JsonParser.parseString(
            """
            {
              "sessionId": "s_hist_1",
              "update": {
                "sessionUpdate": "available_commands_update",
                "availableCommands": [
                  { "name": "plan", "description": "Plan before executing" },
                  { "name": "ikuai-cli", "description": "Manage iKuai router" }
                ]
              }
            }
            """.trimIndent()
        ).asJsonObject
        val cmdsEv = AcpModels.parseSessionUpdate(cmdsParams) as AcpModels.SessionUpdateEvent.AvailableCommandsUpdated
        assertEquals(2, cmdsEv.commands.size)
        assertEquals("ikuai-cli", cmdsEv.commands[1].name)
    }

    @Test
    fun `initialize result detects official ACP auth logout capability`() {
        val agyInitJson = """
            {
              "protocolVersion": 1,
              "agentCapabilities": {
                "loadSession": true,
                "auth": { "logout": {} }
              },
              "authMethods": [
                { "id": "oauth-personal", "name": "Log in with Google" }
              ]
            }
        """.trimIndent()
        val agyInit = gson.fromJson(agyInitJson, AcpModels.InitializeResult::class.java)
        assertTrue(agyInit.supportsLogout())

        val legacyInitJson = """
            {
              "protocolVersion": 1,
              "agentCapabilities": { "loadSession": false },
              "authMethods": [
                { "id": "oauth-personal", "name": "Login with Google" }
              ]
            }
        """.trimIndent()
        val legacyInit = gson.fromJson(legacyInitJson, AcpModels.InitializeResult::class.java)
        assertFalse(legacyInit.supportsLogout())

        val v2InitJson = """
            {
              "protocolVersion": 2,
              "authMethods": [
                { "id": "oauth-personal", "name": "Login with Google" }
              ]
            }
        """.trimIndent()
        val v2Init = gson.fromJson(v2InitJson, AcpModels.InitializeResult::class.java)
        assertTrue(v2Init.supportsLogout())
    }
}
