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
import java.util.concurrent.TimeUnit
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
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"auth\":{\"logout\":{}}},\"authMethods\":[{\"id\":\"oauth-personal\",\"name\":\"Login with Google\"}]}}");
                            System.out.flush();
                        } else if (line.contains("\"method\":\"authenticate\"")) {
                            String leakedKey = System.getenv("GEMINI_API_KEY");
                            System.err.println("env_key=" + (leakedKey == null ? "ABSENT" : leakedKey));
                            System.err.println("Visit https://accounts.google.com/o/oauth2/v2/auth?client_id=demo if browser closed");
                            System.err.flush();
                            String respId = line.contains("\"id\":6") ? "6" : "2";
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + respId + ",\"result\":{}}");
                            System.out.flush();
                        } else if (line.contains("\"method\":\"logout\"")) {
                            System.err.println("logged_out_cleanly");
                            System.err.flush();
                            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}");
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

            // 验证官方 ACP logout 与重新 authenticate 流程
            transport.sendRequest("logout", JsonObject(), 30).get(30, TimeUnit.SECONDS)
            transport.sendRequest("authenticate", AcpModels.AuthenticateParams("oauth-personal"), 30).get(30, TimeUnit.SECONDS)
            assertTrue(stderrLines.any { it.contains("logged_out_cleanly") })
        } finally {
            transport.close()
        }
        assertNull(runCatching { exitLatch.await(1, TimeUnit.SECONDS) }.exceptionOrNull())
    }
}
