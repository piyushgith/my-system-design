import { AppLayout } from '../components/layout/AppLayout';
import { ErrorBoundary } from '../components/ui/ErrorBoundary';

export function ChatPage() {
  return (
    <ErrorBoundary>
      <AppLayout />
    </ErrorBoundary>
  );
}
