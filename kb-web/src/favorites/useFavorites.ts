import { useEffect, useMemo, useSyncExternalStore } from 'react';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { FavoriteStore, unknownFavorite } from './store';
import { uniqueIds } from './api';

export function useFavorites(ids: readonly string[], resource: unknown, seeded = false) {
  const { session, state } = useSession();
  const { operations } = useDocumentOperations();
  const store = useMemo(() => new FavoriteStore(session, operations), [session, state.epoch, state.phase, operations]);
  const snapshot = useSyncExternalStore(store.subscribe, store.getSnapshot);
  const key = uniqueIds(ids).join(',');
  const active = state.phase === 'ready' && resource != null;
  useEffect(() => {
    store.bind(resource, active && key ? key.split(',') : [], seeded);
  }, [store, resource, key, active, seeded]);
  useEffect(() => () => store.dispose(), [store]);
  const current = active && Object.is(store.source, resource);
  return {
    status: (id: string) => current ? snapshot[id] ?? unknownFavorite : unknownFavorite,
    unavailable: (id: string) => current && snapshot[id]?.phase === 'unavailable',
    set: (id: string, target: boolean) => current ? store.set(id, target) : Promise.resolve(),
    check: (id: string) => current ? store.read([id]) : Promise.resolve(),
  };
}
export type Favorites = ReturnType<typeof useFavorites>;
