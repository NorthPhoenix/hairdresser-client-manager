package com.northphoenix.hairdresserclientmanager.domain

import com.northphoenix.hairdresserclientmanager.data.AppointmentService

/**
 * How loudly a Service on an Appointment offers to add a Color Formula. A haircut or a travel fee
 * hardly ever has one, so only a Service that already has a Color Formula, or whose name looks like
 * colour work, gets the full button; every other Service keeps a quiet one, so that a formula can
 * still be added to any Service.
 */
object ColorFormulaOffer {
    /** Parts of words that name colour work, in Russian and English, compared without letter case. */
    private val colorWords = listOf(
        "краск", "окраш", "мелир", "обесцвеч", "тонир", "балаяж", "шатуш", "омбре", "колор",
        "colo", "highlight", "bleach", "toner", "balayage", "ombre", "dye",
    )

    fun isProminent(service: AppointmentService): Boolean =
        service.colorFormulas.isNotEmpty() || looksLikeColor(service.name)

    fun looksLikeColor(name: String): Boolean {
        val lower = name.lowercase().replace('ё', 'е')

        return colorWords.any { it in lower }
    }
}
