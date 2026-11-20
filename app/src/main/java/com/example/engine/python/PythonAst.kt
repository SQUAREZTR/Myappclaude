package com.example.engine.python

sealed class PyStmt(val line: Int) {
    class ExprStmt(val expr: PyExpr, line: Int) : PyStmt(line)
    class Assign(val targets: List<PyTarget>, val value: PyExpr, line: Int) : PyStmt(line)
    class AugAssign(val target: PyTarget, val op: String, val value: PyExpr, line: Int) : PyStmt(line)
    class Return(val value: PyExpr?, line: Int) : PyStmt(line)
    class Pass(line: Int) : PyStmt(line)
    class Break(line: Int) : PyStmt(line)
    class Continue(line: Int) : PyStmt(line)
    class Global(val names: List<String>, line: Int) : PyStmt(line)
    class Del(val target: PyTarget, line: Int) : PyStmt(line)
    class Assert(val test: PyExpr, val message: PyExpr?, line: Int) : PyStmt(line)
    class Raise(val expr: PyExpr?, line: Int) : PyStmt(line)
    class If(val branches: List<Pair<PyExpr, List<PyStmt>>>, val elseBody: List<PyStmt>, line: Int) : PyStmt(line)
    class While(val cond: PyExpr, val body: List<PyStmt>, val elseBody: List<PyStmt>, line: Int) : PyStmt(line)
    class For(val target: PyTarget, val iter: PyExpr, val body: List<PyStmt>, val elseBody: List<PyStmt>, line: Int) : PyStmt(line)
    class Def(val name: String, val params: List<PyParam>, val body: List<PyStmt>, val decorators: List<PyExpr>, line: Int) : PyStmt(line)
    class ClassDef(val name: String, val baseExpr: PyExpr?, val body: List<PyStmt>, line: Int) : PyStmt(line)
    class Import(val aliases: List<Pair<String, String?>>, line: Int) : PyStmt(line)
    class FromImport(val module: String, val names: List<Pair<String, String?>>, line: Int) : PyStmt(line)
    class TryExcept(
        val tryBody: List<PyStmt>,
        val handlers: List<ExceptHandler>,
        val finallyBody: List<PyStmt>,
        line: Int
    ) : PyStmt(line)
    class With(val contextExpr: PyExpr, val asName: String?, val body: List<PyStmt>, line: Int) : PyStmt(line)
}

data class ExceptHandler(
    val excTypeName: String?,
    val asName: String?,
    val body: List<PyStmt>
)

sealed class PyTarget {
    data class Name(val name: String) : PyTarget()
    data class Attr(val obj: PyExpr, val attr: String) : PyTarget()
    data class Index(val obj: PyExpr, val index: PyExpr) : PyTarget()
    data class TupleUnpack(val targets: List<PyTarget>) : PyTarget()
}

sealed class PyExpr(val line: Int) {
    class Literal(val value: PyValue, line: Int) : PyExpr(line)
    class FString(val rawTemplate: String, line: Int) : PyExpr(line)
    class Name(val name: String, line: Int) : PyExpr(line)
    class ListLit(val items: List<PyExpr>, line: Int) : PyExpr(line)
    class TupleLit(val items: List<PyExpr>, line: Int) : PyExpr(line)
    class SetLit(val items: List<PyExpr>, line: Int) : PyExpr(line)
    class DictLit(val pairs: List<Pair<PyExpr, PyExpr>>, line: Int) : PyExpr(line)
    class ListComp(val elem: PyExpr, val target: PyTarget, val iter: PyExpr, val cond: PyExpr?, line: Int) : PyExpr(line)
    class DictComp(val key: PyExpr, val value: PyExpr, val target: PyTarget, val iter: PyExpr, val cond: PyExpr?, line: Int) : PyExpr(line)
    class Unary(val op: String, val operand: PyExpr, line: Int) : PyExpr(line)
    class Binary(val left: PyExpr, val op: String, val right: PyExpr, line: Int) : PyExpr(line)
    class Compare(val left: PyExpr, val ops: List<Pair<String, PyExpr>>, line: Int) : PyExpr(line)
    class BoolOp(val op: String, val left: PyExpr, val right: PyExpr, line: Int) : PyExpr(line)
    class IfExpr(val cond: PyExpr, val thenExpr: PyExpr, val elseExpr: PyExpr, line: Int) : PyExpr(line)
    class Lambda(val params: List<PyParam>, val body: PyExpr, line: Int) : PyExpr(line)
    class Attr(val obj: PyExpr, val attr: String, line: Int) : PyExpr(line)
    class Subscript(val obj: PyExpr, val index: PyExpr?, val end: PyExpr?, val step: PyExpr?, val isSlice: Boolean, line: Int) : PyExpr(line)
    class Call(val callee: PyExpr, val args: List<CallArg>, line: Int) : PyExpr(line)
}

data class CallArg(
    val name: String? = null,
    val expr: PyExpr,
    val isStar: Boolean = false,
    val isDoubleStar: Boolean = false
)
