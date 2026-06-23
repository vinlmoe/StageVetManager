package fr.vetbrain.stagevetmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.vetbrain.stagevetmanager.model.TrackingTarget
import fr.vetbrain.stagevetmanager.scraper.SeleniumScraper
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

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
    trackingTargets: List<TrackingTarget>,
    onTrackingTargetsChange: (List<TrackingTarget>) -> Unit,
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = exportDir,
                    onValueChange = onExportDirChange,
                    label = { Text("Chemin du dossier") },
                    placeholder = { Text("Ex : /home/user/Documents") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    browseDirectory(exportDir)?.let(onExportDirChange)
                }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Parcourir")
                }
            }

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

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = oneDrivePath,
                    onValueChange = onOneDrivePathChange,
                    label = { Text("Chemin export principal (relatif à la racine OneDrive)") },
                    placeholder = { Text("Documents/StageVet/export_stagevet.xlsx") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    browseExcelFile(oneDrivePath)?.let(onOneDrivePathChange)
                }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "Parcourir")
                }
            }

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

            // --- Tableaux de suivi ER ---
            Text("Tableaux de suivi ER", style = MaterialTheme.typography.titleMedium)

            Text(
                "Configurez un tableau par année d'étude. L'étiquette filtre les stages correspondants " +
                    "(ex : « 3 » ou « 3ème » filtre les stages dont l'année commence par cette valeur). " +
                    "Laissez l'étiquette vide pour inclure tous les stages.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )

            trackingTargets.forEachIndexed { index, target ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = target.yearLabel,
                        onValueChange = { newLabel ->
                            onTrackingTargetsChange(
                                trackingTargets.toMutableList().also { it[index] = target.copy(yearLabel = newLabel) }
                            )
                        },
                        label = { Text("Année (ex: 3)", fontSize = 11.sp) },
                        placeholder = { Text("Tous") },
                        singleLine = true,
                        modifier = Modifier.width(130.dp),
                    )
                    OutlinedTextField(
                        value = target.filePath,
                        onValueChange = { newPath ->
                            onTrackingTargetsChange(
                                trackingTargets.toMutableList().also { it[index] = target.copy(filePath = newPath) }
                            )
                        },
                        label = { Text("Chemin OneDrive", fontSize = 11.sp) },
                        placeholder = { Text("Documents/StageVet/suivi_3eme.xlsx") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        browseExcelFile(target.filePath)?.let { path ->
                            onTrackingTargetsChange(
                                trackingTargets.toMutableList().also { it[index] = target.copy(filePath = path) }
                            )
                        }
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Parcourir")
                    }
                    IconButton(
                        onClick = {
                            onTrackingTargetsChange(trackingTargets.filterIndexed { i, _ -> i != index })
                        }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Supprimer",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { onTrackingTargetsChange(trackingTargets + TrackingTarget("", "")) },
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ajouter un tableau", fontSize = 13.sp)
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

private fun browseDirectory(current: String): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choisir un dossier"
        if (current.isNotBlank()) currentDirectory = File(current).let { if (it.isDirectory) it else it.parentFile ?: it }
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
        chooser.selectedFile.absolutePath else null
}

private fun browseExcelFile(current: String): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_ONLY
        dialogTitle = "Choisir un fichier Excel"
        fileFilter = FileNameExtensionFilter("Fichiers Excel (*.xlsx)", "xlsx")
        if (current.isNotBlank()) {
            val f = File(current)
            currentDirectory = if (f.isDirectory) f else f.parentFile ?: File(System.getProperty("user.home"))
            if (f.isFile) selectedFile = f
        }
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
        chooser.selectedFile.absolutePath else null
}
