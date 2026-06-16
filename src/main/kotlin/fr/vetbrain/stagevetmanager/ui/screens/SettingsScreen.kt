package fr.vetbrain.stagevetmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    browserType: SeleniumScraper.BrowserType,
    headless: Boolean,
    exportDir: String,
    onBrowserChange: (SeleniumScraper.BrowserType) -> Unit,
    onHeadlessChange: (Boolean) -> Unit,
    onExportDirChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text("Navigateur", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SeleniumScraper.BrowserType.entries.forEach { type ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(
                            selected = browserType == type,
                            onClick = { onBrowserChange(type) },
                        )
                        Text(type.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 14.sp)
                    }
                }
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = headless, onCheckedChange = onHeadlessChange)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Mode sans fenêtre (headless)", fontSize = 14.sp)
                    Text(
                        "Le navigateur tourne en arrière-plan (plus rapide, mais sans affichage)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            HorizontalDivider()

            Text("Dossier d'export Excel", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = exportDir,
                onValueChange = onExportDirChange,
                label = { Text("Chemin du dossier") },
                placeholder = { Text("Ex : /home/user/Documents") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Les fichiers Excel seront exportés dans ce dossier avec la date du jour comme nom.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            HorizontalDivider()

            Text("Info", style = MaterialTheme.typography.titleMedium)
            Text(
                "Le pilote WebDriver (ChromeDriver ou GeckoDriver) est téléchargé automatiquement " +
                    "au premier lancement via WebDriverManager. Une connexion internet est requise " +
                    "lors de la première utilisation.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
