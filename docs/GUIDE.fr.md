# Guide d’utilisation de Tessera

*[English version](GUIDE.md)*

Tessera édite les cases des bandes dessinées au format ACBF, pour que les liseuses puissent
les montrer case par case, en zoomant d’une case à la suivante. Il ouvre les archives CBZ,
avec ou sans document ACBF à l’intérieur, et les fichiers `.acbf` seuls.

![L’éditeur de cases](images/editor-fr.jpg)

## Ouvrir une bande dessinée

- **Fichier → Ouvrir…** (⌘O sur macOS, Ctrl+O ailleurs), ou déposez un fichier sur la fenêtre.
- Depuis un terminal : `./kotlin run -m app -- chemin/vers/livre.cbz`.

Formats pris en charge : archives `.cbz` et `.zip`, documents `.acbf` (images à côté, intégrées
au document en base64, ou dans des sous-dossiers). Un CBZ sans document ACBF s’ouvre aussi :
Tessera prend ses images dans l’ordre naturel (page2 avant page10), la première comme
couverture. L’enregistrement ajoute alors un document ACBF à l’archive.

Les archives CBR (RAR) ne sont pas encore prises en charge.

## La fenêtre

| Zone | Contenu |
|---|---|
| Barre du haut | **‹ ›** planche précédente et suivante, nom du fichier (un point s’il y a des modifications non enregistrées), numéro de planche et version ACBF, **Lire** et **Enregistrer** |
| Bandeau de gauche | Toutes les planches avec leur nombre de cases ; un **0** en pointillé signale une planche sans case |
| Canevas | La planche et ses cases, numérotées dans l’ordre de lecture ; les outils à gauche, le zoom en bas à droite |
| Inspecteur | La liste des cases, les réglages de la planche, et ses cases telles qu’elles sont écrites dans le fichier |
| Barre d’aide | Ce que fait l’outil en cours, et ses touches |

**Textes** et **Informations** sont les prochaines étapes du projet ; ils sont grisés pour
l’instant.

Pendant le décodage d’une image, le canevas affiche **Chargement…** ; sur les gros livres, cela
peut prendre un instant par planche, et les planches voisines sont préparées à l’avance.

## Tracer des cases

| Outil | Touche | Comment |
|---|---|---|
| Sélection | V | Cliquez une case pour la sélectionner |
| Rectangle | R | Tracez un rectangle en glissant |
| Polygone | P | Cliquez chaque coin ; recliquez le premier point, ou appuyez sur ↵, pour fermer. ⌫ retire le dernier point |
| Ordre de lecture | O | Cliquez les cases dans l’ordre où on les lit |

Les bords **s’aimantent** au bord de l’image et aux coins des autres cases : des cases voisines
s’alignent sans interstice. Une ligne rouge pointillée montre à quoi un bord s’est aimanté.
Après un tracé, Tessera revient à la sélection, la nouvelle case sélectionnée.

## Retoucher une case

Avec l’outil Sélection :

- **Déplacer** : glissez la case.
- **Ajuster un coin** : glissez l’une de ses poignées carrées.
- **Ajouter un coin** : glissez le petit point au milieu d’un côté.
- **Retirer un coin** : ⌥-clic dessus (Alt-clic sous Windows et Linux). Une case garde au moins
  trois coins.
- **Décaler** : les flèches déplacent la case sélectionnée de 1 pixel, de 10 avec ⇧.
- **Supprimer** : ⌫ ou Suppr.

Les cases peuvent se chevaucher. Un clic choisit la plus petite case sous le pointeur, si bien
qu’une case tracée à l’intérieur d’une autre reste accessible.

## Ordre de lecture

Les liseuses montrent les cases dans l’ordre du fichier. Trois façons de le régler :

- **Ordre auto** (inspecteur) : les lignes de haut en bas, et chaque ligne dans le sens choisi
  juste en dessous, **Gauche → droite** ou **Droite → gauche** (manga). Le sens part de
  l’élément `reading-direction` du livre quand il en a un.
- **Outil Ordre de lecture** (O) : cliquez les cases l’une après l’autre. Les cases non
  cliquées gardent leur ordre, après les autres. ↵ ou **Valider** l’applique ; Échap annule.
- **Glissez une ligne** de la liste des cases par sa poignée (⋮⋮).

![Régler l’ordre de lecture au clic](images/reading-order.jpg)

## Lire case par case

**Lire** (ou Espace) montre la planche comme une liseuse : la vue glisse de case en case, et
tout ce qui sort de la case prend sa couleur de fond (celle de la case, sinon de la planche,
sinon du livre). Les flèches, Page préc./suiv. ou un clic passent d’une case à l’autre (un clic
dans le tiers gauche revient en arrière). Échap ou Espace ferme.

![Lecture case par case](images/reading-preview.jpg)

## Réglages de la planche

- **Fond** montre la couleur utilisée par les liseuses autour d’une case zoomée, et indique si
  elle est propre à la planche ou héritée du livre.
- **Transition** est l’animation depuis la planche précédente : fondu, mélange, défilement vers
  la droite, vers le bas, ou aucune.

## Se déplacer

| Action | Touches |
|---|---|
| Planche suivante / précédente | **‹ ›** dans la barre du haut, une miniature, Page suiv. / Page préc., ⌥ et une flèche, ou une flèche seule quand aucune case n’est sélectionnée |
| Zoomer | ⌘ et la molette, ⌘+ / ⌘−, ou les boutons de zoom |
| Ajuster la planche | ⌘0 ou **Ajuster** |
| Faire défiler | Molette (⇧ pour l’horizontale), ou glisser avec le bouton droit ou du milieu |
| Annuler / rétablir | ⌘Z / ⇧⌘Z |
| Enregistrer | ⌘S |
| Enregistrer sous | ⇧⌘S |

Sous Windows et Linux, Ctrl remplace ⌘.

## Enregistrement et compatibilité

**Enregistrer** (⌘S) réécrit la bande dessinée sur place, par un fichier temporaire qui ne
remplace l’original qu’une fois complet. **Fichier → Enregistrer sous…** (⇧⌘S) écrit une copie
sous un autre nom et continue sur cette copie ; un CBZ peut aller n’importe où, mais un document
ACBF dont les images sont à côté doit rester dans leur dossier. Dans un CBZ, seul le document ACBF est réécrit ; images et
polices sont recopiées telles quelles, sans recompression.

Tessera garde tout ce qu’il ne modifie pas : autres métadonnées, calques de texte, styles,
commentaires, éléments inconnus, et l’indentation du fichier. Ce qui change, ce sont les
attributs `points` des cases retouchées, et les éléments de case ajoutés, déplacés ou
supprimés. Le panneau **Dans le fichier** montre les cases de la planche telles qu’elles seront
écrites, vos changements en couleur. Les coordonnées sont toujours écrites en pixels entiers
séparés par une seule espace, la forme que toutes les liseuses ACBF comprennent.

Tout annuler redonne le fichier d’origine à l’octet près. Si vous fermez la fenêtre ou ouvrez
une autre bande dessinée avec des modifications non enregistrées, Tessera propose de les
enregistrer.

## Langue

**Langue** dans la barre de menus passe aussitôt de l’anglais au français. Le choix est retenu ;
la première fois, Tessera suit la langue du système.
