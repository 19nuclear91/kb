import { onScopeDispose } from 'vue';
import { useExternalStore } from '../useExternalStore';
import type { ImportQueue } from './queue';

/** Vue 只订阅快照；文件与请求控制权留在队列核心。 */
export function useImportQueue(queue: ImportQueue) {
  const state = useExternalStore(() => queue);
  onScopeDispose(() => queue.dispose());
  return state;
}
