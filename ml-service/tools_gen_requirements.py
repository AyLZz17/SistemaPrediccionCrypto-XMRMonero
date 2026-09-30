"""Genera requirements.txt y requirements-dev.txt desde el entorno real.

R-17: las versiones se **verifican** con pip, nunca se suponen. Este script
es la fuente de verdad; el fichero generado es un artefacto.
"""

import subprocess
import sys

#: Dependencias directas declaradas en pyproject.toml.
DIRECT = {
    # runtime
    "fastapi",
    "uvicorn",
    "starlette",
    "pydantic",
    "pydantic_core",
    "pydantic-settings",
    "python-dotenv",
    "python-multipart",
    "typing_extensions",
    "numpy",
    "pandas",
    "scikit-learn",
    "statsmodels",
    "joblib",
    "pyyaml",
    "httpx",
    "mlflow-skinny",
    # dev
    "pytest",
    "pytest-cov",
    "ruff",
    "mypy",
    "types-pyyaml",
}

DEV_ONLY = {
    "pytest",
    "pytest-cov",
    "ruff",
    "mypy",
    "mypy_extensions",
    "types-pyyaml",
    "coverage",
    "iniconfig",
    "pluggy",
    "colorama",
    "librt",
    "ast-serialize",
}


def freeze() -> list[tuple[str, str]]:
    """Devuelve la lista ``(nombre, version)`` instalada."""
    out = subprocess.run(
        [sys.executable, "-m", "pip", "list", "--format=freeze"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    pairs = []
    for line in out.splitlines():
        if "==" in line:
            name, version = line.split("==", 1)
            pairs.append((name.strip(), version.strip()))
    return sorted(pairs, key=lambda p: p[0].lower())


def main() -> None:
    """Escribe ambos ficheros de requisitos."""
    pairs = freeze()
    with open("requirements.txt", "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# ml-service — versiones EXACTAMENTE instaladas (R-17).\n")
        handle.write(
            "# Generado con `python tools_gen_requirements.py` (pip list --format=freeze).\n"
        )
        handle.write(f"# Python real: {sys.version.split()[0]} (spec 3.11; target >=3.11).\n")
        handle.write("# TensorFlow NO se incluye: es extra opcional (`pip install .[tf]`).\n\n")
        for name, version in pairs:
            if name.lower() in DEV_ONLY or name.lower() not in DIRECT:
                continue
            handle.write(f"{name}=={version}\n")

    with open("requirements-dev.txt", "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# ml-service — herramientas de calidad (R-16, R-17).\n")
        handle.write("# Generado con `python tools_gen_requirements.py`.\n\n")
        for name, version in pairs:
            if name.lower() in DEV_ONLY:
                handle.write(f"{name}=={version}\n")

    print(f"requirements.txt / requirements-dev.txt escritos ({len(pairs)} paquetes instalados)")


if __name__ == "__main__":
    main()
