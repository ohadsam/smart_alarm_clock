package com.smartring.app.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, null) } },
                title = { Text("הגדרות", fontWeight = FontWeight.ExtraBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsGroup("שפה") {
                RadioRow("עברית",   "he", s.language)  { vm.setLanguage("he") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RadioRow("English", "en", s.language)  { vm.setLanguage("en") }
            }
            SettingsGroup("עיצוב") {
                RadioRow("אוטומטי לפי המכשיר", "auto",  s.themeMode) { vm.setThemeMode("auto")  }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RadioRow("כהה",                "dark",  s.themeMode) { vm.setThemeMode("dark")  }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                RadioRow("בהיר",               "light", s.themeMode) { vm.setThemeMode("light") }
            }
            SettingsGroup("אודות") {
                ListItem(
                    headlineContent   = { Text("SmartRing v1.0.0", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Android 8+ · Kotlin + Compose") },
                    leadingContent    = { Icon(Icons.Rounded.Info, null) },
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text     = title.uppercase(),
            style    = MaterialTheme.typography.labelSmall,
            color    = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Surface(
            shape  = RoundedCornerShape(14.dp),
            color  = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun RadioRow(label: String, value: String, current: String, onClick: () -> Unit) {
    ListItem(
        headlineContent  = { Text(label, fontWeight = FontWeight.Medium) },
        trailingContent  = { RadioButton(selected = current == value, onClick = onClick) },
        modifier         = Modifier.clickable(onClick = onClick),
    )
}
