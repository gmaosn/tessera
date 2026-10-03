# Tessera

A modern editor and reader for ACBF comics (Advanced Comic Book Format), in Kotlin and Compose.
Perfect compatibility with ACBF: files are rewritten byte for byte, except what you change.

- `core/` — lossless XML, the ACBF model, CBZ reading and raw rewriting (JVM and Android).
- `editor/` — the frame editor's logic and Compose UI (desktop now, tablet later).
- `app/` — the desktop application.

```sh
./kotlin test -p jvm                        # every test
./kotlin run -m app -- path/to/comic.cbz    # the editor
tools/fetch-fixtures.sh                     # the sample books used by tests and screenshots
```

`editor/test@jvm/ScreensTest.kt` renders each screen into `build/screens/` for a visual check.
See `docs/COMPATIBILITY.md` for what the format and the original tools taught us, and
`NOTES.md` (French) for the owner's decisions.
