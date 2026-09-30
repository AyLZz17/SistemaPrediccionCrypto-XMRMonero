const httpCodes = [
  { code: 200, name: 'OK', description: 'La solicitud se ha completado correctamente.', category: 'success' },
  { code: 201, name: 'Created', description: 'Se ha creado un nuevo recurso correctamente.', category: 'success' },
  { code: 202, name: 'Accepted', description: 'La solicitud se ha aceptado para procesamiento asíncrono.', category: 'success' },
  { code: 204, name: 'No Content', description: 'La solicitud se completó pero no hay contenido para devolver.', category: 'success' },
  { code: 301, name: 'Moved Permanently', description: 'El recurso se ha movido permanentemente a una nueva URL.', category: 'redirect' },
  { code: 302, name: 'Found', description: 'El recurso se encuentra temporalmente en otra URL.', category: 'redirect' },
  { code: 304, name: 'Not Modified', description: 'El recurso no ha sido modificado desde la última solicitud.', category: 'redirect' },
  { code: 400, name: 'Bad Request', description: 'La solicitud es incorrecta o mal formada.', category: 'client' },
  { code: 401, name: 'Unauthorized', description: 'Se requiere autenticación para acceder al recurso.', category: 'client' },
  { code: 403, name: 'Forbidden', description: 'No tienes permisos para acceder al recurso.', category: 'client' },
  { code: 404, name: 'Not Found', description: 'El recurso solicitado no existe.', category: 'client' },
  { code: 405, name: 'Method Not Allowed', description: 'El método HTTP no está permitido para este recurso.', category: 'client' },
  { code: 409, name: 'Conflict', description: 'Conflicto con el estado actual del recurso.', category: 'client' },
  { code: 422, name: 'Unprocessable Entity', description: 'La solicitud es válida pero contiene errores semánticos.', category: 'client' },
  { code: 429, name: 'Too Many Requests', description: 'Se ha excedido el límite de solicitudes.', category: 'client' },
  { code: 500, name: 'Internal Server Error', description: 'Error interno del servidor.', category: 'server' },
  { code: 502, name: 'Bad Gateway', description: 'Respuesta inválida de un servidor upstream.', category: 'server' },
  { code: 503, name: 'Service Unavailable', description: 'El servidor no está disponible temporalmente.', category: 'server' },
  { code: 504, name: 'Gateway Timeout', description: 'Tiempo de espera agotado de un servidor upstream.', category: 'server' },
]

const categoryColors = {
  success: 'bg-green-100 text-green-800 border-green-200',
  redirect: 'bg-blue-100 text-blue-800 border-blue-200',
  client: 'bg-yellow-100 text-yellow-800 border-yellow-200',
  server: 'bg-red-100 text-red-800 border-red-200',
}

const categoryLabels = {
  success: 'Éxito (2xx)',
  redirect: 'Redirección (3xx)',
  client: 'Error del cliente (4xx)',
  server: 'Error del servidor (5xx)',
}

export default function HttpCodesPage() {
  const categories = ['success', 'redirect', 'client', 'server'] as const

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-800">Códigos de Respuesta HTTP</h2>
      <p className="text-gray-600">
        Referencia de códigos de respuesta HTTP utilizados en la API de XMR-Forecast.
      </p>

      {categories.map((category) => (
        <div key={category} className="space-y-3">
          <h3 className="text-lg font-semibold text-gray-800">
            {categoryLabels[category]}
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {httpCodes
              .filter((c) => c.category === category)
              .map((c) => (
                <div
                  key={c.code}
                  className={`p-4 rounded-lg border ${categoryColors[category]}`}
                >
                  <div className="flex items-center gap-3 mb-2">
                    <span className="text-2xl font-bold">{c.code}</span>
                    <span className="font-medium">{c.name}</span>
                  </div>
                  <p className="text-sm opacity-80">{c.description}</p>
                </div>
              ))}
          </div>
        </div>
      ))}
    </div>
  )
}
