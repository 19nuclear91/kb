import { isAbort } from '../api';
import type { Session, SsoHandoff } from '../session';
import { authorizationUrl, loginResult, SsoError, type Intent, type Platform, type Bound } from './api';
import { browserStorage, HintStorageError, SsoHints, type FlowHint, type StorageLike } from './storage';

export interface FlowState {
  phase: 'idle' | 'starting' | 'pending' | 'confirmed' | 'rejected' | 'uncertain' | 'invalid';
  intent?: Intent; platform?: Platform; returnPath?: string; message?: string; code?: string; correlationId?: string;
}
export type FlowSession = Pick<Session, 'sso' | 'beginSsoHandoff' | 'getSnapshot'>;
const missing = '授权流程已失效，请返回登录页或个人账户重新发起。';
const uncertain = '本次授权结果尚未确认，请核对状态或重新发起。';
export class SsoFlow {
  readonly hints: SsoHints;
  private state: FlowState = { phase: 'idle' };
  private listeners = new Set<() => void>();
  private pending: Promise<void> | null = null;
  private controller: AbortController | null = null;
  private handoff: SsoHandoff | null = null;
  constructor(private session: FlowSession, storage: StorageLike = browserStorage,
      private navigate: (url: string) => void = url => window.location.assign(url), now?: () => number) {
    this.hints = new SsoHints(storage, now);
  }
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private patch(state: FlowState) { this.state = state; this.listeners.forEach(listener => listener()); }
  private context(hint: FlowHint) { return { intent: hint.intent, platform: hint.platform, returnPath: hint.returnPath }; }
  private failed(hint: FlowHint, error: unknown) {
    const current = this.hints.readFlow();
    if (current?.attemptKey !== hint.attemptKey) {
      if (!current) this.patch({ phase: 'invalid', message: missing });
      return;
    }
    const safe = error instanceof SsoError ? error : null;
    const unknown = isAbort(error) || !(error instanceof SsoError || error instanceof HintStorageError) || safe?.uncertain === true;
    try {
      this.hints.update(hint, unknown ? 'UNCERTAIN' : 'REJECTED');
      if (safe?.code === 'SSO_BINDING_REQUIRED' && hint.intent === 'LOGIN') this.hints.navigateTo('/account', hint.platform);
    } catch { /* UI 仍安全结束，不再次发送旧交换。 */ }
    this.patch({ ...this.context(hint), phase: unknown ? 'uncertain' : 'rejected',
      message: unknown ? uncertain : error instanceof Error ? error.message : missing, code: safe?.code, correlationId: safe?.correlationId });
  }
  async start(intent: Intent, platform: Platform, password?: string, returnPath: unknown = '/documents'): Promise<void> {
    if (this.state.phase === 'starting' || this.state.phase === 'pending') return;
    this.pending = null;
    let hint: FlowHint;
    try { hint = this.hints.start(intent, platform, returnPath); }
    catch { this.patch({ phase: 'rejected', message: new HintStorageError().message }); return; }
    const controller = new AbortController(); this.controller = controller;
    this.patch({ ...this.context(hint), phase: 'starting' });
    try {
      const result = await this.session.sso(intent === 'LOGIN' ? { kind: 'login', platform } : { kind: 'bind', platform, password: password ?? '' }, controller.signal);
      if (controller.signal.aborted || this.hints.readFlow()?.attemptKey !== hint.attemptKey) throw new DOMException('请求已停止', 'AbortError');
      const url = authorizationUrl(result.authorizationUrl, platform);
      this.hints.update(hint, 'ARMED');
      this.navigate(url);
      this.patch({ ...this.context(hint), phase: 'idle' });
    } catch (error) { this.failed(hint, error); }
    finally { if (this.controller === controller) this.controller = null; }
  }
  exchange(): Promise<void> {
    if (this.pending) return this.pending;
    const hint = this.hints.readFlow();
    if (!hint || hint.phase !== 'ARMED') {
      if (hint?.phase === 'CONFIRMED') this.patch({ ...this.context(hint), phase: 'confirmed' });
      else this.patch({ ...(hint ? this.context(hint) : {}), phase: hint && ['CONSUMING', 'UNCERTAIN'].includes(hint.phase) ? 'uncertain' : 'invalid', message: hint ? uncertain : missing });
      return Promise.resolve();
    }
    this.pending = Promise.resolve().then(() => this.consume(hint));
    this.patch({ ...this.context(hint), phase: 'pending' });
    return this.pending;
  }
  private async consume(hint: FlowHint) {
    const controller = new AbortController(); this.controller = controller;
    let handoff: SsoHandoff | null = null, finished = false;
    try {
      this.hints.update(hint, 'CONSUMING');
      handoff = this.session.beginSsoHandoff(); this.handoff = handoff;
      const signal = AbortSignal.any([controller.signal, handoff.signal]);
      const result = await this.session.sso({ kind: 'exchange', intent: hint.intent, platform: hint.platform }, signal);
      if (signal.aborted || !handoff.valid() || this.hints.readFlow()?.attemptKey !== hint.attemptKey) throw new DOMException('请求已停止', 'AbortError');
      let accepted;
      if (hint.intent === 'LOGIN') accepted = loginResult(result);
      else {
        const bound = result as Bound;
        if (bound.bound !== true || bound.platform !== hint.platform || bound.connectionId !== hint.platform || 'tokenValue' in bound || 'user' in bound) throw new SsoError(503, undefined, true);
      }
      const valid = await handoff.finish(accepted); finished = true;
      if (!valid || controller.signal.aborted || this.hints.readFlow()?.attemptKey !== hint.attemptKey) throw new DOMException('请求已停止', 'AbortError');
      this.hints.update(hint, 'CONFIRMED');
      this.patch({ ...this.context(hint), phase: 'confirmed' });
    } catch (error) { this.failed(hint, error); }
    finally {
      if (!finished && handoff) await handoff.finish().catch(() => {});
      if (this.controller === controller) this.controller = null;
      if (this.handoff === handoff) this.handoff = null;
    }
  }
  /** 路由真正离开时结束；组件重复挂载不调用此方法。 */
  leave() {
    this.controller?.abort(); this.handoff?.cancel();
    const hint = this.hints.readFlow();
    if (hint && ['STARTING', 'CONSUMING'].includes(hint.phase)) this.failed(hint, new DOMException('请求已停止', 'AbortError'));
  }
}
const flows = new WeakMap<Session, SsoFlow>();
export function getSsoFlow(session: Session): SsoFlow {
  let flow = flows.get(session);
  if (!flow) { flow = new SsoFlow(session); flows.set(session, flow); }
  return flow;
}
