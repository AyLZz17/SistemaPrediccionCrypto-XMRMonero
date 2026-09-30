# XMR-Forecast — Diagramas

> Todos los diagramas están en **Mermaid** y se renderizan en GitHub, GitLab, VS Code (extensión Mermaid), Obsidian y el editor de [mermaid.live](https://mermaid.live).
> **Aviso legal:** análisis predictivo; no es asesoría financiera ni promete rentabilidad.

---

## 1. Arquitectura del sistema

```mermaid
flowchart LR
    subgraph CLIENT["Cliente"]
        SPA["React SPA<br/>TypeScript + Vite"]
    end

    subgraph BACKEND["Spring Boot Backend (Java 21)"]
        API["API REST<br/>/api/v1"]
        AUTH["Auth Module<br/>JWT + RBAC"]
        MLINT["ML Integration<br/>WebClient"]
        subgraph MODULES["Módulos de dominio"]
            USERS["users"]
            MARKET["market"]
            EXPERIMENTS["experiments"]
            PREDICTIONS["predictions"]
            AUDIT["audit"]
        end
    end

    subgraph ML["FastAPI-ML Service (Python 3.11)"]
        MLAPI["API ML<br/>/api/v1/ml"]
        MLCORE["ML Core<br/>sin dependencias web"]
    end

    subgraph STORE["Almacenamiento"]
        PG[("PostgreSQL")]
        RD[("Redis")]
        MLF["MLflow"]
    end

    EXT["Fuente externa<br/>Yahoo Finance"]

    SPA -->|"HTTPS JSON"| API
    API --> AUTH
    API --> MODULES
    API --> MLINT
    MLINT -->|"HTTPS/TLS interno"| MLAPI
    MLAPI --> MLCORE
    MODULES --> PG
    MODULES --> RD
    MLCORE --> MLF
    MARKET -->|"HTTPS"| EXT
```

---

## 2. Flujo de autenticación

```mermaid
sequenceDiagram
    actor U as Usuario
    participant SPA as Frontend
    participant API as Spring Boot
    participant DB as PostgreSQL

    U->>SPA: Ingresa credenciales
    SPA->>API: POST /auth/login (HTTPS)
    API->>DB: Busca usuario por email
    DB-->>API: Usuario encontrado
    API->>API: Verifica contraseña (BCrypt)
    API->>API: Genera JWT (10 min)
    API-->>SPA: 200 OK + JWT
    SPA->>SPA: Guarda token en localStorage
    SPA->>API: Request con Authorization: Bearer
    API->>API: Valida JWT y rol
    API-->>SPA: 200 OK + datos
```

---

## 3. Flujo de predicción

```mermaid
sequenceDiagram
    actor U as Usuario
    participant SPA as Frontend
    participant API as Spring Boot
    participant ML as FastAPI-ML
    participant DB as PostgreSQL

    U->>SPA: Abre dashboard
    SPA->>API: GET /predictions/next (HTTPS)
    API->>API: Valida JWT y rol
    API->>ML: POST /api/v1/ml/infer (HTTPS/TLS)
    ML->>ML: Carga modelo verificado
    ML->>ML: Ejecuta inferencia
    ML-->>API: Predicción + confianza
    API->>DB: Guarda predicción (is_live=true)
    API-->>SPA: 200 OK + predicción + disclaimer
    SPA-->>U: Muestra tarjeta de predicción
```

---

## 4. Flujo de entrenamiento

```mermaid
sequenceDiagram
    actor A as Analista
    participant SPA as Frontend
    participant API as Spring Boot
    participant ML as FastAPI-ML
    participant DB as PostgreSQL
    participant MLF as MLflow

    A->>SPA: Configura experimento
    SPA->>API: POST /experiments (HTTPS)
    API->>API: Valida rol analyst+
    API->>DB: Guarda experimento (PENDING)
    API->>ML: POST /api/v1/ml/train (HTTPS/TLS)
    API-->>SPA: 202 Accepted + job_id
    ML->>ML: Ejecuta entrenamiento
    ML->>MLF: Registra parámetros y métricas
    ML->>DB: Guarda training_run (COMPLETED)
    ML-->>API: Resultado
    API-->>SPA: Estado actualizado
```

---

## 5. Flujo HTTPS

```mermaid
flowchart TD
    A[Cliente] -->|"HTTP :8080"| B[Spring Boot]
    B -->|"Redirige a HTTPS"| C[HTTPS :8443]
    C -->|"Procesa solicitud"| D[Respuesta]
    
    E[Frontend] -->|"HTTPS :3000"| F[Spring Boot]
    F -->|"HTTPS/TLS interno"| G[FastAPI-ML]
    G -->|"HTTPS/TLS interno"| H[MLflow]
    
    F -->|"TLS"| I[PostgreSQL]
    F -->|"TLS"| J[Redis]
```

---

## 6. Flujo de errores e indisponibilidad

```mermaid
flowchart TD
    A[Solicitud] --> B{¿Servicio disponible?}
    B -->|Sí| C[Procesar]
    B -->|No| D[Timeout]
    D --> E{¿Reintentos agotados?}
    E -->|No| F[Reintentar con backoff]
    F --> B
    E -->|Sí| G[Error 503]
    G --> H[Respuesta de error segura]
    
    C --> I{¿Éxito?}
    I -->|Sí| J[200 OK]
    I -->|No| K[Error 4xx/5xx]
    K --> H
```

---

## 7. Modelo entidad–relación

```mermaid
erDiagram
    APP_USER ||--o{ AUDIT_LOG : genera
    APP_USER ||--o{ EXPERIMENT : crea
    DATA_SOURCE ||--o{ OHLCV_DAILY : provee
    ASSET ||--o{ OHLCV_DAILY : tiene
    DATA_SOURCE ||--o{ INGESTION_LOG : registra
    ASSET ||--o{ DATASET_VERSION : agrupa
    DATA_SOURCE ||--o{ DATASET_VERSION : origina
    DATASET_VERSION ||--o{ DATA_SPLIT : "se particiona en"
    DATASET_VERSION ||--o{ EXPERIMENT : "es usado por"
    DATA_SPLIT ||--o{ EXPERIMENT : "es usado por"
    FEATURE_SET ||--o{ EXPERIMENT : "es usado por"
    EXPERIMENT ||--o{ TRAINING_RUN : contiene
    MODEL_DEFINITION ||--o{ TRAINING_RUN : instancia
    TRAINING_RUN ||--o{ EVALUATION_METRIC : produce
    TRAINING_RUN ||--o{ PREDICTION : genera
    TRAINING_RUN ||--o{ FAILURE_PERIOD : presenta

    APP_USER {
        bigint id PK
        varchar email UK
        varchar password_hash
        varchar role "viewer, analyst o admin"
        boolean is_active
        timestamptz created_at
    }
    AUDIT_LOG {
        bigint id PK
        bigint user_id FK
        varchar role
        varchar action
        varchar entity
        bigint entity_id
        varchar result
        varchar request_id
        jsonb metadata "redactada"
        timestamptz created_at
    }
    DATA_SOURCE {
        bigint id PK
        varchar name UK
        varchar type
        varchar base_url
        boolean is_active
    }
    ASSET {
        bigint id PK
        varchar symbol UK
        varchar name
        varchar quote_currency
    }
    OHLCV_DAILY {
        bigint id PK
        bigint asset_id FK
        bigint source_id FK
        date trade_date "UNIQUE con asset_id y source_id"
        numeric open
        numeric high
        numeric low
        numeric close
        numeric volume
        timestamptz ingested_at
    }
    INGESTION_LOG {
        bigint id PK
        bigint source_id FK
        timestamptz started_at
        timestamptz finished_at
        int rows_inserted
        varchar status
        text error
    }
    DATASET_VERSION {
        bigint id PK
        bigint asset_id FK
        bigint source_id FK
        date start_date
        date end_date
        int n_rows
        varchar checksum
        varchar snapshot_path
        timestamptz created_at
    }
    DATA_SPLIT {
        bigint id PK
        bigint dataset_version_id FK
        date train_start
        date train_end
        date val_start
        date val_end
        date test_start
        date test_end
        varchar strategy
    }
    FEATURE_SET {
        bigint id PK
        varchar name UK
        jsonb features
        int window_size
    }
    MODEL_DEFINITION {
        bigint id PK
        varchar name UK
        varchar family "baseline_stat o recurrent"
        varchar algorithm
        jsonb default_params
    }
    EXPERIMENT {
        bigint id PK
        varchar name
        bigint dataset_version_id FK
        bigint split_id FK
        bigint feature_set_id FK
        bigint created_by FK
        varchar task_type "regression o direction"
        int seed
        varchar status
        timestamptz created_at
    }
    TRAINING_RUN {
        bigint id PK
        bigint experiment_id FK
        bigint model_definition_id FK
        jsonb hyperparams
        int seed
        varchar status
        int best_epoch
        float train_time_s
        varchar artifact_path
        varchar mlflow_run_id
        boolean is_champion
    }
    EVALUATION_METRIC {
        bigint id PK
        bigint training_run_id FK
        varchar subset "val o test"
        varchar metric_name
        float value
    }
    PREDICTION {
        bigint id PK
        bigint training_run_id FK
        date target_date
        numeric y_true
        numeric y_pred
        smallint direction_true
        smallint direction_pred
        varchar subset
        boolean is_live
    }
    FAILURE_PERIOD {
        bigint id PK
        bigint training_run_id FK
        date period_start
        date period_end
        varchar volatility_regime
        float error_value
        text notes
    }
```

---

## 8. Diagramas de estados

### 8.1 Ciclo de vida de un experimento

```mermaid
stateDiagram-v2
    [*] --> PENDING : experimento creado
    PENDING --> RUNNING : se inicia entrenamiento
    PENDING --> CANCELLED : cancelado
    RUNNING --> COMPLETED : evaluación guardada
    RUNNING --> FAILED : error en ejecución
    RUNNING --> CANCELLED : cancelado
    FAILED --> PENDING : reintento manual
    COMPLETED --> [*]
    CANCELLED --> [*]
```

### 8.2 Ciclo de vida de una predicción

```mermaid
stateDiagram-v2
    [*] --> GENERATED : predicción t+1 guardada
    GENERATED --> AWAITING_TRUTH : fecha objetivo futura
    AWAITING_TRUTH --> EVALUATED : llega el dato real
    EVALUATED --> [*]
```
