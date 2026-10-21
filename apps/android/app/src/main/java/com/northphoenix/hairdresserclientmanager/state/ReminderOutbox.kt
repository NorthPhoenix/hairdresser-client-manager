package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.domain.ReminderLog
import com.northphoenix.hairdresserclientmanager.domain.ReminderMessage
import com.northphoenix.hairdresserclientmanager.domain.Reminded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Why an SMS was not sent, as far as the phone says. */
enum class SmsFailure { AIRPLANE_MODE, NO_SERVICE, NO_PERMISSION, TIMEOUT, OTHER }

sealed interface SmsResult {
    data object Sent : SmsResult

    data class Failed(val reason: SmsFailure) : SmsResult
}

/** Sends an SMS from this phone and says how it went. */
fun interface SmsGateway {
    suspend fun send(number: String, text: String): SmsResult
}

/** Where the log of reminders lives between runs of the app. */
interface ReminderStore {
    fun load(): Map<String, Reminded>

    fun save(entries: Map<String, Reminded>)
}

/** Where one reminder of a batch is. */
sealed interface SendState {
    data object Waiting : SendState

    data object Sending : SendState

    data object Sent : SendState

    /** The Stylist's SMS app was opened with the reminder (no SMS permission, or no way to send from here). */
    data object Opened : SendState

    data class Failed(val reason: SmsFailure) : SendState
}

/**
 * Sends Client Reminders from the phone, one after another, and keeps the device-local log of which
 * Appointments were reminded and when. It lives as long as the app, so closing the sheet that started a
 * batch does not stop it. A reminder is logged when its SMS was sent, or when the SMS app was opened with it.
 */
class ReminderOutbox(
    private val gateway: SmsGateway,
    private val store: ReminderStore,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {
    private val _log = MutableStateFlow(ReminderLog.pruned(store.load(), clock()))

    /** Reminded Appointments by id. */
    val log: StateFlow<Map<String, Reminded>> = _log.asStateFlow()

    private val _sends = MutableStateFlow<Map<String, SendState>>(emptyMap())

    /** The results of the batch in progress or the last one, by Appointment id. */
    val sends: StateFlow<Map<String, SendState>> = _sends.asStateFlow()

    private val turn = Mutex()

    /** Whether something is still to be sent. */
    val busy: Boolean get() = _sends.value.values.any { it == SendState.Waiting || it == SendState.Sending }

    /** Forgets the results of the last batch, unless one is still going. */
    fun reset() {
        if (!busy) _sends.value = emptyMap()
    }

    /** Sends [messages] one after another. A batch started while another one goes waits for its turn. */
    fun send(messages: List<ReminderMessage>) {
        if (messages.isEmpty()) return
        _sends.update { it + messages.associate { message -> message.appointmentId to SendState.Waiting } }

        scope.launch {
            for (message in messages) {
                turn.withLock {
                    // Not waiting any more: it was sent already, by another batch that started it again.
                    if (_sends.value[message.appointmentId] != SendState.Waiting) return@withLock
                    _sends.update { it + (message.appointmentId to SendState.Sending) }

                    when (val result = gateway.send(message.number, message.text)) {
                        SmsResult.Sent -> {
                            record(message)
                            _sends.update { it + (message.appointmentId to SendState.Sent) }
                        }
                        is SmsResult.Failed -> _sends.update { it + (message.appointmentId to SendState.Failed(result.reason)) }
                    }
                }
            }
        }
    }

    /** [message] was opened in the Stylist's SMS app: what the app can know is that she was shown it. */
    fun opened(message: ReminderMessage) {
        record(message)
        _sends.update { it + (message.appointmentId to SendState.Opened) }
    }

    /** Logs [message] as reminded now, for the time its Appointment has. */
    fun record(message: ReminderMessage) {
        val updated = ReminderLog.pruned(_log.value, clock()) + (message.appointmentId to Reminded(message.appointmentId, message.startsAt, clock()))

        _log.value = updated
        store.save(updated)
    }
}
