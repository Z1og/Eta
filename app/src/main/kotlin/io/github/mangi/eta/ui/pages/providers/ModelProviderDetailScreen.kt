@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.OpenCodeEmbeddedGateway
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaOverlayDialog
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowSpinnerPreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixPageBottomSpacer
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var createdId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    val draft = remember(newType) {
        when (newType) {
            NewProviderType.OpenAiCompatible -> CustomProviderSetting(
                id = "",
                name = "",
                baseUrl = "",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
            NewProviderType.Anthropic -> AnthropicProviderSetting(
                id = "",
                name = "",
                baseUrl = "https://api.anthropic.com",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        MiuixScaffoldPage(
            title = stringResource(R.string.route_provider_details),
            onBack = onBack,
        ) {
            item(key = "missing_provider") {
                Column(
                    modifier = Modifier.fillParentMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.ui_provider_does_not_exist_83cee6))
                    Spacer(modifier = Modifier.height(12.dp))
                    EtaTextButton(text = stringResource(R.string.ui_return_11d024), onClick = onBack)
                }
            }
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew) context.getString(R.string.page_create_new_provider_36cab9) else initial.name

    MiuixScaffold(title = title, onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            if (!isNew) {
                TabRow(
                    tabs = listOf(context.getString(R.string.page_configuration_d7d7ce), context.getString(R.string.page_model_98fd0c)),
                    selectedTabIndex = currentTab,
                    onTabSelected = { currentTab = it },
                    modifier = Modifier.padding(
                        horizontal = sidePadding + EtaPreferenceDefaults.SidePadding,
                        vertical = 8.dp,
                    ),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> ProviderConfigTab(
                        provider = initial,
                        draft = configDraft,
                        onDraftChange = { configDraft = it },
                        scope = scope,
                        isNew = isNew,
                        scrollBehavior = scrollBehavior,
                        contentSidePadding = sidePadding,
                        onCreated = { id -> createdId = id },
                        onDeleted = onBack,
                    )
                    1 -> if (!isNew) {
                        ProviderModelsTab(
                            provider = initial,
                            scope = scope,
                            scrollBehavior = scrollBehavior,
                            contentSidePadding = sidePadding,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    scrollBehavior: ScrollBehavior,
    contentSidePadding: Dp,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }
    val hasEmbeddedOpenCode = provider.id == BuiltinProviders.OPENCODE_ID
    var localOpenCode by remember(provider.id) {
        mutableStateOf(hasEmbeddedOpenCode && OpenCodeEmbeddedGateway.enabled())
    }
    var localGatewayBusy by remember { mutableStateOf(false) }
    var localGatewayStatus by remember { mutableStateOf<String?>(null) }
    var localZenKey by remember(provider.id) { mutableStateOf(if (hasEmbeddedOpenCode) OpenCodeEmbeddedGateway.zenKey() else "") }
    var localGoKey by remember(provider.id) { mutableStateOf(if (hasEmbeddedOpenCode) OpenCodeEmbeddedGateway.goKey() else "") }
    var localUpstreamProxy by remember(provider.id) {
        mutableStateOf(if (hasEmbeddedOpenCode) OpenCodeEmbeddedGateway.upstreamProxy() else "")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // 低层 MiuixScaffold 只负责把顶栏 Insets 传给调用方，输入法 Insets 由列表自行消费。
            .imePadding()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = contentSidePadding,
            end = contentSidePadding,
        ),
        overscrollEffect = null,
    ) {
        item(key = "connection") {
            ProviderSection(title = stringResource(R.string.ui_connection_configuration_7d057b)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it)) },
                        label = stringResource(R.string.ui_name_1be7ae),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = draft.baseUrl,
                        onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                        label = "Base URL",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = draft.apiKey,
                        onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                        label = "API Key",
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = if (apiKeyVisible) context.getString(R.string.page_hide_bb0e7e) else context.getString(R.string.page_show_71b677),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.OPENCODE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (hasEmbeddedOpenCode) {
                                "上面的 Base URL / API Key 是外部 API 备用配置；切换到外部模式后生效。" +
                                    "本地模式使用手机内置网关、随机访问密钥及 127.0.0.1。" +
                                    "上游是否允许免费推理仍取决于 OpenCode 的实际授权。"
                            } else {
                                "自建 opencode2api 网关请使用 HTTPS /v1 地址及其 server_key。" +
                                    "模型目录公开不代表免费推理已获授权。"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (provider is AnthropicProviderSetting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            value = draft.anthropicVersion,
                            onValueChange = { onDraftChange(draft.copy(anthropicVersion = it)) },
                            label = "anthropic-version",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (hasEmbeddedOpenCode) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaSwitchPreference(
                        title = "手机内置 opencode2api 网关",
                        summary = "默认开启：网关在本机 127.0.0.1 运行，无需 VPS；关闭即使用上方填写的外部 API 配置",
                        checked = localOpenCode,
                        enabled = !isWorking && !localGatewayBusy,
                        onCheckedChange = { selected ->
                            scope.launch {
                                localGatewayBusy = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        OpenCodeEmbeddedGateway.setEnabled(selected)
                                    }
                                    localOpenCode = selected
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                    localGatewayStatus = if (selected) {
                                        "已选择本地模式，首次调用会自动启动网关"
                                    } else "已切换外部 API，保留原地址和 Key"
                                } catch (e: Exception) {
                                    localGatewayStatus = "切换失败：${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    localGatewayBusy = false
                                }
                            }
                        },
                    )
                    if (localOpenCode) {
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = if (localGatewayBusy) "正在启动并检查服务…" else "启动并检测本地网关",
                            summary = localGatewayStatus ?: OpenCodeEmbeddedGateway.status(),
                            enabled = !localGatewayBusy,
                            onClick = {
                                scope.launch {
                                    localGatewayBusy = true
                                    localGatewayStatus = "正在启动 Android ARM64 本地服务…"
                                    try {
                                        localGatewayStatus = withContext(Dispatchers.IO) {
                                            OpenCodeEmbeddedGateway.ensureRunning()
                                            OpenCodeEmbeddedGateway.catalogueReadiness() +
                                                "；上游出口：" + OpenCodeEmbeddedGateway.upstreamProxyDescription()
                                        }
                                    } catch (e: Exception) {
                                        localGatewayStatus = "启动失败：${e.message ?: e.javaClass.simpleName}"
                                    } finally {
                                        localGatewayBusy = false
                                    }
                                }
                            },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "上游网络代理：留空时自动使用 Android 系统 HTTP 代理，否则直连。" +
                                    "如需手动代理，可填 http://127.0.0.1:端口 或 socks5://127.0.0.1:端口；" +
                                    "127.0.0.1 指手机，不是电脑。填 direct 可强制直连。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = localUpstreamProxy,
                                onValueChange = { localUpstreamProxy = it },
                                label = "HTTP / SOCKS5 代理（可选）",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            EtaTextButton(
                                text = "保存上游代理并重启网关",
                                enabled = !localGatewayBusy,
                                onClick = {
                                    scope.launch {
                                        localGatewayBusy = true
                                        try {
                                            withContext(Dispatchers.IO) {
                                                OpenCodeEmbeddedGateway.setUpstreamProxy(localUpstreamProxy)
                                            }
                                            localGatewayStatus = "已保存；下次调用自动重启。" +
                                                OpenCodeEmbeddedGateway.upstreamProxyDescription()
                                        } catch (e: Exception) {
                                            localGatewayStatus = "代理配置无效：${e.message ?: e.javaClass.simpleName}"
                                        } finally {
                                            localGatewayBusy = false
                                        }
                                    }
                                },
                            )
                        }
                        EtaPreferenceDivider(hasLeading = false)
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "可选：填写合法的 Zen / Go 上游 Key；没有 Key 时仅使用网关支持的匿名模式。" +
                                    "网关只暴露免费模型，但上游有权限制匿名调用。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = localZenKey,
                                onValueChange = { localZenKey = it },
                                label = "Zen 上游 Key（可选）",
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = localGoKey,
                                onValueChange = { localGoKey = it },
                                label = "Go 上游 Key（可选）",
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            EtaTextButton(
                                text = "保存上游 Key",
                                enabled = !localGatewayBusy,
                                onClick = {
                                    scope.launch {
                                        localGatewayBusy = true
                                        try {
                                            withContext(Dispatchers.IO) {
                                                OpenCodeEmbeddedGateway.setUpstreamKeys(localZenKey, localGoKey)
                                            }
                                            localGatewayStatus = "上游 Key 已保存，网关将在下一次请求时重启"
                                        } catch (e: Exception) {
                                            localGatewayStatus = "保存失败：${e.message ?: e.javaClass.simpleName}"
                                        } finally {
                                            localGatewayBusy = false
                                        }
                                    }
                                },
                            )
                        }
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = "停止内置网关",
                            summary = "停止本机子进程；下次拉取或发送时会自动启动",
                            enabled = !localGatewayBusy,
                            onClick = {
                                scope.launch {
                                    localGatewayBusy = true
                                    withContext(Dispatchers.IO) { OpenCodeEmbeddedGateway.stop() }
                                    localGatewayStatus = "已停止，下次需要时自动启动"
                                    localGatewayBusy = false
                                }
                            },
                        )
                    }
                }
                if (provider !is AnthropicProviderSetting) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaWindowSpinnerPreference(
                        items = listOf(
                            DropdownItem(text = "Chat Completions API"),
                            DropdownItem(text = "Responses API"),
                        ),
                        selectedIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0,
                        title = stringResource(R.string.ui_endpoint_mode_3c8546),
                        summary = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                            context.getString(R.string.page_using_typed_items_with_semantic_streaming_events_f9c906)
                        } else {
                            context.getString(R.string.page_use_standard_chat_completions_ee4b1a)
                        },
                        onSelectedIndexChange = { selectedIndex ->
                            onDraftChange(
                                draft.copy(
                                    endpointMode = if (selectedIndex == 1) {
                                        OpenAiEndpointMode.RESPONSES
                                    } else {
                                        OpenAiEndpointMode.CHAT_COMPLETIONS
                                    },
                                ),
                            )
                        },
                    )
                    if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                        EtaPreferenceDivider(hasLeading = false)
                        EtaSwitchPreference(
                            title = stringResource(R.string.ui_server_side_web_search_ddb8e0),
                            summary = stringResource(R.string.ui_allows_the_model_to_call_web_searches_provided_by_th_2f752f),
                            checked = draft.hostedWebSearchEnabled,
                            onCheckedChange = {
                                onDraftChange(draft.copy(hostedWebSearchEnabled = it))
                            },
                        )
                    }
                }
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(
                    title = stringResource(R.string.ui_test_connection_10b7d8),
                    summary = testStatus,
                    enabled = !isWorking,
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            testStatus = context.getString(R.string.provider_error, validationError)
                            return@EtaPreference
                        }
                        scope.launch {
                            isWorking = true
                            testStatus = context.getString(R.string.page_testing_f43705)
                            try {
                                testStatus = testConnection(
                                    context,
                                    buildUpdatedProvider(
                                        source = provider,
                                        name = draft.name,
                                        baseUrl = draft.baseUrl,
                                        apiKey = draft.apiKey,
                                        systemPrompt = draft.systemPrompt,
                                        isEnabled = draft.isEnabled,
                                        endpointMode = draft.endpointMode,
                                        hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                                        anthropicVersion = draft.anthropicVersion,
                                        customHeaders = draft.headers.map { it.header },
                                    )
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
            }
        }

        providerHeadersEditor(
            headers = draft.headers,
            expanded = headersExpanded,
            onExpandedChange = { headersExpanded = it },
            onHeadersChange = { onDraftChange(draft.copy(headers = it)) },
        )

        item(key = "preferences_and_prompt") {
            ProviderSection(title = stringResource(R.string.ui_preferences_and_strategies_2abd3c)) {
                EtaSwitchPreference(
                    title = stringResource(R.string.ui_enable_this_provider_683a76),
                    checked = draft.isEnabled,
                    onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) }
                )
                EtaPreferenceDivider(hasLeading = false)
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.systemPrompt,
                        onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
                        label = stringResource(R.string.ui_system_prompt_word_193981),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        singleLine = false,
                    )
                    Text(
                        text = stringResource(R.string.ui_leave_blank_to_use_the_default_mobile_agent_prompt_w_21e7c8),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item(key = "actions") {
            // 操作分层：主按钮实心独占，次要操作降级为文字按钮，与弹窗按钮语言一致
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EtaTextButton(
                    text = when {
                        isWorking -> context.getString(R.string.page_saving_d70d42)
                        creationCommitted -> context.getString(R.string.page_created_62cfc5)
                        isNew -> context.getString(R.string.page_create_fcbd09)
                        else -> context.getString(R.string.page_save_configuration_817af1)
                    },
                    enabled = !isWorking && !creationCommitted,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val validationError = validateProviderDraft(context, draft)
                        if (validationError != null) {
                            status = context.getString(R.string.provider_error, validationError)
                            return@EtaTextButton
                        }
                        scope.launch {
                            isWorking = true
                            val built = buildUpdatedProvider(
                                source = provider,
                                name = draft.name,
                                baseUrl = draft.baseUrl,
                                apiKey = draft.apiKey,
                                systemPrompt = draft.systemPrompt,
                                isEnabled = draft.isEnabled,
                                endpointMode = draft.endpointMode,
                                hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                                anthropicVersion = draft.anthropicVersion,
                                customHeaders = draft.headers.map { it.header },
                            )
                            try {
                                if (isNew) {
                                    val added = ProviderRepository.addProvider(
                                        built.withId(ProviderRepository.newId())
                                    )
                                    if (added.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(added.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        EtaApp.serviceInstance
                                    )
                                    status = context.getString(R.string.capability_provider_created)
                                    creationCommitted = true
                                    onCreated(added.id)
                                } else {
                                    ProviderRepository.updateProvider(built)
                                    if (built.isEnabled) {
                                        RuntimeConfigRepository.setSelectedProviderId(built.id)
                                    }
                                    RuntimeConfigRepository.syncToRemotePreferences(
                                        EtaApp.serviceInstance
                                    )
                                    status = when {
                                        !built.isEnabled -> context.getString(R.string.page_saved_provider_not_enabled_7afa54)
                                        else -> context.getString(R.string.capability_provider_saved)
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                status = context.getString(
                                    R.string.provider_error,
                                    throwable.message ?: context.getString(R.string.provider_save_failed),
                                )
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                )
                status?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (message.startsWith(context.getString(R.string.page_fail_3e3c80))) StatusError else StatusSuccess,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (!isNew) {
            item(key = "danger_zone") {
                EtaCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp),
                    showIndication = true,
                    onClick = if (isWorking) {
                        null
                    } else {
                        {
                            if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                        }
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (provider.isBuiltIn) context.getString(R.string.page_reset_built_in_configuration_35b6ec) else context.getString(R.string.page_remove_provider_9f848f),
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            MiuixPageBottomSpacer()
        }
    }

    if (showDeleteDialog) {
        EtaOverlayDialog(
            show = true,
            title = stringResource(R.string.ui_remove_provider_9f848f),
            summary = stringResource(R.string.provider_delete_summary, provider.name),
            onDismissRequest = { if (!isWorking) showDeleteDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isWorking) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                destructive = true,
                onCancel = { showDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        isWorking = true
                        try {
                            ProviderRepository.deleteProvider(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            showDeleteDialog = false
                            onDeleted()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_delete_failed),
                            )
                            showDeleteDialog = false
                        } finally {
                            isWorking = false
                        }
                    }
                },
            )
        }
    }

    if (showResetDialog) {
        EtaOverlayDialog(
            show = true,
            title = stringResource(R.string.ui_reset_built_in_configuration_35b6ec),
            summary = stringResource(R.string.provider_reset_summary, provider.name),
            onDismissRequest = { if (!isWorking) showResetDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isWorking) context.getString(R.string.page_resetting_616090) else context.getString(R.string.page_reset_3d8134),
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                // 重置会覆盖用户改过的连接与模型配置，与删除同属破坏性确认。
                destructive = true,
                onCancel = { showResetDialog = false },
                onConfirm = {
                    scope.launch {
                        isWorking = true
                        try {
                            ProviderRepository.resetBuiltIn(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            status = context.getString(R.string.page_reset_a0cc65)
                            showResetDialog = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_reset_failed),
                            )
                            showResetDialog = false
                        } finally {
                            isWorking = false
                        }
                    }
                },
            )
        }
    }
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String =
    RemoteModelFetcher.fetch(provider)
        .map { models ->
            if (ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.OPENCODE) {
                "OpenCode 目录连接成功（${models.size} 个模型）。这只验证模型列表，" +
                    "不代表免费推理已获授权；需选择模型发送测试消息。"
            } else {
                context.resources.getQuantityString(R.plurals.provider_models_fetched, models.size, models.size)
            }
        }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
