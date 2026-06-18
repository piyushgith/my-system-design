import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { authService } from '@/api/services/auth.service'
import { useAuthStore } from '@/store/auth.store'
import { useUiStore } from '@/store/ui.store'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Card } from '@/components/ui/Card'
import { otpPhoneSchema, otpVerifySchema, type OtpPhoneForm, type OtpVerifyForm } from '@/features/auth/schemas/otp.schema'
import { MOCK_OTP } from '@/constants/demo'
import type { UserRole } from '@/types/api/common.types'

export const LoginPage = () => {
  const navigate = useNavigate()
  const loginOtp = useAuthStore((s) => s.loginOtp)
  const addToast = useUiStore((s) => s.addToast)
  const [step, setStep] = useState<'phone' | 'otp'>('phone')
  const [otpRequestId, setOtpRequestId] = useState('')
  const [role, setRole] = useState<UserRole>('RIDER')

  const phoneForm = useForm<OtpPhoneForm>({
    resolver: zodResolver(otpPhoneSchema),
    defaultValues: { phoneNumber: '+919900000099' },
  })

  const otpForm = useForm<OtpVerifyForm>({
    resolver: zodResolver(otpVerifySchema),
    defaultValues: { otpCode: MOCK_OTP, userType: 'RIDER' },
  })

  const requestMutation = useMutation({
    mutationFn: authService.requestOtp,
    onSuccess: (data) => {
      setOtpRequestId(data.otp_request_id)
      otpForm.setValue('otpRequestId', data.otp_request_id)
      setStep('otp')
      addToast({ type: 'info', message: data.dev_hint ?? 'OTP sent' })
    },
  })

  const verifyMutation = useMutation({
    mutationFn: authService.verifyOtp,
    onSuccess: (data) => {
      loginOtp(data.user_id, data.user_type, data.access_token, data.refresh_token)
      addToast({ type: 'success', message: 'Logged in successfully' })
      navigate(data.user_type === 'DRIVER' ? '/driver' : '/rider')
    },
  })

  const handleRequestOtp = phoneForm.handleSubmit((values) => {
    requestMutation.mutate({ phoneNumber: values.phoneNumber })
  })

  const handleVerifyOtp = otpForm.handleSubmit((values) => {
    verifyMutation.mutate({
      otpRequestId: values.otpRequestId || otpRequestId,
      otpCode: values.otpCode,
      userType: role,
    })
  })

  return (
    <div className="animate-fade-in space-y-6">
      <div>
        <h1 className="font-display text-2xl font-bold text-cream">Sign in with OTP</h1>
        <p className="mt-1 text-sm text-muted">Mock OTP for dev: {MOCK_OTP}</p>
      </div>

      <div className="flex gap-2">
        {(['RIDER', 'DRIVER'] as const).map((r) => (
          <button
            key={r}
            type="button"
            onClick={() => setRole(r)}
            className={`flex-1 rounded-xl border py-2 text-sm font-medium transition ${
              role === r ? 'border-amber-500 bg-amber-500/10 text-amber-400' : 'border-border text-muted'
            }`}
          >
            {r === 'RIDER' ? 'Rider' : 'Driver'}
          </button>
        ))}
      </div>

      {step === 'phone' ? (
        <Card title="Phone number">
          <form onSubmit={handleRequestOtp} className="space-y-4">
            <Input label="Phone" {...phoneForm.register('phoneNumber')} error={phoneForm.formState.errors.phoneNumber?.message} />
            <Button type="submit" loading={requestMutation.isPending} className="w-full">
              Send OTP
            </Button>
          </form>
        </Card>
      ) : (
        <Card title="Enter OTP">
          <form onSubmit={handleVerifyOtp} className="space-y-4">
            <input type="hidden" {...otpForm.register('otpRequestId')} />
            <Input label="OTP code" {...otpForm.register('otpCode')} error={otpForm.formState.errors.otpCode?.message} />
            <Button type="submit" loading={verifyMutation.isPending} className="w-full">
              Verify & continue
            </Button>
            <Button type="button" variant="ghost" className="w-full" onClick={() => setStep('phone')}>
              Change number
            </Button>
          </form>
        </Card>
      )}

      <p className="text-center text-sm text-muted">
        <Link to="/login/dev" className="text-amber-500 hover:underline">Use demo shortcuts instead</Link>
      </p>
    </div>
  )
}
