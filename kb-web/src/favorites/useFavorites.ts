import { computed, markRaw, onScopeDispose, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { useExternalStore } from '../useExternalStore';
import { FavoriteStore, unknownFavorite } from './store';
import { uniqueIds } from './api';

/** 页面级收藏状态：随会话代次、身份阶段与文档操作上下文重建，资源对象变化时重新绑定。 */
export function useFavorites(ids: MaybeRefOrGetter<readonly string[]>, resource: MaybeRefOrGetter<unknown>, seeded = false) {
  const auth = useSession();
  const documents = useDocumentOperations();
  const store = computed(() => {
    void auth.state.epoch;
    void auth.state.phase;
    return markRaw(new FavoriteStore(auth.session, documents.operations));
  });
  const snapshot = useExternalStore(() => store.value);
  const key = computed(() => uniqueIds(toValue(ids)).join(','));
  const active = computed(() => auth.state.phase === 'ready' && toValue(resource) != null);
  watch(store, (current, _, onCleanup) => onCleanup(() => current.dispose()), { immediate: true });
  onScopeDispose(() => store.value.dispose());
  watch([store, () => toValue(resource), key, active], ([current, source, list, enabled]) => {
    current.bind(source, enabled && list ? list.split(',') : [], seeded);
  }, { immediate: true });
  const current = computed(() => {
    void snapshot.value;
    return active.value && Object.is(store.value.source, toValue(resource));
  });
  return {
    status: (id: string) => current.value ? snapshot.value[id] ?? unknownFavorite : unknownFavorite,
    unavailable: (id: string) => current.value && snapshot.value[id]?.phase === 'unavailable',
    set: (id: string, target: boolean) => current.value ? store.value.set(id, target) : Promise.resolve(),
    check: (id: string) => current.value ? store.value.read([id]) : Promise.resolve(),
  };
}
export type Favorites = ReturnType<typeof useFavorites>;
