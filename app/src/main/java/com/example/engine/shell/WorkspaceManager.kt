package com.example.engine.shell

import android.content.Context
import java.io.File

data class WorkspaceFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

data class DiffLine(
    val type: DiffLineType,
    val text: String
)

enum class DiffLineType { CONTEXT, ADDED, REMOVED, HEADER }

data class FileEditResult(
    val relativePath: String,
    val isNewFile: Boolean,
    val addedCount: Int,
    val removedCount: Int,
    val diffLines: List<DiffLine>
)

data class GrepMatch(
    val relativePath: String,
    val lineNumber: Int,
    val lineText: String
)

data class WorkspaceInfo(
    val name: String,
    val fileCount: Int,
    val isActive: Boolean
)

class WorkspaceManager(context: Context) {

    private val defaultRootDir: File = File(context.filesDir, "workspace").apply {
        if (!exists()) mkdirs()
    }

    private val workspacesContainerDir: File = File(context.filesDir, "workspaces").apply {
        if (!exists()) mkdirs()
    }

    var activeWorkspaceName: String = "default"
        private set

    var rootDir: File = defaultRootDir
        private set

    init {
        seedStarterFilesIfEmpty()
    }

    fun sanitizeWorkspaceName(name: String): String {
        val cleaned = name.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
        return cleaned.ifBlank { "project" }
    }

    private fun dirForWorkspace(name: String): File {
        return if (name == "default") defaultRootDir else File(workspacesContainerDir, name)
    }

    fun listWorkspaces(): List<WorkspaceInfo> {
        val result = mutableListOf<WorkspaceInfo>()
        val defaultCount = defaultRootDir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
        result += WorkspaceInfo(
            name = "default",
            fileCount = defaultCount,
            isActive = activeWorkspaceName == "default"
        )
        workspacesContainerDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { dir ->
                val count = dir.listFiles()?.count { !it.name.startsWith(".") } ?: 0
                result += WorkspaceInfo(
                    name = dir.name,
                    fileCount = count,
                    isActive = activeWorkspaceName == dir.name
                )
            }
        return result
    }

    fun createWorkspace(name: String, withStarterMain: Boolean = true): String {
        val clean = sanitizeWorkspaceName(name)
        if (clean == "default") {
            switchWorkspace("default")
            return "default"
        }
        val dir = dirForWorkspace(clean)
        if (!dir.exists()) dir.mkdirs()
        activeWorkspaceName = clean
        rootDir = dir
        ensureDefaultSkills()
        if (withStarterMain && dir.listFiles()?.none { !it.name.startsWith(".") } == true) {
            File(dir, "main.py").writeText(
                """
                # Workspace: $clean
                print("Workspace '$clean' ready!")
                """.trimIndent() + "\n"
            )
        }
        val claudeMd = File(dir, "CLAUDE.md")
        if (!claudeMd.exists()) {
            claudeMd.writeText("# CLAUDE.md\n\nDedicated workspace: `$clean`\n")
        }
        return clean
    }

    fun switchWorkspace(name: String): Boolean {
        val clean = if (name.trim().equals("default", ignoreCase = true)) {
            "default"
        } else {
            sanitizeWorkspaceName(name)
        }
        val dir = dirForWorkspace(clean)
        if (!dir.exists() || !dir.isDirectory) return false
        activeWorkspaceName = clean
        rootDir = dir
        ensureDefaultSkills()
        return true
    }

    fun deleteWorkspace(name: String): Boolean {
        val clean = sanitizeWorkspaceName(name)
        if (clean == "default") return false
        val dir = dirForWorkspace(clean)
        if (!dir.exists()) return false
        val deleted = dir.deleteRecursively()
        if (deleted && activeWorkspaceName == clean) {
            switchWorkspace("default")
        }
        return deleted
    }

    private fun seedStarterFilesIfEmpty() {
        val existing = rootDir.listFiles()
        if (existing != null && existing.isNotEmpty()) return

        File(rootDir, "hello.py").writeText(
            """
            # NimTerm Starter Python Script
            import math
            import datetime

            def greet(name="Developer"):
                now = datetime.datetime.now().strftime("%H:%M:%S")
                return f"Hello {name}! NimTerm Python engine ready at {now}"

            print(greet("Android"))
            squares = [x ** 2 for x in range(1, 8)]
            print("Squares:", squares)
            print("Pi sqrt:", round(math.sqrt(math.pi), 5))
            """.trimIndent() + "\n"
        )

        File(rootDir, "fibonacci.py").writeText(
            """
            def fib(n):
                a, b = 0, 1
                seq = []
                for _ in range(n):
                    seq.append(a)
                    a, b = b, a + b
                return seq

            if __name__ == "__main__":
                result = fib(12)
                print("Fibonacci(12):", result)
                print("Sum:", sum(result))
            """.trimIndent() + "\n"
        )

        File(rootDir, "nvidia_nim_demo.py").writeText(
            """
            # Call the active NVIDIA NIM model straight from Python!
            import nvidia

            print("Active model:", nvidia.model())
            # Uncomment below once your NVIDIA API key is set in Settings:
            # reply = nvidia.chat("Write a one-line haiku about terminal coding.")
            # print("NIM reply:", reply)
            """.trimIndent() + "\n"
        )

        ensureDefaultSkills()
    }

    fun ensureDefaultSkills() {
        val skillsDir = File(rootDir, ".claude/skills").apply { mkdirs() }
        val defaults = mapOf(
            "python-dev.md" to """
                # Skill: python-dev
                Write clean, modular Python 3.12 code compatible with the NimTerm embedded Python runtime.
                Always run `run_python` after creating or editing `.py` files to verify zero syntax or runtime errors.
            """.trimIndent() + "\n",
            "bug-hunter.md" to """
                # Skill: bug-hunter
                Systematically inspect stack traces, read target files, search usages with `grep_files`, identify root causes, apply minimal surgical `edit_file` fixes, and re-run verification.
            """.trimIndent() + "\n",
            "nim-api.md" to """
                # Skill: nim-api
                Build scripts using the built-in `nvidia` or `litellm` Python modules (`nvidia.chat(prompt)`, `litellm.completion(...)`) to orchestrate NVIDIA NIM models from Python.
            """.trimIndent() + "\n",
            "refactor.md" to """
                # Skill: refactor
                Improve readability, extract clean functions/classes, eliminate dead code, and verify existing behavior remains intact via `run_python`.
            """.trimIndent() + "\n"
        )
        defaults.forEach { (name, content) ->
            val f = File(skillsDir, name)
            if (!f.exists()) f.writeText(content)
        }
    }

    fun listSkills(): List<String> {
        ensureDefaultSkills()
        val skillsDir = File(rootDir, ".claude/skills")
        return skillsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.map { it.name.removeSuffix(".md") }
            ?.sorted()
            ?: emptyList()
    }

    fun readSkill(name: String): String? {
        ensureDefaultSkills()
        val clean = name.trim().removeSuffix(".md")
        val f = File(rootDir, ".claude/skills/$clean.md")
        return if (f.exists() && f.isFile) f.readText() else null
    }

    fun createSkill(name: String, instructions: String): String {
        ensureDefaultSkills()
        val clean = name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").removeSuffix(".md")
        val f = File(rootDir, ".claude/skills/$clean.md")
        f.writeText("# Skill: $clean\n\n$instructions\n")
        return clean
    }

    fun readClaudeMd(): String? {
        val f = File(rootDir, "CLAUDE.md")
        return if (f.exists() && f.isFile) f.readText() else null
    }

    fun writeClaudeMd(content: String) {
        File(rootDir, "CLAUDE.md").writeText(content)
    }

    fun resolveSafe(path: String, cwd: File = rootDir): File {
        val trimmed = path.trim()
        val candidate = when {
            trimmed.isEmpty() || trimmed == "." -> cwd
            trimmed == "~" -> rootDir
            trimmed.startsWith("~/") -> File(rootDir, trimmed.removePrefix("~/"))
            trimmed.startsWith("/") -> File(rootDir, trimmed.removePrefix("/"))
            else -> File(cwd, trimmed)
        }
        val canonicalRoot = rootDir.canonicalFile
        val canonicalTarget = candidate.canonicalFile
        if (canonicalTarget.path != canonicalRoot.path &&
            !canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            throw SecurityException("Access denied outside workspace: $path")
        }
        return canonicalTarget
    }

    fun relativePath(file: File): String {
        val rootPath = rootDir.canonicalPath
        val filePath = file.canonicalPath
        return if (filePath == rootPath) {
            "."
        } else {
            filePath.removePrefix(rootPath + File.separator).replace(File.separatorChar, '/')
        }
    }

    fun displayCwd(cwd: File): String {
        val base = if (activeWorkspaceName == "default") "~/workspace" else "~/ws/$activeWorkspaceName"
        val rel = relativePath(cwd)
        return if (rel == ".") base else "$base/$rel"
    }

    fun listFiles(path: String = ".", cwd: File = rootDir, recursive: Boolean = false): List<WorkspaceFileItem> {
        val target = resolveSafe(path, cwd)
        if (!target.exists()) return emptyList()
        if (target.isFile) {
            return listOf(
                WorkspaceFileItem(
                    name = target.name,
                    relativePath = relativePath(target),
                    isDirectory = false,
                    sizeBytes = target.length(),
                    lastModified = target.lastModified()
                )
            )
        }

        val items = mutableListOf<WorkspaceFileItem>()
        if (recursive) {
            target.walkTopDown()
                .filter { it.canonicalPath != target.canonicalPath }
                .forEach { f ->
                    items += WorkspaceFileItem(
                        name = f.name,
                        relativePath = relativePath(f),
                        isDirectory = f.isDirectory,
                        sizeBytes = if (f.isFile) f.length() else 0L,
                        lastModified = f.lastModified()
                    )
                }
        } else {
            target.listFiles()?.forEach { f ->
                items += WorkspaceFileItem(
                    name = f.name,
                    relativePath = relativePath(f),
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified()
                )
            }
        }
        return items.sortedWith(compareBy<WorkspaceFileItem> { !it.isDirectory }.thenBy { it.relativePath.lowercase() })
    }

    fun readFile(path: String, cwd: File = rootDir): String {
        val file = resolveSafe(path, cwd)
        if (!file.exists()) throw IllegalArgumentException("File not found: ${relativePath(file)}")
        if (file.isDirectory) throw IllegalArgumentException("Path is a directory: ${relativePath(file)}")
        return file.readText()
    }

    fun writeFile(path: String, content: String, cwd: File = rootDir): FileEditResult {
        val file = resolveSafe(path, cwd)
        val existed = file.exists() && file.isFile
        val oldLines = if (existed) file.readLines() else emptyList()
        file.parentFile?.mkdirs()
        file.writeText(content)
        val newLines = content.lines()
        return buildDiff(relativePath(file), !existed, oldLines, newLines)
    }

    fun editFile(path: String, oldText: String, newText: String, cwd: File = rootDir): FileEditResult {
        val file = resolveSafe(path, cwd)
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("File not found: ${relativePath(file)}")
        }
        val current = file.readText()
        if (!current.contains(oldText)) {
            throw IllegalArgumentException("Target snippet not found in ${relativePath(file)}")
        }
        val updated = current.replaceFirst(oldText, newText)
        val oldLines = current.lines()
        val newLines = updated.lines()
        file.writeText(updated)
        return buildDiff(relativePath(file), false, oldLines, newLines)
    }

    fun deletePath(path: String, cwd: File = rootDir, recursive: Boolean = true): Boolean {
        val target = resolveSafe(path, cwd)
        if (target.canonicalPath == rootDir.canonicalPath) {
            throw SecurityException("Cannot delete workspace root")
        }
        if (!target.exists()) return false
        return if (recursive) target.deleteRecursively() else target.delete()
    }

    fun mkdir(path: String, cwd: File = rootDir): Boolean {
        val dir = resolveSafe(path, cwd)
        return if (dir.exists()) dir.isDirectory else dir.mkdirs()
    }

    fun copyPath(src: String, dst: String, cwd: File = rootDir) {
        val srcFile = resolveSafe(src, cwd)
        if (!srcFile.exists()) throw IllegalArgumentException("Source not found: $src")
        val rawDst = resolveSafe(dst, cwd)
        val finalDst = if (rawDst.exists() && rawDst.isDirectory) File(rawDst, srcFile.name) else rawDst
        srcFile.copyRecursively(finalDst, overwrite = true)
    }

    fun movePath(src: String, dst: String, cwd: File = rootDir) {
        val srcFile = resolveSafe(src, cwd)
        if (!srcFile.exists()) throw IllegalArgumentException("Source not found: $src")
        val rawDst = resolveSafe(dst, cwd)
        val finalDst = if (rawDst.exists() && rawDst.isDirectory) File(rawDst, srcFile.name) else rawDst
        if (!srcFile.renameTo(finalDst)) {
            srcFile.copyRecursively(finalDst, overwrite = true)
            srcFile.deleteRecursively()
        }
    }

    fun grep(pattern: String, path: String = ".", cwd: File = rootDir): List<GrepMatch> {
        val target = resolveSafe(path, cwd)
        if (!target.exists()) return emptyList()
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
            .getOrElse { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
        val matches = mutableListOf<GrepMatch>()
        val files = if (target.isFile) listOf(target) else target.walkTopDown().filter { it.isFile }.toList()
        for (file in files) {
            if (file.length() > 1_000_000L) continue
            val rel = relativePath(file)
            file.readLines().forEachIndexed { idx, line ->
                if (regex.containsMatchIn(line)) {
                    matches += GrepMatch(rel, idx + 1, line)
                    if (matches.size >= 150) return matches
                }
            }
        }
        return matches
    }

    private fun buildDiff(
        relPath: String,
        isNew: Boolean,
        oldLines: List<String>,
        newLines: List<String>
    ): FileEditResult {
        val diff = mutableListOf<DiffLine>()
        diff += DiffLine(DiffLineType.HEADER, if (isNew) "+++ $relPath (new file)" else "--- a/$relPath  +++ b/$relPath")
        if (isNew) {
            val preview = newLines.take(40)
            preview.forEach { diff += DiffLine(DiffLineType.ADDED, "+ $it") }
            if (newLines.size > 40) {
                diff += DiffLine(DiffLineType.CONTEXT, "... (+${newLines.size - 40} more lines)")
            }
            return FileEditResult(relPath, true, newLines.size, 0, diff)
        }

        // Compute concise line-level diff via LCS for small/medium files
        val maxLen = maxOf(oldLines.size, newLines.size)
        if (oldLines.size * newLines.size > 250_000) {
            // Fast fallback summary for very large files
            val removed = (oldLines - newLines.toSet()).size
            val added = (newLines - oldLines.toSet()).size
            diff += DiffLine(DiffLineType.CONTEXT, "Updated $relPath (${newLines.size} lines)")
            return FileEditResult(relPath, false, added, removed, diff)
        }

        val m = oldLines.size
        val n = newLines.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i][j] = if (oldLines[i] == newLines[j]) {
                    1 + dp[i + 1][j + 1]
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        val rawOps = mutableListOf<Pair<DiffLineType, String>>()
        while (i < m && j < n) {
            when {
                oldLines[i] == newLines[j] -> {
                    rawOps += DiffLineType.CONTEXT to "  ${oldLines[i]}"
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    rawOps += DiffLineType.REMOVED to "- ${oldLines[i]}"
                    removed++; i++
                }
                else -> {
                    rawOps += DiffLineType.ADDED to "+ ${newLines[j]}"
                    added++; j++
                }
            }
        }
        while (i < m) {
            rawOps += DiffLineType.REMOVED to "- ${oldLines[i]}"
            removed++; i++
        }
        while (j < n) {
            rawOps += DiffLineType.ADDED to "+ ${newLines[j]}"
            added++; j++
        }

        // Keep changed lines plus 1 line of context around changes
        val showIndices = BooleanArray(rawOps.size)
        for (idx in rawOps.indices) {
            if (rawOps[idx].first != DiffLineType.CONTEXT) {
                for (k in (idx - 1).coerceAtLeast(0)..(idx + 1).coerceAtMost(rawOps.lastIndex)) {
                    showIndices[k] = true
                }
            }
        }
        var skipping = false
        for (idx in rawOps.indices) {
            if (showIndices[idx]) {
                skipping = false
                if (diff.size < 65) {
                    diff += DiffLine(rawOps[idx].first, rawOps[idx].second)
                }
            } else if (!skipping) {
                skipping = true
                if (diff.size < 65 && maxLen > 3) {
                    diff += DiffLine(DiffLineType.CONTEXT, "  ...")
                }
            }
        }
        return FileEditResult(relPath, false, added, removed, diff)
    }
}
