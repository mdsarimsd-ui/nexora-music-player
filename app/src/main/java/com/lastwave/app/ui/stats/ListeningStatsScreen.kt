package com.lastwave.app.ui.stats

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import com.lastwave.app.ui.common.ArtworkImage
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.shell.FloatingNavDefaults

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningStatsScreen(
    onBack: () -> Unit,
    viewModel: ListeningStatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .adaptiveContentWidth(maxWidth = 860.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Songs",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::refresh) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .adaptiveContentWidth(maxWidth = 860.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = FloatingNavDefaults.contentBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── ALL SONGS: play, shuffle, loop, sort, rearrange ──
            item {
                SongsHeaderCard(
                    count = uiState.songsRows.size,
                    sort = uiState.songsSort,
                    onSort = viewModel::setSongsSort,
                    onShuffleAll = viewModel::shuffleSongs,
                )
            }
            item { SongsControlsRow(viewModel) }
            val rows = uiState.songsRows
            val manual = uiState.songsSort == SongsSort.MANUAL
            itemsIndexed(rows, key = { _, row -> row.key }) { idx, row ->
                SongsListRowItem(
                    row = row,
                    index = idx,
                    total = rows.size,
                    manualMode = manual,
                    onMoveUp = { viewModel.moveSong(idx, idx - 1) },
                    onMoveDown = { viewModel.moveSong(idx, idx + 1) },
                    onClick = { viewModel.playSongsAt(idx) },
                )
            }

            // ── TIME RANGE CHIPS ──
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(StatsTimeRange.entries) { range ->
                        StatsFilterChip(
                            label = range.label,
                            selected = (uiState.selectedRange == range),
                            onClick = { viewModel.setTimeRange(range) },
                        )
                    }
                }
            }

            // ── TOP SUMMARY CARDS (Listening & Plays) ──
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SummaryStatCard(
                        label = "Listening",
                        value = uiState.totalListeningLabel,
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    SummaryStatCard(
                        label = "Plays",
                        value = uiState.totalPlays.toString(),
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // ── RHYTHM & CONSISTENCY (SLEEP TRACKER INSPIRED) ──
            uiState.consistency?.let { consistency ->
                item {
                    ConsistencyScoreCard(data = consistency)
                }
            }

            // ── LISTENING STAGES (DEEP FOCUS / FLOW / DISCOVERY) ──
            uiState.stages?.let { stages ->
                item {
                    ListeningStagesCard(stages = stages)
                }
            }

            // ── PERSONAL RECORDS ──
            uiState.records?.let { records ->
                item {
                    PersonalRecordsCard(records = records)
                }
            }

            // ── WEEKLY RHYTHM & MONTH COMPARISON ──
            uiState.weeklyRhythm?.let { rhythm ->
                item {
                    WeeklyRhythmCard(rhythm = rhythm)
                }
            }

            uiState.monthComparison?.let { comparison ->
                item {
                    MonthComparisonCard(comparison = comparison)
                }
            }

            // ── HOURLY LISTENING RHYTHM ──
            if (uiState.hourlyRhythm.isNotEmpty()) {
                item {
                    HourlyRhythmChart(bars = uiState.hourlyRhythm)
                }
            }

            // ── ACTIVITY HEATMAP (LAST 12 WEEKS) ──
            if (uiState.activityHeatmap.isNotEmpty()) {
                item {
                    ActivityHeatmapGrid(days = uiState.activityHeatmap)
                }
            }

            // ── SECTION 1: LISTENING TIMELINE ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Listening timeline",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (uiState.selectedRange) {
                            StatsTimeRange.WEEK -> "Total listening captured this week. Daily bars summarize your progression."
                            StatsTimeRange.MONTH_TO_DATE -> "Total listening captured this month. Daily bars summarize your progression."
                            StatsTimeRange.YEAR_TO_DATE -> "Total listening captured this year. Monthly bars summarize your progression."
                            StatsTimeRange.ALL_TIME -> "Total listening captured in the selected range. Yearly bars summarize your full history."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Timeline metric chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(TimelineMetric.entries) { metric ->
                            StatsFilterChip(
                                label = metric.label,
                                selected = (uiState.selectedTimelineMetric == metric),
                                onClick = { viewModel.setTimelineMetric(metric) },
                            )
                        }
                    }

                    // Bar Chart Card
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        text = when (uiState.selectedRange) {
                                            StatsTimeRange.WEEK -> "Current week progression"
                                            StatsTimeRange.MONTH_TO_DATE -> "Current month progression"
                                            StatsTimeRange.YEAR_TO_DATE -> "Year-to-date progression"
                                            StatsTimeRange.ALL_TIME -> "All-time progression"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = when (uiState.selectedRange) {
                                            StatsTimeRange.WEEK -> "Grouped by day"
                                            StatsTimeRange.MONTH_TO_DATE -> "Grouped by day"
                                            StatsTimeRange.YEAR_TO_DATE -> "Grouped by month"
                                            StatsTimeRange.ALL_TIME -> "Grouped by year"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = uiState.selectedTimelineMetric.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.height(18.dp))

                            ListeningStatsBarChart(
                                bars = uiState.timelineBars,
                                highlightColor = MaterialTheme.colorScheme.primary,
                                barColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }

                    // Peak segment card
                    if (uiState.peakSegmentName.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.TrendingUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = "Peak segment",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = uiState.peakSegmentName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = uiState.peakSegmentDuration,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── SECTION 2: TOP CATEGORIES ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Top categories",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Compare how you listen across genres, artists, albums, and songs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Filter chips: Song / Album / Artist / Genre
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(CategoryFilter.entries) { cat ->
                            StatsFilterChip(
                                label = cat.label,
                                selected = (uiState.selectedCategory == cat),
                                onClick = { viewModel.setCategory(cat) },
                            )
                        }
                    }

                    // Content card
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Listening by ${uiState.selectedCategory.label.lowercase()}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(14.dp))

                            if (uiState.categoryItems.isEmpty()) {
                                Text(
                                    text = "No ${uiState.selectedCategory.label.lowercase()} plays captured in this range yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    uiState.categoryItems.forEach { item ->
                                        RankedCategoryRow(item = item)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── SECTION 3: LISTENING HABITS ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Listening habits",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val habits = uiState.habits
                    if (habits != null) {
                        HabitItemRow(
                            title = "Total sessions",
                            value = habits.totalSessions.toString(),
                            icon = Icons.Filled.History,
                        )
                        HabitItemRow(
                            title = "Avg session",
                            value = habits.avgSessionLabel,
                            icon = Icons.Filled.Timer,
                        )
                        HabitItemRow(
                            title = "Longest session",
                            value = habits.longestSessionLabel,
                            icon = Icons.Filled.Bolt,
                        )
                        HabitItemRow(
                            title = "Sessions/day",
                            value = habits.sessionsPerDay,
                            icon = Icons.Filled.AutoAwesome,
                        )
                        HabitItemRow(
                            title = "Most active day",
                            value = habits.mostActiveDay,
                            icon = Icons.Filled.CalendarMonth,
                        )
                        HabitItemRow(
                            title = "Peak timeline slot",
                            value = habits.peakTimelineSlot,
                            icon = Icons.Filled.TrendingUp,
                        )
                    }
                }
            }

            // ── SECTION 4: TRACK CONCENTRATION ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Track concentration",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "How your listening time is distributed across your top tracks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    val conc = uiState.concentration
                    if (conc != null) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                DonutConcentrationChart(
                                    percentage = conc.top3SharePercent,
                                    accentColor = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )

                                Spacer(Modifier.height(16.dp))

                                Text(
                                    text = "Listening concentration",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Top 3 tracks account for ${conc.top3SharePercent}% of your listening time.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Spacer(Modifier.height(16.dp))

                                // Summary mini-cards
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                            Text(
                                                text = conc.avgPlaysPerTrack,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = "Avg plays/track",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                            Text(
                                                text = conc.uniqueTracksCount.toString(),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = "Unique tracks",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                // Breakdown rows with colored dots
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    ConcentrationRow(
                                        label = "Top 1",
                                        percentage = conc.top1Percent,
                                        duration = conc.top1DurationLabel,
                                        dotColor = MaterialTheme.colorScheme.primary,
                                    )
                                    ConcentrationRow(
                                        label = "Top 2-3",
                                        percentage = conc.top2To3Percent,
                                        duration = conc.top2To3DurationLabel,
                                        dotColor = MaterialTheme.colorScheme.tertiary,
                                    )
                                    ConcentrationRow(
                                        label = "Others",
                                        percentage = conc.othersPercent,
                                        duration = conc.othersDurationLabel,
                                        dotColor = MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── SECTION 5: TRACKS IN THIS RANGE ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Tracks in this range",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Most played tracks for the selected time range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (uiState.tracksInRange.isEmpty()) {
                        Text(
                            text = "No track playback recorded for this range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.tracksInRange.forEach { track ->
                                RankedTrackCard(track = track)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConcentrationRow(
    label: String,
    percentage: Int,
    duration: String,
    dotColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${percentage}%",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = duration,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSongDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val totalSec = ms / 1000L
    return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
}

@Composable
private fun SongsHeaderCard(
    count: Int,
    sort: SongsSort,
    onSort: (SongsSort) -> Unit,
    onShuffleAll: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "All songs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$count songs \u00b7 every download and import",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onShuffleAll) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle all",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text(sort.label, style = MaterialTheme.typography.labelLarge)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    SongsSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onSort(option)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongsListRowItem(
    row: SongsListRow,
    index: Int,
    total: Int,
    manualMode: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (manualMode) {
                Column {
                    IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Move up",
                            tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = index < total - 1, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Move down",
                            tint = if (index < total - 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            ArtworkImage(
                name = row.title,
                artist = row.artist,
                embeddedUrl = row.artworkUrl,
                fallbackIcon = Icons.Filled.MusicNote,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)),
                decodeSizePx = 96,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title.ifBlank { "Unknown Title" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(row.artist.ifBlank { "Unknown Artist" })
                        if (row.playCount > 0) append(" \u00b7 ${row.playCount} plays")
                        val d = formatSongDuration(row.durationMs)
                        if (d.isNotBlank()) append(" \u00b7 $d")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SongsControlsRow(viewModel: ListeningStatsViewModel) {
    var shuffleOn by remember { mutableStateOf(false) }
    var loopMode by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            shuffleOn = !shuffleOn
            viewModel.toggleSongsShuffle()
        }) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = "Shuffle",
                tint = if (shuffleOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = {
            loopMode = (loopMode + 1) % 3
            viewModel.setSongsRepeat(loopMode)
        }) {
            Icon(
                imageVector = if (loopMode == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = "Loop",
                tint = if (loopMode == 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
