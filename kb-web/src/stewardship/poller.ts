import type { Session } from '../session';
import { validityApi } from '../validity/api';

export const DEFAULT_POLL_MS = 300_000, MIN_POLL_MS = 60_000;
export function pollInterval(value: unknown = import.meta.env.VITE_STEWARDSHIP_POLL_MS): number {
  const parsed = Number(value); return Number.isFinite(parsed) && parsed > 0 ? Math.max(MIN_POLL_MS, Math.floor(parsed)) : DEFAULT_POLL_MS;
}
export type CountState = { phase: 'idle' | 'loading' | 'unavailable' } | { phase: 'ready'; count: string; asOfDate: string };
export function countLabel(state: CountState): string { return state.phase === 'unavailable' ? '?' : state.phase === 'ready' && state.count !== '0' ? BigInt(state.count) > 99n ? '99+' : state.count : ''; }
/** 每个会话共享，后台、离线和身份变化取消读取并清除旧数；恢复先核对身份。 */
export class StewardshipPoller {
  private state: CountState = { phase: 'idle' };
  private listeners = new Set<() => void>();
  private consumers = 0; private epoch = -1; private ready = false; private paused = false; private generation = 0;
  private controller: AbortController | null = null; private timer: ReturnType<typeof setTimeout> | null = null; private again = false;
  constructor(private readonly session: Session, private readonly interval = pollInterval()) {}
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  attach(epoch: number, ready: boolean): () => void {
    if (this.consumers === 0) this.listen(); this.consumers++;
    if (epoch !== this.epoch || ready !== this.ready) { this.reset(); this.epoch = epoch; this.ready = ready; this.paused = false; this.set(ready ? { phase: 'loading' } : { phase: 'idle' }); if (this.running()) this.fetch(); }
    return () => { this.consumers--; if (this.consumers === 0) { this.unlisten(); this.reset(); this.epoch = -1; this.ready = false; this.set({ phase: 'idle' }); } };
  }
  refresh() { if (!this.running()) return; if (this.controller) this.again = true; else this.fetch(); }
  private running() { return this.consumers > 0 && this.ready && !this.paused && document.visibilityState !== 'hidden' && navigator.onLine !== false; }
  private fetch() {
    if (this.controller) { this.again = true; return; } if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    const generation = this.generation, controller = new AbortController(); this.controller = controller;
    void Promise.resolve().then(() => validityApi.count(this.session, controller.signal)).then(
      value => { if (generation === this.generation && !controller.signal.aborted) this.set({ phase: 'ready', ...value }); },
      () => { if (generation === this.generation && !controller.signal.aborted) this.set({ phase: 'unavailable' }); },
    ).finally(() => { if (generation !== this.generation) return; this.controller = null; if (!this.running()) return;
      if (this.again) { this.again = false; this.fetch(); } else this.timer = setTimeout(() => { this.timer = null; this.fetch(); }, Math.max(MIN_POLL_MS, this.interval)); });
  }
  private reset() { this.generation++; this.controller?.abort(); this.controller = null; this.again = false; if (this.timer) { clearTimeout(this.timer); this.timer = null; } }
  private set(state: CountState) { this.state = state; this.listeners.forEach(listener => listener()); }
  private pause(state: CountState) { this.reset(); this.paused = true; this.set(state); }
  private resume() { if (this.paused && this.consumers > 0) void Promise.resolve().then(() => this.session.refresh()).catch(() => {}); }
  private onVisibility = () => { if (document.visibilityState === 'hidden') this.pause({ phase: 'idle' }); else this.resume(); };
  private onOffline = () => this.pause({ phase: 'unavailable' });
  private onOnline = () => this.resume();
  private listen() { document.addEventListener('visibilitychange', this.onVisibility); window.addEventListener('offline', this.onOffline); window.addEventListener('online', this.onOnline); }
  private unlisten() { document.removeEventListener('visibilitychange', this.onVisibility); window.removeEventListener('offline', this.onOffline); window.removeEventListener('online', this.onOnline); }
}
const pollers = new WeakMap<Session, StewardshipPoller>();
export function pollerFor(session: Session) { let poller = pollers.get(session); if (!poller) { poller = new StewardshipPoller(session); pollers.set(session, poller); } return poller; }
