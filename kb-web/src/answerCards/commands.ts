import { shallowReactive } from 'vue';
import type { Session } from '../session';
import { cardApi } from './api';
import { CardError } from './protocol';
import type { Intent, Receipt } from './types';

export interface PendingCommand { intent: Intent; requestId: string; phase: 'sending' | 'unknown' | 'checking' | 'committed'; receipt: Receipt | null; notice: string }
export interface CommandOutcome { kind: 'committed' | 'unknown' | 'rejected' | 'stale'; status?: number; receipt?: Receipt }
const stores = new WeakMap<Session, CardCommands>();
/** 当前Session内存中的原意图；组件关闭不清空，认证代次改变立即作废全部。 */
export class CardCommands {
  readonly entries = shallowReactive(new Map<string, PendingCommand>());
  private controllers = new Map<string, AbortController>();
  private epoch: number;
  constructor(private session: Session) {
    this.epoch = session.getSnapshot().epoch;
    session.subscribe(() => {
      if (this.epoch !== session.getSnapshot().epoch || session.getSnapshot().phase !== 'ready') {
        this.epoch = session.getSnapshot().epoch;
        for (const controller of this.controllers.values()) controller.abort();
        this.controllers.clear(); this.entries.clear();
      }
    });
  }
  start(key: string, intent: Intent): Promise<CommandOutcome> {
    if (this.entries.has(key)) throw new CardError(409);
    const pending: PendingCommand = { intent: JSON.parse(JSON.stringify(intent)) as Intent, requestId: crypto.randomUUID(), phase: 'sending', receipt: null, notice: '' };
    this.entries.set(key, pending);
    return this.perform(key, false);
  }
  retry(key: string): Promise<CommandOutcome> { return this.perform(key, false); }
  check(key: string): Promise<CommandOutcome> { return this.perform(key, true); }
  abandon(key: string): void { this.controllers.get(key)?.abort(); this.controllers.delete(key); this.entries.delete(key); }
  /** 页面守卫卸载之前按本次已确认权限收缩清理私有原载荷。 */
  revoke(allowed: (intent: Intent) => boolean): void {
    for (const [key, value] of this.entries) if (!allowed(value.intent)) this.abandon(key);
  }
  acknowledge(key: string): void { if (this.entries.get(key)?.phase === 'committed') this.entries.delete(key); }
  private async perform(key: string, checking: boolean): Promise<CommandOutcome> {
    const original = this.entries.get(key);
    if (!original || this.controllers.has(key) || original.phase === 'committed') throw new CardError(409);
    const controller = new AbortController(), epoch = this.epoch;
    const pending = { ...original, phase: checking ? 'checking' as const : 'sending' as const, notice: '' };
    this.entries.set(key, pending); this.controllers.set(key, controller);
    const active = () => this.entries.get(key) === pending && this.epoch === epoch && !controller.signal.aborted;
    try {
      const result = checking ? await cardApi.command(this.session, pending.intent, pending.requestId, controller.signal)
        : { status: 'COMMITTED' as const, receipt: await cardApi.write(this.session, pending.intent, pending.requestId, controller.signal) };
      if (!active()) return { kind: 'stale' };
      if (result.status === 'NOT_FOUND') {
        this.entries.set(key, { ...pending, phase: 'unknown', notice: '本次未查到提交记录，原请求仍可能在途。请继续核对或使用原绑定重试。' });
        return { kind: 'unknown' };
      }
      this.entries.set(key, { ...pending, phase: 'committed', receipt: result.receipt, notice: '已核对持久提交结果，请重新读取当前事实。' });
      return { kind: 'committed', receipt: result.receipt! };
    } catch (error) {
      if (!active()) return { kind: 'stale' };
      const status = error instanceof CardError ? error.status : 0;
      // 核对失败以及依赖失败不能证明原写入未提交，继续保留原绑定。
      if ([401, 403, 404].includes(status) || !checking && [400, 409, 413].includes(status)) {
        this.entries.delete(key);
        return { kind: 'rejected', status };
      }
      this.entries.set(key, { ...pending, phase: 'unknown', notice: '结果尚未确认；新操作已冻结，请主动核对或按原绑定重试。' });
      return { kind: 'unknown' };
    } finally { if (this.controllers.get(key) === controller) this.controllers.delete(key); }
  }
}
export function cardCommands(session: Session): CardCommands {
  let store = stores.get(session);
  if (!store) { store = new CardCommands(session); stores.set(session, store); }
  return store;
}
