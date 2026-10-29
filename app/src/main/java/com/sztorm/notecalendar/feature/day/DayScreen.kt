package com.sztorm.notecalendar.feature.day

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sztorm.notecalendar.R
import com.sztorm.notecalendar.core.common.getLocalizedGenitiveCaseName
import com.sztorm.notecalendar.core.common.getLocalizedName
import com.sztorm.notecalendar.core.common.remainingDurationFromNow
import com.sztorm.notecalendar.core.logging.AppLogger
import com.sztorm.notecalendar.data.models.NoteData
import com.sztorm.notecalendar.domain.models.ReminderNote
import com.sztorm.notecalendar.domain.repositories.NoteRepository
import com.sztorm.notecalendar.feature.app.AppEvent
import com.sztorm.notecalendar.feature.app.AppViewModel
import com.sztorm.notecalendar.platform.notifications.AppNotificationManager
import com.sztorm.notecalendar.platform.permissions.AppPermissionManager
import com.sztorm.notecalendar.ui.components.ActionButton
import com.sztorm.notecalendar.ui.components.DayNote
import com.sztorm.notecalendar.ui.components.InfiniteHorizontalPager
import com.sztorm.notecalendar.ui.components.TimePickerDialog
import com.sztorm.notecalendar.ui.components.TimedContent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration as KotlinDuration

/** Per-note actions exposed by the day page to the screen-level controller. */
data class DayNoteActions(
    /** Index into the notes list of the clicked note. */
    val onReminderClick: (index: Int) -> Unit,
    val onTimeClick: (index: Int) -> Unit,
    val onEditClick: (index: Int) -> Unit,
    val onDeleteClick: (index: Int) -> Unit,
    val onReminderExpired: (index: Int) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    logger: AppLogger,
    appViewModel: AppViewModel,
    permissionManager: AppPermissionManager,
    notificationManager: AppNotificationManager,
    noteRepository: NoteRepository,
    isCreateOrEditRequested: Boolean = false
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val themeColors = appViewModel.state.themeColors
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var timePickerNoteIndex by remember { mutableStateOf<Int?>(null) }   // reminder picker
    var showNoteTimePicker by remember { mutableStateOf(false) }
    var noteTimePickerIndex by remember { mutableStateOf<Int?>(null) }   // note time picker

    fun loadNotes(date: LocalDate): List<DayScreenNote> =
        noteRepository.getAllBy(date).map { DayScreenNote.from(it) }

    val viewModel = viewModel<DayScreenViewModel>(
        factory = DayScreenViewModelFactory(
            initialState = DayScreenState(
                uiState = DayUiState.Viewing,
                initialDate = appViewModel.state.dayScreenDate,
                notes = loadNotes(appViewModel.state.dayScreenDate),
                prevNotes = loadNotes(appViewModel.state.dayScreenDate.minusDays(1)),
                nextNotes = loadNotes(appViewModel.state.dayScreenDate.plusDays(1)),
            )
        )
    )

    fun reloadCurrent() {
        viewModel.onEvent(DayScreenEvent.NoteChange(loadNotes(viewModel.state.initialDate)))
    }

    /** Persists an already existing (database-backed) note. */
    fun saveExistingNote(note: DayScreenNote) {
        val data = note.id
            ?.let { id -> noteRepository.getAllBy(note.date).find { it.id == id } }

        if (data != null) {
            noteRepository.update(
                data.copy(
                    text = note.textValue.text,
                    reminderDateTime = note.reminderDateTime?.toString() ?: "",
                    time = note.time?.toString() ?: ""
                )
            )
        }
    }

    fun addNote(note: DayScreenNote) {
        noteRepository.add(
            NoteData(
                date = note.date.toString(),
                text = note.textValue.text,
                reminderDateTime = note.reminderDateTime?.toString() ?: "",
                time = note.time?.toString() ?: ""
            )
        )
    }

    fun onBackPressedDismissKeyboard() {
        focusRequester.freeFocus()
        keyboardController?.hide()
    }

    fun showNotificationPermissionDeniedToast() = Toast
        .makeText(
            context,
            context.getString(R.string.Message_NotificationPermissionDenied),
            Toast.LENGTH_SHORT
        ).show()

    fun scheduleReminder(note: DayScreenNote, reminderDateTime: OffsetDateTime) {
        val scheduleCallback = { isSuccess: Boolean ->
            if (isSuccess) {
                viewModel.onEvent(DayScreenEvent.ReminderPermissionsChange(false))
                saveExistingNote(note.copy(reminderDateTime = reminderDateTime))
                reloadCurrent()
            } else {
                if (viewModel.state.haveReminderPermissionsBeenDenied) {
                    permissionManager.requestNotifiactionsSettingsPermission { isGranted ->
                        if (isGranted) {
                            notificationManager.tryScheduleNotification(
                                note = ReminderNote(
                                    note.date,
                                    note.textValue.text,
                                    reminderDateTime,
                                    note.time
                                ),
                                permissionManager = permissionManager,
                                coroutineScope = coroutineScope,
                                onCompletion = {}
                            )
                            viewModel.onEvent(
                                DayScreenEvent.ReminderPermissionsChange(false)
                            )
                            saveExistingNote(note.copy(reminderDateTime = reminderDateTime))
                            reloadCurrent()
                        } else {
                            showNotificationPermissionDeniedToast()
                        }
                    }
                } else {
                    showNotificationPermissionDeniedToast()
                    viewModel.onEvent(
                        DayScreenEvent.ReminderPermissionsChange(true)
                    )
                }
            }
        }

        notificationManager.tryScheduleNotification(
            note = ReminderNote(
                note.date,
                note.textValue.text,
                reminderDateTime,
                note.time
            ),
            permissionManager = permissionManager,
            coroutineScope = coroutineScope,
            onCompletion = scheduleCallback
        )
    }

    fun deleteReminder(index: Int) {
        val notes = viewModel.state.notes
        val note = notes.getOrNull(index) ?: return

        notificationManager.cancelScheduledNotification(note.date, note.time)
        saveExistingNote(note.copy(reminderDateTime = null))

        if (viewModel.state.uiState == DayUiState.EditingReminder &&
            viewModel.state.affectedNoteIndex == index
        ) {
            viewModel.onEvent(DayScreenEvent.UiStateChange(DayUiState.Viewing))
        }
        reloadCurrent()
    }

    fun deleteNoteAt(index: Int) {
        val notes = viewModel.state.notes
        val note = notes.getOrNull(index)

        if (note != null) {
            if (note.id != null) {
                noteRepository.getAllBy(note.date).find { it.id == note.id }?.let {
                    noteRepository.delete(it)
                }
            }
            notificationManager.cancelScheduledNotification(note.date, note.time)

            viewModel.onEvent(
                DayScreenEvent.DeleteNoteChange(
                    notes.filterIndexed { i, _ -> i != index },
                    deletedNote = note
                )
            )
        }
    }

    fun setNoteTime(index: Int, localTime: LocalTime) {
        val note = viewModel.state.notes.getOrNull(index) ?: return

        saveExistingNote(note.copy(time = localTime))
        reloadCurrent()
    }

    val noteActions = DayNoteActions(
        onReminderClick = { index ->
            val note = viewModel.state.notes.getOrNull(index)

            if (note != null) {
                viewModel.onEvent(
                    DayScreenEvent.NoteChange(
                        viewModel.state.notes,
                        affectedNoteIndex = index
                    )
                )
                if (note.reminderDateTime == null) {
                    timePickerNoteIndex = index
                    showReminderTimePicker = true
                } else {
                    viewModel.onEvent(
                        DayScreenEvent.UiStateChange(DayUiState.EditingReminder)
                    )
                }
            }
        },
        onTimeClick = { index ->
            if (viewModel.state.notes.getOrNull(index) != null) {
                noteTimePickerIndex = index
                showNoteTimePicker = true
            }
        },
        onEditClick = { index ->
            val note = viewModel.state.notes.getOrNull(index)

            if (note != null) {
                viewModel.onEvent(
                    DayScreenEvent.NoteChange(
                        viewModel.state.notes,
                        affectedNoteIndex = index
                    )
                )
                viewModel.onEvent(
                    DayScreenEvent.UiStateChange(DayUiState.EditingNote)
                )
                keyboardController?.show()
                Unit
            }
        },
        onDeleteClick = ::deleteNoteAt,
        onReminderExpired = ::deleteReminder
    )

    val onAddOrEditNoteClick = {
        val notes = viewModel.state.notes

        viewModel.onEvent(
            DayScreenEvent.NoteChange(
                notes = notes + DayScreenNote(
                    date = appViewModel.state.dayScreenDate,
                    textValue = TextFieldValue()
                ),
                affectedNoteIndex = notes.size
            )
        )
        viewModel.onEvent(
            DayScreenEvent.UiStateChange(DayUiState.AddingNote)
        )
        keyboardController?.show()
        Unit
    }

    val onUndoNoteDeletionClick = {
        val backUpNote = viewModel.state.noteBackup

        if (backUpNote != null) {
            // Alarms of the deleted note were cancelled; adding it back restores state.
            notificationManager
                .cancelScheduledNotification(backUpNote.date, backUpNote.time)
            addNote(backUpNote)
            viewModel.onEvent(
                DayScreenEvent.RestoreNoteChange(loadNotes(backUpNote.date))
            )
        }
    }

    val onDeleteNoteClick = {
        viewModel.state.affectedNoteIndex?.let(::deleteNoteAt)
        viewModel.onEvent(
            DayScreenEvent.UiStateChange(DayUiState.Viewing)
        )
        onBackPressedDismissKeyboard()
    }

    val onCancelNoteEditClick = {
        val affectedNoteIndex = viewModel.state.affectedNoteIndex

        when (viewModel.state.uiState) {
            DayUiState.EditingNote -> {
                if (affectedNoteIndex != null) {
                    val note = viewModel.state.notes.getOrNull(affectedNoteIndex)
                    val storedData = note
                        ?.let { n ->
                            n.id?.let { id ->
                                noteRepository.getAllBy(n.date).find { it.id == id }
                            }
                        }

                    if (note != null && storedData != null) {
                        val notes = viewModel.state.notes.toMutableList()
                        notes[affectedNoteIndex] = DayScreenNote.from(storedData)
                        viewModel.onEvent(
                            DayScreenEvent.NoteChange(notes, affectedNoteIndex = null)
                        )
                    }
                }
            }

            DayUiState.AddingNote -> {
                val notes = viewModel.state.notes.filterIndexed { i, _ ->
                    i != affectedNoteIndex
                }
                viewModel.onEvent(
                    DayScreenEvent.NoteChange(notes, affectedNoteIndex = null)
                )
            }

            else -> {}
        }
        viewModel.onEvent(
            DayScreenEvent.UiStateChange(DayUiState.Viewing)
        )
        onBackPressedDismissKeyboard()
    }

    val onAcceptNoteEditClick = {
        val note = viewModel.affectedNote()

        if (note != null) {
            val isStored = note.id != null

            if (isStored) {
                saveExistingNote(note)
            } else {
                addNote(note)
            }
            note.toReminderNoteOrNull()?.let {
                // Re-schedule so an edited text shows up in the pending notification.
                notificationManager.tryScheduleNotification(
                    note = it,
                    permissionManager = permissionManager,
                    coroutineScope = coroutineScope,
                    onCompletion = {}
                )
            }
            reloadCurrent()
            viewModel.onEvent(
                DayScreenEvent.UiStateChange(DayUiState.Viewing)
            )
        }
        onBackPressedDismissKeyboard()
    }

    val onReminderDialogConfirm: (LocalTime) -> Unit = { localTime ->
        val offsetNow = OffsetDateTime.now()
        val localNow = LocalDateTime.now()
        val reminderDateTime = OffsetDateTime.of(
            if (localTime > localNow.toLocalTime()) localNow.toLocalDate()
            else localNow.toLocalDate().plusDays(1),
            localTime,
            offsetNow.offset
        )
        val note = viewModel.affectedNote()

        showReminderTimePicker = false
        timePickerNoteIndex = null

        if (note != null) {
            scheduleReminder(note, reminderDateTime)
        }
        viewModel.onEvent(DayScreenEvent.UiStateChange(DayUiState.Viewing))
    }

    val onReminderDialogDismiss: (LocalTime) -> Unit = {
        showReminderTimePicker = false
    }

    val onNoteTimeDialogConfirm: (LocalTime) -> Unit = { localTime ->
        showNoteTimePicker = false
        noteTimePickerIndex?.let { setNoteTime(it, localTime) }
        noteTimePickerIndex = null
    }

    val onNoteTimeDialogDismiss: (LocalTime) -> Unit = {
        showNoteTimePicker = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        InfiniteHorizontalPager(
            verticalAlignment = Alignment.Top,
            key = { viewModel.state.initialDate.plusDays(it.toLong()) },
            onPageChange = { page ->
                val date = viewModel.state.initialDate.plusDays(page.toLong())
                appViewModel.onEvent(AppEvent.DayScreenDateChange(date))
                viewModel.onEvent(
                    DayScreenEvent.DateChange(
                        notes = loadNotes(date),
                        prevNotes = loadNotes(date.minusDays(1)),
                        nextNotes = loadNotes(date.plusDays(1))
                    )
                )
                viewModel.onEvent(
                    DayScreenEvent.UiStateChange(DayUiState.Viewing)
                )
            }
        ) { page ->
            val date = viewModel.state.initialDate.plusDays(page.toLong())

            DayPageLayout(
                logger = logger,
                modifier = Modifier.fillMaxSize(),
                appViewModel = appViewModel,
                viewModel = viewModel,
                focusRequester = focusRequester,
                date = date,
                noteActions = noteActions
            )
        }
        ActionButtons(
            logger = logger,
            appViewModel = appViewModel,
            viewModel = viewModel,
            onUndoNoteDeletionClick = onUndoNoteDeletionClick,
            onAddOrEditNoteClick = onAddOrEditNoteClick,
            onDeleteNoteClick = onDeleteNoteClick,
            onCancelNoteEditClick = onCancelNoteEditClick,
            onAcceptNoteEditClick = onAcceptNoteEditClick,
            onDeleteReminderClick = { viewModel.state.affectedNoteIndex?.let(::deleteReminder) },
            onCancelReminderEditClick = {
                viewModel.onEvent(
                    DayScreenEvent.NoteChange(
                        viewModel.state.notes,
                        affectedNoteIndex = null
                    )
                )
                viewModel.onEvent(
                    DayScreenEvent.UiStateChange(DayUiState.Viewing)
                )
            },
            onEditReminderDialogClick = { showReminderTimePicker = true },
        )
    }
    if (showReminderTimePicker) {
        TimePickerDialog(
            title = stringResource(R.string.SetReminder),
            initialTime = viewModel.affectedNote()?.reminderDateTime?.toLocalTime()
                ?: OffsetDateTime.now().toLocalTime(),
            titleColor = themeColors.textColor,
            dialogButtonColors = ButtonDefaults.textButtonColors(
                contentColor = themeColors.primaryColor,
                disabledContentColor = themeColors.inactiveTextColor
            ),
            dialogColors = CardDefaults.cardColors().copy(
                containerColor = themeColors.backgroundColor,
                contentColor = themeColors.backgroundColor,
            ),
            onConfirm = onReminderDialogConfirm,
            onDismiss = onReminderDialogDismiss
        )
    }
    if (showNoteTimePicker) {
        TimePickerDialog(
            title = stringResource(R.string.SetNoteTime),
            initialTime = noteTimePickerIndex
                ?.let(viewModel.state.notes::getOrNull)
                ?.time
                ?: viewModel.affectedNote()?.time
                ?: OffsetDateTime.now().toLocalTime(),
            titleColor = themeColors.textColor,
            dialogButtonColors = ButtonDefaults.textButtonColors(
                contentColor = themeColors.primaryColor,
                disabledContentColor = themeColors.inactiveTextColor
            ),
            dialogColors = CardDefaults.cardColors().copy(
                containerColor = themeColors.backgroundColor,
                contentColor = themeColors.backgroundColor,
            ),
            onConfirm = onNoteTimeDialogConfirm,
            onDismiss = onNoteTimeDialogDismiss
        )
    }
    LaunchedEffect(isCreateOrEditRequested) {
        if (isCreateOrEditRequested) {
            val notes = viewModel.state.notes

            if (notes.isEmpty()) {
                viewModel.onEvent(
                    DayScreenEvent.NoteChange(
                        notes = listOf(
                            DayScreenNote(
                                date = appViewModel.state.dayScreenDate,
                                textValue = TextFieldValue()
                            )
                        ),
                        affectedNoteIndex = 0
                    )
                )
                viewModel.onEvent(
                    DayScreenEvent.UiStateChange(DayUiState.AddingNote)
                )
            } else {
                val firstNote = notes.first()
                val editedNote = firstNote.copy(
                    textValue = firstNote.textValue.copy(
                        selection = TextRange(firstNote.textValue.text.length)
                    )
                )
                viewModel.onEvent(
                    DayScreenEvent.NoteChange(
                        notes = notes.toMutableList().apply { set(0, editedNote) },
                        affectedNoteIndex = 0
                    )
                )
                viewModel.onEvent(
                    DayScreenEvent.UiStateChange(DayUiState.EditingNote)
                )
            }
            keyboardController?.show()
            Unit
        }
    }
}

@Composable
private fun ColumnScope.DayDateText(
    date: LocalDate, textColor: Color, modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .fillMaxWidth()
            .weight(0.6f)
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.5f)
        )
        BasicText(
            text = date.dayOfMonth.toString(),
            style = LocalTextStyle.current.copy(
                color = textColor,
                textAlign = TextAlign.Center,
                fontSize = 140.sp
            ),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .weight(4f)
        )
        BasicText(
            text = date.month.getLocalizedGenitiveCaseName(),
            style = LocalTextStyle.current.copy(
                color = textColor,
                textAlign = TextAlign.Center,
                fontSize = 36.sp
            ),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.5f)
        )
        BasicText(
            text = date.dayOfWeek.getLocalizedName(),
            style = LocalTextStyle.current.copy(
                color = textColor,
                textAlign = TextAlign.Center,
                fontSize = 24.sp
            ),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
private fun BoxScope.ActionButtons(
    @Suppress("unused") logger: AppLogger,
    appViewModel: AppViewModel,
    viewModel: DayScreenViewModel,
    onUndoNoteDeletionClick: () -> Unit,
    onAddOrEditNoteClick: () -> Unit,
    onDeleteNoteClick: () -> Unit,
    onCancelNoteEditClick: () -> Unit,
    onAcceptNoteEditClick: () -> Unit,
    onDeleteReminderClick: () -> Unit,
    onCancelReminderEditClick: () -> Unit,
    onEditReminderDialogClick: () -> Unit,
) {
    val themeColors = appViewModel.state.themeColors
    val uiState = viewModel.state.uiState
    val affectedNote = viewModel.affectedNote()

    Row(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(8.dp)
    ) {
        ActionButton(
            onClick = onDeleteReminderClick,
            visible = uiState == DayUiState.EditingReminder &&
                affectedNote?.reminderDateTime != null,
            containerColor = themeColors.primaryColor,
            contentColor = themeColors.buttonTextColor,
            icon = painterResource(R.drawable.icon_outline_rounded_delete_forever),
            contentDescription = stringResource(R.string.DeleteReminder)
        )
        ActionButton(
            onClick = onCancelReminderEditClick,
            visible = uiState == DayUiState.EditingReminder &&
                affectedNote?.reminderDateTime != null,
            containerColor = themeColors.primaryColor,
            contentColor = themeColors.buttonTextColor,
            icon = painterResource(R.drawable.icon_outline_rounded_edit_notifications),
            contentDescription = stringResource(R.string.Cancel)
        )
        ActionButton(
            onClick = onUndoNoteDeletionClick,
            visible = uiState == DayUiState.Viewing &&
                viewModel.state.noteBackup != null,
            containerColor = themeColors.primaryColor,
            contentColor = themeColors.buttonTextColor,
            icon = painterResource(R.drawable.icon_rounded_undo),
            contentDescription = stringResource(R.string.UndoDeletion)
        )
        ActionButton(
            onClick = onAddOrEditNoteClick,
            visible = uiState == DayUiState.Viewing,
            containerColor = themeColors.primaryColor,
            contentColor = themeColors.buttonTextColor,
            icon = painterResource(R.drawable.icon_rounded_plus),
            contentDescription = stringResource(R.string.AddNote)
        )
        ActionButton(
            onClick = onDeleteNoteClick,
            visible = uiState == DayUiState.EditingNote,
            containerColor = themeColors.primaryColor,
            contentColor = themeColors.buttonTextColor,
            icon = painterResource(R.drawable.icon_outline_rounded_delete),
            contentDescription = stringResource(R.string.DeleteNote)
        )
        ActionButton(
            onClick = onCancelNoteEditClick,
            visible = uiState == DayUiState.EditingNote || uiState == DayUiState.AddingNote,
            containerColor = themeColors.primaryColor,
            contentColor = themeColors.buttonTextColor,
            icon = painterResource(R.drawable.icon_outline_rounded_edit_off),
            contentDescription = stringResource(R.string.Cancel)
        )
        ActionButton(
            onClick = onAcceptNoteEditClick,
            visible = uiState == DayUiState.EditingNote || uiState == DayUiState.AddingNote,
            containerColor = themeColors.primaryColor,
            contentColor = themeColors.buttonTextColor,
            icon = painterResource(R.drawable.icon_rounded_check),
            contentDescription = stringResource(R.string.Accept)
        )
    }
}

private val NOTE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun DayPageLayout(
    @Suppress("unused") logger: AppLogger,
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel,
    viewModel: DayScreenViewModel,
    focusRequester: FocusRequester,
    date: LocalDate,
    noteActions: DayNoteActions,
) {
    val themeColors = appViewModel.state.themeColors
    val isCurrentPage = date == viewModel.state.initialDate
    val notes = when {
        date < viewModel.state.initialDate -> viewModel.state.prevNotes
        date > viewModel.state.initialDate -> viewModel.state.nextNotes
        else -> viewModel.state.notes
    }
    val actionType =
        if (isCurrentPage) viewModel.state.uiState
        else DayUiState.Viewing
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = modifier) {
        DayDateText(date = date, textColor = themeColors.textColor)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isCurrentPage && actionType == DayUiState.Viewing && notes.isEmpty()) {
                BasicText(
                    text = stringResource(R.string.Message_NoNotesForDay),
                    style = LocalTextStyle.current.copy(
                        color = themeColors.inactiveTextColor,
                        fontSize = 16.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 24.dp)
                        .wrapContentHeight(align = Alignment.Bottom)
                )
            }
            notes.forEachIndexed { index, note ->
                val isEditingThisNote = isCurrentPage &&
                    actionType in setOf(DayUiState.EditingNote, DayUiState.AddingNote) &&
                    viewModel.state.affectedNoteIndex == index
                val isEditingReminderForThisNote = isCurrentPage &&
                    actionType == DayUiState.EditingReminder &&
                    viewModel.state.affectedNoteIndex == index

                DayNoteBox(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth(),
                    appViewModel = appViewModel,
                    viewModel = viewModel,
                    focusRequester = focusRequester,
                    isCurrentPage = isCurrentPage,
                    actionType = actionType,
                    date = date,
                    index = index,
                    note = note,
                    isThisNoteEditable = isEditingThisNote,
                    isEditingReminderForThisNote = isEditingReminderForThisNote,
                    noteActions = noteActions,
                    keyboardController = keyboardController,
                )
            }
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}

private fun formatRemainingTime(remainingTime: KotlinDuration?): String =
    remainingTime?.toComponents { hours, minutes, seconds, _ ->
        "%02d:%02d:%02d".format(Locale.current.platformLocale, hours, minutes, seconds)
    } ?: ""

@Composable
private fun DayNoteBox(
    modifier: Modifier = Modifier,
    appViewModel: AppViewModel,
    viewModel: DayScreenViewModel,
    focusRequester: FocusRequester,
    isCurrentPage: Boolean,
    actionType: DayUiState,
    date: LocalDate,
    index: Int,
    note: DayScreenNote,
    isThisNoteEditable: Boolean,
    isEditingReminderForThisNote: Boolean,
    noteActions: DayNoteActions,
    keyboardController: SoftwareKeyboardController?,
) {
    val themeColors = appViewModel.state.themeColors

    AnimatedVisibility(
        visible = true,
        enter = scaleIn() + expandIn(),
        exit = shrinkOut() + scaleOut()
    ) {
        DayNote(
            color = themeColors.noteColor,
            bendTint = themeColors.noteColorVariant,
            bendWidth = with(LocalDensity.current) { 32.dp.toPx() },
            bendShadowWidth = with(LocalDensity.current) { 1.dp.toPx() },
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                if (isCurrentPage &&
                    (actionType == DayUiState.Viewing || isEditingReminderForThisNote)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (note.reminderDateTime != null &&
                            note.reminderDateTime > OffsetDateTime.now()
                        ) {
                            TimedContent(note.reminderDateTime) { remainingTime ->
                                viewModel.onEvent(
                                    DayScreenEvent.ReminderRemainingTimeChange(remainingTime)
                                )
                                if (remainingTime == KotlinDuration.ZERO) {
                                    noteActions.onReminderExpired(index)
                                }
                            }
                            Text(
                                text = formatRemainingTime(
                                    viewModel.state.remainingReminderTime
                                ),
                                color = themeColors.noteTextColor.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { noteActions.onReminderClick(index) }
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (note.reminderDateTime == null)
                                        R.drawable.icon_outline_rounded_notification_add
                                    else R.drawable.icon_outline_rounded_notifications
                                ),
                                contentDescription =
                                    if (note.reminderDateTime == null)
                                        stringResource(R.string.AddReminder)
                                    else stringResource(R.string.EditReminder),
                                tint = themeColors.noteTextColor
                            )
                        }
                        IconButton(
                            onClick = { noteActions.onTimeClick(index) }
                        ) {
                            Text(
                                text = note.time?.format(NOTE_TIME_FORMATTER)
                                    ?: stringResource(R.string.SetNoteTime),
                                color = themeColors.noteTextColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = { noteActions.onEditClick(index) }
                        ) {
                            Icon(
                                painter = painterResource(
                                    R.drawable.icon_outline_rounded_edit
                                ),
                                contentDescription = stringResource(R.string.EditNote),
                                tint = themeColors.noteTextColor
                            )
                        }
                        IconButton(
                            onClick = { noteActions.onDeleteClick(index) }
                        ) {
                            Icon(
                                painter = painterResource(
                                    R.drawable.icon_outline_rounded_delete
                                ),
                                contentDescription = stringResource(R.string.DeleteNote),
                                tint = themeColors.noteTextColor
                            )
                        }
                    }
                }
                BasicTextField(
                    value = note.textValue,
                    onValueChange = {
                        if (isThisNoteEditable) {
                            val notes = viewModel.state.notes.toMutableList()
                            notes[index] = note.copy(textValue = it)
                            viewModel.onEvent(
                                DayScreenEvent.NoteChange(
                                    notes,
                                    affectedNoteIndex = index
                                )
                            )
                        }
                    },
                    readOnly = !isThisNoteEditable,
                    minLines = 1,
                    maxLines = Int.MAX_VALUE,
                    textStyle = TextStyle(
                        color = themeColors.noteTextColor,
                        fontSize = appViewModel.state.noteFontSize.value,
                        lineHeight = appViewModel.state.noteFontSize.value *
                            appViewModel.state.noteLineSpacing.fontScaleFactor,
                    ),
                    cursorBrush = SolidColor(themeColors.secondaryColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .then(
                            when (isThisNoteEditable) {
                                true -> Modifier.focusRequester(focusRequester)
                                false -> Modifier
                            }
                        )
                )
                LaunchedEffect(
                    date, index, isThisNoteEditable, isEditingReminderForThisNote
                ) {
                    when {
                        isThisNoteEditable -> {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }

                        else -> {
                            focusRequester.freeFocus()
                            keyboardController?.hide()
                        }
                    }
                    Unit
                }
            }
        }
    }
}
