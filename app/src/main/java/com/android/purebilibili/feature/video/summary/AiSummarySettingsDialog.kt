package com.android.purebilibili.feature.video.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.android.purebilibili.core.ui.AppContentColors
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppContentCard
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextField
import kotlinx.coroutines.launch

@Composable
fun AiSummarySettingsDialog(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(AiProviderConfig()) }
    var apiKey by remember { mutableStateOf("") }
    var asrApiKey by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var showAsrApiKey by remember { mutableStateOf(false) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var testIsSuccess by remember { mutableStateOf(false) }
    var cacheCount by remember { mutableIntStateOf(0) }

    var showAsrWarningDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        AiProviderSettings.getConfigFlow(context).collect { loaded ->
            config = loaded
            apiKey = AiKeyStore.getLlmApiKey(context)
            asrApiKey = AiKeyStore.getAsrApiKey(context)
            cacheCount = SummaryCacheRepository.getCacheCount(context)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = "AI 视频总结设置",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = AppContentColors.primaryText()
                    )
                }

                AppText(
                    text = "使用你自配的 OpenAI 兼容端点（如 DeepSeek、OpenAI、Ollama 等）。密钥保存在本机 Keystore，绝不上报或云同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppContentColors.secondaryText()
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // LLM API Key
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        text = "API Key",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppTextField(
                            value = if (showApiKey) apiKey else apiKey.map { '•' }.joinToString(""),
                            onValueChange = { apiKey = it.trim() },
                            placeholder = "sk-...",
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showApiKey) "隐藏" else "显示",
                                tint = AppContentColors.secondaryText()
                            )
                        }
                    }
                }

                // Base URL
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        text = "API Base URL",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    AppTextField(
                        value = config.llmBaseUrl,
                        onValueChange = { config = config.copy(llmBaseUrl = it) },
                        placeholder = "https://api.deepseek.com/v1",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Model Name
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        text = "模型名称",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    AppTextField(
                        value = config.llmModel,
                        onValueChange = { config = config.copy(llmModel = it) },
                        placeholder = "deepseek-chat",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 测试连接按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (apiKey.isBlank()) {
                                testStatus = "请先填写 API Key"
                                testIsSuccess = false
                                return@Button
                            }
                            isTesting = true
                            testStatus = "正在测试连接..."
                            scope.launch {
                                val result = CustomAiHttpClient.testConnection(
                                    baseUrl = config.llmBaseUrl,
                                    apiKey = apiKey,
                                    model = config.llmModel
                                )
                                isTesting = false
                                result.onSuccess { latency ->
                                    testIsSuccess = true
                                    testStatus = "连接成功！延迟: ${latency}ms"
                                }.onFailure { e ->
                                    testIsSuccess = false
                                    testStatus = "连接失败: ${e.message}"
                                }
                            }
                        },
                        enabled = !isTesting
                    ) {
                        Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isTesting) "测试中..." else "测试连接")
                    }

                    if (testStatus != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (testIsSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = if (testIsSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = testStatus.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (testIsSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                maxLines = 2
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 开关选项
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        AppText(
                            text = "官方总结优先",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                        AppText(
                            text = "若视频已有 B 站官方总结则直接展示，不消耗 API Key",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppContentColors.secondaryText()
                        )
                    }
                    Switch(
                        checked = config.preferOfficialSummary,
                        onCheckedChange = { config = config.copy(preferOfficialSummary = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        AppText(
                            text = "无字幕时启用 ASR 语音转写",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                        AppText(
                            text = "在无字幕时提取 DASH 音频并调用转写接口（将产生上传与 API 计费）",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppContentColors.secondaryText()
                        )
                    }
                    Switch(
                        checked = config.enableAsr,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showAsrWarningDialog = true
                            } else {
                                config = config.copy(enableAsr = false)
                            }
                        }
                    )
                }

                if (config.enableAsr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            AppText(text = "ASR 仅在 Wi-Fi 下工作", style = MaterialTheme.typography.bodyMedium)
                            AppText(
                                text = "避免移动数据下下载与上传音频流量消耗",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppContentColors.secondaryText()
                            )
                        }
                        Switch(
                            checked = config.asrWifiOnly,
                            onCheckedChange = { config = config.copy(asrWifiOnly = it) }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppText(text = "ASR 模型名称", style = MaterialTheme.typography.labelMedium)
                        AppTextField(
                            value = config.asrModel,
                            onValueChange = { config = config.copy(asrModel = it) },
                            placeholder = "whisper-1",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // 清理缓存
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        AppText(text = "本地总结缓存", style = MaterialTheme.typography.bodyMedium)
                        AppText(
                            text = "当前缓存 $cacheCount 条记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppContentColors.secondaryText()
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                SummaryCacheRepository.clearAllCache(context)
                                cacheCount = 0
                            }
                        }
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空缓存")
                    }
                }

                // 底部保存与取消
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                AiKeyStore.saveLlmApiKey(context, apiKey)
                                if (asrApiKey.isNotBlank()) {
                                    AiKeyStore.saveAsrApiKey(context, asrApiKey)
                                }
                                AiProviderSettings.updateConfig(context, config)
                                onDismiss()
                            }
                        }
                    ) {
                        Text("保存设置")
                    }
                }
            }
        }
    }

    if (showAsrWarningDialog) {
        AlertDialog(
            onDismissRequest = { showAsrWarningDialog = false },
            title = { Text("开启语音转写 (ASR) 说明") },
            text = {
                Text(
                    "开启后，当视频没有任何字幕时，应用将向你询问是否允许上传该视频的音频流到你配置的 ASR 服务提供商端点进行识别。\n\n" +
                    "音频仅发往你自行填写的服务器，绝不会上传用户个人 Cookie、账号信息或发往任何第三方作者服务器。\n\n" +
                    "是否确定开启？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        config = config.copy(enableAsr = true)
                        showAsrWarningDialog = false
                    }
                ) {
                    Text("确认开启")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        config = config.copy(enableAsr = false)
                        showAsrWarningDialog = false
                    }
                ) {
                    Text("放弃")
                }
            }
        )
    }
}
