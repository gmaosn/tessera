# Third-party work

Tessera is GPL-3.0. It includes or uses the following, under their own licences.

## Real-ESRGAN (super-resolution display)

`editor/resources/realesr-animevideov3.bin` holds the weights of Real-ESRGAN's
`realesr-animevideov3` model, converted by `tools/realesrgan_weights.py`; the network is
re-implemented in `editor/src/enhance/RealEsrgan.kt`. Source: https://github.com/xinntao/Real-ESRGAN

```
BSD 3-Clause License

Copyright (c) 2021, Xintao Wang
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Anime4K (restoration display)

`editor/src/enhance/Anime4KModels.kt` holds the CNN weights of Anime4K v3.2/v4.0, extracted by
`tools/anime4k_weights.py`. Source: https://github.com/bloc97/Anime4K. MIT licence, © 2019–2021
bloc97; the full notice is at the top of that file.

## Apache PDFBox (PDF import)

Used as a library, under the Apache License 2.0. https://pdfbox.apache.org/

## Hyphenation patterns (hyph-utf8)

`editor/resources/hyphenation/*.pat` are TeX hyphenation patterns from the hyph-utf8 collection
(https://github.com/hyphenation/tex-hyphen, commit `5684c0f`), converted by
`tools/hyphenation.py`. Each keeps its authors and licence, stated in its source file:

| File | Source | Authors | Licence |
|---|---|---|---|
| `en.pat` | `hyph-en-us.tex` | Gerard D.C. Kuiken | Copying and distribution, with or without modification, permitted in any medium without royalty, provided the copyright notice and this notice are preserved |
| `fr.pat` | `hyph-fr.tex` | Daniel Flipo, Bernard Gaulle, Arthur Reutenauer | MIT |
| `de.pat` | `hyph-de-1996.tex` | Deutschsprachige Trennmustermannschaft | MIT |
| `es.pat` | `hyph-es.tex` | Javier Bezos, CervanTeX | MIT/X11 |
| `it.pat` | `hyph-it.tex` | Claudio Beccari | MIT (or LPPL 1.3) |
| `nl.pat` | `hyph-nl.tex` | Piet Tutelaers | MIT |
| `pt.pat` | `hyph-pt.tex` | Pedro J. de Rezende, J. Joao Dias Almeida, Leonardo Araujo, Aline Benevides | BSD 3-clause |
| `sk.pat` | `hyph-sk.tex` | Jana Chlebíková | MIT |
| `cs.pat` | `hyph-cs.tex` | Pavel Ševeček | GPL 2 or later |
| `pl.pat` | `hyph-pl.tex` | Hanna Kołodziejska, Bogusław Jackowski, Marek Ryćko | MIT (or others) |

Russian is not included: its patterns are under the LPPL only.

## ACBF schemas (tests)

`fixtures/schema/` holds the ACBF 1.0 and 1.1 XML schemas, unchanged, from
https://github.com/ACBF-Advanced-Comic-Book-Format/ACBF (GPL-3.0).
