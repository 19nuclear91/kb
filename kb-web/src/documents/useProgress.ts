import { computed, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort, messageOf, positiveId } from '../api';
import { pendingProgress } from '../logic';
import type { Progress } from '../types';

type ProgressState = { key: string; data?: Progress; error?: string; loading: boolean; denied?: boolean };

export function useProgress(documentId: MaybeRefOrGetter<string>, versionId: MaybeRefOrGetter<string>, revision: MaybeRefOrGetter<number> = 0) {
  const auth = useSession();
  const key = computed(() => `${auth.state.epoch}/${toValue(documentId)}/${toValue(versionId)}/${toValue(revision)}`);
  const state = shallowRef<ProgressState>({ key: key.value, loading: true });
  watch([key, () => auth.session], ([target, session], _, onCleanup) => {
    const document$ = toValue(documentId), version = toValue(versionId);
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined, failures = 0;
    const stop = () => {
      if (document.visibilityState === 'hidden') {
        controller.abort();
        clearTimeout(timer);
        state.value = { key: target, loading: false };
      }
    };
    document.addEventListener('visibilitychange', stop);
    state.value = { key: target, loading: true };
    const load = async () => {
      try {
        const data = await session.json<Progress>(`/api/documents/${positiveId(document$)}/progress?versionId=${positiveId(version)}`, { signal: controller.signal });
        if (controller.signal.aborted) {
          return;
        }
        failures = 0;
        state.value = { key: target, data, loading: false };
        if (pendingProgress(data)) {
          timer = setTimeout(() => void load(), 3000);
        }
      } catch (error) {
        if (controller.signal.aborted || isAbort(error)) {
          return;
        }
        const denied = error instanceof ApiError && [401, 403, 404].includes(error.status);
        state.value = { key: target, error: messageOf(error), loading: false, denied };
        if (!denied && ++failures < 3) {
          timer = setTimeout(() => void load(), 3000 * failures);
        }
      }
    };
    if (auth.state.phase === 'ready' && document.visibilityState !== 'hidden') {
      void load();
    }
    onCleanup(() => {
      controller.abort();
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', stop);
    });
  }, { immediate: true });
  const current = computed<ProgressState>(() => state.value.key === key.value ? state.value : { key: key.value, loading: true, data: undefined, error: undefined, denied: false });
  return {
    get key() { return current.value.key; },
    get data() { return current.value.data; },
    get error() { return current.value.error; },
    get loading() { return current.value.loading; },
    get denied() { return current.value.denied; },
  };
}
