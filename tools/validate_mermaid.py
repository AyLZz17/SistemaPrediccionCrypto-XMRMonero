#!/usr/bin/env python3
"""Validador de diagramas Mermaid de XMR-Forecast.

R-25 exige que todo diagrama Mermaid se valide ANTES de entregarlo, y no solo
que parsee: tambien que se renderice. Este extractor llama a un runner de Node
que ejecuta las dos fases y devuelve el resultado por bloque.

Uso:
    python tools/validate_mermaid.py docs/04_diagramas.md
    python tools/validate_mermaid.py docs/04_diagramas.md tools/.mermaid-runner/node_modules/mermaid/dist/mermaid.min.js

Codigo de salida:
    0  todos los diagramas parsean y renderizan
    1  algun diagrama fallo (imprime el detalle de cada fallo)
    2  error de uso o dependencia ausente
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

# Bloque de codigo mermaid, con o sin atributos de lenguaje adicionales.
BLOCK_RE = re.compile(r"```mermaid[^\n]*\n(.*?)```", re.DOTALL)

VALID_DIAGRAM_KINDS = (
    "flowchart",
    "graph",
    "sequenceDiagram",
    "classDiagram",
    "stateDiagram",
    "stateDiagram-v2",
    "erDiagram",
    "journey",
    "gantt",
    "pie",
    "C4Context",
)


def extract_blocks(markdown: str) -> list[dict]:
    """Extrae cada bloque mermaid junto con el titulo de su seccion."""
    blocks: list[dict] = []
    # Titulo mas cercano por encima, para localizar el fallo en el informe.
    headings = [
        (m.start(), m.group(0).strip()) for m in re.finditer(r"^#{1,6} .+$", markdown, re.MULTILINE)
    ]

    for i, match in enumerate(BLOCK_RE.finditer(markdown), start=1):
        code = match.group(1)
        heading = "(sin titulo)"
        for pos, title in headings:
            if pos < match.start():
                heading = title
            else:
                break

        kind = "desconocido"
        first = next((line.strip() for line in code.splitlines() if line.strip()), "")
        for candidate in VALID_DIAGRAM_KINDS:
            if first.startswith(candidate):
                kind = candidate
                break

        blocks.append({"index": i, "code": code, "kind": kind, "heading": heading})
    return blocks


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2

    target = Path(argv[1])
    if not target.is_file():
        print(f"ERROR: no existe el fichero {target}")
        return 2

    runner = Path(__file__).resolve().parent / ".mermaid-runner" / "validate.js"
    if not runner.is_file():
        print(f"ERROR: falta el runner {runner}")
        print("       ejecuta: npm install --prefix tools/.mermaid-runner mermaid jsdom")
        return 2

    node = shutil.which("node")
    if node is None:
        print("ERROR: node no esta disponible en el PATH")
        return 2

    blocks = extract_blocks(target.read_text(encoding="utf-8"))
    if not blocks:
        print(f"No se encontraron bloques mermaid en {target}")
        return 0

    print(f"Validando {len(blocks)} diagrama(s) de {target}...\n")

    try:
        proc = subprocess.run(
            [node, str(runner)],
            input=json.dumps({"blocks": blocks}),
            capture_output=True,
            text=True,
            timeout=180,
        )
    except subprocess.TimeoutExpired:
        print("ERROR: el runner de Mermaid tardo demasiado")
        return 2

    raw = (proc.stdout or "").strip()
    if not raw:
        print("ERROR: el runner no devolvio resultado")
        print((proc.stderr or "")[:800])
        return 2

    try:
        results = json.loads(raw)
    except json.JSONDecodeError:
        print("ERROR: la salida del runner no es JSON valido")
        print(raw[:500])
        return 2

    by_index = {entry["index"]: entry for entry in results}
    failures = 0

    for block in blocks:
        entry = by_index.get(block["index"], {})
        parse_ok = entry.get("parseOk", False)
        render_ok = entry.get("renderOk", False)
        ok = parse_ok and render_ok

        if ok:
            print(f"  [OK]   #{block['index']:<2} {block['kind']:<18} {block['heading'][:64]}")
        else:
            failures += 1
            state = "parse OK, render FALLA" if parse_ok else "PARSE FALLA"
            print(f"  [FALLO] #{block['index']:<2} {block['kind']:<18} {block['heading'][:64]}")
            print(f"          {state}")
            if entry.get("error"):
                print(f"          {entry['error'][:300]}")

    print()
    if failures:
        print(f"RESULTADO: {failures} de {len(blocks)} diagrama(s) con error. (R-25 incumplido)")
        return 1

    print(f"RESULTADO: {len(blocks)} diagrama(s) validan (parse + render). (R-25 cumplido)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))