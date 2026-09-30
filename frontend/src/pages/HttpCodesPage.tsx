import { useState } from 'react'

interface HttpCode {
  code: number
  name: string
  description: string
  category: 'success' | 'client-error' | 'server-error' | 'redirect' | 'informational'
}

const httpCodes: HttpCode[] = [
  { code: 200, name: 'OK', description: 'La solicitud se ha completado correctamente.', category: 'success' },
  { code: 201, name: 'Created', description: 'El recurso se ha creado correctamente.', category: 'success' },
  { code: 202, name: 'Accepted', description: 'La solicitud se ha aceptado para procesamiento asíncrono.', category: 'success' },
  { code: 204, name: 'No Content', description: 'La solicitud se completó pero no hay contenido para devolver.', category: 'success' },
  { code: 301, name: 'Moved Permanently', description: 'El recurso se ha movido permanentemente a una nueva URL.', category: 'redirect' },
  { code: 302, name: 'Found', description: 'El recurso se encuentra temporalmente en otra URL.', category: 'redirect' },
  { code: 304, name: 'Not Modified', description: 'El recurso no ha sido modificado desde la última solicitud.', category: 'redirect' },
  { code: 400, name: 'Bad Request', description: 'La solicitud es incorrecta o malformada.', category: 'client-error' },
  { code: 401, name: 'Unauthorized', description: 'Se requiere autenticación para acceder al recurso.', category: 'client-error' },
  { code: 403, name: 'Forbidden', description: 'No tienes permiso para acceder al recurso.', category: 'client-error' },
  { code: 404, name: 'Not Found', description: 'El recurso solicitado no existe.', category: 'client-error' },
  { code: 409, name: 'Conflict', description: 'Conflicto con el estado actual del recurso.', category: 'client-error' },
  { code: 422, name: 'Unprocessable Entity', description: 'La solicitud es válida pero contiene errores de validación.', category: 'client-error' },
  { code: 429, name: 'Too Many Requests', description: 'Has excedido el límite de solicitudes permitidas.', category: 'client-error' },
  { code: 500, name: 'Internal Server Error', description: 'Error interno del servidor.', category: 'server-error' },
  { code: 502, name: 'Bad Gateway', description: 'Respuesta inválida de un servidor ascendente.', category: 'server-error' },
  { code: 503, name: 'Service Unavailable', description: 'El servicio no está disponible temporalmente.', category: 'server-error' },
]

const categoryColors = {
  'success': 'bg-green-100 text-green-800 border-green-200',
  'client-error': 'bg-yellow-100 text-yellow-800 border-yellow-200',
  'server-error': 'bg-red-100 text-red-800 border-red-200',
  'redirect': 'bg-blue-100 text-blue-800 border-blue-200',
  'informational': 'bg-gray-100 text-gray-800 border-gray-200',
}

export default function HttpCodesPage() {
  const [selectedCode, setSelectedCode] = useState<HttpCode | null>(null)

  return (
    <div className="space-y-6">
      <h2 className="text-2xl font-bold text-gray-800">Códigos de Respuesta HTTP</h2>
      <p className="text-gray-600">
        Catálogo de códigos de respuesta HTTP utilizados en la API de XMR-Forecast.
      </p>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        {httpCodes.map((code) => (
          <div
            key={code.code}
            className={`p-4 rounded-lg border cursor-pointer transition hover:shadow-md ${categoryColors[code.category]}`}
            onClick={() => setSelectedCode(code)}
          >
            <div className="flex items-center justify-between mb-2">
              <span className="text-2xl font-bold">{code.code}</span>
              <span className="text-xs uppercase tracking-wide">{code.category}</span>
            </div>
            <h3 className="font-semibold text-sm">{code.name}</h3>
          </div>
        ))}
      </div>

      {selectedCode && (
        <div className={`p-6 rounded-lg border ${categoryColors[selectedCode.category]}`}>
          <h3 className="text-xl font-bold mb-2">
            {selectedCode.code} - {selectedCode.name}
          </h3>
          <p className="text-sm mb-4">{selectedCode.description}</p>
          <div className="text-xs">
            <strong>Categoría:</strong> {selectedCode.category}
          </div>
        </div>
      )}

      <div className="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
        <h3 className="text-lg font-semibold text-gray-800 mb-4">Leyenda de Categorías</h3>
        <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
          <div className="flex items-center gap-2">
            <div className="w-4 h-4 bg-green-100 border border-green-200 rounded"></div>
            <span className="text-sm">2xx Success</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-4 h-4 bg-blue-100 border border-blue-200 rounded"></div>
            <span className="text-sm">3xx Redirect</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-4 h-4 bg-yellow-100 border border-yellow-200 rounded"></div>
            <span className="text-sm">4xx Client Error</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-4 h-4 bg-red-100 border border-red-200 rounded"></div>
            <span className="text-sm">5xx Server Error</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-4 h-4 bg-gray-100 border border-gray-200 rounded"></div>
            <span className="text-sm">1xx Info</span>
          </div>
        </div>
      </div>
    </div>
  )
}
