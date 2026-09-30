# MEMORY.md — Memoria a Corto Plazo

## Tarea Actual
Diagramas UML en formato drawio — COMPLETADA.

## Contexto
- Proyecto integra retail (ventas) y criptomonedas (Monero/XMR)
- Ambos usan LSTM/GRU para predicción de series de tiempo
- Documentos fuente: Fase1_Proyecto7.docx y Propuesta_Monero_IEEE.docx

## Archivos Creados
- AGENTS.md, SKILLS.md, MEMORY.md
- docs/01-07 (7 documentos de documentación)
- docs/diagrams/01-15 (15 diagramas UML en formato drawio)
- docker-compose.yml, .env.example, .gitignore, README.md
- backend/: FastAPI con routers (auth, datasets, models, predictions, metrics)
- frontend/: React + TypeScript + Tailwind CSS con páginas principales
- Estructura de carpetas completa con .gitkeep

## Diagramos drawio Creados
1. Diagrama_Clases.drawio - Clases del dominio ML
2. Diagrama_Secuencia.drawio - Secuencia predicción
3. Diagrama_Actividad_Entrenamiento.drawio - Actividad entrenamiento
4. Diagrama_Casos_de_Uso.drawio - Casos de uso
5. Diagrama_Componentes.drawio - Componentes del sistema
6. Diagrama_Despliegue.drawio - Despliegue
7. Diagrama_ER_BaseDatos.drawio - ER base de datos
8. Diagrama_Actividad_Prediccion.drawio - Actividad predicción
9. Diagrama_Secuencia_Entrenamiento.drawio - Secuencia entrenamiento
10. Diagrama_Estados_Modelo.drawio - Estados del modelo
11. Diagrama_Paquetes.drawio - Paquetes
12. Diagrama_Despliegue.drawio - Despliegue (detallado)
13. Diagrama_Componentes.drawio - Componentes (detallado)
14. Diagrama_Comunicacion.drawio - Comunicación predicción
15. Diagrama_Tiempos.drawio - Tiempos entrenamiento

## Pendiente
- Inicializar repositorio Git
- Crear archivos de prueba (tests/)
- Implementar ML pipeline (src/data/, src/models/, src/evaluation/)
- Conectar frontend con backend real
- Implementar Celery tasks para entrenamiento async

## Decisiones Tomadas
- Stack: FastAPI + React + PostgreSQL + TensorFlow
- Partición temporal obligatoria (no aleatoria)
- Métricas: MAE, RMSE, MAPE
- Modelos base: media móvil, ARIMA, regresión lineal
- Ventana deslizante: 30 días (Monero), 14 días (Retail)

## Notas
- Proyecto académico, no herramienta de inversión
- Monero es el foco principal (poco estudiado en literatura)
- Hiperparámetros base: 2 capas x 100 unidades, dropout 0.2, Adam, batch 32, 20 épocas
