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
- **Informations du livre** : tous les champs de métadonnées (auteur·rices, titres par langue,
  séries, publication, historique du document…), modifiés sur place. Voir le
  [guide d’utilisation](docs/GUIDE.fr.md).
- Ensuite : calques de texte et traductions, puis la liseuse Android.

## Compiler et lancer

Le dépôt contient le lanceur `./kotlin` (Kotlin Toolchain 0.12, JDK 25), qui installe tout au
premier usage.

```sh
./kotlin run -m app -- chemin/vers/livre.cbz   # l’éditeur
./kotlin test -p jvm                           # tous les tests
tools/fetch-fixtures.sh                        # les livres d’exemple (environ 120 Mo) pour tests et captures
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

## Licence des livres d’exemple

Les livres de `fixtures/` gardent leurs licences Creative Commons ou domaine public ; voir
[fixtures/README.md](fixtures/README.md).
