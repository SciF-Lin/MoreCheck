package com.morecheck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.MoreCheckRoot
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.SplashGate
import com.morecheck.ui.settings.SettingsViewModel
import com.morecheck.ui.theme.MoreCheckTheme
import com.morecheck.util.ThemeMode

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Result intentionally ignored: denial must not break check-in flow.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            val settingsViewModel: SettingsViewModel =
                viewModel(factory = AppViewModelProvider.Factory)
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val dynamicColor by settingsViewModel.dynamicColor.collectAsStateWithLifecycle()

            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> systemDark
            }

            MoreCheckTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                // 把同一个 SettingsViewModel 下发给设置页。
                // 之前设置页用 viewModel(...) 自建了一份实例，导致开关读到的是
                // 永远停在默认值的那一份，表现为「动态配色关不掉」（已实测定位）。
                CompositionLocalProvider(
                    LocalSettingsViewModel provides settingsViewModel
                ) {
                    // 启动页门闸：先显示浅色 Hello World，短暂停留后淡入主界面。
                    SplashGate {
                        MoreCheckRoot()
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
