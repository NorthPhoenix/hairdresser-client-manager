package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.Appointment
import com.northphoenix.hairdresserclientmanager.data.ProfileShare
import com.northphoenix.hairdresserclientmanager.data.ShareChoice

/**
 * A change as the screen shows it ahead of the server's answer: an optimistic change. The Stylist
 * does not wait for it. The screen has the new value, or the item gone, at once, nothing is
 * disabled while it is saved, and when it cannot be saved the screen goes back to what the server
 * has and the failure is reported as any other.
 *
 * It is no second copy of anything: [show] lays an edit over what the read cache holds
 * ([ReadCache.expect]), and [settle] takes it away again. A read holder makes one for what it
 * reads, such as [AppointmentReads.ahead]; [ActionRunner.run] takes it and calls the three in
 * their order.
 */
internal class Ahead(
    /** Lays the change over what is on screen, and over whatever is loaded while it is on its way. */
    val show: () -> Unit = {},
    /** The server has accepted the change: it is what the screen has of the server from now on. */
    val saved: () -> Unit = {},
    /** The change has been answered, saved or not: what [show] laid over the screen is taken away. */
    val settle: () -> Unit,
)

/** Both changes as one: shown, saved and settled together. For a change that shows in two kinds of read. */
internal operator fun Ahead.plus(other: Ahead) = Ahead(
    show = { show(); other.show() },
    saved = { saved(); other.saved() },
    settle = { settle(); other.settle() },
)

/**
 * Saves a value the Stylist can change again while it is still being saved, such as a status
 * chip, without making them wait: every tap is on screen at once ([ahead] of the value tapped),
 * the requests of one [target] go out one at a time, and the value tapped last is the one saved
 * last (see [LatestWins]). A key would drop a tap that goes back to a value whose request is
 * still running.
 *
 * [saved] is the value on screen, which is the saved one whenever nothing is on its way: tapping
 * it then starts nothing. [save] makes the request for one value. When it fails, the screen goes
 * back to what the server has, which is the value of the last request that worked, the failure is
 * reported once, and the reload still shows what an earlier tap saved.
 * [onSaved] runs once, when the last value tapped has been saved.
 */
internal fun <V> ActionRunner.saveLatest(
    order: LatestWins<String, V>,
    target: String,
    value: V,
    saved: V?,
    ahead: (V) -> Ahead,
    reload: suspend () -> Unit = {},
    onSaved: () -> Unit = {},
    save: suspend (V) -> Unit,
) {
    val starts = order.offer(target, value, saved)

    if (!order.inFlight(target)) {
        // Already the saved value, and nothing is on its way to change it.
        return
    }
    // Every tap shows, also the one that a send already running will pick up.
    ahead(value).show()
    if (!starts) {
        return
    }

    var savedAny = false

    // No key: the values are ordered here, and the next change may start while this one is still reloading.
    run(
        key = null,
        change = {
            order.send(target) { next ->
                save(next)
                ahead(next).saved()
                savedAny = true
            }
        },
        reload = reload,
        savedInPart = { savedAny },
        // Shown tap by tap above, and saved request by request: what is left for the runner is to take it off the screen.
        ahead = Ahead(settle = { ahead(value).settle() }),
    ) { _ -> onSaved() }
}

// What a delete the Stylist has confirmed leaves of an Appointment. Each is exactly what the
// server answers afterwards, so it is shown ahead of the answer and kept once the delete is saved.

/**
 * Without one of its Services. The totals are sums of the Services' prices (the final total
 * unless it is overridden), so they go down by the price of the one that is gone.
 */
internal fun Appointment.withoutService(serviceId: String): Appointment {
    val gone = services.firstOrNull { it.id == serviceId } ?: return this
    val total = serviceTotalCents - gone.priceCents

    return copy(
        services = services - gone,
        participants = participants.map { if (it.clientId == gone.clientId) it.copy(subtotalCents = it.subtotalCents - gone.priceCents) else it },
        serviceTotalCents = total,
        finalTotalCents = finalTotalCentsOverride ?: total,
    )
}

internal fun Appointment.withoutColorFormula(formulaId: String): Appointment =
    copy(services = services.map { service -> service.copy(colorFormulas = service.colorFormulas.filterNot { it.id == formulaId }) })

internal fun Appointment.withoutPhoto(photoId: String): Appointment = copy(photos = photos.filterNot { it.id == photoId })

/** The Profile Share with one of its choices made the other way. Its link stays the same. */
internal fun ProfileShare.with(choice: ShareChoice, included: Boolean): ProfileShare =
    when (choice) {
        ShareChoice.COLOR_FORMULAS -> copy(includeColorFormulas = included)
        ShareChoice.AFTER_PHOTOS -> copy(includeAfterPhotos = included)
        ShareChoice.OTHER_PHOTOS -> copy(includeOtherPhotos = included)
    }
