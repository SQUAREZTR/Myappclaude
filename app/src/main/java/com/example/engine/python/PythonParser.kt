package com.example.engine.python

class PythonParser(private val tokens: List<Token>) {
    private var pos = 0

    private fun peek(offset: Int = 0): Token =
        tokens.getOrElse(pos + offset) { tokens.last() }

    private fun check(type: TokType): Boolean = peek().type == type

    private fun advance(): Token {
        val tok = peek()
        if (tok.type != TokType.EOF) pos++
        return tok
    }

    private fun match(vararg types: TokType): Boolean {
        if (peek().type in types) {
            advance()
            return true
        }
        return false
    }

    private fun expect(type: TokType, message: String): Token {
        if (peek().type == type) return advance()
        throw PythonRuntimeException("SyntaxError", "$message (got '${peek().lexeme}')", peek().line)
    }

    private fun skipNewlines() {
        while (check(TokType.NEWLINE)) advance()
    }

    fun parseProgram(): List<PyStmt> {
        val stmts = mutableListOf<PyStmt>()
        skipNewlines()
        while (!check(TokType.EOF)) {
            stmts += parseStatement()
            skipNewlines()
        }
        return stmts
    }

    private fun parseStatement(): PyStmt {
        val line = peek().line
        return when (peek().type) {
            TokType.AT -> parseDecoratedDef()
            TokType.KW_IF -> parseIf()
            TokType.KW_WHILE -> parseWhile()
            TokType.KW_FOR -> parseFor()
            TokType.KW_DEF -> parseDef(emptyList())
            TokType.KW_CLASS -> parseClass()
            TokType.KW_TRY -> parseTry()
            TokType.KW_WITH -> parseWith()
            else -> parseSimpleStatementLine(line)
        }
    }

    private fun parseSimpleStatementLine(line: Int): PyStmt {
        val first = parseSmallStatement()
        if (check(TokType.SEMI)) {
            val stmts = mutableListOf(first)
            while (match(TokType.SEMI)) {
                if (check(TokType.NEWLINE) || check(TokType.EOF)) break
                stmts += parseSmallStatement()
            }
            if (check(TokType.NEWLINE)) advance()
            // Wrap multiple semicolon statements inside an unconditional If(True) or return first if 1
            return if (stmts.size == 1) stmts[0] else PyStmt.If(listOf(PyExpr.Literal(PyBool(true), line) to stmts), emptyList(), line)
        }
        if (check(TokType.NEWLINE)) advance()
        else if (!check(TokType.EOF) && !check(TokType.DEDENT)) {
            throw PythonRuntimeException("SyntaxError", "Expected end of line near '${peek().lexeme}'", peek().line)
        }
        return first
    }

    private fun parseSmallStatement(): PyStmt {
        val line = peek().line
        return when (peek().type) {
            TokType.KW_RETURN -> {
                advance()
                val expr = if (!check(TokType.NEWLINE) && !check(TokType.SEMI) && !check(TokType.EOF)) {
                    parseTupleOrExpr()
                } else null
                PyStmt.Return(expr, line)
            }
            TokType.KW_PASS -> { advance(); PyStmt.Pass(line) }
            TokType.KW_BREAK -> { advance(); PyStmt.Break(line) }
            TokType.KW_CONTINUE -> { advance(); PyStmt.Continue(line) }
            TokType.KW_GLOBAL -> {
                advance()
                val names = mutableListOf(expect(TokType.IDENT, "Expected variable name").lexeme)
                while (match(TokType.COMMA)) {
                    names += expect(TokType.IDENT, "Expected variable name").lexeme
                }
                PyStmt.Global(names, line)
            }
            TokType.KW_DEL -> {
                advance()
                val expr = parseExpr()
                PyStmt.Del(exprToTarget(expr), line)
            }
            TokType.KW_ASSERT -> {
                advance()
                val test = parseExpr()
                val msg = if (match(TokType.COMMA)) parseExpr() else null
                PyStmt.Assert(test, msg, line)
            }
            TokType.KW_RAISE -> {
                advance()
                val expr = if (!check(TokType.NEWLINE) && !check(TokType.SEMI) && !check(TokType.EOF)) parseExpr() else null
                PyStmt.Raise(expr, line)
            }
            TokType.KW_IMPORT -> parseImport()
            TokType.KW_FROM -> parseFromImport()
            else -> parseAssignOrExpr(line)
        }
    }

    private fun parseAssignOrExpr(line: Int): PyStmt {
        val firstExpr = parseTupleOrExpr()
        val augOp = when (peek().type) {
            TokType.PLUSEQ -> "+="
            TokType.MINUSEQ -> "-="
            TokType.STAREQ -> "*="
            TokType.SLASHEQ -> "/="
            TokType.FLOORDIVEQ -> "//="
            TokType.PERCENTEQ -> "%="
            TokType.POWEQ -> "**="
            else -> null
        }
        if (augOp != null) {
            advance()
            val rhs = parseTupleOrExpr()
            return PyStmt.AugAssign(exprToTarget(firstExpr), augOp, rhs, line)
        }
        if (match(TokType.ASSIGN)) {
            val chain = mutableListOf(firstExpr)
            var rhs = parseTupleOrExpr()
            while (match(TokType.ASSIGN)) {
                chain += rhs
                rhs = parseTupleOrExpr()
            }
            return PyStmt.Assign(chain.map { exprToTarget(it) }, rhs, line)
        }
        return PyStmt.ExprStmt(firstExpr, line)
    }

    private fun exprToTarget(expr: PyExpr): PyTarget = when (expr) {
        is PyExpr.Name -> PyTarget.Name(expr.name)
        is PyExpr.Attr -> PyTarget.Attr(expr.obj, expr.attr)
        is PyExpr.Subscript -> {
            if (expr.isSlice || expr.index == null) {
                throw PythonRuntimeException("SyntaxError", "Cannot assign to slice", expr.line)
            }
            PyTarget.Index(expr.obj, expr.index)
        }
        is PyExpr.TupleLit -> PyTarget.TupleUnpack(expr.items.map { exprToTarget(it) })
        is PyExpr.ListLit -> PyTarget.TupleUnpack(expr.items.map { exprToTarget(it) })
        else -> throw PythonRuntimeException("SyntaxError", "Cannot assign to expression", expr.line)
    }

    private fun parseBlock(): List<PyStmt> {
        expect(TokType.COLON, "Expected ':' before block")
        if (!check(TokType.NEWLINE)) {
            // Single-line suite: `if x: return 1`
            val line = peek().line
            val stmt = parseSimpleStatementLine(line)
            return listOf(stmt)
        }
        skipNewlines()
        expect(TokType.INDENT, "Expected indented block")
        val body = mutableListOf<PyStmt>()
        skipNewlines()
        while (!check(TokType.DEDENT) && !check(TokType.EOF)) {
            body += parseStatement()
            skipNewlines()
        }
        if (check(TokType.DEDENT)) advance()
        return body
    }

    private fun parseIf(): PyStmt {
        val line = expect(TokType.KW_IF, "Expected 'if'").line
        val branches = mutableListOf<Pair<PyExpr, List<PyStmt>>>()
        val cond = parseExpr()
        branches += cond to parseBlock()
        skipNewlines()
        while (match(TokType.KW_ELIF)) {
            val elifCond = parseExpr()
            branches += elifCond to parseBlock()
            skipNewlines()
        }
        val elseBody = if (match(TokType.KW_ELSE)) parseBlock() else emptyList()
        return PyStmt.If(branches, elseBody, line)
    }

    private fun parseWhile(): PyStmt {
        val line = expect(TokType.KW_WHILE, "Expected 'while'").line
        val cond = parseExpr()
        val body = parseBlock()
        skipNewlines()
        val elseBody = if (match(TokType.KW_ELSE)) parseBlock() else emptyList()
        return PyStmt.While(cond, body, elseBody, line)
    }

    private fun parseFor(): PyStmt {
        val line = expect(TokType.KW_FOR, "Expected 'for'").line
        val targetExpr = parseForTarget()
        expect(TokType.KW_IN, "Expected 'in' in for loop")
        val iter = parseTupleOrExpr()
        val body = parseBlock()
        skipNewlines()
        val elseBody = if (match(TokType.KW_ELSE)) parseBlock() else emptyList()
        return PyStmt.For(exprToTarget(targetExpr), iter, body, elseBody, line)
    }

    private fun parseForTarget(): PyExpr {
        val first = parsePrimaryOrAttrOrSubscript()
        if (!check(TokType.COMMA)) return first
        val items = mutableListOf(first)
        while (match(TokType.COMMA)) {
            if (check(TokType.KW_IN)) break
            items += parsePrimaryOrAttrOrSubscript()
        }
        return PyExpr.TupleLit(items, first.line)
    }

    private fun parseDecoratedDef(): PyStmt {
        val decorators = mutableListOf<PyExpr>()
        while (match(TokType.AT)) {
            decorators += parseExpr()
            skipNewlines()
        }
        return when (peek().type) {
            TokType.KW_DEF -> parseDef(decorators)
            TokType.KW_CLASS -> parseClass()
            else -> throw PythonRuntimeException("SyntaxError", "Expected def or class after decorator", peek().line)
        }
    }

    private fun parseDef(decorators: List<PyExpr>): PyStmt {
        val line = expect(TokType.KW_DEF, "Expected 'def'").line
        val name = expect(TokType.IDENT, "Expected function name").lexeme
        expect(TokType.LPAREN, "Expected '(' after function name")
        val params = parseParamList(TokType.RPAREN)
        expect(TokType.RPAREN, "Expected ')' after parameters")
        if (match(TokType.ARROW)) {
            parseExpr() // optional return type annotation
        }
        val body = parseBlock()
        return PyStmt.Def(name, params, body, decorators, line)
    }

    private fun parseParamList(terminator: TokType): List<PyParam> {
        val params = mutableListOf<PyParam>()
        while (!check(terminator) && !check(TokType.EOF)) {
            val isDoubleStar = match(TokType.POW)
            val isStar = if (!isDoubleStar) match(TokType.STAR) else false
            val pName = expect(TokType.IDENT, "Expected parameter name").lexeme
            if (match(TokType.COLON)) {
                parseExpr() // optional type annotation
            }
            val defaultExpr = if (match(TokType.ASSIGN)) parseExpr() else null
            params += PyParam(pName, defaultExpr, isStar, isDoubleStar)
            if (!match(TokType.COMMA)) break
        }
        return params
    }

    private fun parseClass(): PyStmt {
        val line = expect(TokType.KW_CLASS, "Expected 'class'").line
        val name = expect(TokType.IDENT, "Expected class name").lexeme
        var baseExpr: PyExpr? = null
        if (match(TokType.LPAREN)) {
            if (!check(TokType.RPAREN)) {
                baseExpr = parseExpr()
                while (match(TokType.COMMA)) {
                    if (check(TokType.RPAREN)) break
                    parseExpr()
                }
            }
            expect(TokType.RPAREN, "Expected ')' after base class")
        }
        val body = parseBlock()
        return PyStmt.ClassDef(name, baseExpr, body, line)
    }

    private fun parseImport(): PyStmt {
        val line = expect(TokType.KW_IMPORT, "Expected 'import'").line
        val aliases = mutableListOf<Pair<String, String?>>()
        do {
            val modName = parseDottedName()
            val asName = if (match(TokType.KW_AS)) expect(TokType.IDENT, "Expected alias").lexeme else null
            aliases += modName to asName
        } while (match(TokType.COMMA))
        return PyStmt.Import(aliases, line)
    }

    private fun parseFromImport(): PyStmt {
        val line = expect(TokType.KW_FROM, "Expected 'from'").line
        val module = parseDottedName()
        expect(TokType.KW_IMPORT, "Expected 'import'")
        val names = mutableListOf<Pair<String, String?>>()
        if (match(TokType.STAR)) {
            names += "*" to null
        } else {
            val paren = match(TokType.LPAREN)
            do {
                if (paren && check(TokType.RPAREN)) break
                val item = expect(TokType.IDENT, "Expected symbol name").lexeme
                val asName = if (match(TokType.KW_AS)) expect(TokType.IDENT, "Expected alias").lexeme else null
                names += item to asName
            } while (match(TokType.COMMA))
            if (paren) expect(TokType.RPAREN, "Expected ')'")
        }
        return PyStmt.FromImport(module, names, line)
    }

    private fun parseDottedName(): String {
        val parts = mutableListOf(expect(TokType.IDENT, "Expected module name").lexeme)
        while (match(TokType.DOT)) {
            parts += expect(TokType.IDENT, "Expected submodule name").lexeme
        }
        return parts.joinToString(".")
    }

    private fun parseTry(): PyStmt {
        val line = expect(TokType.KW_TRY, "Expected 'try'").line
        val tryBody = parseBlock()
        val handlers = mutableListOf<ExceptHandler>()
        skipNewlines()
        while (match(TokType.KW_EXCEPT)) {
            var excType: String? = null
            var asName: String? = null
            if (!check(TokType.COLON)) {
                excType = expect(TokType.IDENT, "Expected exception type").lexeme
                while (match(TokType.DOT)) {
                    excType += "." + expect(TokType.IDENT, "Expected name").lexeme
                }
                if (match(TokType.KW_AS)) {
                    asName = expect(TokType.IDENT, "Expected variable name").lexeme
                }
            }
            val handlerBody = parseBlock()
            handlers += ExceptHandler(excType, asName, handlerBody)
            skipNewlines()
        }
        val finallyBody = if (match(TokType.KW_FINALLY)) parseBlock() else emptyList()
        return PyStmt.TryExcept(tryBody, handlers, finallyBody, line)
    }

    private fun parseWith(): PyStmt {
        val line = expect(TokType.KW_WITH, "Expected 'with'").line
        val ctxExpr = parseExpr()
        val asName = if (match(TokType.KW_AS)) expect(TokType.IDENT, "Expected variable name").lexeme else null
        val body = parseBlock()
        return PyStmt.With(ctxExpr, asName, body, line)
    }

    private fun parseTupleOrExpr(): PyExpr {
        val first = parseExpr()
        if (!check(TokType.COMMA)) return first
        val items = mutableListOf(first)
        while (match(TokType.COMMA)) {
            if (check(TokType.NEWLINE) || check(TokType.ASSIGN) || check(TokType.RPAREN) ||
                check(TokType.RBRACKET) || check(TokType.COLON) || check(TokType.SEMI) || check(TokType.EOF)
            ) break
            items += parseExpr()
        }
        return PyExpr.TupleLit(items, first.line)
    }

    private fun parseExpr(): PyExpr {
        if (check(TokType.KW_LAMBDA)) return parseLambda()
        val expr = parseOr()
        if (match(TokType.KW_IF)) {
            val cond = parseOr()
            expect(TokType.KW_ELSE, "Expected 'else' in conditional expression")
            val elseExpr = parseExpr()
            return PyExpr.IfExpr(cond, expr, elseExpr, expr.line)
        }
        return expr
    }

    private fun parseLambda(): PyExpr {
        val line = expect(TokType.KW_LAMBDA, "Expected 'lambda'").line
        val params = if (!check(TokType.COLON)) parseParamList(TokType.COLON) else emptyList()
        expect(TokType.COLON, "Expected ':' in lambda")
        val body = parseExpr()
        return PyExpr.Lambda(params, body, line)
    }

    private fun parseOr(): PyExpr {
        var left = parseAnd()
        while (match(TokType.KW_OR)) {
            val right = parseAnd()
            left = PyExpr.BoolOp("or", left, right, left.line)
        }
        return left
    }

    private fun parseAnd(): PyExpr {
        var left = parseNot()
        while (match(TokType.KW_AND)) {
            val right = parseNot()
            left = PyExpr.BoolOp("and", left, right, left.line)
        }
        return left
    }

    private fun parseNot(): PyExpr {
        if (match(TokType.KW_NOT)) {
            val line = peek().line
            return PyExpr.Unary("not", parseNot(), line)
        }
        return parseCompare()
    }

    private fun parseCompare(): PyExpr {
        val left = parseAddSub()
        val ops = mutableListOf<Pair<String, PyExpr>>()
        while (true) {
            val op = when (peek().type) {
                TokType.EQ -> { advance(); "==" }
                TokType.NEQ -> { advance(); "!=" }
                TokType.LT -> { advance(); "<" }
                TokType.GT -> { advance(); ">" }
                TokType.LE -> { advance(); "<=" }
                TokType.GE -> { advance(); ">=" }
                TokType.KW_IN -> { advance(); "in" }
                TokType.KW_NOT -> {
                    if (peek(1).type == TokType.KW_IN) {
                        advance(); advance(); "not in"
                    } else break
                }
                TokType.KW_IS -> {
                    advance()
                    if (match(TokType.KW_NOT)) "is not" else "is"
                }
                else -> break
            }
            ops += op to parseAddSub()
        }
        return if (ops.isEmpty()) left else PyExpr.Compare(left, ops, left.line)
    }

    private fun parseAddSub(): PyExpr {
        var left = parseMulDiv()
        while (check(TokType.PLUS) || check(TokType.MINUS)) {
            val op = advance().lexeme
            left = PyExpr.Binary(left, op, parseMulDiv(), left.line)
        }
        return left
    }

    private fun parseMulDiv(): PyExpr {
        var left = parseUnary()
        while (check(TokType.STAR) || check(TokType.SLASH) || check(TokType.FLOORDIV) || check(TokType.PERCENT)) {
            val op = advance().lexeme
            left = PyExpr.Binary(left, op, parseUnary(), left.line)
        }
        return left
    }

    private fun parseUnary(): PyExpr {
        if (check(TokType.PLUS) || check(TokType.MINUS)) {
            val op = advance()
            return PyExpr.Unary(op.lexeme, parseUnary(), op.line)
        }
        return parsePower()
    }

    private fun parsePower(): PyExpr {
        val left = parsePrimaryOrAttrOrSubscript()
        if (match(TokType.POW)) {
            return PyExpr.Binary(left, "**", parseUnary(), left.line)
        }
        return left
    }

    private fun parsePrimaryOrAttrOrSubscript(): PyExpr {
        var expr = parseAtom()
        while (true) {
            expr = when (peek().type) {
                TokType.DOT -> {
                    advance()
                    val attr = expect(TokType.IDENT, "Expected attribute name").lexeme
                    PyExpr.Attr(expr, attr, expr.line)
                }
                TokType.LPAREN -> {
                    advance()
                    val args = mutableListOf<CallArg>()
                    while (!check(TokType.RPAREN) && !check(TokType.EOF)) {
                        when {
                            match(TokType.POW) -> args += CallArg(expr = parseExpr(), isDoubleStar = true)
                            match(TokType.STAR) -> args += CallArg(expr = parseExpr(), isStar = true)
                            peek().type == TokType.IDENT && peek(1).type == TokType.ASSIGN -> {
                                val kw = advance().lexeme
                                advance()
                                args += CallArg(name = kw, expr = parseExpr())
                            }
                            else -> args += CallArg(expr = parseExpr())
                        }
                        if (!match(TokType.COMMA)) break
                    }
                    expect(TokType.RPAREN, "Expected ')' after arguments")
                    PyExpr.Call(expr, args, expr.line)
                }
                TokType.LBRACKET -> {
                    advance()
                    var start: PyExpr? = null
                    var end: PyExpr? = null
                    var step: PyExpr? = null
                    var isSlice = false
                    if (!check(TokType.COLON)) {
                        start = parseExpr()
                    }
                    if (match(TokType.COLON)) {
                        isSlice = true
                        if (!check(TokType.COLON) && !check(TokType.RBRACKET)) {
                            end = parseExpr()
                        }
                        if (match(TokType.COLON)) {
                            if (!check(TokType.RBRACKET)) {
                                step = parseExpr()
                            }
                        }
                    }
                    expect(TokType.RBRACKET, "Expected ']'")
                    PyExpr.Subscript(expr, start, end, step, isSlice, expr.line)
                }
                else -> return expr
            }
        }
    }

    private fun parseAtom(): PyExpr {
        val tok = peek()
        return when (tok.type) {
            TokType.INT -> { advance(); PyExpr.Literal(PyInt(tok.lexeme.toLong()), tok.line) }
            TokType.FLOAT -> { advance(); PyExpr.Literal(PyFloat(tok.lexeme.toDouble()), tok.line) }
            TokType.STRING -> { advance(); PyExpr.Literal(PyStr(tok.lexeme), tok.line) }
            TokType.FSTRING -> { advance(); PyExpr.FString(tok.lexeme, tok.line) }
            TokType.KW_TRUE -> { advance(); PyExpr.Literal(PyBool(true), tok.line) }
            TokType.KW_FALSE -> { advance(); PyExpr.Literal(PyBool(false), tok.line) }
            TokType.KW_NONE -> { advance(); PyExpr.Literal(PyNone, tok.line) }
            TokType.IDENT -> { advance(); PyExpr.Name(tok.lexeme, tok.line) }
            TokType.LPAREN -> {
                advance()
                if (match(TokType.RPAREN)) return PyExpr.TupleLit(emptyList(), tok.line)
                val first = parseExpr()
                if (check(TokType.KW_FOR)) {
                    // Generator expression -> treat as list comprehension
                    advance()
                    val target = exprToTarget(parseForTarget())
                    expect(TokType.KW_IN, "Expected 'in'")
                    val iter = parseOr()
                    val cond = if (match(TokType.KW_IF)) parseExpr() else null
                    expect(TokType.RPAREN, "Expected ')'")
                    return PyExpr.ListComp(first, target, iter, cond, tok.line)
                }
                if (match(TokType.COMMA)) {
                    val items = mutableListOf(first)
                    while (!check(TokType.RPAREN) && !check(TokType.EOF)) {
                        items += parseExpr()
                        if (!match(TokType.COMMA)) break
                    }
                    expect(TokType.RPAREN, "Expected ')'")
                    PyExpr.TupleLit(items, tok.line)
                } else {
                    expect(TokType.RPAREN, "Expected ')'")
                    first
                }
            }
            TokType.LBRACKET -> {
                advance()
                if (match(TokType.RBRACKET)) return PyExpr.ListLit(emptyList(), tok.line)
                val first = parseExpr()
                if (match(TokType.KW_FOR)) {
                    val target = exprToTarget(parseForTarget())
                    expect(TokType.KW_IN, "Expected 'in'")
                    val iter = parseOr()
                    val cond = if (match(TokType.KW_IF)) parseExpr() else null
                    expect(TokType.RBRACKET, "Expected ']'")
                    return PyExpr.ListComp(first, target, iter, cond, tok.line)
                }
                val items = mutableListOf(first)
                while (match(TokType.COMMA)) {
                    if (check(TokType.RBRACKET)) break
                    items += parseExpr()
                }
                expect(TokType.RBRACKET, "Expected ']'")
                PyExpr.ListLit(items, tok.line)
            }
            TokType.LBRACE -> {
                advance()
                if (match(TokType.RBRACE)) return PyExpr.DictLit(emptyList(), tok.line)
                val first = parseExpr()
                if (match(TokType.COLON)) {
                    val firstVal = parseExpr()
                    if (match(TokType.KW_FOR)) {
                        val target = exprToTarget(parseForTarget())
                        expect(TokType.KW_IN, "Expected 'in'")
                        val iter = parseOr()
                        val cond = if (match(TokType.KW_IF)) parseExpr() else null
                        expect(TokType.RBRACE, "Expected '}'")
                        return PyExpr.DictComp(first, firstVal, target, iter, cond, tok.line)
                    }
                    val pairs = mutableListOf(first to firstVal)
                    while (match(TokType.COMMA)) {
                        if (check(TokType.RBRACE)) break
                        val k = parseExpr()
                        expect(TokType.COLON, "Expected ':' in dict literal")
                        val v = parseExpr()
                        pairs += k to v
                    }
                    expect(TokType.RBRACE, "Expected '}'")
                    PyExpr.DictLit(pairs, tok.line)
                } else {
                    val items = mutableListOf(first)
                    while (match(TokType.COMMA)) {
                        if (check(TokType.RBRACE)) break
                        items += parseExpr()
                    }
                    expect(TokType.RBRACE, "Expected '}'")
                    PyExpr.SetLit(items, tok.line)
                }
            }
            else -> throw PythonRuntimeException("SyntaxError", "Unexpected token '${tok.lexeme}'", tok.line)
        }
    }
}
