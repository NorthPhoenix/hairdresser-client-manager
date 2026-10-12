package com.northphoenix.hairdresserclientmanager.state

import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import kotlinx.coroutines.CoroutineScope

/**
 * The read of the Service Menu ([menu], `serviceMenu.list`), through a [ReadCache].
 *
 * A change is told here, as in [AppointmentReads]: the menu on screen gets what the server has
 * confirmed at once where that is certain ([saved], [deleted]), and the change is marked in
 * [changes], so that no answer from before it is shown or served again.
 *
 * Not thread-safe: use it from the thread of the scope.
 */
internal class ServiceMenuReads(
    scope: CoroutineScope,
    private val changes: Changes,
    fetch: suspend (StylistKey) -> List<ServiceMenuItem>,
) {
    val menu = ReadCache(scope, changes, LIMITS, about = { _, _ -> listOf(MENU) }, fetch = fetch)

    /**
     * The server has just saved this Service Menu Item. One the menu on screen has is replaced
     * where it stands. Where a new one goes is the server's to say: the reload shows it.
     */
    fun saved(item: ServiceMenuItem) {
        menu.update { _, items -> items.map { if (it.id == item.id) item else it } }
        changed()
    }

    /** The server has just deleted this Service Menu Item: it leaves the menu on screen, and with it the Service form. */
    fun deleted(id: String) {
        menu.update { _, items -> items.filterNot { it.id == id } }
        changed()
    }

    /**
     * A Service Menu Item whose delete the Stylist has confirmed and does not wait for (see
     * [Ahead]): it is gone from the menu on screen at once, and back in its place when the delete fails.
     */
    fun deletedAhead(change: String, id: String) = Ahead(
        show = { menu.expect(change) { _, items -> items.filterNot { it.id == id } } },
        saved = { deleted(id) },
        settle = { menu.settled(change) },
    )

    /** Marks that the server has saved or deleted a Service Menu Item, or may have. */
    fun changed() = changes.changed(MENU)

    /** Empties the cache, for a Stylist who has signed out. Call [Changes.startOver] first. */
    suspend fun clear() = menu.clear()

    private companion object {
        /** One Stylist has one menu. */
        val LIMITS = CacheLimits(answers = 1)

        /** Names the Service Menu, in what an answer is about. */
        const val MENU = "service-menu"
    }
}
