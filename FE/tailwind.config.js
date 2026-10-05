/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        canvas: {
          light: '#F8FAFD',
          dark: '#060B14',
        },
        surface: {
          light: '#FFFFFF',
          dark: '#0F172A',
          subtleLight: '#EBF1F9',
          subtleDark: '#162238',
        },
        cobalt: {
          50: '#F0F5FF',
          100: '#E0EBFF',
          200: '#C0D7FE',
          300: '#94BCFB',
          400: '#6098F7',
          500: '#3B76F0',
          600: '#1E4FD8',
          700: '#173EAF',
          800: '#15338E',
          900: '#162D72',
          950: '#0E1A43',
        },
        cyanIce: {
          400: '#38BDF8',
          500: '#0EA5E9',
          600: '#0284C7',
        },
        navy: {
          800: '#14233C',
          850: '#0F1C32',
          900: '#0B1528',
          950: '#080E1A',
        }
      },
      fontFamily: {
        sans: ['ui-sans-serif', 'system-ui', '-apple-system', 'BlinkMacSystemFont', '"Segoe UI"', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', 'monospace'],
      },
      borderRadius: {
        'card': '20px',
        'button': '12px',
        'input': '8px',
        'modal': '24px',
      },
      boxShadow: {
        'subtle': '0 1px 3px 0 rgba(15, 23, 42, 0.05), 0 1px 2px -1px rgba(15, 23, 42, 0.05)',
        'float': '0 10px 30px -10px rgba(14, 30, 60, 0.12), 0 4px 10px -4px rgba(14, 30, 60, 0.07)',
        'float-dark': '0 14px 35px -10px rgba(0, 0, 0, 0.5), 0 4px 12px -4px rgba(0, 0, 0, 0.3)',
        'cobalt-glow': '0 0 20px -3px rgba(30, 79, 216, 0.3)',
        'cyan-glow': '0 0 15px -2px rgba(14, 165, 233, 0.35)',
      },
      keyframes: {
        pulseSubtle: {
          '0%, 100%': { opacity: '1', transform: 'scale(1)' },
          '50%': { opacity: '0.6', transform: 'scale(0.96)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        streamDot: {
          '0%, 80%, 100%': { transform: 'scale(0)', opacity: '0.3' },
          '40%': { transform: 'scale(1)', opacity: '1' },
        }
      },
      animation: {
        'pulse-subtle': 'pulseSubtle 3s ease-in-out infinite',
        'stream-dot': 'streamDot 1.4s infinite ease-in-out both',
        'shimmer': 'shimmer 2.5s infinite linear',
      }
    },
  },
  plugins: [],
}
