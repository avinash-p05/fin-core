import { useId } from 'react'

/**
 * FinCore brand mark: an "F" inside a hexagon (the core) with a coin dot.
 * `inverted` draws it white-on-indigo-text for use on the indigo login panel.
 */
export function Logo({ size = 'md', inverted = false }: { size?: 'md' | 'lg'; inverted?: boolean }) {
  // Unique gradient id, because the logo can appear more than once on a page.
  const gradientId = useId()
  const px = size === 'lg' ? 40 : 32
  const hexFill = inverted ? '#ffffff' : `url(#${gradientId})`
  const letter = inverted ? '#4f46e5' : '#ffffff'
  const coin = inverted ? '#818cf8' : '#a5b4fc'

  return (
    <svg width={px} height={px} viewBox="0 0 32 32" aria-hidden="true">
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#6366f1" />
          <stop offset="1" stopColor="#3730a3" />
        </linearGradient>
      </defs>
      <path
        d="M16 2.2 L27.95 9.1 L27.95 22.9 L16 29.8 L4.05 22.9 L4.05 9.1 Z"
        fill={hexFill}
        stroke={hexFill}
        strokeWidth="2.4"
        strokeLinejoin="round"
      />
      <g stroke={letter} strokeWidth="3" strokeLinecap="round" fill="none">
        <path d="M12 23 V9.5 H20.5" />
        <path d="M12 16 H18" />
      </g>
      <circle cx="20.6" cy="21.4" r="2.1" fill={coin} />
    </svg>
  )
}
