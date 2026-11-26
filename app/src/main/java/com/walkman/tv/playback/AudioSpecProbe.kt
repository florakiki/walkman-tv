package com.walkman.tv.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream

/**
 * Probes a played audio URL's file header to recover the **actual** codec parameters,
 * independent of what the source script claimed. Spec docs/audio-quality-spec-android-tv.md §5.
 *
 * Strategy:
 *  - Remote: HTTP `Range: bytes=0-65535`. Some CDNs ignore Range and return the whole file,
 *    so we hand-truncate to `count` bytes.
 *  - Local file: seek+read directly.
 *  - Parse by magic:
 *      `fLaC`  → FLAC STREAMINFO bit-unpack
 *      `ID3`   → skip ID3v2 tag (length syncsafe), fetch from `10+size` if it overshoots the
 *                64 KB buffer, then continue at the next MPEG sync
 *      anything starting with `0xFF 0xEx` → bare MP3 sync scan
 *  - Anything else (m4a, encrypted .mgg, …) → return null, never throw.
 *
 * UI surfaces the result only on success; failures are silently logged.
 *
 * [validate] (2026-11-26《相亲相爱》原生闪退) is the pre-play gate that reuses the same
 * header fetch: a URL whose first bytes are not audio — an error page served as a file,
 * a truncated download, a FLAC whose STREAMINFO looks fine but whose body has no valid
 * frames — must never reach the native decoder (SIGSEGV, no callback, no log). Anything
 * the gate cannot prove is dirty is let through: an inconclusive check must never block
 * a playable song.
 */
class AudioSpecProbe(private val http: OkHttpClient) {

    /** Pre-play verdict for a resolved URL. [Dirty] is proven-not-audio; [Unknown] means
     *  the check could not decide (fetch failed, Range refused, unrecognised binary) and
     *  the caller must let the URL through. */
    sealed interface HeadVerdict {
        data object Valid : HeadVerdict
        data class Dirty(val reason: String) : HeadVerdict
        data class Unknown(val reason: String) : HeadVerdict
    }

    /**
     * Validate a play URL's head bytes before it is handed to the decoder.
     * Never throws; any fetch/parse failure degrades to [HeadVerdict.Unknown] (allow).
     * [durationSec] is the track's known length in seconds (null = skip the size check).
     */
    suspend fun validate(url: String, durationSec: Int?): HeadVerdict = withContext(Dispatchers.IO) {
        runCatching {
            val head: RemoteHead? = when {
                url.startsWith("http") -> fetchRemoteHead(url, HEAD_BYTES)
                url.startsWith("file://") -> {
                    val path = url.removePrefix("file://")
                    val file = File(path)
                    if (!file.exists()) return@runCatching HeadVerdict.Unknown("本地文件不存在：$path")
                    val bytes = readLocal(path, HEAD_BYTES) ?: return@runCatching HeadVerdict.Unknown("本地文件读不到")
                    RemoteHead(bytes, contentType = null, contentLength = file.length())
                }
                // content:// (SAF 导入) 由调用方用 ContentResolver 读字节后调 validateHead，
                // 这里不认——直接放行，别把本地歌卡死。
                else -> return@runCatching HeadVerdict.Unknown("未知 scheme，未校验")
            }
            val h = head ?: return@runCatching HeadVerdict.Unknown("未取到头字节")
            validateHead(h.bytes, h.contentType, h.contentLength, durationSec)
        }.onFailure { Log.d(TAG, "validate failed for $url: ${it.message}") }
            .getOrElse { HeadVerdict.Unknown("校验异常：${it.message}") }
    }

    /**
     * Pure head-bytes verdict. [contentLength] is the full file size when the server /
     * filesystem reports it (null = unknown, skip the size check).
     */
    fun validateHead(
        bytes: ByteArray,
        contentType: String?,
        contentLength: Long?,
        durationSec: Int?,
    ): HeadVerdict {
        if (bytes.isEmpty()) return HeadVerdict.Unknown("未取到头字节")
        if (bytes.size < MIN_HEAD_BYTES) {
            return HeadVerdict.Dirty("文件只有 ${bytes.size} 字节，不是完整音频")
        }
        // (1) Content-Type 明说不是音频：后端把报错页当文件发了。
        val ct = contentType?.lowercase().orEmpty()
        if (ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("html")) {
            return HeadVerdict.Dirty("Content-Type 是 $contentType，不是音频")
        }
        // (2) 大小荒谬：一首 durationSec 秒的歌不可能只有这点字节（按 8 kbps 的底线算，
        //  正常歌都在 128k 以上；底线留得极低，只拦几十 KB 的假文件，不拦短预览）。
        if (contentLength != null && durationSec != null && durationSec > 0) {
            val floor = maxOf(1024L, durationSec * 1000L)
            if (contentLength < floor) {
                return HeadVerdict.Dirty("文件只有 $contentLength 字节，$durationSec 秒的歌不可能这么小")
            }
        }
        // (3) FLAC：STREAMINFO 必须合理，且元数据块之后 64 KB 内必须出现至少一个有效帧头。
        //  《相亲相爱》那次角标能读出 FLAC 24bit/192kHz（头是对的），崩在后面的帧上——
        //  只认 "fLaC" 四个字节不够，必须往后看到帧才放行。
        if (isFlacMagic(bytes)) {
            val spec = parseFlac(bytes)
                ?: return HeadVerdict.Dirty("FLAC 头损坏（STREAMINFO 读不出采样率/位深）")
            val frameAt = firstFlacFrameOffset(bytes)
                ?: return HeadVerdict.Unknown("FLAC 元数据超出 ${HEAD_BYTES / 1024}KB，帧位置未知")
            if (!hasFlacFrameSync(bytes, frameAt)) {
                return HeadVerdict.Dirty(
                    "FLAC 头有效（${spec.displayText}），但 ${HEAD_BYTES / 1024}KB 内找不到有效音频帧——文件体损坏",
                )
            }
            return HeadVerdict.Valid
        }
        // (4) 其它认得的容器：只认容器、不拆参数——探头不给规格不等于不能播。
        //  注意放 MP3 分支前面：裸 AAC（ADTS 0xFFF1/0xFFF9）也满足 0xFF 0xEx 的同步字
        //  外形，别让它掉进 MP3 分支判成 Unknown。
        if (isWav(bytes) || isOgg(bytes) || isFtyp(bytes) || isAdts(bytes)) return HeadVerdict.Valid
        // (5) MP3：ID3 跳过标签找同步字，裸 MP3 直接扫同步字。找得到就是音频；
        //  找不着不硬判脏——ID3 标签巨大时同步字可能在 64KB 外，放行。
        if (isId3(bytes) || isMpegSyncAt(bytes, 0)) {
            if (isId3(bytes)) {
                if (bytes.size < 10) return HeadVerdict.Unknown("ID3 头不完整")
                val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or
                    ((bytes[7].toInt() and 0x7F) shl 14) or
                    ((bytes[8].toInt() and 0x7F) shl 7) or
                    (bytes[9].toInt() and 0x7F)
                val frameStart = 10 + tagSize
                if (frameStart >= bytes.size) {
                    return HeadVerdict.Unknown("ID3 标签超出头 ${HEAD_BYTES / 1024}KB，同步字位置未知")
                }
                return if (scanMpegSync(bytes, frameStart)) HeadVerdict.Valid
                else HeadVerdict.Unknown("ID3 标签后 8KB 内无 MP3 同步字")
            }
            return if (scanMpegSync(bytes, 0)) HeadVerdict.Valid
            else HeadVerdict.Unknown("头 ${HEAD_BYTES / 1024}KB 内无 MP3 同步字")
        }
        // (6) 其它认得的容器已在上面；这里只剩文本嗅探：HTML/JSON/纯文本报错页。
        if (looksLikeText(bytes)) {
            val preview = String(bytes.copyOf(minOf(48, bytes.size)), Charsets.UTF_8)
                .replace("\n", " ").replace("\r", " ").take(48)
            return HeadVerdict.Dirty("文件头是文本（$preview），不是音频——后端返回了报错页")
        }
        // (7) 二进制但不认：放行，别误拦。
        return HeadVerdict.Unknown("未识别的容器，原样放行")
    }

    // MARK: - Head validation helpers -----------------------------------------------------

    private fun isFlacMagic(b: ByteArray) = b.size >= 4 &&
        b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
        b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    private fun isId3(b: ByteArray) = b.size >= 3 &&
        b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    private fun isWav(b: ByteArray) = b.size >= 12 &&
        b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
        b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
        b[8] == 'W'.code.toByte() && b[9] == 'A'.code.toByte() &&
        b[10] == 'V'.code.toByte() && b[11] == 'E'.code.toByte()

    private fun isOgg(b: ByteArray) = b.size >= 4 &&
        b[0] == 'O'.code.toByte() && b[1] == 'g'.code.toByte() &&
        b[2] == 'g'.code.toByte() && b[3] == 'S'.code.toByte()

    /** MP4/M4A：offset 4 起是 "ftyp" box（M4A/AAC 探头不拆规格，但容器认得就能播）。 */
    private fun isFtyp(b: ByteArray) = b.size >= 8 &&
        b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
        b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()

    /** 裸 AAC ADTS：0xFF 0xF1 / 0xFF 0xF9。 */
    private fun isAdts(b: ByteArray) = b.size >= 2 &&
        b[0] == 0xFF.toByte() && (b[1].toInt() and 0xFF) in setOf(0xF1, 0xF9)

    private fun isMpegSyncAt(b: ByteArray, at: Int) = b.size > at + 1 &&
        b[at] == 0xFF.toByte() && (b[at + 1].toInt() and 0xE0) == 0xE0

    /** 与 parseMpegSync 同一套同步字判定，只是不拆码率/采样率：扫到就算音频。 */
    private fun scanMpegSync(buf: ByteArray, fromOffset: Int): Boolean {
        val end = minOf(buf.size - 4, fromOffset + 8192)
        for (i in fromOffset..end) {
            if (buf[i] != 0xFF.toByte()) continue
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b1 and 0xE0 != 0xE0) continue
            val versionBits = (b1 shr 3) and 0x3
            if (versionBits == 1) continue
            val layerBits = (b1 shr 1) and 0x3
            if (layerBits != 1) continue
            val b2 = buf[i + 2].toInt() and 0xFF
            val bitrateIdx = b2 shr 4
            if (bitrateIdx == 0 || bitrateIdx == 15) continue
            val rateIdx = (b2 shr 2) and 0x3
            if (rateIdx == 3) continue
            return true
        }
        return false
    }

    /**
     * 沿 FLAC 元数据块链走到第一帧的偏移。块头 4 字节：bit7 = 最后一块，低 7 位 = 类型，
     * 后 3 字节大端 = 块长。块链跑出缓冲区就返回 null（帧位置未知→放行，不硬拦）。
     */
    private fun firstFlacFrameOffset(buf: ByteArray): Int? {
        var off = 4
        while (off + 4 <= buf.size) {
            val last = (buf[off].toInt() and 0x80) != 0
            val len = ((buf[off + 1].toInt() and 0xFF) shl 16) or
                ((buf[off + 2].toInt() and 0xFF) shl 8) or
                (buf[off + 3].toInt() and 0xFF)
            off += 4 + len
            if (last) return if (off <= buf.size) off else null
            if (off > buf.size) return null
        }
        return null
    }

    /**
     * 从 [from] 起 4KB 内找一个像样的 FLAC 帧头：0xFF + 0xF8..0xFB（14 位同步字），
     * 块大小码 != 0000（保留值）、采样率码 != 1111（无效值）。随机垃圾撞上这一组的
     * 概率很低；真 FLAC 的第一帧就在元数据块后，基本是零偏移命中。
     */
    private fun hasFlacFrameSync(buf: ByteArray, from: Int): Boolean {
        val end = minOf(buf.size - 4, from + 4096)
        for (i in from..end) {
            if (buf[i] != 0xFF.toByte()) continue
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b1 !in 0xF8..0xFB) continue
            val b2 = buf[i + 2].toInt() and 0xFF
            if ((b2 shr 4) == 0) continue // 块大小码 0000 保留
            if ((b2 and 0x0F) == 0x0F) continue // 采样率码 1111 无效
            return true
        }
        return false
    }

    /** 头 512 字节里 ≥95% 是可打印 ASCII / 空白：HTML、JSON、纯文本报错页。 */
    private fun looksLikeText(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        if (b[0] == '<'.code.toByte()) return true
        val n = minOf(512, b.size)
        var printable = 0
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if ((c in 0x09..0x0D) || (c in 0x20..0x7E)) printable++
        }
        return printable * 100 / n >= 95
    }

    private data class RemoteHead(
        val bytes: ByteArray,
        val contentType: String?,
        val contentLength: Long?,
    )

    private fun fetchRemoteHead(url: String, count: Int): RemoteHead? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-${count - 1}")
            .build()
        // 与探头同一套 10 秒超时；调用方另有 withTimeoutOrNull 兜底（见 PlaybackController），
        // 验不出时不拖播放。
        val client = http.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val stream = resp.body?.byteStream() ?: return@use null
            val bytes = readUpTo(stream, count)
            RemoteHead(
                bytes = bytes,
                contentType = resp.header("Content-Type"),
                contentLength = resp.header("Content-Length")?.toLongOrNull(),
            )
        }
    }

    suspend fun probe(url: String): AudioSpec? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = if (url.startsWith("http")) fetchRemote(url, 65_536) else readLocal(url, 65_536)
            if (bytes == null || bytes.size < 32) return@runCatching null
            parse(bytes, url)
        }.onFailure { Log.d(TAG, "probe failed for $url: ${it.message}") }
            .getOrNull()
    }

    // MARK: - Bytes -------------------------------------------------------------------------

    private fun fetchRemote(url: String, count: Int): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-${count - 1}")
            .build()
        // Custom 10s timeouts per the spec — independent of the shared OkHttp client config.
        val client = http.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val source = resp.body?.byteStream() ?: return@use null
            readUpTo(source, count)
        }
    }

    private fun readLocal(path: String, count: Int): ByteArray? = runCatching {
        File(path).inputStream().use { readUpTo(it, count) }
    }.getOrNull()

    private fun readUpTo(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var total = 0
        while (total < count) {
            val read = input.read(out, total, count - total)
            if (read <= 0) break
            total += read
        }
        return if (total == count) out else out.copyOf(total)
    }

    // MARK: - Parse -------------------------------------------------------------------------

    private suspend fun parse(buf: ByteArray, originalUrl: String): AudioSpec? {
        // FLAC magic: "fLaC" then STREAMINFO is guaranteed to be the first metadata block.
        if (buf.size >= 4 && buf[0] == 'f'.code.toByte() && buf[1] == 'L'.code.toByte() &&
            buf[2] == 'a'.code.toByte() && buf[3] == 'C'.code.toByte()
        ) {
            return parseFlac(buf)
        }
        // ID3v2 tag: "ID3" + 2-byte version + 1-byte flags + 4-byte syncsafe size at offset 6-9.
        if (buf.size >= 10 && buf[0] == 'I'.code.toByte() && buf[1] == 'D'.code.toByte() &&
            buf[2] == '3'.code.toByte()
        ) {
            val size = ((buf[6].toInt() and 0x7F) shl 21) or
                ((buf[7].toInt() and 0x7F) shl 14) or
                ((buf[8].toInt() and 0x7F) shl 7) or
                (buf[9].toInt() and 0x7F)
            val frameStart = 10 + size
            // Album art commonly bloats ID3 tags past 64 KB. Refetch a small window at the
            // computed offset to find the MPEG sync.
            return if (frameStart < buf.size) {
                parseMpegSync(buf, frameStart)
            } else if (originalUrl.startsWith("http")) {
                val extra = fetchRangeWindow(originalUrl, frameStart, 8192) ?: return null
                parseMpegSync(extra, 0)
            } else {
                null
            }
        }
        // Bare MP3 / other: scan for an MPEG sync word at the start of the buffer.
        return parseMpegSync(buf, 0)
    }

    private fun parseFlac(buf: ByteArray): AudioSpec? {
        // Metadata block 1 at offset 4: byte[4] = (lastBlockFlag << 7) | blockType (0 = STREAMINFO).
        if (buf.size < 4 + 4 + 18) return null
        val blockType = buf[4].toInt() and 0x7F
        if (blockType != 0) return null
        // STREAMINFO body starts at offset 4 (header) + 4 (block header) = 8.
        // Bytes 8..17 are min/max block sizes and frame sizes; the 8-byte big-endian packed
        // value at offset 8+10 = 18 contains sample rate / channels / bits-per-sample.
        var packed = 0L
        for (i in 0 until 8) {
            packed = (packed shl 8) or (buf[18 + i].toLong() and 0xFF)
        }
        val sampleRate = ((packed ushr 44) and 0xFFFFF).toInt() // 20 bits
        val bitsPerSample = (((packed ushr 36) and 0x1F) + 1).toInt() // 5 bits + 1
        if (sampleRate <= 0 || bitsPerSample <= 0) return null
        return AudioSpec(codec = "FLAC", sampleRate = sampleRate, bitsPerSample = bitsPerSample)
    }

    private fun parseMpegSync(buf: ByteArray, fromOffset: Int): AudioSpec? {
        val end = minOf(buf.size - 4, fromOffset + 8192)
        for (i in fromOffset..end) {
            if (buf[i] != 0xFF.toByte()) continue
            val b1 = buf[i + 1].toInt() and 0xFF
            if (b1 and 0xE0 != 0xE0) continue
            val versionBits = (b1 shr 3) and 0x3
            if (versionBits == 1) continue // reserved
            val layerBits = (b1 shr 1) and 0x3
            if (layerBits != 1) continue // we only care about Layer 3
            val b2 = buf[i + 2].toInt() and 0xFF
            val bitrateIdx = b2 shr 4
            if (bitrateIdx == 0 || bitrateIdx == 15) continue
            val rateIdx = (b2 shr 2) and 0x3
            if (rateIdx == 3) continue

            val bitrateTable = if (versionBits == 3) MPEG1_L3_BITRATE else MPEG2_L3_BITRATE
            val rateTable = when (versionBits) {
                3 -> MPEG1_RATES
                2 -> MPEG2_RATES
                0 -> MPEG2_5_RATES
                else -> return null
            }
            val kbps = bitrateTable.getOrNull(bitrateIdx) ?: continue
            val hz = rateTable.getOrNull(rateIdx) ?: continue
            return AudioSpec(codec = "MP3", sampleRate = hz, bitrateKbps = kbps)
        }
        return null
    }

    private fun fetchRangeWindow(url: String, from: Int, count: Int): ByteArray? {
        val to = from + count - 1
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=$from-$to")
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.byteStream()?.use { readUpTo(it, count) }
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "AudioSpecProbe"
        private const val UA = "Mozilla/5.0 (Linux; Android 14; AndroidTV) AppleWebKit/537.36"
        /** 点播前验地址取的头字节数（与探头一致）。 */
        private const val HEAD_BYTES = 65_536
        /** 少于这个字节的文件头不可能属于一首能播的歌。 */
        private const val MIN_HEAD_BYTES = 32

        // Bitrate / sample-rate tables straight from the MPEG audio Layer-III spec.
        private val MPEG1_L3_BITRATE = intArrayOf(
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320,
        )
        private val MPEG2_L3_BITRATE = intArrayOf(
            0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160,
        )
        private val MPEG1_RATES = intArrayOf(44100, 48000, 32000)
        private val MPEG2_RATES = intArrayOf(22050, 24000, 16000)
        private val MPEG2_5_RATES = intArrayOf(11025, 12000, 8000)
    }
}
