import { useAdminWrite, type WriteOutcome } from '../hooks';
import type { WriteIntent } from '../api';
import { lifecycleWrite } from './api';

export function useLifecycleWrite(scope: string, callbacks: { close: () => void; done: (message: string) => void; refresh: () => void }, confirmed: (value: unknown) => string, invalidate?: () => void) {
  const write = useAdminWrite(scope, lifecycleWrite);
  function after(outcome: WriteOutcome<unknown>) {
    if (outcome.status === 'confirmed') {
      callbacks.done(confirmed(outcome.value));
    } else if (outcome.status === 'rejected') {
      invalidate?.();
      if ([404, 409].includes(outcome.error.status)) {
        callbacks.refresh();
      }
    }
  }
  function restart() {
    if (!write.busy) {
      invalidate?.(); write.reset(); callbacks.close(); callbacks.refresh();
    }
  }
  // 不展开 getter 快照，保持 facade 在每次渲染读取最新写入状态。
  const view = new Proxy(write, { get(target, property, receiver) { return property === 'reset' ? restart : Reflect.get(target, property, receiver); } });
  return { write: view, submit: async (build: () => WriteIntent) => after(await write.submit(build)), retry: async () => after(await write.retry()) };
}
