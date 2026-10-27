import { ApiError, decodeJson, encodeJson, positiveId } from '../api';
import type { Credential, Identity, LoginResult } from '../types';

export const platforms = ['dingtalk', 'wecom', 'feishu'] as const;
export type Platform = typeof platforms[number];
export type Intent = 'LOGIN' | 'BIND';
export const platformNames: Record<Platform, string> = { dingtalk: '钉钉', wecom: '企业微信', feishu: '飞书' };
export interface Provider { connectionId: Platform; platform: Platform }
export interface BindingStatus extends Provider { connectionEnabled: boolean; bound: boolean }
export interface Bound extends Provider { bound: true }
export type SsoOperation = { kind: 'providers' } | { kind: 'bindings' }
  | { kind: 'login'; platform: Platform } | { kind: 'bind'; platform: Platform; password: string }
  | { kind: 'exchange'; intent: Intent; platform: Platform };
export type SsoResult<T extends SsoOperation> = T extends { kind: 'providers' } ? Provider[]
  : T extends { kind: 'bindings' } ? BindingStatus[]
  : T extends { kind: 'exchange' } ? LoginResult | Bound : { authorizationUrl: string };

const reasons: Record<string, { statuses: number[]; message: string }> = {
  INVALID_FLOW: { statuses: [400, 403], message: '授权流程已失效，请重新发起。' },
  CONFIG_CHANGED: { statuses: [400], message: '登录配置已变化，请重新发起。' },
  CREDENTIAL_REJECTED: { statuses: [401, 403], message: '本次验证未通过或授权已取消，请重新验证。' },
  NOT_MEMBER: { statuses: [403], message: '当前企业身份不具备成员资格。' },
  USER_DISABLED: { statuses: [403], message: '当前账号不可用，请联系管理员。' },
  PASSWORD_CHANGE_REQUIRED: { statuses: [403], message: '请先完成本地密码修改。' },
  CONNECTION_UNAVAILABLE: { statuses: [404], message: '企业登录连接不可用，请刷新目录或使用本地登录。' },
  SSO_BINDING_REQUIRED: { statuses: [409], message: '请先登录已有本地账号，再到个人账户主动绑定企业身份。' },
  BINDING_CONFLICT: { statuses: [409], message: '该企业身份当前无法绑定，请联系管理员处理。' },
  RATE_LIMITED: { statuses: [429], message: '企业授权发起过于频繁，请稍后重新发起。' },
  UPSTREAM_UNAVAILABLE: { statuses: [503], message: '企业认证服务暂不可用，请稍后重新发起。' },
  STORAGE_UNAVAILABLE: { statuses: [503], message: '企业授权暂不可用，请稍后重新发起。' },
};
export class SsoError extends ApiError {
  constructor(status: number, public readonly code?: string, public readonly uncertain = false,
              public readonly correlationId?: string) {
    super(status);
    this.message = (code && reasons[code]?.message) || (uncertain ? '本次授权结果尚未确认，请核对状态或重新发起。'
      : status === 429 ? '企业授权发起受限，请稍后重新发起。' : '企业认证请求未完成，请核对身份或重新发起。');
  }
}
export function isPlatform(value: unknown): value is Platform { return platforms.includes(value as Platform); }
function object(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value); }
function invalid(): never { throw new SsoError(503); }
function provider(value: unknown): Provider {
  if (!object(value) || !isPlatform(value.connectionId) || value.platform !== value.connectionId) return invalid();
  return { connectionId: value.connectionId, platform: value.connectionId };
}
export function credentialValid(value: unknown): value is Credential {
  return object(value) && value.tokenName === 'satoken' && typeof value.tokenValue === 'string' && /^[\x21-\x7e]{1,4096}$/.test(value.tokenValue);
}
export function loginResult(value: unknown): LoginResult {
  if (!object(value) || !credentialValid(value) || typeof value.mustChangePassword !== 'boolean'
      || 'bound' in value || 'platform' in value || 'connectionId' in value || !object(value.user)) return invalid();
  const user = value.user;
  const id = positiveId(user.userId);
  if (value.loginId !== undefined && positiveId(value.loginId) !== id) return invalid();
  if (user.enabled !== true || typeof user.username !== 'string' || typeof user.displayName !== 'string'
      || !Number.isInteger(user.clearanceLevel) || user.username.length > 512 || user.displayName.length > 512) return invalid();
  const identity: Identity = { userId: id, username: user.username, displayName: user.displayName,
    departmentId: user.departmentId === null ? null : positiveId(user.departmentId),
    clearanceLevel: user.clearanceLevel as number, enabled: true };
  return { tokenName: value.tokenName, tokenValue: value.tokenValue, mustChangePassword: value.mustChangePassword, user: identity };
}
const destinations: Record<Platform, [string, string]> = {
  dingtalk: ['login.dingtalk.com', '/oauth2/auth'], wecom: ['login.work.weixin.qq.com', '/wwlogin/sso/login'],
  feishu: ['accounts.feishu.cn', '/open-apis/authen/v1/authorize'],
};
export function authorizationUrl(value: unknown, platform: Platform): string {
  if (typeof value !== 'string' || value.length > 8192 || /[\s\\]/.test(value)) return invalid();
  let url: URL;
  try { url = new URL(value); } catch { return invalid(); }
  const [host, path] = destinations[platform];
  if (url.protocol !== 'https:' || url.hostname !== host || url.pathname !== path || url.username || url.password || url.port || url.hash) return invalid();
  return value;
}
function validate<T extends SsoOperation>(value: unknown, operation: T): SsoResult<T> {
  let result: unknown;
  if (operation.kind === 'providers' || operation.kind === 'bindings') {
    if (!Array.isArray(value) || value.length > 3) return invalid();
    const seen = new Set<string>();
    result = value.map(item => {
      const base = provider(item);
      if (seen.has(base.platform)) return invalid();
      seen.add(base.platform);
      if (operation.kind === 'providers') return base;
      if (!object(item) || typeof item.bound !== 'boolean' || typeof item.connectionEnabled !== 'boolean') return invalid();
      return { ...base, bound: item.bound, connectionEnabled: item.connectionEnabled };
    });
    if (operation.kind === 'bindings' && seen.size !== 3) return invalid();
  } else if (operation.kind === 'exchange') {
    if (operation.intent === 'LOGIN') result = loginResult(value);
    else {
      const base = provider(value);
      if (!object(value) || value.bound !== true || base.platform !== operation.platform || 'tokenValue' in value || 'tokenName' in value || 'user' in value) return invalid();
      result = { ...base, bound: true };
    }
  } else {
    if (!object(value)) return invalid();
    result = { authorizationUrl: authorizationUrl(value.authorizationUrl, operation.platform) };
  }
  return result as SsoResult<T>;
}
export function ssoRequest(operation: SsoOperation, credential?: Credential): [string, RequestInit] {
  const kind = operation.kind;
  if (!['providers', 'bindings', 'login', 'bind', 'exchange'].includes(kind)) return invalid();
  if ('platform' in operation && !isPlatform(operation.platform)) return invalid();
  if (kind === 'exchange' && operation.intent !== 'LOGIN' && operation.intent !== 'BIND') return invalid();
  if (kind === 'bind' && (typeof operation.password !== 'string' || !operation.password || operation.password.length > 4096)) throw new SsoError(400);
  const authenticated = kind === 'bindings' || kind === 'bind' || (kind === 'exchange' && operation.intent === 'BIND');
  if (authenticated && !credentialValid(credential)) throw new SsoError(401);
  const headers = new Headers();
  if (authenticated) headers.set('satoken', credential!.tokenValue);
  const read = kind === 'providers' || kind === 'bindings';
  if (!read) headers.set('Content-Type', 'application/json');
  const suffix = kind === 'login' ? `/${operation.platform}/login` : kind === 'bind' ? `/${operation.platform}/bindings` : `/${kind}`;
  return [`/api/auth/sso${suffix}`, { method: read ? 'GET' : 'POST', headers, cache: 'no-store',
    credentials: read ? 'omit' : 'same-origin', body: read ? undefined : encodeJson(kind === 'bind' ? { password: operation.password } : {}) }];
}
function aborted() { return new DOMException('请求已停止', 'AbortError'); }
async function abortable<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) throw aborted();
  return new Promise<T>((resolve, reject) => {
    const stop = () => reject(aborted());
    signal.addEventListener('abort', stop, { once: true });
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', stop));
  });
}
async function readSsoBody(response: Response, limit: number, signal: AbortSignal): Promise<unknown> {
  if (!response.body || !/^application\/json(?:\s*;|\s*$)/i.test(response.headers.get('Content-Type') ?? '')) {
    void response.body?.cancel().catch(() => {}); throw new SsoError(503);
  }
  const reader = response.body.getReader(), chunks: Uint8Array[] = [];
  let size = 0, completed = false;
  try {
    while (true) {
      const next = await abortable(reader.read(), signal);
      if (next.done) { completed = true; break; }
      size += next.value.byteLength;
      if (size > limit) throw new SsoError(503);
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return decodeJson(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } finally {
    if (!completed) void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
/** 只发送一次固定 SSO 操作；截止时间同时覆盖响应头与响应体读取。 */
export async function ssoCall<T extends SsoOperation>(transport: typeof fetch, operation: T,
    credential?: Credential, signal?: AbortSignal): Promise<SsoResult<T>> {
  const [path, options] = ssoRequest(operation, credential);
  const deadline = new AbortController(), combined = signal ? AbortSignal.any([signal, deadline.signal]) : deadline.signal;
  const timer = setTimeout(() => deadline.abort(), 15000);
  let response: Response | undefined;
  try {
    if (combined.aborted) throw aborted();
    const requested = Promise.resolve(transport(path, { ...options, signal: combined })).then(value => {
      if (combined.aborted) { void value.body?.cancel().catch(() => {}); throw aborted(); }
      return value;
    });
    response = await abortable(requested, combined);
    if (!response.ok) {
      let code: string | undefined, correlationId: string | undefined;
      try {
        const value = await readSsoBody(response, 8192, combined);
        if (object(value) && value.code === 500 && Object.keys(value).every(key => ['code', 'message', 'data'].includes(key))
            && object(value.data) && Object.keys(value.data).every(key => ['errorCode', 'correlationId'].includes(key)) && typeof value.data.errorCode === 'string'
            && Object.hasOwn(reasons, value.data.errorCode) && reasons[value.data.errorCode].statuses.includes(response.status)) {
          code = value.data.errorCode;
          if (typeof value.data.correlationId === 'string' && /^[0-9a-f-]{36}$/i.test(value.data.correlationId)) correlationId = value.data.correlationId;
        }
      } catch { if (combined.aborted) throw aborted(); }
      throw new SsoError(response.status, code, response.status >= 500, correlationId);
    }
    const value = await readSsoBody(response, 32768, combined);
    if (!object(value) || value.code !== 200 || !Object.hasOwn(value, 'data')) return invalid();
    return validate(value.data, operation);
  } catch (error) {
    if (signal?.aborted) throw aborted();
    if (!deadline.signal.aborted && !response?.ok && error instanceof SsoError) throw error;
    throw new SsoError(response?.ok ? 0 : response?.status ?? 0, undefined, true);
  } finally { clearTimeout(timer); }
}
