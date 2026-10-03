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
  saving, English and French. See the [user guide](docs/GUIDE.md).
- Next: text layers and translations, book information, then the Android reader.

## Building and running

The repository carries the `./kotlin` launcher (Kotlin Toolchain 0.12, JDK 25), which provisions
everything on first use.

```sh
./kotlin run -m app -- path/to/comic.cbz    # the editor
./kotlin test -p jvm                        # every test
tools/fetch-fixtures.sh                     # the sample books (about 120 MB) for tests and screenshots
```

## Documentation

- [User guide](docs/GUIDE.md) · [Guide d’utilisation](docs/GUIDE.fr.md)
- [Architecture](docs/ARCHITECTURE.md): modules, the lossless XML layer, how saving works.
- [ACBF compatibility notes](docs/COMPATIBILITY.md): what the specification, the original tools
  and real files taught us.
- `NOTES.md` (French): the owner's decisions and progress.

## Licence of the sample books

The books in `fixtures/` keep their own Creative Commons or public-domain licences; see
[fixtures/README.md](fixtures/README.md).
