"""Enforcement de R-13: ``app/ml`` no puede importar frameworks HTTP/BD.

El test escanea **todos** los ficheros ``.py`` bajo ``app/ml`` con ``ast`` y
falla si aparece un import prohibido, ya sea absoluto o relativo.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

#: Raiz del paquete ``app``.
PACKAGE_ROOT = Path(__file__).resolve().parents[1]

#: Modulos que ``app/ml`` tiene prohibido importar (R-13).
FORBIDDEN_ROOTS: frozenset[str] = frozenset(
    {
        "fastapi",
        "starlette",
        "pydantic",
        "pydantic_settings",
        "sqlalchemy",
        "alembic",
        "celery",
        "django",
        "httpx",
        "requests",
        "aiohttp",
        "urllib3",
        "flask",
        "bottle",
        "tornado",
        "uvicorn",
        "django_rest_framework",
        "psycopg2",
        "psycopg",
        "asyncpg",
        "sqlmodel",
        "tortoise",
        "peewee",
        "databases",
        "redis",
        "boto3",
    }
)


def _iter_ml_files() -> list[Path]:
    """Devuelve todos los ficheros Python bajo ``app/ml``."""
    ml_root = PACKAGE_ROOT / "app" / "ml"
    assert ml_root.is_dir(), f"No existe el paquete puro: {ml_root}"
    return sorted(p for p in ml_root.rglob("*.py"))


def _imported_roots(tree: ast.AST) -> set[str]:
    """Extrae los nombres raiz de todos los ``import`` del arbol.

    Cubre ``import x``, ``import x.y``, ``from x import ...`` y
    ``from . import x`` (los relativos se resuelven como ``app.ml``).
    """
    roots: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                roots.add(alias.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            if node.level and node.level > 0:
                # Import relativo dentro de app.ml: permitted root.
                roots.add("app")
                continue
            if node.module:
                roots.add(node.module.split(".")[0])
    return roots


def test_ml_package_is_not_empty() -> None:
    """El paquete ``app/ml`` debe tener ficheros que escanear."""
    files = _iter_ml_files()
    assert len(files) >= 10, f"Se esperaban al menos 10 ficheros en app/ml, hay {len(files)}"


@pytest.mark.parametrize("path", _iter_ml_files(), ids=lambda p: p.name)
def test_no_forbidden_imports(path: Path) -> None:
    """Ningun fichero de ``app/ml`` importa un framework HTTP/BD (R-13)."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    offending = _imported_roots(tree) & FORBIDDEN_ROOTS
    assert not offending, (
        f"{path.relative_to(PACKAGE_ROOT)} importa {sorted(offending)}; "
        "app/ml debe permanecer puro (R-13)"
    )


@pytest.mark.parametrize("path", _iter_ml_files(), ids=lambda p: p.name)
def test_allowed_third_party_imports(path: Path) -> None:
    """``app/ml`` solo usa las librerias de ML y de la stdlib."""
    allowed = frozenset(
        {
            "app",
            "numpy",
            "np",
            "pandas",
            "pd",
            "sklearn",
            "statsmodels",
            "tensorflow",
            "keras",
            "joblib",
            "yaml",
            "scipy",
            "hashlib",
            "json",
            "datetime",
            "pathlib",
            "dataclasses",
            "typing",
            "abc",
            "collections",
            "functools",
            "warnings",
            "importlib",
            "os",
            "sys",
            "io",
            "math",
            "uuid",
            "logging",
            "random",
            "__future__",
            "itertools",
            "contextlib",
            "re",
        }
    )
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    unexpected = _imported_roots(tree) - allowed
    assert not unexpected, (
        f"{path.relative_to(PACKAGE_ROOT)} importa {sorted(unexpected)}; "
        "solo se permiten numpy/pandas/sklearn/statsmodels/tensorflow/joblib/yaml y la stdlib"
    )


def test_ml_package_imports_without_web_stack() -> None:
    """``app.ml`` debe ser importable sin tener la capa web cargada (R-13)."""
    import subprocess
    import sys

    script = (
        "import sys\n"
        "for blocked in ('fastapi', 'starlette', 'pydantic', 'sqlalchemy'):\n"
        "    sys.modules[blocked] = None\n"
        "import app.ml.data.features\n"
        "import app.ml.data.split\n"
        "import app.ml.data.ingest\n"
        "import app.ml.data.cleaning\n"
        "import app.ml.models.baselines\n"
        "import app.ml.models.arima\n"
        "import app.ml.models.registry\n"
        "import app.ml.evaluation.metrics\n"
        "import app.ml.evaluation.compare\n"
        "import app.ml.evaluation.error_analysis\n"
        "import app.ml.pipelines.train\n"
        "import app.ml.pipelines.inference\n"
        "print('PURE_OK')\n"
    )
    result = subprocess.run(
        [sys.executable, "-c", script], capture_output=True, text=True, cwd=str(PACKAGE_ROOT)
    )
    assert result.returncode == 0, f"Importacion pura fallo: {result.stderr}"
    assert "PURE_OK" in result.stdout


def test_recurrent_module_imports_without_tensorflow() -> None:
    """``recurrent`` debe importarse aunque TensorFlow no este instalado."""
    import app.ml.models.recurrent as recurrent

    assert recurrent.TensorFlowMissingError is not None
    # El error debe ser explicito y accionable cuando TF falta.
    if not recurrent.tensorflow_available():
        with pytest.raises(recurrent.TensorFlowMissingError) as excinfo:
            recurrent.LSTMRegressor().build(n_features=3)
        assert "tensorflow" in str(excinfo.value).lower()
