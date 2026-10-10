# Tessera

*[English version](README.md)*

Un éditeur et une liseuse modernes pour les bandes dessinées au format ACBF (Advanced Comic
Book Format), en Kotlin et Compose.

ACBF décrit les cases d’une bande dessinée, pour qu’une liseuse zoome de case en case sur un
petit écran ; ses calques de texte par langue, pour la traduire sans toucher aux images ; et des
métadonnées riches. Le format est ouvert et bon ; ses outils (ACBF Editor et ACBF Viewer, en
Python et GTK) ont vieilli. Tessera vise une compatibilité parfaite : un fichier est réécrit à
l’octet près, sauf ce que vous avez modifié.

![L’éditeur de cases](docs/images/editor-fr.jpg)

## État

- **Éditeur de cases** (ordinateur) : tracé, retouche et ordre des cases, lecture case par case,
  annulation, enregistrement, en anglais et en français.
- **Import de PDF** : toujours sans perte, à la meilleure qualité que contient le PDF ; devient un CBZ.
- **Import de scans** : les scans à plat d’un livre ouvert deviennent des pages droites, coupées
  au pli (planches doubles gardées entières), dépliées près du dos, sans ombre, recadrées ; un CBZ sans perte.
- **Affichage amélioré** : netteté, restauration Anime4K (instantanée) ou super-résolution
  Real-ESRGAN (lente, gardée à côté du livre), en double résolution, seulement à l’écran.
- **Informations du livre** : tous les champs de métadonnées (auteur·rices, titres par langue,
  séries, publication, historique du document…), modifiés sur place. Voir le
  [guide d’utilisation](docs/GUIDE.fr.md).
- **Textes et traductions** : zones de texte par langue, tracées comme les cases, saisies sur
  place, avec le texte de l’autre langue à côté de chacune et ses zones copiées d’un clic.
- **Lecture traduite** : la lecture case par case pose la langue choisie sur les images (L pour
  changer).
- Ensuite : la liseuse Android.

## Compiler et lancer

Le dépôt contient le lanceur `./kotlin` (Kotlin Toolchain 0.12, JDK 25), qui installe tout au
premier usage.

```sh
tools/run.sh chemin/vers/livre.cbz            # l’éditeur, dans son propre dossier de compilation
./kotlin test -p jvm                           # tous les tests
tools/fetch-fixtures.sh                        # les livres d’exemple (environ 120 Mo) pour tests et captures
```

Un seul jar pour macOS, Windows et Linux, x64 et ARM64 (à lancer par `java -jar`, Java 25) :

```sh
./kotlin package -m app -p jvm -f executable-jar --build-dir /tmp/tessera-pack
tools/package-universal.py --input /tmp/tessera-pack/tasks/_app_executableJarJvm/app-jvm-executable.jar --output Tessera-universel.jar
```

## Documentation

- [Guide d’utilisation](docs/GUIDE.fr.md) · [User guide](docs/GUIDE.md)
- [Architecture](docs/ARCHITECTURE.md) (en anglais) : modules, couche XML sans perte,
  enregistrement.
- [Notes de compatibilité ACBF](docs/COMPATIBILITY.md) (en anglais) : ce que la spécification,
  les outils d’origine et les vrais fichiers nous ont appris.

## Licence

Tessera est un logiciel libre sous [licence publique générale GNU v3.0](LICENSE), comme
ACBF Viewer et ACBF Editor d’origine.

## Travaux de tiers

L’affichage amélioré utilise les poids de [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)
(BSD-3) et d’[Anime4K](https://github.com/bloc97/Anime4K) (MIT), exécutés par le moteur Kotlin
de Tessera. Licences et détails : [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).

L’import de PDF utilise [Apache PDFBox](https://pdfbox.apache.org/) (licence Apache 2.0).

## Licence des livres d’exemple

Les livres de `fixtures/` gardent leurs licences Creative Commons ou domaine public ; voir
[fixtures/README.md](fixtures/README.md).
