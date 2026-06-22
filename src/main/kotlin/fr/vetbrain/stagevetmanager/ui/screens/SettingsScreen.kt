package fr.vetbrain.stagevetmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    // OneDrive
    azureClientId: String,
    oneDrivePath: String,
    onAzureClientIdChange: (String) -> Unit,
    onOneDrivePathChange: (String) -> Unit,
    trackingFilePath: String,
    onTrackingFilePathChange: (String) -> Unit,
    onSignOutOneDrive: () -> Unit,
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
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // --- Navigateur ---
            Text("Navigateur", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SeleniumScraper.BrowserType.entries.forEach { type ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = browserType == type,
                            onClick = { onBrowserChange(type) },
                        )
                        Text(type.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 14.sp)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
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

            // --- Export local ---
            Text("Dossier d'export Excel local", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = exportDir,
                onValueChange = onExportDirChange,
                label = { Text("Chemin du dossier") },
                placeholder = { Text("Ex : /home/user/Documents") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            // --- Export OneDrive ---
            Text("Export OneDrive (Microsoft Graph)", style = MaterialTheme.typography.titleMedium)

            Text(
                "Prérequis : créer une App Registration dans le portail Azure (Entra ID) " +
                    "de type « Mobile and desktop application », redirect URI = http://localhost, " +
                    "permissions : Files.ReadWrite + offline_access (déléguées).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            OutlinedTextField(
                value = azureClientId,
                onValueChange = onAzureClientIdChange,
                label = { Text("Client ID Azure (GUID)") },
                placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = oneDrivePath,
                onValueChange = onOneDrivePathChange,
                label = { Text("Chemin export principal (relatif à la racine OneDrive)") },
                placeholder = { Text("Documents/StageVet/export_stagevet.xlsx") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = trackingFilePath,
                onValueChange = onTrackingFilePathChange,
                label = { Text("Chemin tableau de suivi ER (relatif à la racine OneDrive)") },
                placeholder = { Text("Documents/StageVet/suivi_ER.xlsx") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Au premier clic sur « OneDrive », votre navigateur s'ouvrira pour la " +
                    "connexion Microsoft. Le token est ensuite mis en cache (~/.stagevetmanager/msal_cache.json) " +
                    "pour les sessions suivantes.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            OutlinedButton(onClick = onSignOutOneDrive) {
                Text("Se déconnecter de Microsoft", fontSize = 13.sp)
            }

            HorizontalDivider()

            // --- Info ---
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
