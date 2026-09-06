package com.liban.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.liban.android.data.*
import com.liban.android.model.*
import java.text.DateFormat
import java.util.Date

private enum class Destination(val label: String) {
    HOME("首页"), BUDGET("预算"), GOAL("目标"), HISTORY("历史"), SETTINGS("设置")
}

@Composable
fun LibanApp(viewModel: AppViewModel, onStartScreenMode: () -> Unit, onStopScreenMode: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF6750A4), secondary = Color(0xFFF4B400))) {
        if (!state.onboarded) {
            OnboardingScreen { budget, goal ->
                viewModel.completeOnboarding(budget, goal)
                onStartScreenMode()
            }
        } else {
            var destination by remember { mutableStateOf(Destination.HOME) }
            Scaffold(bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Text(item.label.take(1)) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (destination) {
                        Destination.HOME -> HomeScreen(state, viewModel, onStartScreenMode, onStopScreenMode)
                        Destination.BUDGET -> BudgetScreen(state.profile, viewModel::saveBudget)
                        Destination.GOAL -> GoalScreen(state.goal, viewModel::saveGoal)
                        Destination.HISTORY -> HistoryScreen(state.decisions, state.transactions)
                        Destination.SETTINGS -> SettingsScreen(state, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingScreen(onComplete: (String, String) -> Unit) {
    var budget by remember { mutableStateOf("5000") }
    var goal by remember { mutableStateOf("10000") }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Text("理伴", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Text("让每一次消费决定更清醒", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(32.dp))
        MoneyField("每月预算", budget) { budget = it }
        Spacer(Modifier.height(12.dp))
        MoneyField("储蓄目标", goal) { goal = it }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onComplete(budget, goal) }, modifier = Modifier.fillMaxWidth()) { Text("保存并进入") }
        Text("截图在设备内识别，不上传、不保存。默认将全屏 OCR 文字发送到已配置的 LLM 提取商品名和价格；可在设置中关闭。请避开含聊天、地址或账户信息的页面。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun HomeScreen(state: AppUiState, viewModel: AppViewModel, onStart: () -> Unit, onStop: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("理伴", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text("消费决策助手") }
        item { BudgetSummary(state.profile) }
        item {
            state.goal?.let {
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(it.name, fontWeight = FontWeight.SemiBold)
                    Text("已存 ${yuan(it.currentAmountCents)} / ${yuan(it.targetAmountCents)}")
                } }
            }
        }
        item {
            Text(if (state.apiConfig.llmOcrEnabled) "智能提取已开启：OCR 文字将发送给 LLM，截图不上传。" else "仅使用设备内文字解析。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("开启识屏模式") }
            TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止识屏服务") }
        }
        item { AnalysisPanel(state.analysis, viewModel) }
        item { state.decisions.firstOrNull()?.let { Text("最近决策", style = MaterialTheme.typography.titleMedium); DecisionHistoryCard(it) } }
    }
}

@Composable
private fun BudgetSummary(profile: UserProfileEntity?) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text("本月预算", style = MaterialTheme.typography.titleMedium)
        if (profile == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
            Text(yuan(profile.monthlyBudgetCents - profile.currentSpentCents), style = MaterialTheme.typography.headlineMedium)
            Text("已消费 ${yuan(profile.currentSpentCents)} · 总额 ${yuan(profile.monthlyBudgetCents)}")
        }
    } }
}

@Composable
private fun AnalysisPanel(state: AnalysisState, viewModel: AppViewModel) {
    when (state) {
        AnalysisState.Idle -> Text("开启后点击悬浮球“理伴一下”")
        AnalysisState.Capturing -> ProgressText("正在取得当前画面…")
        AnalysisState.Recognizing -> ProgressText("正在设备内识别文字…")
        AnalysisState.ExtractingProduct -> ProgressText("正在根据页面文字智能提取商品名、型号和价格…")
        is AnalysisState.PreliminaryResult -> DecisionResultCard(state.scene, state.decision, true) { action ->
            viewModel.recordFeedback(state.decision.decisionId, action, state.decision.delayHours)
        }
        is AnalysisState.EnrichingPrice -> DecisionResultCard(state.scene, state.decision, true) { action ->
            viewModel.recordFeedback(state.decision.decisionId, action, state.decision.delayHours)
        }
        is AnalysisState.Error -> CorrectionForm(state.message, null, viewModel::submitCorrection)
        is AnalysisState.NeedsCorrection -> CorrectionForm(state.message, state.scene, viewModel::submitCorrection)
        is AnalysisState.Result -> DecisionResultCard(state.scene, state.decision, false) { action ->
            viewModel.recordFeedback(state.decision.decisionId, action, state.decision.delayHours)
        }
    }
}

@Composable private fun ProgressText(text: String) = Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(text) }

@Composable
private fun CorrectionForm(message: String, scene: SceneContext?, onSubmit: (String, String, String) -> Unit) {
    var name by remember(scene) { mutableStateOf(scene?.product?.name.orEmpty()) }
    var price by remember(scene) { mutableStateOf(scene?.price?.currentCents?.div(100.0)?.toString().orEmpty()) }
    var category by remember(scene) { mutableStateOf(scene?.product?.category ?: "other") }
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedTextField(name, { name = it }, label = { Text("商品名称（含完整型号）") }, modifier = Modifier.fillMaxWidth())
        MoneyField("价格", price) { price = it }
        OutlinedTextField(category, { category = it }, label = { Text("分类") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSubmit(name, price, category) }, modifier = Modifier.fillMaxWidth()) { Text("确认并分析") }
    } }
}

@Composable
private fun DecisionResultCard(scene: SceneContext, decision: DecisionResult, enriching: Boolean, onFeedback: (UserAction) -> Unit) {
    val uriHandler = LocalUriHandler.current
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(decision.display.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("${scene.product.name} · ${Money(scene.price.currentCents).yuanText()}")
        ProductText.summary(scene.product).takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(scene.price.contextText(), style = MaterialTheme.typography.bodySmall)
        Text(if (scene.sceneType == "ocr_llm") "识别来源：LLM 结构化提取" else "识别来源：本地自动提取",
            style = MaterialTheme.typography.bodySmall)
        Text("风险 ${decision.riskLevel.name} · ${decision.display.summary}")
        decision.display.keyPoints.forEach { Text("• $it") }
        if (enriching) {
            AssistChip(onClick = {}, label = { Text("价格正在查询，本地结论已可用") })
        } else {
            decision.price?.let { price ->
                Text(priceLabel(price), fontWeight = FontWeight.SemiBold)
                if (price.sampleCount > 0) {
                    Text("有效样本 ${price.sampleCount} · 查询 ${date(price.queriedAt)}${if (price.cached) " · 缓存" else ""}", style = MaterialTheme.typography.bodySmall)
                }
                price.rationale?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                price.references.take(3).forEach { ref ->
                    TextButton(onClick = { runCatching { uriHandler.openUri(ref.url) } }) { Text(ref.title, maxLines = 1) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onFeedback(UserAction.PURCHASE) }) { Text("还是买") }
            TextButton(onClick = { onFeedback(UserAction.DELAY) }) { Text("等等") }
            TextButton(onClick = { onFeedback(UserAction.CANCEL) }) { Text("放弃") }
        }
    } }
}

private fun priceLabel(price: PriceComparison): String {
    val source = when (price.evidenceSource) {
        PriceEvidenceSource.SEARCH_VERIFIED -> "实时搜索验证"
        PriceEvidenceSource.SEARCH_ASSISTED_ESTIMATE -> "搜索辅助估价"
        PriceEvidenceSource.MODEL_ESTIMATE -> "模型知识估价"
        PriceEvidenceSource.UNAVAILABLE -> "价格不可用"
    }
    val range = if (price.referenceLowCents != null && price.referenceHighCents != null) {
        " · ${yuan(price.referenceLowCents)}–${yuan(price.referenceHighCents)}"
    } else ""
    return source + range
}

@Composable
private fun BudgetScreen(profile: UserProfileEntity?, onSave: (String, String) -> Unit) {
    var budget by remember(profile) { mutableStateOf(profile?.monthlyBudgetCents?.div(100.0)?.toString().orEmpty()) }
    var spent by remember(profile) { mutableStateOf(profile?.currentSpentCents?.div(100.0)?.toString().orEmpty()) }
    FormScreen("预算设置") {
        MoneyField("月预算", budget) { budget = it }
        MoneyField("已消费", spent) { spent = it }
        Button(onClick = { onSave(budget, spent) }, modifier = Modifier.fillMaxWidth()) { Text("保存预算") }
    }
}

@Composable
private fun GoalScreen(goal: SavingGoalEntity?, onSave: (String, String, String) -> Unit) {
    var name by remember(goal) { mutableStateOf(goal?.name.orEmpty()) }
    var target by remember(goal) { mutableStateOf(goal?.targetAmountCents?.div(100.0)?.toString().orEmpty()) }
    var current by remember(goal) { mutableStateOf(goal?.currentAmountCents?.div(100.0)?.toString().orEmpty()) }
    FormScreen("储蓄目标") {
        OutlinedTextField(name, { name = it }, label = { Text("目标名称") }, modifier = Modifier.fillMaxWidth())
        MoneyField("目标金额", target) { target = it }
        MoneyField("当前金额", current) { current = it }
        Button(onClick = { onSave(name, target, current) }, modifier = Modifier.fillMaxWidth()) { Text("保存目标") }
    }
}

@Composable
private fun FormScreen(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun MoneyField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text("$label（元）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
private fun HistoryScreen(decisions: List<DecisionHistoryEntity>, transactions: List<TransactionEntity>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("决策与消费历史", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        items(decisions, key = { it.decisionId }) { DecisionHistoryCard(it) }
        if (transactions.isNotEmpty()) item { Text("已确认消费", style = MaterialTheme.typography.titleMedium) }
        items(transactions, key = { it.id }) {
            ListItem(headlineContent = { Text(it.name) }, supportingContent = { Text(date(it.timestamp)) }, trailingContent = { Text(yuan(it.amountCents)) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun DecisionHistoryCard(item: DecisionHistoryEntity) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Text(item.productName, fontWeight = FontWeight.SemiBold)
        Text("${yuan(item.priceCents)} · ${item.riskLevel} · ${item.recommendation}")
        val source = if (item.sourceMode in setOf("OCR_LOCAL", "OCR_LLM")) item.priceSource else "旧版记录"
        Text("${item.userAction ?: "待反馈"} · $source · ${date(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
private fun SettingsScreen(state: AppUiState, viewModel: AppViewModel) {
    val config = state.apiConfig
    var llmEndpoint by remember(config.llmEndpoint) { mutableStateOf(config.llmEndpoint) }
    var llmModel by remember(config.llmModel) { mutableStateOf(config.llmModel) }
    var llmKey by remember(config.llmApiKey) { mutableStateOf(config.llmApiKey) }
    var searchBase by remember(config.searchBaseUrl) { mutableStateOf(config.searchBaseUrl) }
    var searchEngine by remember(config.searchEngine) { mutableStateOf(config.searchEngine) }
    var searchKey by remember(config.searchApiKey) { mutableStateOf(config.searchApiKey) }
    var readerEnabled by remember(config.readerEnabled) { mutableStateOf(config.readerEnabled) }
    var llmOcrEnabled by remember(config.llmOcrEnabled) { mutableStateOf(config.llmOcrEnabled) }
    var showLlmKey by remember { mutableStateOf(false) }
    var showSearchKey by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("接口设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("已提供联调预置配置，保存后在本机加密存储。可自行替换或清除密钥。")
            TextButton(onClick = viewModel::restorePresetConfiguration) { Text("恢复预置配置") }
            Text("修改配置后请先保存，再测试连接。", style = MaterialTheme.typography.bodySmall)
        }
        item { Text("OpenAI-compatible 文本 LLM", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(llmEndpoint, { llmEndpoint = it }, label = { Text("Chat Completions Endpoint") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(llmModel, { llmModel = it }, label = { Text("模型名") }, modifier = Modifier.fillMaxWidth()) }
        item { SecretField("API Key${if (config.hasLlmKey) "（已加密保存）" else ""}", llmKey, showLlmKey, { llmKey = it }, { showLlmKey = !showLlmKey }) }
        item {
            Row { Checkbox(llmOcrEnabled, { llmOcrEnabled = it }); Text("使用 LLM 提取商品信息", modifier = Modifier.padding(top = 12.dp)) }
            Text("开启后发送全屏 OCR 文字及文字位置，不发送截图；关闭后仅本地解析。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row { Button(onClick = viewModel::testLlm) { Text("测试 LLM") }; TextButton(onClick = viewModel::clearLlmConfiguration) { Text("清除") } }
            state.connectionMessages["LLM"]?.let { Text(it) }
        }
        item { HorizontalDivider(); Text("智谱联网搜索", style = MaterialTheme.typography.titleMedium) }
        item { OutlinedTextField(searchBase, { searchBase = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(searchEngine, { searchEngine = it }, label = { Text("搜索引擎") }, modifier = Modifier.fillMaxWidth()) }
        item { SecretField("API Key${if (config.hasSearchKey) "（已加密保存）" else ""}", searchKey, showSearchKey, { searchKey = it }, { showSearchKey = !showSearchKey }) }
        item { Row { Checkbox(readerEnabled, { readerEnabled = it }); Text("搜索不足时阅读最多 2 个网页", modifier = Modifier.padding(top = 12.dp)) } }
        item {
            Row { Button(onClick = viewModel::testSearch) { Text("测试搜索") }; TextButton(onClick = viewModel::clearSearchConfiguration) { Text("清除密钥") } }
            state.connectionMessages["SEARCH"]?.let { Text(it) }
        }
        item {
            Button(onClick = {
                viewModel.saveApiConfiguration(llmEndpoint, llmKey, llmModel, searchBase, searchKey, searchEngine, readerEnabled, llmOcrEnabled)
            }, modifier = Modifier.fillMaxWidth()) { Text("加密保存全部配置") }
            state.connectionMessages["SAVE"]?.let { Text(it) }
        }
        item { HorizontalDivider(); Text("接口诊断", style = MaterialTheme.typography.titleMedium) }
        items(state.diagnostics, key = { it.provider }) { diagnostic ->
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                Text(diagnostic.provider, fontWeight = FontWeight.Bold)
                Text("上次成功（历史）：${diagnostic.lastSuccessAt?.let(::date) ?: "无"}")
                Text("耗时：${diagnostic.lastLatencyMs?.let { "${it}ms" } ?: "-"} · HTTP ${diagnostic.lastHttpStatus ?: "-"}")
                diagnostic.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, visible: Boolean, onChange: (String) -> Unit, onToggle: () -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = onToggle) { Text(if (visible) "隐藏" else "显示") } },
    )
}

private fun yuan(cents: Long): String = "¥%,.2f".format(cents / 100.0)
private fun date(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
