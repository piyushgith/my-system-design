import React from 'react';

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  hint?: string;
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ label, error, hint, className = '', id, ...props }, ref) => {
    const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-');
    return (
      <div className="flex flex-col gap-1.5">
        {label && (
          <label
            htmlFor={inputId}
            className="text-xs font-display font-semibold uppercase tracking-widest text-ch-muted"
          >
            {label}
          </label>
        )}
        <input
          ref={ref}
          id={inputId}
          className={`
            w-full bg-ch-surface border rounded-lg px-3 py-2.5 text-sm text-ch-text
            placeholder:text-ch-faint
            transition-all duration-150
            focus:outline-none focus:border-ch-accent focus:bg-ch-elevated focus:shadow-[0_0_0_2px_rgba(245,158,11,0.15)]
            disabled:opacity-40 disabled:cursor-not-allowed
            ${error ? 'border-ch-error focus:border-ch-error focus:shadow-[0_0_0_2px_rgba(239,68,68,0.15)]' : 'border-ch-border'}
            ${className}
          `}
          {...props}
        />
        {error && <p className="text-xs text-ch-error font-body">{error}</p>}
        {hint && !error && <p className="text-xs text-ch-faint font-body">{hint}</p>}
      </div>
    );
  },
);

Input.displayName = 'Input';
