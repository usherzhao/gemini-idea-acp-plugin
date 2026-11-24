package com.geminicode.acp.ide

import com.geminicode.acp.protocol.AcpModels
import com.geminicode.acp.settings.GeminiAcpSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPermissionPolicyTest {

    @Test
    fun `presets and summary badges match expected levels`() {
        assertTrue(DataPermissionConfig.READ_ONLY.isReadOnlyPreset())
        assertEquals("🔒 只读模式 ▾", DataPermissionConfig.READ_ONLY.summaryBadgeText())
        assertEquals(AccessLevel.AUTO, DataPermissionConfig.READ_ONLY.effectiveReadLevel())
        assertEquals(AccessLevel.DENY, DataPermissionConfig.READ_ONLY.effectiveWriteLevel())
        assertEquals(AccessLevel.DENY, DataPermissionConfig.READ_ONLY.effectiveCommandLevel())
        assertTrue(DataPermissionConfig.READ_ONLY.requiresStrictServerApproval())

        assertTrue(DataPermissionConfig.STANDARD.isStandardPreset())
        assertEquals("🛡️ 权限: 标准 ▾", DataPermissionConfig.STANDARD.summaryBadgeText())
        assertTrue(DataPermissionConfig.STANDARD.requiresStrictServerApproval())

        assertTrue(DataPermissionConfig.FULL_AUTO.isFullAutoPreset())
        assertEquals("⚡ 权限: 全自动 ▾", DataPermissionConfig.FULL_AUTO.summaryBadgeText())
        assertFalse(DataPermissionConfig.FULL_AUTO.requiresStrictServerApproval())
    }

    @Test
    fun `readOnlyMode always forces write level to DENY even if writeFile is AUTO`() {
        val cfg = DataPermissionConfig(
            readOnlyMode = true,
            readFile = AccessLevel.ASK,
            writeFile = AccessLevel.AUTO,
            command = AccessLevel.ASK
        )
        assertEquals(AccessLevel.DENY, cfg.effectiveWriteLevel())
        assertEquals(AccessLevel.DENY, cfg.effectiveLevelFor(ToolOperationCategory.WRITE))
        assertEquals(AccessLevel.ASK, cfg.effectiveReadLevel())
        assertEquals(AccessLevel.ASK, cfg.effectiveCommandLevel())
        assertEquals("🔒 只读(读:问 命:问) ▾", cfg.summaryBadgeText())
        assertTrue(cfg.requiresStrictServerApproval())
    }

    @Test
    fun `custom mix summary badge displays all three compact flags`() {
        val cfg = DataPermissionConfig(
            readOnlyMode = false,
            readFile = AccessLevel.DENY,
            writeFile = AccessLevel.ASK,
            command = AccessLevel.DENY
        )
        assertEquals("🛡️ 读:禁 写:问 命:禁 ▾", cfg.summaryBadgeText())
        assertTrue(cfg.tooltipHtml().contains("读文件：禁止"))
        assertTrue(cfg.tooltipHtml().contains("写文件：每次确认"))
        assertTrue(cfg.tooltipHtml().contains("终端命令：禁止"))
    }

    @Test
    fun `classifyToolCall maps ACP kinds and title fallbacks accurately`() {
        assertEquals(ToolOperationCategory.READ, DataPermissionPolicy.classifyKindAndTitle("read", "Read File"))
        assertEquals(ToolOperationCategory.READ, DataPermissionPolicy.classifyKindAndTitle("search", "Grep"))
        assertEquals(ToolOperationCategory.WRITE, DataPermissionPolicy.classifyKindAndTitle("edit", "Edit File"))
        assertEquals(ToolOperationCategory.WRITE, DataPermissionPolicy.classifyKindAndTitle("delete", "Delete"))
        assertEquals(ToolOperationCategory.COMMAND, DataPermissionPolicy.classifyKindAndTitle("execute", "Run tests"))
        assertEquals(ToolOperationCategory.COMMAND, DataPermissionPolicy.classifyKindAndTitle("shell", "git status"))

        // Title fallback when kind is "other" or absent: command & write take priority over read
        assertEquals(
            ToolOperationCategory.COMMAND,
            DataPermissionPolicy.classifyToolCall(AcpModels.PermissionToolCall(kind = "other", title = "run_command: cat README.md"))
        )
        assertEquals(
            ToolOperationCategory.WRITE,
            DataPermissionPolicy.classifyToolCall(AcpModels.PermissionToolCall(kind = null, title = "write_to_file src/App.kt"))
        )
        assertEquals(
            ToolOperationCategory.READ,
            DataPermissionPolicy.classifyToolCall(AcpModels.PermissionToolCall(kind = "other", title = "view_file Main.java"))
        )
        assertEquals(
            ToolOperationCategory.OTHER,
            DataPermissionPolicy.classifyToolCall(AcpModels.PermissionToolCall(kind = "other", title = "custom_mcp_action"))
        )
    }

    @Test
    fun `settings state migrates legacy flags and round-trips DataPermissionConfig`() {
        val settings = GeminiAcpSettingsState()
        // Legacy defaults: autoApproveReadTools=true, requireDiffBeforeWrite=true -> STANDARD
        assertEquals(DataPermissionConfig.STANDARD, settings.getDataPermissionConfig())

        // Legacy explicit disable of both booleans without new fields set
        settings.state.autoApproveReadTools = false
        settings.state.requireDiffBeforeWrite = false
        val migrated = settings.getDataPermissionConfig()
        assertEquals(AccessLevel.ASK, migrated.readFile)
        assertEquals(AccessLevel.AUTO, migrated.writeFile)
        assertEquals(AccessLevel.ASK, migrated.command)

        // Explicit save & reload
        val custom = DataPermissionConfig(
            readOnlyMode = true,
            readFile = AccessLevel.DENY,
            writeFile = AccessLevel.DENY,
            command = AccessLevel.ASK
        )
        settings.setDataPermissionConfig(custom)
        assertEquals(custom, settings.getDataPermissionConfig())
        assertFalse(settings.state.autoApproveReadTools)
        assertTrue(settings.state.requireDiffBeforeWrite)
    }
}
