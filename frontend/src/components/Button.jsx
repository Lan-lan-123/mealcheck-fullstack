// @ts-check
import { LoaderCircle } from 'lucide-react'

/** @typedef {'primary' | 'secondary' | 'danger' | 'ghost' | 'plain'} ButtonVariant */
/** @typedef {import('react').ButtonHTMLAttributes<HTMLButtonElement> & {
 * variant?: ButtonVariant, busy?: boolean, loadingText?: string,
 * ref?: import('react').Ref<HTMLButtonElement>
 * }} ButtonProps */

const variantClasses = {
  primary: 'primary-btn',
  secondary: 'secondary-btn',
  danger: 'danger-btn',
  ghost: 'ghost',
  plain: '',
}

/** @param {ButtonProps} props */
export default function Button({
  variant = 'secondary',
  busy = false,
  disabled = false,
  type = 'button',
  className = '',
  loadingText,
  children,
  ref,
  ...props
}) {
  return (
    <button
      {...props}
      ref={ref}
      type={type}
      disabled={disabled || busy}
      aria-busy={busy || props['aria-busy']}
      className={['mc-button', `mc-button--${variant}`, variantClasses[variant], className].filter(Boolean).join(' ')}
    >
      {busy && <LoaderCircle size={16} className="is-spinning" aria-hidden="true" />}
      {busy && loadingText ? loadingText : children}
    </button>
  )
}
