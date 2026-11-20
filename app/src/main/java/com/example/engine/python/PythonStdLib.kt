package com.example.engine.python

import com.example.data.nvidia.ApiChatMessage
import com.example.data.nvidia.NvidiaNimClient
import com.example.engine.shell.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.random.Random

class PythonStdLib(
    private val workspace: WorkspaceManager,
    private val cwdProvider: () -> File,
    private val cwdSetter: (File) -> Unit,
    private val nimClient: NvidiaNimClient,
    private val apiKeyProvider: () -> String,
    private val baseUrlProvider: () -> String,
    private val activeModelProvider: () -> String,
    private val scriptArgs: List<String>,
    private val callFunctionBridge: suspend (PyValue, List<PyValue>, Map<String, PyValue>) -> PyValue
) {
    private var rng = Random(System.currentTimeMillis())
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getBuiltinModule(name: String): PyModule? = when (name) {
        "math" -> buildMathModule()
        "random" -> buildRandomModule()
        "json" -> buildJsonModule()
        "os" -> buildOsModule()
        "os.path" -> buildOsPathModule()
        "sys" -> buildSysModule()
        "time" -> buildTimeModule()
        "datetime" -> buildDatetimeModule()
        "re" -> buildReModule()
        "collections" -> buildCollectionsModule()
        "pathlib" -> buildPathlibModule()
        "statistics" -> buildStatisticsModule()
        "string" -> buildStringModule()
        "urllib" -> buildUrllibPackage()
        "urllib.request" -> buildUrllibRequestModule()
        "nvidia" -> buildNvidiaModule()
        "litellm" -> buildLiteLlmModule()
        else -> null
    }

    private fun buildMathModule() = PyModule("math").apply {
        members["pi"] = PyFloat(PI)
        members["e"] = PyFloat(E)
        members["tau"] = PyFloat(2 * PI)
        members["inf"] = PyFloat(Double.POSITIVE_INFINITY)
        members["nan"] = PyFloat(Double.NaN)
        fun num1(n: String, fn: (Double) -> Double) {
            members[n] = PyBuiltin(n) { pos, _ ->
                if (pos.isEmpty()) throw PythonRuntimeException("TypeError", "$n() takes 1 argument")
                PyFloat(fn(asDouble(pos[0])))
            }
        }
        num1("sqrt") { sqrt(it) }
        num1("sin") { sin(it) }
        num1("cos") { cos(it) }
        num1("tan") { tan(it) }
        num1("asin") { asin(it) }
        num1("acos") { acos(it) }
        num1("atan") { atan(it) }
        num1("exp") { exp(it) }
        num1("fabs") { abs(it) }
        num1("degrees") { Math.toDegrees(it) }
        num1("radians") { Math.toRadians(it) }
        members["floor"] = PyBuiltin("floor") { pos, _ -> PyInt(floor(asDouble(pos[0])).toLong()) }
        members["ceil"] = PyBuiltin("ceil") { pos, _ -> PyInt(ceil(asDouble(pos[0])).toLong()) }
        members["log"] = PyBuiltin("log") { pos, _ ->
            val x = asDouble(pos[0])
            if (pos.size >= 2) PyFloat(ln(x) / ln(asDouble(pos[1]))) else PyFloat(ln(x))
        }
        members["log10"] = PyBuiltin("log10") { pos, _ -> PyFloat(log10(asDouble(pos[0]))) }
        members["log2"] = PyBuiltin("log2") { pos, _ -> PyFloat(log2(asDouble(pos[0]))) }
        members["pow"] = PyBuiltin("pow") { pos, _ -> PyFloat(asDouble(pos[0]).pow(asDouble(pos[1]))) }
        members["factorial"] = PyBuiltin("factorial") { pos, _ ->
            val n = asLong(pos[0]).toInt()
            if (n < 0) throw PythonRuntimeException("ValueError", "factorial() not defined for negative values")
            var acc = 1L
            for (i in 2..n) acc *= i
            PyInt(acc)
        }
        members["gcd"] = PyBuiltin("gcd") { pos, _ ->
            var a = abs(asLong(pos[0]))
            var b = abs(asLong(pos[1]))
            while (b != 0L) { val t = a % b; a = b; b = t }
            PyInt(a)
        }
    }

    private fun buildRandomModule() = PyModule("random").apply {
        members["seed"] = PyBuiltin("seed") { pos, _ ->
            rng = if (pos.isNotEmpty()) Random(asLong(pos[0])) else Random(System.currentTimeMillis())
            PyNone
        }
        members["random"] = PyBuiltin("random") { _, _ -> PyFloat(rng.nextDouble()) }
        members["uniform"] = PyBuiltin("uniform") { pos, _ ->
            val a = asDouble(pos[0]); val b = asDouble(pos[1])
            PyFloat(a + rng.nextDouble() * (b - a))
        }
        members["randint"] = PyBuiltin("randint") { pos, _ ->
            val a = asLong(pos[0]); val b = asLong(pos[1])
            if (a > b) throw PythonRuntimeException("ValueError", "empty range for randint")
            PyInt(rng.nextLong(a, b + 1))
        }
        members["choice"] = PyBuiltin("choice") { pos, _ ->
            val seq = asSequenceList(pos[0])
            if (seq.isEmpty()) throw PythonRuntimeException("IndexError", "Cannot choose from an empty sequence")
            seq[rng.nextInt(seq.size)]
        }
        members["shuffle"] = PyBuiltin("shuffle") { pos, _ ->
            val list = pos[0] as? PyList ?: throw PythonRuntimeException("TypeError", "shuffle() expects a list")
            list.items.shuffle(rng)
            PyNone
        }
        members["sample"] = PyBuiltin("sample") { pos, _ ->
            val seq = asSequenceList(pos[0]).toMutableList()
            val k = asLong(pos[1]).toInt()
            if (k < 0 || k > seq.size) throw PythonRuntimeException("ValueError", "Sample larger than population")
            seq.shuffle(rng)
            PyList(seq.take(k).toMutableList())
        }
    }

    private fun buildJsonModule() = PyModule("json").apply {
        members["loads"] = PyBuiltin("loads") { pos, _ ->
            val s = (pos.firstOrNull() as? PyStr)?.value
                ?: throw PythonRuntimeException("TypeError", "json.loads expects a str")
            val trimmed = s.trim()
            try {
                when {
                    trimmed.startsWith("{") -> jsonObjToPy(JSONObject(trimmed))
                    trimmed.startsWith("[") -> jsonArrToPy(JSONArray(trimmed))
                    trimmed == "true" -> PyBool(true)
                    trimmed == "false" -> PyBool(false)
                    trimmed == "null" -> PyNone
                    trimmed.startsWith("\"") && trimmed.endsWith("\"") ->
                        PyStr(JSONArray("[$trimmed]").getString(0))
                    trimmed.contains(".") -> PyFloat(trimmed.toDouble())
                    else -> PyInt(trimmed.toLong())
                }
            } catch (e: Exception) {
                throw PythonRuntimeException("JSONDecodeError", e.message ?: "Invalid JSON")
            }
        }
        members["dumps"] = PyBuiltin("dumps") { pos, kw ->
            val obj = pos.firstOrNull() ?: throw PythonRuntimeException("TypeError", "json.dumps missing argument")
            val indent = (kw["indent"] as? PyInt)?.value?.toInt()
            val formatted = pyToJsonString(obj, indent, 0)
            PyStr(formatted)
        }
    }

    private fun buildOsPathModule() = PyModule("os.path").apply {
        members["join"] = PyBuiltin("join") { pos, _ ->
            val parts = pos.map { it.toDisplayString() }
            if (parts.isEmpty()) PyStr("") else PyStr(parts.reduce { acc, p -> File(acc, p).path })
        }
        members["basename"] = PyBuiltin("basename") { pos, _ ->
            PyStr(File(pos[0].toDisplayString()).name)
        }
        members["dirname"] = PyBuiltin("dirname") { pos, _ ->
            PyStr(File(pos[0].toDisplayString()).parent ?: "")
        }
        members["exists"] = PyBuiltin("exists") { pos, _ ->
            val f = runCatching { workspace.resolveSafe(pos[0].toDisplayString(), cwdProvider()) }.getOrNull()
            PyBool(f?.exists() == true)
        }
        members["isfile"] = PyBuiltin("isfile") { pos, _ ->
            val f = runCatching { workspace.resolveSafe(pos[0].toDisplayString(), cwdProvider()) }.getOrNull()
            PyBool(f?.isFile == true)
        }
        members["isdir"] = PyBuiltin("isdir") { pos, _ ->
            val f = runCatching { workspace.resolveSafe(pos[0].toDisplayString(), cwdProvider()) }.getOrNull()
            PyBool(f?.isDirectory == true)
        }
        members["abspath"] = PyBuiltin("abspath") { pos, _ ->
            val f = workspace.resolveSafe(pos[0].toDisplayString(), cwdProvider())
            PyStr("/" + workspace.relativePath(f).trimStart('.').trimStart('/'))
        }
    }

    private fun buildOsModule() = PyModule("os").apply {
        members["name"] = PyStr("posix")
        members["sep"] = PyStr("/")
        members["path"] = buildOsPathModule()
        val envDict = PyDict().apply {
            entries[PyStr("HOME")] = PyStr("/workspace")
            entries[PyStr("USER")] = PyStr("nimterm")
            entries[PyStr("SHELL")] = PyStr("/bin/sh")
            entries[PyStr("NVIDIA_API_BASE")] = PyStr(baseUrlProvider())
            val key = apiKeyProvider()
            if (key.isNotBlank()) entries[PyStr("NVIDIA_API_KEY")] = PyStr(key)
        }
        members["environ"] = envDict
        members["getcwd"] = PyBuiltin("getcwd") { _, _ ->
            val rel = workspace.relativePath(cwdProvider())
            PyStr(if (rel == ".") "/workspace" else "/workspace/$rel")
        }
        members["chdir"] = PyBuiltin("chdir") { pos, _ ->
            val target = workspace.resolveSafe(pos[0].toDisplayString(), cwdProvider())
            if (!target.exists() || !target.isDirectory) {
                throw PythonRuntimeException("FileNotFoundError", "No such directory: ${pos[0].toDisplayString()}")
            }
            cwdSetter(target)
            PyNone
        }
        members["listdir"] = PyBuiltin("listdir") { pos, _ ->
            val path = pos.firstOrNull()?.toDisplayString() ?: "."
            val items = workspace.listFiles(path, cwdProvider(), recursive = false)
            PyList(items.map { PyStr(it.name) }.toMutableList())
        }
        members["mkdir"] = PyBuiltin("mkdir") { pos, _ ->
            workspace.mkdir(pos[0].toDisplayString(), cwdProvider())
            PyNone
        }
        members["makedirs"] = PyBuiltin("makedirs") { pos, _ ->
            workspace.mkdir(pos[0].toDisplayString(), cwdProvider())
            PyNone
        }
        members["remove"] = PyBuiltin("remove") { pos, _ ->
            if (!workspace.deletePath(pos[0].toDisplayString(), cwdProvider(), recursive = false)) {
                throw PythonRuntimeException("FileNotFoundError", "File not found: ${pos[0].toDisplayString()}")
            }
            PyNone
        }
        members["unlink"] = members["remove"]!!
    }

    private fun buildSysModule() = PyModule("sys").apply {
        members["version"] = PyStr("3.12.0 (NimTerm Embedded Python)")
        members["platform"] = PyStr("android-linux")
        members["argv"] = PyList(scriptArgs.map { PyStr(it) }.toMutableList())
        members["path"] = PyList(mutableListOf(PyStr("/workspace")))
        members["exit"] = PyBuiltin("exit") { pos, _ ->
            val code = (pos.firstOrNull() as? PyInt)?.value?.toInt() ?: 0
            throw ExitSignal(code)
        }
    }

    private fun buildTimeModule() = PyModule("time").apply {
        members["time"] = PyBuiltin("time") { _, _ -> PyFloat(System.currentTimeMillis() / 1000.0) }
        members["monotonic"] = PyBuiltin("monotonic") { _, _ -> PyFloat(System.nanoTime() / 1_000_000_000.0) }
        members["sleep"] = PyBuiltin("sleep") { pos, _ ->
            val secs = asDouble(pos[0]).coerceAtLeast(0.0)
            delay((secs * 1000).toLong())
            PyNone
        }
        members["strftime"] = PyBuiltin("strftime") { pos, _ ->
            val fmt = pos.firstOrNull()?.toDisplayString() ?: "%Y-%m-%d %H:%M:%S"
            PyStr(formatPyDateTime(Date(), fmt))
        }
    }

    private fun buildDatetimeModule() = PyModule("datetime").apply {
        val dtClass = PyClass("datetime").apply {
            methods["now"] = PyBuiltin("now") { _, _ ->
                makeDateTimeInstance(this, Date())
            }
            methods["today"] = methods["now"]!!
        }
        val dateClass = PyClass("date").apply {
            methods["today"] = PyBuiltin("today") { _, _ ->
                makeDateTimeInstance(dtClass, Date())
            }
        }
        members["datetime"] = dtClass
        members["date"] = dateClass
    }

    private fun makeDateTimeInstance(dtClass: PyClass, date: Date): PyInstance {
        val cal = java.util.Calendar.getInstance().apply { time = date }
        return PyInstance(dtClass).apply {
            fields["year"] = PyInt(cal.get(java.util.Calendar.YEAR).toLong())
            fields["month"] = PyInt((cal.get(java.util.Calendar.MONTH) + 1).toLong())
            fields["day"] = PyInt(cal.get(java.util.Calendar.DAY_OF_MONTH).toLong())
            fields["hour"] = PyInt(cal.get(java.util.Calendar.HOUR_OF_DAY).toLong())
            fields["minute"] = PyInt(cal.get(java.util.Calendar.MINUTE).toLong())
            fields["second"] = PyInt(cal.get(java.util.Calendar.SECOND).toLong())
            fields["strftime"] = PyBuiltin("strftime") { pos, _ ->
                val fmt = pos.firstOrNull()?.toDisplayString() ?: "%Y-%m-%d %H:%M:%S"
                PyStr(formatPyDateTime(date, fmt))
            }
            fields["isoformat"] = PyBuiltin("isoformat") { _, _ ->
                PyStr(formatPyDateTime(date, "%Y-%m-%dT%H:%M:%S"))
            }
        }
    }

    private fun buildReModule() = PyModule("re").apply {
        val matchClass = PyClass("Match")
        fun makeMatch(m: MatchResult): PyInstance = PyInstance(matchClass).apply {
            fields["group"] = PyBuiltin("group") { pos, _ ->
                val idx = (pos.firstOrNull() as? PyInt)?.value?.toInt() ?: 0
                PyStr(m.groupValues.getOrElse(idx) { "" })
            }
            fields["groups"] = PyBuiltin("groups") { _, _ ->
                PyTuple(m.groupValues.drop(1).map { PyStr(it) })
            }
            fields["start"] = PyBuiltin("start") { _, _ -> PyInt(m.range.first.toLong()) }
            fields["end"] = PyBuiltin("end") { _, _ -> PyInt((m.range.last + 1).toLong()) }
        }

        members["search"] = PyBuiltin("search") { pos, _ ->
            val regex = Regex(pos[0].toDisplayString())
            val match = regex.find(pos[1].toDisplayString())
            if (match != null) makeMatch(match) else PyNone
        }
        members["match"] = PyBuiltin("match") { pos, _ ->
            val regex = Regex(pos[0].toDisplayString())
            val match = regex.matchAt(pos[1].toDisplayString(), 0)
            if (match != null) makeMatch(match) else PyNone
        }
        members["findall"] = PyBuiltin("findall") { pos, _ ->
            val regex = Regex(pos[0].toDisplayString())
            val items = regex.findAll(pos[1].toDisplayString()).map { m ->
                if (m.groupValues.size > 2) PyTuple(m.groupValues.drop(1).map { PyStr(it) })
                else if (m.groupValues.size == 2) PyStr(m.groupValues[1])
                else PyStr(m.value)
            }.toMutableList()
            PyList(items)
        }
        members["sub"] = PyBuiltin("sub") { pos, _ ->
            val regex = Regex(pos[0].toDisplayString())
            PyStr(regex.replace(pos[2].toDisplayString(), pos[1].toDisplayString()))
        }
        members["split"] = PyBuiltin("split") { pos, _ ->
            val regex = Regex(pos[0].toDisplayString())
            PyList(regex.split(pos[1].toDisplayString()).map { PyStr(it) }.toMutableList())
        }
    }

    private fun buildCollectionsModule() = PyModule("collections").apply {
        members["Counter"] = PyBuiltin("Counter") { pos, _ ->
            val dict = PyDict()
            if (pos.isNotEmpty()) {
                for (item in asSequenceList(pos[0])) {
                    val cur = (dict.entries[item] as? PyInt)?.value ?: 0L
                    dict.entries[item] = PyInt(cur + 1L)
                }
            }
            dict
        }
        members["defaultdict"] = PyBuiltin("defaultdict") { _, _ -> PyDict() }
    }

    private fun buildPathlibModule() = PyModule("pathlib").apply {
        val pathClass = PyClass("Path")
        fun makePath(raw: String): PyInstance = PyInstance(pathClass).apply {
            val file = workspace.resolveSafe(raw, cwdProvider())
            val rel = workspace.relativePath(file)
            fields["name"] = PyStr(file.name)
            fields["exists"] = PyBuiltin("exists") { _, _ -> PyBool(file.exists()) }
            fields["is_file"] = PyBuiltin("is_file") { _, _ -> PyBool(file.isFile) }
            fields["is_dir"] = PyBuiltin("is_dir") { _, _ -> PyBool(file.isDirectory) }
            fields["read_text"] = PyBuiltin("read_text") { _, _ ->
                if (!file.exists()) throw PythonRuntimeException("FileNotFoundError", "No such file: $rel")
                PyStr(file.readText())
            }
            fields["write_text"] = PyBuiltin("write_text") { pos, _ ->
                val content = pos[0].toDisplayString()
                workspace.writeFile(rel, content, workspace.rootDir)
                PyInt(content.length.toLong())
            }
            fields["iterdir"] = PyBuiltin("iterdir") { _, _ ->
                val children = file.listFiles()?.map { makePath(workspace.relativePath(it)) } ?: emptyList()
                PyList(children.toMutableList())
            }
        }
        members["Path"] = PyBuiltin("Path") { pos, _ ->
            makePath(pos.firstOrNull()?.toDisplayString() ?: ".")
        }
    }

    private fun buildStatisticsModule() = PyModule("statistics").apply {
        members["mean"] = PyBuiltin("mean") { pos, _ ->
            val nums = asSequenceList(pos[0]).map { asDouble(it) }
            if (nums.isEmpty()) throw PythonRuntimeException("StatisticsError", "mean requires at least one data point")
            PyFloat(nums.average())
        }
        members["median"] = PyBuiltin("median") { pos, _ ->
            val nums = asSequenceList(pos[0]).map { asDouble(it) }.sorted()
            if (nums.isEmpty()) throw PythonRuntimeException("StatisticsError", "no median for empty data")
            val mid = nums.size / 2
            PyFloat(if (nums.size % 2 == 1) nums[mid] else (nums[mid - 1] + nums[mid]) / 2.0)
        }
    }

    private fun buildStringModule() = PyModule("string").apply {
        members["ascii_lowercase"] = PyStr("abcdefghijklmnopqrstuvwxyz")
        members["ascii_uppercase"] = PyStr("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        members["ascii_letters"] = PyStr("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        members["digits"] = PyStr("0123456789")
        members["punctuation"] = PyStr("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")
    }

    private fun buildUrllibPackage() = PyModule("urllib").apply {
        members["request"] = buildUrllibRequestModule()
    }

    private fun buildUrllibRequestModule() = PyModule("urllib.request").apply {
        val respClass = PyClass("HTTPResponse")
        members["urlopen"] = PyBuiltin("urlopen") { pos, _ ->
            val url = pos[0].toDisplayString()
            val bodyBytes = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        throw PythonRuntimeException("URLError", "HTTP ${res.code} fetching $url")
                    }
                    res.body?.string().orEmpty()
                }
            }
            PyInstance(respClass).apply {
                fields["status"] = PyInt(200)
                fields["read"] = PyBuiltin("read") { _, _ -> PyStr(bodyBytes) }
                fields["decode"] = PyBuiltin("decode") { _, _ -> PyStr(bodyBytes) }
            }
        }
    }

    private fun buildNvidiaModule() = PyModule("nvidia").apply {
        members["model"] = PyBuiltin("model") { _, _ -> PyStr(activeModelProvider()) }
        members["base_url"] = PyBuiltin("base_url") { _, _ -> PyStr(baseUrlProvider()) }
        members["models"] = PyBuiltin("models") { _, _ ->
            val res = nimClient.fetchLiveModels(apiKeyProvider(), baseUrlProvider())
            val list = res.getOrElse { throw PythonRuntimeException("RuntimeError", it.message ?: "Failed") }
            PyList(list.map { PyStr(it.id) }.toMutableList())
        }
        members["chat"] = PyBuiltin("chat") { pos, kw ->
            val prompt = pos.firstOrNull()?.toDisplayString()
                ?: kw["prompt"]?.toDisplayString()
                ?: throw PythonRuntimeException("TypeError", "nvidia.chat(prompt, model=...) requires prompt")
            val model = kw["model"]?.toDisplayString() ?: activeModelProvider()
            val temp = kw["temperature"]?.let { asDouble(it) } ?: 0.2
            val system = kw["system"]?.toDisplayString()
            val msgs = buildList {
                if (!system.isNullOrBlank()) add(ApiChatMessage(role = "system", content = system))
                add(ApiChatMessage(role = "user", content = prompt))
            }
            val result = nimClient.completeChat(
                apiKey = apiKeyProvider(),
                model = model,
                messages = msgs,
                tools = null,
                temperature = temp,
                maxTokens = 2048,
                baseUrl = baseUrlProvider(),
                onEvent = {}
            )
            val completed = result.getOrElse {
                throw PythonRuntimeException("NvidiaAPIError", it.message ?: "Request failed")
            }
            PyStr(completed.content)
        }
    }

    private fun buildLiteLlmModule() = PyModule("litellm").apply {
        val choiceClass = PyClass("Choice")
        val msgClass = PyClass("Message")
        val respClass = PyClass("ModelResponse")

        members["completion"] = PyBuiltin("completion") { _, kw ->
            val model = kw["model"]?.toDisplayString() ?: activeModelProvider()
            val rawMsgs = kw["messages"] as? PyList
                ?: throw PythonRuntimeException("TypeError", "litellm.completion() requires messages=[...]")
            val apiMsgs = rawMsgs.items.map { item ->
                val d = item as? PyDict ?: throw PythonRuntimeException("TypeError", "Each message must be a dict")
                val role = d.entries[PyStr("role")]?.toDisplayString() ?: "user"
                val content = d.entries[PyStr("content")]?.toDisplayString().orEmpty()
                ApiChatMessage(role = role, content = content)
            }
            val temp = kw["temperature"]?.let { asDouble(it) } ?: 0.2
            val maxTok = kw["max_tokens"]?.let { asLong(it).toInt() } ?: 2048
            val apiBase = kw["api_base"]?.toDisplayString() ?: baseUrlProvider()
            val apiKey = kw["api_key"]?.toDisplayString() ?: apiKeyProvider()

            val result = nimClient.completeChat(
                apiKey = apiKey,
                model = model.removePrefix("nvidia_nim/").removePrefix("openai/"),
                messages = apiMsgs,
                tools = null,
                temperature = temp,
                maxTokens = maxTok,
                baseUrl = apiBase,
                onEvent = {}
            )
            val completed = result.getOrElse {
                throw PythonRuntimeException("LiteLLMError", it.message ?: "Completion failed")
            }
            val msgInst = PyInstance(msgClass).apply {
                fields["role"] = PyStr("assistant")
                fields["content"] = PyStr(completed.content)
            }
            val choiceInst = PyInstance(choiceClass).apply {
                fields["message"] = msgInst
                fields["index"] = PyInt(0)
            }
            PyInstance(respClass).apply {
                fields["model"] = PyStr(model)
                fields["choices"] = PyList(mutableListOf(choiceInst))
            }
        }
    }

    private fun formatPyDateTime(date: Date, pyFmt: String): String {
        val javaFmt = pyFmt
            .replace("%Y", "yyyy")
            .replace("%m", "MM")
            .replace("%d", "dd")
            .replace("%H", "HH")
            .replace("%M", "mm")
            .replace("%S", "ss")
        return SimpleDateFormat(javaFmt, Locale.US).format(date)
    }

    private fun jsonObjToPy(obj: JSONObject): PyDict {
        val dict = PyDict()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            dict.entries[PyStr(k)] = jsonAnyToPy(obj.get(k))
        }
        return dict
    }

    private fun jsonArrToPy(arr: JSONArray): PyList {
        val list = mutableListOf<PyValue>()
        for (i in 0 until arr.length()) {
            list += jsonAnyToPy(arr.get(i))
        }
        return PyList(list)
    }

    private fun jsonAnyToPy(v: Any?): PyValue = when (v) {
        null, JSONObject.NULL -> PyNone
        is Boolean -> PyBool(v)
        is Int -> PyInt(v.toLong())
        is Long -> PyInt(v)
        is Double -> PyFloat(v)
        is Float -> PyFloat(v.toDouble())
        is String -> PyStr(v)
        is JSONObject -> jsonObjToPy(v)
        is JSONArray -> jsonArrToPy(v)
        else -> PyStr(v.toString())
    }

    private fun pyToJsonString(v: PyValue, indent: Int?, depth: Int): String {
        val pad = if (indent != null) " ".repeat(indent * (depth + 1)) else ""
        val closePad = if (indent != null) " ".repeat(indent * depth) else ""
        val sep = if (indent != null) ",\n" else ", "
        val kvSep = if (indent != null) ": " else ": "
        return when (v) {
            is PyNone -> "null"
            is PyBool -> if (v.value) "true" else "false"
            is PyInt -> v.value.toString()
            is PyFloat -> v.toReprString()
            is PyStr -> JSONObject.quote(v.value)
            is PyList -> {
                if (v.items.isEmpty()) "[]"
                else if (indent != null) "[\n" + v.items.joinToString(sep) { "$pad${pyToJsonString(it, indent, depth + 1)}" } + "\n$closePad]"
                else v.items.joinToString(", ", "[", "]") { pyToJsonString(it, null, depth) }
            }
            is PyTuple -> {
                if (v.items.isEmpty()) "[]"
                else v.items.joinToString(", ", "[", "]") { pyToJsonString(it, indent, depth) }
            }
            is PyDict -> {
                if (v.entries.isEmpty()) "{}"
                else if (indent != null) "{\n" + v.entries.entries.joinToString(sep) {
                    "$pad${JSONObject.quote(it.key.toDisplayString())}$kvSep${pyToJsonString(it.value, indent, depth + 1)}"
                } + "\n$closePad}"
                else v.entries.entries.joinToString(", ", "{", "}") {
                    "${JSONObject.quote(it.key.toDisplayString())}: ${pyToJsonString(it.value, null, depth)}"
                }
            }
            else -> JSONObject.quote(v.toDisplayString())
        }
    }

    companion object {
        fun asDouble(v: PyValue): Double = when (v) {
            is PyInt -> v.value.toDouble()
            is PyFloat -> v.value
            is PyBool -> if (v.value) 1.0 else 0.0
            else -> throw PythonRuntimeException("TypeError", "Expected number, got ${v.typeName()}")
        }

        fun asLong(v: PyValue): Long = when (v) {
            is PyInt -> v.value
            is PyBool -> if (v.value) 1L else 0L
            is PyFloat -> v.value.toLong()
            else -> throw PythonRuntimeException("TypeError", "Expected int, got ${v.typeName()}")
        }

        fun asSequenceList(v: PyValue): List<PyValue> = when (v) {
            is PyList -> v.items
            is PyTuple -> v.items
            is PySet -> v.items.toList()
            is PyStr -> v.value.map { PyStr(it.toString()) }
            is PyDict -> v.entries.keys.toList()
            else -> throw PythonRuntimeException("TypeError", "'${v.typeName()}' object is not iterable")
        }
    }
}
