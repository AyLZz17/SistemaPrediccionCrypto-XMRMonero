import { useState } from 'react'
import Input from '../common/Input'
import Button from '../common/Button'

interface DatasetFormProps {
  onSubmit: (data: any) => void
  loading?: boolean
}

export default function DatasetForm({ onSubmit, loading }: DatasetFormProps) {
  const [name, setName] = useState('')
  const [domain, setDomain] = useState('crypto')
  const [source, setSource] = useState('yahoo_finance')
  const [symbol, setSymbol] = useState('XMR-USD')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    onSubmit({ name, domain, source, symbol })
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <Input
        label="Nombre"
        value={name}
        onChange={(e) => setName(e.target.value)}
        required
      />
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Dominio
        </label>
        <select
          value={domain}
          onChange={(e) => setDomain(e.target.value)}
          className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500"
        >
          <option value="crypto">Criptomonedas</option>
          <option value="retail">Retail</option>
        </select>
      </div>
      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">
          Fuente
        </label>
        <select
          value={source}
          onChange={(e) => setSource(e.target.value)}
          className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-indigo-500"
        >
          <option value="yahoo_finance">Yahoo Finance</option>
          <option value="cryptodatadownload">CryptoDataDownload</option>
          <option value="coingecko">CoinGecko</option>
        </select>
      </div>
      <Input
        label="Símbolo"
        value={symbol}
        onChange={(e) => setSymbol(e.target.value)}
        placeholder="XMR-USD"
      />
      <Button type="submit" disabled={loading} className="w-full">
        {loading ? 'Creando...' : 'Crear Dataset'}
      </Button>
    </form>
  )
}
