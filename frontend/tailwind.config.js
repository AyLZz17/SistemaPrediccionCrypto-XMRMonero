/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Surfaces -> bg-root / bg-deep / bg-raised
        root: 'var(--xmr-bg-root)',
        deep: 'var(--xmr-bg-deep)',
        raised: 'var(--xmr-bg-raised)',
        surface: {
          1: 'var(--xmr-surface-1)',
          2: 'var(--xmr-surface-2)',
          3: 'var(--xmr-surface-3)',
          inset: 'var(--xmr-surface-inset)',
          overlay: 'var(--xmr-surface-overlay)',
        },
        // Borders
        hairline: {
          subtle: 'var(--xmr-border-subtle)',
          DEFAULT: 'var(--xmr-border-default)',
          strong: 'var(--xmr-border-strong)',
          accent: 'var(--xmr-border-accent)',
        },
        // Text
        ink: {
          DEFAULT: 'rgb(var(--xmr-text-primary-ch) / <alpha-value>)',
          secondary: 'rgb(var(--xmr-text-secondary-ch) / <alpha-value>)',
          muted: 'rgb(var(--xmr-text-muted-ch) / <alpha-value>)',
          inverse: 'var(--xmr-text-inverse)',
        },
        // Accents (state / alert / metric accents only)
        accent: {
          green: 'rgb(var(--xmr-accent-green-ch) / <alpha-value>)',
          cyan: 'rgb(var(--xmr-accent-cyan-ch) / <alpha-value>)',
          red: 'rgb(var(--xmr-accent-red-ch) / <alpha-value>)',
          amber: 'rgb(var(--xmr-accent-amber-ch) / <alpha-value>)',
          violet: 'rgb(var(--xmr-accent-violet-ch) / <alpha-value>)',
        },
        // State aliases
        idle: 'rgb(var(--xmr-text-muted-ch) / <alpha-value>)',
        active: 'rgb(var(--xmr-accent-cyan-ch) / <alpha-value>)',
        success: 'rgb(var(--xmr-accent-green-ch) / <alpha-value>)',
        warning: 'rgb(var(--xmr-accent-amber-ch) / <alpha-value>)',
        danger: 'rgb(var(--xmr-accent-red-ch) / <alpha-value>)',
        info: 'rgb(var(--xmr-accent-violet-ch) / <alpha-value>)',
        disabled: 'var(--xmr-state-disabled)',
      },
      backgroundColor: {
        'accent-soft': {
          green: 'var(--xmr-accent-green-soft)',
          cyan: 'var(--xmr-accent-cyan-soft)',
          red: 'var(--xmr-accent-red-soft)',
          amber: 'var(--xmr-accent-amber-soft)',
          violet: 'var(--xmr-accent-violet-soft)',
        },
      },
      borderColor: {
        'accent-soft': {
          green: 'var(--xmr-accent-green-glow)',
          cyan: 'var(--xmr-accent-cyan-glow)',
          red: 'var(--xmr-accent-red-glow)',
        },
      },
      fontFamily: {
        sans: ['var(--xmr-font-sans)'],
        mono: ['var(--xmr-font-mono)'],
      },
      fontSize: {
        xs: 'var(--xmr-text-xs)',
        sm: 'var(--xmr-text-sm)',
        base: 'var(--xmr-text-base)',
        lg: 'var(--xmr-text-lg)',
        xl: 'var(--xmr-text-xl)',
        '2xl': 'var(--xmr-text-2xl)',
        '3xl': 'var(--xmr-text-3xl)',
      },
      letterSpacing: {
        wide: 'var(--xmr-tracking-wide)',
      },
      lineHeight: {
        tight: 'var(--xmr-leading-tight)',
        normal: 'var(--xmr-leading-normal)',
      },
      borderRadius: {
        sm: 'var(--xmr-radius-sm)',
        md: 'var(--xmr-radius-md)',
        lg: 'var(--xmr-radius-lg)',
        full: 'var(--xmr-radius-full)',
      },
      boxShadow: {
        'card': 'var(--xmr-shadow-md)',
        'card-lg': 'var(--xmr-shadow-lg)',
        'card-sm': 'var(--xmr-shadow-sm)',
        'glow-cyan': 'var(--xmr-shadow-glow-cyan)',
        'glow-green': 'var(--xmr-shadow-glow-green)',
        inset: 'var(--xmr-shadow-inset)',
      },
      backdropBlur: {
        glass: 'var(--xmr-glass-blur)',
      },
      transitionTimingFunction: {
        out: 'var(--xmr-ease-out)',
      },
      transitionDuration: {
        fast: 'var(--xmr-duration-fast)',
        DEFAULT: 'var(--xmr-duration-base)',
        slow: 'var(--xmr-duration-slow)',
      },
      maxWidth: {
        content: 'var(--xmr-content-max)',
      },
      spacing: {
        sidebar: 'var(--xmr-sidebar-width)',
        header: 'var(--xmr-header-height)',
        footer: 'var(--xmr-footer-height)',
      },
      keyframes: {
        'pulse-ring': {
          '0%': { boxShadow: '0 0 0 0 var(--xmr-accent-cyan-glow)' },
          '70%': { boxShadow: '0 0 0 8px rgba(34, 211, 238, 0)' },
          '100%': { boxShadow: '0 0 0 0 rgba(34, 211, 238, 0)' },
        },
        'fade-in': {
          from: { opacity: '0', transform: 'translateY(6px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'fade-in-slow': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
        shimmer: {
          '100%': { transform: 'translateX(100%)' },
        },
        'spin-slow': {
          to: { transform: 'rotate(360deg)' },
        },
        'scan-line': {
          '0%': { transform: 'translateY(-100%)' },
          '100%': { transform: 'translateY(400%)' },
        },
      },
      animation: {
        'pulse-ring': 'pulse-ring 2.4s var(--xmr-ease-out) infinite',
        'fade-in': 'fade-in var(--xmr-duration-slow) var(--xmr-ease-out) both',
        'fade-in-slow': 'fade-in-slow var(--xmr-duration-slow) linear both',
        shimmer: 'shimmer 1.6s infinite',
        'spin-slow': 'spin-slow 1.1s linear infinite',
        'scan-line': 'scan-line 2.8s linear infinite',
      },
    },
  },
  plugins: [],
}
