package tessera.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Language(val code: String, val label: String) {
    English("en", "English"),
    French("fr", "Français"),
    ;

    companion object {
        fun of(code: String?): Language = entries.firstOrNull { it.code == code?.lowercase()?.take(2) } ?: English
    }
}

/**
 * Every user-visible text, in English and French. [language] is Compose state: changing it
 * redraws the interface in the other language at once.
 */
object Strings {
    var language by mutableStateOf(Language.English)

    /** The command and option keys as this system names them; set by the app. */
    var cmd = "⌘"
    var alt = "⌥"

    private fun t(en: String, fr: String) = if (language == Language.French) fr else en

    val modeFrames get() = t("Frames", "Cases")
    val modeTexts get() = t("Texts", "Textes")
    val modeInfo get() = t("Book info", "Informations")
    val read get() = t("Read", "Lire")
    val save get() = t("Save", "Enregistrer")
    val cover get() = t("Cover", "Couv.")
    val coverPage get() = t("Cover", "Couverture")
    val frames get() = t("Frames", "Cases")
    val page get() = t("Page", "Planche")
    val inFile get() = t("In the file", "Dans le fichier")
    val inFileNote get() = t(
        "Only the points attributes you change are rewritten; the rest of the file stays byte for byte the same.",
        "Seuls les attributs points modifiés changent ; tout le reste du fichier est réécrit à l’octet près.",
    )
    val background get() = t("Background", "Fond")
    val transition get() = t("Transition", "Transition")
    val transitionDefault get() = t("Default (fade)", "Par défaut (fondu)")
    val autoOrder get() = t("Auto order", "Ordre auto")
    val leftToRight get() = t("Left → right", "Gauche → droite")
    val rightToLeft get() = t("Right → left", "Droite → gauche")
    val rectangle get() = t("Rectangle", "Rectangle")
    val noFrames get() = t("No frames yet. Draw one with R, or click point by point with P.", "Aucune case. Tracez-en une avec R, ou cliquez point par point avec P.")
    val fit get() = t("Fit", "Ajuster")
    val orderNeedsTwo get() = t("Reading order needs at least two frames", "Il faut au moins deux cases")
    val threePointsMinimum get() = t("A frame keeps at least three points", "Une case garde au moins trois points")
    val orderSaved get() = t("Reading order saved", "Ordre de lecture enregistré")
    val autoOrderLtr get() = t("Order: rows top to bottom, left to right", "Ordre : lignes de haut en bas, de gauche à droite")
    val autoOrderRtl get() = t("Order: rows top to bottom, right to left", "Ordre : lignes de haut en bas, de droite à gauche")
    val nothingToSave get() = t("Nothing to save", "Rien à enregistrer")
    val noFramesToRead get() = t("No frames to read", "Aucune case à lire")
    val close get() = t("Close", "Fermer")
    val loading get() = t("Loading…", "Chargement…")
    val imageMissing get() = t("Image not found in the comic", "Image introuvable dans la bande dessinée")
    val previousPage get() = t("Previous page (Page Up)", "Planche précédente (Page préc.)")
    val nextPage get() = t("Next page (Page Down)", "Planche suivante (Page suiv.)")
    val validate get() = t("Done", "Valider")
    val cancel get() = t("Cancel", "Annuler")
    val inherited get() = t("inherited", "hérité")
    val spaceKey get() = t("Space", "Espace")
    val escKey get() = t("Esc", "Échap")
    val previewKeys get() = t("← →  frame · Esc  close", "← →  case · Échap  fermer")

    fun pageOf(n: Int, total: Int) = t("Page $n of $total", "Planche $n sur $total")
    fun polygon(points: Int) = t("Polygon · $points points", "Polygone · $points points")
    fun frameOf(n: Int, total: Int) = t("Frame $n / $total", "Case $n / $total")
    fun orderProgress(done: Int, total: Int) = t("Reading order: $done / $total", "Ordre de lecture : $done / $total")
    fun saved(addedAcbf: Boolean) =
        if (addedAcbf) t("Saved: ACBF file added to the CBZ", "Enregistré : fichier ACBF ajouté au CBZ")
        else t("Saved: only the ACBF file changed", "Enregistré : seul le fichier ACBF a changé")
    fun saveFailed(reason: String?) = t("Could not save: ${reason ?: "unknown error"}", "Enregistrement impossible : ${reason ?: "erreur inconnue"}")

    // ----- Hint bar -----

    fun hintMode(tool: Tool) = when (tool) {
        Tool.Select -> t("Select", "Sélection")
        Tool.Rectangle -> t("Rectangle", "Rectangle")
        Tool.Polygon -> t("Polygon", "Polygone")
        Tool.Order -> t("Reading order", "Ordre de lecture")
    }

    fun hints(tool: Tool): List<Hint> = when (tool) {
        Tool.Select -> listOf(
            Hint(text = t("Drag a frame:", "Glisser une case :"), strong = t("move", "déplacer")),
            Hint(text = t("Drag a corner:", "Glisser un coin :"), strong = t("adjust", "ajuster")),
            Hint(text = t("Dot in the middle of a side:", "Point au milieu d’un côté :"), strong = t("add", "ajouter")),
            Hint(listOf(alt), t("click a corner:", "clic sur un coin :"), t("remove", "retirer")),
            Hint(listOf("←↑→↓"), t("1 px · ⇧ 10 px", "1 px · ⇧ 10 px")),
            Hint(listOf("⌫"), t("delete", "supprimer")),
            Hint(listOf("${cmd}Z"), t("undo", "annuler")),
            Hint(text = t("Wheel:", "Molette :"), strong = t("zoom", "zoom")),
        )
        Tool.Rectangle -> listOf(
            Hint(text = t("Drag:", "Glisser :"), strong = t("draw a frame", "tracer une case")),
            Hint(text = t("Edges snap to nearby frames and to the image border", "Les bords s’aimantent aux cases voisines et au bord de l’image")),
            Hint(listOf(escKey), t("back to Select", "retour à la sélection")),
        )
        Tool.Polygon -> listOf(
            Hint(text = t("Click:", "Clic :"), strong = t("add a point", "ajouter un point")),
            Hint(listOf("↵"), t("double-click or the first point:", "double-clic ou premier point :"), t("close", "fermer")),
            Hint(listOf("⌫"), t("last point", "dernier point")),
            Hint(listOf(escKey), t("cancel", "annuler")),
        )
        Tool.Order -> listOf(
            Hint(text = t("Click the frames", "Cliquez les cases"), strong = t("in the order they are read", "dans l’ordre où on les lit")),
            Hint(listOf("↵"), t("done", "valider")),
            Hint(listOf(escKey), t("cancel", "annuler")),
        )
    }

    val hintRead get() = Hint(listOf(spaceKey), t("read frame by frame", "lire case par case"))

    fun toolTip(tool: Tool) = when (tool) {
        Tool.Select -> t("Select (V)", "Sélection (V)")
        Tool.Rectangle -> t("Rectangle (R)", "Rectangle (R)")
        Tool.Polygon -> t("Polygon (P)", "Polygone (P)")
        Tool.Order -> t("Reading order (O)", "Ordre de lecture (O)")
    }

    val noLanguage get() = t("No language", "Sans langue")

    // ----- Book information dialog -----

    val bookInfoTitle get() = t("About this comic", "À propos de cette bande dessinée")
    val bookInfoIntro get() = t(
        "This comic has no ACBF document yet. Tessera will add one when you save; tell it what to write in it.",
        "Cette bande dessinée n’a pas encore de document ACBF. Tessera en ajoutera un à l’enregistrement ; dites-lui quoi y écrire.",
    )
    val fieldTitle get() = t("Title", "Titre")
    val fieldAuthors get() = t("Authors", "Auteur·rices")
    val fieldAuthorsHint get() = t("First and last name, or a pen name; separate several with commas", "Prénom et nom, ou pseudonyme ; plusieurs séparés par des virgules")
    val fieldGenre get() = t("Genre", "Genre")
    val fieldLanguage get() = t("Language of the book", "Langue du livre")
    val fieldSummary get() = t("Summary", "Résumé")
    val optional get() = t("optional", "facultatif")
    val fieldCreator get() = t("Your name", "Votre nom")
    val fieldCreatorHint get() = t("as the maker of this ACBF document; remembered", "comme auteur·rice de ce document ACBF ; retenu")
    val bookInfoLaterNote get() = t("Blank fields are left out of the file.", "Les champs vides ne sont pas écrits dans le fichier.")
    val later get() = t("Later", "Plus tard")

    fun genre(key: String): String = when (key) {
        "science_fiction" -> t("Science fiction", "Science-fiction")
        "fantasy" -> t("Fantasy", "Fantasy")
        "adventure" -> t("Adventure", "Aventure")
        "horror" -> t("Horror", "Horreur")
        "mystery" -> t("Mystery", "Mystère")
        "crime" -> t("Crime", "Policier")
        "military" -> t("War", "Guerre")
        "real_life" -> t("Real life", "Vie réelle")
        "superhero" -> t("Superhero", "Super-héros")
        "humor" -> t("Humour", "Humour")
        "western" -> t("Western", "Western")
        "manga" -> t("Manga", "Manga")
        "politics" -> t("Politics", "Politique")
        "caricature" -> t("Caricature", "Caricature")
        "sports" -> t("Sports", "Sport")
        "history" -> t("History", "Histoire")
        "biography" -> t("Biography", "Biographie")
        "education" -> t("Education", "Éducation")
        "computer" -> t("Computers", "Informatique")
        "religion" -> t("Religion", "Religion")
        "romance" -> t("Romance", "Romance")
        "children" -> t("Children", "Jeunesse")
        "non-fiction" -> t("Non-fiction", "Documentaire")
        "adult" -> t("Adult", "Adulte")
        "alternative" -> t("Alternative", "Alternatif")
        else -> t("Other", "Autre")
    }

    // ----- Book info tab -----

    val infoBook get() = t("Book", "Livre")
    val infoPublishing get() = t("Publishing", "Publication")
    val infoDocument get() = t("ACBF document", "Document ACBF")
    val infoReferences get() = t("References", "Références")
    val textsLanguage get() = t("Language of the title, summary and keywords", "Langue du titre, du résumé et des mots-clés")
    val addLanguage get() = t("Language", "Langue")
    val fieldGenres get() = t("Genres", "Genres")
    val addGenre get() = t("Genre", "Genre")
    val fieldKeywords get() = t("Keywords", "Mots-clés")
    val fieldCharacters get() = t("Characters", "Personnages")
    val fieldSeries get() = t("Series", "Séries")
    val seriesColumns get() = t("Series title · volume · number", "Titre de la série · tome · numéro")
    val addSeries get() = t("+ Add a series", "+ Ajouter une série")
    val readingDirection get() = t("Reading direction", "Sens de lecture")
    val authorColumns get() = t("First name · last name · pen name · role", "Prénom · nom · pseudonyme · rôle")
    val authorColumnsNoRole get() = t("First name · last name · pen name", "Prénom · nom · pseudonyme")
    val addAuthor get() = t("+ Add an author", "+ Ajouter un·e auteur·rice")
    val fieldPublisher get() = t("Publisher", "Éditeur")
    val fieldCity get() = t("City", "Ville")
    val fieldPublishDate get() = t("Publication date", "Date de publication")
    val fieldIsbn get() = t("ISBN", "ISBN")
    val fieldLicense get() = t("Licence", "Licence")
    val dateShown get() = t("as shown", "telle qu’affichée")
    val dateIso get() = t("YYYY-MM-DD", "AAAA-MM-JJ")
    val dateFormat get() = t("Write the date as YYYY-MM-DD, for example 2026-10-04.", "Écrivez la date sous la forme AAAA-MM-JJ, par exemple 2026-10-04.")
    val fieldDocumentAuthors get() = t("Authors of the document", "Auteur·rices du document")
    val fieldDocumentAuthorsNote get() = t("who made this ACBF file", "qui ont fait ce fichier ACBF")
    val fieldCreationDate get() = t("Creation date", "Date de création")
    val fieldId get() = t("Identifier", "Identifiant")
    val generateId get() = t("Generate", "Générer")
    val fieldVersion get() = t("Version", "Version")
    val fieldSource get() = t("Source", "Source")
    val fieldHistory get() = t("History", "Historique")
    val onePerLine get() = t("one paragraph per line", "un paragraphe par ligne")
    val fieldDatabases get() = t("Comic databases", "Bases de données de BD")
    val fieldDatabasesNote get() = t("for example GCD, the Grand Comics Database", "par exemple GCD, la Grand Comics Database")
    val databaseColumns get() = t("Database · reference type · value", "Base · type de référence · valeur")
    val addDatabase get() = t("+ Add a reference", "+ Ajouter une référence")
    val fieldRatings get() = t("Content ratings", "Classifications par âge")
    val ratingColumns get() = t("Rating system · rating", "Système · classification")
    val addRating get() = t("+ Add a rating", "+ Ajouter une classification")
    val phFirstName get() = t("First name", "Prénom")
    val phLastName get() = t("Last name", "Nom")
    val phNickname get() = t("Pen name", "Pseudonyme")
    val phSeriesTitle get() = t("Series title", "Titre de la série")
    val phVolume get() = t("Volume", "Tome")
    val phNumber get() = t("Number", "Numéro")
    val phDatabase get() = t("Database", "Base")
    val phRefType get() = t("Type (IssueID…)", "Type (IssueID…)")
    val phRefValue get() = t("Value or URL", "Valeur ou URL")
    val phRatingSystem get() = t("Rating system", "Système")
    val phRating get() = t("Rating", "Classification")
    val infoMode get() = t("Book info", "Informations")
    val infoHints get() = listOf(
        Hint(text = t("Changes apply at once;", "Les modifications s’appliquent aussitôt ;"), strong = t("untouched fields stay as they were", "les champs non touchés restent tels quels")),
        Hint(listOf("${cmd}Z"), t("undo", "annuler")),
        Hint(listOf("${cmd}S"), t("save", "enregistrer")),
    )

    fun activity(key: String): String = when (key) {
        "Writer" -> t("Writer", "Scénariste")
        "Adapter" -> t("Adapter", "Adaptation")
        "Artist" -> t("Artist", "Dessin")
        "Penciller" -> t("Penciller", "Crayonné")
        "Inker" -> t("Inker", "Encrage")
        "Colorist" -> t("Colorist", "Couleurs")
        "Letterer" -> t("Letterer", "Lettrage")
        "CoverArtist" -> t("Cover artist", "Couverture")
        "Photographer" -> t("Photographer", "Photographie")
        "Editor" -> t("Editor", "Direction éditoriale")
        "AssistantEditor" -> t("Assistant editor", "Assistanat éditorial")
        "Translator" -> t("Translator", "Traduction")
        "Other" -> t("Other", "Autre")
        else -> key
    }

    // ----- Display enhancement -----

    val enhanceTitle get() = t("Enhanced display", "Affichage amélioré")
    val enhanceButton get() = t("Enhanced display", "Affichage amélioré")
    val enhanceShort get() = t("Display", "Affichage")
    val compare get() = t("Compare", "Comparer")
    val compareHint get() = t("Hold to see the page without enhancement (or hold C)", "Maintenir pour voir la planche sans amélioration (ou maintenir C)")
    val withoutEnhancement get() = t("Without enhancement", "Sans amélioration")
    val enhanceBusy get() = t("Computing…", "Calcul…")
    val enhanceOff get() = t("Off", "Désactivé")
    val enhanceSharpen get() = t("Sharpen", "Netteté")
    val enhanceRestore get() = t("Restore", "Restauration")
    val enhanceSuperRes get() = t("Super-res", "Super-résolution")
    fun enhanceProgress(f: Float) = t("Computing… ${(f * 100).toInt()} %", "Calcul… ${(f * 100).toInt()} %")
    fun superResPill(f: Float?) = if (f == null) t("Super-resolution: waiting…", "Super-résolution : en attente…") else t("Super-resolution: ${(f * 100).toInt()} %", "Super-résolution : ${(f * 100).toInt()} %")
    fun enhanceSuperResNote(place: String?) = t(
        "Real-ESRGAN: the best quality, but slow: about two minutes per page the first time on a laptop, or about a minute per frame for high-definition pages, which are enhanced frame by frame when reading; you can keep reading meanwhile. " +
            (if (place != null) "Results are kept in “$place”, beside the book, and show at once afterwards." else "Results are kept in memory for this session."),
        "Real-ESRGAN : la meilleure qualité, mais lente : environ deux minutes par planche la première fois sur un portable, ou environ une minute par case pour les planches en haute définition, améliorées case par case en lecture ; vous pouvez continuer à lire en attendant. " +
            (if (place != null) "Les résultats sont gardés dans « $place », à côté du livre, puis s’affichent aussitôt." else "Les résultats sont gardés en mémoire pour cette session."),
    )
    fun framePill(superRes: Boolean, f: Float?) = when {
        !superRes -> t("Enhancing this frame…", "Amélioration de la case…")
        f == null -> t("Super-resolution of this frame: waiting…", "Super-résolution de la case : en attente…")
        else -> t("Super-resolution of this frame: ${(f * 100).toInt()} %", "Super-résolution de la case : ${(f * 100).toInt()} %")
    }
    fun aheadReady(ready: Int, total: Int) = t("✦ $ready / $total ahead ready", "✦ $ready / $total à venir prêtes")
    val alreadyHighDefinition get() = t("High-definition page: enhanced frame by frame when reading", "Planche en haute définition : améliorée case par case en lecture")
    val enhanceSharpness get() = t("Sharpness", "Netteté")
    val enhanceStrength get() = t("Strength", "Force")
    val enhanceOffNote get() = t("Pages are shown exactly as they are.", "Les planches s’affichent telles quelles.")
    val enhanceSharpenNote get() = t("Crisper lines, without halos; instant.", "Des traits plus nets, sans halo ; instantané.")
    val enhanceRestoreNote get() = t(
        "Anime4K removes compression blocks and redraws clean lines at twice the resolution. About a second per page; the next one is prepared ahead.",
        "Anime4K efface les blocs de compression et redessine des traits propres en double résolution. Environ une seconde par planche ; la suivante est préparée à l’avance.",
    )
    val enhanceScreenOnly get() = t("On screen only: files are never changed.", "Seulement à l’écran : les fichiers ne sont jamais modifiés.")

    // ----- Application -----

    val menuFile get() = t("File", "Fichier")
    val menuOpen get() = t("Open…", "Ouvrir…")
    val menuImportPdf get() = t("Import a PDF…", "Importer un PDF…")
    val importPdfDialog get() = t("Choose a PDF to import", "Choisir un PDF à importer")
    fun importTitle(name: String) = t("Import “$name”", "Importer « $name »")
    fun importPages(n: Int) = t("$n pages, saved as a CBZ comic beside the PDF.", "$n pages, enregistrées en bande dessinée CBZ à côté du PDF.")
    val importLossless get() = t("Pages that are images are taken as they are, at their own resolution, without loss. Pages with text or vector drawings are rendered at 300 dpi, or more if their images are finer.", "Les pages qui sont des images sont reprises telles quelles, à leur résolution d’origine, sans perte. Les pages avec du texte ou du dessin vectoriel sont rendues à 300 dpi, ou plus si leurs images sont plus fines.")
    val importTarget get() = t("Saved as", "Enregistré sous")
    val change get() = t("Change…", "Changer…")
    val importStart get() = t("Import", "Importer")
    fun importProgress(done: Int, total: Int) = t("Page $done of $total", "Page $done sur $total")
    fun importing(name: String) = t("Importing “$name”", "Importation de « $name »")
    fun importDone(pages: Int, kept: Int, rendered: Int) = t("Imported $pages pages: $kept taken as they were, $rendered rendered", "$pages pages importées : $kept reprises telles quelles, $rendered rendues")
    fun importFailed(reason: String?) = t("Could not import: ${reason ?: "unknown error"}", "Importation impossible : ${reason ?: "erreur inconnue"}")
    val menuSaveAs get() = t("Save As…", "Enregistrer sous…")
    val saveAsDialog get() = t("Save the comic as", "Enregistrer la bande dessinée sous")
    fun savedAs(name: String) = t("Saved as “$name”", "Enregistré sous « $name »")
    val mustStayBesideImages get() = t("An ACBF file must stay in the folder of its images. Choose a name in that folder, or save the comic as a CBZ.", "Un fichier ACBF doit rester dans le dossier de ses images. Choisissez un nom dans ce dossier.")
    val menuLanguage get() = t("Language", "Langue")
    val welcome get() = t("Open a CBZ or ACBF comic, or drop it here.", "Ouvrez une bande dessinée CBZ ou ACBF, ou déposez-la ici.")
    val openDialog get() = t("Open a comic", "Ouvrir une bande dessinée")
    val unsavedTitle get() = t("Unsaved changes", "Modifications non enregistrées")
    fun unsavedMessage(file: String) = t("Save the changes to “$file” before closing?", "Enregistrer les modifications de « $file » avant de fermer ?")
    val dontSave get() = t("Don’t save", "Ne pas enregistrer")
}
