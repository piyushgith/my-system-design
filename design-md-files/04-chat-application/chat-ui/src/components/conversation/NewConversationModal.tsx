import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { conversationsApi } from '../../api/conversations';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { useToastStore } from '../../store/toastStore';
import { extractApiError } from '../../api/client';
import type { ConversationType } from '../../types';

const schema = z.object({
  type: z.enum(['DIRECT', 'GROUP', 'CHANNEL']),
  name: z.string().max(255).optional(),
  memberIds: z.string().min(1, 'At least one member ID required'),
});

type FormValues = z.infer<typeof schema>;

interface Props {
  onClose: () => void;
  onCreated: (conversationId: string) => void;
}

export function NewConversationModal({ onClose, onCreated }: Props) {
  const queryClient = useQueryClient();
  const push = useToastStore((s) => s.push);
  const [selectedType, setSelectedType] = useState<ConversationType>('DIRECT');

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { type: 'DIRECT' },
  });

  const mutation = useMutation({
    mutationFn: (values: FormValues) =>
      conversationsApi.create({
        type: values.type,
        name: values.name || undefined,
        memberIds: values.memberIds.split(',').flatMap((s) => { const t = s.trim(); return t ? [t] : []; }),
      }),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
      push('success', 'Conversation created');
      onCreated(data.conversationId);
    },
    onError: (err) => {
      push('error', extractApiError(err));
    },
  });

  const onSubmit = handleSubmit((values) => {
    mutation.mutate(values);
  });

  return (
    <>
      <button
        type="button"
        className="fixed inset-0 z-40 w-full h-full bg-black/60 backdrop-blur-sm animate-fade-in cursor-default"
        aria-label="Close dialog"
        onClick={onClose}
      />
      <dialog
        open
        aria-labelledby="new-conv-title"
        onCancel={onClose}
        className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-transparent border-0 m-0 max-w-none w-full h-full pointer-events-none"
      >
      <div className="w-full max-w-md bg-ch-elevated border border-ch-border rounded-2xl shadow-2xl animate-pop-in p-6 pointer-events-auto">
        <div className="flex items-center justify-between mb-5">
          <h2 className="font-display font-bold text-lg text-ch-text">New Conversation</h2>
          <button
            type="button"
            onClick={onClose}
            className="text-ch-faint hover:text-ch-text transition-colors"
            aria-label="Close"
          >
            <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <form onSubmit={onSubmit} className="flex flex-col gap-4">
          <div>
            <p className="text-xs font-display font-semibold uppercase tracking-widest text-ch-muted mb-2">
              Type
            </p>
            <div className="grid grid-cols-3 gap-2">
              {(['DIRECT', 'GROUP', 'CHANNEL'] as ConversationType[]).map((t) => (
                <label
                  key={t}
                  className={`
                    flex flex-col items-center gap-1 p-3 rounded-xl border cursor-pointer transition-all duration-150 text-xs font-display font-semibold
                    ${selectedType === t
                      ? 'border-ch-accent bg-ch-accent-glow text-ch-accent'
                      : 'border-ch-border text-ch-muted hover:border-ch-muted hover:text-ch-text'
                    }
                  `}
                >
                  <input
                    type="radio"
                    value={t}
                    {...register('type')}
                    className="sr-only"
                    onChange={() => setSelectedType(t)}
                  />
                  {t}
                </label>
              ))}
            </div>
          </div>

          {selectedType !== 'DIRECT' && (
            <Input
              label="Name"
              placeholder={selectedType === 'GROUP' ? 'e.g. Team Alpha' : 'e.g. announcements'}
              {...register('name')}
              error={errors.name?.message}
            />
          )}

          <Input
            label="Member IDs"
            placeholder="UUID, UUID, ..."
            hint="Comma-separated user UUIDs"
            {...register('memberIds')}
            error={errors.memberIds?.message as string}
          />

          <div className="flex gap-3 justify-end pt-2">
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={mutation.isPending}>
              Create
            </Button>
          </div>
        </form>
      </div>
      </dialog>
    </>
  );
}
