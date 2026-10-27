import { ApiError, encodeJson, readJson } from './api';
import type { Credential, LoginResult, Me } from './types';
import { credentialValid, ssoCall, SsoError, type SsoOperation, type SsoResult } from './sso/api';
import { FLOW_KEY, NAV_KEY } from './sso/storage';

export interface SessionState { phase: 'anonymous' | 'loading' | 'ready' | 'password' | 'unavailable'; me: Me | null; epoch: number; notice?: string }
const STORAGE_KEY = 'kb.local-session';
export interface SsoHandoff {
  signal: AbortSignal;
  valid(): boolean;
  finish(result?: LoginResult): Promise<boolean>;
  cancel(): void;
}

export class Session {
  private credential: Credential | null = null;
  private state: SessionState = { phase: 'anonymous', me: null, epoch: 0 };
  private listeners = new Set<() => void>();
  private controllers = new Set<AbortController>();
  private refreshing: Promise<void> | null = null;
  private checking403: Promise<void> | null = null;
  private credentialGeneration = 0;
  private ssoControllers = new Set<AbortController>();
  private handoff: { controller: AbortController; generation: number; closed: boolean } | null = null;
  private initialPasswordGate = false;

  constructor(private storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>,
              private transport: typeof fetch = (...args) => fetch(...args)) {
    try {
      const saved = JSON.parse(storage.getItem(STORAGE_KEY) ?? 'null') as Credential | null;
      if (saved && saved.tokenName === 'satoken' && typeof saved.tokenValue === 'string' && /^[\x21-\x7e]{1,4096}$/.test(saved.tokenValue)) {
        this.credential = saved;
        this.state = { ...this.state, phase: 'loading' };
      } else storage.removeItem(STORAGE_KEY);
    } catch { try { storage.removeItem(STORAGE_KEY); } catch { /* 会话存储被禁用时仍显示登录入口。 */ } }
  }

  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };

  private patch(update: Partial<SessionState>) { this.state = { ...this.state, ...update }; this.listeners.forEach(listener => listener()); }
  private invalidate() {
    this.controllers.forEach(controller => controller.abort());
    this.controllers.clear();
    this.refreshing = null;
    this.checking403 = null;
    this.patch({ epoch: this.state.epoch + 1, me: null, phase: this.credential ? 'loading' : 'anonymous' });
  }
  private accept(me: Me) {
    if (!me?.user?.userId || me.user.enabled !== true || typeof me.mustChangePassword !== 'boolean') throw new ApiError(503);
    const password = me.mustChangePassword || this.initialPasswordGate;
    this.patch({ me: password ? { ...me, mustChangePassword: true } : me, phase: password ? 'password' : 'ready', notice: undefined });
  }

  private supersedeAuthentication(keepFlow = false) {
    this.credentialGeneration++;
    this.ssoControllers.forEach(controller => controller.abort());
    this.ssoControllers.clear();
    if (this.handoff) { this.handoff.closed = true; this.handoff.controller.abort(); this.handoff = null; }
    this.initialPasswordGate = false;
    if (!keepFlow) { try { this.storage.removeItem(FLOW_KEY); } catch { /* 旧提示不会成为授权凭据。 */ } }
  }

  /** 统一接管登录令牌；当前主体仍必须由随后 me 核对。 */
  private adoptLogin(result: Credential & Partial<LoginResult>, generation: number, keepFlow = false) {
    if (generation !== this.credentialGeneration) throw new DOMException('请求已停止', 'AbortError');
    if (!credentialValid(result)) throw new ApiError(503);
    this.supersedeAuthentication(keepFlow);
    this.invalidate();
    const credential = { tokenName: result.tokenName, tokenValue: result.tokenValue };
    try { this.storage.setItem(STORAGE_KEY, JSON.stringify(credential)); }
    catch {
      this.credential = null;
      try { this.storage.removeItem(STORAGE_KEY); } catch { /* 不持久化额外凭据副本。 */ }
      this.patch({ phase: 'anonymous', me: null, notice: '标签页会话无法保存，请检查浏览器存储设置。' });
      throw new ApiError(503);
    }
    this.credential = credential;
    this.initialPasswordGate = result.mustChangePassword === true;
    this.patch({ phase: 'loading', me: null });
  }

  /** 固定 SSO 操作，页面无法导出令牌或给任意 URL 开启 Cookie。 */
  async sso<T extends SsoOperation>(operation: T, signal?: AbortSignal): Promise<SsoResult<T>> {
    const generation = this.credentialGeneration, controller = new AbortController();
    this.ssoControllers.add(controller);
    const combined = signal ? AbortSignal.any([signal, controller.signal]) : controller.signal;
    try {
      const result = await ssoCall(this.transport, operation, this.credential ?? undefined, combined);
      if (generation !== this.credentialGeneration || combined.aborted) throw new DOMException('请求已停止', 'AbortError');
      return result;
    } catch (error) {
      if (generation !== this.credentialGeneration || combined.aborted) throw new DOMException('请求已停止', 'AbortError');
      const protectedOperation = operation.kind === 'bind' || operation.kind === 'bindings' || (operation.kind === 'exchange' && operation.intent === 'BIND');
      if (error instanceof SsoError && protectedOperation) {
        if (error.status === 401 && operation.kind !== 'bind') this.clear('登录状态已失效，请重新登录。');
        else if ((error.status === 401 && operation.kind === 'bind') || (error.status === 403
          && (!error.code || ['PASSWORD_CHANGE_REQUIRED', 'USER_DISABLED', 'INVALID_FLOW'].includes(error.code)))) {
          // 先完整消费错误体，再核对当前身份；BIND 密码 401 并不证明会话失效。
          if (!this.handoff) {
            await this.refresh();
            if (generation === this.credentialGeneration && this.state.phase === 'ready') this.patch({ notice: error.message });
          }
        }
      }
      throw error;
    } finally { this.ssoControllers.delete(controller); }
  }

  /** 专用结果页的一次性交接资格；普通重核不会替换凭据代次。 */
  beginSsoHandoff(): SsoHandoff {
    if (this.handoff) throw new SsoError(409);
    const holder = { controller: new AbortController(), generation: this.credentialGeneration, closed: false };
    this.invalidate();
    this.handoff = holder;
    const valid = () => !holder.closed && !holder.controller.signal.aborted && holder.generation === this.credentialGeneration;
    return {
      signal: holder.controller.signal, valid,
      finish: async result => {
        if (!valid()) return false;
        holder.closed = true; this.handoff = null;
        if (result) this.adoptLogin(result, holder.generation, true);
        const generation = this.credentialGeneration;
        await this.refresh();
        return generation === this.credentialGeneration;
      },
      cancel: () => {
        if (holder.closed) return;
        holder.closed = true; holder.controller.abort();
        if (this.handoff === holder) this.handoff = null;
      },
    };
  }

  async send(path: string, options: RequestInit = {}, anonymous = false, check403 = true): Promise<Response> {
    if (!path.startsWith('/api/') || path.includes('\\') || /[\r\n#]/.test(path)) throw new ApiError(400);
    if (!anonymous && !this.credential) throw new ApiError(401);
    const epoch = this.state.epoch;
    const controller = new AbortController();
    this.controllers.add(controller);
    const headers = new Headers(options.headers);
    const signal = options.signal ? AbortSignal.any([controller.signal, options.signal]) : controller.signal;
    if (!anonymous && this.credential) headers.set(this.credential.tokenName, this.credential.tokenValue);
    let response: Response;
    try {
      response = await this.transport(path, { ...options, headers, credentials: 'omit', cache: 'no-store',
        signal });
    } catch (error) {
      this.controllers.delete(controller);
      if (controller.signal.aborted || options.signal?.aborted || epoch !== this.state.epoch) throw new DOMException('请求已停止', 'AbortError');
      throw new ApiError(0);
    }
    if (epoch !== this.state.epoch || signal.aborted) {
      this.controllers.delete(controller); await response.body?.cancel().catch(() => {});
      throw new DOMException('请求已停止', 'AbortError');
    }
    // 响应体仍可能流式读取，直到调用者取消或身份切换才解除控制。
    if (response.body) {
      const reader = response.body.getReader();
      let released = false;
      const stop = () => { void reader.cancel().catch(() => {}); };
      signal.addEventListener('abort', stop, { once: true });
      const release = () => { if (!released) { released = true; signal.removeEventListener('abort', stop); this.controllers.delete(controller); reader.releaseLock(); } };
      const check = () => { if (epoch !== this.state.epoch || controller.signal.aborted || options.signal?.aborted) throw new DOMException('请求已停止', 'AbortError'); };
      const body = new ReadableStream<Uint8Array>({
        pull: async target => {
          try { check(); const next = await reader.read(); check(); if (next.done) { release(); target.close(); } else target.enqueue(next.value); }
          catch (error) { release(); target.error(error); }
        },
        cancel: async () => { controller.abort(); try { await reader.cancel(); } finally { release(); } },
      });
      response = new Response(body, { status: response.status, statusText: response.statusText, headers: response.headers });
    } else this.controllers.delete(controller);
    if (epoch !== this.state.epoch || controller.signal.aborted) throw new DOMException('请求已停止', 'AbortError');
    if (!anonymous && response.status === 401) this.clear('登录状态已失效，请重新登录。');
    if (!anonymous && response.status === 403 && check403) await this.handle403();
    return response;
  }

  async json<T>(path: string, options: RequestInit = {}, envelope = true, anonymous = false): Promise<T> {
    const epoch = this.state.epoch;
    const result = await readJson<T>(await this.send(path, options, anonymous), envelope);
    if (epoch !== this.state.epoch) throw new DOMException('请求已停止', 'AbortError');
    return result;
  }
  post<T>(path: string, body: unknown, envelope = true, signal?: AbortSignal) {
    return this.json<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(body), signal }, envelope)
      .then(result => { if (path === '/api/auth/password') this.initialPasswordGate = false; return result; });
  }

  async login(username: string, password: string): Promise<void> {
    this.supersedeAuthentication();
    const generation = this.credentialGeneration;
    const epoch = this.state.epoch;
    const result = await this.json<LoginResult>('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: encodeJson({ username, password }) }, true, true);
    if (epoch !== this.state.epoch) throw new DOMException('请求已停止', 'AbortError');
    this.adoptLogin(result, generation);
    await this.refresh();
  }

  refresh = (): Promise<void> => {
    if (this.handoff) return Promise.resolve();
    if (!this.credential) return Promise.resolve();
    if (this.refreshing) return this.refreshing;
    this.invalidate();
    const epoch = this.state.epoch;
    this.patch({ phase: 'loading', notice: undefined });
    const operation = (async () => {
      try { const me = await readJson<Me>(await this.send('/api/auth/me', { signal: AbortSignal.timeout(15000) }, false, false)); if (epoch === this.state.epoch) this.accept(me); }
      catch (error) {
        if (epoch !== this.state.epoch) return;
        if (error instanceof ApiError && error.status === 401) this.clear('登录状态已失效，请重新登录。');
        else this.patch({ phase: 'unavailable', me: null, notice: '当前身份暂时无法确认，请重试或退出。' });
      }
    })();
    this.refreshing = operation;
    void operation.finally(() => { if (this.refreshing === operation) this.refreshing = null; });
    return operation;
  };

  private async handle403() {
    if (this.checking403) return this.checking403;
    const epoch = this.state.epoch;
    const operation = (async () => {
      try {
        const me = await readJson<Me>(await this.send('/api/auth/me', {}, false, false));
        if (epoch !== this.state.epoch) return;
        if (me.mustChangePassword) { this.invalidate(); this.accept(me); }
        else if (!me.user?.enabled) throw new ApiError(403);
      } catch {
        if (epoch === this.state.epoch) { this.invalidate(); this.patch({ phase: 'unavailable', notice: '当前身份不可确认或已被停用。' }); }
      }
    })();
    this.checking403 = operation;
    try { await operation; } finally { if (this.checking403 === operation) this.checking403 = null; }
  }

  suspend() { if (!this.handoff && this.credential) { this.invalidate(); this.patch({ phase: 'loading' }); } }
  clear(notice?: string) {
    this.supersedeAuthentication();
    this.invalidate(); this.credential = null;
    try { this.storage.removeItem(STORAGE_KEY); } catch { /* 当前内存身份仍立即失效。 */ }
    try { this.storage.removeItem(NAV_KEY); } catch { /* 已清理会话，导航提示不携带认证材料。 */ }
    this.patch({ phase: 'anonymous', me: null, notice });
  }
  async logout() {
    const credential = this.credential;
    this.clear();
    const epoch = this.state.epoch;
    try {
      if (credential) {
        const response = await this.transport('/api/auth/logout', { method: 'POST', credentials: 'omit', cache: 'no-store',
          headers: { [credential.tokenName]: credential.tokenValue }, signal: AbortSignal.timeout(5000) });
        await readJson(response);
      }
    } catch { if (epoch === this.state.epoch) this.patch({ notice: '本地已退出，服务器会话退出暂未确认。' }); }
  }
}
