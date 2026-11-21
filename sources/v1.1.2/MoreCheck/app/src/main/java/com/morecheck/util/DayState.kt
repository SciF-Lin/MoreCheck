package com.morecheck.util

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/**
 * Process-wide "today" observable. Seeded at app start and refreshed by the
 * midnight rollover worker so every open screen flips to the new day without
 * any polling.
 */
object DayState {
    val today = MutableStateFlow(LocalDate.now())
}
