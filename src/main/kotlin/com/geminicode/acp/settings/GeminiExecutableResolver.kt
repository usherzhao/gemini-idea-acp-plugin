package com.geminicode.acp.settings

import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 自动探测官方 Antigravity CLI (agy) 与兼容版 Gemini CLI 可执行文件，
 * 并以只读方式读取本地账号元数据用于界面状态展示。
 */
object GeminiExecutableResolver {

    enum class BackendKind {
        ANTIGRAVITY_AGY,
        GEMINI_CLI,
        NPX_GEMINI_CLI
    }

    data class ResolutionResult(
        val found: Boolean,
        val executablePath: String?,
        val needsCmdWrapper: Boolean,
        val checkedLocations: List<String>,
        val viaNpxFallback: Boolean = false,
        val directNodePath: String? = null,
        val directScriptPath: String? = null,
        val backendKind: BackendKind = BackendKind.GEMINI_CLI
    )

    data class CachedAccountInfo(
        val hasOAuthCreds: Boolean,
        val activeEmail: String?,
        val selectedAuthType: String?
    )

    fun resolveExecutable(
        configuredPath: String,
        allowNpxFallback: Boolean = false
    ): ResolutionResult {
        val checked = mutableListOf<String>()
        val trimmed = configuredPath.trim()
        if (trimmed.isNotEmpty()) {
            checked.add(trimmed)
            val file = File(trimmed)
            return if (file.isFile && (SystemInfo.isWindows || file.canExecute())) {
                val kind = if (isAgyBinary(file)) BackendKind.ANTIGRAVITY_AGY else BackendKind.GEMINI_CLI
                buildFoundResult(file, checked, viaNpxFallback = false, backendKind = kind)
            } else {
                ResolutionResult(
                    found = false,
                    executablePath = null,
                    needsCmdWrapper = false,
                    checkedLocations = checked
                )
            }
        }

        // 1. 优先探测官方 Antigravity CLI (agy acp)，完整支持个人账号 oauth-personal
        val agyCandidates = collectAgyCandidates()
        for (candidate in agyCandidates) {
            checked.add(candidate.toString())
            val file = candidate.toFile()
            if (file.isFile && (SystemInfo.isWindows || file.canExecute())) {
                return buildFoundResult(file, checked.distinct(), viaNpxFallback = false, backendKind = BackendKind.ANTIGRAVITY_AGY)
            }
        }

        // 2. 回退探测本地 gemini CLI（适用于 API Key / 企业账号场景）
        val geminiCandidates = collectPlatformCandidates(binaryBaseName = "gemini")
        for (candidate in geminiCandidates) {
            checked.add(candidate.toString())
            val file = candidate.toFile()
            if (file.isFile && (SystemInfo.isWindows || file.canExecute())) {
                return buildFoundResult(file, checked.distinct(), viaNpxFallback = false, backendKind = BackendKind.GEMINI_CLI)
            }
        }

        // 3. 可选回退 npx -y @google/gemini-cli
        if (allowNpxFallback) {
            val npxCandidates = collectPlatformCandidates(binaryBaseName = "npx")
            for (candidate in npxCandidates) {
                checked.add(candidate.toString())
                val file = candidate.toFile()
                if (file.isFile && (SystemInfo.isWindows || file.canExecute())) {
                    return ResolutionResult(
                        found = true,
                        executablePath = file.absolutePath,
                        needsCmdWrapper = isWindowsBatchFile(file),
                        checkedLocations = checked.distinct(),
                        viaNpxFallback = true,
                        backendKind = BackendKind.NPX_GEMINI_CLI
                    )
                }
            }
        }

        return ResolutionResult(
            found = false,
            executablePath = null,
            needsCmdWrapper = false,
            checkedLocations = checked.distinct()
        )
    }

    fun resolveNodeExecutable(): String? {
        val candidates = collectPlatformCandidates(binaryBaseName = "node")
        for (candidate in candidates) {
            val file = candidate.toFile()
            if (file.isFile && (SystemInfo.isWindows || file.canExecute())) {
                return file.absolutePath
            }
        }
        return null
    }

    /**
     * 仅读取非敏感的本地状态元数据（不读取或传输令牌密钥）。
     */
    fun readCachedAccountInfo(userHome: Path = Paths.get(System.getProperty("user.home"))): CachedAccountInfo {
        val geminiDir = userHome.resolve(".gemini")
        val agyCliDir = geminiDir.resolve("antigravity-cli")
        val hasOAuthCreds = Files.isRegularFile(geminiDir.resolve("oauth_creds.json")) ||
            Files.isDirectory(agyCliDir)

        val activeEmail = runCatching {
            val accountsFile = geminiDir.resolve("google_accounts.json")
            if (!Files.isRegularFile(accountsFile)) return@runCatching null
            val root = JsonParser.parseString(Files.readString(accountsFile))
            if (root.isJsonObject) {
                root.asJsonObject.get("active")?.takeIf { it.isJsonPrimitive }?.asString
            } else {
                null
            }
        }.getOrNull()

        val selectedType = runCatching {
            val settingsFile = geminiDir.resolve("settings.json")
            if (!Files.isRegularFile(settingsFile)) return@runCatching null
            val root = JsonParser.parseString(Files.readString(settingsFile)).asJsonObject
            root.getAsJsonObject("security")
                ?.getAsJsonObject("auth")
                ?.get("selectedType")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?: root.get("selectedAuthType")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()

        return CachedAccountInfo(
            hasOAuthCreds = hasOAuthCreds,
            activeEmail = activeEmail,
            selectedAuthType = selectedType
        )
    }

    private fun isAgyBinary(file: File): Boolean {
        val lower = file.name.lowercase()
        return lower == "agy" || lower == "agy.exe" || lower == "agy.cmd" || lower == "agy.bat"
    }

    private fun buildFoundResult(
        file: File,
        checked: List<String>,
        viaNpxFallback: Boolean,
        backendKind: BackendKind
    ): ResolutionResult {
        val needsCmd = isWindowsBatchFile(file)
        var nodePath: String? = null
        var scriptPath: String? = null

        if (needsCmd && backendKind == BackendKind.GEMINI_CLI) {
            val parent = file.parentFile
            if (parent != null) {
                val jsEntry = File(parent, "node_modules/@google/gemini-cli/dist/index.js")
                if (jsEntry.isFile) {
                    val foundNode = resolveNodeExecutable()
                    if (foundNode != null) {
                        nodePath = foundNode
                        scriptPath = jsEntry.absolutePath
                    }
                }
            }
        }

        return ResolutionResult(
            found = true,
            executablePath = file.absolutePath,
            needsCmdWrapper = needsCmd,
            checkedLocations = checked,
            viaNpxFallback = viaNpxFallback,
            directNodePath = nodePath,
            directScriptPath = scriptPath,
            backendKind = backendKind
        )
    }

    private fun isWindowsBatchFile(file: File): Boolean {
        if (!SystemInfo.isWindows) return false
        val lower = file.name.lowercase()
        return lower.endsWith(".cmd") || lower.endsWith(".bat")
    }

    private fun collectAgyCandidates(): List<Path> {
        val result = mutableListOf<Path>()
        val pathEnv = System.getenv("PATH").orEmpty()
        val names = if (SystemInfo.isWindows) listOf("agy.exe", "agy.cmd", "agy.bat") else listOf("agy")

        for (dir in pathEnv.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val dirPath = runCatching { Paths.get(dir.trim()) }.getOrNull() ?: continue
            for (name in names) {
                result.add(dirPath.resolve(name))
            }
        }

        val userHome = System.getProperty("user.home").orEmpty()
        if (SystemInfo.isWindows) {
            val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
            if (localAppData.isNotBlank()) {
                result.add(Paths.get(localAppData, "agy", "bin", "agy.exe"))
                result.add(Paths.get(localAppData, "Programs", "Antigravity", "bin", "agy.exe"))
            }
            if (userHome.isNotBlank()) {
                result.add(Paths.get(userHome, ".local", "bin", "agy.exe"))
            }
        } else {
            if (userHome.isNotBlank()) {
                result.add(Paths.get(userHome, ".local", "bin", "agy"))
                result.add(Paths.get(userHome, ".agy", "bin", "agy"))
            }
            result.add(Paths.get("/opt/homebrew/bin/agy"))
            result.add(Paths.get("/usr/local/bin/agy"))
        }
        return result
    }

    private fun collectPlatformCandidates(binaryBaseName: String): List<Path> {
        val result = mutableListOf<Path>()
        val pathEnv = System.getenv("PATH").orEmpty()
        val binaryNames = if (SystemInfo.isWindows) {
            if (binaryBaseName == "node") {
                listOf("node.exe")
            } else {
                listOf("$binaryBaseName.cmd", "$binaryBaseName.exe", "$binaryBaseName.bat")
            }
        } else {
            listOf(binaryBaseName)
        }

        for (dir in pathEnv.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val dirPath = runCatching { Paths.get(dir.trim()) }.getOrNull() ?: continue
            for (name in binaryNames) {
                result.add(dirPath.resolve(name))
            }
        }

        val userHome = System.getProperty("user.home").orEmpty()
        if (SystemInfo.isWindows) {
            val appData = System.getenv("APPDATA").orEmpty()
            val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
            val nvmSymlink = System.getenv("NVM_SYMLINK").orEmpty()
            val programFiles = System.getenv("ProgramFiles").orEmpty()
            val primaryWinName = binaryNames.first()

            if (appData.isNotBlank()) result.add(Paths.get(appData, "npm", primaryWinName))
            if (localAppData.isNotBlank()) {
                result.add(Paths.get(localAppData, "pnpm", primaryWinName))
                result.add(Paths.get(localAppData, "Yarn", "bin", primaryWinName))
                result.add(Paths.get(localAppData, "Volta", "bin", primaryWinName))
            }
            if (nvmSymlink.isNotBlank()) result.add(Paths.get(nvmSymlink, primaryWinName))
            if (programFiles.isNotBlank()) result.add(Paths.get(programFiles, "nodejs", primaryWinName))
            if (userHome.isNotBlank()) result.add(Paths.get(userHome, "scoop", "shims", primaryWinName))
        } else {
            result.add(Paths.get("/opt/homebrew/bin/$binaryBaseName"))
            result.add(Paths.get("/usr/local/bin/$binaryBaseName"))
            if (userHome.isNotBlank()) {
                result.add(Paths.get(userHome, ".npm-global", "bin", binaryBaseName))
                result.add(Paths.get(userHome, ".local", "share", "pnpm", binaryBaseName))
                result.add(Paths.get(userHome, ".volta", "bin", binaryBaseName))
                val nvmVersions = Paths.get(userHome, ".nvm", "versions", "node").toFile()
                if (nvmVersions.isDirectory) {
                    nvmVersions.listFiles()
                        ?.sortedByDescending { it.name }
                        ?.forEach { versionDir ->
                            result.add(versionDir.toPath().resolve("bin").resolve(binaryBaseName))
                        }
                }
            }
        }

        return result
    }
}
