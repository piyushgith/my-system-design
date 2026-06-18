export const logger = {
  debug: (...args: unknown[]) => {
    if (import.meta.env.DEV) {
      console.debug('[ride]', ...args)
    }
  },
  error: (...args: unknown[]) => {
    console.error('[ride]', ...args)
  },
}
