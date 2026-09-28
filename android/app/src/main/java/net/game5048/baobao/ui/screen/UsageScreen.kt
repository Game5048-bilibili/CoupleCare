package net.game5048.baobao.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.game5048.baobao.data.local.PreferencesManager
import net.game5048.baobao.data.model.PartnerState
import net.game5048.baobao.ui.components.AppIcon
import net.game5048.baobao.ui.components.UsageEmptyState
import net.game5048.baobao.ui.components.UsageTimelineItem
import net.game5048.baobao.util.TimeFormatter

/**
 * 使用记录页。
 *
 * 顶部一张「对方当前在用什么」的卡片，下面是从新到旧的时间线。
 * 时长由 start/end 事件配对算出（见 TrackerRepository），进行中的会话实时累计。
 */
@Composable
fun UsageScreen(
    partnerState: PartnerState,
    settings: PreferencesManager.Settings,
    usagePermissionGranted: Boolean,
    onRequestUsagePermission: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 10 秒刷新一次“现在”，让进行中会话的时长会自己走
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            now = System.currentTimeMillis()
        }
    }

    val sessions = partnerState.sessions
    val ongoing = sessions.firstOrNull { it.ongoing }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ---------- 当前前台应用 ----------
        item(key = "current") {
            CurrentAppCard(
                packageName = ongoing?.packageName,
                appLabel = partnerState.currentForegroundApp,
                startTime = ongoing?.startTime,
                now = now
            )
        }

        // ---------- 自己没给权限时的提醒 ----------
        if (!usagePermissionGranted && settings.reportUsage) {
            item(key = "permission") {
                PermissionBanner(onRequestUsagePermission = onRequestUsagePermission)
            }
        }

        // ---------- 列表标题 ----------
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "使用时间线",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (sessions.isEmpty()) "" else "共 ${sessions.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- 时间线 ----------
        if (sessions.isEmpty()) {
            item(key = "empty") {
                UsageEmptyState(
                    title = if (partnerState.paired) "还没有使用记录" else "还没有配对",
                    description = if (partnerState.paired) {
                        "对方切换前台应用时，这里会自动出现记录"
                    } else {
                        "配对成功后，这里会显示对方最近用过哪些 App"
                    }
                )
            }
        } else {
            itemsIndexed(
                items = sessions,
                key = { index, session -> "${session.packageName}_${session.startTime}_$index" }
            ) { index, session ->
                UsageTimelineItem(
                    session = session,
                    now = now,
                    isLast = index == sessions.lastIndex
                )
            }
        }

        // ---------- 底部刷新 ----------
        item(key = "refresh") {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新拉取历史记录")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** 「对方正在使用 XX」大卡片 */
@Composable
private fun CurrentAppCard(
    packageName: String?,
    appLabel: String?,
    startTime: Long?,
    now: Long
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (appLabel != null) {
                scheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                scheme.surfaceContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (appLabel != null) {
                AppIcon(packageName = packageName.orEmpty(), size = 46.dp)
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (appLabel != null) "对方正在使用" else "对方当前没有在使用手机",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = appLabel ?: "空闲中",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface
                )
                if (appLabel != null && startTime != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "已持续 ${TimeFormatter.duration(now - startTime)} · " +
                                "从 ${TimeFormatter.clock(startTime)} 开始",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (appLabel != null) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** 未授予「使用情况访问权限」时的提示条 */
@Composable
private fun PermissionBanner(onRequestUsagePermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "对方看不到你在用什么应用",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "需要开启「使用情况访问权限」，在系统设置里手动允许本应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onRequestUsagePermission) {
                Text("去开启")
            }
        }
    }
}
