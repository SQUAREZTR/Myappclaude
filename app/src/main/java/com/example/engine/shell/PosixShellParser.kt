package com.example.engine.shell

enum class ChainOperator {
    FIRST,
    AND,
    OR,
    SEQUENCE
}

data class ChainedStatement(
    val operator: ChainOperator,
    val raw: String
)

data class RedirectSpec(
    val commandRaw: String,
    val outputPath: String?,
    val append: Boolean
)

/**
 * Quote-aware POSIX shell lexer/parser supporting:
 * - Command chaining (`&&`, `||`, `;`, newlines)
 * - Pipelines (`|`)
 * - Output redirection (`>`, `>>`)
 * - Environment variable expansion (`$VAR`, `${VAR}`) respecting single vs double quotes
 * - Tokenization with backslash escapes and single/double quoting
 */
object PosixShellParser {

    fun splitCommandChain(input: String): List<ChainedStatement> {
        val result = mutableListOf<ChainedStatement>()
        val cur = StringBuilder()
        var pendingOp = ChainOperator.FIRST
        var inSingle = false
        var inDouble = false
        var i = 0

        fun flush(nextOp: ChainOperator) {
            val trimmed = cur.toString().trim()
            if (trimmed.isNotEmpty()) {
                result += ChainedStatement(pendingOp, trimmed)
                pendingOp = nextOp
            } else if (result.isNotEmpty()) {
                pendingOp = nextOp
            }
            cur.clear()
        }

        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' && !inSingle && i + 1 < input.length -> {
                    cur.append(c).append(input[i + 1])
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    cur.append(c)
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    cur.append(c)
                }
                !inSingle && !inDouble && c == '#' && (cur.isEmpty() || cur.last().isWhitespace()) -> {
                    // Skip shell comment until end of line
                    while (i < input.length && input[i] != '\n') i++
                    continue
                }
                !inSingle && !inDouble && c == '&' && i + 1 < input.length && input[i + 1] == '&' -> {
                    flush(ChainOperator.AND)
                    i += 2
                    continue
                }
                !inSingle && !inDouble && c == '|' && i + 1 < input.length && input[i + 1] == '|' -> {
                    flush(ChainOperator.OR)
                    i += 2
                    continue
                }
                !inSingle && !inDouble && (c == ';' || c == '\n') -> {
                    flush(ChainOperator.SEQUENCE)
                }
                else -> cur.append(c)
            }
            i++
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) {
            result += ChainedStatement(pendingOp, tail)
        }
        return result
    }

    fun splitPipeline(input: String): List<String> {
        val stages = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0

        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' && !inSingle && i + 1 < input.length -> {
                    cur.append(c).append(input[i + 1])
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    cur.append(c)
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    cur.append(c)
                }
                !inSingle && !inDouble && c == '|' -> {
                    val isOr = i + 1 < input.length && input[i + 1] == '|'
                    if (!isOr) {
                        val segment = cur.toString().trim()
                        if (segment.isNotEmpty()) stages += segment
                        cur.clear()
                        i++
                        continue
                    } else {
                        cur.append("||")
                        i += 2
                        continue
                    }
                }
                else -> cur.append(c)
            }
            i++
        }
        val last = cur.toString().trim()
        if (last.isNotEmpty()) stages += last
        return stages
    }

    fun extractRedirection(stage: String): RedirectSpec {
        var inSingle = false
        var inDouble = false
        var i = 0
        var redirIdx = -1
        var isAppend = false

        while (i < stage.length) {
            val c = stage[i]
            when {
                c == '\\' && !inSingle && i + 1 < stage.length -> {
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                !inSingle && !inDouble && c == '>' -> {
                    redirIdx = i
                    isAppend = (i + 1 < stage.length && stage[i + 1] == '>')
                    break
                }
            }
            i++
        }

        if (redirIdx < 0) {
            return RedirectSpec(commandRaw = stage.trim(), outputPath = null, append = false)
        }

        val cmdPart = stage.substring(0, redirIdx).trim()
        val afterOp = stage.substring(redirIdx + if (isAppend) 2 else 1).trim()
        val pathTokens = tokenize(afterOp)
        val outPath = pathTokens.firstOrNull()
        val trailingArgs = if (pathTokens.size > 1) {
            " " + pathTokens.drop(1).joinToString(" ")
        } else {
            ""
        }
        return RedirectSpec(
            commandRaw = (cmdPart + trailingArgs).trim(),
            outputPath = outPath,
            append = isAppend
        )
    }

    fun expandVariables(input: String, resolver: (String) -> String?): String {
        val out = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0

        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' && !inSingle && i + 1 < input.length -> {
                    val next = input[i + 1]
                    if (next == '$') {
                        // Keep \$ escaped as literal $ for tokenize or emit \$? Keep \$ so tokenize turns it into $
                        out.append('\\').append('$')
                    } else {
                        out.append(c).append(next)
                    }
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    out.append(c)
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    out.append(c)
                }
                c == '$' && !inSingle && i + 1 < input.length -> {
                    if (input[i + 1] == '{') {
                        val close = input.indexOf('}', i + 2)
                        if (close > i + 2) {
                            val varName = input.substring(i + 2, close)
                            out.append(resolver(varName).orEmpty())
                            i = close + 1
                            continue
                        }
                    } else if (input[i + 1].isLetter() || input[i + 1] == '_') {
                        var end = i + 1
                        while (end < input.length && (input[end].isLetterOrDigit() || input[end] == '_')) {
                            end++
                        }
                        val varName = input.substring(i + 1, end)
                        out.append(resolver(varName).orEmpty())
                        i = end
                        continue
                    } else if (input[i + 1] == '?') {
                        out.append(resolver("?").orEmpty())
                        i += 2
                        continue
                    }
                    out.append(c)
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var hasTokenContent = false
        var i = 0

        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' && !inSingle && i + 1 < input.length -> {
                    cur.append(input[i + 1])
                    hasTokenContent = true
                    i += 2
                    continue
                }
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    hasTokenContent = true
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                    hasTokenContent = true
                }
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (hasTokenContent) {
                        tokens += cur.toString()
                        cur.clear()
                        hasTokenContent = false
                    }
                }
                else -> {
                    cur.append(c)
                    hasTokenContent = true
                }
            }
            i++
        }
        if (hasTokenContent) tokens += cur.toString()
        return tokens
    }
}
