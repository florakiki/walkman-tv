package com.walkman.tv.source.js

import android.util.Log
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.ScriptCapabilities
import com.walkman.tv.data.model.SourceCapability
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.UserScript
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Runs a single lx-music v4 user script in a QuickJS context. Ports `userApi/QuickJS.java`
 * (native env + preload contract) and the async request/init contract from iOS `JSRuntime.swift`,
 * but routes results to Kotlin coroutines instead of RN events.
 *
 * QuickJS is single-threaded: every context interaction runs on [jsDispatcher].
 */
class JsScriptRuntime(
    val script: UserScript,
    private val preload: String,
    private val http: ScriptHttpClient,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "lx-js-${script.id.take(8)}") }
    private val jsDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + jsDispatcher)
    private val key = UUID.randomUUID().toString()

    @Volatile private var ctx: QuickJSContext? = null
    @Volatile var inited = false
        private set
    @Volatile var capabilities = ScriptCapabilities()
        private set

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private var initDeferred: CompletableDeferred<ScriptCapabilities>? = null

    open class ScriptException(message: String) : Exception(message)
    /** The script never answered (vs. answered with an error) — retrying it at another quality
     *  tier would just wait out the timeout again. */
    class ScriptTimeoutException(message: String) : ScriptException(message)

    /** Create the context, evaluate preload + script, and wait for the script to report `init`. */
    suspend fun load(timeoutMs: Long = 10_000): ScriptCapabilities {
        val deferred = CompletableDeferred<ScriptCapabilities>()
        initDeferred = deferred
        try {
            // 第十七轮：evaluate 也要限时。JS 线程卡死时 withContext 会永远等下去，
            // 后面的 10 秒超时根本跑不到（它只包着 deferred.await()），
            // 启动时 bootstrap 会一直挂在 loadAll() 里、脚本永远加载不上。
            withTimeout(LOAD_EVAL_TIMEOUT_MS) {
                withContext(jsDispatcher) {
                    val c = QuickJSContext.create()
                    ctx = c
                    c.setConsole(object : QuickJSContext.Console {
                        override fun log(info: String?) { Log.d(TAG, info ?: "") }
                        override fun info(info: String?) { Log.i(TAG, info ?: "") }
                        override fun warn(info: String?) { Log.w(TAG, info ?: "") }
                        override fun error(info: String?) { Log.e(TAG, info ?: "") }
                    })
                    installGlobals(c)
                    c.evaluate(preload)
                    c.globalObject.getJSFunction("lx_setup").call(
                        key, script.id, script.name, script.description,
                        script.version, script.author, script.homepage, script.rawScript,
                    )
                    // v4 source is an IIFE — evaluate directly (mirrors QuickJS.java loadScript).
                    try {
                        c.evaluate(script.rawScript)
                    } catch (e: Exception) {
                        // A throw after a successful init is non-fatal; otherwise fail init.
                        if (!inited) failInit(e.message ?: "脚本执行错误")
                    }
                    Unit // force lambda return type to Unit; avoids if-without-else type-check error
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ScriptException("脚本加载超时（JS 线程无响应）")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw ScriptException("脚本响应超时")
        }
    }

    /** Ask the script to perform an action (musicUrl / lyric / pic). */
    suspend fun requestAction(source: SourceID, action: String, info: JSONObject, timeoutMs: Long = 25_000): Any? {
        if (!inited) throw ScriptException("脚本尚未初始化")
        val requestKey = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Any?>()
        pending[requestKey] = deferred
        val data = JSONObject()
            .put("source", source.key)
            .put("action", action)
            .put("info", info)
        val payload = JSONObject().put("requestKey", requestKey).put("data", data)
        // 第十七轮：发给脚本这一步也要限时。JS 线程卡死时 withContext(jsDispatcher)
        // 会永远等下去，后面的 25 秒超时根本跑不到（它只包着 deferred.await()），
        // 解析就永远不完成也不失败，UI 一直卡在“正在获取播放地址…”。
        // 5 秒还没接手就当脚本线程死了，调用方（SourceManager）会换一个新 runtime 重试。
        val sent = withTimeoutOrNull(DISPATCH_TIMEOUT_MS) {
            withContext(jsDispatcher) { sendToScript("request", payload.toString()) }
            true
        }
        if (sent != true) {
            pending.remove(requestKey)
            throw ScriptTimeoutException("脚本线程无响应")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw ScriptTimeoutException("脚本调用超时")
        } finally {
            pending.remove(requestKey)
        }
    }

    fun destroy() {
        scope.launch {
            runCatching { ctx?.destroy() }
            ctx = null
        }
        // Allow the destroy task to run, then stop the thread.
        executor.shutdown()
    }

    // MARK: - native env

    private fun installGlobals(c: QuickJSContext) {
        val g = c.globalObject
        // Every JSCallFunction body is wrapped in runCatching: these lambdas are invoked from
        // QuickJS via JNI, and a Java exception escaping through that boundary (e.g. a bad
        // script passing a non-string arg into the casts below) can take down the process
        // instead of surfacing as a script error. Crypto helpers fall back to "" — same value
        // CryptoBridge itself returns on failure.
        g.setProperty("__lx_native_call__", JSCallFunction { args ->
            runCatching {
                if (args.isNotEmpty() && args[0] == key) {
                    val action = args.getOrNull(1) as? String ?: return@runCatching
                    val data = args.getOrNull(2) as? String
                    handleScriptCall(action, data)
                }
            }.onFailure { Log.e(TAG, "__lx_native_call__ failed: ${it.message}") }
            null
        })
        g.setProperty("__lx_native_call__utils_str2b64", JSCallFunction { a ->
            runCatching { CryptoBridge.str2b64(a[0] as String) }.getOrDefault("")
        })
        g.setProperty("__lx_native_call__utils_b642buf", JSCallFunction { a ->
            runCatching { CryptoBridge.b642buf(a[0] as String) }.getOrDefault("[]")
        })
        g.setProperty("__lx_native_call__utils_str2md5", JSCallFunction { a ->
            runCatching { CryptoBridge.str2md5(a[0] as String) }.getOrDefault("")
        })
        g.setProperty("__lx_native_call__utils_aes_encrypt", JSCallFunction { a ->
            runCatching {
                CryptoBridge.aesEncrypt(a[0] as String, a[1] as String, a[2] as String, a[3] as String)
            }.getOrDefault("")
        })
        g.setProperty("__lx_native_call__utils_rsa_encrypt", JSCallFunction { a ->
            runCatching {
                CryptoBridge.rsaEncrypt(a[0] as String, a[1] as String, a[2] as String)
            }.getOrDefault("")
        })
        g.setProperty("__lx_native_call__set_timeout", JSCallFunction { a ->
            runCatching {
                val id = (a[0] as Number).toInt()
                val ms = (a[1] as Number).toLong()
                scope.launch {
                    delay(ms)
                    sendToScript("__set_timeout__", id.toString())
                }
            }.onFailure { Log.e(TAG, "set_timeout failed: ${it.message}") }
            null
        })
    }

    /** Call the script's `__lx_native__(key, action[, dataString])`. Must run on [jsDispatcher]. */
    private fun sendToScript(action: String, dataString: String?) {
        val c = ctx ?: return
        try {
            val fn = c.globalObject.getJSFunction("__lx_native__")
            if (dataString == null) fn.call(key, action) else fn.call(key, action, dataString)
        } catch (e: Exception) {
            Log.e(TAG, "sendToScript($action) failed: ${e.message}")
        }
    }

    // MARK: - script → host

    private fun handleScriptCall(action: String, rawData: String?) {
        when (action) {
            "init" -> handleInit(rawData)
            "request" -> handleHttpRequest(rawData)
            "cancelRequest" -> rawData?.let { http.cancel(stripQuotes(it)) }
            "response" -> handleScriptResponse(rawData)
            "showUpdateAlert" -> Log.i(TAG, "updateAlert: $rawData")
            else -> Log.d(TAG, "unknown script call: $action")
        }
    }

    private fun handleInit(rawData: String?) {
        val obj = parseObj(rawData)
        if (obj == null) {
            failInit("init payload not an object"); return
        }
        if (obj.has("status") && !obj.optBoolean("status", true)) {
            failInit(obj.optString("errorMessage", "unknown")); return
        }
        val sources = mutableMapOf<SourceID, SourceCapability>()
        obj.optJSONObject("info")?.optJSONObject("sources")?.let { srcObj ->
            srcObj.keys().forEach { k ->
                val src = SourceID.fromKey(k) ?: return@forEach
                val v = srcObj.optJSONObject(k) ?: return@forEach
                val actions = v.optJSONArray("actions")?.let { arr ->
                    List(arr.length()) { arr.optString(it) }
                } ?: emptyList()
                val qualities = v.optJSONArray("qualitys")?.let { arr ->
                    (0 until arr.length()).mapNotNull { Quality.fromKey(arr.optString(it)) }
                } ?: emptyList()
                sources[src] = SourceCapability(v.optString("type", "music"), actions, qualities)
            }
        }
        capabilities = ScriptCapabilities(sources)
        inited = true
        initDeferred?.complete(capabilities)
        initDeferred = null
    }

    private fun failInit(message: String) {
        inited = true
        initDeferred?.completeExceptionally(ScriptException("加载脚本失败: $message"))
        initDeferred = null
    }

    private fun handleHttpRequest(rawData: String?) {
        val obj = parseObj(rawData) ?: return
        val requestKey = obj.optString("requestKey").ifEmpty { return }
        val url = obj.optString("url").ifEmpty { return }
        val options = obj.optJSONObject("options") ?: JSONObject()
        http.send(requestKey, url, options) { error, response ->
            // Completes on an OkHttp thread — hop back to the JS thread to call the script.
            scope.launch {
                val payload = JSONObject().put("requestKey", requestKey)
                if (error != null || response == null) {
                    payload.put("error", error ?: "no response")
                    payload.put("response", JSONObject.NULL)
                } else {
                    payload.put("error", JSONObject.NULL)
                    payload.put(
                        "response",
                        JSONObject()
                            .put("statusCode", response.statusCode)
                            .put("statusMessage", response.statusMessage)
                            .put("headers", JSONObject(response.headers as Map<*, *>))
                            .put("body", response.body),
                    )
                }
                sendToScript("response", payload.toString())
            }
        }
    }

    private fun handleScriptResponse(rawData: String?) {
        val obj = parseObj(rawData) ?: return
        val requestKey = obj.optString("requestKey").ifEmpty { return }
        val deferred = pending.remove(requestKey) ?: return
        if (obj.optBoolean("status", false)) {
            deferred.complete(if (obj.isNull("result")) null else obj.get("result"))
        } else {
            deferred.completeExceptionally(ScriptException(obj.optString("errorMessage", "unknown error")))
        }
    }

    private fun parseObj(raw: String?): JSONObject? =
        try {
            if (raw.isNullOrEmpty()) null else JSONObject(raw)
        } catch (e: Exception) {
            null
        }

    private fun stripQuotes(s: String): String =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

    companion object {
        private const val TAG = "JsScriptRuntime"
        // 第十七轮：这两个超时包的是“把任务发到 JS 线程”这一步本身。
        // 25 秒/10 秒超时只包着 deferred.await()（等脚本回信），
        // JS 线程卡死时 withContext(jsDispatcher) 会永远等下去、后面的超时根本跑不到，
        // 解析就永远不完成也不失败，UI 一直卡在“正在获取播放地址…”。
        /** 发给脚本的任务 5 秒还没被 JS 线程接手 → 当脚本线程死了。 */
        const val DISPATCH_TIMEOUT_MS = 5_000L
        /** 加载脚本（evaluate preload + lx_setup + 脚本）15 秒没跑完 → 当 JS 线程死了。 */
        const val LOAD_EVAL_TIMEOUT_MS = 15_000L
    }
}
