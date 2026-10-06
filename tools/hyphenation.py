#!/usr/bin/env python3
"""Converts TeX hyphenation patterns (hyph-utf8) into Tessera's resources.

Each language becomes editor/resources/hyphenation/<code>.pat, plain UTF-8:
  line 1: the shortest left and right parts ("2 3"),
  then one pattern per line, then a line "-" and one exception per line (hyphens marked).

Sources: https://github.com/hyphenation/tex-hyphen, at the commit below. Licences: see
docs/THIRD_PARTY.md. Russian is left out: its patterns are under the LPPL only.
"""
import os
import re
import sys
import urllib.request

COMMIT = "5684c0f51c0b81133db2efbe60a408b4155a3ff5"
URL = "https://raw.githubusercontent.com/hyphenation/tex-hyphen/{}/hyph-utf8/tex/generic/hyph-utf8/patterns/tex/hyph-{}.tex"
# Tessera's language code: the hyph-utf8 file.
LANGUAGES = {
    "en": "en-us", "fr": "fr", "de": "de-1996", "es": "es", "it": "it", "nl": "nl",
    "pt": "pt", "sk": "sk", "cs": "cs", "pl": "pl",
}


def block(text, command):
    m = re.search(r"\\" + command + r"\s*\{", text)
    if not m:
        return []
    end = text.index("}", m.end())
    return text[m.end():end].split()


def convert(tex):
    mins = re.search(r"hyphenmins:\s*%\s*typesetting:\s*%\s*left:\s*(\d+)\s*%\s*right:\s*(\d+)", tex)
    left, right = (mins.group(1), mins.group(2)) if mins else ("2", "3")
    body = "\n".join(line.split("%", 1)[0] for line in tex.splitlines())
    patterns = block(body, "patterns")
    exceptions = block(body, "hyphenation")
    return "\n".join([f"{left} {right}", *patterns, "-", *exceptions]) + "\n"


def main():
    out = os.path.join(os.path.dirname(__file__), "..", "editor", "resources", "hyphenation")
    os.makedirs(out, exist_ok=True)
    for code, name in LANGUAGES.items():
        with urllib.request.urlopen(URL.format(COMMIT, name)) as r:
            tex = r.read().decode("utf-8")
        data = convert(tex)
        with open(os.path.join(out, code + ".pat"), "w", encoding="utf-8") as f:
            f.write(data)
        print(code, name, len(data.splitlines()), "lines", file=sys.stderr)


if __name__ == "__main__":
    main()
