/** Small square brand mark, used in the sidebar and on the login page. */
export function Logo({ size = 'md' }: { size?: 'md' | 'lg' }) {
  const box = size === 'lg' ? 'h-10 w-10 text-lg' : 'h-8 w-8 text-base'
  return <span className={`flex items-center justify-center rounded-lg bg-indigo-600 font-bold text-white ${box}`}>F</span>
}
