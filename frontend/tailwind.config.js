/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Base backgrounds
        root: 'var(--xmr-bg-root)',
        deep: 'var(--xmr-bg-deep)',
        raised: 'var(--xmr-bg-raised)',
        elevated: 'var(--xmr-bg-elevated)',
        // Surfaces -> panels
        surface: {
          1: 'var(--xmr-surface-1)',
          2: 'var(--xmr-surface-2)',
          3: 'var(--xmr-surface-3)',
          inset: 'var(--xmr-surface-inset)',
          overlay: 'var(--xmr-surface-overlay)',
        },
        // Borders
        hairline: {
          DEFAULT: 'var(--xmr-border-hairline)',
          subtle: 'var(--xmr-border-subtle)',
          default: 'var(--xmr-border-default)',
          strong: 'var(--xmr-border-strong)',
          focus: 'var(--xmr-border-focus)',
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
        },
        // State aliases
        idle: 'rgb(var(--xmr-text-muted-ch) / <alpha-value>)',
        active: 'rgb(var(--xmr-accent-cyan-ch) / <alpha-value>)',
        success: 'rgb(var(--xmr-accent-green-ch) / <alpha-value>)',
        warning: 'rgb(var(--xmr-accent-amber-ch) / <alpha-value>)',
        danger: 'rgb(var(--xmr-accent-red-ch) / <alpha-value>)',
        disabled: 'var(--xmr-state-disabled)',
      },
      backgroundColor: {
        'accent-soft': {
          green: 'var(--xmr-accent-green-soft)',
          cyan: 'var(--xmr-accent-cyan-soft)',
          red: 'var(--xmr-accent-red-soft)',
          amber: 'var(--xmr-accent-amber-soft)',
        },
      },
      borderColor: {
        'accent-soft': {
          green: 'var(--xmr-accent-green-glow)',
          cyan: 'var(--xmr-accent-cyan-glow)',
          red: 'var(--xmr-accent-red-glow)',
          amber: 'var(--xmr-accent-amber-glow)',
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
        tight: 'var(--xmr-tracking-tight)',
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
        'focus': 'var(--xmr-shadow-focus)',
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
          '70%': { boxShadow: '0 0 0 8px rgba(6, 182, 212, 0)' },
          '100%': { boxShadow: '0 0 0 0 rgba(6, 182, 212, 0)' },
        },
        'fade-in': {
          from: { opacity: '0', transform: 'translateY(4px)' },
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
      },
      animation: {
        'pulse-ring': 'pulse-ring 2.4s var(--xmr-ease-out) infinite',
        'fade-in': 'fade-in var(--xmr-duration-slow) var(--xmr-ease-out) both',
        'fade-in-slow': 'fade-in-slow var(--xmr-duration-slow) linear both',
        shimmer: 'shimmer 1.6s infinite',
        'spin-slow': 'spin-slow 1.1s linear infinite',
      },
    },
  },
  plugins: [],
}