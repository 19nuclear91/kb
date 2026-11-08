import { computed, ref, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { ApiError, isAbort, messageOf } from './api';

export function useResource<T>(load: (signal: AbortSignal) => Promise<T>, key: MaybeRefOrGetter<string>) {
  type State = { key: string; revision: number; data?: T; error?: string; status?: number; loading: boolean };
  const revision = ref(0);
  const state = shallowRef<State>({ key: toValue(key), revision: 0, loading: true });
  watch([() => toValue(key), revision], ([target, turn], _, onCleanup) => {
    const controller = new AbortController();
    let active = true;
    state.value = { key: target, revision: turn, loading: true };
    void load(controller.signal).then(data => {
      if (active && !controller.signal.aborted) {
        state.value = { key: target, revision: turn, data, loading: false };
      }
    }, error => {
      if (active && !controller.signal.aborted && !isAbort(error)) {
        state.value = { key: target, revision: turn, error: messageOf(error), status: error instanceof ApiError ? error.status : undefined, loading: false };
      }
    });
    onCleanup(() => {
      active = false;
      controller.abort();
    });
  }, { immediate: true });
  const current = computed<State>(() => {
    const target = toValue(key);
    return state.value.key === target && state.value.revision === revision.value ? state.value : { key: target, revision: revision.value, loading: true };
  });
  return {
    get key() { return current.value.key; },
    get revision() { return current.value.revision; },
    get data() { return current.value.data; },
    get error() { return current.value.error; },
    get status() { return current.value.status; },
    get loading() { return current.value.loading; },
    reload() { revision.value += 1; },
  };
}
