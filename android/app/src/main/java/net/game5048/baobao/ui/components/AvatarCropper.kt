package net.game5048.baobao.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.game5048.baobao.util.AvatarStore

/** 缩放上限倍数：再放大只是在放大像素点，没有意义 */
private const val MAX_ZOOM = 6f

/** 解码上限：长边超过这个值的原图先采样。裁剪框最多也就屏幕大小，2048 足够了 */
private const val MAX_SOURCE_EDGE = 2048

/** 取景框上下各留 12dp 空白，让「框外被压暗」这件事在视觉上看得出来 */
private val CROP_FRAME_PADDING = 24.dp

/**
 * 从相册选图后弹出的方形裁剪对话框。
 * 用户可拖动 / 双指缩放，中间固定一个正方形取景框，确认后输出 256x256 JPEG。
 */
@Composable
fun AvatarCropDialog(
    sourceUri: Uri,
    onDismiss: () -> Unit,
    onCropped: (ByteArray) -> Unit
) {
    val context = LocalContext.current

    var bitmap by remember(sourceUri) { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember(sourceUri) { mutableStateOf(false) }

    // 放在 IO 线程解码：相册原图常常十几 MB，主线程解码会直接卡住动画甚至 ANR。
    // key 用 sourceUri：换一张图时（对话框被复用）会重新解码并重置裁剪状态。
    LaunchedEffect(sourceUri) {
        decodeFailed = false
        bitmap = null
        val decoded = withContext(Dispatchers.IO) {
            decodeScaled(context, sourceUri, MAX_SOURCE_EDGE)
        }
        if (decoded == null) decodeFailed = true else bitmap = decoded
    }

    Dialog(
        onDismissRequest = onDismiss, // 返回键、点外部区域都走这里，和「取消」行为一致
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "裁剪头像",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "拖动图片调整位置，双指缩放。方框里看到的区域就是最终头像。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val current = bitmap
                    when {
                        current != null -> CropStage(
                            bitmap = current,
                            onCancel = onDismiss,
                            onCropped = onCropped
                        )

                        decodeFailed -> DecodeFailedBlock(onClose = onDismiss)

                        else -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

/** 圆形头像显示组件（在地图标记、设置页里复用） */
@Composable
fun AvatarImage(
    bitmap: Bitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 2.dp
) {
    val scheme = MaterialTheme.colorScheme
    // 已经被 recycle 的 Bitmap 一旦交给 asImageBitmap 就会崩，这里提前挡掉
    val bmp = bitmap?.takeIf { !it.isRecycled }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            // 白色圆环用「外层底色 + 内层 padding」实现：Modifier.border 是在子内容之前
            // 绘制的，图片会把它整个盖住，只有退让出 padding 的底色才看得见。
            .background(scheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(borderWidth)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 占位：没设头像时用主色容器色画个「人」，和真实头像保持同样的圆与环
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier.size(size * 0.6f)
                    )
                }
            }
        }
    }
}

// ======================================================================
// 裁剪舞台
// ======================================================================

/**
 * 裁剪舞台：正方形取景框 + 手势 + 底部「取消 / 确定」。
 *
 * 尺寸相关的状态（取景框边长、缩放、位移）都集中在这里，是为了让「用户看到的」
 * 和「确定时裁出来的」共用同一组数值，避免两边各算一套导致对不上。
 */
@Composable
private fun CropStage(
    bitmap: Bitmap,
    onCancel: () -> Unit,
    onCropped: (ByteArray) -> Unit
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 取景框边长 = 屏幕短边的 86%，再夹到卡片可用宽度：窄屏 / 小窗模式下不会溢出卡片
        val side: Dp = minOf(
            (minOf(configuration.screenWidthDp, configuration.screenHeightDp) * 0.86f).dp,
            maxWidth
        )
        val sidePx = with(density) { side.toPx() }

        // sidePx 或位图变化时重建，等于把缩放/位移重置成初始状态（每次打开都是铺满的原始视角）
        val viewport = remember(bitmap, sidePx) { CropViewport(bitmap, sidePx) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            viewport.onGesture(zoomChange, panChange)
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(side + CROP_FRAME_PADDING),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(side)
                        .clipToBounds()
                        .background(Color.Black)
                        // 手势挂在外层正方形上：手指落在框内任何位置都算拖动，
                        // 即使图片本身比框大得多也不会漏掉事件
                        .transformable(state = transformState)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            // 1 个位图像素 = 1 个布局像素地摆放。缩放/位移才可以直接用像素计算，
                            // 不用再乘一次密度，也就不会和 graphicsLayer 的 scale 对不上。
                            .size(
                                width = with(density) { bitmap.width.toDp() },
                                height = with(density) { bitmap.height.toDp() }
                            )
                            .graphicsLayer {
                                // 以左上角为变换原点：这样 translation 就是「位图左上角相对
                                // 取景框左上角的位置」，与 CropViewport 的坐标定义完全一致。
                                // 若用默认的中点原点，同样的数值会得到完全不同的画面。
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = viewport.scale
                                scaleY = viewport.scale
                                translationX = viewport.offset.x
                                translationY = viewport.offset.y
                            }
                    )
                }

                // 遮罩画在图片之上；Canvas 不带 pointerInput，不会抢走下面的手势
                CropMaskOverlay(
                    sidePx = sidePx,
                    modifier = Modifier.matchParentSize()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel, enabled = !busy) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            // 画图 + 压缩要分配几 MB 的 Bitmap，放 Default 线程；
                            // 整个流程包在 runCatching 里，失败就当成取消，绝不崩对话框。
                            val bytes = withContext(Dispatchers.Default) {
                                runCatching { viewport.cropToJpeg() }.getOrNull()
                            }
                            busy = false
                            if (bytes != null && bytes.isNotEmpty()) onCropped(bytes) else onCancel()
                        }
                    },
                    enabled = !busy
                ) {
                    Text("确定")
                }
            }
        }
    }
}

/**
 * 取景框遮罩：把正方形以外的区域压暗，再画 2dp 白色内边框和三分参考线。
 *
 * 取景框在容器里是居中的（见 [CropStage] 的 contentAlignment），所以四条边的位置
 * 可以直接由容器尺寸算出来，不需要再去测量子节点 —— 也就不会因为测量时序导致遮罩闪一下。
 */
@Composable
private fun CropMaskOverlay(
    sidePx: Float,
    modifier: Modifier = Modifier
) {
    val dimColor = Color.Black.copy(alpha = 0.55f)
    val guideColor = Color.White.copy(alpha = 0.35f)

    Canvas(modifier = modifier) {
        // 正方形四条边（夹到容器范围内，退化时不会画出负尺寸的矩形）
        val frameLeft = ((size.width - sidePx) / 2f).coerceIn(0f, size.width)
        val frameTop = ((size.height - sidePx) / 2f).coerceIn(0f, size.height)
        val frameRight = (frameLeft + sidePx).coerceIn(0f, size.width)
        val frameBottom = (frameTop + sidePx).coerceIn(0f, size.height)
        val frameWidth = (frameRight - frameLeft).coerceAtLeast(0f)
        val frameHeight = (frameBottom - frameTop).coerceAtLeast(0f)

        // 遮罩：上 / 下 / 左 / 右四条，正好把正方形留空
        drawRect(dimColor, topLeft = Offset(0f, 0f), size = Size(size.width, frameTop))
        drawRect(
            dimColor,
            topLeft = Offset(0f, frameBottom),
            size = Size(size.width, (size.height - frameBottom).coerceAtLeast(0f))
        )
        drawRect(
            dimColor,
            topLeft = Offset(0f, frameTop),
            size = Size(frameLeft, frameHeight)
        )
        drawRect(
            dimColor,
            topLeft = Offset(frameRight, frameTop),
            size = Size((size.width - frameRight).coerceAtLeast(0f), frameHeight)
        )

        // 2dp 白色描边。Stroke 以路径为中心向两侧扩散，所以整体内缩半个线宽，
        // 保证这条线完全落在正方形内部、不会盖住一像素的图像内容。
        val borderWidth = 2.dp.toPx()
        drawRect(
            color = Color.White,
            topLeft = Offset(frameLeft + borderWidth / 2f, frameTop + borderWidth / 2f),
            size = Size(
                (frameWidth - borderWidth).coerceAtLeast(0f),
                (frameHeight - borderWidth).coerceAtLeast(0f)
            ),
            style = Stroke(width = borderWidth)
        )

        // 三分线：只做视觉参考，不参与任何计算
        val guideWidth = 1.dp.toPx()
        for (i in 1..2) {
            val x = frameLeft + frameWidth * i / 3f
            drawLine(
                color = guideColor,
                start = Offset(x, frameTop),
                end = Offset(x, frameBottom),
                strokeWidth = guideWidth
            )
            val y = frameTop + frameHeight * i / 3f
            drawLine(
                color = guideColor,
                start = Offset(frameLeft, y),
                end = Offset(frameRight, y),
                strokeWidth = guideWidth
            )
        }
    }
}

/** 解码失败时的提示块：给一个「关闭」，因为这时候没有图可裁 */
@Composable
private fun DecodeFailedBlock(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "这张图片读不出来，换一张再试",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onClose) {
            Text("关闭")
        }
    }
}

// ======================================================================
// 坐标模型：唯一负责「看到的 = 裁到的」
// ======================================================================

/**
 * 裁剪取景框的坐标模型（纯数据 + 纯计算，和 UI 分开写，方便单独核对算术）。
 *
 * ## 坐标约定（必须和 UI 用同一套，否则裁出来的图会和用户看到的不一样）
 * - 取景框是边长 [sidePx] 像素的正方形，原点在它的左上角。
 * - 位图按 **1 个位图像素 = 1 个布局像素** 摆放（见 [CropStage] 里 `size` 的换算）。
 * - [scale] 是叠加在位图上的整体缩放，所以 **显示宽度 = bitmapWidth * scale**。
 * - [offset] 是**显示像素**（= 布局像素）单位的位移，即位图左上角相对取景框左上角的位置。
 *
 * 于是「取景框坐标 → 源图坐标」的换算是唯一的：
 *
 *     srcX = (viewX - offset.x) / scale
 *     srcY = (viewY - offset.y) / scale
 *
 * 把取景框四条边 viewX = 0 / sidePx、viewY = 0 / sidePx 代入，就得到可见源矩形：
 *
 *     srcLeft   = -offset.x / scale
 *     srcTop    = -offset.y / scale
 *     srcRight  = (sidePx - offset.x) / scale
 *     srcBottom = (sidePx - offset.y) / scale
 *
 * 关键：这三个量（scale、offset、graphicsLayer 里的 scale/translation）必须是同一组数。
 * 一旦把 offset 存成别的单位（比如「源图像素」或 dp）再拿去换算，画面和结果就会错位。
 */
private class CropViewport(
    private val bitmap: Bitmap,
    private val sidePx: Float
) {
    private val bitmapWidth: Int = bitmap.width
    private val bitmapHeight: Int = bitmap.height

    /** 刚好铺满取景框（不露空白）的缩放；比它更小就会看到黑边 */
    val minScale: Float =
        if (sidePx > 0f && bitmapWidth > 0 && bitmapHeight > 0) {
            maxOf(sidePx / bitmapWidth, sidePx / bitmapHeight)
        } else {
            1f
        }

    val maxScale: Float = minScale * MAX_ZOOM

    /** 整体缩放：显示宽度 = bitmapWidth * scale */
    var scale by mutableFloatStateOf(minScale)

    /** 显示像素位移：位图左上角相对取景框左上角的位置 */
    var offset by mutableStateOf(Offset.Zero)

    /**
     * 手势回调。[zoomChange] 是相对倍数（1.0 表示没缩），[panChange] 是显示像素位移。
     */
    fun onGesture(zoomChange: Float, panChange: Offset) {
        val target = (scale * zoomChange).coerceIn(minScale, maxScale)

        // 以取景框中心为锚点缩放：先算出「中心点现在对应源图上的哪个位置」，
        // 再让这个位置在新缩放下仍然落在中心。不这么做的话，捏合时内容会朝左上角滑走。
        val ratio = target / scale
        val centerX = sidePx / 2f
        val centerY = sidePx / 2f
        val anchorX = centerX - (centerX - offset.x) * ratio
        val anchorY = centerY - (centerY - offset.y) * ratio

        scale = target
        offset = clamp(Offset(anchorX + panChange.x, anchorY + panChange.y), target)
    }

    /**
     * 把位移夹到「图片始终盖满取景框」的范围内。
     *
     * 显示宽度 = bitmapWidth * scale ≥ sidePx，所以 allowedX = [sidePx - 显示宽度, 0]，
     * 上限是 0（左边不留缝），下限由右边不留缝决定。这个区间在 minScale 下一定非空。
     */
    fun clamp(candidate: Offset, atScale: Float): Offset {
        if (sidePx <= 0f) return Offset.Zero
        val minX = minOf(sidePx - bitmapWidth * atScale, 0f)
        val minY = minOf(sidePx - bitmapHeight * atScale, 0f)
        return Offset(
            candidate.x.coerceIn(minX, 0f),
            candidate.y.coerceIn(minY, 0f)
        )
    }

    /** 当前可见的源图矩形，换算见类注释 */
    fun sourceRect(): RectF = RectF(
        -offset.x / scale,
        -offset.y / scale,
        (sidePx - offset.x) / scale,
        (sidePx - offset.y) / scale
    )

    /**
     * 把取景框里的内容渲染成 [AvatarStore.AVATAR_SIZE] 见方的 JPEG 字节；失败返回空数组。
     *
     * 用 Matrix.setRectToRect(src, dst, FILL) 把「源矩形 → 256x256」的缩放与平移交给系统算，
     * 比手写缩放比例少一处出错的机会（源矩形被兜底夹过之后不再是严格正方形，FILL 也能正确铺满）。
     */
    fun cropToJpeg(): ByteArray {
        if (scale <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) return ByteArray(0)

        val src = sourceRect()
        // 正常情况下 offset 已经被 clamp 过，取景框必然完全在图内；这里只是兜底，
        // 避免越界区域被 Skia 当成透明像素，在边缘画出黑边。
        src.left = src.left.coerceIn(0f, bitmapWidth.toFloat())
        src.top = src.top.coerceIn(0f, bitmapHeight.toFloat())
        src.right = src.right.coerceIn(0f, bitmapWidth.toFloat())
        src.bottom = src.bottom.coerceIn(0f, bitmapHeight.toFloat())
        if (src.width() <= 0f || src.height() <= 0f) return ByteArray(0)

        val edge = AvatarStore.AVATAR_SIZE // 256，和 AvatarStore 落盘尺寸保持一致
        val output = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        matrix.setRectToRect(
            src,
            RectF(0f, 0f, edge.toFloat(), edge.toFloat()),
            Matrix.ScaleToFit.FILL
        )
        // android.graphics.Canvas 与 Compose 的 Canvas 同名，这里全限定书写以示区分
        android.graphics.Canvas(output).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return AvatarStore.encodeJpeg(output)
    }
}

// ======================================================================
// 解码
// ======================================================================

/**
 * 按 [maxEdge] 采样解码相册图片。
 *
 * 先用 inJustDecodeBounds 只读尺寸（不分配像素内存），再由 [sampleSizeFor] 定采样率，
 * 这样即使选中的是 8000px 的原图，峰值内存也只有目标尺寸那一份。
 * 任何失败（URI 失效、权限被撤、格式不支持）都返回 null，交给 UI 显示错误块。
 */
private fun decodeScaled(context: Context, uri: Uri, maxEdge: Int): Bitmap? =
    try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream 之后流会被关闭，所以这里要点两次 URI（相册的流不可 seek，读第二遍最稳）
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
    } catch (t: Throwable) {
        // 包括 SecurityException（URI 权限过期）和 OutOfMemoryError
        null
    }

/**
 * 求一个 2 的幂次采样率，使采样后的长边不超过 [maxEdge]。
 *
 * 只用 2 的幂是因为 BitmapFactory 内部也会把非 2 的幂向上取整到最近的 2 的幂，
 * 自己按 2 的幂算，拿到手的尺寸才是可预期的。
 *
 * 注意：这里没有抽到 AvatarStore 里复用，是为了让本文件不依赖 util 包的内部实现细节，
 * 代价只是 10 行重复代码。
 */
private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    if (maxEdge <= 0) return 1
    val longEdge = if (width > height) width else height
    var sample = 1
    while (longEdge / sample > maxEdge) sample *= 2
    return sample
}
