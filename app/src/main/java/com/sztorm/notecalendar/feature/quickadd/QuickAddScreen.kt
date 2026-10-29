package com.sztorm.notecalendar.feature.quickadd

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmi3dmi3.saywhen.parser.MultilingualEventParser
import com.dmi3dmi3.saywhen.parser.ParsedEvent
import com.sztorm.notecalendar.R
import com.sztorm.notecalendar.core.logging.AppLogger
import com.sztorm.notecalendar.core.logging.LogTags
import com.sztorm.notecalendar.data.models.NoteData
import com.sztorm.notecalendar.domain.models.ReminderNote
import com.sztorm.notecalendar.domain.repositories.NoteRepository
import com.sztorm.notecalendar.feature.app.AppViewModel
import com.sztorm.notecalendar.platform.notifications.AppNotificationManager
import com.sztorm.notecalendar.platform.permissions.AppPermissionManager
import com.sztorm.notecalendar.ui.components.ActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "Say when" quick add (adapted from https://github.com/dmi3dmi3/saywhen, GPL-3.0).
 * One phrase, e.g. "Gym every tuesday and friday at 9" → several dated notes.
 */
object QuickAddParser {
    /** Maximum occurrences created for a recurring phrase. */
    const val RECURRENCE_LIMIT = 400

    const val RECURRENCE_YEARS_LIMIT = 1L

    private val byDayCodes: Map<String, java.time.DayOfWeek> = mapOf(
        "MO" to java.time.DayOfWeek.MONDAY,
        "TU" to java.time.DayOfWeek.TUESDAY,
        "WE" to java.time.DayOfWeek.WEDNESDAY,
        "TH" to java.time.DayOfWeek.THURSDAY,
        "FR" to java.time.DayOfWeek.FRIDAY,
        "SA" to java.time.DayOfWeek.SATURDAY,
        "SU" to java.time.DayOfWeek.SUNDAY,
    )

    private data class Rule(
        val freq: String,
        val interval: Int,
        val byDay: Set<java.time.DayOfWeek>,
        val until: LocalDate?,
        val count: Int?
    )

    private fun parseRule(rrule: String): Rule? {
        val parts = rrule.split(";")
            .mapNotNull {
                val index = it.indexOf('=')
                if (index < 0) null else it.substring(0, index) to it.substring(index + 1)
            }
            .toMap()
        val freq = parts["FREQ"]?.uppercase(Locale.ROOT) ?: return null
        // The parser emits UNTIL either as a BASIC_ISO_DATE or as a UTC timestamp.
        val untilDate = parts["UNTIL"]?.let { until ->
            val datePart = Regex("""\b(\d{8})""")
                .find(until)?.groupValues?.get(1)

            datePart?.let {
                LocalDate.of(
                    it.substring(0, 4).toInt(),
                    it.substring(4, 6).toInt(),
                    it.substring(6, 8).toInt()
                )
            }
        }

        return Rule(
            freq = freq,
            interval = parts["INTERVAL"]?.toIntOrNull() ?: 1,
            byDay = parts["BYDAY"]
                ?.split(',')
                ?.mapNotNull { byDayCodes[it.trim().uppercase(Locale.ROOT)] }
                ?.toSet().orEmpty(),
            until = untilDate,
            count = parts["COUNT"]?.toIntOrNull()
        )
    }

    /** Expands an RRULE into concrete dates, starting at [startDate] (inclusive). */
    fun expand(
        startDate: LocalDate,
        rrule: String?,
        maxOccurrences: Int = RECURRENCE_LIMIT
    ): List<LocalDate> {
        val rule = rrule?.let { parseRule(it) } ?: return listOf(startDate)
        val hardLimit = minOf(maxOccurrences, RECURRENCE_LIMIT)
        val lastAllowedDate = startDate.plusYears(RECURRENCE_YEARS_LIMIT)
        val untilDate = rule.until?.let { minOf(it, lastAllowedDate) } ?: lastAllowedDate
        val dates = mutableListOf<LocalDate>()
        fun reachedCount(index: Int) = rule.count != null && index >= rule.count

        when (rule.freq) {
            "DAILY" -> {
                var date = startDate
                var index = 0

                while (date <= untilDate && dates.size < hardLimit && !reachedCount(index)) {
                    dates.add(date)
                    date = date.plusDays(rule.interval.toLong())
                    index++
                }
            }

            "WEEKLY" -> {
                val days = if (rule.byDay.isEmpty()) setOf(startDate.dayOfWeek) else rule.byDay
                var cursor = startDate
                var index = 0
                val weekLimit = untilDate.plusDays(7)

                while (cursor <= weekLimit && dates.size < hardLimit && !reachedCount(index)) {
                    if (cursor <= untilDate && cursor.dayOfWeek in days &&
                        ChronoUnit.WEEKS.between(startDate, cursor) % rule.interval == 0L
                    ) {
                        dates.add(cursor)
                        index++
                    }
                    cursor = cursor.plusDays(1)
                }
            }

            "MONTHLY" -> {
                var monthsFromStart = 0L
                var index = 0

                while (dates.size < hardLimit && !reachedCount(index)) {
                    val yearMonth = YearMonth.from(startDate).plusMonths(monthsFromStart)

                    if (yearMonth.atDay(1) > untilDate) break
                    if (startDate.dayOfMonth <= yearMonth.lengthOfMonth()) {
                        val date = yearMonth.atDay(startDate.dayOfMonth)
                        if (date >= startDate && date <= untilDate) {
                            dates.add(date)
                            index++
                        }
                    }
                    monthsFromStart += rule.interval
                }
            }

            "YEARLY" -> {
                var yearsFromStart = 0L
                var index = 0

                while (dates.size < hardLimit && !reachedCount(index)) {
                    val date = startDate.plusYears(yearsFromStart)

                    if (date > untilDate) break
                    // Feb 29 in non-leap years is silently shifted by plusYears; skip
                    // months that do not match to avoid duplicated day-of-month entries.
                    if (date.month == startDate.month && date.dayOfMonth ==
                        startDate.dayOfMonth
                    ) {
                        dates.add(date)
                        index++
                    }
                    yearsFromStart += rule.interval
                }
            }

            else -> dates.add(startDate)
        }
        return dates
    }

    fun parse(text: String): ParsedEvent? {
        val parser = MultilingualEventParser()

        return try {
            parser.parse(text.trim(), ZonedDateTime.now())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts a parsed phrase into notes. Recurring phrases generate one note per
     * occurrence; past occurrences are skipped.
     */
    fun toNotes(parsed: ParsedEvent): List<NoteData> {
        val today = LocalDate.now()
        val occurrences = expand(
            startDate = parsed.start.toLocalDate(),
            rrule = parsed.rrule
        ).filter { it >= today }
        val noteTime: LocalTime? = parsed.start
            .takeIf { !parsed.allDay }
            ?.toLocalTime()
        val textSuffix = parsed.duration?.let { duration ->
            " (${formatDuration(duration)})"
        } ?: ""

        return occurrences.map { date ->
            NoteData(
                date = date.toString(),
                text = "${parsed.title}$textSuffix",
                reminderDateTime = reminderDateTime(parsed, date)?.toString() ?: "",
                time = noteTime?.toString() ?: ""
            )
        }
    }

    private fun reminderDateTime(parsed: ParsedEvent, date: LocalDate): OffsetDateTime? {
        val reminderMinutes = parsed.reminderMinutes ?: return null
        if (parsed.allDay) return null

        val zone = parsed.start.zone
        val occurrenceStart = date.atTime(parsed.start.toLocalTime()).atZone(zone)

        return occurrenceStart
            .minusMinutes(reminderMinutes.toLong())
            .toOffsetDateTime()
            .takeIf { it.isAfter(OffsetDateTime.now()) }
    }

    fun formatDuration(duration: Duration): String {
        val totalMinutes = duration.toMinutes()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return when {
            hours > 0 && minutes > 0 -> "%dh%02dm".format(hours, minutes)
            hours > 0 -> "%dh".format(hours)
            else -> "%dm".format(minutes)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddScreen(
    logger: AppLogger,
    appViewModel: AppViewModel,
    noteRepository: NoteRepository,
    permissionManager: AppPermissionManager,
    notificationManager: AppNotificationManager,
) {
    val themeColors = appViewModel.state.themeColors
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var textValue by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<ParsedEvent?>(null) }
    val dateFormatter = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
    }
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm").withLocale(Locale.getDefault())
    }

    LaunchedEffect(textValue) {
        if (textValue.isBlank()) {
            parsed = null
        } else {
            delay(300)
            parsed = QuickAddParser.parse(textValue)
        }
    }
    val createNotes: (ParsedEvent) -> Unit = { parsedEvent ->
        val notes = QuickAddParser.toNotes(parsedEvent)
        val reminders = notes.mapNotNull { noteData ->
            noteData.reminderDateTime
                .ifEmpty { null }
                ?.let { OffsetDateTime.parse(it) }
                ?.let {
                    ReminderNote(
                        LocalDate.parse(noteData.date),
                        noteData.text,
                        it,
                        noteData.noteTime.ifEmpty { null }?.let(LocalTime::parse)
                    )
                }
        }

        coroutineScope.launch {
            noteRepository.addAll(notes)
            logger.info("${LogTags.APPLICATION} Quick add created ${notes.size} notes.")
        }
        reminders.forEach { reminderNote ->
            notificationManager.tryScheduleNotification(
                note = reminderNote,
                permissionManager = permissionManager,
                coroutineScope = coroutineScope,
                onCompletion = {}
            )
        }
        keyboardController?.hide()
        focusManager.clearFocus()
        Toast.makeText(
            context,
            context.getString(
                R.string.QuickAdd_CreatedNotes, notes.size
            ),
            Toast.LENGTH_SHORT
        ).show()
        textValue = ""
        parsed = null
    }
    val canCreate = parsed?.title?.isNotBlank() == true &&
        parsed?.let { QuickAddParser.toNotes(it).isNotEmpty() } == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.QuickAdd_Title),
                fontSize = 38.sp,
                fontWeight = FontWeight.Light,
                color = themeColors.textColor,
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    textStyle = TextStyle(
                        color = themeColors.textColor,
                        fontSize = 18.sp
                    ),
                    cursorBrush = SolidColor(themeColors.secondaryColor),
                    decorationBox = { innerTextField ->
                        Column {
                            if (textValue.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.QuickAdd_Hint),
                                    color = themeColors.inactiveTextColor,
                                    fontSize = 18.sp
                                )
                            }
                            innerTextField()
                        }
                    },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                )
                Text(
                    text = stringResource(R.string.QuickAdd_PreviewTitle),
                    color = themeColors.secondaryColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                val parsedEvent = parsed

                if (parsedEvent == null) {
                    Text(
                        text = stringResource(R.string.QuickAdd_NothingParsed),
                        color = themeColors.inactiveTextColor
                    )
                } else {
                    QuickAddPreview(
                        parsedEvent = parsedEvent,
                        dateFormatter = dateFormatter,
                        timeFormatter = timeFormatter,
                        textColor = themeColors.textColor,
                        labelColor = themeColors.secondaryColor
                    )
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        ActionButton(
            modifier = Modifier.align(Alignment.BottomEnd),
            visible = textValue.isNotBlank(),
            enabled = canCreate,
            onClick = { parsed?.let(createNotes) },
            containerColor = themeColors.primaryColor,
            contentColor = themeColors.buttonTextColor,
        ) {
            androidx.compose.material3.Icon(
                painter = painterResource(R.drawable.icon_rounded_check),
                contentDescription = stringResource(R.string.QuickAdd_CreateNotes)
            )
        }
    }
}

@Composable
private fun QuickAddPreview(
    parsedEvent: ParsedEvent,
    dateFormatter: DateTimeFormatter,
    timeFormatter: DateTimeFormatter,
    textColor: Color,
    labelColor: Color,
) {
    Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
    ) {
        PreviewRow(
            label = stringResource(R.string.QuickAdd_DateLabel),
            value = parsedEvent.start.toLocalDate().format(dateFormatter),
            labelColor = labelColor,
            textColor = textColor
        )
        if (!parsedEvent.allDay) {
            PreviewRow(
                label = stringResource(R.string.QuickAdd_TimeLabel),
                value = parsedEvent.start.toLocalTime().format(timeFormatter),
                labelColor = labelColor,
                textColor = textColor
            )
        }
        parsedEvent.duration?.let { duration ->
            PreviewRow(
                label = stringResource(R.string.QuickAdd_DurationLabel),
                value = QuickAddParser.formatDuration(duration),
                labelColor = labelColor,
                textColor = textColor
            )
        }
        parsedEvent.rrule?.let { rrule ->
            val occurrences = QuickAddParser.expand(
                parsedEvent.start.toLocalDate(), rrule
            )

            PreviewRow(
                label = stringResource(R.string.QuickAdd_RecurrenceLabel),
                value = occurrences.size.toString(),
                labelColor = labelColor,
                textColor = textColor
            )
        }
        val reminderText = parsedEvent.reminderMinutes?.let { minutes ->
            "-$minutes min"
        } ?: stringResource(R.string.QuickAdd_ReminderNone)

        PreviewRow(
            label = stringResource(R.string.QuickAdd_ReminderLabel),
            value = reminderText,
            labelColor = labelColor,
            textColor = textColor
        )
        PreviewRow(
            label = stringResource(R.string.QuickAdd_NotesLabel),
            value = parsedEvent.title,
            labelColor = labelColor,
            textColor = textColor
        )
    }
}

@Composable
private fun PreviewRow(
    label: String,
    value: String,
    labelColor: Color,
    textColor: Color,
) {
    Row {
        Text(
            text = label,
            color = labelColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            color = textColor
        )
    }
}
