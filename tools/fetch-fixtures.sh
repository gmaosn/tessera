#!/bin/sh
# Downloads the official ACBF sample books (about 120 MB) into fixtures/samples, and refreshes
# the ACBF documents extracted from them in fixtures/acbf-xml.
set -eu
cd "$(dirname "$0")/.."
mkdir -p fixtures/samples fixtures/acbf-xml
cd fixtures/samples
curl -fL -o SampleBooks.zip "https://launchpad.net/acbf/trunk/1.1/+download/SampleBooks.zip"
curl -fL -o "Doctorow, Cory - Craphound-1.1.cbz" "https://launchpad.net/acbf/trunk/1.1/+download/Doctorow%2C%20Cory%20-%20Craphound.cbz"
unzip -oq SampleBooks.zip && rm SampleBooks.zip
for z in *.cbz; do
  n=$(unzip -Z1 "$z" | grep -i '\.acbf$' | head -1) || true
  [ -n "$n" ] && unzip -p "$z" "$n" > "../acbf-xml/${z%.cbz}.acbf"
done
