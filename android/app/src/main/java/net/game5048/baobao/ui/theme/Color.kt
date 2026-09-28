package net.game5048.baobao.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 「异地之约」配色。
 *
 * 主色选玫瑰粉（Rose），副色偏暖褐，整体温柔不刺眼；
 * 深色模式下手动调整过对比度，保证夜间看地图时不刺眼但文字仍然清晰。
 *
 * 如果手机是 Android 12+，[BaobaoTheme] 会优先使用系统动态取色（Material You），
 * 这一套颜色只作为 12 以下的回退方案。
 */

// ---------------- 浅色 ----------------
val LightPrimary = Color(0xFFB4194E)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFD9E1)
val LightOnPrimaryContainer = Color(0xFF3F0018)

val LightSecondary = Color(0xFF74565D)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFFFD9E1)
val LightOnSecondaryContainer = Color(0xFF2B151B)

val LightTertiary = Color(0xFF7D5636)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFDCC1)
val LightOnTertiaryContainer = Color(0xFF2E1500)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

val LightBackground = Color(0xFFFFF8F8)
val LightOnBackground = Color(0xFF22191B)
val LightSurface = Color(0xFFFFF8F8)
val LightOnSurface = Color(0xFF22191B)
val LightSurfaceVariant = Color(0xFFF3DDE1)
val LightOnSurfaceVariant = Color(0xFF524347)
val LightSurfaceContainer = Color(0xFFFCEAEC)
val LightOutline = Color(0xFF857377)
val LightOutlineVariant = Color(0xFFD7C1C6)

// ---------------- 深色 ----------------
val DarkPrimary = Color(0xFFFFB1C5)
val DarkOnPrimary = Color(0xFF65002A)
val DarkPrimaryContainer = Color(0xFF8E003B)
val DarkOnPrimaryContainer = Color(0xFFFFD9E1)

val DarkSecondary = Color(0xFFE3BDC5)
val DarkOnSecondary = Color(0xFF422931)
val DarkSecondaryContainer = Color(0xFF5A3F47)
val DarkOnSecondaryContainer = Color(0xFFFFD9E1)

val DarkTertiary = Color(0xFFF0BD92)
val DarkOnTertiary = Color(0xFF47290D)
val DarkTertiaryContainer = Color(0xFF623F21)
val DarkOnTertiaryContainer = Color(0xFFFFDCC1)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

val DarkBackground = Color(0xFF191113)
val DarkOnBackground = Color(0xFFF0DEE1)
val DarkSurface = Color(0xFF191113)
val DarkOnSurface = Color(0xFFF0DEE1)
val DarkSurfaceVariant = Color(0xFF524347)
val DarkOnSurfaceVariant = Color(0xFFD7C1C6)
val DarkSurfaceContainer = Color(0xFF261D1F)
val DarkOutline = Color(0xFF9F8C90)
val DarkOutlineVariant = Color(0xFF524347)

// ---------------- 语义化扩展色（状态提示用） ----------------
/** 在线 / 正常 */
val StatusOnline = Color(0xFF2E7D32)
val StatusOnlineDark = Color(0xFF81C784)

/** 离线 / 异常 */
val StatusOffline = Color(0xFF9E9E9E)
val StatusOfflineDark = Color(0xFFBDBDBD)

/** 充电中 */
val StatusCharging = Color(0xFF00897B)
val StatusChargingDark = Color(0xFF4DB6AC)

/** 低电量告警阈值 */
const val LOW_BATTERY_THRESHOLD = 20
val StatusLowBattery = Color(0xFFD84315)
val StatusLowBatteryDark = Color(0xFFFF8A65)
