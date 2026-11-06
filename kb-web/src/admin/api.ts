import { ApiError, encodeJson, positiveId, readJson } from '../api';
import type { Session } from '../session';
import type { UserFilters, Version } from './types';

export const ADMIN_BASE = '/api/admin/identity';
export const SEARCH_TEXT_MAX = 128;
export const FAILURE_LIMIT = 8 * 1024;

const users = `${ADMIN_BASE}/users`, departments = `${ADMIN_BASE}/departments`, roles = `${ADMIN_BASE}/roles`;
export const adminPaths = {
  user: (userId: string) => `${users}/${positiveId(userId)}`,
  userRoles: (userId: string, cursor?: string | null) => `${users}/${positiveId(userId)}/roles${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''}`,
  departments, department: (departmentId: string) => `${departments}/${positiveId(departmentId)}`,
  roles, role: (roleId: string) => `${roles}/${positiveId(roleId)}`,
  permissions: `${ADMIN_BASE}/permissions`, securityLevels: '/api/auth/security-levels', mine: '/api/auth/permissions',
};

/** 与服务端 q 规则一致：去首尾空白，空值不提交，超长或含控制字符在发请求前拒绝。 */
export function searchText(value: string | undefined): string | undefined {
  const text = (value ?? '').trim();
  if (!text) return undefined;
  if ([...text].length > SEARCH_TEXT_MAX || /\p{Cc}/u.test(text)) throw new ApiError(400);
  return text;
}
/** 用户检索只带服务端支持的条件；部门只精确匹配主部门，游标原样回传。 */
export function searchPath(filters: UserFilters, cursor?: string | null): string {
  const params: string[] = [], q = searchText(filters.q);
  if (q) params.push(`q=${encodeURIComponent(q)}`);
  if (filters.departmentId) params.push(`departmentId=${positiveId(filters.departmentId)}`);
  if (filters.enabled !== undefined) params.push(`enabled=${filters.enabled}`);
  if (cursor) params.push(`cursor=${encodeURIComponent(cursor)}`);
  return `${users}/search${params.length ? `?${params.join('&')}` : ''}`;
}

export type WriteMethod = 'POST' | 'PATCH' | 'PUT' | 'DELETE';
/** 一次逻辑写入：方法、目标路径和精确提交值；请求标识只走 X-Request-Id，不重复写入请求体。 */
export interface WriteIntent { method: WriteMethod; path: string; body?: Record<string, unknown> }

export const numericId = (value: string) => BigInt(positiveId(value));
const parentOf = (value: string | null) => value == null ? null : numericId(value);
export function exactVersion(value: Version): bigint {
  if (typeof value === 'bigint') { if (value >= 0n && value <= 9223372036854775807n) return value; }
  else if (Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  throw new ApiError(400);
}
export function level(value: number): number {
  if (!Number.isInteger(value) || value < -32768 || value > 32767) throw new ApiError(400);
  return value;
}
export const adminWrites = {
  createUser: (value: { username: string; displayName: string; departmentId: string; clearanceLevel: number; initialPassword: string }): WriteIntent =>
    ({ method: 'POST', path: users, body: { username: value.username, displayName: value.displayName, departmentId: numericId(value.departmentId),
      clearanceLevel: level(value.clearanceLevel), initialPassword: value.initialPassword } }),
  moveUser: (userId: string, departmentId: string, version: Version): WriteIntent =>
    ({ method: 'PATCH', path: `${adminPaths.user(userId)}/department`, body: { departmentId: numericId(departmentId), version: exactVersion(version) } }),
  setUserEnabled: (userId: string, enabled: boolean, version: Version): WriteIntent =>
    ({ method: 'PATCH', path: `${adminPaths.user(userId)}/enabled`, body: { enabled, version: exactVersion(version) } }),
  resetPassword: (userId: string, newPassword: string): WriteIntent =>
    ({ method: 'PATCH', path: `${adminPaths.user(userId)}/password`, body: { newPassword } }),
  changeClearance: (userId: string, clearanceLevel: number, version: Version): WriteIntent =>
    ({ method: 'PATCH', path: `${adminPaths.user(userId)}/clearance`, body: { clearanceLevel: level(clearanceLevel), version: exactVersion(version) } }),
  assignRole: (userId: string, roleId: string): WriteIntent =>
    ({ method: 'POST', path: `${adminPaths.user(userId)}/roles`, body: { roleId: numericId(roleId) } }),
  revokeRole: (userId: string, roleId: string): WriteIntent =>
    ({ method: 'DELETE', path: `${adminPaths.user(userId)}/roles/${positiveId(roleId)}` }),
  createDepartment: (value: { code: string; name: string; parentId: string | null }): WriteIntent =>
    ({ method: 'POST', path: departments, body: { code: value.code, name: value.name, parentId: parentOf(value.parentId) } }),
  renameDepartment: (departmentId: string, name: string, version: Version): WriteIntent =>
    ({ method: 'PATCH', path: `${adminPaths.department(departmentId)}/name`, body: { name, version: exactVersion(version) } }),
  moveDepartment: (departmentId: string, parentId: string | null, version: Version): WriteIntent =>
    ({ method: 'PATCH', path: `${adminPaths.department(departmentId)}/parent`, body: { parentId: parentOf(parentId), version: exactVersion(version) } }),
  setDepartmentEnabled: (departmentId: string, enabled: boolean, version: Version): WriteIntent =>
    ({ method: 'PATCH', path: `${adminPaths.department(departmentId)}/enabled`, body: { enabled, version: exactVersion(version) } }),
  createRole: (code: string, name: string): WriteIntent => ({ method: 'POST', path: roles, body: { code, name } }),
  renameRole: (roleId: string, name: string): WriteIntent => ({ method: 'PATCH', path: adminPaths.role(roleId), body: { name } }),
  deleteRole: (roleId: string): WriteIntent => ({ method: 'DELETE', path: adminPaths.role(roleId) }),
  setRolePermission: (roleId: string, permission: string, granted: boolean): WriteIntent =>
    ({ method: 'PUT', path: `${adminPaths.role(roleId)}/permissions`, body: { permission, granted } }),
};
/** 绑定键：方法、目标和精确提交值（含 version）一致才视为同一逻辑写入。 */
export function intentKey(intent: WriteIntent): string { return `${intent.method} ${intent.path} ${intent.body ? encodeJson(intent.body) : ''}`; }

/** 服务端管理写投影的有限原因及其唯一合法 HTTP 状态；提示文本固定，不读取服务端 message。 */
export const adminReasons = {
  EMERGENCY_MANAGER_REQUIRED: [409, '需保留合格的独立应急管理员；请先由新候选本人完成登记或轮换。'],
  LIFECYCLE_RESTRICTED: [409, '账号存在生命周期限制，普通启用不能解除；请使用人工复职核对。'],
  LIFECYCLE_CONFIRMATION_STALE: [409, '核验已过期或相关事实发生变化，请重新读取并核验。'],
  DEPENDENCY_UNAVAILABLE: [503, '当前依赖不可用，无法确认操作结果，请稍后核对。'],
  SPACE_NOT_EMPTY: [409, '空间仍有文档，请改用归档或先处理文档。'],
  SOURCE_MISMATCH: [409, '文档归属已变化，请重新读取后确认。'],
  TARGET_ARCHIVED: [409, '目标空间已归档，请重新选择。'],
  ALREADY_IN_TARGET: [409, '文档已在目标空间，请重新读取后确认。'],
  INVALID_VERSION: [409, '数据已被其他操作更新，请重新读取后确认新的版本。'],
  DEPARTMENT_NOT_EMPTY: [409, '部门仍有启用的子部门或用户，请先处理后再停用。'],
  ROLE_IN_USE: [409, '角色仍有用户绑定或空间/文档授权引用，请先处理这些引用；系统不会强制删除。'],
  LAST_MANAGER_REQUIRED: [409, '该操作会使系统失去最后一名合格管理员，请先保留其他合格管理员。'],
  IDENTIFIER_CONFLICT: [409, '用户名或编码已存在，请更换后提交。'],
  INVALID_HIERARCHY: [409, '部门层级不符合要求，请重新读取后确认。'],
  INVALID_PARENT: [409, '目标上级部门不符合要求，请重新读取后确认。'],
  CONFLICT: [409, '当前状态已变化，请重新读取后确认。'],
  INVALID_REQUEST: [400, '输入不符合要求，请检查表单后重新提交。'],
  FORBIDDEN: [403, '当前操作被拒绝，正在重新确认管理权限。'],
  DISABLED: [403, '目标或关联对象已停用。'],
  PASSWORD_CHANGE_REQUIRED: [403, '当前账号需要先修改密码。'],
  NOT_FOUND: [404, '目标不存在或当前不可访问，请重新读取。'],
  UNAUTHORIZED: [401, '登录状态已失效，请重新登录。'],
} as const satisfies Record<string, readonly [number, string]>;
export type AdminReason = keyof typeof adminReasons;

export class AdminError extends ApiError {
  constructor(status: number, public readonly reason: AdminReason) { super(status); this.message = adminReasons[reason][1]; }
}

async function readLimited(body: ReadableStream<Uint8Array>, limit: number): Promise<string | null> {
  const reader = body.getReader(), chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const next = await reader.read();
    if (next.done) break;
    size += next.value.byteLength;
    if (size > limit) { await reader.cancel().catch(() => {}); return null; }
    chunks.push(next.value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch { return null; }
}

/**
 * 只消费白名单管理写失败体：JSON、最多 8 KiB、code=500/data=null，且 reasonCode 与 HTTP 状态匹配。
 * 任一条件不满足都回退为当前 HTTP 的通用提示；身份切换导致的中止继续向上抛出。
 */
export async function adminFailure(response: Response): Promise<ApiError> {
  const status = response.status, fallback = () => new ApiError(status);
  const declared = response.headers.get('Content-Length');
  const candidate = Object.values(adminReasons).some(([expected]) => expected === status);
  if (!candidate || !response.body || !response.headers.get('Content-Type')?.includes('application/json')
      || (declared !== null && !(/^\d+$/.test(declared) && Number(declared) <= FAILURE_LIMIT))) {
    await response.body?.cancel().catch(() => {});
    return fallback();
  }
  let text: string | null;
  try { text = await readLimited(response.body, FAILURE_LIMIT); }
  catch (error) { if (error instanceof Error && error.name === 'AbortError') throw error; return fallback(); }
  if (text === null) return fallback();
  let value: unknown;
  try { value = JSON.parse(text); } catch { return fallback(); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return fallback();
  const shape = value as Record<string, unknown>, reason = shape.reasonCode;
  if (shape.code !== 500 || !Object.hasOwn(shape, 'data') || shape.data !== null || typeof reason !== 'string'
      || !Object.hasOwn(adminReasons, reason) || adminReasons[reason as AdminReason][0] !== status) return fallback();
  return new AdminError(status, reason as AdminReason);
}

/** 发送一次管理写入：请求标识放 X-Request-Id；读取期间身份变化按中止处理，成功但无法读出回执视为结果不确定。 */
export async function adminWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  if (!/^[\x21-\x7e]{1,128}$/.test(requestId)) throw new ApiError(400);
  const epoch = session.getSnapshot().epoch;
  const headers: Record<string, string> = { 'X-Request-Id': requestId };
  if (intent.body) headers['Content-Type'] = 'application/json';
  const response = await session.send(intent.path, { method: intent.method, headers, body: intent.body ? encodeJson(intent.body) : undefined, signal });
  const stale = () => epoch !== session.getSnapshot().epoch || signal?.aborted === true;
  if (stale()) { await response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  if (!response.ok) {
    const failure = await adminFailure(response);
    if (stale()) throw new DOMException('请求已停止', 'AbortError');
    throw failure;
  }
  let result: T;
  try { result = await readJson<T>(response); }
  catch (error) { if (stale() || (error instanceof Error && error.name === 'AbortError')) throw new DOMException('请求已停止', 'AbortError'); throw new ApiError(0); }
  if (stale()) throw new DOMException('请求已停止', 'AbortError');
  return result;
}

/** 网络失败、网关/服务端 5xx 或成功回执不可读时，写入可能已经执行。 */
export function uncertain(error: unknown): boolean { return !(error instanceof ApiError) || error.status === 0 || error.status >= 500; }
export const uncertainMessage = '提交结果尚未确认，服务器可能已经执行。请先重新读取核对，或按原内容和原请求标识重试；不会自动重发。';
