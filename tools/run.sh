#!/bin/sh
# Runs Tessera from a build directory of its own, so that later builds and tests never replace
# the classes of an open window (it would fail with NoClassDefFoundError). Arguments are passed on.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
run_dir=$(mktemp -d "${TMPDIR:-/tmp}/tessera-run.XXXXXX")
printf 'Tessera: %s\n' "$run_dir"
cd "$project_dir"
exec ./kotlin run -m app --build-dir "$run_dir" -- "$@"
