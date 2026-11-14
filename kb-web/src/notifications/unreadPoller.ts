import type { Session } from '../session';
import { NOTIFICATION_CONTRACT, unreadCount } from './api';

export const DEFAULT_POLL_MS = 60_000;
export const MIN_POLL_MS = 30_000;
/** 自动查询间隔不得短于 30 秒；非法配置回落到默认值。 */
export function pollInterval(value: unknown = import.meta.env.VITE_NOTIFICATION_POLL_MS): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? Math.max(MIN_POLL_MS, Math.floor(parsed)) : DEFAULT_POLL_MS;
}

export type UnreadState =
  | { phase: 'idle' }
  | { phase: 'loading' }
  | { phase: 'ready'; count: string; asOf: string }
  | { phase: 'unavailable' };

/**
 * 同一标签页、同一会话共享一个计数订阅和至多一个在途请求。
 * 只保存在内存中；身份代次变化、隐藏、离线或退出时取消请求并清除旧数，恢复时先由会话核对身份。
 */
export class UnreadPoller {
  private state: UnreadState = { phase: 'idle' };
  private readonly listeners = new Set<() => void>();
  private consumers = 0;
  private epoch = -1;
  private ready = false;
  private paused = false;
  private generation = 0;
  private controller: AbortController | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private again = false;

  constructor(private readonly session: Session, private readonly interval = pollInterval()) {}

  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };

  /** 页面挂载时登记；返回的清理函数在卸载或身份代次变化时调用。 */
  attach(epoch: number, ready: boolean): () => void {
    if (this.consumers === 0) this.listen();
    this.consumers++;
    if (epoch !== this.epoch || ready !== this.ready) {
      this.reset();
      this.epoch = epoch; this.ready = ready; this.paused = false;
      this.set(ready ? { phase: 'loading' } : { phase: 'idle' });
      if (this.running()) this.fetch();
    }
    return () => {
      this.consumers--;
      if (this.consumers === 0) { this.unlisten(); this.reset(); this.epoch = -1; this.ready = false; this.set({ phase: 'idle' }); }
    };
  }

  /** 显式已读成功后立即核对；在途时合并为一次后续请求。 */
  refresh() {
    if (!this.running()) return;
    if (this.controller) this.again = true; else this.fetch();
  }

  private running() {
    return this.consumers > 0 && this.ready && !this.paused && document.visibilityState !== 'hidden' && navigator.onLine !== false;
  }

  private fetch() {
    if (this.controller) { this.again = true; return; }
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    const generation = this.generation, controller = new AbortController();
    this.controller = controller;
    void Promise.resolve().then(() => unreadCount(this.session, controller.signal)).then(
      value => { if (generation === this.generation && !controller.signal.aborted) this.set({ phase: 'ready', ...value }); },
      () => { if (generation === this.generation && !controller.signal.aborted) this.set({ phase: 'unavailable' }); },
    ).finally(() => {
      if (generation !== this.generation) return;
      this.controller = null;
      if (!this.running()) return;
      if (this.again) { this.again = false; this.fetch(); }
      else this.timer = setTimeout(() => { this.timer = null; this.fetch(); }, this.interval);
    });
  }

  private reset() {
    this.generation++;
    this.controller?.abort(); this.controller = null; this.again = false;
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
  }

  private set(next: UnreadState) {
    if (next.phase === this.state.phase && next.phase !== 'ready') return;
    this.state = next; this.listeners.forEach(listener => listener());
  }

  private readonly onVisibility = () => {
    if (document.visibilityState === 'hidden') { this.pause({ phase: 'idle' }); return; }
    this.resume();
  };
  private readonly onOffline = () => this.pause({ phase: 'unavailable' });
  private readonly onOnline = () => this.resume();

  /** 后台或离线不保留旧数字作为当前事实。 */
  private pause(state: UnreadState) {
    this.reset(); this.paused = true; this.set(state);
  }

  /** 恢复必须先核对身份；身份确认后的新代次通过 attach 重新开始。 */
  private resume() {
    if (!this.paused || this.consumers === 0) return;
    void Promise.resolve().then(() => this.session.refresh()).catch(() => {});
  }

  private listen() {
    document.addEventListener('visibilitychange', this.onVisibility);
    window.addEventListener('offline', this.onOffline);
    window.addEventListener('online', this.onOnline);
  }

  private unlisten() {
    document.removeEventListener('visibilitychange', this.onVisibility);
    window.removeEventListener('offline', this.onOffline);
    window.removeEventListener('online', this.onOnline);
  }
}

const pollers = new WeakMap<object, Map<string, UnreadPoller>>();
export function pollerFor(session: Session): UnreadPoller {
  let contracts = pollers.get(session);
  if (!contracts) { contracts = new Map(); pollers.set(session, contracts); }
  let poller = contracts.get(NOTIFICATION_CONTRACT);
  if (!poller) { poller = new UnreadPoller(session); contracts.set(NOTIFICATION_CONTRACT, poller); }
  return poller;
}
