import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { defineComponent, h, shallowRef, type Component } from 'vue';
import { renderHost } from '../test/render';
import { provideAccess } from '../test/access';
import { deferred, json, me, storage } from '../test/fixtures';
import { emptyAdminAccess, perm } from '../admin/access';
import { Session } from '../session';
import { useSessionStore } from '../stores/session';
import { spaceWrites } from '../admin/spaceApi';
import DiagnosticHost from './DiagnosticHost.vue';
import AclPanel from '../admin/AclPanel.vue';
import { diagnosticFixture } from './fixtures';
import { fromWrite, type Binding, type Source } from './api';
import { provideDocumentOperations } from '../documents/OperationContext';
import { DocumentOperations } from '../documents/operations';

vi.mock('../admin/access', async original => (await import('../test/access')).withFakeAccess(await original()));
const check = vi.fn();
beforeEach(() => { check.mockReset(); provideAccess({ ...emptyAdminAccess, status: 'ready', spaceStatus: 'ready', has: code => code === perm.diagnose, check }); });
async function show(component: Component, props: Record<string, unknown>, transport: typeof fetch, operations?: DocumentOperations) {
  const saved = storage(), session = new Session(saved, transport); await session.refresh();
  const current = shallowRef(props);
  const Host = defineComponent({ setup() { useSessionStore().bind(session); if (operations) provideDocumentOperations(() => operations); return () => h(component, current.value); } });
  return { ...renderHost(Host), session, saved, async update(next: Record<string, unknown>) { current.value = { ...current.value, ...next }; await flushPromises(); } };
}
function response(path: string, binding: Binding) {
  if (path === '/api/auth/me') return json(me);
  if (path.endsWith('/users/search')) return json({ contract: 'AUTHORIZATION_DIAGNOSTIC_USERS_V1', resource: diagnosticFixture({ ...binding, documentId: null }).resource, items: [{ userId: binding.userId, label: '目标用户' }], hasMore: false, nextCursor: null });
  if (path.endsWith('/explain') || path.endsWith('/preview')) return json(diagnosticFixture(binding));
  if (path.endsWith('/acl')) return json([{ id: '1', spaceId: binding.spaceId, principalType: 'USER', principalId: binding.userId, principalName: '目标用户', principalKey: `1:${binding.userId}`, permission: 'view' }]);
  return json({ items: [], hasMore: false, nextCursor: null });
}
it('独立资格确认后才显示入口；从 USER 行进入只读解释且显示标签为文本', async () => {
  const binding: Binding = { kind: 'spaces', id: '10', spaceId: '10', userId: '8' };
  const transport = vi.fn(async (path: any) => response(String(path), binding));
  provideAccess({ ...emptyAdminAccess, status: 'ready', spaceStatus: 'ready', has: () => false, check });
  const view = await show(AclPanel, { kind: 'spaces', resourceId: '10', archived: true }, transport);
  await screen.findByText('USER · 目标用户 · view'); expect(screen.queryByText('权限诊断')).toBeNull();
  expect(transport.mock.calls.filter(([path]) => String(path).includes('authorization-diagnostics'))).toHaveLength(0);
  provideAccess({ ...emptyAdminAccess, status: 'ready', spaceStatus: 'ready', has: code => code === perm.diagnose, check });
  await fireEvent.click(await screen.findByRole('button', { name: '诊断此用户' }));
  await fireEvent.click(await screen.findByRole('button', { name: '查看权限解释' }));
  await screen.findByText(/计算时间/); expect(view.container.querySelector('img')).toBeNull();
  expect(transport.mock.calls.some(([path]) => String(path).includes('/identity/'))).toBe(false);
});
it('管理但无正文资格可以查看文档层；关闭、草稿变化与写入阻断清除旧预演', async () => {
  const write = spaceWrites.grant('documents', '20', { principalType: 'USER', principalId: '8', label: '目标' }, 'view');
  const binding: Binding = { kind: 'documents', id: '20', spaceId: '10', userId: '8', intent: fromWrite('documents', write) };
  const transport = vi.fn(async (path: any) => response(String(path), binding));
  const view = await show(DiagnosticHost, { kind: 'documents', resourceId: '20', spaceId: '10', managed: true, preview: true, writeIntent: write }, transport);
  await fireEvent.click(screen.getByRole('button', { name: '预演影响' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await screen.findByText('若提交后'); expect(screen.getAllByText(/没有当前发布版本/)).toHaveLength(2);
  expect(transport.mock.calls.some(([path]) => String(path) === '/api/documents/20')).toBe(false);
  await view.update({ writeIntent: spaceWrites.grant('documents', '20', { principalType: 'USER', principalId: '8', label: '目标' }, 'download') });
  expect(screen.queryByText('若提交后')).toBeNull();
  await view.update({ blocked: true }); expect((screen.getByRole('button', { name: '计算预演' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '关闭诊断' })); expect(screen.queryByText('所选用户：目标用户')).toBeNull();
  expect(view.saved.setItem).not.toHaveBeenCalled();
});
it('隐藏清理结果；迟到响应不能恢复，403 只触发一次资格核对', async () => {
  const binding: Binding = { kind: 'spaces', id: '10', spaceId: '10', userId: '8' }, late = deferred<Response>();
  let denied = false;
  const transport = vi.fn(async (path: any) => String(path).endsWith('/explain') ? denied ? json({ code: 500, data: null, reasonCode: 'FORBIDDEN' }, false, 403) : late.promise : response(String(path), binding));
  await show(DiagnosticHost, { kind: 'spaces', resourceId: '10', spaceId: '10', managed: true }, transport);
  await fireEvent.click(screen.getByRole('button', { name: '权限诊断' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8'); await fireEvent.click(screen.getByRole('button', { name: '查看权限解释' }));
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange')); await flushPromises();
  late.resolve(json(diagnosticFixture(binding))); await flushPromises(); expect(screen.queryByText(/计算时间/)).toBeNull();
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange')); denied = true;
  await fireEvent.click(screen.getByRole('button', { name: '搜索诊断用户' })); await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  await fireEvent.click(screen.getByRole('button', { name: '查看权限解释' })); await waitFor(() => expect(check).toHaveBeenCalledTimes(1));
  expect(screen.queryByText(/计算时间/)).toBeNull(); vi.restoreAllMocks();
});

it('预演失败不自动提交；正式写入未知时关闭重开保留原标识并禁止新预演', async () => {
  const binding: Binding = { kind: 'documents', id: '20', spaceId: '10', userId: '8' };
  const transport = vi.fn(async (path: any, init?: RequestInit) => {
    const url = String(path);
    if (url.includes('/acl/principals')) return json({ items: [{ principalType: 'USER', principalId: '8', label: '目标用户' }], hasMore: false, nextCursor: null });
    if (url.endsWith('/preview')) return json({ code: 500, data: null, reasonCode: 'DIAGNOSTIC_UNAVAILABLE' }, false, 503);
    if (url.endsWith('/acl') && init?.method === 'POST') throw new TypeError('offline');
    return response(url, binding);
  });
  await show(AclPanel, { kind: 'documents', resourceId: '20', spaceId: '10' }, transport);
  await fireEvent.click(await screen.findByRole('button', { name: '授予授权' }));
  await screen.findByRole('option', { name: '目标用户' });
  await fireEvent.update(screen.getByLabelText('授权主体'), '8');
  await fireEvent.click(screen.getByRole('button', { name: '预演影响' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await screen.findByText('服务暂不可用，请稍后重试。');
  const writes = () => transport.mock.calls.filter(([path, init]) => String(path).endsWith('/acl') && init?.method === 'POST');
  expect(writes()).toHaveLength(0);
  await fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
  await screen.findByRole('button', { name: '按原内容重试' });
  const first = writes()[0][1]!; expect((screen.getByRole('button', { name: '计算预演' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: /^(关闭此对话框|Close this dialog)$/ })); await flushPromises();
  await fireEvent.click(screen.getByRole('button', { name: '授予授权' }));
  await fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
  await waitFor(() => expect(writes()).toHaveLength(2));
  expect(new Headers(writes()[1][1]!.headers).get('X-Request-Id')).toBe(new Headers(first.headers).get('X-Request-Id'));
  expect(writes()[1][1]!.body).toBe(first.body);
  expect(transport.mock.calls.filter(([path]) => String(path).endsWith('/preview'))).toHaveLength(1);
});

it('文档父级删除和写入协调取消迟到解释，密级资格本身不能开放诊断', async () => {
  const binding: Binding = { kind: 'documents', id: '20', spaceId: '10', userId: '8' }, late = deferred<Response>();
  const transport = vi.fn(async (path: any) => String(path).endsWith('/explain') ? late.promise : response(String(path), binding));
  const operations = new DocumentOperations();
  await show(AclPanel, { kind: 'documents', resourceId: '20', spaceId: '10' }, transport, operations);
  await fireEvent.click(await screen.findByRole('button', { name: '诊断此用户' }));
  await fireEvent.click(await screen.findByRole('button', { name: '查看权限解释' }));
  operations.beginDelete(); await flushPromises(); late.resolve(json(diagnosticFixture(binding))); await flushPromises();
  expect(screen.queryByText(/计算时间/)).toBeNull(); expect((screen.getByRole('button', { name: '查看权限解释' }) as HTMLButtonElement).disabled).toBe(true);
  operations.cancelDelete(); const writing = operations.beginWrite('security:20')!; await flushPromises();
  writing.settle('uncertain'); await flushPromises(); expect((screen.getByRole('button', { name: '查看权限解释' }) as HTMLButtonElement).disabled).toBe(true);
  provideAccess({ ...emptyAdminAccess, status: 'ready', spaceStatus: 'ready', has: code => code === perm.securityLevelWrite, check }); await flushPromises();
  expect(screen.queryByRole('button', { name: '权限诊断' })).toBeNull(); expect(operations.getSnapshot().uncertain).toBe(1);
});

it('完整撤销预演显示个人来源拟移除、部门来源保留和访问结论不变', async () => {
  const write = spaceWrites.revoke('spaces', '10', { principalType: 'USER', principalId: '8', label: '目标' }, 'view');
  const binding: Binding = { kind: 'spaces', id: '10', spaceId: '10', userId: '8', documentId: '20', intent: fromWrite('spaces', write) };
  const sources: Source[] = [
    { key: 'SPACE:1:8:view', sourceKind: 'SPACE', principalType: 'USER', principalId: '8', permission: 'view', label: '个人来源', origin: 'EXISTING' },
    { key: 'SPACE:3:5:view', sourceKind: 'SPACE', principalType: 'DEPT', principalId: '5', permission: 'view', label: '部门来源', origin: 'EXISTING' },
  ];
  const transport = vi.fn(async (path: any) => {
    if (String(path).startsWith('/api/admin/documents?')) return json({ items: [{ documentId: '20', spaceId: '10', spaceArchived: false }], hasMore: false, nextCursor: null });
    if (String(path).endsWith('/preview')) return json(diagnosticFixture(binding, { sources, current: true }));
    return response(String(path), binding);
  });
  await show(DiagnosticHost, { kind: 'spaces', resourceId: '10', spaceId: '10', managed: true, preview: true, writeIntent: write }, transport);
  await fireEvent.click(screen.getByRole('button', { name: '预演影响' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  await fireEvent.click(screen.getByRole('button', { name: '选择同空间文档（可选）' }));
  await fireEvent.update(await screen.findByLabelText('观察文档'), '20');
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  const after = await screen.findByRole('region', { name: '若提交后' }), before = screen.getByRole('region', { name: '当前' });
  expect(within(before).getAllByText(/个人来源/)).toHaveLength(2);
  expect(within(after).queryByText(/个人来源/)).toBeNull();
  expect(within(after).getAllByText(/部门来源/)).toHaveLength(2);
  expect(within(before).getByText('查看：权限条件允许')).toBeTruthy();
  expect(within(after).getByText('查看：权限条件允许')).toBeTruthy();
  expect(screen.getByText('USER 8 · view · 拟移除')).toBeTruthy();
  expect(screen.getByText(/授权判定和访问结果不变/)).toBeTruthy();
});

it.each(['归档', '密级'] as const)('新增文档授权后仍显示%s阻断，不把新增来源当成可读', async condition => {
  const write = spaceWrites.grant('documents', '20', { principalType: 'USER', principalId: '8', label: '目标' }, 'view');
  const binding: Binding = { kind: 'documents', id: '20', spaceId: '10', userId: '8', intent: fromWrite('documents', write) };
  const transport = vi.fn(async (path: any) => String(path).endsWith('/preview')
    ? json(diagnosticFixture(binding, { current: true, archived: condition === '归档', clearanceBlocked: condition === '密级' })) : response(String(path), binding));
  await show(DiagnosticHost, { kind: 'documents', resourceId: '20', spaceId: '10', managed: true, preview: true, writeIntent: write }, transport);
  await fireEvent.click(screen.getByRole('button', { name: '预演影响' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  const after = await screen.findByRole('region', { name: '若提交后' });
  expect(within(after).getByText(/view：授权命中；通用条件未通过/)).toBeTruthy();
  expect(within(after).getByText(/查看：权限条件不允许/)).toBeTruthy();
  expect(within(after).getAllByText(condition === '归档' ? /空间已归档/ : /密级条件未通过/).length).toBeGreaterThan(0);
  expect(screen.getByText('USER 8 · view · 拟新增')).toBeTruthy(); expect(within(after).queryByRole('link')).toBeNull();
});

it('同空间文档选择只读取 ID 清单，分页后按明确选择绑定诊断并清除旧结果', async () => {
  const binding: Binding = { kind: 'spaces', id: '10', spaceId: '10', userId: '8' };
  const transport = vi.fn(async (path: any, init?: RequestInit) => {
    const url = String(path);
    if (url.startsWith('/api/admin/documents?')) {
      const next = url.includes('cursor=next');
      return json({ items: [{ documentId: next ? '21' : '20', spaceId: '10', spaceArchived: false }], hasMore: !next, nextCursor: next ? null : 'next' });
    }
    if (url.endsWith('/explain')) return json(diagnosticFixture({ ...binding, documentId: String(JSON.parse(String(init?.body)).documentId) }));
    return response(url, binding);
  });
  await show(DiagnosticHost, { kind: 'spaces', resourceId: '10', spaceId: '10', managed: true }, transport);
  await fireEvent.click(screen.getByRole('button', { name: '权限诊断' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  await fireEvent.click(screen.getByRole('button', { name: '选择同空间文档（可选）' }));
  await screen.findByRole('option', { name: '文档 20' });
  await fireEvent.click(screen.getByRole('button', { name: '下一页文档' }));
  await screen.findByRole('option', { name: '文档 21' });
  await fireEvent.update(screen.getByLabelText('观察文档'), '21');
  await fireEvent.click(screen.getByRole('button', { name: '查看权限解释' }));
  await screen.findByText(/计算时间/);
  const explain = transport.mock.calls.find(([path]) => String(path).endsWith('/explain'))!;
  expect(JSON.parse(String(explain[1]?.body))).toEqual({ userId: 8, documentId: 21 });
  expect(transport.mock.calls.filter(([path]) => String(path).startsWith('/api/admin/documents?')).map(([path]) => path))
    .toEqual(['/api/admin/documents?spaceId=10&limit=100', '/api/admin/documents?spaceId=10&limit=100&cursor=next']);
  expect(transport.mock.calls.some(([path]) => /^\/api\/documents\//.test(String(path)))).toBe(false);
  await fireEvent.update(screen.getByLabelText('观察文档'), '');
  expect(screen.queryByText(/计算时间/)).toBeNull();
});
