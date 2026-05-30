import { useState } from 'react'
import { useNavigate, Navigate } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import { useToastStore } from '../store/toastStore'
import { authApi } from '../api/auth'
import { Button } from '../components/ui/Button'
import { Input } from '../components/ui/Input'

type Step = 'phone' | 'otp'

function roleDefaultPath(role: string | null): string {
  if (role === 'RESTAURANT_OWNER') return '/restaurant/orders'
  if (role === 'DELIVERY_PARTNER') return '/delivery'
  return '/'
}

export function LoginPage() {
  const { isAuthenticated, role, login } = useAuthStore()
  const { addToast } = useToastStore()
  const navigate = useNavigate()

  const [step, setStep] = useState<Step>('phone')
  const [phone, setPhone] = useState('')
  const [otp, setOtp] = useState('')
  const [loading, setLoading] = useState(false)
  const [phoneError, setPhoneError] = useState('')
  const [otpError, setOtpError] = useState('')

  if (isAuthenticated) {
    return <Navigate to={roleDefaultPath(role)} replace />
  }

  async function handleSendOtp(e: React.FormEvent) {
    e.preventDefault()
    setPhoneError('')
    const cleaned = phone.replace(/\s/g, '')
    if (!/^\d{10}$/.test(cleaned)) {
      setPhoneError('Enter valid 10-digit phone number')
      return
    }
    setLoading(true)
    try {
      await authApi.sendOtp({ phone: cleaned })
      setPhone(cleaned)
      setStep('otp')
      addToast('OTP sent to ' + cleaned, 'success')
    } catch {
      setPhoneError('Failed to send OTP. Try again.')
    } finally {
      setLoading(false)
    }
  }

  async function handleVerifyOtp(e: React.FormEvent) {
    e.preventDefault()
    setOtpError('')
    if (!/^\d{4,6}$/.test(otp)) {
      setOtpError('Enter valid OTP')
      return
    }
    setLoading(true)
    try {
      const res = await authApi.verifyOtp({ phone, otp })
      const { accessToken, userId, name } = res.data
      login(accessToken, userId, name, phone)
      const r = useAuthStore.getState().role
      navigate(roleDefaultPath(r), { replace: true })
    } catch {
      setOtpError('Invalid or expired OTP')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center px-4">
      <div className="w-full max-w-sm bg-white rounded-2xl shadow-md p-8">
        <div className="text-center mb-8">
          <h1 className="text-2xl font-bold text-orange-500">FoodDash</h1>
          <p className="text-sm text-gray-500 mt-1">
            {step === 'phone' ? 'Enter your phone number' : 'Enter the OTP'}
          </p>
        </div>

        {step === 'phone' ? (
          <form onSubmit={handleSendOtp} className="space-y-4">
            <Input
              label="Phone Number"
              type="tel"
              placeholder="9876543210"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              error={phoneError}
              autoFocus
              maxLength={10}
            />
            <Button type="submit" variant="primary" className="w-full" loading={loading}>
              Send OTP
            </Button>
          </form>
        ) : (
          <form onSubmit={handleVerifyOtp} className="space-y-4">
            <p className="text-sm text-gray-600">
              OTP sent to <span className="font-medium">{phone}</span>
            </p>
            <Input
              label="OTP"
              type="text"
              placeholder="Enter OTP"
              value={otp}
              onChange={(e) => setOtp(e.target.value)}
              error={otpError}
              autoFocus
              maxLength={6}
              inputMode="numeric"
            />
            <Button type="submit" variant="primary" className="w-full" loading={loading}>
              Verify OTP
            </Button>
            <button
              type="button"
              onClick={() => { setStep('phone'); setOtp(''); setOtpError('') }}
              className="w-full text-sm text-gray-400 hover:text-gray-600 transition-colors"
            >
              Change phone number
            </button>
          </form>
        )}
      </div>
    </div>
  )
}
