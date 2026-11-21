package com.morecheck.util

/** Preset emoji icons the user can pick for a task. */
object TaskIcons {
    val all = listOf(
        "🌅", "💧", "🏃", "📖", "💪", "🧘",
        "🍎", "😴", "✍️", "🎯", "🚶", "🥗",
        "💊", "🎸", "🧹", "☕", "📵", "🦷",
        "🌙", "💻", "🚭", "🙏"
    )
}

/** Preset task colors (ARGB Int). */
object TaskColors {
    val all: List<Int> = listOf(
        0xFF4CAF50.toInt(), 0xFF2196F3.toInt(), 0xFFFF9800.toInt(), 0xFFF44336.toInt(),
        0xFF9C27B0.toInt(), 0xFF00BCD4.toInt(), 0xFF3F51B5.toInt(), 0xFFFF5722.toInt(),
        0xFF009688.toInt(), 0xFF795548.toInt(), 0xFF607D8B.toInt(), 0xFFE91E63.toInt(),
        0xFF8BC34A.toInt(), 0xFFFFC107.toInt(), 0xFF03A9F4.toInt(), 0xFF673AB7.toInt()
    )
}
