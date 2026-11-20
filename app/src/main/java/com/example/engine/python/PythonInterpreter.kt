package com.example.engine.python

import com.example.data.nvidia.NvidiaNimClient
import com.example.engine.shell.WorkspaceManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.pow

class PythonInterpreter(
    private val workspace: WorkspaceManager,
    private val cwdProvider: () -> File,
    private val cwdSetter: (File) -> Unit,
    private val nimClient: NvidiaNimClient,
    private val apiKeyProvider: () -> String,
    private val baseUrlProvider: () -> String,
    private val activeModelProvider: () -> String,
    private val onStdout: suspend (String) -> Unit,
    private val onStderr: suspend (String) -> Unit,
    private val onInput: suspend (String) -> String
) {
    private var globalEnv = PyEnv()
    private val loadedModules = mutableMapOf<String, PyModule>()
    private var stdLib: PythonStdLib? = null

    fun resetReplEnv() {
        globalEnv = PyEnv()
        loadedModules.clear()
        stdLib = null
    }

    suspend fun execute(
        code: String,
        scriptName: String = "<stdin>",
        scriptArgs: List<String> = listOf(scriptName),
        isRepl: Boolean = false
    ): Result<Int> {
        return try {
            if (stdLib == null || !isRepl) {
                if (!isRepl) {
                    globalEnv = PyEnv()
                    loadedModules.clear()
                }
                globalEnv.vars["__name__"] = PyStr("__main__")
                stdLib = PythonStdLib(
                    workspace = workspace,
                    cwdProvider = cwdProvider,
                    cwdSetter = cwdSetter,
                    nimClient = nimClient,
                    apiKeyProvider = apiKeyProvider,
                    baseUrlProvider = baseUrlProvider,
                    activeModelProvider = activeModelProvider,
                    scriptArgs = scriptArgs,
                    callFunctionBridge = { fn, pos, kw -> invokeCallable(fn, pos, kw, 0) }
                )
                installBuiltins(globalEnv)
            }

            val tokens = PythonLexer(code).tokenize()
            val stmts = PythonParser(tokens).parseProgram()

            if (isRepl && stmts.size == 1 && stmts[0] is PyStmt.ExprStmt) {
                val value = evalExpr((stmts[0] as PyStmt.ExprStmt).expr, globalEnv)
                if (value !== PyNone) {
                    onStdout(value.toReprString() + "\n")
                }
            } else {
                execBlock(stmts, globalEnv)
            }
            Result.success(0)
        } catch (exit: ExitSignal) {
            Result.success(exit.code)
        } catch (e: PythonRuntimeException) {
            val loc = if (e.line > 0) "File \"$scriptName\", line ${e.line}\n" else ""
            onStderr("Traceback (most recent call last):\n$loc${e.excType}: ${e.excMessage}\n")
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            onStderr("\nKeyboardInterrupt\n")
            throw e
        } catch (e: Exception) {
            onStderr("RuntimeError: ${e.message ?: e::class.simpleName}\n")
            Result.failure(e)
        }
    }

    private fun installBuiltins(env: PyEnv) {
        fun add(name: String, fn: suspend (List<PyValue>, Map<String, PyValue>) -> PyValue) {
            env.vars[name] = PyBuiltin(name, fn)
        }

        add("print") { pos, kw ->
            val sep = (kw["sep"] as? PyStr)?.value ?: " "
            val end = (kw["end"] as? PyStr)?.value ?: "\n"
            onStdout(pos.joinToString(sep) { it.toDisplayString() } + end)
            PyNone
        }

        add("input") { pos, _ ->
            val prompt = pos.firstOrNull()?.toDisplayString().orEmpty()
            PyStr(onInput(prompt))
        }

        add("len") { pos, _ ->
            val v = pos.firstOrNull() ?: throw PythonRuntimeException("TypeError", "len() takes 1 argument")
            PyInt(
                when (v) {
                    is PyStr -> v.value.length.toLong()
                    is PyList -> v.items.size.toLong()
                    is PyTuple -> v.items.size.toLong()
                    is PyDict -> v.entries.size.toLong()
                    is PySet -> v.items.size.toLong()
                    else -> throw PythonRuntimeException("TypeError", "object of type '${v.typeName()}' has no len()")
                }
            )
        }

        add("range") { pos, _ ->
            val (start, stop, step) = when (pos.size) {
                1 -> Triple(0L, PythonStdLib.asLong(pos[0]), 1L)
                2 -> Triple(PythonStdLib.asLong(pos[0]), PythonStdLib.asLong(pos[1]), 1L)
                3 -> Triple(PythonStdLib.asLong(pos[0]), PythonStdLib.asLong(pos[1]), PythonStdLib.asLong(pos[2]))
                else -> throw PythonRuntimeException("TypeError", "range expected 1 to 3 arguments")
            }
            if (step == 0L) throw PythonRuntimeException("ValueError", "range() arg 3 must not be zero")
            val items = mutableListOf<PyValue>()
            var cur = start
            var safety = 0
            while ((step > 0 && cur < stop) || (step < 0 && cur > stop)) {
                items += PyInt(cur)
                cur += step
                if (++safety > 250_000) throw PythonRuntimeException("MemoryError", "range size exceeds mobile limit")
            }
            PyList(items)
        }

        add("enumerate") { pos, kw ->
            val seq = PythonStdLib.asSequenceList(pos[0])
            val start = (kw["start"] ?: pos.getOrNull(1))?.let { PythonStdLib.asLong(it) } ?: 0L
            PyList(seq.mapIndexed { idx, item -> PyTuple(listOf(PyInt(start + idx), item)) }.toMutableList())
        }

        add("zip") { pos, _ ->
            val lists = pos.map { PythonStdLib.asSequenceList(it) }
            val minSize = lists.minOfOrNull { it.size } ?: 0
            val result: MutableList<PyValue> = (0 until minSize).map { i -> PyTuple(lists.map { it[i] }) }.toMutableList()
            PyList(result)
        }

        add("map") { pos, _ ->
            val fn = pos[0]
            val seq = PythonStdLib.asSequenceList(pos[1])
            PyList(seq.map { invokeCallable(fn, listOf(it), emptyMap(), 0) }.toMutableList())
        }

        add("filter") { pos, _ ->
            val fn = pos[0]
            val seq = PythonStdLib.asSequenceList(pos[1])
            val out = mutableListOf<PyValue>()
            for (item in seq) {
                val keep = if (fn === PyNone) item.isTruthy() else invokeCallable(fn, listOf(item), emptyMap(), 0).isTruthy()
                if (keep) out += item
            }
            PyList(out)
        }

        add("sum") { pos, _ ->
            val seq = PythonStdLib.asSequenceList(pos[0])
            var acc: PyValue = pos.getOrNull(1) ?: PyInt(0)
            for (item in seq) acc = evalBinary("+", acc, item, 0)
            acc
        }

        add("min") { pos, _ ->
            val seq = if (pos.size == 1) PythonStdLib.asSequenceList(pos[0]) else pos
            if (seq.isEmpty()) throw PythonRuntimeException("ValueError", "min() arg is an empty sequence")
            seq.reduce { a, b -> if (compareLt(a, b, 0)) a else b }
        }

        add("max") { pos, _ ->
            val seq = if (pos.size == 1) PythonStdLib.asSequenceList(pos[0]) else pos
            if (seq.isEmpty()) throw PythonRuntimeException("ValueError", "max() arg is an empty sequence")
            seq.reduce { a, b -> if (compareLt(b, a, 0)) a else b }
        }

        add("sorted") { pos, kw ->
            val seq = PythonStdLib.asSequenceList(pos[0]).toMutableList()
            val reverse = kw["reverse"]?.isTruthy() == true
            val keyFn = kw["key"]
            if (keyFn != null && keyFn !== PyNone) {
                val keyed = seq.map { it to invokeCallable(keyFn, listOf(it), emptyMap(), 0) }
                    .sortedWith { a, b -> compareValues(a.second, b.second) }
                    .map { it.first }
                PyList((if (reverse) keyed.reversed() else keyed).toMutableList())
            } else {
                seq.sortWith { a, b -> compareValues(a, b) }
                if (reverse) seq.reverse()
                PyList(seq)
            }
        }

        add("reversed") { pos, _ -> PyList(PythonStdLib.asSequenceList(pos[0]).reversed().toMutableList()) }
        add("abs") { pos, _ ->
            when (val v = pos[0]) {
                is PyInt -> PyInt(kotlin.math.abs(v.value))
                is PyFloat -> PyFloat(kotlin.math.abs(v.value))
                else -> throw PythonRuntimeException("TypeError", "bad operand type for abs()")
            }
        }
        add("round") { pos, _ ->
            val num = PythonStdLib.asDouble(pos[0])
            val digits = pos.getOrNull(1)?.let { PythonStdLib.asLong(it).toInt() }
            if (digits == null) PyInt(kotlin.math.round(num).toLong())
            else {
                val factor = 10.0.pow(digits)
                PyFloat(kotlin.math.round(num * factor) / factor)
            }
        }
        add("pow") { pos, _ -> evalBinary("**", pos[0], pos[1], 0) }
        add("divmod") { pos, _ ->
            val a = PythonStdLib.asLong(pos[0]); val b = PythonStdLib.asLong(pos[1])
            PyTuple(listOf(PyInt(Math.floorDiv(a, b)), PyInt(Math.floorMod(a, b))))
        }
        add("any") { pos, _ -> PyBool(PythonStdLib.asSequenceList(pos[0]).any { it.isTruthy() }) }
        add("all") { pos, _ -> PyBool(PythonStdLib.asSequenceList(pos[0]).all { it.isTruthy() }) }
        add("chr") { pos, _ -> PyStr(PythonStdLib.asLong(pos[0]).toInt().toChar().toString()) }
        add("ord") { pos, _ ->
            val s = (pos[0] as? PyStr)?.value ?: throw PythonRuntimeException("TypeError", "ord() expected string")
            if (s.length != 1) throw PythonRuntimeException("TypeError", "ord() expected a character")
            PyInt(s[0].code.toLong())
        }
        add("hex") { pos, _ -> PyStr("0x" + PythonStdLib.asLong(pos[0]).toString(16)) }
        add("bin") { pos, _ -> PyStr("0b" + PythonStdLib.asLong(pos[0]).toString(2)) }
        add("oct") { pos, _ -> PyStr("0o" + PythonStdLib.asLong(pos[0]).toString(8)) }
        add("int") { pos, _ ->
            if (pos.isEmpty()) PyInt(0)
            else when (val v = pos[0]) {
                is PyInt -> v
                is PyFloat -> PyInt(v.value.toLong())
                is PyBool -> PyInt(if (v.value) 1L else 0L)
                is PyStr -> {
                    val base = pos.getOrNull(1)?.let { PythonStdLib.asLong(it).toInt() } ?: 10
                    PyInt(v.value.trim().toLong(base))
                }
                else -> throw PythonRuntimeException("TypeError", "int() argument invalid")
            }
        }
        add("float") { pos, _ ->
            if (pos.isEmpty()) PyFloat(0.0)
            else when (val v = pos[0]) {
                is PyFloat -> v
                is PyInt -> PyFloat(v.value.toDouble())
                is PyBool -> PyFloat(if (v.value) 1.0 else 0.0)
                is PyStr -> PyFloat(v.value.trim().toDouble())
                else -> throw PythonRuntimeException("TypeError", "float() argument invalid")
            }
        }
        add("str") { pos, _ -> PyStr(pos.firstOrNull()?.toDisplayString() ?: "") }
        add("bool") { pos, _ -> PyBool(pos.firstOrNull()?.isTruthy() == true) }
        add("list") { pos, _ ->
            if (pos.isEmpty()) PyList() else PyList(PythonStdLib.asSequenceList(pos[0]).toMutableList())
        }
        add("tuple") { pos, _ ->
            if (pos.isEmpty()) PyTuple(emptyList()) else PyTuple(PythonStdLib.asSequenceList(pos[0]))
        }
        add("set") { pos, _ ->
            if (pos.isEmpty()) PySet() else PySet(LinkedHashSet(PythonStdLib.asSequenceList(pos[0])))
        }
        add("dict") { pos, kw ->
            val d = PyDict()
            if (pos.isNotEmpty()) {
                val src = pos[0]
                if (src is PyDict) d.entries.putAll(src.entries)
                else for (pair in PythonStdLib.asSequenceList(src)) {
                    val t = PythonStdLib.asSequenceList(pair)
                    if (t.size >= 2) d.entries[t[0]] = t[1]
                }
            }
            kw.forEach { (k, v) -> d.entries[PyStr(k)] = v }
            d
        }
        add("type") { pos, _ -> PyStr("<class '${pos[0].typeName()}'>") }
        add("repr") { pos, _ -> PyStr(pos[0].toReprString()) }
        add("callable") { pos, _ ->
            val v = pos[0]
            PyBool(v is PyFunction || v is PyBuiltin || v is PyClass)
        }
        add("isinstance") { pos, _ ->
            val obj = pos[0]; val cls = pos[1]
            val match = when (cls) {
                is PyClass -> obj is PyInstance && isClassOrSubclass(obj.pyClass, cls)
                is PyBuiltin -> when (cls.name) {
                    "int" -> obj is PyInt
                    "float" -> obj is PyFloat
                    "str" -> obj is PyStr
                    "bool" -> obj is PyBool
                    "list" -> obj is PyList
                    "dict" -> obj is PyDict
                    "tuple" -> obj is PyTuple
                    "set" -> obj is PySet
                    else -> false
                }
                else -> false
            }
            PyBool(match)
        }
        add("hasattr") { pos, _ ->
            val ok = runCatching { getAttr(pos[0], pos[1].toDisplayString(), 0) }.isSuccess
            PyBool(ok)
        }
        add("getattr") { pos, _ ->
            val attr = pos[1].toDisplayString()
            runCatching { getAttr(pos[0], attr, 0) }.getOrElse {
                if (pos.size >= 3) pos[2] else throw it
            }
        }
        add("setattr") { pos, _ ->
            setAttr(pos[0], pos[1].toDisplayString(), pos[2], 0)
            PyNone
        }
        add("dir") { pos, _ ->
            val names = when (val v = pos.firstOrNull()) {
                is PyModule -> v.members.keys.sorted()
                is PyInstance -> (v.fields.keys + v.pyClass.methods.keys).distinct().sorted()
                is PyClass -> v.methods.keys.sorted()
                null -> env.vars.keys.sorted()
                else -> emptyList()
            }
            PyList(names.map { PyStr(it) }.toMutableList())
        }
        add("open") { pos, kw ->
            val path = pos.firstOrNull()?.toDisplayString()
                ?: throw PythonRuntimeException("TypeError", "open() missing file path")
            val mode = (pos.getOrNull(1) ?: kw["mode"])?.toDisplayString() ?: "r"
            val file = workspace.resolveSafe(path, cwdProvider())
            if (mode.contains("r") && !mode.contains("+") && !file.exists()) {
                throw PythonRuntimeException("FileNotFoundError", "No such file: '$path'")
            }
            PyFileHandle(file, mode)
        }
    }

    private suspend fun execBlock(stmts: List<PyStmt>, env: PyEnv) {
        for (stmt in stmts) {
            currentCoroutineContext().ensureActive()
            execStmt(stmt, env)
        }
    }

    private suspend fun execStmt(stmt: PyStmt, env: PyEnv) {
        try {
            when (stmt) {
                is PyStmt.Pass -> Unit
                is PyStmt.Break -> throw BreakSignal()
                is PyStmt.Continue -> throw ContinueSignal()
                is PyStmt.Return -> throw ReturnSignal(stmt.value?.let { evalExpr(it, env) } ?: PyNone)
                is PyStmt.ExprStmt -> { evalExpr(stmt.expr, env) }
                is PyStmt.Global -> env.globalsDeclared.addAll(stmt.names)
                is PyStmt.Assign -> {
                    val v = evalExpr(stmt.value, env)
                    stmt.targets.forEach { assignTarget(it, v, env, stmt.line) }
                }
                is PyStmt.AugAssign -> {
                    val cur = readTarget(stmt.target, env, stmt.line)
                    val rhs = evalExpr(stmt.value, env)
                    val op = stmt.op.removeSuffix("=")
                    val updated = evalBinary(op, cur, rhs, stmt.line)
                    assignTarget(stmt.target, updated, env, stmt.line)
                }
                is PyStmt.Del -> deleteTarget(stmt.target, env, stmt.line)
                is PyStmt.Assert -> {
                    if (!evalExpr(stmt.test, env).isTruthy()) {
                        val msg = stmt.message?.let { evalExpr(it, env).toDisplayString() } ?: "Assertion failed"
                        throw PythonRuntimeException("AssertionError", msg, stmt.line)
                    }
                }
                is PyStmt.Raise -> {
                    if (stmt.expr == null) throw PythonRuntimeException("RuntimeError", "Re-raised exception", stmt.line)
                    val v = evalExpr(stmt.expr, env)
                    throw PythonRuntimeException(v.typeName(), v.toDisplayString(), stmt.line)
                }
                is PyStmt.If -> {
                    var ran = false
                    for ((cond, body) in stmt.branches) {
                        if (evalExpr(cond, env).isTruthy()) {
                            execBlock(body, env)
                            ran = true
                            break
                        }
                    }
                    if (!ran && stmt.elseBody.isNotEmpty()) execBlock(stmt.elseBody, env)
                }
                is PyStmt.While -> {
                    var broke = false
                    while (evalExpr(stmt.cond, env).isTruthy()) {
                        currentCoroutineContext().ensureActive()
                        try {
                            execBlock(stmt.body, env)
                        } catch (_: ContinueSignal) {
                            continue
                        } catch (_: BreakSignal) {
                            broke = true
                            break
                        }
                    }
                    if (!broke && stmt.elseBody.isNotEmpty()) execBlock(stmt.elseBody, env)
                }
                is PyStmt.For -> {
                    val items = PythonStdLib.asSequenceList(evalExpr(stmt.iter, env)).toList()
                    var broke = false
                    for (item in items) {
                        currentCoroutineContext().ensureActive()
                        assignTarget(stmt.target, item, env, stmt.line)
                        try {
                            execBlock(stmt.body, env)
                        } catch (_: ContinueSignal) {
                            continue
                        } catch (_: BreakSignal) {
                            broke = true
                            break
                        }
                    }
                    if (!broke && stmt.elseBody.isNotEmpty()) execBlock(stmt.elseBody, env)
                }
                is PyStmt.Def -> {
                    var fn: PyValue = PyFunction(stmt.name, stmt.params, stmt.body, env)
                    for (dec in stmt.decorators.reversed()) {
                        val decFn = evalExpr(dec, env)
                        fn = invokeCallable(decFn, listOf(fn), emptyMap(), stmt.line)
                    }
                    env.set(stmt.name, fn)
                }
                is PyStmt.ClassDef -> {
                    val base = stmt.baseExpr?.let {
                        evalExpr(it, env) as? PyClass
                            ?: throw PythonRuntimeException("TypeError", "Base must be a class", stmt.line)
                    }
                    val classEnv = PyEnv(env)
                    execBlock(stmt.body, classEnv)
                    val cls = PyClass(stmt.name, base, classEnv.vars.toMutableMap())
                    env.set(stmt.name, cls)
                }
                is PyStmt.Import -> {
                    for ((modName, alias) in stmt.aliases) {
                        val mod = loadModule(modName, stmt.line)
                        val bindName = alias ?: modName.substringBefore(".")
                        env.set(bindName, mod)
                    }
                }
                is PyStmt.FromImport -> {
                    val mod = loadModule(stmt.module, stmt.line)
                    for ((sym, alias) in stmt.names) {
                        if (sym == "*") {
                            mod.members.forEach { (k, v) -> if (!k.startsWith("_")) env.set(k, v) }
                        } else {
                            val valObj = mod.members[sym]
                                ?: throw PythonRuntimeException("ImportError", "cannot import name '$sym' from '${stmt.module}'", stmt.line)
                            env.set(alias ?: sym, valObj)
                        }
                    }
                }
                is PyStmt.TryExcept -> {
                    try {
                        execBlock(stmt.tryBody, env)
                    } catch (e: PythonRuntimeException) {
                        val matched = stmt.handlers.firstOrNull { h ->
                            h.excTypeName == null || h.excTypeName == "Exception" || h.excTypeName == e.excType
                        }
                        if (matched == null) throw e
                        if (matched.asName != null) {
                            env.set(matched.asName, PyStr(e.excMessage))
                        }
                        execBlock(matched.body, env)
                    } finally {
                        if (stmt.finallyBody.isNotEmpty()) execBlock(stmt.finallyBody, env)
                    }
                }
                is PyStmt.With -> {
                    val ctx = evalExpr(stmt.contextExpr, env)
                    if (stmt.asName != null) env.set(stmt.asName, ctx)
                    try {
                        execBlock(stmt.body, env)
                    } finally {
                        if (ctx is PyFileHandle) ctx.close()
                    }
                }
            }
        } catch (e: PythonRuntimeException) {
            if (e.line <= 0) throw PythonRuntimeException(e.excType, e.excMessage, stmt.line)
            throw e
        }
    }

    private suspend fun loadModule(moduleName: String, line: Int): PyModule {
        loadedModules[moduleName]?.let { return it }

        stdLib?.getBuiltinModule(moduleName)?.let { mod ->
            loadedModules[moduleName] = mod
            return mod
        }

        // Check if parent module exists (e.g. import os.path -> load os)
        if ("." in moduleName) {
            val topName = moduleName.substringBefore(".")
            stdLib?.getBuiltinModule(topName)?.let { topMod ->
                loadedModules[topName] = topMod
                return topMod
            }
        }

        // Try loading local .py file from workspace
        val fileRel = moduleName.replace('.', '/') + ".py"
        val file = runCatching { workspace.resolveSafe(fileRel, cwdProvider()) }.getOrNull()
        if (file != null && file.exists() && file.isFile) {
            val modEnv = PyEnv()
            modEnv.vars["__name__"] = PyStr(moduleName)
            installBuiltins(modEnv)
            val tokens = PythonLexer(file.readText()).tokenize()
            val stmts = PythonParser(tokens).parseProgram()
            execBlock(stmts, modEnv)
            val mod = PyModule(moduleName, modEnv.vars.toMutableMap())
            loadedModules[moduleName] = mod
            return mod
        }

        throw PythonRuntimeException("ModuleNotFoundError", "No module named '$moduleName'", line)
    }

    private suspend fun evalExpr(expr: PyExpr, env: PyEnv): PyValue = when (expr) {
        is PyExpr.Literal -> expr.value
        is PyExpr.Name -> env.get(expr.name)
            ?: throw PythonRuntimeException("NameError", "name '${expr.name}' is not defined", expr.line)
        is PyExpr.FString -> evalFString(expr.rawTemplate, env, expr.line)
        is PyExpr.ListLit -> PyList(expr.items.map { evalExpr(it, env) }.toMutableList())
        is PyExpr.TupleLit -> PyTuple(expr.items.map { evalExpr(it, env) })
        is PyExpr.SetLit -> PySet(LinkedHashSet(expr.items.map { evalExpr(it, env) }))
        is PyExpr.DictLit -> {
            val d = PyDict()
            for ((k, v) in expr.pairs) d.entries[evalExpr(k, env)] = evalExpr(v, env)
            d
        }
        is PyExpr.ListComp -> {
            val out = mutableListOf<PyValue>()
            val compEnv = PyEnv(env)
            for (item in PythonStdLib.asSequenceList(evalExpr(expr.iter, env))) {
                currentCoroutineContext().ensureActive()
                assignTarget(expr.target, item, compEnv, expr.line)
                if (expr.cond == null || evalExpr(expr.cond, compEnv).isTruthy()) {
                    out += evalExpr(expr.elem, compEnv)
                }
            }
            PyList(out)
        }
        is PyExpr.DictComp -> {
            val d = PyDict()
            val compEnv = PyEnv(env)
            for (item in PythonStdLib.asSequenceList(evalExpr(expr.iter, env))) {
                currentCoroutineContext().ensureActive()
                assignTarget(expr.target, item, compEnv, expr.line)
                if (expr.cond == null || evalExpr(expr.cond, compEnv).isTruthy()) {
                    d.entries[evalExpr(expr.key, compEnv)] = evalExpr(expr.value, compEnv)
                }
            }
            d
        }
        is PyExpr.Unary -> {
            val v = evalExpr(expr.operand, env)
            when (expr.op) {
                "not" -> PyBool(!v.isTruthy())
                "-" -> when (v) {
                    is PyInt -> PyInt(-v.value)
                    is PyFloat -> PyFloat(-v.value)
                    else -> throw PythonRuntimeException("TypeError", "bad operand type for unary -", expr.line)
                }
                "+" -> v
                else -> throw PythonRuntimeException("SyntaxError", "Unknown unary operator ${expr.op}", expr.line)
            }
        }
        is PyExpr.Binary -> evalBinary(expr.op, evalExpr(expr.left, env), evalExpr(expr.right, env), expr.line)
        is PyExpr.BoolOp -> {
            val left = evalExpr(expr.left, env)
            if (expr.op == "and") {
                if (!left.isTruthy()) left else evalExpr(expr.right, env)
            } else {
                if (left.isTruthy()) left else evalExpr(expr.right, env)
            }
        }
        is PyExpr.Compare -> {
            var cur = evalExpr(expr.left, env)
            var result = true
            for ((op, rhsExpr) in expr.ops) {
                val rhs = evalExpr(rhsExpr, env)
                if (!evalSingleCompare(op, cur, rhs, expr.line)) {
                    result = false
                    break
                }
                cur = rhs
            }
            PyBool(result)
        }
        is PyExpr.IfExpr -> {
            if (evalExpr(expr.cond, env).isTruthy()) evalExpr(expr.thenExpr, env) else evalExpr(expr.elseExpr, env)
        }
        is PyExpr.Lambda -> PyFunction("<lambda>", expr.params, emptyList(), env, isLambda = true, lambdaExpr = expr.body)
        is PyExpr.Attr -> getAttr(evalExpr(expr.obj, env), expr.attr, expr.line)
        is PyExpr.Subscript -> evalSubscript(expr, env)
        is PyExpr.Call -> {
            val callee = evalExpr(expr.callee, env)
            val pos = mutableListOf<PyValue>()
            val kw = mutableMapOf<String, PyValue>()
            for (arg in expr.args) {
                when {
                    arg.isStar -> pos += PythonStdLib.asSequenceList(evalExpr(arg.expr, env))
                    arg.isDoubleStar -> {
                        val d = evalExpr(arg.expr, env) as? PyDict
                            ?: throw PythonRuntimeException("TypeError", "** unpacking requires a dict", expr.line)
                        d.entries.forEach { (k, v) -> kw[k.toDisplayString()] = v }
                    }
                    arg.name != null -> kw[arg.name] = evalExpr(arg.expr, env)
                    else -> pos += evalExpr(arg.expr, env)
                }
            }
            invokeCallable(callee, pos, kw, expr.line)
        }
    }

    private suspend fun evalFString(template: String, env: PyEnv, line: Int): PyStr {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                if (i + 1 < template.length && template[i + 1] == '{') {
                    sb.append('{'); i += 2; continue
                }
                val end = findMatchingBrace(template, i + 1)
                if (end < 0) throw PythonRuntimeException("SyntaxError", "f-string: expecting '}'", line)
                val inner = template.substring(i + 1, end)
                val colonIdx = findFormatColon(inner)
                val exprCode = if (colonIdx >= 0) inner.substring(0, colonIdx) else inner
                val fmtSpec = if (colonIdx >= 0) inner.substring(colonIdx + 1) else null
                val tokens = PythonLexer(exprCode).tokenize()
                val stmts = PythonParser(tokens).parseProgram()
                val expr = (stmts.firstOrNull() as? PyStmt.ExprStmt)?.expr
                    ?: throw PythonRuntimeException("SyntaxError", "Invalid f-string expression '$exprCode'", line)
                val value = evalExpr(expr, env)
                sb.append(formatValue(value, fmtSpec))
                i = end + 1
            } else if (c == '}' && i + 1 < template.length && template[i + 1] == '}') {
                sb.append('}'); i += 2
            } else {
                sb.append(c); i++
            }
        }
        return PyStr(sb.toString())
    }

    private fun findMatchingBrace(s: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < s.length) {
            if (s[i] == '{') depth++
            else if (s[i] == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    private fun findFormatColon(inner: String): Int {
        var depth = 0
        for (i in inner.indices) {
            when (inner[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ':' -> if (depth == 0) return i
            }
        }
        return -1
    }

    private fun formatValue(value: PyValue, spec: String?): String {
        if (spec.isNullOrBlank()) return value.toDisplayString()
        val fMatch = Regex("""\.(\d+)f""").find(spec)
        if (fMatch != null) {
            val digits = fMatch.groupValues[1].toInt()
            return String.format(java.util.Locale.US, "%.${digits}f", PythonStdLib.asDouble(value))
        }
        return value.toDisplayString()
    }

    private suspend fun invokeCallable(callee: PyValue, pos: List<PyValue>, kw: Map<String, PyValue>, line: Int): PyValue {
        return when (callee) {
            is PyBuiltin -> callee.fn(pos, kw)
            is PyFunction -> {
                val fnEnv = PyEnv(callee.closure)
                bindParams(callee.params, pos, kw, fnEnv, callee.name, line)
                if (callee.isLambda && callee.lambdaExpr != null) {
                    evalExpr(callee.lambdaExpr, fnEnv)
                } else {
                    try {
                        execBlock(callee.body, fnEnv)
                        PyNone
                    } catch (ret: ReturnSignal) {
                        ret.value
                    }
                }
            }
            is PyClass -> {
                val instance = PyInstance(callee)
                val init = callee.findMember("__init__")
                if (init != null) {
                    invokeCallable(init, listOf(instance) + pos, kw, line)
                }
                instance
            }
            else -> throw PythonRuntimeException("TypeError", "'${callee.typeName()}' object is not callable", line)
        }
    }

    private suspend fun bindParams(
        params: List<PyParam>,
        pos: List<PyValue>,
        kw: Map<String, PyValue>,
        fnEnv: PyEnv,
        fnName: String,
        line: Int
    ) {
        var posIdx = 0
        val remainingKw = kw.toMutableMap()
        for (p in params) {
            when {
                p.isVarArg -> {
                    val rest = if (posIdx < pos.size) pos.subList(posIdx, pos.size) else emptyList()
                    posIdx = pos.size
                    fnEnv.vars[p.name] = PyTuple(rest.toList())
                }
                p.isKwArg -> {
                    val d = PyDict()
                    remainingKw.forEach { (k, v) -> d.entries[PyStr(k)] = v }
                    remainingKw.clear()
                    fnEnv.vars[p.name] = d
                }
                posIdx < pos.size -> fnEnv.vars[p.name] = pos[posIdx++]
                remainingKw.containsKey(p.name) -> fnEnv.vars[p.name] = remainingKw.remove(p.name)!!
                p.defaultExpr != null -> fnEnv.vars[p.name] = evalExpr(p.defaultExpr, fnEnv)
                else -> throw PythonRuntimeException("TypeError", "$fnName() missing required argument: '${p.name}'", line)
            }
        }
    }

    private fun getAttr(obj: PyValue, attr: String, line: Int): PyValue = when (obj) {
        is PyModule -> obj.members[attr]
            ?: throw PythonRuntimeException("AttributeError", "module '${obj.name}' has no attribute '$attr'", line)
        is PyInstance -> {
            obj.fields[attr] ?: when (val m = obj.pyClass.findMember(attr)) {
                is PyFunction -> PyBuiltin(attr) { pos, kw -> invokeCallable(m, listOf(obj) + pos, kw, line) }
                is PyValue -> m
                null -> throw PythonRuntimeException("AttributeError", "'${obj.pyClass.name}' object has no attribute '$attr'", line)
            }
        }
        is PyClass -> obj.findMember(attr)
            ?: throw PythonRuntimeException("AttributeError", "type '${obj.name}' has no attribute '$attr'", line)
        is PyStr -> getStrMethod(obj, attr, line)
        is PyList -> getListMethod(obj, attr, line)
        is PyDict -> getDictMethod(obj, attr, line)
        is PySet -> getSetMethod(obj, attr, line)
        is PyFileHandle -> getFileMethod(obj, attr, line)
        else -> throw PythonRuntimeException("AttributeError", "'${obj.typeName()}' object has no attribute '$attr'", line)
    }

    private fun getStrMethod(s: PyStr, attr: String, line: Int): PyValue = when (attr) {
        "upper" -> PyBuiltin("upper") { _, _ -> PyStr(s.value.uppercase()) }
        "lower" -> PyBuiltin("lower") { _, _ -> PyStr(s.value.lowercase()) }
        "title" -> PyBuiltin("title") { _, _ -> PyStr(s.value.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }) }
        "strip" -> PyBuiltin("strip") { pos, _ ->
            if (pos.isEmpty()) PyStr(s.value.trim())
            else PyStr(s.value.trim(*pos[0].toDisplayString().toCharArray()))
        }
        "lstrip" -> PyBuiltin("lstrip") { _, _ -> PyStr(s.value.trimStart()) }
        "rstrip" -> PyBuiltin("rstrip") { _, _ -> PyStr(s.value.trimEnd()) }
        "split" -> PyBuiltin("split") { pos, _ ->
            val parts = if (pos.isEmpty() || pos[0] === PyNone) {
                s.value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            } else {
                s.value.split(pos[0].toDisplayString())
            }
            PyList(parts.map { PyStr(it) }.toMutableList())
        }
        "splitlines" -> PyBuiltin("splitlines") { _, _ -> PyList(s.value.lines().map { PyStr(it) }.toMutableList()) }
        "join" -> PyBuiltin("join") { pos, _ ->
            PyStr(PythonStdLib.asSequenceList(pos[0]).joinToString(s.value) { it.toDisplayString() })
        }
        "replace" -> PyBuiltin("replace") { pos, _ ->
            PyStr(s.value.replace(pos[0].toDisplayString(), pos[1].toDisplayString()))
        }
        "startswith" -> PyBuiltin("startswith") { pos, _ -> PyBool(s.value.startsWith(pos[0].toDisplayString())) }
        "endswith" -> PyBuiltin("endswith") { pos, _ -> PyBool(s.value.endsWith(pos[0].toDisplayString())) }
        "find" -> PyBuiltin("find") { pos, _ -> PyInt(s.value.indexOf(pos[0].toDisplayString()).toLong()) }
        "count" -> PyBuiltin("count") { pos, _ ->
            val sub = pos[0].toDisplayString()
            if (sub.isEmpty()) PyInt((s.value.length + 1).toLong())
            else PyInt(Regex(Regex.escape(sub)).findAll(s.value).count().toLong())
        }
        "isdigit" -> PyBuiltin("isdigit") { _, _ -> PyBool(s.value.isNotEmpty() && s.value.all { it.isDigit() }) }
        "isalpha" -> PyBuiltin("isalpha") { _, _ -> PyBool(s.value.isNotEmpty() && s.value.all { it.isLetter() }) }
        "format" -> PyBuiltin("format") { pos, _ ->
            var out = s.value
            pos.forEachIndexed { idx, arg ->
                out = out.replace("{$idx}", arg.toDisplayString())
                if (out.contains("{}")) out = out.replaceFirst("{}", arg.toDisplayString())
            }
            PyStr(out)
        }
        else -> throw PythonRuntimeException("AttributeError", "'str' object has no attribute '$attr'", line)
    }

    private fun getListMethod(list: PyList, attr: String, line: Int): PyValue = when (attr) {
        "append" -> PyBuiltin("append") { pos, _ -> list.items.add(pos[0]); PyNone }
        "extend" -> PyBuiltin("extend") { pos, _ -> list.items.addAll(PythonStdLib.asSequenceList(pos[0])); PyNone }
        "insert" -> PyBuiltin("insert") { pos, _ ->
            val idx = PythonStdLib.asLong(pos[0]).toInt().coerceIn(0, list.items.size)
            list.items.add(idx, pos[1]); PyNone
        }
        "pop" -> PyBuiltin("pop") { pos, _ ->
            if (list.items.isEmpty()) throw PythonRuntimeException("IndexError", "pop from empty list", line)
            val idx = pos.firstOrNull()?.let { normalizeIdx(PythonStdLib.asLong(it).toInt(), list.items.size, line) }
                ?: list.items.lastIndex
            list.items.removeAt(idx)
        }
        "remove" -> PyBuiltin("remove") { pos, _ ->
            if (!list.items.remove(pos[0])) throw PythonRuntimeException("ValueError", "list.remove(x): x not in list", line)
            PyNone
        }
        "clear" -> PyBuiltin("clear") { _, _ -> list.items.clear(); PyNone }
        "copy" -> PyBuiltin("copy") { _, _ -> PyList(list.items.toMutableList()) }
        "reverse" -> PyBuiltin("reverse") { _, _ -> list.items.reverse(); PyNone }
        "sort" -> PyBuiltin("sort") { _, kw ->
            val reverse = kw["reverse"]?.isTruthy() == true
            list.items.sortWith { a, b -> compareValues(a, b) }
            if (reverse) list.items.reverse()
            PyNone
        }
        "index" -> PyBuiltin("index") { pos, _ ->
            val idx = list.items.indexOf(pos[0])
            if (idx < 0) throw PythonRuntimeException("ValueError", "item not in list", line)
            PyInt(idx.toLong())
        }
        "count" -> PyBuiltin("count") { pos, _ -> PyInt(list.items.count { it == pos[0] }.toLong()) }
        else -> throw PythonRuntimeException("AttributeError", "'list' object has no attribute '$attr'", line)
    }

    private fun getDictMethod(dict: PyDict, attr: String, line: Int): PyValue = when (attr) {
        "get" -> PyBuiltin("get") { pos, _ -> dict.entries[pos[0]] ?: pos.getOrNull(1) ?: PyNone }
        "keys" -> PyBuiltin("keys") { _, _ -> PyList(dict.entries.keys.toMutableList()) }
        "values" -> PyBuiltin("values") { _, _ -> PyList(dict.entries.values.toMutableList()) }
        "items" -> PyBuiltin("items") { _, _ ->
            PyList(dict.entries.entries.map { PyTuple(listOf(it.key, it.value)) }.toMutableList())
        }
        "pop" -> PyBuiltin("pop") { pos, _ ->
            dict.entries.remove(pos[0]) ?: pos.getOrNull(1)
            ?: throw PythonRuntimeException("KeyError", pos[0].toReprString(), line)
        }
        "setdefault" -> PyBuiltin("setdefault") { pos, _ ->
            dict.entries.getOrPut(pos[0]) { pos.getOrNull(1) ?: PyNone }
        }
        "update" -> PyBuiltin("update") { pos, _ ->
            val other = pos[0] as? PyDict ?: throw PythonRuntimeException("TypeError", "update() expects dict", line)
            dict.entries.putAll(other.entries)
            PyNone
        }
        "clear" -> PyBuiltin("clear") { _, _ -> dict.entries.clear(); PyNone }
        "copy" -> PyBuiltin("copy") { _, _ -> PyDict(LinkedHashMap(dict.entries)) }
        else -> throw PythonRuntimeException("AttributeError", "'dict' object has no attribute '$attr'", line)
    }

    private fun getSetMethod(set: PySet, attr: String, line: Int): PyValue = when (attr) {
        "add" -> PyBuiltin("add") { pos, _ -> set.items.add(pos[0]); PyNone }
        "remove" -> PyBuiltin("remove") { pos, _ ->
            if (!set.items.remove(pos[0])) throw PythonRuntimeException("KeyError", pos[0].toReprString(), line)
            PyNone
        }
        "discard" -> PyBuiltin("discard") { pos, _ -> set.items.remove(pos[0]); PyNone }
        "clear" -> PyBuiltin("clear") { _, _ -> set.items.clear(); PyNone }
        "union" -> PyBuiltin("union") { pos, _ ->
            PySet(LinkedHashSet(set.items).apply { addAll(PythonStdLib.asSequenceList(pos[0])) })
        }
        "intersection" -> PyBuiltin("intersection") { pos, _ ->
            val other = PythonStdLib.asSequenceList(pos[0]).toSet()
            PySet(LinkedHashSet(set.items.filter { it in other }))
        }
        else -> throw PythonRuntimeException("AttributeError", "'set' object has no attribute '$attr'", line)
    }

    private fun getFileMethod(file: PyFileHandle, attr: String, line: Int): PyValue = when (attr) {
        "read" -> PyBuiltin("read") { _, _ -> PyStr(file.read()) }
        "readline" -> PyBuiltin("readline") { _, _ -> PyStr(file.readline()) }
        "readlines" -> PyBuiltin("readlines") { _, _ -> file.readlines() }
        "write" -> PyBuiltin("write") { pos, _ -> PyInt(file.write(pos[0].toDisplayString()).toLong()) }
        "close" -> PyBuiltin("close") { _, _ -> file.close(); PyNone }
        else -> throw PythonRuntimeException("AttributeError", "'TextIOWrapper' object has no attribute '$attr'", line)
    }

    private suspend fun evalSubscript(expr: PyExpr.Subscript, env: PyEnv): PyValue {
        val target = evalExpr(expr.obj, env)
        if (!expr.isSlice) {
            val key = evalExpr(expr.index!!, env)
            return when (target) {
                is PyDict -> target.entries[key] ?: throw PythonRuntimeException("KeyError", key.toReprString(), expr.line)
                is PyList -> target.items[normalizeIdx(PythonStdLib.asLong(key).toInt(), target.items.size, expr.line)]
                is PyTuple -> target.items[normalizeIdx(PythonStdLib.asLong(key).toInt(), target.items.size, expr.line)]
                is PyStr -> PyStr(target.value[normalizeIdx(PythonStdLib.asLong(key).toInt(), target.value.length, expr.line)].toString())
                else -> throw PythonRuntimeException("TypeError", "'${target.typeName()}' object is not subscriptable", expr.line)
            }
        }
        val size = when (target) {
            is PyList -> target.items.size
            is PyTuple -> target.items.size
            is PyStr -> target.value.length
            else -> throw PythonRuntimeException("TypeError", "Cannot slice '${target.typeName()}'", expr.line)
        }
        val step = expr.step?.let { PythonStdLib.asLong(evalExpr(it, env)).toInt() } ?: 1
        if (step == 0) throw PythonRuntimeException("ValueError", "slice step cannot be zero", expr.line)
        val rawStart = expr.index?.let { PythonStdLib.asLong(evalExpr(it, env)).toInt() }
        val rawEnd = expr.end?.let { PythonStdLib.asLong(evalExpr(it, env)).toInt() }
        val start = if (rawStart == null) {
            if (step > 0) 0 else size - 1
        } else {
            val s = if (rawStart < 0) size + rawStart else rawStart
            if (step > 0) s.coerceIn(0, size) else s.coerceIn(-1, size - 1)
        }
        val end = if (rawEnd == null) {
            if (step > 0) size else -1
        } else {
            val e = if (rawEnd < 0) size + rawEnd else rawEnd
            if (step > 0) e.coerceIn(0, size) else e.coerceIn(-1, size - 1)
        }
        val indices = mutableListOf<Int>()
        var idx = start
        while ((step > 0 && idx < end) || (step < 0 && idx > end)) {
            if (idx in 0 until size) indices += idx
            idx += step
        }
        return when (target) {
            is PyList -> PyList(indices.map { target.items[it] }.toMutableList())
            is PyTuple -> PyTuple(indices.map { target.items[it] })
            is PyStr -> PyStr(indices.map { target.value[it] }.joinToString(""))
            else -> PyNone
        }
    }

    private fun normalizeIdx(idx: Int, size: Int, line: Int): Int {
        val norm = if (idx < 0) size + idx else idx
        if (norm !in 0 until size) throw PythonRuntimeException("IndexError", "index out of range", line)
        return norm
    }

    private suspend fun assignTarget(target: PyTarget, value: PyValue, env: PyEnv, line: Int) {
        when (target) {
            is PyTarget.Name -> env.set(target.name, value)
            is PyTarget.Attr -> setAttr(evalExpr(target.obj, env), target.attr, value, line)
            is PyTarget.Index -> {
                val container = evalExpr(target.obj, env)
                val key = evalExpr(target.index, env)
                when (container) {
                    is PyDict -> container.entries[key] = value
                    is PyList -> container.items[normalizeIdx(PythonStdLib.asLong(key).toInt(), container.items.size, line)] = value
                    else -> throw PythonRuntimeException("TypeError", "'${container.typeName()}' does not support item assignment", line)
                }
            }
            is PyTarget.TupleUnpack -> {
                val seq = PythonStdLib.asSequenceList(value)
                if (seq.size != target.targets.size) {
                    throw PythonRuntimeException("ValueError", "cannot unpack ${seq.size} values into ${target.targets.size}", line)
                }
                target.targets.zip(seq).forEach { (t, v) -> assignTarget(t, v, env, line) }
            }
        }
    }

    private suspend fun readTarget(target: PyTarget, env: PyEnv, line: Int): PyValue = when (target) {
        is PyTarget.Name -> env.get(target.name)
            ?: throw PythonRuntimeException("NameError", "name '${target.name}' is not defined", line)
        is PyTarget.Attr -> getAttr(evalExpr(target.obj, env), target.attr, line)
        is PyTarget.Index -> {
            val container = evalExpr(target.obj, env)
            val key = evalExpr(target.index, env)
            when (container) {
                is PyDict -> container.entries[key] ?: throw PythonRuntimeException("KeyError", key.toReprString(), line)
                is PyList -> container.items[normalizeIdx(PythonStdLib.asLong(key).toInt(), container.items.size, line)]
                else -> throw PythonRuntimeException("TypeError", "cannot index '${container.typeName()}'", line)
            }
        }
        is PyTarget.TupleUnpack -> throw PythonRuntimeException("SyntaxError", "illegal target for augmented assignment", line)
    }

    private suspend fun deleteTarget(target: PyTarget, env: PyEnv, line: Int) {
        when (target) {
            is PyTarget.Name -> if (!env.delete(target.name)) throw PythonRuntimeException("NameError", target.name, line)
            is PyTarget.Index -> {
                val container = evalExpr(target.obj, env)
                val key = evalExpr(target.index, env)
                when (container) {
                    is PyDict -> container.entries.remove(key)
                    is PyList -> container.items.removeAt(normalizeIdx(PythonStdLib.asLong(key).toInt(), container.items.size, line))
                    else -> throw PythonRuntimeException("TypeError", "cannot delete item", line)
                }
            }
            else -> throw PythonRuntimeException("SyntaxError", "cannot delete target", line)
        }
    }

    private fun setAttr(obj: PyValue, attr: String, value: PyValue, line: Int) {
        when (obj) {
            is PyInstance -> obj.fields[attr] = value
            is PyModule -> obj.members[attr] = value
            else -> throw PythonRuntimeException("AttributeError", "cannot set attribute '$attr'", line)
        }
    }

    private fun evalBinary(op: String, l: PyValue, r: PyValue, line: Int): PyValue {
        if (op == "+" && l is PyStr && r is PyStr) return PyStr(l.value + r.value)
        if (op == "+" && l is PyList && r is PyList) return PyList((l.items + r.items).toMutableList())
        if (op == "*" && l is PyStr && r is PyInt) return PyStr(l.value.repeat(r.value.toInt().coerceAtLeast(0)))
        if (op == "*" && l is PyInt && r is PyStr) return PyStr(r.value.repeat(l.value.toInt().coerceAtLeast(0)))
        if (op == "*" && l is PyList && r is PyInt) {
            val out = mutableListOf<PyValue>()
            repeat(r.value.toInt().coerceAtLeast(0)) { out.addAll(l.items) }
            return PyList(out)
        }

        if ((l is PyInt || l is PyBool || l is PyFloat) && (r is PyInt || r is PyBool || r is PyFloat)) {
            val useFloat = l is PyFloat || r is PyFloat || op == "/"
            if (useFloat) {
                val a = PythonStdLib.asDouble(l); val b = PythonStdLib.asDouble(r)
                return when (op) {
                    "+" -> PyFloat(a + b)
                    "-" -> PyFloat(a - b)
                    "*" -> PyFloat(a * b)
                    "/" -> {
                        if (b == 0.0) throw PythonRuntimeException("ZeroDivisionError", "division by zero", line)
                        PyFloat(a / b)
                    }
                    "//" -> {
                        if (b == 0.0) throw PythonRuntimeException("ZeroDivisionError", "floor division by zero", line)
                        PyFloat(kotlin.math.floor(a / b))
                    }
                    "%" -> PyFloat(((a % b) + b) % b)
                    "**" -> PyFloat(a.pow(b))
                    else -> throw PythonRuntimeException("TypeError", "unsupported operator $op", line)
                }
            } else {
                val a = PythonStdLib.asLong(l); val b = PythonStdLib.asLong(r)
                return when (op) {
                    "+" -> PyInt(a + b)
                    "-" -> PyInt(a - b)
                    "*" -> PyInt(a * b)
                    "//" -> {
                        if (b == 0L) throw PythonRuntimeException("ZeroDivisionError", "integer division by zero", line)
                        PyInt(Math.floorDiv(a, b))
                    }
                    "%" -> {
                        if (b == 0L) throw PythonRuntimeException("ZeroDivisionError", "integer modulo by zero", line)
                        PyInt(Math.floorMod(a, b))
                    }
                    "**" -> if (b < 0) PyFloat(a.toDouble().pow(b.toDouble())) else PyInt(a.toDouble().pow(b.toDouble()).toLong())
                    else -> throw PythonRuntimeException("TypeError", "unsupported operator $op", line)
                }
            }
        }
        throw PythonRuntimeException("TypeError", "unsupported operand type(s) for $op: '${l.typeName()}' and '${r.typeName()}'", line)
    }

    private fun evalSingleCompare(op: String, l: PyValue, r: PyValue, line: Int): Boolean = when (op) {
        "==" -> pyEquals(l, r)
        "!=" -> !pyEquals(l, r)
        "is" -> l === r || (l is PyNone && r is PyNone) || (l is PyBool && r is PyBool && l.value == r.value)
        "is not" -> !evalSingleCompare("is", l, r, line)
        "<" -> compareLt(l, r, line)
        ">" -> compareLt(r, l, line)
        "<=" -> compareLt(l, r, line) || pyEquals(l, r)
        ">=" -> compareLt(r, l, line) || pyEquals(l, r)
        "in" -> containsValue(r, l, line)
        "not in" -> !containsValue(r, l, line)
        else -> false
    }

    private fun pyEquals(l: PyValue, r: PyValue): Boolean = when {
        (l is PyInt || l is PyBool || l is PyFloat) && (r is PyInt || r is PyBool || r is PyFloat) ->
            PythonStdLib.asDouble(l) == PythonStdLib.asDouble(r)
        else -> l == r
    }

    private fun compareLt(l: PyValue, r: PyValue, line: Int): Boolean = when {
        l is PyStr && r is PyStr -> l.value < r.value
        (l is PyInt || l is PyFloat || l is PyBool) && (r is PyInt || r is PyFloat || r is PyBool) ->
            PythonStdLib.asDouble(l) < PythonStdLib.asDouble(r)
        else -> throw PythonRuntimeException("TypeError", "'<' not supported between '${l.typeName()}' and '${r.typeName()}'", line)
    }

    private fun compareValues(a: PyValue, b: PyValue): Int = when {
        pyEquals(a, b) -> 0
        compareLt(a, b, 0) -> -1
        else -> 1
    }

    private fun containsValue(container: PyValue, item: PyValue, line: Int): Boolean = when (container) {
        is PyStr -> item is PyStr && container.value.contains(item.value)
        is PyList -> container.items.any { pyEquals(it, item) }
        is PyTuple -> container.items.any { pyEquals(it, item) }
        is PySet -> container.items.any { pyEquals(it, item) }
        is PyDict -> container.entries.keys.any { pyEquals(it, item) }
        else -> throw PythonRuntimeException("TypeError", "argument of type '${container.typeName()}' is not iterable", line)
    }

    private fun isClassOrSubclass(c: PyClass, target: PyClass): Boolean {
        var cur: PyClass? = c
        while (cur != null) {
            if (cur === target) return true
            cur = cur.base
        }
        return false
    }
}
