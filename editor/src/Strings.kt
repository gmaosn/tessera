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
            Hint(listOf("↵"), t("or click the first point:", "ou clic sur le premier point :"), t("close", "fermer")),
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

    // ----- Application -----

    val menuFile get() = t("File", "Fichier")
    val menuOpen get() = t("Open…", "Ouvrir…")
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
