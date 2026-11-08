import type { WriteOutcome } from './hooks';

export function settle<T>(outcome: WriteOutcome<T>, done: (value: T) => void, refresh?: () => void) {
  if (outcome.status === 'confirmed') {
    done(outcome.value);
  } else if (outcome.status === 'rejected' && (outcome.error.status === 404 || outcome.error.status === 409
    || ('reason' in outcome.error && outcome.error.reason === 'DISABLED'))) {
    refresh?.();
  }
}
