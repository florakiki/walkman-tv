package com.walkman.tv.data.store

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KITV-AES 解密：与 KITV 配置加密同一路子（AES-256-CBC，`KITV-AES:` + Base64(IV‖密文））。
 * 只在内存里解密，不写回磁盘。密钥嵌在代码里，反编译能拿到，防君子不防小人。
 * 不带前缀的内容按原样返回，所以明文源不受影响。
 */
object KitvAes {
    const val PREFIX = "KITV-AES:"

    /** 复用 KITV 的 AES-256 密钥（ki.json/kiki.txt 加密用的那把）。 */
    private const val KEY_B64 = "BXXRltkJBOEmX/AOPh+KrhNLkvJiTy4x5VuqBOOwNP0="

    private val keyBytes: ByteArray by lazy { Base64.decode(KEY_B64, Base64.DEFAULT) }

    /** 带 KITV-AES: 前缀就解密后返回，否则原样返回。解密失败抛异常，由调用方处理。 */
    fun decryptIfPrefixed(text: String): String {
        if (!text.startsWith(PREFIX)) return text
        val raw = Base64.decode(text.removePrefix(PREFIX).trim(), Base64.DEFAULT)
        require(raw.size > 16) { "KITV-AES: 数据太短" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(raw.copyOfRange(0, 16)),
        )
        return cipher.doFinal(raw.copyOfRange(16, raw.size)).toString(Charsets.UTF_8)
    }
}
