# Tessera user guide

*[Version française](GUIDE.fr.md)*

Tessera edits the frames of ACBF comics, so that readers can show a comic frame by frame,
zooming from one frame to the next. It opens CBZ archives, with or without an ACBF document
inside, and standalone `.acbf` files.

![The frame editor](images/editor-en.jpg)

## Opening a comic

- **File → Open…** (⌘O on macOS, Ctrl+O elsewhere), or drop a file on the window.
- From a terminal: `./kotlin run -m app -- path/to/comic.cbz`.

Supported: `.cbz` and `.zip` archives, `.acbf` documents (images beside them, inside the
document in base64, or in sub-folders). A CBZ without an ACBF document is opened too: Tessera
lists its images in natural order (page2 before page10), the first one as the cover, and asks
**About this comic**: title (from the file name), authors (first and last name, or a pen name;
several separated by commas), genre, the book's language, an optional summary, and your name as
the maker of the ACBF document (remembered). Blank fields are left out of the file; **Later**
keeps just the title. Saving adds the ACBF document to the archive; it is valid against the
official ACBF 1.1 schema.

CBR (RAR) archives are not supported yet.

## The window

| Area | What it holds |
|---|---|
| Top bar | **‹ ›** previous and next page, file name (a dot when there are unsaved changes), page number and ACBF version, **Read** and **Save** |
| Left strip | Every page with its number of frames; a dashed **0** marks pages without frames |
| Canvas | The page with its frames, numbered in reading order; the tools on the left, the zoom at the bottom right |
| Inspector | The frame list, the page settings, and the page's frames as they are written in the file |
| Hint bar | What the current tool does, and its keys |

**Texts** is the next step of the project; it is greyed for now.

While a page image is being decoded, the canvas says **Loading…**; large books can take a moment
per page, and the next and previous pages are prepared in advance.

## Drawing frames

| Tool | Key | How |
|---|---|---|
| Select | V | Click a frame to select it |
| Rectangle | R | Drag out a rectangle |
| Polygon | P | Click each corner; click the first point again, or press ↵, to close. ⌫ removes the last point |
| Reading order | O | Click the frames in the order they are read |

Edges **snap** to the image border and to the corners of the other frames, so neighbouring
frames line up without gaps. A red dashed line shows what an edge snapped to. After drawing,
Tessera goes back to Select with the new frame selected.

## Adjusting a frame

With the Select tool:

- **Move**: drag the frame.
- **Adjust a corner**: drag one of its square handles.
- **Add a corner**: drag the small dot in the middle of a side.
- **Remove a corner**: ⌥-click it (Alt-click on Windows and Linux). A frame keeps at least
  three corners.
- **Nudge**: arrow keys move the selected frame by 1 pixel, with ⇧ by 10.
- **Delete**: ⌫ or Delete.

Overlapping frames are allowed. A click picks the smallest frame under the pointer, so a frame
drawn inside another stays reachable.

## Reading order

Readers show frames in the order they appear in the file. Three ways to set it:

- **Auto order** (inspector): rows from top to bottom, and each row in the direction chosen
  just below, **Left → right** or **Right → left** (manga). The direction starts from the
  book's own `reading-direction` when it has one.
- **Reading order tool** (O): click the frames one after the other. Frames you did not click
  keep their order after the ones you did. ↵ or **Done** applies it; Esc cancels.
- **Drag a row** of the frame list by its grip (⋮⋮).

![Setting the reading order by clicking](images/reading-order.jpg)

## Reading frame by frame

**Read** (or Space) shows the page as a reader app would: the view glides from frame to
frame, and everything outside the frame takes the frame's background colour (the frame's own,
else the page's, else the book's). Arrows, Page Up/Down or a click move between frames (a click
on the left third goes back). After a page's last frame comes the next page's first frame, with
a fade; a page without frames is shown whole. Esc or Space closes it, and the editor goes to the
page you reached.

![Reading frame by frame](images/reading-preview.jpg)

## Enhanced display

Low-resolution scans look blurry or blocky once enlarged, especially when reading frame by
frame. **✧ Display** at the right end of the zoom pill (editor) and **✧ Enhanced display** in the reading bar open the
display settings, remembered separately for editing and reading:

- **Off**: pages as they are.
- **Sharpen**: crisper lines without halos (AMD's contrast-adaptive sharpening), instant.
- **Restore**: Anime4K, neural networks trained on line art, removes compression blocks and
  redraws clean lines at twice the resolution. About a second per page, computed in the
  background (the plain page shows meanwhile, then is replaced); while reading, the next page
  is prepared ahead.

**Sharpness** and, for Restore, **Strength** adjust the effect from 0 to 100 %. To compare,
hold **◐ Compare** (beside the display button, in the editor and in the reading bar) or hold
**C**: the page shows without enhancement until you let go. The star turns
solid (✦) when an enhancement is on. This changes only what you see: files are never touched,
and frames are always drawn on the page's real pixels. For frame work at the pixel, leave the
editor on Off.

![Reading with Restore](images/reader-restored.jpg)

## Page settings

- **Background** shows the colour readers use around a zoomed frame, and whether it is the
  page's own or inherited from the book.
- **Transition** is the animation from the previous page: fade, blend, scroll right, scroll
  down, or none.

## Book information

**Book info** in the top bar shows every metadata field of the document, in four cards:

- **Book**: title, authors (first name, last name or pen name, and role: writer, artist,
  colourist, translator…), genres, summary, keywords, characters, series (title, volume,
  number). Title, summary and keywords exist per language: pick the language in the chips at
  the top, or add one with **+ Language**.
- **Publishing**: publisher, city, publication date, ISBN, licence.
- **ACBF document**: who made this ACBF file, creation date, identifier (**Generate** makes a
  unique one), version, source and history (one paragraph per line).
- **References**: comic database entries (such as GCD, the Grand Comics Database) and content
  ratings.

![Book information](images/book-info-en.jpg)

Dates have two fields: the text shown to readers ("Spring 1953") and the machine-readable date
as YYYY-MM-DD. Changes apply as you type; ⌘Z undoes a whole field at a time. As with frames,
only what you change is rewritten; a field you empty keeps its (empty) element, so retyping a
value puts it back where it was. A summary paragraph you do not touch keeps its emphasis and
links. The reading direction appears only for documents that already use the ACBF 1.2
proposal, since ACBF 1.1 files may not contain it.

## Moving around

| Action | Keys |
|---|---|
| Next / previous page | **‹ ›** in the top bar, a thumbnail, Page Down / Page Up, ⌥ and an arrow, or an arrow alone when no frame is selected |
| Zoom | The mouse wheel (around the pointer), ⌘+ / ⌘−, or the zoom buttons |
| Fit the page | ⌘0 or **Fit** |
| Scroll | Drag where there is no frame (Select tool), drag with the right or middle button, ⌘ and the wheel, or ⇧ and the wheel sideways |
| Undo / redo | ⌘Z / ⇧⌘Z |
| Save | ⌘S |
| Save as | ⇧⌘S |

On Windows and Linux, Ctrl replaces ⌘.

## Saving and compatibility

**Save** (⌘S) writes the comic back in place, through a temporary file that replaces the original
only once complete. **File → Save As…** (⇧⌘S) writes a copy under another name and goes on
editing the copy; a CBZ can go anywhere, while an ACBF document whose images lie beside it must
stay in their folder. Inside a CBZ, only the ACBF document is rewritten; images and fonts are copied
as they are, without recompression.

Tessera keeps everything it does not edit: other metadata, text layers, styles, comments,
unknown elements, and the file's own indentation. What changes are the `points` attributes of
the frames you touched, and the frame elements you added, moved or removed. The **In the file**
panel shows the page's frames as they will be written, with your changes highlighted. Points
are always written as whole pixels separated by single spaces, the form every ACBF reader
understands.

Undoing everything gives back the original file byte for byte. When you close the window or
open another comic with unsaved changes, Tessera asks whether to save them.

## Language

**Language** in the menu bar switches between English and French at once. The choice is
remembered; the first time, Tessera follows the system's language.
