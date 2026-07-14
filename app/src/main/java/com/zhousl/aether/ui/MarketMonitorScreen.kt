package com.zhousl.aether.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhousl.aether.data.AlertType
import com.zhousl.aether.data.MarketIndex
import com.zhousl.aether.data.MarketSnapshot
import com.zhousl.aether.data.SectorSort
import com.zhousl.aether.data.SectorItem
import com.zhousl.aether.data.SectorType
import com.zhousl.aether.data.WatchlistEntry
import com.zhousl.aether.data.WatchlistQuote
import com.zhousl.aether.ui.theme.AetherBackground
import com.zhousl.aether.ui.theme.AetherOnSurface
import com.zhousl.aether.ui.theme.AetherOnSurfaceVariant
import com.zhousl.aether.ui.theme.AetherPrimary
import com.zhousl.aether.ui.theme.AetherSurface
import com.zhousl.aether.ui.theme.AetherSurfaceHigh
import com.zhousl.aether.ui.theme.AetherSurfaceHigher
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ── 涨跌颜色（A股：红涨绿跌）────────────────────────────────────────────────

private val RiseColor = Color(0xFFE84040)
private val FallColor = Color(0xFF0FBF6F)
private val FlatColor @Composable get() = AetherOnSurfaceVariant

@Composable
private fun changeColor(pct: Double): Color = when {
    pct > 0 -> RiseColor
    pct < 0 -> FallColor
    else -> FlatColor
}

private fun formatPct(pct: Double): String {
    val sign = if (pct > 0) "+" else ""
    return "${sign}${String.format(java.util.Locale.US, "%.2f", pct)}%"
}

private fun formatPrice(price: Double): String = String.format(java.util.Locale.US, "%.2f", price)

// ── 入口 Screen ─────────────────────────────────────────────────────────────

@Composable
fun MarketMonitorScreen(
    onBack: () -> Unit,
    viewModel: MarketMonitorViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.startMonitoring()
        onDispose { viewModel.stopMonitoring() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherBackground)
            .statusBarsPadding(),
    ) {
        MonitorTopBar(
            isMarketOpen = uiState.snapshot.isMarketOpen,
            onBack = onBack,
            onRefresh = viewModel::refreshNow,
        )

        var selectedTab by remember { mutableIntStateOf(0) }
        MonitorTabRow(
            selectedIndex = selectedTab,
            tabs = listOf("大盘", "板块", "自选"),
            onTabSelected = { selectedTab = it },
        )

        when (selectedTab) {
            0 -> MarketOverviewTab(snapshot = uiState.snapshot)
            1 -> SectorTab(
                snapshot = uiState.snapshot,
                onSortChange = viewModel::updateSectorSort,
            )
            2 -> WatchlistTab(
                snapshot = uiState.snapshot,
                entries = uiState.watchlistEntries,
                onAdd = { sym, name -> viewModel.addToWatchlist(sym, name) },
                onRemove = viewModel::removeFromWatchlist,
                onAddAlert = { sym, name, type, threshold ->
                    viewModel.addAlertRule(sym, name, type, threshold)
                },
                onRemoveAlert = viewModel::removeAlertRule,
            )
        }
    }
}

// ── 顶部栏 ───────────────────────────────────────────────────────────────────

@Composable
private fun MonitorTopBar(
    isMarketOpen: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = AetherOnSurface,
            )
        }
        Text(
            text = "行情监控",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AetherOnSurface,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        // 交易时段指示
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isMarketOpen) RiseColor.copy(alpha = 0.12f) else AetherSurfaceHigh)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (isMarketOpen) "交易中" else "休市",
                fontSize = 11.sp,
                color = if (isMarketOpen) RiseColor else AetherOnSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "刷新",
                tint = AetherOnSurfaceVariant,
            )
        }
    }
}

// ── 自定义 TabRow ─────────────────────────────────────────────────────────────

@Composable
private fun MonitorTabRow(
    selectedIndex: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bgColor by animateColorAsState(
                targetValue = if (selected) AetherPrimary.copy(alpha = 0.12f) else Color.Transparent,
                label = "tab_bg",
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) AetherPrimary else AetherOnSurfaceVariant,
                label = "tab_text",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .clickable { onTabSelected(index) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                )
            }
        }
    }
}

// ── Tab 1：大盘总览 ──────────────────────────────────────────────────────────

@Composable
private fun MarketOverviewTab(snapshot: MarketSnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (snapshot.indices.isEmpty()) {
            item {
                EmptyHint(
                    if (snapshot.lastError.isNotBlank()) "加载失败：${snapshot.lastError}" else "正在加载指数行情…"
                )
            }
        } else {
            items(snapshot.indices) { index ->
                IndexCard(index)
            }
        }
        // 涨跌家数统计
        if (snapshot.breadth.risingCount > 0 || snapshot.breadth.fallingCount > 0) {
            item {
                BreadthCard(snapshot)
            }
        }
        item { DataDisclaimer() }
    }
}

@Composable
private fun IndexCard(index: MarketIndex) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AetherSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = index.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AetherOnSurface,
            )
            Text(
                text = index.code,
                fontSize = 11.sp,
                color = AetherOnSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatPrice(index.price),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = changeColor(index.changePercent),
            )
            Text(
                text = "${formatPct(index.changePercent)}  ${if (index.change >= 0) "+" else ""}${formatPrice(index.change)}",
                fontSize = 12.sp,
                color = changeColor(index.changePercent),
            )
        }
    }
}

@Composable
private fun BreadthCard(snapshot: MarketSnapshot) {
    val b = snapshot.breadth
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AetherSurface)
            .padding(16.dp),
    ) {
        val total = (b.risingCount + b.fallingCount + b.flatCount).coerceAtLeast(1)
        val riseWeight = b.risingCount.toFloat().coerceAtLeast(1f)
        val fallWeight = b.fallingCount.toFloat().coerceAtLeast(1f)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            Box(
                Modifier
                    .weight(riseWeight)
                    .fillMaxSize()
                    .background(RiseColor.copy(alpha = 0.75f))
            )
            Box(
                Modifier
                    .weight((total - b.risingCount - b.fallingCount).toFloat().coerceAtLeast(1f))
                    .fillMaxSize()
                    .background(AetherSurfaceHigh)
            )
            Box(
                Modifier
                    .weight(fallWeight)
                    .fillMaxSize()
                    .background(FallColor.copy(alpha = 0.75f))
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BreadthStat("上涨", b.risingCount.toString(), RiseColor)
            BreadthStat("下跌", b.fallingCount.toString(), FallColor)
            BreadthStat("涨停", b.limitUpCount.toString(), RiseColor)
            BreadthStat("跌停", b.limitDownCount.toString(), FallColor)
            BreadthStat("平盘", b.flatCount.toString(), AetherOnSurfaceVariant)
        }
        if (b.isEstimatedLimitStats) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "涨跌停家数为公开接口不可用时的阈值估算",
                fontSize = 10.sp,
                color = AetherOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BreadthStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, fontSize = 11.sp, color = AetherOnSurfaceVariant)
    }
}

// ── Tab 2：板块排行 ──────────────────────────────────────────────────────────

@Composable
private fun SectorTab(
    snapshot: MarketSnapshot,
    onSortChange: (SectorSort, Boolean) -> Unit,
) {
    var selectedType by remember { mutableStateOf(SectorType.Industry) }
    var selectedSort by remember { mutableStateOf(SectorSort.ChangePercent) }
    var ascending by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectorTypeChip("行业", SectorType.Industry, selectedType) { selectedType = it }
            SectorTypeChip("概念", SectorType.Concept, selectedType) { selectedType = it }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectorSortChip(
                label = "涨幅",
                selected = selectedSort == SectorSort.ChangePercent && !ascending,
            ) {
                selectedSort = SectorSort.ChangePercent
                ascending = false
                onSortChange(selectedSort, ascending)
            }
            SectorSortChip(
                label = "跌幅",
                selected = selectedSort == SectorSort.ChangePercent && ascending,
            ) {
                selectedSort = SectorSort.ChangePercent
                ascending = true
                onSortChange(selectedSort, ascending)
            }
            SectorSortChip(
                label = "换手",
                selected = selectedSort == SectorSort.TurnoverRate,
            ) {
                selectedSort = SectorSort.TurnoverRate
                ascending = false
                onSortChange(selectedSort, ascending)
            }
        }

        val sectorList = when (selectedType) {
            SectorType.Industry -> snapshot.industrySectors
            SectorType.Concept -> snapshot.conceptSectors
        }

        if (sectorList.isEmpty()) {
            EmptyHint("正在加载板块数据…")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(sectorList) { sector ->
                    SectorRow(sector)
                }
                item { DataDisclaimer() }
            }
        }
    }
}

@Composable
private fun SectorSortChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) AetherPrimary.copy(alpha = 0.12f) else AetherSurfaceHigh)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AetherPrimary else AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun SectorTypeChip(
    label: String,
    type: SectorType,
    selected: SectorType,
    onSelect: (SectorType) -> Unit,
) {
    val isSelected = type == selected
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) AetherSurfaceHigher else AetherSurfaceHigh)
            .clickable { onSelect(type) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) AetherOnSurface else AetherOnSurfaceVariant,
        )
    }
}

@Composable
private fun SectorRow(sector: SectorItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AetherSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sector.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AetherOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sector.leadingStock.isNotBlank()) {
                Text(
                    text = "领涨：${sector.leadingStock}  ${formatPct(sector.leadingStockChange)}",
                    fontSize = 11.sp,
                    color = AetherOnSurfaceVariant,
                )
            }
        }
        // 涨跌幅色块
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(changeColor(sector.changePercent).copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = formatPct(sector.changePercent),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = changeColor(sector.changePercent),
            )
        }
    }
}

// ── Tab 3：自选股 ────────────────────────────────────────────────────────────

@Composable
private fun WatchlistTab(
    snapshot: MarketSnapshot,
    entries: List<WatchlistEntry>,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onAddAlert: (String, String, AlertType, Double) -> Unit,
    onRemoveAlert: (String, String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var alertTargetEntry by remember { mutableStateOf<WatchlistEntry?>(null) }

    val quoteMap = snapshot.watchlistQuotes.associateBy { it.symbol }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AetherPrimary.copy(alpha = 0.1f))
                    .clickable { showAddDialog = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = AetherPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加自选", fontSize = 13.sp, color = AetherPrimary, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (entries.isEmpty()) {
            EmptyHint("还没有自选股，点击右上角添加")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.symbol }) { entry ->
                    val quote = quoteMap[entry.symbol]
                    WatchlistCard(
                        entry = entry,
                        quote = quote,
                        onRemove = { onRemove(entry.symbol) },
                        onAddAlert = { alertTargetEntry = entry },
                        onRemoveAlert = { ruleId -> onRemoveAlert(entry.symbol, ruleId) },
                    )
                }
                item { DataDisclaimer() }
            }
        }
    }

    if (showAddDialog) {
        AddWatchlistDialog(
            onConfirm = { sym, name ->
                onAdd(sym, name)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    alertTargetEntry?.let { entry ->
        AddAlertDialog(
            entry = entry,
            onConfirm = { type, threshold ->
                onAddAlert(entry.symbol, entry.name, type, threshold)
                alertTargetEntry = null
            },
            onDismiss = { alertTargetEntry = null },
        )
    }
}

@Composable
private fun WatchlistCard(
    entry: WatchlistEntry,
    quote: WatchlistQuote?,
    onRemove: () -> Unit,
    onAddAlert: () -> Unit,
    onRemoveAlert: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AetherSurface)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name.ifBlank { entry.symbol },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AetherOnSurface,
                )
                Text(text = entry.symbol, fontSize = 11.sp, color = AetherOnSurfaceVariant)
            }
            if (quote != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatPrice(quote.price),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = changeColor(quote.changePercent),
                    )
                    Text(
                        text = formatPct(quote.changePercent),
                        fontSize = 13.sp,
                        color = changeColor(quote.changePercent),
                    )
                }
            } else {
                Text("--", fontSize = 16.sp, color = AetherOnSurfaceVariant)
            }
        }

        if (quote != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                QuoteStat("今开", formatPrice(quote.open))
                QuoteStat("昨收", formatPrice(quote.preClose))
                QuoteStat("最高", formatPrice(quote.high), RiseColor)
                QuoteStat("最低", formatPrice(quote.low), FallColor)
            }
        }

        // 预警规则展示
        if (entry.alertRules.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            entry.alertRules.forEach { rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AetherSurfaceHigh)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = AetherPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (rule.type) {
                            AlertType.PriceAbove -> "价格 > ${rule.threshold}"
                            AlertType.PriceBelow -> "价格 < ${rule.threshold}"
                            AlertType.ChangePercentUp -> "涨幅 > ${rule.threshold}%"
                            AlertType.ChangePercentDown -> "跌幅 > ${rule.threshold}%"
                        },
                        fontSize = 12.sp,
                        color = AetherOnSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onRemoveAlert(rule.id) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "删除预警",
                            tint = AetherOnSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onAddAlert,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("设置预警", fontSize = 12.sp)
            }
            TextButton(
                onClick = onRemove,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("移除", fontSize = 12.sp, color = AetherOnSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuoteStat(label: String, value: String, valueColor: Color = AetherOnSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 13.sp, color = valueColor, fontWeight = FontWeight.Medium)
        Text(text = label, fontSize = 10.sp, color = AetherOnSurfaceVariant)
    }
}

@Composable
private fun SoftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        textStyle = TextStyle(
            color = AetherOnSurface,
            fontSize = 14.sp,
        ),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AetherSurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        color = AetherOnSurfaceVariant,
                        fontSize = 14.sp,
                    )
                }
                innerTextField()
            }
        },
    )
}

// ── 对话框 ───────────────────────────────────────────────────────────────────

@Composable
private fun AddWatchlistDialog(
    onConfirm: (symbol: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var symbol by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自选股") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftTextField(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase().trim() },
                    placeholder = "代码（如 600519.SH）",
                    modifier = Modifier.fillMaxWidth(),
                )
                SoftTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "名称（可选，留空自动匹配）",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (symbol.isNotBlank()) onConfirm(symbol, name) },
                colors = ButtonDefaults.buttonColors(containerColor = AetherPrimary),
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AddAlertDialog(
    entry: WatchlistEntry,
    onConfirm: (AlertType, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedType by remember { mutableStateOf(AlertType.PriceAbove) }
    var threshold by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val typeLabel = mapOf(
        AlertType.PriceAbove to "价格高于",
        AlertType.PriceBelow to "价格低于",
        AlertType.ChangePercentUp to "涨幅超过 (%)",
        AlertType.ChangePercentDown to "跌幅超过 (%)",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加预警 · ${entry.name.ifBlank { entry.symbol }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AetherSurfaceHigh)
                            .clickable { expanded = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = typeLabel[selectedType] ?: "",
                            fontSize = 14.sp,
                            color = AetherOnSurface,
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        AlertType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(typeLabel[type] ?: "") },
                                onClick = {
                                    selectedType = type
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                SoftTextField(
                    value = threshold,
                    onValueChange = { threshold = it },
                    placeholder = "阈值",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val t = threshold.toDoubleOrNull()
                    if (t != null) onConfirm(selectedType, t)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AetherPrimary),
            ) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ── 通用占位 ─────────────────────────────────────────────────────────────────

@Composable
private fun EmptyHint(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = AetherOnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DataDisclaimer() {
    Text(
        text = "东方财富公开数据可能延迟，数据仅供参考，不构成投资建议",
        fontSize = 11.sp,
        color = AetherOnSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    )
}
