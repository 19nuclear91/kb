import { shallowRef, watch, type ShallowRef } from 'vue';

export interface ExternalStore<T> { subscribe: (listener: () => void) => () => void; getSnapshot: () => T }

/**
 * 对应 React 的 useSyncExternalStore：订阅后立即重读一次，避免订阅前的变化丢失；
 * 来源（如按会话取得的轮询器）变化时改订新来源，作用域结束时退订。
 */
export function useExternalStore<T>(source: () => ExternalStore<T>): Readonly<ShallowRef<T>> {
  const snapshot = shallowRef(source().getSnapshot());
  watch(source, (store, _, onCleanup) => {
    onCleanup(store.subscribe(() => { snapshot.value = store.getSnapshot(); }));
    snapshot.value = store.getSnapshot();
  }, { immediate: true, flush: 'sync' });
  return snapshot;
}
