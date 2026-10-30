import { ApiError, messageOf, positiveId, readJson } from '../api';
import type { Session } from '../session';
import type { Capabilities, DocumentSummary } from '../types';
import { canDelete } from './capabilities';
import { DocumentOperations } from './operations';

export interface DeletionTransport {
  detail(id: string, signal: AbortSignal): Promise<DocumentSummary>;
  capabilities(id: string, signal: AbortSignal): Promise<Capabilities>;
  remove(id: string, signal: AbortSignal): Promise<void>;
}
export function deletionTransport(session: Pick<Session, 'json' | 'send'>): DeletionTransport {
  return {
    detail: (id, signal) => session.json(`/api/documents/${positiveId(id)}`, { signal }),
    capabilities: (id, signal) => session.json(`/api/documents/${positiveId(id)}/capabilities`, { signal }),
    remove: async (id, signal) => {
      const response = await session.send(`/api/documents/${positiveId(id)}`, { method: 'DELETE', signal });
      if (signal.aborted) { await response.body?.cancel().catch(() => {}); throw signal.reason; }
      if (response.status !== 200) {
        await response.body?.cancel().catch(() => {});
        throw new ApiError(response.ok ? 503 : response.status);
      }
      const result = await readJson<unknown>(response);
      if (signal.aborted) throw signal.reason;
      if (result !== null) throw new ApiError(503);
    },
  };
}

export type DeletionPhase = 'idle' | 'checking' | 'confirming' | 'submitting' | 'uncertain' | 'verifying'
  | 'rejected' | 'unavailable' | 'confirmed' | 'disposed';
export interface DeletionFacts { detail: DocumentSummary; capabilities: Capabilities }
export interface DeletionState {
  phase: DeletionPhase;
  showContent: boolean;
  attempted: boolean;
  uncertainResult: boolean;
  facts?: DeletionFacts;
  message?: string;
  rejection?: number;
  acknowledgement?: number;
}
const initialState = (): DeletionState => ({ phase: 'idle', showContent: true, attempted: false, uncertainResult: false,
  facts: undefined, message: undefined, rejection: undefined, acknowledgement: undefined });

/** 当前详情页的删除意图；核对仅查询当前事实，不推断原 DELETE 的历史结果。 */
export class DocumentDeletion {
  private state = initialState();
  private listeners = new Set<() => void>();
  private generation = 0;
  private request: AbortController | null = null;
  private epoch: number;

  constructor(readonly documentId: string, readonly operations: DocumentOperations,
    private session: Pick<Session, 'getSnapshot'>, private transport: DeletionTransport,
    private confirmed: () => void, private visible: () => boolean = () => true, private timeout = 15000) {
    this.epoch = session.getSnapshot().epoch;
  }
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private publish(update: Partial<DeletionState>) {
    this.state = { ...this.state, ...update };
    this.listeners.forEach(listener => listener());
  }
  private live(generation = this.generation) {
    const identity = this.session.getSnapshot();
    return this.state.phase !== 'disposed' && generation === this.generation && this.visible()
      && identity.epoch === this.epoch && identity.phase === 'ready';
  }
  private resultPhase(): 'uncertain' | 'rejected' {
    return this.state.uncertainResult && this.state.rejection === undefined ? 'uncertain' : 'rejected';
  }
  /** 支持 React StrictMode 的 effect 重新建立；旧异步代次始终失效。 */
  activate() {
    if (this.state.phase === 'disposed') {
      this.state = initialState(); this.operations.cancelDelete(); this.listeners.forEach(listener => listener());
    }
  }
  dispose() {
    this.generation++; this.request?.abort(); this.request = null;
    this.publish({ phase: 'disposed', facts: undefined, showContent: false, message: undefined });
  }
  private begin() {
    this.generation++; this.request?.abort();
    const request = new AbortController(); this.request = request;
    return { request, generation: this.generation };
  }
  private async bounded<T>(request: AbortController, execute: (signal: AbortSignal) => Promise<T>): Promise<T> {
    let stop!: () => void;
    const aborted = new Promise<never>((_, reject) => {
      stop = () => reject(request.signal.reason);
      request.signal.addEventListener('abort', stop, { once: true });
    });
    const timer = setTimeout(() => request.abort(new DOMException('等待已到期', 'TimeoutError')), this.timeout);
    try { return await Promise.race([execute(request.signal), aborted]); }
    finally { clearTimeout(timer); request.signal.removeEventListener('abort', stop); request.abort(); }
  }
  private async facts(signal: AbortSignal): Promise<DeletionFacts> {
    const id = positiveId(this.documentId);
    const [detail, capabilities] = await Promise.all([this.transport.detail(id, signal), this.transport.capabilities(id, signal)]);
    if (!detail || detail.id !== id || typeof detail.title !== 'string' || !capabilities || capabilities.documentId !== id) throw new ApiError(503);
    return { detail, capabilities };
  }

  open = async () => {
    if (!this.live()) return;
    const before = this.state;
    if (before.phase !== 'idle' && !(['uncertain', 'rejected'].includes(before.phase) && canDelete(before.facts?.capabilities))) return;
    if (!this.operations.getSnapshot().exclusive && !this.operations.beginDelete()) return;
    const { request, generation } = this.begin();
    this.publish({ phase: 'checking', facts: undefined, message: undefined, acknowledgement: undefined });
    try {
      const facts = await this.bounded(request, signal => this.facts(signal));
      if (!this.live(generation)) return;
      this.publish({ phase: 'confirming', facts, message: canDelete(facts.capabilities) ? undefined : '当前没有整文档删除资格。' });
    } catch (error) {
      if (!this.live(generation)) return;
      const unavailable = error instanceof ApiError && [403, 404].includes(error.status);
      this.publish({ phase: unavailable ? 'unavailable' : 'confirming', facts: undefined,
        showContent: unavailable ? false : before.showContent, message: messageOf(error) });
    }
  };

  cancel = () => {
    if (!this.live() || !['checking', 'confirming'].includes(this.state.phase)) return;
    this.generation++; this.request?.abort();
    if (this.state.attempted) {
      this.publish({ phase: this.resultPhase(), facts: undefined, showContent: false, acknowledgement: undefined });
    } else {
      this.operations.cancelDelete();
      this.publish({ ...initialState() });
    }
  };

  invalidateConfirmation = (unavailable = false) => {
    if (!this.live() || ['submitting', 'confirmed'].includes(this.state.phase)) return;
    if (!unavailable && this.state.phase === 'idle') return;
    this.generation++; this.request?.abort();
    if (unavailable && !this.operations.getSnapshot().exclusive) this.operations.beginDelete();
    const wasVerifying = this.state.phase === 'verifying';
    let phase = this.state.phase;
    if (unavailable || (wasVerifying && !this.state.attempted)) phase = 'unavailable';
    else if (['checking', 'confirming'].includes(phase)) phase = 'confirming';
    else if (wasVerifying) phase = this.resultPhase();
    this.publish({ phase, facts: undefined,
      showContent: unavailable || wasVerifying ? false : this.state.showContent,
      message: unavailable ? '文档当前不可访问。' : phase === 'confirming' ? '操作资格已变化或暂不可确认，请取消后重新打开确认。'
        : '当前操作资格已变化，请重新核对当前状态。' });
  };

  submit = async (acknowledge = false) => {
    if (!this.live() || this.state.phase !== 'confirming' || !canDelete(this.state.facts?.capabilities) || !this.operations.canSubmitDelete()) return;
    const operations = this.operations.getSnapshot();
    if (operations.uncertain && (!acknowledge || this.state.acknowledgement !== operations.uncertaintyRevision)) {
      this.publish({ acknowledgement: operations.uncertaintyRevision }); return;
    }
    const { request, generation } = this.begin();
    this.publish({ phase: 'submitting', showContent: false, facts: undefined, attempted: true,
      message: undefined, rejection: undefined, acknowledgement: undefined });
    try {
      await this.bounded(request, signal => this.transport.remove(positiveId(this.documentId), signal));
    } catch (error) {
      if (!this.live(generation)) return;
      const rejection = error instanceof ApiError && [400, 401, 403, 404, 409].includes(error.status) ? error.status : undefined;
      if (rejection === 404) {
        this.publish({ phase: 'unavailable', rejection, message: '目标不存在或当前不可访问。' });
      } else if (rejection) {
        this.publish({ phase: 'rejected', rejection, message: messageOf(error) });
        await this.verify();
      } else {
        this.publish({ phase: 'uncertain', uncertainResult: true, message: '删除结果尚未确认；请求可能已经执行，请主动核对当前状态。' });
      }
      return;
    }
    if (!this.live(generation)) return;
    this.publish({ phase: 'confirmed', uncertainResult: false });
    if (this.live(generation)) this.confirmed();
  };

  verify = async () => {
    if (!this.live() || !['uncertain', 'rejected', 'unavailable'].includes(this.state.phase)) return;
    const { request, generation } = this.begin();
    this.publish({ phase: 'verifying', facts: undefined, showContent: false, acknowledgement: undefined });
    try {
      const facts = await this.bounded(request, signal => this.facts(signal));
      if (!this.live(generation)) return;
      if (!this.state.attempted) {
        this.publish(initialState());
        this.operations.cancelDelete();
        return;
      }
      this.publish({ phase: this.resultPhase(), facts, showContent: true });
    } catch (error) {
      if (!this.live(generation)) return;
      const unavailable = error instanceof ApiError && [403, 404].includes(error.status);
      this.publish({ phase: unavailable || !this.state.attempted ? 'unavailable' : this.resultPhase(),
        facts: undefined, showContent: false,
        message: unavailable ? '文档当前不可访问；不能据此确认原删除请求的结果。' : '当前状态核对失败，请稍后主动重读。' });
    }
  };
}
