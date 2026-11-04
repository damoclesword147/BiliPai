@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.android.purebilibili.feature.video.ui.section

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppCircularProgressIndicator
import com.android.purebilibili.core.ui.components.AppContentCard
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.data.model.response.AiSummaryData
import com.android.purebilibili.feature.video.note.VideoNoteEditorDocument
import com.android.purebilibili.feature.video.note.VideoNoteUiState
import com.android.purebilibili.feature.video.summary.*
import com.android.purebilibili.feature.video.ui.VideoDetailShapes
import com.android.purebilibili.feature.video.viewmodel.AiSummaryPromptState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * PiliPlus 式信息行右端紧凑入口：AI 总结（AutoAwesome）与视频笔记（EditNote）小图标。
 * 与 PiliPlus 一致，图标只作触发器，内容在 [AiSummarySheet] / [VideoNoteListSheet] 中展示。
 */
@Composable
fun VideoSupplementStatsActions(
    showAiSummary: Boolean,
    showNote: Boolean,
    onAiSummaryClick: () -> Unit,
    onNoteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showAiSummary && !showNote) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAiSummary) {
            SupplementEntryIcon(
                icon = Icons.Outlined.AutoAwesome,
                label = "AI 总结",
                onClick = onAiSummaryClick,
            )
        }
        if (showNote) {
            SupplementEntryIcon(
                icon = Icons.Outlined.EditNote,
                label = "视频笔记",
                onClick = onNoteClick,
            )
        }
    }
}

@Composable
private fun SupplementEntryIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    // 浮于统计行右上的紧凑入口：28dp 点击槽贴合统计行高度，不再借 40dp 按钮撑出空白。
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = AppContentColors.secondaryText(),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 
 * 扩展后的 AI 视频总结 BottomSheet
 * 统一承载 B 站官方总结以及用户自配的 OpenAI / ASR 视频总结能力
 */
@Composable
fun AiSummarySheet(
    visible: Boolean,
    aiSummary: AiSummaryData?,
    promptState: AiSummaryPromptState?,
    onDismiss: () -> Unit,
    onTimestampClick: ((Long) -> Unit)?,
    onRetry: () -> Unit,
    onCreateNoteDraft: () -> Unit,
    videoIdentity: VideoIdentity? = null,
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirmDialog by remember { mutableStateOf(false) }

    val coordinator = remember(videoIdentity?.bvid, videoIdentity?.cid) {
        SummaryCoordinator(context.applicationContext)
    }
    val customState by coordinator.uiState.collectAsState()

    // 判断展示的数据源与对象
    val effectiveSummary: AiSummaryData? = customState.adaptedAiSummary ?: aiSummary
    val isOfficialSummaryActive = (customState.adaptedAiSummary == null && hasAiSummaryContent(aiSummary))
    val currentSource = if (isOfficialSummaryActive) SummarySource.OFFICIAL else customState.source

    // 页面关闭时若有运行中的任务则取消
    DisposableEffect(visible) {
        onDispose {
            currentJob?.cancel()
        }
    }

    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // 顶部标题栏：包含来源徽章与设置入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppText(
                        text = "AI 视频总结",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = AppContentColors.primaryText()
                    )
                    // 来源徽标
                    if (hasAiSummaryContent(effectiveSummary)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = currentSource.badgeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "AI 总结设置",
                        tint = AppContentColors.secondaryText(),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 1. 进行中状态指示 (Loading / Transcribing / Summarizing / Validating)
            val isGenerating = customState.stage in listOf(
                SummaryStage.CHECKING_CACHE,
                SummaryStage.LOADING_TRANSCRIPT,
                SummaryStage.DOWNLOADING_AUDIO,
                SummaryStage.TRANSCRIBING,
                SummaryStage.COMPOSING,
                SummaryStage.SUMMARIZING,
                SummaryStage.VALIDATING
            )

            if (isGenerating) {
                AppContentCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AppCircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Text(
                            text = customState.progressMessage.ifBlank { "正在生成总结..." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppContentColors.primaryText()
                        )
                        if (customState.stage == SummaryStage.DOWNLOADING_AUDIO && customState.progress > 0f) {
                            LinearProgressIndicator(
                                progress = { customState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                currentJob?.cancel()
                                coordinator.reset()
                            }
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("取消")
                        }
                    }
                }
            }
            // 2. 错误状态
            else if (customState.stage == SummaryStage.ERROR) {
                AppContentCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "生成总结失败",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = customState.errorMessage ?: "发生未知错误",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppContentColors.secondaryText()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!customState.isKeyConfigured) {
                                Button(onClick = { showSettingsDialog = true }) {
                                    Text("去配置 API Key")
                                }
                            } else if (customState.canRetry && videoIdentity != null) {
                                Button(onClick = {
                                    currentJob?.cancel()
                                    currentJob = scope.launch {
                                        coordinator.generateSummary(videoIdentity, bypassCache = true)
                                    }
                                }) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                }
            }
            // 3. 拥有成功内容的总结卡片展示
            else if (hasAiSummaryContent(effectiveSummary)) {
                AiSummaryCard(
                    aiSummary = effectiveSummary,
                    onTimestampClick = onTimestampClick,
                    onCreateNoteDraftClick = onCreateNoteDraft,
                    defaultExpanded = true,
                )

                // 操作工具条：复制文本 / 重新生成
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val summaryText = buildString {
                                appendLine(effectiveSummary?.modelResult?.summary.orEmpty())
                                effectiveSummary?.modelResult?.outline?.forEach { out ->
                                    appendLine("[${SummaryComposer.formatDuration(out.timestamp * 1000L)}] ${out.title}")
                                    out.partOutline.forEach { part ->
                                        appendLine("  - ${part.content}")
                                    }
                                }
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("AI Summary", summaryText))
                            Toast.makeText(context, "已复制总结与章节大纲", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制总结")
                    }

                    if (videoIdentity != null) {
                        OutlinedButton(
                            onClick = { showRegenerateConfirmDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isOfficialSummaryActive) "自配 AI 总结" else "重新生成")
                        }
                    }
                }

                Text(
                    text = "内容由 AI 生成，仅供参考",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = AppContentColors.secondaryText().copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
            // 4. 官方原生的提示状态 (排队中、失败等) 或无内容时的生成引导
            else if (promptState != null) {
                AiSummaryPromptCard(
                    promptState = promptState,
                    onActionClick = onRetry,
                )

                // 官方不可用时，若有视频标识，展示「使用自配 AI 生成」引导
                if (videoIdentity != null) {
                    AppContentCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "使用自定义 AI 生成视频总结",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = AppContentColors.primaryText()
                            )
                            Text(
                                text = "将提取视频字幕并调用你配置的 AI 端点生成摘要与分段大纲。",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppContentColors.secondaryText()
                            )
                            Button(
                                onClick = {
                                    if (!AiKeyStore.hasLlmApiKey(context)) {
                                        showSettingsDialog = true
                                    } else {
                                        currentJob?.cancel()
                                        currentJob = scope.launch {
                                            coordinator.generateSummary(videoIdentity)
                                        }
                                    }
                                }
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (AiKeyStore.hasLlmApiKey(context)) "生成自定义总结" else "配置 AI 服务")
                            }
                        }
                    }
                }
            } else if (videoIdentity != null) {
                // 完全没有总结且无 promptState 时的主动生成卡片
                AppContentCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "生成 AI 视频总结",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = AppContentColors.primaryText()
                        )
                        Text(
                            text = "支持通过官方字幕或语音识别进行提炼，可点击章节直接跳转对应时间播放。",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppContentColors.secondaryText()
                        )
                        Button(
                            onClick = {
                                if (!AiKeyStore.hasLlmApiKey(context)) {
                                    showSettingsDialog = true
                                } else {
                                    currentJob?.cancel()
                                    currentJob = scope.launch {
                                        coordinator.generateSummary(videoIdentity)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (AiKeyStore.hasLlmApiKey(context)) "立即生成总结" else "配置 AI 服务")
                        }
                    }
                }
            }
        }
    }

    // ASR 上传确认对话框
    if (customState.showAsrConsentDialog && videoIdentity != null) {
        AlertDialog(
            onDismissRequest = { coordinator.reset() },
            title = { Text("转写音频授权确认") },
            text = {
                Text(
                    "当前视频未提供可用字幕。\n\n" +
                    "是否允许提取该视频的音频片段并上传到你配置的 ASR 服务进行语音转写？\n\n" +
                    "音频仅发往你配置的服务商，不会上传用户个人凭证。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        currentJob?.cancel()
                        currentJob = scope.launch {
                            coordinator.continueWithAsr(videoIdentity)
                        }
                    }
                ) {
                    Text("同意并转写")
                }
            },
            dismissButton = {
                TextButton(onClick = { coordinator.reset() }) {
                    Text("取消")
                }
            }
        )
    }

    // 重新生成确认对话框
    if (showRegenerateConfirmDialog && videoIdentity != null) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirmDialog = false },
            title = { Text("重新生成 AI 总结") },
            text = { Text("重新生成将绕过本地缓存并调用你配置的 AI 服务，可能会产生少量 Token 消耗。是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRegenerateConfirmDialog = false
                        currentJob?.cancel()
                        currentJob = scope.launch {
                            coordinator.generateSummary(videoIdentity, bypassCache = true)
                        }
                    }
                ) {
                    Text("确认重新生成")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // AI 设置弹窗
    AiSummarySettingsDialog(
        visible = showSettingsDialog,
        onDismiss = { showSettingsDialog = false }
    )
}

/** Bottom sheet hosting the video note list and its actions. */
@Composable
fun VideoNoteListSheet(
    visible: Boolean,
    noteState: VideoNoteUiState,
    isLoggedIn: Boolean,
    onDismiss: () -> Unit,
    onCreateOrEditClick: () -> Unit,
    onOfficialEditorClick: () -> Unit = {},
    onRetryClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: (VideoNoteEditorDocument) -> Unit,
    onPublicNoteClick: (Long, String) -> Unit,
    onAuthorClick: (Long) -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    if (!visible) return
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            VideoNoteCard(
                noteState = noteState,
                isLoggedIn = isLoggedIn,
                onCreateOrEditClick = onCreateOrEditClick,
                onRetryClick = onRetryClick,
                onDeleteClick = onDeleteClick,
                onShareClick = onShareClick,
                onPublicNoteClick = onPublicNoteClick,
                onAuthorClick = onAuthorClick,
                onLoadMore = onLoadMore,
                defaultCollapsed = false,
            )
            VideoNoteSheetFooter(
                isLoggedIn = isLoggedIn,
                onOfficialEditorClick = onOfficialEditorClick,
                onCreateOrEditClick = onCreateOrEditClick,
            )
        }
    }
}


/** PiliPlus 式底部操作栏：官方 H5 编辑器 + 内置编辑器双入口。 */
@Composable
private fun VideoNoteSheetFooter(
    isLoggedIn: Boolean,
    onOfficialEditorClick: () -> Unit,
    onCreateOrEditClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppButton(
            onClick = onOfficialEditorClick,
            enabled = isLoggedIn,
            modifier = Modifier.weight(1f),
        ) {
            AppText(if (isLoggedIn) "开始记笔记" else "登录后开始记笔记")
        }
        AppButton(
            onClick = onCreateOrEditClick,
            modifier = Modifier.weight(1f),
        ) {
            AppText("内置编辑器")
        }
    }
    if (!isLoggedIn) {
        AppText(
            text = "官方笔记编辑器需要登录 B 站账号",
            style = MaterialTheme.typography.labelSmall,
            color = AppContentColors.secondaryText(),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
