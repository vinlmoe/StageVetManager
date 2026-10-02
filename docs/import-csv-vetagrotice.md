# CSV VetAgroTice — Spécification pour l’importation

Document du 2 octobre 2026, décrivant l’export actuel de StageVet Manager, avec les deux colonnes d’évaluation. Destinataire : développeur ou administrateur de l’importateur VetAgroTice / StageCompagnon.

## 1. Périmètre et unité d’import

Un **enregistrement CSV représente un stage**. Un étudiant peut donc apparaître plusieurs fois.

L’export lancé depuis l’application sélectionne l’année d’étude demandée, puis ne conserve que les stages dont la date de signature finale est renseignée. Les filtres de consultation de la table ne définissent pas ce périmètre : l’export utilise la liste des stages chargés et l’année choisie. Il ne constitue donc pas une liste exhaustive de tous les stages de StageVet.

### Export incrémental et suivi

Par défaut, l’application exporte uniquement les stages signés nouveaux ou dont le contenu CSV a changé depuis **leur dernier export réussi**. La comparaison porte sur les 60 colonnes et leurs en-têtes, y compris les évaluations, les données PDF et le texte brut de la convention. Une nouvelle extraction à contenu identique ne suffit pas à provoquer un nouvel export.

Le suivi est enregistré dans la base locale, indépendamment du nom de fichier choisi, et reste disponible après redémarrage. Le premier export suivant l’installation de ce suivi inclut tous les stages signés de l’année choisie : les anciens fichiers CSV ne sont pas automatiquement repris dans l’historique.

L’option « Tout réexporter pour cette année » produit un export complet et actualise également les références de comparaison. Chaque année exportée ne modifie le suivi que des stages effectivement écrits. Si aucun stage n’est à exporter, l’application ne crée ni ne remplace de fichier et n’ajoute pas d’entrée à l’historique.

Le dialogue d’export affiche les 20 derniers exports réussis : date, année, nombre de stages, mode et chemin du fichier. Ce suivi constate la création du CSV, **pas son import effectif dans VetAgroTice**. En cas de fichier perdu ou d’import cible échoué, utiliser le réexport complet si nécessaire.

Le suivi n’est actualisé qu’après une écriture réussie du CSV. Si l’écriture échoue, les stages restent à exporter. Si le CSV est créé mais que l’enregistrement du suivi échoue, un message le signale et les stages seront proposés à nouveau. Vider la base des stages remet à zéro leurs références de comparaison, tout en conservant l’historique des exports.

Les données proviennent du tableau de bord StageVet, des conventions PDF déjà analysées et des évaluations déjà récupérées. L’export ne télécharge ni n’analyse ces documents à cette étape. Une convention non analysée laisse les colonnes PDF vides ; une évaluation peut manquer indépendamment de la signature du stage.

L’absence d’un stage dans un fichier ne doit pas être interprétée comme une demande de suppression dans l’application cible.

## 2. Format physique

| Propriété | Valeur |
| --- | --- |
| Encodage | UTF-8 avec BOM initial (octets `EF BB BF`) |
| Séparateur de colonnes | Point-virgule `;` |
| Délimiteur de champ | Guillemet double `"` ; tous les champs sont encadrés |
| Guillemet dans une valeur | Doublé : `""` |
| Première ligne logique | En-têtes, 60 colonnes dans la version actuelle |
| Valeur absente | Champ vide `""` ; pas de chaîne `NULL` |
| Fin d’enregistrement | Saut de ligne ; lecteur compatible LF et CRLF recommandé |
| Champs multilignes | Autorisés à l’intérieur des guillemets, notamment colonnes 58 à 60 |
| Types physiques | Texte pour toutes les cellules ; conversions après lecture |

**Utiliser un lecteur CSV : ni découpage par `;`, ni lecture ligne par ligne suivie d’un découpage.** Un commentaire peut contenir des points-virgules, des guillemets et des retours à la ligne. Le nombre de lignes physiques n’est pas le nombre de stages.

Particularité de compatibilité : lorsque la valeur se termine par un nombre impair d’antislashs `\`, l’exporteur retire le dernier antislash avant de l’encadrer de guillemets. Cela évite certains décalages avec le lecteur PHP historique de StageCompagnon. Les autres antislashs sont conservés ; ne pas les supprimer globalement à l’import. Ce traitement peut modifier un chemin local terminé par `\`.

## 3. Dictionnaire des 60 colonnes

Les numéros ci-dessous commencent à 1 et décrivent l’ordre actuel. **Faire la correspondance par intitulé d’en-tête**, puis vérifier la présence des colonnes requises pour l’application cible.

Les intitulés sont exacts, accents compris. `Année d'étude` utilise une apostrophe droite, tandis que `Évaluation par l’étudiant` utilise une apostrophe typographique (`U+2019`). Retirer le BOM avant de comparer le premier en-tête.

Origines : **Stage** = tableau de bord StageVet ; **Local** = fichier du poste source ; **PDF** = convention analysée en cache ; **Évaluation** = page de résultat StageVet.

| N° | Intitulé exact | Type à l’import | Origine et signification |
| ---: | --- | --- | --- |
| 1 | Étudiant | Texte | **Stage** — Nom complet affiché dans le tableau de bord ; ne pas découper automatiquement en nom/prénom. |
| 2 | Année d'étude | Texte | **Stage** — Libellé de l’année d’étude, conservé tel quel. |
| 3 | Organisme | Texte | **Stage** — Nom de l’organisme affiché dans le tableau de bord. |
| 4 | Adresse organisme | Texte | **Stage** — Adresse de l’organisme affichée dans le tableau de bord. |
| 5 | N° convention | Texte | **Stage** — Référence de convention telle qu’affichée ; préserver lettres, préfixes et zéros initiaux. |
| 6 | Convention générée le | Texte brut | **Stage** — Libellé de génération de la convention ; aucune normalisation de date par l’exporteur. |
| 7 | Date signature finale | Date JJ/MM/AAAA | **Stage** — Date de signature finale ; obligatoirement renseignée pour une ligne exportée. |
| 8 | Début stage | Date JJ/MM/AAAA | **Stage** — Début du stage ; vide si inconnu. |
| 9 | Fin stage | Date JJ/MM/AAAA | **Stage** — Fin du stage ; vide si inconnue. |
| 10 | Dates brutes | Texte | **Stage** — Période telle qu’affichée sur StageVet. |
| 11 | Thème | Texte | **Stage** — Thème du stage. |
| 12 | Durée | Texte | **Stage** — Durée avec son unité éventuelle ; aucune conversion numérique. |
| 13 | URL convention PDF | URL | **Stage** — Lien vers la convention PDF ; peut nécessiter une session StageVet. |
| 14 | URL signature | URL | **Stage** — Lien de signature enregistré, souvent vide pour un stage signé. |
| 15 | Chemin PDF local | Chemin texte | **Local** — Chemin du PDF sur le poste source ; aucun fichier PDF n’est embarqué dans le CSV. |
| 16 | Contact école | Texte | **PDF** — Contact de l’école extrait du PDF. |
| 17 | Nom tuteur | Texte | **PDF** — Nom de l’enseignant tuteur ; distinct du maître de stage. |
| 18 | Fonction tuteur | Texte | **PDF** — Fonction de l’enseignant tuteur. |
| 19 | Téléphone tuteur | Téléphone texte | **PDF** — Téléphone du tuteur ; préserver +, espaces et zéros initiaux. |
| 20 | Email tuteur | Email texte | **PDF** — Adresse électronique du tuteur. |
| 21 | Organisme (convention) | Texte | **PDF** — Nom de l’organisme extrait de la convention. |
| 22 | Adresse organisme (convention) | Texte | **PDF** — Adresse de l’organisme extraite de la convention. |
| 23 | Représentant organisme | Texte | **PDF** — Représentant de l’organisme. |
| 24 | Qualité maître de stage | Texte | **PDF** — Qualité du maître de stage. |
| 25 | Téléphone organisme | Téléphone texte | **PDF** — Téléphone de l’organisme. |
| 26 | Email organisme | Email texte | **PDF** — Adresse électronique de l’organisme. |
| 27 | Nom maître de stage | Texte | **PDF** — Nom du maître de stage. |
| 28 | Fonction maître de stage | Texte | **PDF** — Fonction du maître de stage. |
| 29 | Nom étudiant | Texte | **PDF** — Nom de famille de l’étudiant extrait du PDF. |
| 30 | Prénom étudiant | Texte | **PDF** — Prénom de l’étudiant extrait du PDF. |
| 31 | Date de naissance étudiant | Texte de date | **PDF** — Date de naissance extraite du PDF, sans normalisation par l’exporteur. |
| 32 | Année étudiant (convention) | Texte | **PDF** — Année d’étude extraite du PDF ; peut différer du tableau de bord. |
| 33 | Adresse étudiant | Texte | **PDF** — Adresse de l’étudiant. |
| 34 | Téléphone étudiant | Téléphone texte | **PDF** — Téléphone de l’étudiant. |
| 35 | Email étudiant | Email texte | **PDF** — Adresse électronique de l’étudiant ; candidate au rapprochement avec un compte existant. |
| 36 | Année universitaire | Texte | **PDF** — Année universitaire telle qu’extraite du PDF. |
| 37 | Début (convention) | Texte de date | **PDF** — Date de début extraite du PDF, sans normalisation par l’exporteur. |
| 38 | Fin (convention) | Texte de date | **PDF** — Date de fin extraite du PDF, sans normalisation par l’exporteur. |
| 39 | Durée (convention) | Texte | **PDF** — Durée extraite du PDF, avec son unité éventuelle. |
| 40 | Jours déclarés | Entier ou vide | **PDF** — Nombre de jours effectifs déclarés dans la convention. |
| 41 | Jours effectifs | Entier ou vide | **PDF** — Nombre de dates de présence effectivement listées dans la convention. |
| 42 | Cohérence jours | Oui / Non / vide | **PDF** — Résultat de comparaison entre jours déclarés et jours effectifs ; vide = indéterminé. |
| 43 | Présence de nuit | Oui / Non / vide | **PDF** — Présence de nuit détectée par l’analyse du PDF. |
| 44 | Présence dimanche | Oui / Non / vide | **PDF** — Présence le dimanche détectée par l’analyse du PDF. |
| 45 | Présence jour férié | Oui / Non / vide | **PDF** — Présence un jour férié détectée par l’analyse du PDF. |
| 46 | Présence à domicile | Oui / Non / vide | **PDF** — Présence à domicile détectée par l’analyse du PDF. |
| 47 | Repos hebdomadaire | Oui / Non / vide | **PDF** — Jour de repos hebdomadaire ; vide = indéterminé. |
| 48 | Thème (convention) | Texte | **PDF** — Thème extrait de la convention. |
| 49 | Statut gratification | avec / sans / vide | **PDF** — Statut de gratification détecté dans la convention. |
| 50 | Montant gratification | Décimal en texte | **PDF** — Montant brut extrait, par exemple 612,50 ou 0 ; vide possible. Aucune unité ni périodicité garantie par cette colonne seule. |
| 51 | Cohérence gratification | Oui / Non / vide | **PDF** — Cohérence du statut et du montant de gratification ; vide = indéterminé. |
| 52 | Gratification | Texte | **PDF** — Résumé lisible de la gratification. |
| 53 | Signature tuteur | Date/heure en texte | **PDF** — Signature de l’enseignant tuteur ; format produit par le parseur : JJ-MM-AAAA à HH:mm. |
| 54 | Signature étudiant | Date/heure en texte | **PDF** — Signature du stagiaire ; même format. |
| 55 | Signature maître de stage | Date/heure en texte | **PDF** — Signature du maître de stage ; même format. |
| 56 | Signature école | Date/heure en texte | **PDF** — Signature de l’école ; même format. Distincte de la date normalisée de la colonne 7. |
| 57 | URL source des données | URL | **PDF** — URL source de la convention analysée. |
| 58 | Texte brut de la convention | Texte multiligne | **PDF** — Texte complet extrait de la convention PDF. |
| 59 | Évaluation par le maître de stage | Texte multiligne | **Évaluation** — Évaluation de l’étudiant rédigée par le maître de stage : items, notes, avis global, commentaire et objectifs. |
| 60 | Évaluation par l’étudiant | Texte multiligne | **Évaluation** — Évaluation du maître/lieu de stage rédigée par l’étudiant : items, notes, recommandation globale, commentaires, auto-évaluation des objectifs et actes pratiqués si disponibles. |

## 4. Règles de conversion et valeurs manquantes

- Convertir les colonnes 7 à 9 avec le format strict `JJ/MM/AAAA`. Une date vide devient une valeur absente, jamais une date par défaut.
- Les dates issues du PDF restent des chaînes à valider. Les signatures PDF sont actuellement extraites au format `JJ-MM-AAAA à HH:mm`, sans fuseau horaire. Ne pas appliquer indistinctement le format des colonnes 7 à 9 à toutes les dates.
- Conserver les références de convention, téléphones, années d’étude et durées comme du texte. Aucune durée standard en jours n’est garantie par les colonnes de durée.
- Convertir les colonnes 40 et 41 en entiers seulement si elles sont renseignées. Zéro est une valeur, pas une absence.
- Pour les booléens : `Oui` → vrai, `Non` → faux, vide → inconnu. Les quatre modalités de présence (43 à 46) valent par défaut faux dans le modèle PDF : `Non` reflète le résultat du parseur, sans garantir qu’une case négative explicite était présente dans le document.
- Pour le montant, valider la chaîne et normaliser éventuellement la virgule décimale avant conversion. Conserver la valeur d’origine pour examiner les formats non reconnus.
- Une colonne PDF vide ne signifie pas nécessairement que l’information n’existe pas dans le PDF : celui-ci peut ne pas avoir été analysé, ou son contenu peut ne pas avoir été reconnu.
- Conserver séparément les champs du tableau de bord et ceux du PDF, ou définir explicitement une priorité. L’export ne garantit pas leur identité.

## 5. Contenu des évaluations

Les colonnes 59 et 60 sont **deux blocs de texte**, un par auteur. Elles ne contiennent ni JSON, ni identifiants techniques des items, ni colonnes numériques individuelles.

Exemple fictif de la valeur de « Évaluation par le maître de stage » :

```text
Savoir-être
Respect des horaires de travail : ponctualité et assiduité : 4/5
Communication avec l’équipe : Non renseigné
Avis global : 4/5
Commentaire
Étudiante attentive ; bonne progression.
Évaluation des objectifs par le maître de stage
L’étudiant a répondu aux objectifs pédagogiques qui lui étaient fixés : 5/5
Commentaire sur la réalisation des objectifs : Objectifs atteints.
```

Les rubriques et libellés viennent de la page source et peuvent varier selon le questionnaire. Les valeurs reconnues vont de `0/5` à `5/5`. `Non renseigné` indique une note absente ou non reconnue ; elle ne doit pas devenir `0/5`. La mention `Aucun commentaire fourni` peut être conservée telle quelle.

L’avis global correspond à la note générale attribuée à l’étudiant pour la colonne 59 et au niveau de recommandation du lieu de stage pour la colonne 60. Ce n’est pas une moyenne calculée par l’exporteur.

Le bloc de l’étudiant peut aussi contenir l’auto-évaluation des objectifs, le commentaire sur leur réalisation et les gestes techniques ou actes pratiqués.

**Import recommandé :** deux champs texte longs distincts, avec conservation des retours à la ligne et affichage comme texte échappé. Ne pas interpréter les commentaires comme du HTML.

Pour une exploitation statistique par item, prévoir une étape de transformation supplémentaire. Une ligne terminée par ` : 4/5` peut être candidate à une note, mais un commentaire libre peut avoir la même forme : ce format texte ne garantit pas un découpage automatique sans ambiguïté. Pour une intégration structurée fiable, il faudra faire évoluer l’export vers des champs ou une table d’items dédiés avec identifiants stables.

Une évaluation vide peut signifier « pas encore disponible » ou « non récupérée ». Si une récupération échoue, StageVet Manager conserve la dernière évaluation enregistrée. Le CSV n’inclut ni statut détaillé de récupération, ni horodatage de fraîcheur des évaluations.

## 6. Correspondance avec l’application cible

L’ajout des colonnes à l’export ne garantit pas leur prise en charge par un importateur existant. Le code de l’importateur StageCompagnon n’a pas été examiné pour ce document.

Dans l’intégration décrite par le dépôt, vérifier le point d’entrée `mod/stage/import_stagevet.php` et la correspondance `$columnmap` de `mod/stage/classes/local/csv_importer.php`. Ajouter les deux intitulés exacts aux champs de destination, puis vérifier leur stockage et leur affichage. Les noms des champs de destination dépendent de l’application cible et ne sont pas définis par ce CSV.

Pour rester compatible avec les exports antérieurs à 58 colonnes, traiter les deux nouvelles colonnes comme facultatives. Leur absence doit permettre de conserver les évaluations existantes dans la cible. Pour une cellule vide présente, définir également une politique explicite ; conserver la valeur existante est recommandé afin qu’une extraction incomplète ne l’efface pas.

Rapprochement conseillé, à adapter au modèle de la cible :

1. Identifier l’étudiant avec son compte existant, de préférence par l’email lorsqu’il est disponible et non ambigu. Aucun identifiant Moodle n’est exporté. Ne pas fusionner deux personnes sur leur seul nom.
2. Identifier le stage par une référence de convention validée dans le contexte de la cible. Le CSV ne garantit ni son unicité ni sa présence ; ne pas supprimer ses préfixes sans règle métier.
3. En cas de référence absente ou ambiguë, soumettre le rapprochement étudiant / organisme / dates à vérification au lieu de créer silencieusement un doublon.
4. Réimporter une même ligne comme une mise à jour, selon cette clé de rapprochement. Le hash local de StageVet Manager n’est pas exporté.

## 7. Exemple de lecture en Python

Cet exemple lit et contrôle le fichier. Il n’écrit pas dans la base de l’application cible.

```python
import csv

with open("export_vetagrotice.csv", encoding="utf-8-sig", newline="") as stream:
    reader = csv.reader(stream, delimiter=";", quotechar='"', strict=True)
    headers = next(reader, None)
    if not headers:
        raise ValueError("En-tête CSV absent")
    if len(set(headers)) != len(headers):
        raise ValueError("En-têtes dupliqués")
    required = {"Étudiant", "N° convention", "Date signature finale"}
    if not required.issubset(headers):
        raise ValueError("Colonnes attendues absentes")

    for record_number, values in enumerate(reader, start=1):
        if len(values) != len(headers):
            raise ValueError(f"Nombre de champs incorrect, stage {record_number}")
        stage = dict(zip(headers, values))
        supervisor_text = stage.get("Évaluation par le maître de stage", "")
        student_text = stage.get("Évaluation par l’étudiant", "")
        # Valider les données, rapprocher le stage et appliquer la politique
        # de mise à jour avant toute écriture dans l’application cible.
```

Ce lecteur conserve les guillemets et antislashs à l’intérieur des valeurs après décodage CSV. Pour un importateur PHP, choisir explicitement les paramètres de lecture CSV et tester les antislashs ; éviter de dépendre implicitement de l’échappement historique par défaut.

## 8. Recette d’importation

| Cas | Résultat attendu |
| --- | --- |
| Accents et apostrophes des en-têtes | Correspondance exacte après retrait du BOM |
| Valeur contenant `;`, `"` ou plusieurs lignes | Un seul champ, aucune colonne décalée |
| Ancien export de 58 colonnes | Import accepté ; évaluations existantes conservées |
| Export actuel de 60 colonnes | Deux évaluations distinctes importées |
| Note `0/5` et note `Non renseigné` | Zéro et absence restent distincts |
| PDF non analysé | Champs PDF vides acceptés selon les exigences métier |
| Évaluation vide | Application de la politique d’absence, sans effacement implicite |
| Même fichier importé deux fois | Pas de doublons de stages |
| Référence ou étudiant ambigu | Ligne signalée pour résolution |
| Chemin Windows, commentaire avec guillemets | Lecture fidèle aux valeurs produites par l’exporteur |
| Aucun stage signé nouveau ou modifié | Aucun fichier produit par l’application ; aucune suppression dans la cible |

Un fichier compagnon, [exemple-vetagrotice.csv](exemple-vetagrotice.csv), contient **un stage entièrement fictif** avec les 60 colonnes, des commentaires multilignes, des guillemets et une note de zéro. Il sert à tester le lecteur, pas à importer des données réelles.

## 9. Références dans le dépôt

- [Exporteur CSV : en-têtes, ordre et encodage](../src/main/kotlin/fr/vetbrain/stagevetmanager/export/VetAgroTiceCsvExporter.kt)
- [Sélection de l’année et déclenchement de l’export](../src/main/kotlin/fr/vetbrain/stagevetmanager/viewmodel/DashboardViewModel.kt)
- [Modèle des données PDF](../src/main/kotlin/fr/vetbrain/stagevetmanager/model/ConventionPdfData.kt)
- [Construction du texte des évaluations](../src/main/kotlin/fr/vetbrain/stagevetmanager/scraper/EvaluationParser.kt)

Si ces fichiers évoluent, vérifier de nouveau le nombre de colonnes, leurs intitulés et les règles de transformation.
