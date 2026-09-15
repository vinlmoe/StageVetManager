# StageVet Manager

[![CI](https://github.com/vinlmoe/StageVetManager/actions/workflows/ci.yml/badge.svg)](https://github.com/vinlmoe/StageVetManager/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.7.3-4285F4?logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![JDK](https://img.shields.io/badge/JDK-21-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net)
[![Gradle](https://img.shields.io/badge/Gradle-8.14.3-02303A?logo=gradle&logoColor=white)](https://gradle.org)
[![SQLite](https://img.shields.io/badge/SQLite-3.47-003B57?logo=sqlite&logoColor=white)](https://www.sqlite.org)
[![Plateformes](https://img.shields.io/badge/Plateformes-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey)](#installation)
[![Licence](https://img.shields.io/badge/Licence-GPL--3.0-blue?logo=gnu&logoColor=white)](LICENSE)

Application de bureau **Kotlin / Compose Desktop** pour la gestion des conventions de
stage vétérinaire de VetAgro Sup. Elle extrait les stages depuis
[stagevet.fr](https://www.stagevet.fr), les stocke localement en SQLite, analyse les
conventions PDF et produit les exports attendus par l'administration.

---

## Fonctionnalités

**Extraction**
- Connexion à stagevet.fr via Selenium (Chrome ou Firefox, mode *headless* possible)
- Extraction ensuite en HTTP direct, pagination téléchargée en parallèle, avec repli
  automatique sur Selenium si le transport HTTP échoue
- Points de reprise : une extraction interrompue reprend là où elle s'est arrêtée
  (moins de 24 h, mêmes filtres)
- Filtres serveur : période, année d'étude, thème, statut

**Consultation**
- Vue **liste** : table triable, recherche plein texte, filtres locaux
- Vue **bilan** : regroupement par étudiant avec détail dépliable
- Vue **cliniques** : agrégation par organisme, avec statut `OK` / `À surveiller` /
  `Ne plus envoyer`
- Vues filtrées : débuts sous 15 jours, non signées, signées récemment,
  à signer par l'école, terminées non sélectionnées

**Conventions PDF**
- Téléchargement via les cookies de session, analyse PDFBox, extraction des
  signataires, dates, gratification et modalités de présence
- Détection de la signature école, contrôle de cohérence des durées et des
  jours déclarés
- Archivage local des conventions signées dans un dossier configurable

**Exports**
- Classeur Excel complet (4 feuilles : tous les stages, débuts à 15 jours,
  signés à 15 jours, cliniques)
- Complétion d'un classeur existant sans le réécrire
- Tableau de suivi ER : écriture des colonnes Lieu / Durée par groupe de thème
- CSV VetAgroTice des stages signés, un champ par colonne

---

## Installation

### Prérequis

- **JDK 21** ([Temurin](https://adoptium.net) recommandé)
- **Chrome** ou **Firefox** installé — le pilote correspondant est résolu
  automatiquement (GeckoDriver embarqué, ChromeDriver via Selenium Manager ou
  téléchargement automatique sous Windows)

### Depuis les sources

```bash
git clone https://github.com/vinlmoe/StageVetManager.git
cd StageVetManager
./gradlew run
```

### Générer un installeur natif

```bash
./gradlew packageDistributionForCurrentOS
```

Produit un `.msi`/`.exe` (Windows), `.dmg` (macOS) ou `.deb` (Linux) dans
`build/compose/binaries/`.
Le nom des installeurs inclut automatiquement la version définie par `version`
dans `build.gradle.kts` (par exemple `StageVetManager-1.0.3.dmg`).

---

## Développement

```bash
./gradlew compileKotlin      # compilation rapide
./gradlew test               # tests unitaires
./gradlew run                # lancer l'application

# Sans accès réseau à github.com : les pilotes GeckoDriver ne servent qu'à
# l'exécution, aucun test n'utilise Selenium.
./gradlew test -PskipGeckoDrivers

# Journaliser le texte brut des conventions pour affiner les regex du parser
./gradlew run -Dstagevet.pdf.debug=true
```

> Toujours utiliser `./gradlew`, jamais `gradle` : le wrapper épingle la version
> validée en CI.

L'architecture, la procédure d'ajout d'un champ scrappé et les invariants de
persistance sont documentés dans **[CLAUDE.md](CLAUDE.md)**.

### Intégration continue

Le workflow [`ci.yml`](.github/workflows/ci.yml) s'exécute sur chaque push et
pull request :

| Job | Rôle |
|-----|------|
| `test` | Compilation et tests unitaires |
| `drivers` | Télécharge réellement les pilotes GeckoDriver et vérifie leurs empreintes SHA-256 épinglées |

> Le dépôt étant privé, le badge CI en haut de page ne s'affiche que pour les
> comptes ayant accès au dépôt ; il apparaît cassé pour un visiteur anonyme.
> Il reste vide tant que le workflow n'a pas tourné au moins une fois sur `main`.

Les binaires de pilotes sont récupérés sur le réseau puis rendus exécutables :
leurs empreintes sont donc épinglées dans `build.gradle.kts` et le build échoue
en cas d'écart.

---

## Données et confidentialité

L'application manipule des **données personnelles d'étudiants** (noms, adresses,
dates de naissance, téléphones, courriels, extraits des conventions).

- Tout est stocké **localement** : `~/.stagevetmanager/internships.db` par défaut,
  dossier configurable dans les Paramètres
- Le mot de passe stagevet.fr n'est **jamais** persisté — il reste en mémoire le
  temps de la session
- Vider la base déclenche une **sauvegarde automatique** préalable. Les cases
  « suivi » cochées et les chemins des PDF téléchargés sont des annotations
  locales qu'une ré-extraction ne restaure pas
- Bases, sauvegardes, exports et journaux sont exclus du dépôt par `.gitignore` :
  ne jamais les committer

---

## Licence

[GNU General Public License v3.0](LICENSE)
