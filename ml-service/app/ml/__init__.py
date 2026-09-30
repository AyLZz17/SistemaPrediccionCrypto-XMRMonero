"""Paquete ML **puro** de XMR-Forecast (regla dura R-13).

Este paquete **no** puede importar ``fastapi``, ``starlette``, ``pydantic``,
``sqlalchemy`` ni ningun framework HTTP/BD. Debe ser importable y testeable desde
un REPL, un CLI o un notebook sin instalar la capa web.

Dependencias admitidas: ``numpy``, ``pandas``, ``scikit-learn``, ``statsmodels``,
``tensorflow`` (opcional), ``joblib``, ``yaml``, ``hashlib``, ``json``.

La restriccion se verifica automaticamente en
``tests/test_no_framework_imports.py`` (escaneo AST de todos los ficheros).
"""

__all__: list[str] = []
