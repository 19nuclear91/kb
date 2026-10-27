import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, isAbort } from '../api';
import { useSession } from '../SessionProvider';
import type { CommentItem, CommentPage } from './api';

type ListState = {
  base: string; revision: number; items: CommentItem[]; cursor: string | null; canCreate: boolean;
  loading: boolean; failed: boolean; status?: number; loaded: boolean; revalidating: boolean;
  /** 同一游标连续失败次数；成功或首页读取后归零。大于 0 期间（含重试在途）对外报告 incomplete。 */
  misses: number;
};
const empty = (base: string, revision: number): ListState =>
  ({ base, revision, items: [], cursor: null, canCreate: false, loading: true, failed: false, loaded: false, revalidating: false, misses: 0 });

/**
 * 一条独立的服务端游标列表（根评论或单个根下的回复）。键含会话 epoch 与固定目标，变化即中止并清空；
 * 首页失败不保留旧讨论、不启用写入；续页失败保留已显示项并可按同一游标重试，不解释为已到末页。
 * 同一目标重读首页期间 revalidating 为真：已确认内容继续显示，但调用方须暂停发表，直到资格重新确认。
 * load 为 null 表示当前不读取（例如资源已拒绝），立即清空。
 */
export function useCommentList(load: ((cursor: string | null, signal: AbortSignal) => Promise<CommentPage>) | null, scope: string) {
  const { state: identity } = useSession();
  const base = `${identity.epoch}|${scope}`, enabled = load !== null;
  const latest = useRef(load); latest.current = load;
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<ListState>(() => empty(base, revision));
  const active = useRef<AbortController | null>(null);
  const fetchPage = useCallback((key: string, at: number, cursor: string | null, previous: ListState | null) => {
    const loader = latest.current;
    if (!loader) return;
    active.current?.abort();
    const controller = new AbortController(); active.current = controller;
    const kept = previous && previous.base === key ? previous : null;
    // 首页重读期间资格尚未重新确认：保留已显示内容，但暂停写入；续页不改变已确认的资格。
    setState(kept ? { ...kept, revision: at, loading: true, failed: false, status: undefined, revalidating: cursor === null && kept.loaded }
      : empty(key, at));
    void new Promise<CommentPage>(resolve => resolve(loader(cursor, controller.signal))).then(page => {
      if (controller.signal.aborted) return;
      setState(current => {
        if (current.base !== key || current.revision !== at) return current;
        const items = cursor === null ? page.items : [...current.items, ...page.items];
        if (new Set(items.map(item => item.commentId)).size !== items.length)
          return { ...current, loading: false, failed: true, status: 503, misses: current.misses + 1,
            ...(cursor === null ? { items: [], canCreate: false, loaded: false, revalidating: false, misses: 0 } : {}) };
        return { base: key, revision: at, items, cursor: page.hasMore ? page.nextCursor : null, canCreate: page.canCreate,
          loading: false, failed: false, loaded: true, revalidating: false, misses: 0 };
      });
    }, error => {
      if (controller.signal.aborted || isAbort(error)) return;
      const status = error instanceof ApiError ? error.status : undefined;
      setState(current => current.base !== key || current.revision !== at ? current
        : cursor === null ? { ...empty(key, at), loading: false, failed: true, status }
          : { ...current, loading: false, failed: true, status, misses: current.misses + 1 });
    });
  }, []);
  const snapshot = useRef(state); snapshot.current = state;
  useEffect(() => {
    if (!enabled) { active.current?.abort(); setState({ ...empty(base, revision), loading: false }); return; }
    // 同一目标的显式重读保留已确认内容直到新结果返回；身份或目标变化则立即清空。
    fetchPage(base, revision, null, snapshot.current.base === base ? snapshot.current : null);
    return () => { active.current?.abort(); active.current = null; };
  }, [base, revision, enabled, fetchPage]);
  // 同一目标重读期间沿用已确认内容并标记读取中，避免发表框与回执提示在重读瞬间卸载；目标或身份变化立即清空。
  const current: ListState = !enabled ? { ...empty(base, revision), loading: false }
    : state.base !== base ? empty(base, revision)
      : state.revision === revision ? state : { ...state, revision, loading: true, failed: false, status: undefined, revalidating: state.loaded };
  const more = () => {
    if (enabled && !current.loading && current.cursor && current === state) fetchPage(base, revision, current.cursor, current);
  };
  /**
   * 续页失败后先按同一游标重试；同一游标第二次失败或游标被拒绝（400）时改为从首页重读，
   * 避免确定性故障让列表与发表一直停在续页失败。首页失败则整体重读。
   */
  const retry = () => {
    if (!enabled || current.loading) return;
    if (current.loaded && current.cursor && current.misses < 2 && current.status !== 400) fetchPage(base, revision, current.cursor, current);
    else setRevision(value => value + 1);
  };
  const reload = useCallback(() => setRevision(value => value + 1), []);
  /** 本人删除确认后立即在本地移除正文与作者，不等重读；服务端投影随后以墓碑覆盖。 */
  const tombstone = useCallback((id: string) => setState(value => value.base !== base ? value : {
    ...value, items: value.items.map(item => item.commentId === id
      ? { ...item, deleted: true, author: null, body: null, isOwn: false, canDelete: false } : item),
  }), [base]);
  // incomplete：续页失败后、同一游标重新读取成功前为真（含重试在途期间），调用方须暂停发表。
  return { items: current.items, loading: current.loading, failed: current.failed, status: current.status, loaded: current.loaded,
    canCreate: current.loaded && current.canCreate, revalidating: current.revalidating, hasMore: current.loaded && current.cursor !== null,
    incomplete: current.misses > 0, more, retry, reload, tombstone };
}
export type CommentList = ReturnType<typeof useCommentList>;
