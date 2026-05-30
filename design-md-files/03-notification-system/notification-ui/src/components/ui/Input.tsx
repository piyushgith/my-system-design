import { forwardRef, type InputHTMLAttributes } from 'react'

interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string
  error?: string
  hint?: string
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ label, error, hint, className = '', ...props }, ref) => (
    <div className="space-y-1">
      {label && (
        <label className="block text-sm font-medium text-gray-700">
          {label}
          {props.required && <span className="ml-1 text-red-500">*</span>}
        </label>
      )}
      <input
        ref={ref}
        className={`block w-full rounded-md border px-3 py-2 text-sm shadow-sm
          placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500
          ${error ? 'border-red-300 focus:ring-red-500' : 'border-gray-300'}
          disabled:cursor-not-allowed disabled:bg-gray-50 ${className}`}
        {...props}
      />
      {error && <p className="text-xs text-red-600">{error}</p>}
      {hint && !error && <p className="text-xs text-gray-500">{hint}</p>}
    </div>
  )
)

Input.displayName = 'Input'
