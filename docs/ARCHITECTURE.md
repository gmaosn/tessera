# Architecture

## Modules

| Module | Platforms | Role |
|---|---|---|
| `core` | JVM, Android | Lossless XML, the ACBF model, ZIP (CBZ) reading and raw rewriting. No UI. |
| `editor` | JVM, Android | The frame editor: session (undo, dirty state), frame tool, Compose UI, strings. |
| `app` | JVM desktop | The window, menus, file dialogs, drag and drop, the unsaved-changes prompt. |

Everything is pure Kotlin. Platform code is small and isolated: DEFLATE (`java.util.zip`) in
`core/src@jvm` and `core/src@android`, image decoding (Skia on the desktop, `BitmapFactory` on
Android) in `editor/src@jvm` and `editor/src@android`, and file access (`ComicFiles`) on the JVM.
The editor UI is common code, so a tablet version needs only a host, not a rewrite; nothing in
it depends on hover or right-click alone.

## Lossless XML (`core/src/xml`)

`XmlParser` builds a tree in which every node keeps its source text: attributes keep their
order, quotes and spacing; text keeps its entities; comments, processing instructions, CDATA
and the DOCTYPE are nodes too. `write()` gives back the source byte for byte. Changing an
attribute or a text marks only that node, which is then serialised afresh.

`XmlLayout.kt` inserts and removes elements with the indentation and line breaks of their
neighbours, so an edit looks as if the original tool had written it.

## ACBF model (`core/src/acbf`)

`AcbfDocument`, `AcbfPage` and `AcbfFrame` are thin views over the XML tree; there is no
second model to keep in sync, and whatever they do not know about is untouched. Pages are the
cover first, then the body pages, as readers number them.

Frame edits: `addFrame`, `removeFrame`, `moveFrame`, and `frameState`/`restoreFrames` for undo.
`restoreFrames` brings back the very same elements in their places, which is why undoing every
change gives back the original bytes (tested on four real books).

`Polygon.format()` writes points the only way ACBF Viewer can read them: integers, one space
between points (it parses with `split(' ')` and `int()`).

## Comics and saving

`Comic` ties a document to a `Container` (a CBZ, a folder, or nothing) and resolves image
`href`s: relative paths (to the ACBF file's folder), `#id` for embedded binaries, case
differences, percent-encoding.

`ZipArchive` reads the central directory and inflates entries on demand. `ZipRewriter` writes a
new archive in which every entry is copied raw (local header, compressed data, data descriptor)
except the ACBF document, which is replaced in place or added at the end. `ComicFiles.save`
writes to a temporary file beside the original and moves it over atomically, then reopens the
archive while keeping the same in-memory document, so undo history survives saving.

## Editor (`editor/src`)

- `Session`: the open comic, the current page, undo and redo steps (frame states or attribute
  changes, text-layer states), the dirty flag, and the frames' saved points for the "In the file" highlight.
  `beginGesture()` turns a drag into a single undo step.
- `FrameTool`: everything between the pointer and the page's `Shapes` (its frames, or the text
  areas of one language: `FrameShapes`, `TextShapes`), in image pixels. The canvas
  passes the zoom so that handle sizes and snapping distances stay constant on screen. Pure
  logic, tested without a UI (`editor/test/FrameToolTest.kt`).
- `PageCanvas` (`CanvasView` holds zoom and scroll), `PageStrip`, `Inspector`, `ReaderPreview`,
  `EditorScreen`: the Compose UI, in Aster's palette (`Theme.kt`).
- `ReaderPreview` animates one progress value: camera position, a geometric zoom, the frame
  outline and the background colour. `Outline` turns a frame's polygon into the cut shown:
  sharp corners kept, curve-like runs of points smoothed (cubic curves through the points),
  inward corners filleted, then walked at 512 even steps with every corner on a step, so that
  two outlines morph point by point (`editor/test/OutlineTest.kt`). `PageCanvas` dashes it.
- `TextFit` lays a text out inside a polygon (each line as wide as the shape at its height,
  largest size that fits, by bisection on widths measured once); `Hyphenator` is Liang's
  algorithm over the hyph-utf8 patterns in `editor/resources/hyphenation` (made by
  `tools/hyphenation.py`); `groundColour` takes the colour behind a text from the page image
  (Otsu split, letters and their soft edges left out).
- `Balloon` finds a balloon from a click (flood of the light inside, letters filled, outline
  measured in rings of ink, notches closed, contours traced and simplified) and grows a frame
  round it or cuts it out of one, keeping the frame's other points.
- `Strings`: every text in English and French. `Strings.language` is Compose state; changing it
  redraws the UI at once.

## Display enhancement (`editor/src/enhance`)

`Enhancer` turns a page's ARGB pixels into a display version: `sharpen` is AMD's
contrast-adaptive sharpening; Restore runs two Anime4K CNNs, `RESTORE_M` then `UPSCALE_X2_M`,
through `Cnn`, a small inference engine for Anime4K's shader passes (sums of `mat4 × input`
terms over 3×3 neighbourhoods, CReLU activations, a final residual or depth-to-space). It works
tile by tile (160 px plus the network's 7 px reach) on every core, so memory stays small; about
a second for a 1000×1500 page on a laptop. `tools/anime4k_weights.py` extracts the weights from
Anime4K's GLSL into base64 float strings. `ImageCache.enhanced` caches results by page and
settings; the canvas and the reader draw them in place of the page at the page's own size.

### Real-ESRGAN

`RealEsrgan` re-implements Real-ESRGAN's compact network (`realesr-animevideov3`: 17 3×3
convolutions of 64 channels with PReLU, one to 48, a ×4 pixel shuffle plus the input) with the
reference's zero padding, so it matches PyTorch to one level out of 255 (`RealEsrganTest`
compares it with a PyTorch output in `fixtures/sr`). Tiles of 128 px plus an 18 px margin run on
every core; four neighbouring pixels share each weight load; about two minutes for a 1000×1500
page on an M1. The ×4 result is averaged down to ×2 as it is produced. `ImageCache` runs one page
at a time, drops queued pages no longer shown, keeps started ones going, and saves results
through a `SuperResStore` (the app's `SuperResSidecar`: `Book.cbz.tessera/real-esrgan-x2/`,
keyed by the image's CRC and size; the user cache folder when the book's folder is read-only).

## Tests

- `core/test`, `core/test@jvm`: XML round trips and edits, geometry, the corpus (byte-for-byte
  round trip of the eight sample documents, frame edits, undo), CBZ reading and rewriting
  (checked with `java.util.zip` as an independent reader).
- `editor/test`: the frame tool (drawing, snapping, handles, order, undo).
- `editor/test@jvm/ScreensTest.kt`: renders every screen state on real books into
  `build/screens/` for a visual check, including the reading preview mid-transition.

## Diagnostics

`TESSERA_TRACE=1 ./kotlin run -m app` writes a trace to `build/trace.log`: every press and
release reaching the editor, thumbnail clicks, page changes, image loading, file loads. Commands
written to `build/control`, one per line, are run by the window: `shot NAME` (its own rendering
to `build/screens/NAME.png`), `click X Y` (window points), `load PATH`, and `dialog PATH` (shows
the Open dialog, closes it, then opens PATH). Run a single instance at a time: they share the
files. Start windows with `tools/run.sh` (own build directory, so builds and tests never pull
classes from under an open window) and stop them with `tools/stop.sh`. Off by default; `Trace` costs nothing when no sink is set.
