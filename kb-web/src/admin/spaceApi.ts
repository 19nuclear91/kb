import { ApiError, positiveId } from '../api';
import type { Space } from '../types';
import { adminWrite, AdminError, exactVersion, level, numericId, searchText, type WriteIntent } from './api';
import type { Version } from './types';
import type { Session } from '../session';

export interface AdminSpace extends Space { code: string; description: string | null; version: Version }
export type PrincipalType = 'USER' | 'ROLE' | 'DEPT';
export type ResourceKind = 'spaces' | 'documents';
export type AclPermission = 'view' | 'download' | 'edit' | 'review' | 'comment' | 'manage';
export interface Principal { principalType: PrincipalType; principalId: string; label: string }
export interface Acl { id: string; spaceId: string; principalType: PrincipalType; principalId: string; principalKey: string; principalName: string; permission: AclPermission }
export interface GrantResult { acl: Acl; alreadyGranted: boolean; clearanceBlockedPrincipalCount: number | bigint }
export interface ManagedDocument { documentId: string; spaceId: string; spaceArchived: boolean }
export interface SecurityState { documentId: string; securityLevel: number; securityVersion: Version }
export interface SecurityResult extends SecurityState { previousSecurityLevel: number; previousSecurityVersion: Version; requestId: string; changedAt: string; replayed: boolean }
export interface RelocationResult { documentId: string; fromSpaceId: string; toSpaceId: string; clearedExceptionCount: number; clearedExceptions: string[]; clearedExceptionsTruncated: boolean; clearanceBlockedPrincipalCount: number | bigint; replayed: boolean; targetCategoryId: string | null; metadataVersion: Version }
export interface RelocationContext { documentId: string; fromSpaceId: string; metadataVersion: Version }
/** 管理端删除回执只含定位字段：不含标题、文件名或正文。 */
export interface DeleteDocumentResult { documentId: string; spaceId: string; replayed: boolean }
export async function relocationContext(session: Session, id: string, signal?: AbortSignal): Promise<RelocationContext> {
  const value = await session.json<RelocationContext>(`${base}/documents/${positiveId(id)}/relocation-context`, { signal });
  try { if (value.documentId !== id) throw new Error(); positiveId(value.fromSpaceId); exactVersion(value.metadataVersion); }
  catch { throw new ApiError(503); }
  return value;
}
export async function relocationWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  try {
    const result = await adminWrite<RelocationResult>(session, intent, requestId, signal);
    try {
      if (result.documentId !== intent.path.split('/')[4] || typeof result.replayed !== 'boolean' || !Object.hasOwn(result, 'targetCategoryId')) throw new Error();
      positiveId(result.fromSpaceId); positiveId(result.toSpaceId);
      if (result.targetCategoryId !== null) positiveId(result.targetCategoryId);
      exactVersion(result.metadataVersion);
    }
    catch { throw new ApiError(503); }
    return result as T;
  } catch (error) { if (error instanceof AdminError && error.reason === 'INVALID_PARENT') error.message = '目标分类不可选，请重新读取并选择。'; throw error; }
}
const base = '/api/admin';
const resource = (kind: ResourceKind, id: string) => `${base}/${kind}/${positiveId(id)}`;
export const spacePaths = {
  spaces: `${base}/spaces`, space: (id: string) => resource('spaces', id),
  acl: (kind: ResourceKind, id: string) => `${resource(kind, id)}/acl`,
  security: (id: string) => `${resource('documents', id)}/security-level`,
  documents: (filters: { spaceId?: string; documentId?: string; limit?: number; cursor?: string | null }) => {
    const query = new URLSearchParams();
    if (filters.spaceId) query.set('spaceId', positiveId(filters.spaceId));
    if (filters.documentId) query.set('documentId', positiveId(filters.documentId));
    if (filters.limit !== undefined) {
      if (!Number.isInteger(filters.limit) || filters.limit < 1 || filters.limit > 100) throw new ApiError(400);
      query.set('limit', String(filters.limit));
    }
    if (filters.cursor) query.set('cursor', filters.cursor);
    return `${base}/documents?${query}`;
  },
  principals: (kind: ResourceKind, id: string, type: PrincipalType, q = '', cursor?: string | null) => {
    if (!['USER', 'ROLE', 'DEPT'].includes(type)) throw new ApiError(400);
    const query = new URLSearchParams({ principalType: type });
    const text = searchText(q); if (text) query.set('q', text);
    if (cursor) query.set('cursor', cursor);
    return `${resource(kind, id)}/acl/principals?${query}`;
  },
};
function spaceText(value: string, max: number, required = true) {
  const text = value.trim();
  if ((required && !text) || text.length > max) throw new ApiError(400);
  return text;
}
const metadata = (name: string, description: string) => ({ name: spaceText(name, 200), description: spaceText(description, 2000, false) });
function aclBody(kind: ResourceKind, principal: Principal, permission: AclPermission) {
  if (!['USER', 'ROLE', 'DEPT'].includes(principal.principalType)
    || !['view', 'download', 'edit', 'comment', ...(kind === 'spaces' ? ['review', 'manage'] : [])].includes(permission)) throw new ApiError(400);
  return { principalType: principal.principalType, principalId: numericId(principal.principalId), permission };
}
export const spaceWrites = {
  create: (code: string, name: string, description: string): WriteIntent => ({ method: 'POST', path: spacePaths.spaces, body: { code: spaceText(code, 64), ...metadata(name, description) } }),
  update: (id: string, name: string, description: string, version: Version): WriteIntent => ({ method: 'PATCH', path: spacePaths.space(id), body: { ...metadata(name, description), version: exactVersion(version) } }),
  archive: (id: string, archived: boolean, version: Version): WriteIntent => ({ method: 'POST', path: `${spacePaths.space(id)}/${archived ? 'archive' : 'unarchive'}`, body: { version: exactVersion(version) } }),
  delete: (id: string): WriteIntent => ({ method: 'DELETE', path: spacePaths.space(id) }),
  /** 管理端整文档删除：无请求体，请求标识只在 X-Request-Id；资格由服务端按当前所属空间 manage 判定。 */
  deleteDocument: (id: string): WriteIntent => ({ method: 'DELETE', path: resource('documents', id) }),
  grant: (kind: ResourceKind, id: string, principal: Principal, permission: AclPermission): WriteIntent => ({ method: 'POST', path: spacePaths.acl(kind, id), body: aclBody(kind, principal, permission) }),
  revoke: (kind: ResourceKind, id: string, principal: Principal, permission: AclPermission): WriteIntent => ({ method: 'DELETE', path: spacePaths.acl(kind, id), body: aclBody(kind, principal, permission) }),
  relocate: (id: string, from: string, to: string, targetCategoryId: string | null, expectedMetadataVersion: Version): WriteIntent => {
    if (positiveId(from) === positiveId(to)) throw new ApiError(400);
    return { method: 'PATCH', path: `${resource('documents', id)}/space`, body: { fromSpaceId: numericId(from), toSpaceId: numericId(to),
      targetCategoryId: targetCategoryId === null ? null : numericId(targetCategoryId), expectedMetadataVersion: exactVersion(expectedMetadataVersion) } };
  },
  security: (id: string, value: number, version: Version, reason: string): WriteIntent => {
    const normalized = reason.trim();
    if (!normalized || [...normalized].length > 500 || normalized.includes('\0')) throw new ApiError(400);
    return { method: 'PATCH', path: spacePaths.security(id), body: { securityLevel: level(value), securityVersion: exactVersion(version), reason: normalized } };
  },
};
