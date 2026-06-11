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
  login: z.string().min(1, 'Username or email required'),
  password: z.string().min(1, 'Password required'),
});

type FormValues = z.infer<typeof schema>;

export function LoginPage() {
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
    mutationFn: authApi.login,
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
        {/* Logo */}
        <div className="text-center mb-8">
          <h1 className="font-display font-black text-4xl shimmer-text tracking-tight">
            Obsidian
          </h1>
          <p className="text-ch-faint text-sm mt-2 font-body">Sign in to continue</p>
        </div>

        <form
          onSubmit={handleSubmit((v) => mutation.mutate(v))}
          className="bg-ch-surface border border-ch-border rounded-2xl p-6 flex flex-col gap-4 shadow-2xl"
        >
          <Input
            label="Username or Email"
            placeholder="you@example.com"
            autoComplete="username"
            {...register('login')}
            error={errors.login?.message}
          />
          <Input
            label="Password"
            type="password"
            placeholder="••••••••"
            autoComplete="current-password"
            {...register('password')}
            error={errors.password?.message}
          />
          <Button
            type="submit"
            size="lg"
            className="mt-1 w-full"
            loading={mutation.isPending}
          >
            Sign in
          </Button>
        </form>

        <p className="text-center text-sm text-ch-faint mt-4 font-body">
          No account?{' '}
          <Link to="/register" className="text-ch-accent hover:underline font-semibold">
            Register
          </Link>
        </p>
      </div>
    </div>
  );
}
