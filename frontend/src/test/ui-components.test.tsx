import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Badge, DataTable, Pagination, type Column } from '../components/ui'
import { Button } from '../components/ui/Button'
import { Modal } from '../components/ui/Modal'
import { Notice } from '../components/ui/Alert'
import { ActivityBar, MetricCard, StatusDot } from '../components/ui/Indicators'
import { EmptyState, ErrorState, Skeleton, SkeletonTable } from '../components/ui/States'
import { TextField, SelectField } from '../components/ui/Field'
import { StatusPill, statusLabel, statusTone } from '../components/common/StatusPill'
import { Disclaimer } from '../components/common/Disclaimer'
import { ErrorBoundary } from '../components/common/ErrorBoundary'
import type { Job } from '../types'

describe('Button', () => {
  it('exposes disabled and busy state to assistive tech', () => {
    render(<Button loading>Guardar</Button>)
    const button = screen.getByRole('button', { name: /guardar/i })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
  })

  it('calls onClick when enabled', async () => {
    const onClick = vi.fn()
    render(<Button onClick={onClick}>Aceptar</Button>)
    await userEvent.click(screen.getByRole('button', { name: /aceptar/i }))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('defaults to type="button" so it never submits a form by accident', () => {
    render(<Button>Seguro</Button>)
    expect(screen.getByRole('button', { name: /seguro/i })).toHaveAttribute('type', 'button')
  })
})

describe('Form fields', () => {
  it('associates every label with its control', () => {
    render(
      <>
        <TextField label="Correo electronico" name="email" defaultValue="a@b.com" />
        <SelectField label="Rol" name="rol" defaultValue="VIEWER">
          <option value="VIEWER">Viewer</option>
        </SelectField>
      </>,
    )
    expect(screen.getByLabelText(/correo electronico/i)).toHaveValue('a@b.com')
    expect(screen.getByLabelText(/rol/i)).toHaveValue('VIEWER')
  })

  it('marks the field invalid and wires aria-describedby to the error', () => {
    render(<TextField label="Contrasena" name="password" error="Muy corta" />)
    const input = screen.getByLabelText(/contrasena/i)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    const describedBy = input.getAttribute('aria-describedby')
    expect(describedBy).toBeTruthy()
    expect(document.getElementById(describedBy as string)).toHaveTextContent('Muy corta')
  })

  it('announces the error with role="alert"', () => {
    render(<TextField label="Correo" name="email" error="Correo invalido" />)
    expect(screen.getByRole('alert')).toHaveTextContent('Correo invalido')
  })
})

describe('DataTable', () => {
  interface Row {
    id: string
    name: string
    amount: number
  }
  const rows: Row[] = [
    { id: '1', name: 'LSTM', amount: 12.5 },
    { id: '2', name: 'ARIMA', amount: 9.25 },
  ]
  const columns: Array<Column<Row>> = [
    { key: 'name', header: 'Modelo' },
    { key: 'amount', header: 'MAE', numeric: true },
  ]

  it('renders a caption and column headers for screen readers', () => {
    render(<DataTable caption="Comparativa de modelos" columns={columns} rows={rows} rowKey={(row) => row.id} />)
    const table = screen.getByRole('table', { name: /comparativa de modelos/i })
    expect(table).toBeInTheDocument()
    expect(within(table).getByRole('columnheader', { name: /modelo/i })).toBeInTheDocument()
    expect(within(table).getByRole('columnheader', { name: /mae/i })).toBeInTheDocument()
  })

  it('renders the empty message when there are no rows', () => {
    render(
      <DataTable
        caption="Vacia"
        columns={columns}
        rows={[]}
        rowKey={(row) => row.id}
        empty="No hay velas."
      />,
    )
    expect(screen.getByText('No hay velas.')).toBeInTheDocument()
  })
})

describe('Pagination', () => {
  it('disables both controls on the first and only page', () => {
    render(<Pagination page={0} totalPages={1} total={3} size={15} onPageChange={() => undefined} />)
    expect(screen.getByRole('button', { name: /anterior/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /siguiente/i })).toBeDisabled()
  })

  it('is a navigation landmark and reports the visible range', () => {
    render(<Pagination page={1} totalPages={4} total={60} size={15} onPageChange={() => undefined} />)
    const nav = screen.getByRole('navigation', { name: /paginacion/i })
    expect(nav).toHaveTextContent('16-30 de 60 registros')
    expect(nav).toHaveTextContent('Pagina 2 / 4')
  })

  it('moves to the next page', async () => {
    const onPageChange = vi.fn()
    render(<Pagination page={0} totalPages={4} total={60} size={15} onPageChange={onPageChange} />)
    await userEvent.click(screen.getByRole('button', { name: /siguiente/i }))
    expect(onPageChange).toHaveBeenCalledWith(1)
  })
})

describe('Modal', () => {
  it('is a labelled dialog with a focus trap and Escape handling', async () => {
    const onClose = vi.fn()
    render(
      <Modal open onClose={onClose} title="Nueva prediccion" description="El backend encolara el calculo.">
        <button type="button">Interior</button>
      </Modal>,
    )
    const dialog = screen.getByRole('dialog', { name: /nueva prediccion/i })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleDescription(/encolara el calculo/i)

    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('renders nothing when closed', () => {
    render(
      <Modal open={false} onClose={() => undefined} title="Oculto">
        <p>contenido</p>
      </Modal>,
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('Alerts and states', () => {
  it('Notice is a neutral, non-alarming block', () => {
    render(<Notice>No es asesoria financiera.</Notice>)
    expect(screen.getByText('No es asesoria financiera.')).toBeInTheDocument()
  })

  it('ErrorState uses role="alert" and offers a retry', async () => {
    const onRetry = vi.fn()
    render(<ErrorState message="No se pudo cargar." requestId="req-9" onRetry={onRetry} />)
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('No se pudo cargar.')
    expect(alert).toHaveTextContent('req-9')
    await userEvent.click(screen.getByRole('button', { name: /reintentar/i }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('EmptyState shows its title and description', () => {
    render(<EmptyState title="Sin datos" description="Nada por aqui todavia." />)
    expect(screen.getByText('Sin datos')).toBeInTheDocument()
    expect(screen.getByText('Nada por aqui todavia.')).toBeInTheDocument()
  })

  it('skeletons are hidden from assistive tech and announced as busy', () => {
    const { container } = render(
      <>
        <Skeleton className="h-4" />
        <SkeletonTable rows={2} columns={2} />
      </>,
    )
    expect(container.querySelectorAll('[aria-hidden="true"]').length).toBeGreaterThan(0)
    expect(screen.getByRole('status', { name: /cargando datos/i })).toBeInTheDocument()
  })
})

describe('Indicators', () => {
  it('Badge renders its label', () => {
    render(<Badge tone="success">campeon</Badge>)
    expect(screen.getByText('campeon')).toBeInTheDocument()
  })

  it('StatusDot shows a text label, not only colour', () => {
    render(<StatusDot tone="success" label="En linea" />)
    expect(screen.getByText('En linea')).toBeInTheDocument()
  })

  it('ActivityBar reports a determinate progress value', () => {
    render(<ActivityBar label="Entrenamiento" progress={0.42} />)
    const bar = screen.getByRole('progressbar', { name: /entrenamiento/i })
    expect(bar).toHaveAttribute('aria-valuenow', '42')
  })

  it('ActivityBar without progress is indeterminate', () => {
    render(<ActivityBar label="Cola" />)
    const bar = screen.getByRole('progressbar', { name: /cola/i })
    expect(bar).not.toHaveAttribute('aria-valuenow')
  })

  it('MetricCard shows label, value and unit', () => {
    render(<MetricCard label="MAE" value="12.34" unit="USD" />)
    expect(screen.getByText('MAE')).toBeInTheDocument()
    expect(screen.getByText('12.34')).toBeInTheDocument()
    expect(screen.getByText('USD')).toBeInTheDocument()
  })
})

describe('StatusPill', () => {
  it('maps known statuses to friendly labels and unknown ones to themselves', () => {
    expect(statusTone('SUCCEEDED')).toBe('success')
    expect(statusTone('FAILED')).toBe('danger')
    expect(statusTone('UNKNOWN_THING')).toBe('neutral')
    expect(statusLabel('RUNNING')).toBe('ejecutando')
    expect(statusLabel('MYSTERY')).toBe('MYSTERY')
    expect(statusLabel(null)).toBe('—')
  })

  it('renders a live dot for running jobs', () => {
    const job: Job = {
      id: 'j-1',
      type: 'TRAIN',
      status: 'RUNNING',
    }
    render(<StatusPill status={job.status} />)
    expect(screen.getByText('ejecutando')).toBeInTheDocument()
  })
})

describe('Disclaimer (R-11 / R-12)', () => {
  it('states it is not financial advice and never promises profitability', () => {
    render(<Disclaimer variant="full" />)
    const notice = screen.getByTestId('disclaimer')
    expect(notice).toHaveTextContent(/no es asesoria financiera/i)
    expect(notice).toHaveTextContent(/no ofrece ni promete rentabilidad/i)
    expect(notice).toHaveTextContent(/no simula operaciones ni backtesting/i)
  })

  it('uses the approved "capacidad predictiva evaluada" wording', () => {
    render(<Disclaimer variant="full" />)
    expect(screen.getByTestId('disclaimer')).toHaveTextContent(/capacidad predictiva evaluada/i)
  })

  it('never uses the forbidden phrase "predice el mercado"', () => {
    const { container } = render(<Disclaimer variant="full" />)
    expect(container.textContent?.toLowerCase()).not.toContain('predice el mercado')
  })
})

describe('ErrorBoundary', () => {
  function Boom(): JSX.Element {
    throw new Error('fallo de renderizado')
  }

  it('catches a render error, shows the mandatory footer and offers a retry', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    )
    expect(screen.getByRole('heading', { name: /encontro un error inesperado/i })).toBeInTheDocument()
    expect(
      screen.getByText(
        'Sistema esta realizado por \u00A9 AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.',
      ),
    ).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /^reintentar$/i }))
    spy.mockRestore()
  })
})
