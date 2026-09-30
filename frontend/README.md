# XMR-Forecast Frontend

Frontend de la aplicación web XMR-Forecast para predicción de precio de Monero (XMR).

## Stack Tecnológico

- **React 18** + **TypeScript** (strict)
- **Vite** - Build tool
- **Tailwind CSS** - Estilos
- **Recharts** - Gráficos
- **TanStack Query** - Estado del servidor
- **Zustand** - Estado global
- **React Router** - Enrutamiento
- **Axios** - Cliente HTTP

## Inicio Rápido

```bash
# Instalar dependencias
npm install

# Copiar variables de entorno
cp .env.example .env

# Iniciar servidor de desarrollo (HTTPS)
npm run dev
```

## HTTPS

El frontend usa HTTPS forzado. Para desarrollo, se requieren certificados SSL en la carpeta `certs/`:

```
certs/
├── key.pem
└── cert.pem
```

Para producción, usar un proxy inverso (Nginx) con certificados válidos.

## Estructura

```
src/
├── components/     # Componentes reutilizables
│   ├── common/     # Button, Card, Input, Modal, Spinner, Table
│   ├── layout/     # Layout, Header, Sidebar
│   ├── charts/     # LineChart, CandlestickChart, ComparisonChart, MetricsChart
│   └── forms/      # LoginForm, DatasetForm, ModelConfigForm, PredictionForm
├── pages/          # Páginas de la aplicación
├── hooks/          # Custom hooks
├── services/       # Servicios API
├── store/          # Estado global (Zustand)
├── types/          # Tipos TypeScript
└── utils/          # Utilidades
```

## Scripts

- `npm run dev` - Servidor de desarrollo con HTTPS
- `npm run build` - Build de producción
- `npm run preview` - Preview del build
- `npm run lint` - Linter

## Seguridad

- HTTPS forzado en desarrollo y producción
- JWT almacenado en memoria (no en localStorage)
- Interceptores para manejo de errores 401
- CORS configurado en el backend
