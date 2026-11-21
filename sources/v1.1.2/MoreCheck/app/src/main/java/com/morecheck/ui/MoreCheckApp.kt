package com.morecheck.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.morecheck.ui.calendar.CalendarScreen
import com.morecheck.ui.home.HomeScreen
import com.morecheck.ui.records.RecordsScreen
import com.morecheck.ui.settings.AboutScreen
import com.morecheck.ui.settings.SettingsScreen
import com.morecheck.ui.tasks.TaskEditScreen
import com.morecheck.ui.tasks.TaskListScreen

private data class Tab(val route: String, val icon: ImageVector, val label: String)

private val TABS = listOf(
    Tab("home", Icons.Filled.Home, "首页"),
    Tab("calendar", Icons.Filled.CalendarMonth, "日历"),
    Tab("records", Icons.Filled.History, "记录"),
    Tab("settings", Icons.Filled.Settings, "设置")
)

@Composable
fun MoreCheckRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in TABS.map { it.route }) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
            // 默认过渡是 700ms 淡入淡出，这里压到 180ms，切换更跟手。
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(180)) }
        ) {
            composable("home") { HomeScreen() }
            composable("calendar") { CalendarScreen() }
            composable("records") { RecordsScreen() }
            composable("settings") {
                SettingsScreen(
                    onOpenTasks = { navController.navigate("tasks") },
                    onOpenAbout = { navController.navigate("about") }
                )
            }
            composable("tasks") {
                TaskListScreen(
                    onBack = { navController.popBackStack() },
                    onAdd = { navController.navigate("task_edit?taskId=-1") },
                    onEdit = { id -> navController.navigate("task_edit?taskId=$id") }
                )
            }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "task_edit?taskId={taskId}",
                arguments = listOf(navArgument("taskId") { type = NavType.LongType; defaultValue = -1L })
            ) { entry ->
                val taskId = entry.arguments?.getLong("taskId") ?: -1L
                TaskEditScreen(taskId = taskId, onDone = { navController.popBackStack() })
            }
        }
    }
}
