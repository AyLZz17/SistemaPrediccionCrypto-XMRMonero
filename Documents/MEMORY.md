# MEMORY.md — Memoria de corto plazo (máx. 50 líneas)
> Se reescribe tras cada tarea. Lo crítico y permanente se promueve a AGENTS.md.

## Contexto
- Proyecto: XMR-Forecast. Aplicación web comercial para predicción de precio de Monero (XMR).
- Modelos: LSTM/GRU vs. MA, regresión lineal y ARIMA. Métricas: MAE, RMSE, MAPE.
- Producto SaaS para análisis predictivo de criptomonedas. NO es asesoría financiera.

## Estado actual
- Entregado: AGENTS, SKILLS, MEMORY, docs 01-05 (documentación completa adaptada para cliente real).
- Frontend: Estructura base creada con React + TypeScript + Tailwind CSS.
- HTTPS: Configurado en vite.config.ts (requiere certs/key.pem y certs/cert.pem).
- HTTP Codes: Página de referencia de códigos HTTP implementada.
- Git: Repositorio inicializado, primer commit realizado y push a origin/master.
- Siguiente paso: Instalar dependencias (npm install) y generar certificados SSL.

## Decisiones vigentes (detalle en AGENTS.md §9)
- D-00: dominio activo = Monero; retail queda como alternativa (interfaz DataSource).
- D-04: dos tareas: R (regresión) y D (dirección).
- Stack: FastAPI + PostgreSQL + Celery/Redis + TensorFlow/Keras + MLflow + Optuna + React/TS + ECharts.
- Actores: viewer, analyst, admin, planificador (Celery Beat), fuente externa.
- 15 tablas PostgreSQL (doc 01 §7, ER en doc 04 §8); API /api/v1 (doc 01 §8); 7 servicios Compose.
- Split 70/15/15 (propuesta); W=30; LSTM 2x100, dropout 0.2, Adam, lote 32, 20 épocas.
- Versiones de paquetes NO fijadas en docs: verificar al crear el entorno (R-17).

## Recordatorios operativos
- R-23 partición por fecha del objetivo · R-24 campeón por validación, ARIMA rodante 1 paso.
- R-25 validar Mermaid antes de entregar.
- R-26…R-30: trazabilidad de seguridad, secretos/mínimo privilegio, procedencia ML, gates CI y runbook de incidentes.
- Tras cada tarea: MEMORY (≤ 50 líneas) + AGENTS §10.

## Tareas recientes
- T-009 adaptación de documentación para cliente real (eliminar enfoque académico).
- T-010 frontend base creado (React + TS + Tailwind) con HTTPS forzado.
- T-011 página de códigos HTTP implementada.
- T-012 primer commit y push a origin/master.
