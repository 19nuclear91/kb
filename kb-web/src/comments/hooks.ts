import { computed, ref, shallowRef, toValue, unref, watch, type MaybeRef, type MaybeRefOrGetter } from 'vue';
import { ApiError, isAbort } from '../api';
import { useSession } from '../SessionProvider';
import type { CommentItem, CommentPage } from './api';

type ListState = {
  base: string; revision: number; items: CommentItem[]; cursor: string | null; canCreate: boolean;
  loading: boolean; failed: boolean; status?: number; loaded: boolean; revalidating: boolean; misses: number;
};
const empty = (base: string, revision: number): ListState => ({ base, revision, items: [], cursor: null, canCreate: false, loading: true, failed: false, loaded: false, revalidating: false, misses: 0 });
type Loader = (cursor: string | null, signal: AbortSignal) => Promise<CommentPage>;

/** 根评论或回复的独立游标列表；首页重读保留已确认内容但暂停发表，身份/目标变化立即清空。 */
export function useCommentList(load: MaybeRef<Loader | null>, scope: MaybeRefOrGetter<string>) {
  const auth = useSession();
  const base = computed(() => `${auth.state.epoch}|${toValue(scope)}`), enabled = computed(() => unref(load) !== null);
  const revision = ref(0), state = shallowRef<ListState>(empty(base.value, 0));
  let active: AbortController | null = null;
  function fetchPage(key: string, at: number, cursor: string | null, previous: ListState | null) {
    const loader = unref(load);
    if (!loader) {
      return;
    }
    active?.abort();
    const controller = new AbortController();
    active = controller;
    const kept = previous && previous.base === key ? previous : null;
    state.value = kept ? { ...kept, revision: at, loading: true, failed: false, status: undefined, revalidating: cursor === null && kept.loaded } : empty(key, at);
    void new Promise<CommentPage>(resolve => resolve(loader(cursor, controller.signal))).then(page => {
      const current = state.value;
      if (controller.signal.aborted || current.base !== key || current.revision !== at) {
        return;
      }
      const items = cursor === null ? page.items : [...current.items, ...page.items];
      if (new Set(items.map(item => item.commentId)).size !== items.length) {
        state.value = { ...current, loading: false, failed: true, status: 503, misses: current.misses + 1,
          ...(cursor === null ? { items: [], canCreate: false, loaded: false, revalidating: false, misses: 0 } : {}) };
        return;
      }
      state.value = { base: key, revision: at, items, cursor: page.hasMore ? page.nextCursor : null, canCreate: page.canCreate, loading: false, failed: false, loaded: true, revalidating: false, misses: 0 };
    }, error => {
      const current = state.value;
      if (controller.signal.aborted || isAbort(error) || current.base !== key || current.revision !== at) {
        return;
      }
      const status = error instanceof ApiError ? error.status : undefined;
      state.value = cursor === null ? { ...empty(key, at), loading: false, failed: true, status }
        : { ...current, loading: false, failed: true, status, misses: current.misses + 1 };
    });
  }
  watch([base, revision, enabled], ([key, at, on], _, onCleanup) => {
    if (!on) {
      active?.abort();
      state.value = { ...empty(key, at), loading: false };
      return;
    }
    fetchPage(key, at, null, state.value.base === key ? state.value : null);
    onCleanup(() => {
      active?.abort();
      active = null;
    });
  }, { immediate: true });
  const current = computed<ListState>(() => !enabled.value ? { ...empty(base.value, revision.value), loading: false }
    : state.value.base !== base.value ? empty(base.value, revision.value)
      : state.value.revision === revision.value ? state.value : { ...state.value, revision: revision.value, loading: true, failed: false, status: undefined, revalidating: state.value.loaded });
  function more() {
    const value = current.value;
    if (enabled.value && !value.loading && value.cursor && value === state.value) {
      fetchPage(base.value, revision.value, value.cursor, value);
    }
  }
  function retry() {
    const value = current.value;
    if (!enabled.value || value.loading) {
      return;
    }
    if (value.loaded && value.cursor && value.misses < 2 && value.status !== 400) {
      fetchPage(base.value, revision.value, value.cursor, value);
    } else {
      revision.value++;
    }
  }
  function tombstone(id: string) {
    if (state.value.base === base.value) {
      state.value = { ...state.value, items: state.value.items.map(item => item.commentId === id ? { ...item, deleted: true, author: null, body: null, isOwn: false, canDelete: false } : item) };
    }
  }
  return {
    get items() { return current.value.items; },
    get loading() { return current.value.loading; },
    get failed() { return current.value.failed; },
    get status() { return current.value.status; },
    get loaded() { return current.value.loaded; },
    get canCreate() { return current.value.loaded && current.value.canCreate; },
    get revalidating() { return current.value.revalidating; },
    get hasMore() { return current.value.loaded && current.value.cursor !== null; },
    get incomplete() { return current.value.misses > 0; },
    more, retry, reload: () => { revision.value++; }, tombstone,
  };
}
export type CommentList = ReturnType<typeof useCommentList>;
