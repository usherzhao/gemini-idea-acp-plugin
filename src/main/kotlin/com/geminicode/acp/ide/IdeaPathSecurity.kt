package com.geminicode.acp.ide

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Pure filesystem path normalization, workspace containment check, and line-range slicing helpers.
 */
object IdeaPathSecurity {

    fun resolveProjectPath(projectBasePath: String?, requestedPath: String): Path {
        val raw = Paths.get(requestedPath)
        val resolved = if (raw.isAbsolute || projectBasePath.isNullOrBlank()) {
            raw
        } else {
            Paths.get(projectBasePath).resolve(raw)
        }
        return resolved.toAbsolutePath().normalize()
    }

    fun isWithinWorkspace(projectBasePath: String?, candidate: Path): Boolean {
        if (projectBasePath.isNullOrBlank()) return false
        val root = Paths.get(projectBasePath).toAbsolutePath().normalize()
        return candidate.toAbsolutePath().normalize().startsWith(root)
    }

    /**
     * Slices text by 1-based [startLine] and optional max [limit] lines per ACP fs/read_text_file spec.
     */
    fun sliceLines(content: String, startLine: Int?, limit: Int?): String {
        if (startLine == null && limit == null) return content
        val lines = content.split("\n")
        val fromIndex = ((startLine ?: 1) - 1).coerceAtLeast(0)
        if (fromIndex >= lines.size) return ""
        val toIndex = if (limit != null && limit > 0) {
            (fromIndex + limit).coerceAtMost(lines.size)
        } else {
            lines.size
        }
        return lines.subList(fromIndex, toIndex).joinToString("\n")
    }
}
