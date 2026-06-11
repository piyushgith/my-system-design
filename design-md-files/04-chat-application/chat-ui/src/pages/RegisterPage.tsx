import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import { Input } from '../components/ui/Input';
import { Button } from '../components/ui/Button';
import { useToastStore } from '../store/toastStore';
import { extractApiError } from '../api/client';

const schema = z.object({
  username: z
    .string()
    .min(3, 'Min 3 characters')
    .max(50, 'Max 50 characters')
    .regex(/^[a-zA-Z0-9_-]+$/, 'Only letters, numbers, _ and - allowed'),
  displayName: z.string().min(1, 'Required').max(100, 'Max 100 characters'),
  email: z.string().email('Valid email required'),
  password: z
    .string()
    .min(8, 'Min 8 characters')
    .max(72, 'Max 72 characters'),
});

type FormValues = z.infer<typeof schema>;

export function RegisterPage() {
  const setUser = useAuthStore((s) => s.setUser);
  const push = useToastStore((s) => s.push);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  const mutation = useMutation({
    mutationFn: authApi.register,
    onSuccess: (data) => {
      queryClient.clear();
      setUser(data);
      navigate('/');
    },
    onError: (err) => push('error', extractApiError(err)),
  });

  return (
    <div className="min-h-screen bg-ch-base flex items-center justify-center p-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <h1 className="font-display font-black text-4xl shimmer-text tracking-tight">
            Obsidian
          </h1>
          <p className="text-ch-faint text-sm mt-2 font-body">Create your account</p>
        </div>

        <form
          onSubmit={handleSubmit((v) => mutation.mutate(v))}
          className="bg-ch-surface border border-ch-border rounded-2xl p-6 flex flex-col gap-4 shadow-2xl"
        >
          <Input
            label="Username"
            placeholder="jsmith"
            autoComplete="username"
            {...register('username')}
            error={errors.username?.message}
          />
          <Input
            label="Display Name"
            placeholder="John Smith"
            autoComplete="name"
            {...register('displayName')}
            error={errors.displayName?.message}
          />
          <Input
            label="Email"
            type="email"
            placeholder="you@example.com"
            autoComplete="email"
            {...register('email')}
            error={errors.email?.message}
          />
          <Input
            label="Password"
            type="password"
            placeholder="Min 8 characters"
            autoComplete="new-password"
            {...register('password')}
            error={errors.password?.message}
          />
          <Button
            type="submit"
            size="lg"
            className="mt-1 w-full"
            loading={mutation.isPending}
          >
            Create Account
          </Button>
        </form>

        <p className="text-center text-sm text-ch-faint mt-4 font-body">
          Already have an account?{' '}
          <Link to="/login" className="text-ch-accent hover:underline font-semibold">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
}
