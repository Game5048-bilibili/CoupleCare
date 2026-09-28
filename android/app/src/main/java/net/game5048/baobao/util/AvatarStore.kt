package net.game5048.baobao.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 头像的本地存储：自己的头像和对方的头像各一个 JPEG 文件，都放在应用私有目录（filesDir）里。
 *
 * 为什么不用 SharedPreferences / 数据库存：头像动辄几十 KB，塞进 XML 里每次读配置都要
 * 解析一遍，而且没法做「原子替换」。两个文件放磁盘上，读的时候按需解码，最简单也最省内存。
 *
 * 三条硬性约定：
 * 1. 磁盘上的文件永远是 [AVATAR_SIZE] 见方的 JPEG，写入前统一归一化，调用方传什么进来都不会污染数据；
 * 2. 任何写失败（磁盘满、权限、文件被占）都只返回 false，**绝不抛异常**给调用方，
 *    头像挂掉不该让整个设置页崩；
 * 3. 读写都不缓存 Bitmap：本对象无状态，Bitmap 由调用方自己 remember / 持有并负责释放，
 *    否则很容易出现「已经被 recycle 的图还在被 Compose 画」这类崩溃。
 */
object AvatarStore {

    /** 头像统一输出尺寸 */
    const val AVATAR_SIZE: Int = 256

    /** JPEG 压缩质量 */
    const val JPEG_QUALITY: Int = 85

    private const val FILE_SELF = "avatar_self.jpg"
    private const val FILE_PARTNER = "avatar_partner.jpg"
    private const val FILE_TMP_SUFFIX = ".tmp"

    /** 读回头像时的目标边长：UI 最大也就显示百来 dp，512 足够清晰又只占 ~1MB */
    private const val LOAD_TARGET_EDGE = 512

    /** 保存前解码采样上限：进来的字节理论上可能很大，先采样再归一化，避免一次解出几十 MB */
    private const val SAVE_DECODE_EDGE = 1024

    /** 自己的头像文件：filesDir/avatar_self.jpg */
    fun selfAvatarFile(context: Context): File = File(context.filesDir, FILE_SELF)

    /** 对方头像文件：filesDir/avatar_partner.jpg */
    fun partnerAvatarFile(context: Context): File = File(context.filesDir, FILE_PARTNER)

    /** 读取头像；文件不存在或解码失败返回 null */
    fun load(context: Context, isSelf: Boolean): Bitmap? {
        val file = if (isSelf) selfAvatarFile(context) else partnerAvatarFile(context)
        if (!isUsable(file)) return null
        return try {
            // 先只读尺寸（inJustDecodeBounds 不会分配像素内存），再据此算采样率，
            // 保证即使文件被人替换成一张巨图，也不会一次把整张图解进内存。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, LOAD_TARGET_EDGE)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        } catch (t: Throwable) {
            // 文件损坏 / OOM / 被其他线程占用都走这里，UI 侧按「没设置头像」处理即可
            null
        }
    }

    /** 保存自己的头像（256x256 JPEG 字节），返回是否成功 */
    fun saveSelfAvatar(context: Context, jpegBytes: ByteArray): Boolean =
        save(selfAvatarFile(context), jpegBytes)

    /** 保存对方的头像（从网络收到），返回是否成功 */
    fun savePartnerAvatar(context: Context, jpegBytes: ByteArray): Boolean =
        save(partnerAvatarFile(context), jpegBytes)

    /** 自己是否已经设置过头像 */
    fun hasSelfAvatar(context: Context): Boolean = isUsable(selfAvatarFile(context))

    /** 清空两个头像（解除配对时用） */
    fun clear(context: Context) {
        try {
            listOf(selfAvatarFile(context), partnerAvatarFile(context)).forEach { file ->
                if (file.exists()) file.delete()
                // 写一半留下的临时文件也要清掉，否则会一直占着磁盘
                val tmp = tmpFileOf(file)
                if (tmp.exists()) tmp.delete()
            }
        } catch (t: Throwable) {
            // 删不掉就算了：最坏情况是旧头像还在，下次保存直接覆盖，不影响功能
        }
    }

    /** Bitmap -> JPEG 字节 */
    fun encodeJpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray =
        try {
            val out = ByteArrayOutputStream()
            // quality 夹到合法区间，避免调用方传 0 / 200 时 compress 返回 false
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            if (ok) out.toByteArray() else ByteArray(0)
        } catch (t: Throwable) {
            // 已经 recycle 的 Bitmap 会抛异常；约定失败返回空数组，让调用方统一判断 isEmpty()
            ByteArray(0)
        }

    /** 归一化：把任意尺寸的图缩放到 256x256（中心裁剪 + 区域平均缩放） */
    fun normalizeToAvatar(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) {
            // 尺寸为 0 说明这张图已经废了（多半被 recycle 过）。这里仍然给出一张尺寸正确的
            // 空图，调用方就不必再判空，后续 createScaledBitmap 也不会抛异常。
            return Bitmap.createBitmap(AVATAR_SIZE, AVATAR_SIZE, Bitmap.Config.ARGB_8888)
        }

        // 1) 中心正方形裁剪：多出来的部分左右（或上下）对称切掉
        val edge = if (width < height) width else height
        val left = (width - edge) / 2
        val top = (height - edge) / 2
        val cropped = if (left == 0 && top == 0 && edge == width && edge == height) {
            source // createBitmap 在这个条件下本来也会原样返回 source，省一次判断
        } else {
            Bitmap.createBitmap(source, left, top, edge, edge)
        }

        // 2) 缩放到 256x256。filter = true 会做双线性插值（缩小时等价于小范围区域平均），
        //    比最近邻干净得多，人像缩小后不会有锯齿。
        val scaled = Bitmap.createScaledBitmap(cropped, AVATAR_SIZE, AVATAR_SIZE, true)

        // 3) 统一成 ARGB_8888：防止原图是 RGB_565 之类的配置，导致后续绘制透明通道丢失
        val result = toArgb8888(scaled)

        // 中间产物在新图里没有引用，可以立刻回收；和 source / result 是同一个对象时跳过，
        // 否则会把仍在使用的图回收掉（createScaledBitmap 在尺寸相同时会直接返回入参）。
        if (cropped !== source && cropped !== scaled && cropped !== result) cropped.recycle()
        if (scaled !== source && scaled !== result) scaled.recycle()
        return result
    }

    // ======================================================================
    // 内部实现
    // ======================================================================

    private fun save(target: File, jpegBytes: ByteArray): Boolean {
        if (jpegBytes.isEmpty()) return false

        // 先解码再落盘：这样「磁盘上的文件一定是 256x256 的 JPEG」这条约定
        // 由本方法自己保证，不管调用方给的是原图、PNG 还是别的尺寸。
        val payload = try {
            val decoded = decodeBytes(jpegBytes) ?: return false
            // 已经是 256x256 的 JPEG（裁剪对话框的正常输出）就原样落盘：
            // 解一次再编一次会白白丢掉一轮画质，而这一轮本来没必要。
            if (decoded.width == AVATAR_SIZE && decoded.height == AVATAR_SIZE && looksLikeJpeg(jpegBytes)) {
                decoded.recycle()
                jpegBytes
            } else {
                val avatar = normalizeToAvatar(decoded)
                val bytes = encodeJpeg(avatar)
                if (avatar !== decoded) avatar.recycle()
                decoded.recycle()
                bytes
            }
        } catch (t: Throwable) {
            return false
        }
        if (payload.isEmpty()) return false

        val tmp = tmpFileOf(target)
        return try {
            // 先写临时文件再改名：改名在同一分区上是原子的，所以读的一方要么看到旧头像，
            // 要么看到新头像，不会读到写了一半的半个文件。
            FileOutputStream(tmp).use { out ->
                out.write(payload)
                out.flush()
                out.fd.sync()
            }
            if (tmp.renameTo(target)) {
                true
            } else {
                // 少数 ROM 上 rename 不覆盖已存在的目标文件，退化成直接写一遍
                tmp.delete()
                writeDirect(target, payload)
            }
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    private fun writeDirect(target: File, bytes: ByteArray): Boolean =
        try {
            FileOutputStream(target).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            true
        } catch (t: Throwable) {
            false
        }

    /** 从字节数组解码，先探测尺寸再采样，避免大图一次性进内存 */
    private fun decodeBytes(bytes: ByteArray): Bitmap? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, SAVE_DECODE_EDGE)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } catch (t: Throwable) {
            null
        }

    /**
     * 求一个 2 的幂次采样率，使采样后的长边不超过 [maxEdge]。
     *
     * 之所以要求 2 的幂：BitmapFactory 内部对非 2 的幂会向上取整到最近的 2 的幂，
     * 我们直接按 2 的幂算，得到的尺寸才是可预期的（也能避免 1 像素的边界误差）。
     */
    private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (maxEdge <= 0) return 1
        val longEdge = if (width > height) width else height
        var sample = 1
        while (longEdge / sample > maxEdge) sample *= 2
        return sample
    }

    /** 统一成 ARGB_8888；copy 失败（OOM）时退化返回原图，至少不抛异常 */
    private fun toArgb8888(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        }

    /**
     * JPEG 的魔数：FF D8 FF。
     *
     * 落盘前确认一次，是为了避免「已经是 256x256 但其实是 PNG」的字节被原样写进 .jpg 文件里 ——
     * 后缀名对不上，以后拿给别人（或别的解码器）就会莫名其妙地打不开。
     */
    private fun looksLikeJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    private fun tmpFileOf(target: File): File = File(target.parentFile, target.name + FILE_TMP_SUFFIX)

    /** 存在且非空才算「有头像」：写到一半断电会留下 0 字节文件，那种要当成没有 */
    private fun isUsable(file: File): Boolean = file.isFile && file.length() > 0L
}
