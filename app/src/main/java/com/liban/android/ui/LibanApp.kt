package com.liban.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.liban.android.ServiceConfiguration
import com.liban.android.data.*
import com.liban.android.demo.DemoScenario
import com.liban.android.model.*
import java.text.DateFormat
import java.util.Date

private enum class Destination(val label: String) {
    HOME("首页"), BUDGET("预算"), GOAL("目标"), HISTORY("历史"), DIAGNOSTICS("诊断")
}

@Composable
fun LibanApp(
    viewModel: AppViewModel,
    serviceConfiguration: ServiceConfiguration,
    onStartScreenMode: () -> Unit,
    onStopScreenMode: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6750A4),
            secondary = Color(0xFFF4B400),
            error = Color(0xFFB3261E),
        )
    ) {
        if (!state.onboarded) {
            OnboardingScreen { budget, goal ->
                viewModel.completeOnboarding(budget, goal)
                onStartScreenMode()
            }
        } else {
            var destination by remember { mutableStateOf(Destination.HOME) }
            Scaffold(
                bottomBar = {
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
                }
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (destination) {
                        Destination.HOME -> HomeScreen(state, viewModel, onStartScreenMode, onStopScreenMode)
                        Destination.BUDGET -> BudgetScreen(state.profile, viewModel::saveBudget)
                        Destination.GOAL -> GoalScreen(state.goal, viewModel::saveGoal)
                        Destination.HISTORY -> HistoryScreen(state.decisions, state.transactions)
                        Destination.DIAGNOSTICS -> DiagnosticsScreen(state.diagnostics, serviceConfiguration)
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
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("理伴", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
        Text("让每一次消费决定更清醒", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(32.dp))
        MoneyField("每月预算", budget) { budget = it }
        Spacer(Modifier.height(12.dp))
        MoneyField("储蓄目标", goal) { goal = it }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onComplete(budget, goal) }, modifier = Modifier.fillMaxWidth()) {
            Text("保存并进入")
        }
        Text(
            "进入后需要单独授权通知、悬浮窗和屏幕捕获。截图只在内存中处理。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun HomeScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onStartScreenMode: () -> Unit,
    onStopScreenMode: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("理伴", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("消费决策助手", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { BudgetSummary(state.profile) }
        item {
            state.goal?.let {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it.name, fontWeight = FontWeight.SemiBold)
                        Text("已存 ${yuan(it.currentAmountCents)} / ${yuan(it.targetAmountCents)}")
                    }
                }
            }
        }
        item {
            Text("固定演示场景", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DemoScenario.entries.forEach { demo ->
                    FilterChip(
                        selected = state.selectedDemo == demo,
                        onClick = { viewModel.selectDemo(demo) },
                        label = { Text("Demo ${demo.name}") },
                    )
                }
            }
        }
        item {
            Button(onClick = onStartScreenMode, modifier = Modifier.fillMaxWidth()) {
                Text("开启识屏模式")
            }
            TextButton(onClick = onStopScreenMode, modifier = Modifier.fillMaxWidth()) {
                Text("停止识屏服务")
            }
        }
        item { AnalysisPanel(state.analysis, viewModel) }
        item {
            state.decisions.firstOrNull()?.let {
                Text("最近决策", style = MaterialTheme.typography.titleMedium)
                DecisionHistoryCard(it)
            }
        }
    }
}

@Composable
private fun BudgetSummary(profile: UserProfileEntity?) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("本月预算", style = MaterialTheme.typography.titleMedium)
            if (profile == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                val remaining = profile.monthlyBudgetCents - profile.currentSpentCents
                Text(yuan(remaining), style = MaterialTheme.typography.headlineMedium)
                Text("已消费 ${yuan(profile.currentSpentCents)} · 总额 ${yuan(profile.monthlyBudgetCents)}")
            }
        }
    }
}

@Composable
private fun AnalysisPanel(state: AnalysisState, viewModel: AppViewModel) {
    when (state) {
        AnalysisState.Idle -> Text("开启后点击悬浮球“理伴一下”")
        AnalysisState.Capturing -> LinearProgressIndicator(Modifier.fillMaxWidth())
        AnalysisState.Analyzing -> Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在综合预算、目标和价格…") }
        is AnalysisState.Error -> CorrectionForm(state.message, null, viewModel::submitCorrection)
        is AnalysisState.NeedsCorrection -> CorrectionForm(state.message, state.scene, viewModel::submitCorrection)
        is AnalysisState.Result -> DecisionResultCard(state.scene, state.decision) { action ->
            viewModel.recordFeedback(state.decision.decisionId, action, state.decision.delayHours)
        }
    }
}

@Composable
private fun CorrectionForm(
    message: String,
    scene: SceneContext?,
    onSubmit: (String, String, String) -> Unit,
) {
    var name by remember(scene) { mutableStateOf(scene?.product?.name.orEmpty()) }
    var price by remember(scene) { mutableStateOf(scene?.price?.currentCents?.div(100.0)?.toString().orEmpty()) }
    var category by remember(scene) { mutableStateOf(scene?.product?.category ?: "other") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
            OutlinedTextField(name, { name = it }, label = { Text("商品名称") }, modifier = Modifier.fillMaxWidth())
            MoneyField("价格", price) { price = it }
            OutlinedTextField(category, { category = it }, label = { Text("分类") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSubmit(name, price, category) }, modifier = Modifier.fillMaxWidth()) { Text("重新分析") }
        }
    }
}

@Composable
private fun DecisionResultCard(scene: SceneContext, decision: DecisionResult, onFeedback: (UserAction) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(decision.display.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${scene.product.name} · ${Money(scene.price.currentCents).yuanText()}")
            Text("风险 ${decision.riskLevel.name} · ${decision.display.summary}")
            decision.display.keyPoints.forEach { Text("• $it") }
            if (decision.sourceMode != SourceMode.LIVE) {
                AssistChip(onClick = {}, label = { Text(if (decision.sourceMode == SourceMode.FALLBACK) "演示回退数据" else "部分实时数据") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onFeedback(UserAction.PURCHASE) }) { Text("还是买") }
                TextButton(onClick = { onFeedback(UserAction.DELAY) }) { Text("等等") }
                TextButton(onClick = { onFeedback(UserAction.CANCEL) }) { Text("放弃") }
            }
        }
    }
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
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun MoneyField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("$label（元）") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
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
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(item.productName, fontWeight = FontWeight.SemiBold)
            Text("${yuan(item.priceCents)} · ${item.riskLevel} · ${item.recommendation}")
            Text("${item.userAction ?: "待反馈"} · ${item.sourceMode} · ${date(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DiagnosticsScreen(diagnostics: List<ApiDiagnosticEntity>, config: ServiceConfiguration) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("服务诊断", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("LLM ${if (config.llmConfigured) "已配置" else "未配置"} · 物价 ${if (config.priceConfigured) "已配置" else "未配置"}")
            Text("未配置或调用失败时，仅固定 Demo 使用显式标记的回退数据。", style = MaterialTheme.typography.bodySmall)
        }
        items(diagnostics, key = { it.provider }) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(it.provider, fontWeight = FontWeight.Bold)
                    Text("最近成功：${it.lastSuccessAt?.let(::date) ?: "无"}")
                    Text("耗时：${it.lastLatencyMs?.let { ms -> "${ms}ms" } ?: "-"} · HTTP ${it.lastHttpStatus ?: "-"}")
                    it.lastError?.let { error -> Text(error, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun yuan(cents: Long): String = "¥%,.2f".format(cents / 100.0)
private fun date(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
