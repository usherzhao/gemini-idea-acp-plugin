package com.geminicode.acp.ide

import com.geminicode.acp.protocol.AcpModels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 扫描本地已安装的 Skills（SKILL.md）并与官方 ACP 服务端推送的 availableCommands 合并，
 * 为输入框 `/` 快捷补全提供实时、去重的技能与斜杠命令列表。
 */
object SkillDiscoveryService {

    private val nameRegex = Regex("""(?m)^name:\s*["']?([^"'\r\n]+)["']?\s*$""")
    private val descRegex = Regex("""(?m)^description:\s*["']?([^"'\r\n]+)["']?\s*$""")

    fun discoverLocalSkills(
        projectBasePath: String?,
        userHome: Path = Paths.get(System.getProperty("user.home"))
    ): List<AcpModels.AvailableCommand> {
        val result = linkedMapOf<String, AcpModels.AvailableCommand>()
        val roots = buildList {
            if (!projectBasePath.isNullOrBlank()) {
                val proj = Paths.get(projectBasePath)
                add(proj.resolve(".agents").resolve("skills"))
                add(proj.resolve(".gemini").resolve("skills"))
            }
            add(userHome.resolve(".gemini").resolve("skills"))
            add(userHome.resolve(".gemini").resolve("antigravity").resolve("builtin").resolve("skills"))

            val pluginsRoot = userHome.resolve(".gemini").resolve("config").resolve("plugins")
            if (Files.isDirectory(pluginsRoot)) {
                runCatching {
                    Files.list(pluginsRoot).use { stream ->
                        stream.filter { Files.isDirectory(it) }.forEach { pluginDir ->
                            add(pluginDir.resolve("skills"))
                        }
                    }
                }
            }
        }

        for (root in roots) {
            if (!Files.isDirectory(root)) continue
            scanSkillsRoot(root, result)
        }
        return result.values.sortedBy { it.name.lowercase() }
    }

    fun mergeCommands(
        acpCommands: List<AcpModels.AvailableCommand>,
        localSkills: List<AcpModels.AvailableCommand>
    ): List<AcpModels.AvailableCommand> {
        val merged = linkedMapOf<String, AcpModels.AvailableCommand>()
        for (cmd in acpCommands) {
            val key = cmd.name.trim().lowercase()
            if (key.isNotEmpty()) {
                merged[key] = cmd
            }
        }
        for (skill in localSkills) {
            val key = skill.name.trim().lowercase()
            if (key.isNotEmpty() && !merged.containsKey(key)) {
                merged[key] = skill
            }
        }
        return merged.values.toList()
    }

    private fun scanSkillsRoot(
        skillsRoot: Path,
        out: MutableMap<String, AcpModels.AvailableCommand>
    ) {
        // 支持直接在 skillsRoot 下放 SKILL.md，以及 skillsRoot/<skill-dir>/SKILL.md（最多 2 层）
        val directSkill = skillsRoot.resolve("SKILL.md")
        if (Files.isRegularFile(directSkill)) {
            parseSkillFile(directSkill, skillsRoot.fileName?.toString() ?: "skill")?.let {
                out.putIfAbsent(it.name.lowercase(), it)
            }
        }
        runCatching {
            Files.list(skillsRoot).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { child ->
                    val skillFile = child.resolve("SKILL.md")
                    if (Files.isRegularFile(skillFile)) {
                        parseSkillFile(skillFile, child.fileName?.toString() ?: "skill")?.let {
                            out.putIfAbsent(it.name.lowercase(), it)
                        }
                    }
                }
            }
        }
    }

    internal fun parseSkillFile(skillFile: Path, fallbackName: String): AcpModels.AvailableCommand? {
        return runCatching {
            val text = Files.readString(skillFile, StandardCharsets.UTF_8)
            val header = extractFrontmatter(text) ?: text.take(1000)
            val name = nameRegex.find(header)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallbackName
            val desc = descRegex.find(header)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("|") && !it.startsWith(">") }
                ?: "已安装的本地 Skill ($fallbackName)"
            AcpModels.AvailableCommand(name = name, description = desc, source = "local-skill")
        }.getOrNull()
    }

    private fun extractFrontmatter(content: String): String? {
        val trimmed = content.trimStart('\uFEFF')
        if (!trimmed.startsWith("---")) return null
        val endIdx = trimmed.indexOf("\n---", startIndex = 3)
        if (endIdx < 0) return null
        return trimmed.substring(3, endIdx)
    }
}
