# ACBF compatibility notes

What the specification, the original tools and the real files taught us (2026-10-04).
Sources: specification 1.1 (`ACBF - File Format Specifications-1.1.odt`), the ACBF wiki
(1.2 proposal), ACBF Viewer and ACBF Editor (GitHub, GTK), libacbf, and the eight documents
in `fixtures/acbf-xml`.

## Versions and namespaces

The latest released specification is **1.1** (2017). "1.2" is only a proposal on the wiki:
`reading-direction` (LTR/RTL) in book-info, text-area type `sound`, author activity
`Designer`, genre `artbook`, and `lang` on `reference`. We read all of it and never add
anything else to a file.

Four namespaces exist in practice; a document keeps the one it was read with:

| Namespace | Where |
|---|---|
| `http://www.fictionbook-lib.org/xml/acbf/1.0` | 1.0 files |
| `http://www.fictionbook-lib.org/xml/acbf/1.1` | most 1.1 files from ACBF Editor 1.1x (not in the spec) |
| `http://www.acbf.info/xml/acbf/1.1` | the specification's 1.1 namespace |
| `http://www.fictionbook-lib.org/xml/acbf/1.2` | new files from the GTK4 ACBF Editor |

New documents get `http://www.acbf.info/xml/acbf/1.1`.

## Round trip

The XML layer (`core/src/xml`) keeps every node's source text, so an untouched document is
written back byte for byte (checked on every fixture), and an edited one changes only the
edited attribute or element. Inserted elements copy the indentation and line breaks around
them. Comments, processing instructions (such as `xml-stylesheet`), unknown elements and
attributes, CDATA and unresolved entities are all kept.

Saving a CBZ copies every other entry raw, local header and compressed data included, so
images and fonts are never recompressed. Only the `.acbf` entry is rewritten, at its place.

## Points

ACBF Viewer reads `points` with `split(' ')` and `int()`. We therefore always write integers
separated by one space, with no trailing space. When reading, we accept anything (double
spaces, stray commas as in the specification's own example, decimals).

## Pages

The cover (`book-info/coverpage`) is page 1 for readers, followed by the body pages. A cover
may have frames and text layers (1.1). Image `href`s are paths relative to the ACBF file
(inside the archive too, sub-folders included), `#id` for embedded `<binary>` data, URLs, or
`zip:archive!/path`. Some CBZ books ship fonts in `Fonts/`, referenced by the CSS in `<style>`.

## Corpus

Frames are often polygons of 5 to 20+ points (rounded or slanted panels), not only rectangles.
Two books have no frames at all (Bad Arguments, NYC2123: 422 pages together): the first
use for the frame editor.
