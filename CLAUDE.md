# StageVetManager — Guide développeur

Application Kotlin/Compose Desktop qui scrape stagevet.fr, stocke les stages localement
en SQLite et les exporte vers Excel ou OneDrive.

---

## Architecture en un coup d'œil

| Couche | Fichier principal | Rôle |
|--------|------------------|------|
| Modèle | `model/Internship.kt` | Data class + filtres `ViewFilter` |
| Modèle PDF | `model/ConventionPdfData.kt` | Champs extraits d'une convention PDF |
| Scraping | `scraper/SeleniumScraper.kt` | Connexion, pagination, filtres serveur |
| Parsing HTML | `scraper/DashboardParser.kt` | HTML → `Internship` via sélecteurs Jsoup |
| Téléchargement PDF | `scraper/PdfDownloader.kt` | OkHttp + cookies session → bytes |
| Parsing PDF | `scraper/ConventionPdfParser.kt` | PDFBox → `ConventionPdfData` |
| Persistance | `persistence/LocalDatabase.kt` | SQLite, upsert, déduplication |
| ViewModel | `viewmodel/DashboardViewModel.kt` | StateFlows, tri, chargement, exports |
| Vue liste | `ui/components/InternshipTable.kt` | Table triable, `COLUMNS` |
| Vue bilan | `ui/components/StudentBilanView.kt` | Regroupement par étudiant, expansion |
| Dialog PDF | `ui/components/ConventionPdfDialog.kt` | Affiche `ConventionPdfData` |
| Export Excel | `export/ExcelExporter.kt` | Apache POI, 3 feuilles |
| Export OneDrive | `onedrive/OneDriveExcelUpdater.kt` | Graph API, sessions Excel |

**Flux de données :**
```
stagevet.fr → SeleniumScraper → DashboardParser → List<Internship>
    → LocalDatabase.upsertAll()  (dédup SHA-256)
    → DashboardViewModel.allInternships (StateFlow)
    → displayed (filtré + trié)
    → InternshipTable  ou  StudentBilanView
    → ExcelExporter / OneDriveExcelUpdater

Flux PDF (à la demande) :
    Clic icône 🔍 dans StudentBilanView
    → DashboardViewModel.downloadConventionPdf(url)
    → PdfDownloader(sessionCookies).download(url)   ← cookies capturés avant close()
    → ConventionPdfParser.parse(bytes)
    → selectedPdfData (StateFlow)
    → ConventionPdfDialog
```

---

## Ajouter un nouveau champ scrappé

Effectuer les étapes dans cet ordre. Compiler (`gradle compileKotlin`) après chaque groupe
pour détecter les erreurs au plus tôt.

### 1 — Modèle · `model/Internship.kt`

Ajouter la propriété au `data class` :

```kotlin
data class Internship(
    // … champs existants …
    val monNouveauChamp: String,   // ou LocalDate?, Int?, etc.
)
```

Si le champ doit apparaître dans la recherche texte, l'ajouter dans `matchesText()` :

```kotlin
fun matchesText(query: String): Boolean {
    // …
    monNouveauChamp.lowercase().contains(q)
}
```

---

### 2 — Parser · `scraper/DashboardParser.kt`

Dans `parseCard(card: Element)`, extraire la valeur avec un sélecteur Jsoup :

```kotlin
val monNouveauChamp = card.selectFirst("div.ma-classe")?.text()?.trim() ?: ""
```

Puis l'ajouter dans le `return Internship(...)` en bas de la fonction.

> Consulter les sélecteurs existants dans `parseCard()` pour le style CSS utilisé par stagevet.fr.

---

### 3 — Base de données · `persistence/LocalDatabase.kt`

**3a — Schéma** dans `init()` → ajouter la colonne dans `CREATE TABLE IF NOT EXISTS` :

```sql
mon_nouveau_champ TEXT,
```

**3b — Migration** pour les bases existantes → ajouter juste après le `CREATE TABLE` :

```kotlin
runCatching {
    conn.createStatement().execute(
        "ALTER TABLE internships ADD COLUMN mon_nouveau_champ TEXT"
    )
}
// runCatching : SQLite renvoie une erreur si la colonne existe déjà, on l'ignore.
```

**3c — INSERT** dans `upsertAll()` → ajouter le nom de colonne dans la liste et `?` dans
VALUES, puis `setString(N, s.monNouveauChamp)` dans le bloc `insertStmt.run { ... }`.

**3d — UPDATE** → ajouter `mon_nouveau_champ=?` dans la requête et `setString(N, ...)` dans
le bloc `updateStmt.run { ... }`. Vérifier que le `setString(N, id)` du WHERE reste en dernier.

**3e — Lecture** dans `loadAll()` → ajouter dans le constructeur `Internship(...)` :

```kotlin
monNouveauChamp = rs.getString("mon_nouveau_champ") ?: "",
```

> **Règle `localId()`** (fin du fichier) : la clé de déduplication est
> `studentName|organization|rawDateStage`. Ne **pas** y ajouter de nouveaux champs, sauf si
> le champ identifie une version *différente* du même stage.

---

### 4 — Tri (optionnel) · `viewmodel/DashboardViewModel.kt`

Si le champ doit être triable depuis l'en-tête de colonne :

```kotlin
// enum class SortColumn (fin du fichier)
enum class SortColumn { STUDENT, YEAR, ORGANIZATION, START_DATE, SIGN_DATE, THEME, MON_CHAMP }

// Dans le combine() de displayed :
SortColumn.MON_CHAMP -> filtered.sortedBy { it.monNouveauChamp }
```

---

### 5 — Vue liste · `ui/components/InternshipTable.kt`

Ajouter une entrée dans `COLUMNS` (lignes 34-43) :

```kotlin
ColumnDef("Mon champ", 0.10f, SortColumn.MON_CHAMP) { it.monNouveauChamp },
// ou null pour SortColumn si non triable
```

> La somme des `weight` dans `COLUMNS` doit rester **≤ 1.0f**.
> Réduire d'autres colonnes si nécessaire (ex. Adresse `0.15f → 0.10f`).

---

### 6 — Vue bilan · `ui/components/StudentBilanView.kt`

**Cas A — champ utile en résumé par étudiant** (ex. statut global) : ajouter à `StudentBilan`
(data class privée en haut du fichier) et dans le bloc de la ligne de résumé.

**Cas B — champ visible uniquement en détail** (plus courant) : ajouter uniquement dans le
bloc `if (isExpanded) { bilan.stages.forEach { stage -> Row { ... } } }` :

```kotlin
Text(
    stage.monNouveauChamp,
    modifier = Modifier.weight(0.15f).padding(end = 4.dp),
    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
)
```

> Ajuster les `weight` des autres cellules de détail pour que leur somme reste **1.0f**.

---

### 7 — Export Excel · `export/ExcelExporter.kt`

Dans `writeSheet()` :

```kotlin
// Ajouter à la liste headers (ligne ~63)
val headers = listOf(
    "Étudiant", /* … */, "Thème", "Mon champ"   // ← ici
)

// Ajouter la cellule de données (après row.createCell(10))
row.createCell(11).setCellValue(s.monNouveauChamp)
```

---

### 8 — Export OneDrive · `onedrive/OneDriveExcelUpdater.kt`

Dans le `companion object` :

```kotlin
private val HEADERS = listOf(
    "Étudiant", /* … */, "Thème", "Mon champ"   // ← ici
)
// LAST_COL = ('A' + HEADERS.size - 1) se recalcule automatiquement
```

Dans `clearAndWrite()`, ajouter la valeur à la liste de données :

```kotlin
add(listOf(
    s.studentName, /* … */, s.theme,
    s.monNouveauChamp,   // ← ici
))
```

---

## Mise à jour des tests après ajout

| Fichier de test | Ce qui change |
|----------------|--------------|
| `ExcelExporterTest.kt` | `header row has 11 columns` → mettre à jour le nombre et la liste `expectedHeaders` |
| `LocalDatabaseTest.kt` | Vérifier que `upsertAll` puis `loadAll` mappent correctement le nouveau champ (ajouter un cas de test si la valeur peut être non nulle) |
| `DashboardParserTest.kt` | Ajouter un cas vérifiant l'extraction du nouveau champ depuis la fixture HTML |
| `src/test/resources/fixtures/sample_card.html` | Ajouter l'élément HTML correspondant au nouveau champ |

---

## Affiner le parser PDF (`ConventionPdfParser.kt`)

Le parser extrait le texte brut via **PDFBox 3.x** (`Loader.loadPDF(bytes)`) et applique des
regex sur le résultat. Les regex sont des approximations — affinez-les après avoir vu le
texte réel d'une convention :

1. Lancer l'appli, scraper des stages avec des `conventionPdfUrl` renseignées
2. Passer en vue **Bilan**, développer un étudiant, cliquer sur **🔍** (icône violette)
3. Dans le dialog, déplier **"Afficher le texte brut"** et copier le contenu
4. Adapter les regex dans `ConventionPdfParser.kt` · champ `parse()`
5. Ajouter un test dans `ConventionPdfParserTest.kt` avec le texte brut en fixture

**Points d'attention PDFBox :**
- API 3.x : `Loader.loadPDF(bytes)` (plus `PDDocument.load()`)
- Le texte extrait peut contenir des sauts de ligne inattendus dans les libellés
- Tester avec `PDFTextStripper().setSortByPosition(true)` si l'ordre des lignes est incohérent

---

## Commandes utiles

```bash
gradle compileKotlin --no-daemon   # compilation rapide
gradle test --no-daemon             # tous les tests unitaires
gradle run                          # lancer l'application
```
