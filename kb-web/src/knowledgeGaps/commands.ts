import { ApiError, isAbort } from '../api';
import type { Session } from '../session';
import { gapWrite, requireGapSession } from './api';
import { gapIntent, gapIntentKey, type GapIntent, type GapSubmission } from './intents';
import { gapUnknownNotice, type GapReceipt } from './types';
export { gapIntent, gapIntentKey } from './intents';
export type { GapIntent, GapSubmission } from './intents';

export interface GapCommandState {
  readonly phase: 'idle' | 'submitting' | 'confirmed' | 'uncertain' | 'rejected'; readonly message?: string; readonly status?: number;
}
export type GapCommandOutcome = { status: 'confirmed'; value: GapReceipt } | { status: 'uncertain' } |
  { status: 'rejected'; error: ApiError } | { status: 'ignored' };
interface Binding { readonly key: string; readonly requestId: string; readonly intent: GapIntent }
/** 每个页面上下文单独持有；绑定只驻当前身份内存，不跨隐藏、离页、换号或目标变化恢复。 */
export class GapCommands {
  private state: GapCommandState = { phase: 'idle' };
  private binding: Binding | null = null;
  private controller: AbortController | null = null;
  private generation = 0;
  private lock = false;
  private closed = false;
  private scope: string;
  private epoch: number;
  private actor: string | null;
  private listeners = new Set<() => void>();
  private unsubscribe: () => void;
  constructor(private readonly session: Session, scope: string, private readonly execute: typeof gapWrite = gapWrite,
    private readonly newRequestId: () => string = () => crypto.randomUUID()) {
    this.scope = scope;
    this.epoch = session.getSnapshot().epoch;
    this.actor = session.getSnapshot().me?.user.userId ?? null;
    this.unsubscribe = session.subscribe(() => {
      const current = session.getSnapshot();
      if (current.epoch !== this.epoch || current.me?.user.userId !== this.actor || current.phase !== 'ready') {
        this.clear(); this.epoch = current.epoch; this.actor = current.me?.user.userId ?? null;
      }
    });
  }
  getSnapshot = (): GapCommandState => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private patch(state: GapCommandState) { this.state = Object.freeze(state); this.listeners.forEach(listener => listener()); }
  setScope(scope: string): void { if (scope !== this.scope) { this.clear(); this.scope = scope; } }
  /** 只放弃本地核对，不声称取消服务端提交。迟到的旧发送不能释放新上下文的互斥。 */
  clear(): void {
    this.generation++; this.controller?.abort(); this.controller = null; this.binding = null; this.lock = false;
    this.patch({ phase: 'idle' });
  }
  dispose(): void { this.closed = true; this.clear(); this.unsubscribe(); this.listeners.clear(); }
  submit(input: GapSubmission): Promise<GapCommandOutcome> {
    if (this.closed || this.lock) return Promise.resolve({ status: 'ignored' });
    let intent: GapIntent;
    try { requireGapSession(this.session); intent = gapIntent(input); }
    catch (error) {
      const failure = error instanceof ApiError ? error : new ApiError(400);
      // 非法的新表单不销毁既有未知结果的精确绑定。
      if (!this.binding) this.patch({ phase: 'rejected', message: failure.message, status: failure.status });
      return Promise.resolve({ status: 'rejected', error: failure });
    }
    const key = gapIntentKey(intent), previous = this.binding;
    const binding = previous?.key === key ? previous : Object.freeze({ key, intent, requestId: this.newRequestId() });
    this.binding = binding;
    return this.run(binding);
  }
  retry(): Promise<GapCommandOutcome> {
    if (this.closed || this.lock || !this.binding || this.state.phase !== 'uncertain') return Promise.resolve({ status: 'ignored' });
    return this.run(this.binding);
  }
  /** 未知结果只能由仍匹配原精确载荷的交互显示重试，不暴露绑定正文。 */
  matches(input: GapSubmission): boolean {
    try { return this.binding !== null && this.binding.key === gapIntentKey(gapIntent(input)); }
    catch { return false; }
  }
  private async run(binding: Binding): Promise<GapCommandOutcome> {
    const id = ++this.generation, epoch = this.session.getSnapshot().epoch, scope = this.scope, controller = new AbortController();
    this.lock = true; this.controller = controller; this.patch({ phase: 'submitting' });
    const live = () => !this.closed && id === this.generation && epoch === this.session.getSnapshot().epoch && scope === this.scope;
    try {
      requireGapSession(this.session);
      const value = await this.execute(this.session, binding.intent, binding.requestId, controller.signal);
      if (!live()) return { status: 'ignored' };
      this.binding = null; this.patch({ phase: 'confirmed' });
      return { status: 'confirmed', value };
    } catch (error) {
      if (!live()) return { status: 'ignored' };
      if (isAbort(error) || !(error instanceof ApiError) || error.status === 0 || error.status >= 500) {
        this.patch({ phase: 'uncertain', message: gapUnknownNotice }); return { status: 'uncertain' };
      }
      this.binding = null; this.patch({ phase: 'rejected', message: error.message, status: error.status });
      return { status: 'rejected', error };
    } finally { if (id === this.generation) { this.lock = false; this.controller = null; } }
  }
}
