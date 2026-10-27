import { ApiError, positiveId } from '../api';
import type { Space } from '../types';
import { exactVersion, level, numericId, searchText, type WriteIntent } from './api';
import type { Version } from './types';

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
export interface RelocationResult { documentId: string; fromSpaceId: string; toSpaceId: string; clearedExceptionCount: number; clearedExceptions: string[]; clearedExceptionsTruncated: boolean; clearanceBlockedPrincipalCount: number | bigint; replayed: boolean }
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
  grant: (kind: ResourceKind, id: string, principal: Principal, permission: AclPermission): WriteIntent => ({ method: 'POST', path: spacePaths.acl(kind, id), body: aclBody(kind, principal, permission) }),
  revoke: (kind: ResourceKind, id: string, principal: Principal, permission: AclPermission): WriteIntent => ({ method: 'DELETE', path: spacePaths.acl(kind, id), body: aclBody(kind, principal, permission) }),
  relocate: (id: string, from: string, to: string): WriteIntent => {
    if (positiveId(from) === positiveId(to)) throw new ApiError(400);
    return { method: 'PATCH', path: `${resource('documents', id)}/space`, body: { fromSpaceId: numericId(from), toSpaceId: numericId(to) } };
  },
  security: (id: string, value: number, version: Version, reason: string): WriteIntent => {
    const normalized = reason.trim();
    if (!normalized || [...normalized].length > 500 || normalized.includes('\0')) throw new ApiError(400);
    return { method: 'PATCH', path: spacePaths.security(id), body: { securityLevel: level(value), securityVersion: exactVersion(version), reason: normalized } };
  },
};
