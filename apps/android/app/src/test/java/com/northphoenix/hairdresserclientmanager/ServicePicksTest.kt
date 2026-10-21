package com.northphoenix.hairdresserclientmanager

import com.northphoenix.hairdresserclientmanager.data.ServiceMenuItem
import com.northphoenix.hairdresserclientmanager.domain.ServicePicks
import org.junit.Assert.assertEquals
import org.junit.Test

/** The add-service sheet's picks from the Service Menu, kept per person and added for everyone at once. */
class ServicePicksTest {
    private val cut = ServiceMenuItem(id = "cut", name = "Стрижка женская", defaultPriceCents = 6_000)
    private val kids = ServiceMenuItem(id = "kids", name = "Стрижка детская", defaultPriceCents = 3_000)
    private val color = ServiceMenuItem(id = "color", name = "Покраска", defaultPriceCents = 12_000)
    private val menu = listOf(cut, kids, color)

    private fun Map<String, List<String>>.tap(clientId: String, itemId: String) = ServicePicks.toggle(this, menu, clientId, itemId)

    @Test
    fun `each person keeps their own picks, in the order they were tapped`() {
        val picks = emptyMap<String, List<String>>()
            .tap("mom", "color")
            .tap("son", "kids")
            .tap("mom", "cut")

        assertEquals(listOf(color, cut), ServicePicks.of(picks, menu, "mom"))
        assertEquals(listOf(kids), ServicePicks.of(picks, menu, "son"))
        assertEquals(emptyList<ServiceMenuItem>(), ServicePicks.of(picks, menu, "dad"))

        // A second tap unpicks it for that person only.
        val unpicked = picks.tap("mom", "color").tap("son", "kids")

        assertEquals(mapOf("mom" to listOf("cut")), unpicked)
    }

    @Test
    fun `everything is added per person in the order of the chips, without people who have no picks`() {
        val picks = emptyMap<String, List<String>>()
            .tap("son", "kids")
            .tap("mom", "color")
            .tap("mom", "cut")

        assertEquals(
            listOf(ServicePicks.ForClient("mom", listOf(color, cut)), ServicePicks.ForClient("son", listOf(kids))),
            ServicePicks.toAdd(picks, menu, listOf("mom", "dad", "son")),
        )
    }

    @Test
    fun `picks of someone no longer listed and items no longer on the menu are not added`() {
        val picks = mapOf("mom" to listOf("gone", "cut"), "left" to listOf("kids"))

        assertEquals(listOf(ServicePicks.ForClient("mom", listOf(cut))), ServicePicks.toAdd(picks, menu, listOf("mom")))
        // A tap drops the items that left the menu.
        assertEquals(mapOf("mom" to listOf("cut", "color"), "left" to listOf("kids")), picks.tap("mom", "color"))
    }
}
