#!/usr/bin/env python
"""
Guardian de la regla de dependencia, en el momento de escribir.

El `ArchitectureTest` ya protege el repositorio, pero solo cuando alguien ejecuta los tests.
Este hook actua antes: si una escritura mete un framework dentro de `core/`, la bloquea y
explica por que, en el mismo instante en que se intenta.

Se ejecuta como hook PostToolUse sobre Write|Edit. Salir con codigo 2 bloquea la accion.
"""
import json
import re
import sys

FORBIDDEN = (
    "androidx.",
    "android.",
    "platform.",
    "java.",
    "javax.",
    "kotlinx.coroutines.android",
    "org.jetbrains.skia",
)

TODO_WITHOUT_CARD = re.compile(r"(TODO|FIXME)\b(?![^\n]*PCA-\d+)")
PIXEL_HINT = re.compile(r"\b\w*(pixel|Pixels|widthPx|heightPx)\w*\b")


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0  # sin payload no hay nada que juzgar; no se estorba al usuario

    path = (payload.get("tool_input") or {}).get("file_path") or ""
    normalized = path.replace("\\", "/")

    if "core/src/commonMain" not in normalized or not normalized.endswith(".kt"):
        return 0

    try:
        with open(path, encoding="utf-8") as handle:
            lines = handle.readlines()
    except OSError:
        return 0

    problems = []
    for number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if stripped.startswith("import "):
            imported = stripped[len("import "):].split(" as ")[0].strip()
            for prefix in FORBIDDEN:
                if imported.startswith(prefix):
                    problems.append(
                        f"linea {number}: importa '{imported}'. El nucleo es Kotlin puro; "
                        f"lo que necesite plataforma va detras de un puerto (ADR-002)."
                    )
        if TODO_WITHOUT_CARD.search(line):
            problems.append(
                f"linea {number}: TODO sin id de tarjeta. Usa 'TODO(PCA-42): ...' para que "
                f"la deuda sea priorizable."
            )

    if not problems:
        return 0

    print(
        "Bloqueado por la regla de dependencia de PROPOR:\n  - "
        + "\n  - ".join(problems),
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
