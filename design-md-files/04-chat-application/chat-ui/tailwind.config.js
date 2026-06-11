/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ch: {
          base: '#080910',
          surface: '#0f1018',
          elevated: '#161724',
          hover: '#1c1e2e',
          border: '#22253a',
          'border-subtle': '#181a28',
          accent: '#f59e0b',
          'accent-dim': '#d97706',
          'accent-glow': 'rgba(245,158,11,0.15)',
          text: '#e2e2ed',
          muted: '#9090a8',
          faint: '#4a4a68',
          online: '#22c55e',
          away: '#eab308',
          offline: '#4a4a68',
          error: '#ef4444',
          mine: '#1e2a4a',
          'mine-border': '#2d4a8a',
        },
      },
      fontFamily: {
        display: ['Syne', 'sans-serif'],
        body: ['Plus\\ Jakarta\\ Sans', 'sans-serif'],
      },
      animation: {
        'slide-in': 'slideIn 0.2s ease-out',
        'fade-in': 'fadeIn 0.15s ease-out',
        'pop-in': 'popIn 0.2s cubic-bezier(0.34, 1.56, 0.64, 1)',
        'shimmer': 'shimmer 1.5s infinite',
      },
      keyframes: {
        slideIn: {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        popIn: {
          '0%': { opacity: '0', transform: 'scale(0.92)' },
          '100%': { opacity: '1', transform: 'scale(1)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% center' },
          '100%': { backgroundPosition: '200% center' },
        },
      },
    },
  },
  plugins: [],
};
