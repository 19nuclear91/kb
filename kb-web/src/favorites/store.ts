import { ApiError, isAbort, messageOf } from '../api';
import type { Session } from '../session';
import { favoriteStates, setFavorite, uniqueIds } from './api';

export interface FavoriteStatus {
  phase: 'loading' | 'ready' | 'error' | 'uncertain' | 'unavailable';
  favorited?: boolean;
  busy?: boolean;
  message?: string;
}
export const unknownFavorite: FavoriteStatus = { phase: 'loading' };

/** 仅属于一个页面与会话代次，所有回调同时核对资源代次和文档操作代次。 */
export class FavoriteStore {
  source: unknown;
  private snapshot: Record<string, FavoriteStatus> = {};
  private revisions = new Map<string, number>();
  private generation = 0;
  private disposed = false;
  private controllers = new Set<AbortController>();
  private listeners = new Set<() => void>();

  constructor(private session: Session) {}
  getSnapshot = () => this.snapshot;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private publish() { this.snapshot = { ...this.snapshot }; this.listeners.forEach(listener => listener()); }
  private next(id: string) { const next = (this.revisions.get(id) ?? 0) + 1; this.revisions.set(id, next); return next; }
  private current(generation: number, id: string, revision: number) {
    return !this.disposed && generation === this.generation && revision === this.revisions.get(id);
  }

  bind(source: unknown, ids: readonly string[], seeded = false) {
    // effect 在 StrictMode 中可重新建立同一页面绑定。
    if (this.disposed || !Object.is(source, this.source)) {
      this.controllers.forEach(controller => controller.abort()); this.controllers.clear();
      this.generation++; this.disposed = false; this.source = source; this.snapshot = {}; this.revisions.clear();
    }
    const missing = uniqueIds(ids).filter(id => !this.snapshot[id]);
    missing.forEach(id => { this.snapshot[id] = seeded ? { phase: 'ready', favorited: true } : unknownFavorite; });
    this.publish();
    if (!seeded) for (let i = 0; i < missing.length; i += 100) void this.read(missing.slice(i, i + 100));
  }

  async read(requested: readonly string[]) {
    if (this.disposed) return;
    const ids = uniqueIds(requested).filter(id => this.snapshot[id] && !this.snapshot[id].busy);
    if (!ids.length) return;
    const generation = this.generation, revisions = new Map<string, number>(), uncertain = new Set<string>();
    for (const id of ids) {
      revisions.set(id, this.next(id));
      const before = this.snapshot[id];
      if (before.phase === 'uncertain') uncertain.add(id);
      // 已确认状态的后台重读允许被明确写入取代，旧读回调将因代次不同被丢弃。
      if (before.phase !== 'ready') this.snapshot[id] = { ...before, busy: true, phase: uncertain.has(id) ? 'uncertain' : 'loading' };
    }
    this.publish();
    const controller = new AbortController(); this.controllers.add(controller);
    try {
      const rows = await favoriteStates(this.session, ids, controller.signal);
      const byId = new Map(rows.map(row => [row.documentId, row.favorited]));
      for (const id of ids) if (this.current(generation, id, revisions.get(id)!)) {
        this.snapshot[id] = byId.has(id) ? { phase: 'ready', favorited: byId.get(id)! } : { phase: 'unavailable' };
      }
    } catch (error) {
      if (!isAbort(error)) for (const id of ids) if (this.current(generation, id, revisions.get(id)!)) {
        this.snapshot[id] = { phase: uncertain.has(id) ? 'uncertain' : 'error', message: messageOf(error) };
      }
    } finally {
      this.controllers.delete(controller);
      if (!this.disposed && generation === this.generation) this.publish();
    }
  }

  async set(id: string, target: boolean) {
    const before = this.snapshot[id];
    if (this.disposed || before?.phase !== 'ready' || before.busy) return;
    const generation = this.generation, revision = this.next(id);
    this.snapshot[id] = { ...before, busy: true, message: undefined }; this.publish();
    const controller = new AbortController(); this.controllers.add(controller);
    try {
      const result = await setFavorite(this.session, id, target, controller.signal);
      if (this.current(generation, id, revision)) this.snapshot[id] = { phase: 'ready', favorited: result.favorited };
    } catch (error) {
      if (this.current(generation, id, revision) && !isAbort(error)) {
        if (error instanceof ApiError && error.status === 404) this.snapshot[id] = { phase: 'unavailable' };
        else if (error instanceof ApiError && [400, 401, 403, 415].includes(error.status)) {
          this.snapshot[id] = { phase: 'error', message: messageOf(error) };
        } else this.snapshot[id] = { phase: 'uncertain', message: '提交结果尚未确认，请核对当前收藏状态。' };
      }
    } finally {
      this.controllers.delete(controller);
      if (!this.disposed && generation === this.generation) this.publish();
    }
  }

  dispose() {
    this.disposed = true; this.generation++;
    this.controllers.forEach(controller => controller.abort()); this.controllers.clear();
    this.snapshot = {}; this.revisions.clear(); this.publish();
  }
}
