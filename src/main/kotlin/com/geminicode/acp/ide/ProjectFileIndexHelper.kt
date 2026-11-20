package com.geminicode.acp.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import java.nio.file.Paths

/**
 * 为输入框 `@` 文件引用补全提供高速模糊搜索（支持前缀、子串、驼峰首字母与跨段模糊子序列匹配），
 * 并始终优先保留当前编辑器已打开的文件。
 */
object ProjectFileIndexHelper {

    data class FileCandidate(
        val fileName: String,
        val relativePath: String,
        val absolutePath: String,
        val openInEditor: Boolean
    )

    private val excludedDirs = setOf(
        ".git", ".gradle", ".idea", ".intellijPlatform", ".kotlin",
        "build", "target", "out", "node_modules", "__pycache__", "dist"
    )

    fun searchProjectFiles(
        project: Project,
        query: String,
        limit: Int = 30,
        isCancelled: () -> Boolean = { false }
    ): List<FileCandidate> {
        val basePath = project.basePath ?: return emptyList()
        val normalizedBase = runCatching { Paths.get(basePath).toAbsolutePath().normalize() }.getOrNull()
            ?: return emptyList()
        val basePrefix = normalizedBase.toString().replace('\\', '/').trimEnd('/') + "/"
        val q = query.trim().replace('\\', '/')

        return ReadAction.compute<List<FileCandidate>, RuntimeException> {
            val seenPaths = HashSet<String>()
            val matches = mutableListOf<Pair<Int, FileCandidate>>()

            // 1. 始终先扫描当前打开的标签页文件，确保无论项目多大都不会漏掉正在编辑的文件
            val openFiles = FileEditorManager.getInstance(project).openFiles
            for (vFile in openFiles) {
                if (isCancelled()) return@compute emptyList()
                if (vFile.isDirectory || !vFile.isValid) continue
                val abs = vFile.path.replace('\\', '/')
                val absKey = abs.lowercase()
                if (!seenPaths.add(absKey)) continue
                val rel = computeRelativeFast(basePrefix, normalizedBase, abs, vFile.name)
                val score = matchScore(vFile.name, rel, q, isOpen = true)
                if (score >= 0) {
                    matches.add(score to FileCandidate(vFile.name, rel, abs, openInEditor = true))
                }
            }

            // 2. 遍历整个项目内容索引做模糊匹配（空查询限制最多收集 600 条以保瞬时响应，非空查询全量扫描且支持快速取消）
            var scannedFiles = 0
            val maxScan = if (q.isEmpty()) 600 else 30_000
            val fileIndex = ProjectFileIndex.getInstance(project)
            fileIndex.iterateContent { vFile ->
                if (isCancelled()) return@iterateContent false
                if (vFile.isDirectory) {
                    return@iterateContent vFile.name !in excludedDirs
                }
                scannedFiles++
                if (scannedFiles > maxScan) return@iterateContent false

                val abs = vFile.path.replace('\\', '/')
                val absKey = abs.lowercase()
                if (!seenPaths.add(absKey)) return@iterateContent true

                val rel = computeRelativeFast(basePrefix, normalizedBase, abs, vFile.name)
                val score = matchScore(vFile.name, rel, q, isOpen = false)
                if (score >= 0) {
                    matches.add(score to FileCandidate(vFile.name, rel, abs, openInEditor = false))
                }
                true
            }

            if (isCancelled()) return@compute emptyList()

            matches.sortedWith(
                compareByDescending<Pair<Int, FileCandidate>> { it.first }
                    .thenBy { it.second.relativePath.length }
                    .thenBy { it.second.relativePath }
            )
                .take(limit)
                .map { it.second }
        }
    }

    /**
     * 综合模糊评分：
     * - 精确 / 前缀 / 连续子串命中最高；
     * - 支持驼峰（如 `osvc` -> `OrderService.java`）与分隔符词首（如 `paf` -> `portal-affair-create`）；
     * - 支持跨字符模糊子序列（如 `ordctl` -> `OrderController.java`、`srv/ord` -> `service/OrderService.java`）。
     */
    internal fun matchScore(fileName: String, relPath: String, query: String, isOpen: Boolean): Int {
        val openBonus = if (isOpen) 10_000 else 0
        val q = query.trim().replace('\\', '/').lowercase()
        if (q.isEmpty()) {
            return openBonus + 100
        }

        val nameLower = fileName.lowercase()
        val pathLower = relPath.lowercase()

        if (nameLower == q) return openBonus + 5_000
        if (nameLower.startsWith(q)) return openBonus + 4_000 - (fileName.length - q.length).coerceAtMost(200)
        val nameSubIdx = nameLower.indexOf(q)
        if (nameSubIdx >= 0) return openBonus + 3_000 - nameSubIdx * 4
        val pathSubIdx = pathLower.indexOf(q)
        if (pathSubIdx >= 0) return openBonus + 2_200 - pathSubIdx * 2

        val nameFuzzy = fuzzySubsequenceScore(fileName, q)
        if (nameFuzzy >= 0) return openBonus + 1_200 + nameFuzzy

        val pathFuzzy = fuzzySubsequenceScore(relPath, q)
        if (pathFuzzy >= 0) return openBonus + 500 + pathFuzzy

        return -1
    }

    /**
     * 类似 fzf / IntelliJ GoToFile 的模糊子序列评分器：
     * 连续命中加高分，词首/驼峰大写/路径分隔符后首字母命中加高分，跨度越大略扣分。
     */
    internal fun fuzzySubsequenceScore(candidate: String, queryLower: String): Int {
        if (queryLower.isEmpty()) return 0
        val lower = candidate.lowercase()
        var qi = 0
        var prevMatchIdx = -2
        var score = 0
        var firstMatchIdx = -1

        for (ci in candidate.indices) {
            if (lower[ci] == queryLower[qi]) {
                if (firstMatchIdx < 0) firstMatchIdx = ci
                score += 10
                if (ci == prevMatchIdx + 1) {
                    score += 18 // 连续字符加分
                }
                if (isWordBoundary(candidate, ci)) {
                    score += 24 // 词首/驼峰/分隔符边界加分
                }
                prevMatchIdx = ci
                qi++
                if (qi == queryLower.length) {
                    val span = prevMatchIdx - firstMatchIdx + 1
                    val compactnessPenalty = (span - queryLower.length).coerceAtLeast(0)
                    return (score - compactnessPenalty).coerceAtLeast(1)
                }
            }
        }
        return -1
    }

    private fun isWordBoundary(text: String, index: Int): Boolean {
        if (index == 0) return true
        val prev = text[index - 1]
        val curr = text[index]
        if (prev == '/' || prev == '\\' || prev == '-' || prev == '_' || prev == '.' || prev == ' ') {
            return true
        }
        return prev.isLowerCase() && curr.isUpperCase()
    }

    private fun computeRelativeFast(
        basePrefix: String,
        normalizedBase: java.nio.file.Path,
        normalizedAbsPath: String,
        fallbackName: String
    ): String {
        if (normalizedAbsPath.startsWith(basePrefix, ignoreCase = true)) {
            return normalizedAbsPath.substring(basePrefix.length)
        }
        return runCatching {
            val p = Paths.get(normalizedAbsPath).toAbsolutePath().normalize()
            if (p.startsWith(normalizedBase)) normalizedBase.relativize(p).toString().replace('\\', '/') else normalizedAbsPath
        }.getOrDefault(fallbackName)
    }
}
