package com.example.engine.python

import java.io.File

sealed class PyValue {
    open fun isTruthy(): Boolean = true
    open fun typeName(): String = "object"
    open fun toDisplayString(): String = toReprString()
    open fun toReprString(): String = "<${typeName()}>"
}

data object PyNone : PyValue() {
    override fun isTruthy() = false
    override fun typeName() = "NoneType"
    override fun toReprString() = "None"
}

data class PyBool(val value: Boolean) : PyValue() {
    override fun isTruthy() = value
    override fun typeName() = "bool"
    override fun toReprString() = if (value) "True" else "False"
}

data class PyInt(val value: Long) : PyValue() {
    override fun isTruthy() = value != 0L
    override fun typeName() = "int"
    override fun toReprString() = value.toString()
}

data class PyFloat(val value: Double) : PyValue() {
    override fun isTruthy() = value != 0.0
    override fun typeName() = "float"
    override fun toReprString(): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
        val s = value.toString()
        return if (s.endsWith(".0") && !s.contains("E", true)) s else s
    }
}

data class PyStr(val value: String) : PyValue() {
    override fun isTruthy() = value.isNotEmpty()
    override fun typeName() = "str"
    override fun toDisplayString() = value
    override fun toReprString(): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("'", "\\'")
        return "'$escaped'"
    }
}

class PyList(val items: MutableList<PyValue> = mutableListOf()) : PyValue() {
    override fun isTruthy() = items.isNotEmpty()
    override fun typeName() = "list"
    override fun toReprString() = items.joinToString(", ", "[", "]") { it.toReprString() }
    override fun equals(other: Any?) = other is PyList && items == other.items
    override fun hashCode() = items.hashCode()
}

class PyTuple(val items: List<PyValue>) : PyValue() {
    override fun isTruthy() = items.isNotEmpty()
    override fun typeName() = "tuple"
    override fun toReprString() =
        if (items.size == 1) "(${items[0].toReprString()},)"
        else items.joinToString(", ", "(", ")") { it.toReprString() }
    override fun equals(other: Any?) = other is PyTuple && items == other.items
    override fun hashCode() = items.hashCode()
}

class PySet(val items: LinkedHashSet<PyValue> = linkedSetOf()) : PyValue() {
    override fun isTruthy() = items.isNotEmpty()
    override fun typeName() = "set"
    override fun toReprString() =
        if (items.isEmpty()) "set()" else items.joinToString(", ", "{", "}") { it.toReprString() }
    override fun equals(other: Any?) = other is PySet && items == other.items
    override fun hashCode() = items.hashCode()
}

class PyDict(val entries: LinkedHashMap<PyValue, PyValue> = linkedMapOf()) : PyValue() {
    override fun isTruthy() = entries.isNotEmpty()
    override fun typeName() = "dict"
    override fun toReprString() = entries.entries.joinToString(", ", "{", "}") {
        "${it.key.toReprString()}: ${it.value.toReprString()}"
    }
    override fun equals(other: Any?) = other is PyDict && entries == other.entries
    override fun hashCode() = entries.hashCode()
}

data class PyParam(
    val name: String,
    val defaultExpr: PyExpr? = null,
    val isVarArg: Boolean = false,
    val isKwArg: Boolean = false
)

class PyFunction(
    val name: String,
    val params: List<PyParam>,
    val body: List<PyStmt>,
    val closure: PyEnv,
    val isLambda: Boolean = false,
    val lambdaExpr: PyExpr? = null
) : PyValue() {
    override fun typeName() = "function"
    override fun toReprString() = "<function $name>"
}

class PyBuiltin(
    val name: String,
    val fn: suspend (posArgs: List<PyValue>, kwArgs: Map<String, PyValue>) -> PyValue
) : PyValue() {
    override fun typeName() = "builtin_function_or_method"
    override fun toReprString() = "<built-in function $name>"
}

class PyClass(
    val name: String,
    val base: PyClass? = null,
    val methods: MutableMap<String, PyValue> = mutableMapOf()
) : PyValue() {
    override fun typeName() = "type"
    override fun toReprString() = "<class '$name'>"

    fun findMember(attr: String): PyValue? = methods[attr] ?: base?.findMember(attr)
}

class PyInstance(
    val pyClass: PyClass,
    val fields: MutableMap<String, PyValue> = mutableMapOf()
) : PyValue() {
    override fun typeName() = pyClass.name
    override fun toReprString() = "<${pyClass.name} object>"
}

class PyModule(
    val name: String,
    val members: MutableMap<String, PyValue> = mutableMapOf()
) : PyValue() {
    override fun typeName() = "module"
    override fun toReprString() = "<module '$name'>"
}

class PyFileHandle(
    val file: File,
    val mode: String
) : PyValue() {
    var closed: Boolean = false
    private val lines: List<String>? = if (mode.contains("r") && file.exists()) file.readLines() else null
    private var lineCursor = 0
    private val appendMode = mode.contains("a")
    private var writeCleared = false

    override fun typeName() = "TextIOWrapper"
    override fun toReprString() = "<_io.TextIOWrapper name='${file.name}' mode='$mode'>"

    fun read(): String {
        checkOpen()
        if (!file.exists()) throw PythonRuntimeException("FileNotFoundError", "No such file: '${file.name}'")
        return file.readText()
    }

    fun readlines(): PyList {
        checkOpen()
        val list = lines ?: if (file.exists()) file.readLines() else emptyList()
        return PyList(list.map { PyStr("$it\n") }.toMutableList())
    }

    fun readline(): String {
        checkOpen()
        val list = lines ?: emptyList()
        return if (lineCursor < list.size) "${list[lineCursor++]}\n" else ""
    }

    fun write(text: String): Int {
        checkOpen()
        file.parentFile?.mkdirs()
        if (!appendMode && !writeCleared) {
            file.writeText(text)
            writeCleared = true
        } else {
            file.appendText(text)
        }
        return text.length
    }

    fun close() {
        closed = true
    }

    private fun checkOpen() {
        if (closed) throw PythonRuntimeException("ValueError", "I/O operation on closed file")
    }
}

class PyEnv(val parent: PyEnv? = null) {
    val vars: MutableMap<String, PyValue> = mutableMapOf()
    val globalsDeclared: MutableSet<String> = mutableSetOf()

    fun get(name: String): PyValue? {
        if (name in vars) return vars[name]
        return parent?.get(name)
    }

    fun root(): PyEnv {
        var cur = this
        while (cur.parent != null) cur = cur.parent!!
        return cur
    }

    fun set(name: String, value: PyValue) {
        if (name in globalsDeclared) {
            root().vars[name] = value
        } else {
            vars[name] = value
        }
    }

    fun delete(name: String): Boolean {
        if (name in vars) {
            vars.remove(name)
            return true
        }
        return false
    }
}

class PythonRuntimeException(
    val excType: String,
    val excMessage: String,
    val line: Int = -1
) : RuntimeException(if (line > 0) "$excType (line $line): $excMessage" else "$excType: $excMessage")

// Control-flow signals
internal class ReturnSignal(val value: PyValue) : RuntimeException(null, null, false, false)
internal class BreakSignal : RuntimeException(null, null, false, false)
internal class ContinueSignal : RuntimeException(null, null, false, false)
internal class ExitSignal(val code: Int) : RuntimeException(null, null, false, false)
