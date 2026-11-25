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
        list.filter { it.enabled }.forEach { sourceManager.load(it) }
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
