package io.github.stwbeery.globallivetranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.stwbeery.globallivetranslator.data.AppSettings
import io.github.stwbeery.globallivetranslator.data.ProxyMode
import io.github.stwbeery.globallivetranslator.data.SecureSettingsStore
import io.github.stwbeery.globallivetranslator.service.TranslationService
import io.github.stwbeery.globallivetranslator.state.TranslationPhase
import io.github.stwbeery.globallivetranslator.state.TranslationStateStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore = SecureSettingsStore(this)
        setContent {
            TranslatorTheme {
                TranslatorApp(settingsStore)
            }
        }
    }
}

@Composable
private fun TranslatorApp(settingsStore: SecureSettingsStore) {
    val context = LocalContext.current
    val serviceState by TranslationStateStore.state.collectAsState()
    val initial = remember { settingsStore.load() }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var targetLanguage by rememberSaveable { mutableStateOf(initial.targetLanguage) }
    var proxyMode by rememberSaveable { mutableStateOf(initial.proxyMode) }
    var proxyHost by rememberSaveable { mutableStateOf(initial.proxyHost) }
    var proxyPort by rememberSaveable { mutableStateOf(initial.proxyPort.toString()) }
    var overlayEnabled by rememberSaveable { mutableStateOf(initial.overlayEnabled) }
    var vadThreshold by rememberSaveable { mutableStateOf(initial.vadThresholdDb.toInt().toString()) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    var savedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var recordAudioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            TranslationService.start(context, result.resultCode, data)
        } else {
            formError = "需要允许系统录屏，Android 才能捕获其他 App 的声音"
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        recordAudioGranted = granted
        if (!granted) formError = "请允许录音权限；Android 的内部音频捕获接口要求该权限"
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted) formError = null
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val running = serviceState.phase !in setOf(TranslationPhase.IDLE, TranslationPhase.ERROR)
    Scaffold(
        containerColor = AppColors.Background,
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Header(serviceState.phase)
            Spacer(Modifier.height(18.dp))
            CaptionPreview(
                translatedText = serviceState.translatedText,
                status = serviceState.status,
                phase = serviceState.phase,
            )
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    formError = null
                    savedMessage = null
                    if (running) {
                        TranslationService.stop(context)
                    } else {
                        val settings = AppSettings(
                            apiKey = apiKey,
                            targetLanguage = targetLanguage,
                            proxyMode = proxyMode,
                            proxyHost = proxyHost,
                            proxyPort = proxyPort.toIntOrNull() ?: 0,
                            overlayEnabled = overlayEnabled,
                            vadThresholdDb = vadThreshold.toFloatOrNull() ?: Float.NaN,
                        )
                        val error = settings.validate()
                        when {
                            error != null -> formError = error
                            !recordAudioGranted -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            overlayEnabled && !Settings.canDrawOverlays(context) -> {
                                overlayPermissionLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                                formError = "请允许悬浮窗权限，然后返回再启动"
                            }
                            else -> {
                                settingsStore.save(settings)
                                val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
                                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) AppColors.Stop else AppColors.Signal,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text(
                    if (running) "停止同传" else "开始捕获并翻译",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            AnimatedVisibility(serviceState.error != null || formError != null) {
                ErrorMessage(serviceState.error ?: formError.orEmpty())
            }

            SectionHeader("权限与显示", "内部声音需要系统录屏授权；不会保存视频画面")
            PermissionRow(
                title = "内部音频权限",
                detail = if (recordAudioGranted) "录音权限已允许" else "需要允许录音权限",
                granted = recordAudioGranted,
                action = if (!recordAudioGranted) {
                    { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                } else null,
            )
            PermissionRow(
                title = "悬浮字幕",
                detail = if (overlayGranted) "可覆盖显示在视频上" else "需要允许显示在其他应用上层",
                granted = overlayGranted,
                action = {
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示悬浮字幕", fontWeight = FontWeight.Medium, color = AppColors.Ink)
                    Text("只保留当前一句译文", color = AppColors.Muted, fontSize = 13.sp)
                }
                Switch(checked = overlayEnabled, onCheckedChange = { overlayEnabled = it })
            }

            SectionHeader("Gemini", "密钥使用 Android Keystore 加密，只保存在本机")
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("AIza…") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = targetLanguage,
                onValueChange = { targetLanguage = it },
                label = { Text("目标语言代码") },
                supportingText = { Text("简体中文使用 zh-Hans") },
                singleLine = true,
                enabled = !running,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Clash Meta", "手机 VPN/TUN 已开启时选择系统网络，无需代理地址")
            ProxyModeField(proxyMode, !running) { proxyMode = it }
            AnimatedVisibility(proxyMode != ProxyMode.SYSTEM) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = { proxyHost = it },
                        label = { Text("地址") },
                        singleLine = true,
                        enabled = !running,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1.6f),
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = { proxyPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SectionHeader("声音检测", "数值越低越容易触发；环境安静可保持 -50")
            OutlinedTextField(
                value = vadThreshold,
                onValueChange = { value -> vadThreshold = value.filter { it.isDigit() || it == '-' }.take(3) },
                label = { Text("语音阈值 dB") },
                supportingText = { Text("可用范围 -80 到 -10") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                enabled = !running,
                onClick = {
                    val settings = AppSettings(
                        apiKey = apiKey,
                        targetLanguage = targetLanguage,
                        proxyMode = proxyMode,
                        proxyHost = proxyHost,
                        proxyPort = proxyPort.toIntOrNull() ?: 0,
                        overlayEnabled = overlayEnabled,
                        vadThresholdDb = vadThreshold.toFloatOrNull() ?: Float.NaN,
                    )
                    settings.validate()?.let { formError = it } ?: run {
                        settingsStore.save(settings)
                        formError = null
                        savedMessage = "设置已保存"
                    }
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("保存设置")
            }
            savedMessage?.let { Text(it, color = AppColors.Signal, fontSize = 13.sp) }
            Spacer(Modifier.height(18.dp))
            Text(
                "限制：部分 DRM 视频、通话和明确禁止捕获的 App 不会提供内部音频。首次使用必须确认系统录屏提示。",
                color = AppColors.Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }

    LaunchedEffect(serviceState.phase) {
        overlayGranted = Settings.canDrawOverlays(context)
    }
}

@Composable
private fun Header(phase: TranslationPhase) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(11.dp)
                .background(phaseColor(phase), CircleShape),
        )
        Column(Modifier.padding(start = 11.dp)) {
            Text(
                "全局实时同传",
                color = AppColors.Ink,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            )
            Text("ANDROID · 内部音频", color = AppColors.Muted, fontSize = 11.sp, letterSpacing = 1.2.sp)
        }
    }
}

@Composable
private fun CaptionPreview(translatedText: String, status: String, phase: TranslationPhase) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Caption, RoundedCornerShape(8.dp))
            .height(130.dp),
    ) {
        Box(Modifier.fillMaxSize().weight(0.035f).background(phaseColor(phase)))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 17.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                status,
                color = Color(0xFF9BA6A1),
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                translatedText.ifBlank { "字幕会显示在这里" },
                color = if (translatedText.isBlank()) Color(0xFF7F8984) else Color.White,
                fontSize = 21.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, description: String) {
    Spacer(Modifier.height(23.dp))
    Divider(color = AppColors.Divider)
    Spacer(Modifier.height(17.dp))
    Text(title, color = AppColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    Text(description, color = AppColors.Muted, fontSize = 12.sp, lineHeight = 17.sp)
    Spacer(Modifier.height(11.dp))
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    action: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (granted) AppColors.Signal else AppColors.Warning, CircleShape),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, color = AppColors.Ink, fontWeight = FontWeight.Medium)
            Text(detail, color = AppColors.Muted, fontSize = 12.sp)
        }
        if (action != null) TextButton(onClick = action) { Text(if (granted) "设置" else "允许") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyModeField(value: ProxyMode, enabled: Boolean, onChange: (ProxyMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        ProxyMode.SYSTEM to "系统网络（Clash VPN/TUN）",
        ProxyMode.HTTP to "手动 HTTP 代理",
        ProxyMode.SOCKS to "手动 SOCKS 代理",
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = !expanded }) {
        OutlinedTextField(
            value = labels.getValue(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("连接方式") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProxyMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(labels.getValue(mode)) },
                    onClick = {
                        onChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Text(
        message,
        color = AppColors.Error,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(Color(0xFFFFEFF0), RoundedCornerShape(8.dp))
            .padding(12.dp),
    )
}

private fun phaseColor(phase: TranslationPhase): Color = when (phase) {
    TranslationPhase.TRANSLATING -> AppColors.Signal
    TranslationPhase.CONNECTING, TranslationPhase.RECONNECTING, TranslationPhase.STOPPING -> AppColors.Warning
    TranslationPhase.ERROR -> AppColors.Error
    TranslationPhase.IDLE, TranslationPhase.WAITING_FOR_AUDIO -> Color(0xFF84908A)
}

private object AppColors {
    val Background = Color(0xFFF6F8F7)
    val Ink = Color(0xFF151A18)
    val Muted = Color(0xFF68736E)
    val Signal = Color(0xFF167A55)
    val Warning = Color(0xFFB56B12)
    val Error = Color(0xFFB73A45)
    val Stop = Color(0xFF9C3942)
    val Caption = Color(0xFF171B1A)
    val Divider = Color(0xFFD8DEDB)
}

@Composable
private fun TranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AppColors.Signal,
            onPrimary = Color.White,
            background = AppColors.Background,
            onBackground = AppColors.Ink,
            surface = Color.White,
            onSurface = AppColors.Ink,
            error = AppColors.Error,
        ),
        content = content,
    )
}
