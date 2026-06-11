import type { PresenceStatus } from '../../types';

interface AvatarProps {
  name: string;
  size?: 'xs' | 'sm' | 'md' | 'lg';
  status?: PresenceStatus;
  className?: string;
}

const COLORS = [
  'bg-amber-600',
  'bg-blue-600',
  'bg-violet-600',
  'bg-emerald-600',
  'bg-rose-600',
  'bg-cyan-600',
  'bg-orange-600',
  'bg-teal-600',
];

function colorForName(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
  return COLORS[Math.abs(hash) % COLORS.length];
}

function initials(name: string): string {
  return name
    .split(/[\s-_]+/)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? '')
    .join('');
}

const statusColors: Record<PresenceStatus, string> = {
  ONLINE: 'bg-ch-online',
  AWAY: 'bg-ch-away',
  OFFLINE: 'bg-ch-offline',
};

const sizeClasses = {
  xs: 'w-6 h-6 text-[9px]',
  sm: 'w-8 h-8 text-xs',
  md: 'w-10 h-10 text-sm',
  lg: 'w-12 h-12 text-base',
};

const dotSizes = {
  xs: 'w-1.5 h-1.5',
  sm: 'w-2 h-2',
  md: 'w-2.5 h-2.5',
  lg: 'w-3 h-3',
};

export function Avatar({ name, size = 'md', status, className = '' }: AvatarProps) {
  const color = colorForName(name);
  return (
    <div className={`relative flex-shrink-0 ${className}`}>
      <div
        className={`${sizeClasses[size]} ${color} rounded-full flex items-center justify-center font-display font-bold text-white`}
        aria-label={name}
      >
        {initials(name)}
      </div>
      {status && (
        <span
          className={`absolute bottom-0 right-0 ${dotSizes[size]} ${statusColors[status]} rounded-full ring-2 ring-ch-surface`}
          aria-label={status}
        />
      )}
    </div>
  );
}
