package com.geminicode.acp.protocol

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class JsonRpcTransportTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `bidirectional stdio json-rpc handles initialize, oauth-personal auth, session updates, and reverse-rpc`() {
        val workDir = tempFolder.newFolder("proj")
        val mockJava = tempFolder.newFile("MockAcpAgent.java").toPath()
        Files.writeString(
            mockJava,
            """
            import java.io.*;
            import java.nio.charset.StandardCharsets;

            public class MockAcpAgent {
                public static void main(String[] args) throws Exception {
                    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.contains("\"method\":\"initialize\"")) {
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"promptCapabilities\":{\"image\":true,\"embeddedContext\":true},\"sessionCapabilities\":{\"close\":{},\"resume\":{},\"additionalDirectories\":{}},\"auth\":{\"logout\":{}}},\"authMethods\":[{\"id\":\"oauth-personal\",\"name\":\"Login with Google\"}]}}");
                            System.out.flush();
                        } else if (line.contains("\"method\":\"authenticate\"")) {
                            String leakedKey = System.getenv("GEMINI_API_KEY");
                            System.err.println("env_key=" + (leakedKey == null ? "ABSENT" : leakedKey));
                            System.err.println("Visit https://accounts.google.com/o/oauth2/v2/auth?client_id=demo if browser closed");
                            System.err.flush();
                            String respId = line.contains("\"id\":8") ? "8" : "2";
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + respId + ",\"result\":{}}");
                            System.out.flush();
                        } else if (line.contains("\"method\":\"session/close\"")) {
                            System.err.println("session_closed");
                            System.err.flush();
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}");
                            System.out.flush();
                        } else if (line.contains("\"method\":\"session/resume\"")) {
                            System.err.println("session_resumed");
                            System.err.flush();
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{\"sessionId\":\"sess_test_1\"}}");
                            System.out.flush();
                        } else if (line.contains("\"method\":\"logout\"")) {
                            System.err.println("logged_out_cleanly");
                            System.err.flush();
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}");
                            System.out.flush();
                        } else if (line.contains("\"method\":\"session/new\"")) {
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"sessionId\":\"sess_test_1\"}}");
                            System.out.flush();
                        } else if (line.contains("\"method\":\"session/prompt\"")) {
                            System.out.println("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"sess_test_1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"Editing...\"}}}}");
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"fs/write_text_file\",\"params\":{\"sessionId\":\"sess_test_1\",\"path\":\"App.kt\",\"content\":\"fun main() {}\"}}");
                            System.out.flush();
                            String ack = in.readLine();
                            if (ack != null && ack.contains("\"id\":99")) {
                                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"end_turn\"}}");
                                System.out.flush();
                            }
                        }
                    }
                }
            }
            """.trimIndent()
        )

        val javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val notifications = CopyOnWriteArrayList<String>()
        val stderrLines = CopyOnWriteArrayList<String>()
        val writtenPath = AtomicReference<String>()
        val writtenContent = AtomicReference<String>()
        val exitLatch = CountDownLatch(1)

        val transport = JsonRpcTransport(
            command = listOf(javaExe, mockJava.toAbsolutePath().toString()),
            workingDirectory = workDir,
            extraEnv = mapOf("HTTP_PROXY" to "http://127.0.0.1:7890"),
            removedEnvKeys = setOf("GEMINI_API_KEY"),
            requestHandler = object : JsonRpcTransport.IncomingRequestHandler {
                override fun handleRequest(method: String, params: JsonObject): JsonObject {
                    if (method == "fs/write_text_file") {
                        writtenPath.set(params.get("path").asString)
                        writtenContent.set(params.get("content").asString)
                    }
                    return JsonObject()
                }
            },
            onNotification = { method, params ->
                if (method == "session/update") {
                    val ev = AcpModels.parseSessionUpdate(params)
                    if (ev is AcpModels.SessionUpdateEvent.AgentMessageChunk) {
                        notifications.add(ev.text)
                    }
                }
            },
            onStderrLine = { stderrLines.add(it) },
            onProcessExit = { _, _ -> exitLatch.countDown() }
        )

        try {
            transport.start()

            val initRes = transport.sendRequest("initialize", AcpModels.InitializeParams(), 30).get(30, TimeUnit.SECONDS).asJsonObject
            assertEquals(1, initRes.get("protocolVersion").asInt)
            assertEquals("oauth-personal", initRes.getAsJsonArray("authMethods")[0].asJsonObject.get("id").asString)

            transport.sendRequest("authenticate", AcpModels.AuthenticateParams("oauth-personal"), 30).get(30, TimeUnit.SECONDS)

            val sessRes = transport.sendRequest("session/new", AcpModels.NewSessionParams(workDir.absolutePath), 30).get(30, TimeUnit.SECONDS).asJsonObject
            assertEquals("sess_test_1", sessRes.get("sessionId").asString)

            val promptRes = transport.sendRequest(
                "session/prompt",
                AcpModels.PromptParams("sess_test_1", listOf(AcpModels.ContentBlock(text = "Edit App.kt"))),
                30
            ).get(30, TimeUnit.SECONDS).asJsonObject

            assertEquals("end_turn", promptRes.get("stopReason").asString)
            assertEquals(listOf("Editing..."), notifications)
            assertEquals("App.kt", writtenPath.get())
            assertEquals("fun main() {}", writtenContent.get())
            assertTrue(stderrLines.any { it.contains("env_key=ABSENT") })
            assertTrue(stderrLines.any { it.contains("https://accounts.google.com/o/oauth2/v2/auth") })

            // 验证官方 ACP session/close、session/resume、logout 与重新 authenticate 流程
            transport.sendRequest("session/close", AcpModels.CloseSessionParams("sess_test_1"), 30).get(30, TimeUnit.SECONDS)
            val resumeParams = AcpModels.buildSessionTargetParamsJson("sess_test_1", workDir.absolutePath, listOf(workDir.absolutePath))
            val resumed = transport.sendRequest("session/resume", resumeParams, 30).get(30, TimeUnit.SECONDS).asJsonObject
            assertEquals("sess_test_1", resumed.get("sessionId").asString)
            transport.sendRequest("logout", JsonObject(), 30).get(30, TimeUnit.SECONDS)
            transport.sendRequest("authenticate", AcpModels.AuthenticateParams("oauth-personal"), 30).get(30, TimeUnit.SECONDS)
            assertTrue(stderrLines.any { it.contains("session_closed") })
            assertTrue(stderrLines.any { it.contains("session_resumed") })
            assertTrue(stderrLines.any { it.contains("logged_out_cleanly") })
        } finally {
            transport.close()
        }
        assertNull(runCatching { exitLatch.await(1, TimeUnit.SECONDS) }.exceptionOrNull())
    }

    @Test
    fun `idleTimeout keeps long streaming and user-dialog requests alive but times out true silence`() {
        val workDir = tempFolder.newFolder("idle-proj")
        val mockJava = tempFolder.newFile("MockStreamingAgent.java").toPath()
        Files.writeString(
            mockJava,
            """
            import java.io.*;
            import java.nio.charset.StandardCharsets;

            public class MockStreamingAgent {
                public static void main(String[] args) throws Exception {
                    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.contains("slow_stream")) {
                            for (int i = 1; i <= 5; i++) {
                                Thread.sleep(550);
                                System.out.println("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"s1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"chunk" + i + "\"}}}}");
                                System.out.flush();
                            }
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"stopReason\":\"end_turn\"}}");
                            System.out.flush();
                        } else if (line.contains("slow_dialog")) {
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":50,\"method\":\"session/request_permission\",\"params\":{}}");
                            System.out.flush();
                            String ack = in.readLine();
                            if (ack != null && ack.contains("\"id\":50")) {
                                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"stopReason\":\"end_turn\"}}");
                                System.out.flush();
                            }
                        } else if (line.contains("true_hang")) {
                            // 保持静默，验证真正无响应时触发空闲超时
                        }
                    }
                }
            }
            """.trimIndent()
        )

        val javaExe = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val chunks = CopyOnWriteArrayList<String>()

        val transport = JsonRpcTransport(
            command = listOf(javaExe, mockJava.toAbsolutePath().toString()),
            workingDirectory = workDir,
            extraEnv = emptyMap(),
            removedEnvKeys = emptySet(),
            requestHandler = object : JsonRpcTransport.IncomingRequestHandler {
                override fun handleRequest(method: String, params: JsonObject): JsonObject {
                    // 模拟用户在 Diff / 权限弹窗停留 1.5 秒（超过 1 秒空闲阈值）
                    Thread.sleep(1500)
                    return JsonObject()
                }
            },
            onNotification = { method, params ->
                if (method == "session/update") {
                    val ev = AcpModels.parseSessionUpdate(params)
                    if (ev is AcpModels.SessionUpdateEvent.AgentMessageChunk) {
                        chunks.add(ev.text)
                    }
                }
            },
            onStderrLine = {},
            onProcessExit = { _, _ -> }
        )

        try {
            transport.start()

            // 1. 持续流式推送总耗时 ~2.75s > idleTimeout(2s)，不应超时
            val streamRes = transport.sendRequest(
                method = "session/prompt",
                params = mapOf("mode" to "slow_stream"),
                timeoutSeconds = 2,
                idleTimeout = true
            ).get(15, TimeUnit.SECONDS).asJsonObject
            assertEquals("end_turn", streamRes.get("stopReason").asString)
            assertEquals(5, chunks.size)

            // 2. 等待反向 RPC 用户确认弹窗 1.5s > idleTimeout(1s)，不应超时
            val dialogRes = transport.sendRequest(
                method = "session/prompt",
                params = mapOf("mode" to "slow_dialog"),
                timeoutSeconds = 1,
                idleTimeout = true
            ).get(15, TimeUnit.SECONDS).asJsonObject
            assertEquals("end_turn", dialogRes.get("stopReason").asString)

            // 3. 真正完全静默 > 1s，必须按预期抛出带清晰提示的 TimeoutException
            var caughtTimeout: Throwable? = null
            try {
                transport.sendRequest(
                    method = "session/prompt",
                    params = mapOf("mode" to "true_hang"),
                    timeoutSeconds = 1,
                    idleTimeout = true
                ).get(10, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                caughtTimeout = e.cause
            }
            assertTrue("真正静默时应抛出 TimeoutException，实际=$caughtTimeout", caughtTimeout is TimeoutException)
            assertTrue(caughtTimeout?.message.orEmpty().contains("空闲超时"))
        } finally {
            transport.close()
        }
    }
}
