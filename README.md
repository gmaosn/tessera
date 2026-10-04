# Tessera

*[Version française](README.fr.md)*

A modern editor and reader for comics in the ACBF format (Advanced Comic Book Format), written
in Kotlin and Compose.

ACBF describes a comic's frames, so that a reader can zoom from frame to frame on a small
screen; its text layers per language, so that a comic can be translated without touching the
images; and rich metadata. The format is open and good; its tools (ACBF Editor and ACBF
Viewer, Python and GTK) have aged. Tessera aims at perfect compatibility: a file is written back
byte for byte, except what you changed.

![The frame editor](docs/images/editor-en.jpg)

## Status

- **Frame editor** (desktop): drawing, adjusting and ordering frames, reading preview, undo,
  saving, English and French.
- **PDF import**: always lossless, at the best quality the PDF holds; becomes a CBZ.
- **Enhanced display**: optional sharpening, Anime4K restoration (instant) or Real-ESRGAN
  super-resolution (slow, kept beside the book), at twice the resolution, on screen only.
- **Book information**: every metadata field (authors, titles per language, series, publishing,
  document history…), edited in place. See the [user guide](docs/GUIDE.md).
- Next: text layers and translations, then the Android reader.

## Building and running

The repository carries the `./kotlin` launcher (Kotlin Toolchain 0.12, JDK 25), which provisions
everything on first use.

```sh
tools/run.sh path/to/comic.cbz              # the editor, in a build directory of its own
./kotlin test -p jvm                        # every test
tools/fetch-fixtures.sh                     # the sample books (about 120 MB) for tests and screenshots
```

One jar for macOS, Windows and Linux, x64 and ARM64 (run with `java -jar`, Java 25):

```sh
./kotlin package -m app -p jvm -f executable-jar --build-dir /tmp/tessera-pack
tools/package-universal.py --input /tmp/tessera-pack/tasks/_app_executableJarJvm/app-jvm-executable.jar --output Tessera-universel.jar
```

## Documentation

- [User guide](docs/GUIDE.md) · [Guide d’utilisation](docs/GUIDE.fr.md)
- [Architecture](docs/ARCHITECTURE.md): modules, the lossless XML layer, how saving works.
- [ACBF compatibility notes](docs/COMPATIBILITY.md): what the specification, the original tools
  and real files taught us.

## Licence

Tessera is free software under the [GNU General Public License v3.0](LICENSE), like the
original ACBF Viewer and ACBF Editor.

## Third-party work

Display enhancement uses the weights of [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)
(BSD-3-Clause) and [Anime4K](https://github.com/bloc97/Anime4K) (MIT), run by Tessera's own
Kotlin engine. Licences and details: [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).

PDF import uses [Apache PDFBox](https://pdfbox.apache.org/) (Apache License 2.0).

## Licence of the sample books

The books in `fixtures/` keep their own Creative Commons or public-domain licences; see
[fixtures/README.md](fixtures/README.md).
