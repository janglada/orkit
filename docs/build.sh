#!/usr/bin/env bash
# Builds the documentation site into site/: Javadoc for the public API plus the MkDocs guide.
# Usage: docs/build.sh [serve]     (run from anywhere; needs Maven, a JDK and Python 3)
set -euo pipefail
cd "$(dirname "$0")/.."

VENV="${DOCS_VENV:-.venv-docs}"
if [ ! -x "$VENV/bin/mkdocs" ]; then
    # --clear rebuilds an environment left half-made by an earlier failed run.
    if command -v uv >/dev/null 2>&1; then
        uv venv --quiet --clear "$VENV"
        uv pip install --quiet --python "$VENV/bin/python" -r docs/requirements.txt
    elif python3 -m venv --clear "$VENV" 2>/dev/null; then
        "$VENV/bin/pip" install --quiet -r docs/requirements.txt
    else
        echo "Cannot create a Python virtual environment in $VENV." >&2
        echo "Install uv (https://docs.astral.sh/uv/) or your distribution's python3-venv package," >&2
        echo "or point DOCS_VENV at an environment that already has mkdocs-material." >&2
        exit 1
    fi
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
