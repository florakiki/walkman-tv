package com.walkman.tv.data.store

import android.content.Context
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Stores imported custom-source scripts and (re)loads enabled ones into [SourceManager]. */
class ScriptStore(private val context: Context, private val sourceManager: SourceManager) {
    private val store = JsonStore(
        File(context.filesDir, "scripts.json"),
        ListSerializer(UserScript.serializer()),
        emptyList(),
    )

    private val _scripts = MutableStateFlow<List<UserScript>>(emptyList())
    val scripts: StateFlow<List<UserScript>> = _scripts.asStateFlow()

    /** Load persisted scripts and start the enabled ones. On the very first launch
     *  (no persisted scripts file yet) the bundled Ki.js is seeded as a built-in
     *  source so the app plays out of the box; it can still be disabled / removed
     *  in Settings like any imported source and is never re-seeded afterwards.
     *  Users with an existing scripts file are untouched.
     *
     *  Debug-only: the bundled source lives in src/debug/assets and is seeded only
     *  on debuggable (debug) builds — release builds ship no
     *  source and never auto-import; release users import sources manually.
     *  The bundled file is KITV-AES encrypted (see [KitvAes]); it is decrypted in
     *  memory here and the plaintext is never written back to disk. */
    suspend fun loadAll() {
        val scriptsFile = File(context.filesDir, "scripts.json")
        val list = withContext(Dispatchers.IO) { store.load() }
        _scripts.value = list
        if (!scriptsFile.exists()) {
            // 内置源只进 debug 测试版：Ki.js 在 src/debug/assets 下，release 包里没有这个
            // 文件；这里再用 debuggable 门住（debug 版默认 debuggable，release 不是），
            // release 即使有残留也不会自动导入。正式版用户自己手动导源
            // （import() 不受影响，明文源照常能导）。不用 BuildConfig：本项目没开
            // buildConfig 生成（见 UpdateManager 的注释）。
            val debuggable = (context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                runCatching {
                    val raw = context.assets.open("script/Ki.js").bufferedReader().use { it.readText() }
                    // 加密版（KITV-AES: 开头）在内存里解出来再导入；明文按原样走。解密后的
                    // 明文只留在内存，不写回磁盘。
                    import(KitvAes.decryptIfPrefixed(raw))
                }.onFailure { android.util.Log.w("ScriptStore", "bundled Ki.js seed failed", it) }
            }
            return
        }
        // 已有 scripts.json：包里带的内置源可能比已存的上一版新（电视上次启动种的是旧版），
        // 见 upgradeBundledIfNeeded。升级失败时返回原列表，旧版不动。
        val effective = runCatching { upgradeBundledIfNeeded(list) }.getOrElse {
            android.util.Log.w("ScriptStore", "bundled Ki.js upgrade failed", it)
            list
        }
        effective.filter { it.enabled }.forEach { sourceManager.load(it) }
    }

    /**
     * 内置源升级：包里带的 Ki.js（src/debug/assets，下个版本换了新脚本）比已存的上一版
     * 新时，把已存的上一版内置源换成包里新版。只认上一版内置源（@author 是 hello world
     * 或 "KITV | 安妮 | 星海" 的那个），别的用户脚本一律不动；用户自己删掉内置源后不再补种。
     *
     * 换法：新版沿用旧 id（SourceManager 按 id 去重，位置、启用状态都原地不动），
     * 先验证新版能加载（QuickJS 里跑起来、拿到 capabilities），成功了再换；
     * 旧版原来是启用状态才进 runtime，原来是禁用就不进，只换列表里的记录。
     * 新版和已存的一样（已经升过了）就不动。返回升级后的有效列表。
     */
    private suspend fun upgradeBundledIfNeeded(list: List<UserScript>): List<UserScript> {
        // 第十八轮：内置源的 author 早就不是 "hello world" 了（现在是 "KITV | 安妮 | 星海"），
        // 只认 "hello world" 的话升级永远匹配不到，包里换了新 Ki.js 电视上也还是旧版在跑。
        // 两个都认（老的是 "hello world"，现在的是 "KITV | 安妮 | 星海"）。
        val old = list.firstOrNull {
            it.author == "hello world" || it.author == "KITV | 安妮 | 星海"
        } ?: return list
        // 内置源只进 debug 测试版：和上面首次种源同一道门，release 包里没有这个文件。
        val debuggable = (context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return list
        val raw = context.assets.open("script/Ki.js").bufferedReader().use { it.readText() }
        val bundled = KitvAes.decryptIfPrefixed(raw)
        if (bundled == old.rawScript) return list // 已经是最新，不动
        val meta = parseHeader(bundled)
        val upgraded = UserScript(
            id = old.id, // 沿用旧 id：位置、启用状态都原地不动
            name = meta["name"] ?: "未命名脚本",
            description = meta["description"] ?: "",
            version = meta["version"] ?: "",
            author = meta["author"] ?: "",
            homepage = meta["homepage"] ?: "",
            rawScript = bundled,
            enabled = old.enabled,
        )
        if (old.enabled) {
            // 先验证新版能加载，成功了再换；失败留着旧版不动（loadAll 随后会按原列表加载旧版）。
            // load() 按 id 先过滤再加：成功时旧 runtime 已被新版顶掉，不用再卸。
            val result = sourceManager.load(upgraded)
            if (result.isFailure) {
                android.util.Log.w("ScriptStore", "bundled Ki.js upgrade load failed, keeping old")
                return list
            }
        }
        val updated = list.map { if (it.id == old.id) upgraded else it }
        _scripts.value = updated
        persist()
        android.util.Log.i("ScriptStore", "bundled Ki.js upgraded: ${old.name} -> ${upgraded.name}")
        return updated
    }

    /** Result of [import]: [replaced] is the older copy of the same script (same name + author)
     *  that this import updated in place, or null when it was added as a new source. */
    data class Imported(val script: UserScript, val replaced: UserScript?) {
        val message: String
            get() = if (replaced == null) "已添加音源：${script.name}"
            else "已更新音源：${script.name}（替换了同名旧版本${replaced.version.takeIf { it.isNotBlank() }?.let { " v$it" } ?: ""}）"
    }

    /**
     * Import a raw script: parse its header, load it, persist. Several sources can coexist; only
     * a script with the same name AND author counts as a new version of an existing one and
     * replaces it (keeping its place in the list). The old runtime is unloaded — previously it
     * stayed loaded in [SourceManager] invisibly — and same-named scripts by different authors
     * are kept side by side instead of silently overwriting each other.
     */
    suspend fun import(raw: String): Result<Imported> {
        val meta = parseHeader(raw)
        val script = UserScript(
            name = meta["name"] ?: "未命名脚本",
            description = meta["description"] ?: "",
            version = meta["version"] ?: "",
            author = meta["author"] ?: "",
            homepage = meta["homepage"] ?: "",
            rawScript = raw,
            enabled = true,
        )
        val old = _scripts.value.firstOrNull { it.name == script.name && it.author == script.author }
        val result = sourceManager.load(script)
        return result.fold(
            onSuccess = {
                if (old != null) {
                    sourceManager.unload(old.id)
                    _scripts.value = _scripts.value.map { if (it.id == old.id) script else it }
                } else {
                    _scripts.value = _scripts.value + script
                }
                persist()
                Result.success(Imported(script, old))
            },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val script = _scripts.value.firstOrNull { it.id == id } ?: return
        _scripts.value = _scripts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (enabled) sourceManager.load(script.copy(enabled = true)) else sourceManager.unload(id)
        persist()
    }

    suspend fun remove(id: String) {
        sourceManager.unload(id)
        _scripts.value = _scripts.value.filter { it.id != id }
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) { store.save(_scripts.value) }

    companion object {
        private val headerBlock = Regex("""/\*[\s\S]*?\*/""")
        private val tagLine = Regex("""@(\w+)\s+(.+)""")

        /** Parse the leading KDoc-style header of an lx v4 script (@name / @version / etc). */
        fun parseHeader(raw: String): Map<String, String> {
            val block = headerBlock.find(raw)?.value ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            tagLine.findAll(block).forEach { m ->
                out[m.groupValues[1].lowercase()] = m.groupValues[2].trim().removeSuffix("*").trim()
            }
            return out
        }
    }
}
