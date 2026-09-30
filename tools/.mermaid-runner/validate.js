// =====================================================================
// Runner de validacion Mermaid.
//
// Recibe por stdin un JSON con la lista de bloques a validar y devuelve
// por stdout un JSON con el resultado de CADA bloque.
//
// Se invocan las dos fases que exige R-25:
//   1. parse  -> el diagrama es sintacticamente valido
//   2. render -> produce SVG sin lanzar error
//
// Un diagrama que pasa el parse pero falla el render esta mal, igual que
// uno que no parsea: por eso se ejecutan ambos.
// =====================================================================
const fs = require('fs')
const path = require('path')
const { JSDOM } = require('jsdom')

const MERMAID_PATH = path.join(__dirname, 'node_modules', 'mermaid', 'dist', 'mermaid.min.js')

function buildDom() {
  const dom = new JSDOM('<!doctype html><html><body><div id="host"></div></body></html>', {
    pretendToBeVisual: true,
    url: 'https://localhost/',
  })
  // jsdom no implementa estas APIs y mermaid las usa al medir el texto.
  dom.window.SVGElement = dom.window.SVGElement || function () {}
  if (!dom.window.matchMedia) {
    dom.window.matchMedia = () => ({
      matches: false,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
    })
  }
  global.window = dom.window
  global.document = dom.window.document
  // En Node 18+ `navigator` es un global de solo lectura: asignarlo lanza
  // TypeError y aborta la carga de mermaid. Se redefine con defineProperty.
  Object.defineProperty(global, 'navigator', {
    value: dom.window.navigator,
    configurable: true,
    writable: true,
  })
  global.Element = dom.window.Element
  global.SVGElement = dom.window.SVGElement
  global.Node = dom.window.Node
  global.DOMParser = dom.window.DOMParser

  // mermaid inyecta los estilos del SVG mediante CSSStyleSheet. jsdom expone una
  // version incompleta (sin replaceSync), y el global no existe en el scope del
  // modulo ESM. Se define un stub minimo de forma INCONDICIONAL: la validacion
  // no necesita los estilos, solo que el render termine y produzca un SVG.
  class StubCSSStyleSheet {
    constructor() {
      this.cssRules = []
    }
    replaceSync() {}
    insertRule(rule) {
      this.cssRules.push({ cssText: String(rule) })
      return this.cssRules.length - 1
    }
    deleteRule() {}
    get cssText() {
      return this.cssRules.map((r) => r.cssText).join('\n')
    }
  }
  dom.window.CSSStyleSheet = StubCSSStyleSheet
  global.CSSStyleSheet = StubCSSStyleSheet

  // mermaid limpia el SVG con DOMPurify, que jsdom no incorpora.
  const dompurifyModule = require('dompurify')
  const createDOMPurify = dompurifyModule.default || dompurifyModule
  global.DOMPurify = createDOMPurify(dom.window)
  dom.window.DOMPurify = global.DOMPurify
  // getBBox no existe en jsdom: se sustituye por un valor fijo razonable.
  dom.window.SVGElement.prototype.getBBox = function () {
    return { x: 0, y: 0, width: 120, height: 20 }
  }
  if (dom.window.Element.prototype) {
    dom.window.Element.prototype.getBoundingClientRect = function () {
      return { x: 0, y: 0, width: 120, height: 20, top: 0, left: 0, right: 120, bottom: 20 }
    }
  }
  return dom
}

async function main() {
  const input = JSON.parse(fs.readFileSync(0, 'utf8'))
  buildDom()

  // Se importa mermaid.core.mjs como ESM real desde disco: el bundle minificado
  // espera un entorno de navegador completo y falla al importarse como datos.
  const corePath = path.join(__dirname, 'node_modules', 'mermaid', 'dist', 'mermaid.core.mjs')
  const mod = await import('file://' + corePath.replace(/\\/g, '/'))

  let mermaid = mod.default
  if (mermaid && typeof mermaid.initialize !== 'function' && mermaid.mermaid) {
    mermaid = mermaid.mermaid
  }
  if (!mermaid) mermaid = mod.mermaid
  if (!mermaid || typeof mermaid.parse !== 'function') {
    throw new Error(
      'No se encontro la API de mermaid. Exportaciones: ' + Object.keys(mod).join(', '),
    )
  }

  mermaid.initialize({
    startOnLoad: false,
    securityLevel: 'loose',
    theme: 'dark',
    fontFamily: 'ui-monospace, monospace',
  })

  const results = []

  for (const block of input.blocks) {
    const entry = { index: block.index, kind: block.kind, parseOk: false, renderOk: false, error: null }

    // ---------------------------------------------------------- fase 1: parse
    try {
      const parsed = await mermaid.parse(block.code)
      entry.parseOk = parsed !== false
    } catch (err) {
      entry.error = String((err && err.message) || err).split('\n').slice(0, 6).join(' | ')
      results.push(entry)
      continue
    }

    // --------------------------------------------------------- fase 2: render
    try {
      const host = global.document.getElementById('host')
      host.innerHTML = ''
      const { svg } = await mermaid.render(`m${block.index}`, block.code)
      entry.renderOk = typeof svg === 'string' && svg.includes('<svg') && svg.length > 50
      if (!entry.renderOk) {
        entry.error = 'El render no produjo un SVG con contenido'
      }
    } catch (err) {
      entry.error = 'render: ' + String((err && err.message) || err).split('\n').slice(0, 6).join(' | ')
    }

    results.push(entry)
  }

  process.stdout.write(JSON.stringify(results))
}

main().catch((err) => {
  process.stdout.write(JSON.stringify([{ index: -1, parseOk: false, renderOk: false, error: String(err && err.message) }]))
  process.exit(1)
})