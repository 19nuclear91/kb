import { describe, expect, it } from 'vitest';
import { decodeJson, encodeJson, readJson } from '../api';
import { json } from '../test/fixtures';
import { spacePaths, spaceWrites } from './spaceApi';
import type { AclPermission, AdminSpace, BatchGrantResult, DeleteDocumentResult, GrantResult, RelocationResult, SecurityResult } from './spaceApi';
describe('空间与文档管理契约', () => {
  it('批量授予构造单个意图，主体与权限去重，大整数不失真', async () => {
    const user = { principalType: 'USER' as const, principalId: '9223372036854775807', label: '甲' };
    const role = { ...user, principalType: 'ROLE' as const };
    const intent = spaceWrites.grantBatch('9007199254740993', [user, role, user], ['view', 'download', 'view']);
    expect(intent).toEqual({ method: 'POST', path: '/api/admin/spaces/9007199254740993/acl/batch', body: {
      principals: [{ principalType: 'USER', principalId: 9223372036854775807n }, { principalType: 'ROLE', principalId: 9223372036854775807n }],
      permissions: ['view', 'download'],
    } });
    expect(encodeJson(intent.body)).toContain('"principalId":9223372036854775807');
    expect(intent.body).not.toHaveProperty('requestId');
    const result = await readJson<BatchGrantResult>(json({ spaceId: 9007199254740993n,
      items: [{ principalType: 'USER', principalId: 9223372036854775807n, permission: 'view', alreadyGranted: true }],
      grantedCount: 0, alreadyGrantedCount: 1, clearanceBlockedPrincipalCount: 0, replayed: true }));
    expect(result).toMatchObject({ spaceId: '9007199254740993', grantedCount: 0, alreadyGrantedCount: 1, replayed: true });
    expect(result.items[0]).toEqual({ principalType: 'USER', principalId: '9223372036854775807', permission: 'view', alreadyGranted: true });
  });
  it('批量去重后检查 50/6 上限，空列表与无效权限在发送前拒绝', () => {
    const principals = Array.from({ length: 51 }, (_, index) => ({ principalType: 'DEPT' as const, principalId: String(index + 1), label: '部门' }));
    const bits: AclPermission[] = ['view', 'download', 'edit', 'review', 'comment', 'manage'];
    expect(() => spaceWrites.grantBatch('7', principals, ['view'])).toThrow('50');
    expect(() => spaceWrites.grantBatch('7', principals.slice(0, 50), bits)).not.toThrow();
    expect(() => spaceWrites.grantBatch('7', Array(51).fill(principals[0]), [...bits, 'view'])).not.toThrow();
    expect(() => spaceWrites.grantBatch('7', [], ['view'])).toThrow('50');
    expect(() => spaceWrites.grantBatch('7', [principals[0]], [])).toThrow('6');
    expect(() => spaceWrites.grantBatch('7', [principals[0]], [...bits, 'share'] as AclPermission[])).toThrow('6');
    expect(() => spaceWrites.grantBatch('7', [principals[0]], ['share'] as unknown as AclPermission[])).toThrow('不支持');
    expect(() => spaceWrites.grantBatch('0', [principals[0]], ['view'])).toThrow();
    expect(() => spaceWrites.grantBatch('7', [{ ...principals[0], principalId: '9223372036854775808' }], ['view'])).toThrow();
  });
  it('读取字段、空响应及大整数不失真', async () => {
    const spaces = await readJson<AdminSpace[]>(json([{ spaceId: 9007199254740993n, version: 9007199254740995n, code: 's', manageable: true }]));
    expect(spaces[0]).toMatchObject({ spaceId: '9007199254740993', version: 9007199254740995n, manageable: true });
    const grant = await readJson<GrantResult>(json({ alreadyGranted: true, clearanceBlockedPrincipalCount: 2, acl: { principalId: 9007199254740993n } }));
    expect(grant.acl.principalId).toBe('9007199254740993'); expect(grant.alreadyGranted).toBe(true);
    expect(await readJson(json({ items: [], hasMore: false, nextCursor: null }))).toEqual({ items: [], hasMore: false, nextCursor: null });
    expect(await readJson(new Response(null, { status: 204 }))).toBeUndefined();
    const move = await readJson<RelocationResult>(json({ clearedExceptionCount: 3, clearedExceptions: ['USER:2:view'], clearedExceptionsTruncated: true, clearanceBlockedPrincipalCount: 1, replayed: true }));
    expect(move).toMatchObject({ clearedExceptionCount: 3, clearedExceptionsTruncated: true, clearanceBlockedPrincipalCount: 1, replayed: true });
    expect(await readJson<SecurityResult>(json({ securityVersion: 9007199254740995n, previousSecurityLevel: 1, securityLevel: 2 }))).toMatchObject({ securityVersion: 9007199254740995n, previousSecurityLevel: 1, securityLevel: 2 });
  });
  it('只编码已支持的查询，游标与上下文绑定', () => {
    expect(spacePaths.documents({ spaceId: '7', cursor: 'a+/=?', limit: 1 })).toBe('/api/admin/documents?spaceId=7&limit=1&cursor=a%2B%2F%3D%3F');
    expect(spacePaths.principals('documents', '9', 'DEPT', '  %_部门  ', 'x+')).toContain('principalType=DEPT&q=%25_%E9%83%A8%E9%97%A8&cursor=x%2B');
    for (const bad of ['0', '-1', 'abc', '9223372036854775808']) expect(() => spacePaths.space(bad)).toThrow();
    expect(() => spacePaths.documents({ limit: 101 })).toThrow();
    expect(() => spacePaths.principals('spaces', '7', 'USER', '😀'.repeat(128))).not.toThrow();
    expect(() => spacePaths.principals('spaces', '7', 'USER', '😀'.repeat(129))).toThrow();
  });
  it('十一类写入的形状、精确数值和请求头绑定', () => {
    const person = { principalType: 'USER' as const, principalId: '9007199254740993', label: '甲' };
    const writes = [spaceWrites.create('s', '空间', ''), spaceWrites.update('7', '空间', '', 9007199254740995n), spaceWrites.archive('7', true, 1), spaceWrites.archive('7', false, 2), spaceWrites.delete('7'), spaceWrites.grant('spaces', '7', person, 'manage'), spaceWrites.revoke('spaces', '7', person, 'view'), spaceWrites.grant('documents', '8', person, 'edit'), spaceWrites.revoke('documents', '8', person, 'download'), spaceWrites.relocate('8', '7', '9', null, 9007199254740995n), spaceWrites.security('8', 2, 9007199254740995n, '  原因  ')];
    expect(writes).toHaveLength(11); expect(writes[4].body).toBeUndefined();
    for (const write of writes) expect(write.body ?? {}).not.toHaveProperty('requestId');
    expect(encodeJson(writes[1].body)).toContain('"version":9007199254740995');
    expect(encodeJson(writes[5].body)).toContain('"principalId":9007199254740993');
    expect(writes[9].body).toEqual({ fromSpaceId: 7n, toSpaceId: 9n, targetCategoryId: null, expectedMetadataVersion: 9007199254740995n });
    expect(decodeJson(encodeJson(writes[10].body))).toMatchObject({ securityVersion: 9007199254740995n, reason: '原因' });
    expect(() => spaceWrites.grant('documents', '8', person, 'manage')).toThrow();
    // comment 在空间与文档例外均可授予；review 只属于空间，文档例外不接受。
    expect(spaceWrites.grant('spaces', '7', person, 'comment').body).toMatchObject({ permission: 'comment' });
    expect(spaceWrites.revoke('documents', '8', person, 'comment').body).toMatchObject({ permission: 'comment' });
    expect(spaceWrites.grant('spaces', '7', person, 'review').body).toMatchObject({ permission: 'review' });
    expect(() => spaceWrites.grant('documents', '8', person, 'review')).toThrow();
    expect(() => spaceWrites.security('8', 2, 0, ' ')).toThrow();
    expect(() => spaceWrites.security('8', 2, 0, '😀'.repeat(500))).not.toThrow();
    expect(() => spaceWrites.security('8', 2, 0, '😀'.repeat(501))).toThrow();
    expect(() => spaceWrites.create('', '空间', '')).toThrow(); expect(() => spaceWrites.create('x'.repeat(65), '空间', '')).toThrow();
    expect(() => spaceWrites.relocate('8', '7', '7', null, 0)).toThrow();
  });
  it('管理端删除文档：DELETE 到文档资源、无请求体、回执精确整数', async () => {
    const intent = spaceWrites.deleteDocument('8');
    expect(intent).toEqual({ method: 'DELETE', path: '/api/admin/documents/8' });
    expect(intent.body).toBeUndefined();
    expect(intent).not.toHaveProperty('body.requestId');
    for (const bad of ['0', '-1', 'abc', '', '9223372036854775808']) expect(() => spaceWrites.deleteDocument(bad)).toThrow();
    const receipt = await readJson<DeleteDocumentResult>(json({ documentId: 9007199254740993n, spaceId: 9007199254740995n, replayed: true }));
    expect(receipt).toEqual({ documentId: '9007199254740993', spaceId: '9007199254740995', replayed: true });
  });
});
