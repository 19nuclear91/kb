import { describe, expect, it, vi } from 'vitest';
import { Session } from '../session';
import { json, storage } from '../test/fixtures';
import { decodeResult, diagnosticApi, fromWrite, type Binding, type Source, type Result } from './api';
import { diagnosticFixture } from './fixtures';
import { spaceWrites } from '../admin/spaceApi';

const binding: Binding = { kind: 'spaces', id: '10', spaceId: '10', userId: '9223372036854775807' };
describe('严格诊断契约', () => {
  it('整体拒绝身份、范围、版本、缺字段、矛盾状态和重复来源', () => {
    const valid = diagnosticFixture(binding); expect(decodeResult(valid, binding)).toEqual(valid);
    for (const change of [
      (row: any) => { row.user.userId = '9'; }, (row: any) => { row.resource.spaceId = '11'; },
      (row: any) => { row.contract = 'V2'; }, (row: any) => { row.mode = 'PREVIEW'; },
      (row: any) => { delete row.before; }, (row: any) => { row.before.spacePermissions[0].grantMatch = 'MATCHED'; },
      (row: any) => { row.before.currentVersionAccess.view = 'DENIED'; }, (row: any) => { row.before.subjectCondition = 'BLOCKED'; },
      (row: any) => { row.before.sources = [{ key: 'SPACE:1:1:view', sourceKind: 'SPACE', principalType: 'USER', principalId: '1', permission: 'view', origin: 'EXISTING', label: 'a' }, { key: 'SPACE:1:1:view', sourceKind: 'SPACE', principalType: 'USER', principalId: '1', permission: 'view', origin: 'EXISTING', label: 'a' }]; },
      (row: any) => { row.before.spacePermissions[0].sourceKeys = ['missing']; },
      (row: any) => { row.user.clearance = 4; }, (row: any) => { row.user.userId = 8; },
    ]) { const bad = structuredClone(valid); change(bad); expect(() => decodeResult(bad, binding)).toThrow(); }
  });
  it('绑定完整笛卡尔积与显式撤销，换序等价但缺项拒绝', () => {
    const principals = [{ principalType: 'USER' as const, principalId: '8', label: '目标' }, { principalType: 'DEPT' as const, principalId: '5', label: '部门' }];
    const grant = fromWrite('spaces', spaceWrites.grantBatch('10', principals, ['view', 'download']));
    expect(grant.entries).toHaveLength(4);
    const revoke = fromWrite('spaces', spaceWrites.revokeBatch('10', [{ ...principals[0], permission: 'view' }, { ...principals[1], permission: 'download' }]));
    expect(revoke.entries).toHaveLength(2);
    const selected = { ...binding, intent: revoke }, result = diagnosticFixture(selected);
    result.changes.reverse(); expect(decodeResult(result, selected).changes).toHaveLength(2);
    result.changes.pop(); expect(() => decodeResult(result, selected)).toThrow();
  });
  it('精确大整数仅通过正文发送，超大响应和错误只返回固定消息', async () => {
    const transport = vi.fn(async () => json(diagnosticFixture(binding)));
    const session = new Session(storage(), transport);
    const value = await diagnosticApi.diagnose(session, binding); expect(value.user.userId).toBe(binding.userId);
    const [path, init] = transport.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/admin/spaces/10/authorization-diagnostics/explain');
    expect(init.body).toBe('{"userId":9223372036854775807}');
    transport.mockImplementation(async () => new Response(' '.repeat(524289), { headers: { 'Content-Type': 'application/json' } }));
    await expect(diagnosticApi.diagnose(session, binding)).rejects.toMatchObject({ status: 503 });
    transport.mockImplementation(async () => json({ code: 500, data: null, message: 'private raw database error', reasonCode: 'DIAGNOSTIC_UNAVAILABLE' }, false, 503));
    await expect(diagnosticApi.diagnose(session, binding)).rejects.toMatchObject({ status: 503, message: '服务暂不可用，请稍后重试。' });
  });
  const source: Source = { key: 'SPACE:1:8:view', sourceKind: 'SPACE', principalType: 'USER', principalId: '8', permission: 'view', origin: 'EXISTING', label: '用户' };
  const own: Binding = { kind: 'spaces', id: '10', spaceId: '10', userId: '8', intent: { type: 'SPACE_GRANT', entries: [{ sourceKind: 'SPACE', principalType: 'USER', principalId: '8', permission: 'view' }] } };
  it.each([
    ['新增', 'SPACE_GRANT', [], 'ADDED'], ['重复授予', 'SPACE_GRANT', [source], 'UNCHANGED'],
    ['移除', 'SPACE_REVOKE', [source], 'REMOVED'], ['缺失撤销', 'SPACE_REVOKE', [], 'UNCHANGED'],
  ] as const)('接受合法%s并核对来源和条目变化', (_, type, sources, outcome) => {
    const bound: Binding = { ...own, intent: { ...own.intent!, type } };
    const result = diagnosticFixture(bound, { sources: [...sources] });
    expect(decodeResult(result, bound)).toEqual(result); expect(result.changes[0].outcome).toBe(outcome);
  });
  it('拒绝已有行新增、必然命中行缺失、错误撤销和不属于目标的直接来源', () => {
    const repeated = diagnosticFixture(own, { sources: [source] }); repeated.changes[0].outcome = 'ADDED';
    const absent = diagnosticFixture(own); absent.after = structuredClone(absent.before);
    const unchanged = diagnosticFixture(own); unchanged.after = structuredClone(unchanged.before); unchanged.changes[0].outcome = 'UNCHANGED';
    for (const bad of [repeated, absent, unchanged]) expect(() => decodeResult(bad, own)).toThrow();
    const revoke: Binding = { ...own, intent: { ...own.intent!, type: 'SPACE_REVOKE' } };
    const kept = diagnosticFixture(revoke, { sources: [source] }); kept.after = structuredClone(kept.before); kept.changes[0].outcome = 'UNCHANGED';
    const missing = diagnosticFixture(revoke); missing.changes[0].outcome = 'REMOVED';
    for (const bad of [kept, missing]) expect(() => decodeResult(bad, revoke)).toThrow();
    const explain: Binding = { ...own, intent: null };
    const other = diagnosticFixture(explain, { sources: [{ ...source, key: 'SPACE:1:9:view', principalId: '9' }] });
    expect(() => decodeResult(other, explain)).toThrow();
  });
  it('仅对已披露的角色命中关系交叉校验，不猜测无关主体或受阻账号的成员关系', () => {
    const role: Source = { ...source, key: 'SPACE:2:5:view', principalType: 'ROLE', principalId: '5' };
    const selected: Binding = { ...own, intent: { type: 'SPACE_GRANT', entries: [{ sourceKind: 'SPACE', principalType: 'ROLE', principalId: '5', permission: 'download' }] } };
    const valid = diagnosticFixture(selected, { sources: [role] }); expect(decodeResult(valid, selected)).toEqual(valid);
    const bad = structuredClone(valid); bad.after = structuredClone(bad.before); expect(() => decodeResult(bad, selected)).toThrow();
    const unrelated = diagnosticFixture(selected); expect(decodeResult(unrelated, selected)).toEqual(unrelated);
    const blocked: Result = diagnosticFixture(own);
    for (const layer of [blocked.before, blocked.after!]) {
      layer.subjectCondition = 'BLOCKED'; layer.sources = [];
      for (const row of layer.spacePermissions) Object.assign(row, { grantMatch: 'NOT_EVALUATED', scopeCondition: 'NOT_EVALUATED', reasonCodes: ['SUBJECT_BLOCKED'], sourceKeys: [] });
    }
    expect(decodeResult(blocked, own)).toEqual(blocked);
  });
});
