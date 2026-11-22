#!/usr/bin/env bash
# Builds the documentation site into site/: Javadoc for the public API plus the MkDocs guide.
# Usage: docs/build.sh [serve]     (run from anywhere; needs Maven, a JDK and Python 3)
set -euo pipefail
cd "$(dirname "$0")/.."

VENV="${DOCS_VENV:-.venv-docs}"
if [ ! -x "$VENV/bin/mkdocs" ]; then
    python3 -m venv "$VENV"
    "$VENV/bin/pip" install --quiet -r docs/requirements.txt
fi

# API reference: public packages only; *.internal is implementation detail.
rm -rf target/reports/apidocs docs/javadoc
mvn -q javadoc:javadoc \
    -DexcludePackageNames='io.github.janglada.orkit.internal:io.github.janglada.orkit.*.internal'
cp -r target/reports/apidocs docs/javadoc

if [ "${1:-}" = "serve" ]; then
    exec "$VENV/bin/mkdocs" serve
fi
"$VENV/bin/mkdocs" build --strict
echo "Documentation written to site/index.html"
