import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/auth'
import { Input } from '../components/ui/Input'
import { Select } from '../components/ui/Select'
import { Button } from '../components/ui/Button'
import type { AuthUser } from '../types'

const schema = z.object({
  userId: z.string().uuid('Must be a valid UUID'),
  name: z.string().min(1, 'Name is required'),
  role: z.enum(['admin', 'producer', 'user']),
})

type FormValues = z.infer<typeof schema>

const DEMO_USERS = [
  { userId: 'a1b2c3d4-0000-0000-0000-000000000001', name: 'Alice (Admin)', role: 'admin' },
  { userId: 'a1b2c3d4-0000-0000-0000-000000000002', name: 'Bob (Producer)', role: 'producer' },
  { userId: 'a1b2c3d4-0000-0000-0000-000000000003', name: 'Carol (User)', role: 'user' },
]

export function LoginPage() {
  const { login } = useAuthStore()
  const navigate = useNavigate()

  const {
    register,
    handleSubmit,
    setValue,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(schema) })

  function onSubmit(values: FormValues) {
    const user: AuthUser = {
      ...values,
      token: `demo-jwt-${values.userId}`,
    }
    login(user)
    navigate('/')
  }

  function fillDemo(demo: (typeof DEMO_USERS)[number]) {
    setValue('userId', demo.userId)
    setValue('name', demo.name)
    setValue('role', demo.role as AuthUser['role'])
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <div className="mb-3 text-5xl">🔔</div>
          <h1 className="text-2xl font-bold text-gray-900">Notification Hub</h1>
          <p className="mt-1 text-sm text-gray-500">Sign in to manage notifications</p>
        </div>

        <div className="rounded-lg border border-gray-200 bg-white p-8 shadow-sm">
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <Input
              label="User ID (UUID)"
              placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
              error={errors.userId?.message}
              {...register('userId')}
            />
            <Input
              label="Display Name"
              placeholder="Your name"
              error={errors.name?.message}
              {...register('name')}
            />
            <Select
              label="Role"
              error={errors.role?.message}
              options={[
                { value: 'admin', label: 'Admin' },
                { value: 'producer', label: 'Producer' },
                { value: 'user', label: 'User' },
              ]}
              {...register('role')}
            />
            <Button type="submit" className="w-full">
              Sign In
            </Button>
          </form>

          <div className="mt-6">
            <p className="mb-2 text-xs font-medium text-gray-500 uppercase tracking-wide">
              Quick demo users
            </p>
            <div className="flex flex-col gap-2">
              {DEMO_USERS.map((d) => (
                <button
                  key={d.userId}
                  type="button"
                  onClick={() => fillDemo(d)}
                  className="rounded-md border border-gray-200 px-3 py-2 text-left text-sm hover:bg-gray-50"
                >
                  <span className="font-medium">{d.name}</span>
                  <span className="ml-2 text-xs text-gray-400">{d.role}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
