package com.example.untilwetalkagain

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

const val APP_NAME = "Until We Talk Again"

enum class AppThemeEnum(val displayName: String) {
    ROSE("Rose"), OCEAN("Ocean"), FOREST("Forest")
}

private val RoseColorScheme = lightColorScheme(
    primary = Color(0xFF9C4146), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFFFDAD9), surface = Color(0xFFFFFBFA), onSurface = Color(0xFF201A1A)
)
private val OceanColorScheme = lightColorScheme(
    primary = Color(0xFF00639B), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFCEE5FF), surface = Color(0xFFF7F9FF), onSurface = Color(0xFF1A1C1E)
)
private val ForestColorScheme = lightColorScheme(
    primary = Color(0xFF2E6930), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFB0F2AE), surface = Color(0xFFF4FBF4), onSurface = Color(0xFF171D17)
)

@Composable
fun AppTheme(theme: AppThemeEnum, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = when (theme) { AppThemeEnum.ROSE -> RoseColorScheme; AppThemeEnum.OCEAN -> OceanColorScheme; AppThemeEnum.FOREST -> ForestColorScheme }, content = content)
}

@Entity(tableName = "day_records")
data class DayRecord(
    @PrimaryKey val dateKey: String,
    val isTalking: Boolean? = null,
    val isMeeting: Boolean? = null,
    val talkingHours: Int = 0,
    val talkingMinutes: Int = 0,
    val talkingSeconds: Int = 0,
    val note: String = ""
)

@Dao
interface DayRecordDao {
    @Query("SELECT * FROM day_records") fun getAllRecords(): Flow<List<DayRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOrUpdate(record: DayRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertOrUpdateAll(records: List<DayRecord>)
}

@Database(entities = [DayRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayRecordDao(): DayRecordDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "until_we_talk_again_db").build().also { INSTANCE = it }
        }
    }
}

data class StreakSummary(val daysSinceLastTalking: Int?, val daysSinceLastNotTalking: Int?, val daysSinceLastMeeting: Int?, val daysSinceLastNotMeeting: Int?)
data class RangeSummary(val talkingDaysCount: Int, val notTalkingDaysCount: Int, val meetingDaysCount: Int, val notMeetingDaysCount: Int)

object Calculations {
    val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    fun calculateStreaks(records: List<DayRecord>, today: LocalDate = LocalDate.now()): StreakSummary {
        val map = records.associateBy { it.dateKey }
        var t: Int? = null; var nt: Int? = null; var m: Int? = null; var nm: Int? = null
        var checkDate = today; var daysChecked = 0
        while (daysChecked < 730) {
            val rec = map[checkDate.format(formatter)]
            val diff = ChronoUnit.DAYS.between(checkDate, today).toInt()
            if (rec?.isTalking == true && t == null) t = diff
            if (rec?.isTalking == false && nt == null) nt = diff
            if (rec?.isMeeting == true && m == null) m = diff
            if (rec?.isMeeting == false && nm == null) nm = diff
            if (t != null && nt != null && m != null && nm != null) break
            checkDate = checkDate.minusDays(1); daysChecked++
        }
        return StreakSummary(t, nt, m, nm)
    }
    fun calculateRangeSummary(records: List<DayRecord>, start: LocalDate, end: LocalDate): RangeSummary {
        val map = records.associateBy { it.dateKey }
        val (first, last) = if (start.isAfter(end)) end to start else start to end
        var tk = 0; var ntk = 0; var mt = 0; var nmt = 0
        var current = first
        while (!current.isAfter(last)) {
            val rec = map[current.format(formatter)]
            if (rec?.isTalking == true) tk++ else if (rec?.isTalking == false) ntk++
            if (rec?.isMeeting == true) mt++ else if (rec?.isMeeting == false) nmt++
            current = current.plusDays(1)
        }
        return RangeSummary(tk, ntk, mt, nmt)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getDatabase(applicationContext).dayRecordDao()
        setContent {
            var currentTheme by remember { mutableStateOf(AppThemeEnum.ROSE) }
            var records by remember { mutableStateOf<List<DayRecord>>(emptyList()) }
            LaunchedEffect(Unit) { dao.getAllRecords().collectLatest { records = it } }
            AppTheme(theme = currentTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    MainScreen(records, currentTheme, { currentTheme = when(currentTheme){AppThemeEnum.ROSE->AppThemeEnum.OCEAN;AppThemeEnum.OCEAN->AppThemeEnum.FOREST;AppThemeEnum.FOREST->AppThemeEnum.ROSE} }, { rec -> lifecycleScope.launch { dao.insertOrUpdate(rec) } }, { list -> lifecycleScope.launch { dao.insertOrUpdateAll(list) } })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(records: List<DayRecord>, currentTheme: AppThemeEnum, onThemeCycle: () -> Unit, onSaveRecord: (DayRecord) -> Unit, onSaveBatch: (List<DayRecord>) -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    var isRangeMode by remember { mutableStateOf(false) }
    var rangeStart by remember { mutableStateOf<LocalDate?>(null) }
    var rangeEnd by remember { mutableStateOf<LocalDate?>(null) }
    var selectedDateForDialog by remember { mutableStateOf<LocalDate?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(APP_NAME, fontWeight = FontWeight.Bold) }, actions = { OutlinedButton(onClick = onThemeCycle, modifier = Modifier.padding(end = 8.dp)) { Text("Theme: ${currentTheme.displayName}") } }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, label = { Text("Talking") }, icon = { Text("💬") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, label = { Text("Meeting") }, icon = { Text("🤝") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, label = { Text("Dashboards") }, icon = { Text("📊") })
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedTab < 2) {
                val isTalkingCal = (selectedTab == 0)
                CalendarScreen(isTalkingCal, currentMonth, records, isRangeMode, rangeStart, rangeEnd, { isRangeMode = !isRangeMode; rangeStart = null; rangeEnd = null }, { currentMonth = it }, { date -> if (!isRangeMode) selectedDateForDialog = date else { if (rangeStart == null || rangeEnd != null) { rangeStart = date; rangeEnd = null } else rangeEnd = date } }, { statusBool ->
                    val start = rangeStart; val end = rangeEnd
                    if (start != null && end != null) {
                        val (first, last) = if (start.isAfter(end)) end to start else start to end
                        val map = records.associateBy { it.dateKey }
                        val updated = mutableListOf<DayRecord>()
                        var curr = first
                        while (!curr.isAfter(last)) {
                            val key = curr.format(Calculations.formatter)
                            val existing = map[key] ?: DayRecord(dateKey = key)
                            updated.add(if (isTalkingCal) existing.copy(isTalking = statusBool) else existing.copy(isMeeting = statusBool))
                            curr = curr.plusDays(1)
                        }
                        onSaveBatch(updated)
                        rangeStart = null; rangeEnd = null
                    }
                })
            } else {
                DashboardsScreen(records, currentMonth, rangeStart, rangeEnd)
            }
        }

        selectedDateForDialog?.let { date ->
            val key = date.format(Calculations.formatter)
            val existing = records.find { it.dateKey == key } ?: DayRecord(dateKey = key)
            val isTalkingCal = (selectedTab == 0)
            var noteText by remember(key) { mutableStateOf(existing.note) }
            var hrsText by remember(key) { mutableStateOf(if (existing.talkingHours > 0) existing.talkingHours.toString() else "") }
            var minsText by remember(key) { mutableStateOf(if (existing.talkingMinutes > 0) existing.talkingMinutes.toString() else "") }
            var secsText by remember(key) { mutableStateOf(if (existing.talkingSeconds > 0) existing.talkingSeconds.toString() else "") }
            fun autoSave(t: Boolean? = existing.isTalking, m: Boolean? = existing.isMeeting) {
                onSaveRecord(existing.copy(isTalking = t, isMeeting = m, note = noteText, talkingHours = hrsText.toIntOrNull() ?: 0, talkingMinutes = minsText.toIntOrNull() ?: 0, talkingSeconds = secsText.toIntOrNull() ?: 0))
            }
            AlertDialog(
                onDismissRequest = { selectedDateForDialog = null },
                title = { Text("${if (isTalkingCal) "Talking" else "Meeting"} • $key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer), onClick = { if (isTalkingCal) autoSave(t = false) else autoSave(m = false); selectedDateForDialog = null }) { Text("❌") }
                            Button(modifier = Modifier.weight(1f), onClick = { if (isTalkingCal) autoSave(t = true) else autoSave(m = true); selectedDateForDialog = null }) { Text("✅") }
                        }
                        if (isTalkingCal) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = hrsText, onValueChange = { hrsText = it; autoSave() }, label = { Text("Hrs") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                                OutlinedTextField(value = minsText, onValueChange = { minsText = it; autoSave() }, label = { Text("Min") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                                OutlinedTextField(value = secsText, onValueChange = { secsText = it; autoSave() }, label = { Text("Sec") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            }
                        }
                        OutlinedTextField(value = noteText, onValueChange = { noteText = it; autoSave() }, label = { Text("Short Note") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = { TextButton(onClick = { selectedDateForDialog = null }) { Text("Done") } }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(isTalkingCalendar: Boolean, currentMonth: YearMonth, records: List<DayRecord>, isRangeMode: Boolean, rangeStart: LocalDate?, rangeEnd: LocalDate?, onToggleRangeMode: () -> Unit, onMonthChange: (YearMonth) -> Unit, onDateTap: (LocalDate) -> Unit, onApplyRangeStatus: (Boolean) -> Unit) {
    val recordMap = records.associateBy { it.dateKey }
    val monthlySummary = Calculations.calculateRangeSummary(records, currentMonth.atDay(1), currentMonth.atEndOfMonth())
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthChange(currentMonth.minusMonths(1)) }) { Text("◀") }
            Text("${currentMonth.month.name} ${currentMonth.year}", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onMonthChange(currentMonth.plusMonths(1)) }) { Text("▶") }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = isRangeMode, onClick = onToggleRangeMode, label = { Text(if (isRangeMode) "Range Mode: ON" else "Select Range") })
            if (isRangeMode && rangeStart != null && rangeEnd != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onApplyRangeStatus(false) }) { Text("Set ❌") }
                    Button(onClick = { onApplyRangeStatus(true) }) { Text("Set ✅") }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(day, style = MaterialTheme.typography.labelMedium) }
            }
        }
        val daysInMonth = currentMonth.lengthOfMonth()
        val firstDayOffset = currentMonth.atDay(1).dayOfWeek.value % 7
        LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.weight(1f)) {
            items(firstDayOffset) { Box(modifier = Modifier.aspectRatio(1f)) }
            items(daysInMonth) { index ->
                val date = currentMonth.atDay(index + 1)
                val record = recordMap[date.format(Calculations.formatter)]
                val symbol = if (isTalkingCalendar) when(record?.isTalking){true->"✅";false->"❌";null->""} else when(record?.isMeeting){true->"✅";false->"❌";null->""}
                val isSelected = when {
                    rangeStart != null && rangeEnd != null -> { val (s, e) = if (rangeStart.isAfter(rangeEnd)) rangeEnd to rangeStart else rangeStart to rangeEnd; !date.isBefore(s) && !date.isAfter(e) }
                    rangeStart == date -> true
                    else -> false
                }
                Box(
                    modifier = Modifier.padding(3.dp).aspectRatio(1f)
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), shape = RoundedCornerShape(8.dp))
                        .border(width = if (date == LocalDate.now()) 2.dp else 0.dp, color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { onDateTap(date) }),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = (index + 1).toString(), style = MaterialTheme.typography.bodyMedium)
                        if (symbol.isNotEmpty()) Text(text = symbol, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("${currentMonth.month.name} Totals", style = MaterialTheme.typography.titleSmall)
                Text("Talking: ✅ ${monthlySummary.talkingDaysCount} | ❌ ${monthlySummary.notTalkingDaysCount}")
                Text("Meeting: ✅ ${monthlySummary.meetingDaysCount} | ❌ ${monthlySummary.notMeetingDaysCount}")
            }
        }
    }
}

@Composable
fun DashboardsScreen(records: List<DayRecord>, currentMonth: YearMonth, rangeStart: LocalDate?, rangeEnd: LocalDate?) {
    var dashboardTab by remember { mutableIntStateOf(0) }
    val streaks = Calculations.calculateStreaks(records)
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TabRow(selectedTabIndex = dashboardTab) {
            Tab(selected = dashboardTab == 0, onClick = { dashboardTab = 0 }, text = { Text("Talking Stats") })
            Tab(selected = dashboardTab == 1, onClick = { dashboardTab = 1 }, text = { Text("Meeting Stats") })
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dashboardTab == 0) {
                    Text("Talking Streaks & Counts", style = MaterialTheme.typography.titleMedium)
                    Text("Days since last Talking (✅): ${streaks.daysSinceLastTalking ?: "None"}")
                    Text("Days since last Not Talking (❌): ${streaks.daysSinceLastNotTalking ?: "None"}")
                    Text("Total Talking Days: ${records.count { it.isTalking == true }}")
                    Text("Total Not Talking Days: ${records.count { it.isTalking == false }}")
                } else {
                    Text("Meeting Streaks & Counts", style = MaterialTheme.typography.titleMedium)
                    Text("Days since last Meeting (✅): ${streaks.daysSinceLastMeeting ?: "None"}")
                    Text("Days since last Not Meeting (❌): ${streaks.daysSinceLastNotMeeting ?: "None"}")
                    Text("Total Meeting Days: ${records.count { it.isMeeting == true }}")
                    Text("Total Not Meeting Days: ${records.count { it.isMeeting == false }}")
                }
            }
        }
        if (rangeStart != null && rangeEnd != null) {
            val rangeStats = Calculations.calculateRangeSummary(records, rangeStart, rangeEnd)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Range Summary ($rangeStart to $rangeEnd)", style = MaterialTheme.typography.titleMedium)
                    Text("Talking: ✅ ${rangeStats.talkingDaysCount} | ❌ ${rangeStats.notTalkingDaysCount}")
                    Text("Meeting: ✅ ${rangeStats.meetingDaysCount} | ❌ ${rangeStats.notMeetingDaysCount}")
                }
            }
        }
        
