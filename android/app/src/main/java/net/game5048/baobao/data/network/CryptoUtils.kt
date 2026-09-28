package net.game5048.baobao.data.network

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM 加解密工具。
 *
 * ## 密钥派生
 * `pair_code` --(PBKDF2-HMAC-SHA256, salt="yuanzhiyue_salt", 10000 次, 256bit)--> AES Key
 *
 * 服务端 `server/crypto.py` 使用完全相同的参数，因此双方派生出同一把密钥。
 * 之所以用固定盐：配对码本身就是“共享秘密 + 群组标识”，盐固定可以让两端无需协商即可算出密钥
 * （牺牲了防彩虹表的强度，但配对码场景下用户可自行设置足够复杂的口令）。
 *
 * ## 密文格式
 * `Base64( IV[12] ‖ CipherText ‖ GCM Tag[16] )`，Base64 使用 NO_WRAP（不带换行）。
 * 每次加密都重新生成随机 IV —— GCM 下 IV 复用会直接导致密钥流复用，是致命错误。
 */
object CryptoUtils {

    private const val TAG = "CryptoUtils"

    /** 固定盐，必须与服务端 config.py 中 SALT 完全一致 */
    const val SALT = "yuanzhiyue_salt"

    /** PBKDF2 迭代次数，必须与服务端一致 */
    const val ITERATIONS = 10_000

    /** 派生密钥长度（bit） */
    const val KEY_BITS = 256

    private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** GCM 推荐 IV 长度就是 12 字节，其他长度会走 GHASH 慢路径 */
    private const val GCM_IV_BYTES = 12

    /** 认证标签 128 bit（GCM 默认最大值） */
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    /** 配对码 -> 密钥 的缓存：PBKDF2 10000 次在高频上报时开销不可忽略 */
    private val keyCache = ConcurrentHashMap<String, SecretKey>()

    /**
     * 由配对码派生 AES 密钥（带缓存）。
     *
     * 注意：Java 的 PBKDF2WithHmacSHA256 把口令按 UTF-8 编码成字节，
     * 与 Python `pair_code.encode("utf-8")` 一致。建议配对码只使用 ASCII 字符。
     */
    fun deriveKey(pairCode: String): SecretKey = keyCache.getOrPut(pairCode) {
        val spec = PBEKeySpec(
            pairCode.toCharArray(),
            SALT.toByteArray(Charsets.UTF_8),
            ITERATIONS,
            KEY_BITS
        )
        val raw = SecretKeyFactory.getInstance(KEY_ALGORITHM).generateSecret(spec).encoded
        SecretKeySpec(raw, "AES")
    }

    /** 配对码变更时清缓存，避免用旧密钥继续通信 */
    fun clearCache() = keyCache.clear()

    /**
     * 加密字符串，返回 Base64(IV + 密文 + Tag)。
     *
     * @throws java.security.GeneralSecurityException 加密失败（极少见）
     */
    fun encrypt(plainText: String, key: SecretKey): String {
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // IV 与密文拼接后整体 Base64，解密端按前 12 字节切分
        val out = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(cipherText, 0, out, iv.size, cipherText.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * 解密 Base64(IV + 密文 + Tag)。
     *
     * @return 明文；解密失败（配对码不一致 / 数据被篡改）返回 null，调用方应丢弃该帧。
     */
    fun decrypt(base64Data: String, key: SecretKey): String? = try {
        val raw = Base64.decode(base64Data, Base64.NO_WRAP)
        require(raw.size > GCM_IV_BYTES) { "密文长度不足" }

        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val cipherText = raw.copyOfRange(GCM_IV_BYTES, raw.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    } catch (t: Throwable) {
        // AEADBadTagException / IllegalArgumentException / Base64 解析失败都走这里。
        // 单独的 util 层不打印完整堆栈，避免日志刷屏。
        Log.w(TAG, "解密失败：${t.javaClass.simpleName} - ${t.message}")
        null
    }
}
