/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        vault: {
          bg: '#070b12',
          surface: '#0f1623',
          panel: '#151e2e',
          border: '#243047',
          muted: '#6b7c96',
          text: '#dce6f2',
          teal: '#2ec4b6',
          brass: '#c9a84c',
          danger: '#e05a5a',
        },
      },
      fontFamily: {
        display: ['Fraunces', 'Georgia', 'serif'],
        sans: ['"IBM Plex Sans"', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      boxShadow: {
        vault: '0 24px 80px rgba(0, 0, 0, 0.45)',
        glow: '0 0 40px rgba(46, 196, 182, 0.12)',
      },
      animation: {
        'fade-up': 'fadeUp 0.55s ease-out both',
        'pulse-soft': 'pulseSoft 2.4s ease-in-out infinite',
      },
      keyframes: {
        fadeUp: {
          '0%': { opacity: '0', transform: 'translateY(16px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        pulseSoft: {
          '0%, 100%': { opacity: '0.45' },
          '50%': { opacity: '1' },
        },
      },
    },
  },
  plugins: [],
}
