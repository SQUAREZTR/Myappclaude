package com.example.engine.python

enum class TokType {
    IDENT, INT, FLOAT, STRING, FSTRING,
    PLUS, MINUS, STAR, SLASH, FLOORDIV, PERCENT, POW, AT,
    EQ, NEQ, LT, GT, LE, GE,
    ASSIGN, PLUSEQ, MINUSEQ, STAREQ, SLASHEQ, FLOORDIVEQ, PERCENTEQ, POWEQ,
    LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
    COMMA, COLON, DOT, SEMI, ARROW,
    NEWLINE, INDENT, DEDENT, EOF,
    // Keywords
    KW_IF, KW_ELIF, KW_ELSE, KW_WHILE, KW_FOR, KW_IN, KW_NOT, KW_AND, KW_OR,
    KW_IS, KW_DEF, KW_RETURN, KW_CLASS, KW_PASS, KW_BREAK, KW_CONTINUE,
    KW_IMPORT, KW_FROM, KW_AS, KW_TRY, KW_EXCEPT, KW_FINALLY, KW_RAISE,
    KW_WITH, KW_LAMBDA, KW_TRUE, KW_FALSE, KW_NONE, KW_ASSERT, KW_DEL, KW_GLOBAL
}

data class Token(
    val type: TokType,
    val lexeme: String,
    val line: Int
)

class PythonLexer(private val source: String) {

    private val keywords = mapOf(
        "if" to TokType.KW_IF,
        "elif" to TokType.KW_ELIF,
        "else" to TokType.KW_ELSE,
        "while" to TokType.KW_WHILE,
        "for" to TokType.KW_FOR,
        "in" to TokType.KW_IN,
        "not" to TokType.KW_NOT,
        "and" to TokType.KW_AND,
        "or" to TokType.KW_OR,
        "is" to TokType.KW_IS,
        "def" to TokType.KW_DEF,
        "return" to TokType.KW_RETURN,
        "class" to TokType.KW_CLASS,
        "pass" to TokType.KW_PASS,
        "break" to TokType.KW_BREAK,
        "continue" to TokType.KW_CONTINUE,
        "import" to TokType.KW_IMPORT,
        "from" to TokType.KW_FROM,
        "as" to TokType.KW_AS,
        "try" to TokType.KW_TRY,
        "except" to TokType.KW_EXCEPT,
        "finally" to TokType.KW_FINALLY,
        "raise" to TokType.KW_RAISE,
        "with" to TokType.KW_WITH,
        "lambda" to TokType.KW_LAMBDA,
        "True" to TokType.KW_TRUE,
        "False" to TokType.KW_FALSE,
        "None" to TokType.KW_NONE,
        "assert" to TokType.KW_ASSERT,
        "del" to TokType.KW_DEL,
        "global" to TokType.KW_GLOBAL
    )

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        val indentStack = ArrayDeque<Int>().apply { addLast(0) }
        var pos = 0
        var line = 1
        var atLineStart = true
        var bracketDepth = 0
        val len = source.length

        while (pos < len) {
            val c = source[pos]

            // Handle indentation at the start of logical lines
            if (atLineStart && bracketDepth == 0) {
                var indent = 0
                var scan = pos
                while (scan < len && (source[scan] == ' ' || source[scan] == '\t')) {
                    indent += if (source[scan] == '\t') 4 else 1
                    scan++
                }
                if (scan >= len) {
                    pos = scan
                    break
                }
                val nextChar = source[scan]
                if (nextChar == '\n' || nextChar == '\r') {
                    pos = scan + 1
                    if (nextChar == '\r' && pos < len && source[pos] == '\n') pos++
                    line++
                    continue
                }
                if (nextChar == '#') {
                    pos = scan
                    while (pos < len && source[pos] != '\n') pos++
                    continue
                }
                pos = scan
                atLineStart = false
                val currentIndent = indentStack.last()
                if (indent > currentIndent) {
                    indentStack.addLast(indent)
                    tokens += Token(TokType.INDENT, "", line)
                } else if (indent < currentIndent) {
                    while (indentStack.size > 1 && indentStack.last() > indent) {
                        indentStack.removeLast()
                        tokens += Token(TokType.DEDENT, "", line)
                    }
                    if (indentStack.last() != indent) {
                        throw PythonRuntimeException("IndentationError", "unindent does not match any outer indentation level", line)
                    }
                }
                continue
            }

            // Skip spaces/tabs inside lines
            if (c == ' ' || c == '\t') {
                pos++
                continue
            }

            // Line continuation backslash
            if (c == '\\' && pos + 1 < len && (source[pos + 1] == '\n' || (source[pos + 1] == '\r'))) {
                pos += 2
                if (pos < len && source[pos - 1] == '\r' && source[pos] == '\n') pos++
                line++
                continue
            }

            // Comments
            if (c == '#') {
                while (pos < len && source[pos] != '\n') pos++
                continue
            }

            // Newlines
            if (c == '\n' || c == '\r') {
                pos++
                if (c == '\r' && pos < len && source[pos] == '\n') pos++
                if (bracketDepth == 0 && tokens.isNotEmpty() && tokens.last().type != TokType.NEWLINE && tokens.last().type != TokType.INDENT) {
                    tokens += Token(TokType.NEWLINE, "\n", line)
                }
                line++
                if (bracketDepth == 0) atLineStart = true
                continue
            }

            // Prefixed strings: f"...", r"...", b"..."
            if ((c == 'f' || c == 'F' || c == 'r' || c == 'R') &&
                pos + 1 < len && (source[pos + 1] == '"' || source[pos + 1] == '\'')
            ) {
                val isF = c == 'f' || c == 'F'
                val isRaw = c == 'r' || c == 'R'
                pos++
                val (strVal, nextPos, linesAdded) = readString(pos, line, isRaw)
                pos = nextPos
                line += linesAdded
                tokens += Token(if (isF) TokType.FSTRING else TokType.STRING, strVal, line)
                continue
            }

            // Strings
            if (c == '"' || c == '\'') {
                val (strVal, nextPos, linesAdded) = readString(pos, line, isRaw = false)
                pos = nextPos
                line += linesAdded
                tokens += Token(TokType.STRING, strVal, line)
                continue
            }

            // Numbers
            if (c.isDigit() || (c == '.' && pos + 1 < len && source[pos + 1].isDigit())) {
                val start = pos
                var isFloat = false
                if (c == '0' && pos + 1 < len && (source[pos + 1] == 'x' || source[pos + 1] == 'X' || source[pos + 1] == 'b' || source[pos + 1] == 'B')) {
                    pos += 2
                    while (pos < len && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
                    val rawNum = source.substring(start, pos).replace("_", "")
                    val parsed = if (rawNum.startsWith("0x", true)) {
                        rawNum.substring(2).toLong(16)
                    } else {
                        rawNum.substring(2).toLong(2)
                    }
                    tokens += Token(TokType.INT, parsed.toString(), line)
                    continue
                }
                while (pos < len && (source[pos].isDigit() || source[pos] == '_')) pos++
                if (pos < len && source[pos] == '.' && (pos + 1 >= len || source[pos + 1] != '.')) {
                    isFloat = true
                    pos++
                    while (pos < len && (source[pos].isDigit() || source[pos] == '_')) pos++
                }
                if (pos < len && (source[pos] == 'e' || source[pos] == 'E')) {
                    isFloat = true
                    pos++
                    if (pos < len && (source[pos] == '+' || source[pos] == '-')) pos++
                    while (pos < len && source[pos].isDigit()) pos++
                }
                val raw = source.substring(start, pos).replace("_", "")
                tokens += Token(if (isFloat) TokType.FLOAT else TokType.INT, raw, line)
                continue
            }

            // Identifiers & Keywords
            if (c.isLetter() || c == '_') {
                val start = pos
                while (pos < len && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
                val word = source.substring(start, pos)
                val kw = keywords[word]
                tokens += Token(kw ?: TokType.IDENT, word, line)
                continue
            }

            // Multi-char operators
            val two = if (pos + 1 < len) source.substring(pos, pos + 2) else ""
            val three = if (pos + 2 < len) source.substring(pos, pos + 3) else ""

            when (three) {
                "**=" -> { tokens += Token(TokType.POWEQ, "**=", line); pos += 3; continue }
                "//=" -> { tokens += Token(TokType.FLOORDIVEQ, "//=", line); pos += 3; continue }
            }

            when (two) {
                "**" -> { tokens += Token(TokType.POW, "**", line); pos += 2; continue }
                "//" -> { tokens += Token(TokType.FLOORDIV, "//", line); pos += 2; continue }
                "==" -> { tokens += Token(TokType.EQ, "==", line); pos += 2; continue }
                "!=" -> { tokens += Token(TokType.NEQ, "!=", line); pos += 2; continue }
                "<=" -> { tokens += Token(TokType.LE, "<=", line); pos += 2; continue }
                ">=" -> { tokens += Token(TokType.GE, ">=", line); pos += 2; continue }
                "+=" -> { tokens += Token(TokType.PLUSEQ, "+=", line); pos += 2; continue }
                "-=" -> { tokens += Token(TokType.MINUSEQ, "-=", line); pos += 2; continue }
                "*=" -> { tokens += Token(TokType.STAREQ, "*=", line); pos += 2; continue }
                "/=" -> { tokens += Token(TokType.SLASHEQ, "/=", line); pos += 2; continue }
                "%=" -> { tokens += Token(TokType.PERCENTEQ, "%=", line); pos += 2; continue }
                "->" -> { tokens += Token(TokType.ARROW, "->", line); pos += 2; continue }
            }

            // Single-char tokens
            pos++
            when (c) {
                '+' -> tokens += Token(TokType.PLUS, "+", line)
                '-' -> tokens += Token(TokType.MINUS, "-", line)
                '*' -> tokens += Token(TokType.STAR, "*", line)
                '/' -> tokens += Token(TokType.SLASH, "/", line)
                '%' -> tokens += Token(TokType.PERCENT, "%", line)
                '@' -> tokens += Token(TokType.AT, "@", line)
                '<' -> tokens += Token(TokType.LT, "<", line)
                '>' -> tokens += Token(TokType.GT, ">", line)
                '=' -> tokens += Token(TokType.ASSIGN, "=", line)
                ',' -> tokens += Token(TokType.COMMA, ",", line)
                ':' -> tokens += Token(TokType.COLON, ":", line)
                '.' -> tokens += Token(TokType.DOT, ".", line)
                ';' -> tokens += Token(TokType.SEMI, ";", line)
                '(' -> { bracketDepth++; tokens += Token(TokType.LPAREN, "(", line) }
                ')' -> { if (bracketDepth > 0) bracketDepth--; tokens += Token(TokType.RPAREN, ")", line) }
                '[' -> { bracketDepth++; tokens += Token(TokType.LBRACKET, "[", line) }
                ']' -> { if (bracketDepth > 0) bracketDepth--; tokens += Token(TokType.RBRACKET, "]", line) }
                '{' -> { bracketDepth++; tokens += Token(TokType.LBRACE, "{", line) }
                '}' -> { if (bracketDepth > 0) bracketDepth--; tokens += Token(TokType.RBRACE, "}", line) }
                else -> throw PythonRuntimeException("SyntaxError", "Unexpected character '$c'", line)
            }
        }

        if (tokens.isNotEmpty() && tokens.last().type != TokType.NEWLINE && tokens.last().type != TokType.DEDENT) {
            tokens += Token(TokType.NEWLINE, "\n", line)
        }
        while (indentStack.size > 1) {
            indentStack.removeLast()
            tokens += Token(TokType.DEDENT, "", line)
        }
        tokens += Token(TokType.EOF, "", line)
        return tokens
    }

    private fun readString(startPos: Int, startLine: Int, isRaw: Boolean): Triple<String, Int, Int> {
        val quote = source[startPos]
        val len = source.length
        val isTriple = startPos + 2 < len && source[startPos + 1] == quote && source[startPos + 2] == quote
        var pos = startPos + if (isTriple) 3 else 1
        val sb = StringBuilder()
        var linesAdded = 0

        while (pos < len) {
            val ch = source[pos]
            if (isTriple) {
                if (ch == quote && pos + 2 < len && source[pos + 1] == quote && source[pos + 2] == quote) {
                    return Triple(sb.toString(), pos + 3, linesAdded)
                }
                if (ch == '\n') linesAdded++
            } else {
                if (ch == quote) {
                    return Triple(sb.toString(), pos + 1, linesAdded)
                }
                if (ch == '\n') {
                    throw PythonRuntimeException("SyntaxError", "EOL while scanning string literal", startLine + linesAdded)
                }
            }

            if (!isRaw && ch == '\\' && pos + 1 < len) {
                val esc = source[pos + 1]
                pos += 2
                when (esc) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '0' -> sb.append('\u0000')
                    else -> { sb.append('\\'); sb.append(esc) }
                }
            } else {
                sb.append(ch)
                pos++
            }
        }
        throw PythonRuntimeException("SyntaxError", "Unterminated string literal", startLine)
    }
}
