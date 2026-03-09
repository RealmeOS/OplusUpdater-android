package com.houvven.oplusupdater.ui.screen.home.components

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import com.houvven.oplusupdater.R
import com.houvven.oplusupdater.domain.UpdateQueryResponse
import com.houvven.oplusupdater.utils.StorageUnitUtil
import com.houvven.oplusupdater.utils.UrlDecryptUtil
import com.houvven.oplusupdater.utils.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import updater.ResponseResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateQueryResponseCard(
    response: ResponseResult,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (response.responseCode.toInt() != 200) {
        Card {
            MiuixItem(title = "status", summary = response.responseCode.toString())
            MiuixItem(title = "message", summary = response.errMsg)
        }
        return
    }

    runCatching {
        val json = Json { ignoreUnknownKeys = true }
        json.decodeFromString<UpdateQueryResponse>(response.decryptedBodyBytes.decodeToString())
    }.onSuccess {
        UpdateQueryResponseCardContent(modifier = modifier, response = it)
    }.onFailure {
        it.message?.let(context::toast)
    }
}

@Composable
private fun MiuixItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface
        )
        if (!summary.isNullOrBlank()) {
            Text(
                text = summary,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun UpdateQueryResponseCardContent(
    modifier: Modifier = Modifier,
    response: UpdateQueryResponse,
) = with(response) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()

    var showUpdateLogDialog by remember { mutableStateOf(false) }

    val copyAction: (String?) -> Unit = { text ->
        if (!text.isNullOrBlank()) {
            coroutineScope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(text, text).toClipEntry())
            }
            context.toast(R.string.copied)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            MiuixItem(
                title = stringResource(R.string.version_type),
                summary = "$versionTypeH5 ($status)",
                onClick = null
            )
            
            val vName = realVersionName ?: versionName
            MiuixItem(title = stringResource(R.string.version_name), summary = vName, onClick = { copyAction(vName) })

            (realOtaVersion ?: otaVersion)?.let { ota ->
                MiuixItem(title = stringResource(R.string.ota_version), summary = ota, onClick = { copyAction(ota) })
            }

            val androidVer = realAndroidVersion ?: androidVersion
            MiuixItem(title = stringResource(R.string.android_version), summary = androidVer, onClick = { copyAction(androidVer) })

            val osVer = realOsVersion ?: colorOSVersion ?: osVersion
            MiuixItem(title = stringResource(R.string.os_version), summary = osVer, onClick = { copyAction(osVer) })

            val patch = securityPatch ?: securityPatchVendor
            MiuixItem(title = stringResource(R.string.security_patch), summary = patch, onClick = { copyAction(patch) })

            val time = publishedTime?.let { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(it)) }
            MiuixItem(title = stringResource(R.string.published_time), summary = time, onClick = { copyAction(time) })

            description?.panelUrl?.let { url ->
                MiuixItem(
                    modifier = Modifier.combinedClickable(
                        onClick = { showUpdateLogDialog = true },
                        onLongClick = { copyAction(url) }
                    ),
                    title = stringResource(R.string.update_log),
                    summary = url,
                    onClick = null
                )
            }
        }

        components?.forEach { component ->
            val componentPackets = component?.componentPackets ?: return@forEach
            var decryptedUrl by remember { mutableStateOf<String?>(null) }
            var isDecrypting by remember { mutableStateOf(false) }
            var expiresTime by remember { mutableStateOf<String?>(null) }
            var isEncryptedUrl by remember { mutableStateOf(false) }

            LaunchedEffect(componentPackets.manualUrl) {
                componentPackets.manualUrl?.let { url ->
                    if (url.contains("downloadCheck")) {
                        isEncryptedUrl = true
                        isDecrypting = true
                        runCatching { UrlDecryptUtil.resolveUrl(url) }
                            .onSuccess { decryptedUrl = it }
                            .onFailure { decryptedUrl = url }
                            .also { isDecrypting = false }
                    } else {
                        isEncryptedUrl = false
                        decryptedUrl = url
                    }
                }
            }
            
            Card {
                val size = componentPackets.size?.toLongOrNull()?.let(StorageUnitUtil::formatSize)
                
                val pName = component.componentName
                MiuixItem(title = stringResource(R.string.packet_name), summary = pName, onClick = { copyAction(pName) })

                if (size != null) {
                    MiuixItem(title = stringResource(R.string.packet_size), summary = size, onClick = { copyAction(size) })
                }

                LaunchedEffect(decryptedUrl, isEncryptedUrl, lifecycleState) {
                    if (!isEncryptedUrl) return@LaunchedEffect
                    if (lifecycleState == Lifecycle.State.RESUMED) {
                        decryptedUrl?.let { url ->
                            UrlDecryptUtil.extractExpiresTimestamp(url)?.let { timestamp ->
                                while (true) {
                                    val remaining = timestamp - System.currentTimeMillis() / 1000
                                    if (remaining > 0) {
                                        expiresTime = UrlDecryptUtil.formatRemainingTime(timestamp, context)
                                        delay(1000)
                                    } else {
                                        expiresTime = context.getString(R.string.url_expired)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }

                val finalUrl = decryptedUrl ?: componentPackets.url
                val expiredString = stringResource(R.string.url_expired)
                val isExpired = expiresTime == expiredString

                componentPackets.manualUrl?.let {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { copyAction(finalUrl) }
                    ) {
                        MiuixItem(title = stringResource(R.string.packet_url))
                        
                        val summaryColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        val timerLabel = "⏱ ${context.getString(R.string.validity_period)}: $expiresTime"

                        BasicText(
                            modifier = Modifier
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                .offset(y = (-12).dp), 
                            text = buildAnnotatedString {
                                append(if (isDecrypting) context.getString(R.string.url_is_decrypting) else (finalUrl ?: ""))
                                if (isEncryptedUrl && expiresTime != null) {
                                    append("\n")
                                    if (isExpired) {
                                        withStyle(SpanStyle(color = Color(0xFFF44336))) {
                                            append(timerLabel)
                                        }
                                    } else {
                                        append(timerLabel)
                                    }
                                }
                            },
                            style = TextStyle(
                                color = summaryColor,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        )
                    }
                }
                
                componentPackets.md5?.let { md5Value ->
                    MiuixItem(title = stringResource(R.string.packet_md5), summary = md5Value, onClick = { copyAction(md5Value) })
                }
            }
        }
    }

    description?.panelUrl?.let {
        UpdateLogDialog(
            show = showUpdateLogDialog,
            url = it,
            softwareVersion = versionName ?: "Only god known it.",
            onDismissRequest = { showUpdateLogDialog = false }
        )
    }
}