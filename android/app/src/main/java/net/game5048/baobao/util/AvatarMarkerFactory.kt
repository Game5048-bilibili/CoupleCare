package net.game5048.baobao.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import kotlin.math.cos
import kotlin.math.sin

/**
 * 生成地图标记用的位图。
 *
 * 设计成「两层」：
 *  1. **头像层**（[buildAvatarMarker]）：圆形头像 + 白色描边，不旋转 —— 照片转了会很怪
 *  2. **方向环层**（[buildHeadingRing]）：一圈细环 + 顶端一个箭头，作为独立 Marker
 *     放在同一个坐标上，用 `setRotateAngle(bearing)` 旋转
 *
 * 两层叠在一起的效果就是：头像不动，外圈的箭头指示对方正在行进的方向。
 * 比起「整个头像跟着转」，这种更符合直觉。
 */
object AvatarMarkerFactory {

    /** 头像层相对基准尺寸的放大倍数（留出白色描边的空间） */
    private const val AVATAR_FILL = 0.86f

    /** 方向环位图的边长 / 头像层边长 */
    const val RING_SCALE = 1.42f

    /**
     * 生成圆形头像位图。
     *
     * @param avatar 用户头像；为 null 时画一个占位圆 + 人形剪影
     * @param sizePx 输出位图边长（像素）
     * @param ringColor 描边颜色（一般是白色，保证在任何地图底色上都看得清）
     * @param fallbackColor 没有头像时的填充色
     */
    fun buildAvatarMarker(
        avatar: Bitmap?,
        sizePx: Int,
        ringColor: Int = Color.WHITE,
        fallbackColor: Int = Color.parseColor("#B4194E")
    ): Bitmap {
        val size = sizePx.coerceAtLeast(24)
        val out = createBitmap(size, size)
        val canvas = Canvas(out)

        val stroke = size * 0.055f
        val radius = size / 2f - stroke / 2f
        val center = size / 2f

        // 1) 圆形裁剪区里画头像
        val saveCount = canvas.save()
        val circle = Path().apply { addCircle(center, center, radius - stroke, Path.Direction.CW) }
        canvas.clipPath(circle)

        if (avatar != null) {
            // 居中裁剪：头像一般已经是正方形，直接铺满圆
            // 注意 Canvas.drawBitmap 没有 (Bitmap, RectF, RectF, Paint) 这个重载，
            // 源矩形必须用 int 的 Rect
            val side = (radius - stroke) * 2
            val src = Rect(0, 0, avatar.width, avatar.height)
            val dst = RectF(center - side / 2, center - side / 2, center + side / 2, center + side / 2)
            canvas.drawBitmap(avatar, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            canvas.drawColor(fallbackColor)
            drawPersonSilhouette(canvas, center, radius - stroke)
        }
        canvas.restoreToCount(saveCount)

        // 2) 白色描边（画在裁剪之外，所以能盖住边缘的锯齿）
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ringColor
            strokeWidth = stroke
        }
        canvas.drawCircle(center, center, radius - stroke / 2, ringPaint)

        return out
    }

    /**
     * 生成方向环位图：细圆环 + 顶端箭头。
     *
     * 位图比头像层大（[RING_SCALE] 倍），两层都以中心对齐，
     * 于是环会套在头像外面，箭头指向位图的正上方（也就是 bearing = 0 的方向）。
     *
     * @param sizePx 输出位图边长，应当等于 头像层边长 * RING_SCALE
     */
    fun buildHeadingRing(
        sizePx: Int,
        ringColor: Int = Color.WHITE,
        arrowColor: Int = Color.parseColor("#FF3B6B")
    ): Bitmap {
        val size = sizePx.coerceAtLeast(32)
        val out = createBitmap(size, size)
        val canvas = Canvas(out)

        val center = size / 2f
        val stroke = size * 0.045f
        val ringRadius = center - stroke - size * 0.12f   // 给箭头留出空间

        // 1) 细圆环（半透明白，任何底色上都能看见，又不抢头像的视觉）
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = ringColor
            strokeWidth = stroke
            alpha = 190
        }
        canvas.drawCircle(center, center, ringRadius, ringPaint)

        // 2) 顶端箭头：以「正上方」为中心的一个等腰三角形
        val arrowHeight = size * 0.20f
        val arrowHalfWidth = size * 0.085f
        val tipRadius = ringRadius + arrowHeight * 0.62f

        val tipX = center
        val tipY = center - tipRadius
        val baseY = center - ringRadius + stroke * 0.6f

        val arrow = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - arrowHalfWidth, baseY)
            lineTo(tipX + arrowHalfWidth, baseY)
            close()
        }

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = arrowColor
        }
        // 轻微渐变让箭头有点立体感
        arrowPaint.shader = LinearGradient(
            tipX, tipY, tipX, baseY,
            arrowColor, darken(arrowColor, 0.75f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(arrow, arrowPaint)

        return out
    }

    /**
     * 生成「自己的位置」标记：一个带白边的实心小圆点。
     * 比对方的头像标记小一号，视觉上主次分明。
     */
    fun buildSelfDot(
        sizePx: Int,
        color: Int = Color.parseColor("#1E88E5"),
        ringColor: Int = Color.WHITE
    ): Bitmap {
        val size = sizePx.coerceAtLeast(16)
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        val center = size / 2f
        val stroke = size * 0.14f

        canvas.drawCircle(
            center, center, center - stroke / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ringColor }
        )
        canvas.drawCircle(
            center, center, center - stroke,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        )
        return out
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 画一个简单的人形剪影，作为「还没设置头像」的占位 */
    private fun drawPersonSilhouette(canvas: Canvas, center: Float, radius: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 255, 255, 255)
            style = Paint.Style.FILL
        }
        // 头
        canvas.drawCircle(center, center - radius * 0.28f, radius * 0.30f, paint)
        // 肩（一个半圆）
        val shoulder = RectF(
            center - radius * 0.62f,
            center + radius * 0.08f,
            center + radius * 0.62f,
            center + radius * 1.32f
        )
        canvas.drawArc(shoulder, 180f, 180f, true, paint)
    }

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    /**
     * 把位图裁成圆形（给 UI 里的圆形头像控件用）。
     * 这里保留一份，避免 UI 层再依赖别的工具类。
     */
    fun circleCrop(source: Bitmap, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        val radius = size / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val circle = Path().apply { addCircle(radius, radius, radius, Path.Direction.CW) }
        canvas.clipPath(circle)

        val side = minOf(source.width, source.height).toFloat()
        val left = (source.width - side) / 2f
        val top = (source.height - side) / 2f
        // 同 buildAvatarMarker：源矩形用 int 的 Rect
        val src = Rect(
            left.toInt(), top.toInt(),
            (left + side).toInt(), (top + side).toInt()
        )
        val dst = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawBitmap(source, src, dst, paint)

        // 用 DST_IN 把圆外清干净，避免 clipPath 在某些机型上的锯齿
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawCircle(radius, radius, radius, clear)

        return out
    }

    /** 计算并返回「箭头」应当时刻指向的旋转角（正北 0，顺时针；供 UI/日志使用） */
    fun normalizeBearing(bearing: Float): Float = GeoUtils.normalizeBearing(bearing)

    /**
     * 把「罗盘方位角」换算成高德 Marker 的 `rotateAngle`。
     *
     * **坑点**：两者的角度方向是相反的。
     *  - 罗盘/`Location.getBearing()`：从正北开始**顺时针**（东 = 90°）
     *  - 高德 `Marker.setRotateAngle()`：官方文档明确写「从正北开始，**逆时针**计算」
     *
     * 而 [buildHeadingRing] 把箭头画在位图正上方（即位图的「北」），
     * 所以要让箭头指向顺时针 B 度，必须传 `360 - B` 给高德，否则箭头会左右镜像
     * （东边的人看起来在往西走）。
     *
     * 如果哪天真机验证发现箭头方向反了，把这里的 360f - 去掉即可 —— 只改这一处。
     */
    fun toAmapRotation(bearing: Float): Float {
        val normalized = GeoUtils.normalizeBearing(bearing)
        val rotation = 360f - normalized
        return if (rotation >= 360f) rotation - 360f else rotation
    }

    /** 供调试/自测：给定位方向加点可读性 */
    fun bearingToCompass(bearing: Float): String {
        if (bearing < 0) return "未知"
        val directions = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val index = (((bearing + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
        return directions[index]
    }

    /** 极坐标转直角坐标（箭头绘制时用得到，留作扩展） */
    internal fun polar(cx: Float, cy: Float, radius: Float, angleDeg: Float): FloatArray {
        val rad = Math.toRadians(angleDeg.toDouble())
        return floatArrayOf(
            cx + (radius * cos(rad)).toFloat(),
            cy + (radius * sin(rad)).toFloat()
        )
    }
}
