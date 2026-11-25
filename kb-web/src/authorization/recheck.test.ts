import { expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { defineComponent, h, shallowRef } from 'vue';
import { renderHost, renderWith } from '../test/render';
import { deferred, json, me, storage } from '../test/fixtures';
import { Session } from '../session';
import { useSessionStore } from '../stores/session';
import { useAdminAccess } from '../admin/access';
import { useAdminResource } from '../admin/hooks';
import type { ManagedDocument } from '../admin/spaceApi';
import type { QueryPage } from '../types';
import AdminAccessProvider from '../admin/AdminAccessProvider.vue';
import AclPanel from '../admin/AclPanel.vue';
import { diagnosticFixture } from './fixtures';
import type { Binding } from './api';
import { provideAclFormRecovery } from '../admin/aclRecovery';
import { useSession } from '../SessionProvider';
import DocumentPanel from '../admin/DocumentPanel.vue';
import SpaceDetail from '../admin/SpaceDetail.vue';

// 使用真实权限 store、资源读取及 ACL 面板，仅替换网络传输。
async function managedPanel(kind: 'spaces' | 'documents' = 'documents', production = false) {
  const binding: Binding = { kind, id: kind === 'spaces' ? '10' : '20', spaceId: '10', userId: '8' };
  const controls = { diagnose: true, managed: true, spaceId: '10', status: 0, failWrite: false, candidateMissing: false, explain: null as Promise<Response> | null, membership: null as Promise<Response> | null };
  const transport = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const path = String(input);
    if (path === '/api/auth/me') return json(me);
    if (path === '/api/auth/permissions') return json(controls.diagnose ? ['authorization:diagnose'] : []);
    if (path === '/api/admin/spaces') return json([{ spaceId: '10', manageable: controls.managed, archived: false }]);
    if (path === '/api/admin/spaces/10') return json({ id: '10', name: '测试空间', code: 'test', manageable: controls.managed, archived: false, version: 1 });
    if (path === '/api/documents/20') return json({ documentId: '20', title: '测试文档' });
    if (path === '/api/admin/documents?documentId=20') return controls.membership ?? json({ items: controls.managed ? [{ documentId: '20', spaceId: controls.spaceId, spaceArchived: false }] : [], hasMore: false, nextCursor: null });
    if (path.includes('/authorization-diagnostics/')) {
      if (path.endsWith('/explain') && controls.explain) return controls.explain;
      if (controls.status) return json({ code: 500, data: null }, false, controls.status);
      if (path.endsWith('/users/search')) return json({ contract: 'AUTHORIZATION_DIAGNOSTIC_USERS_V1', resource: diagnosticFixture(binding).resource,
        items: [{ userId: '8', label: '目标用户' }], hasMore: false, nextCursor: null });
      return json(diagnosticFixture(binding));
    }
    if (path.includes('/acl/principals')) return json({ items: controls.candidateMissing ? [] : [{ principalType: 'USER', principalId: '8', label: '目标用户' }], hasMore: false, nextCursor: null });
    if (path.endsWith('/acl/batch')) return json({ spaceId: '10', items: [{ principalType: 'USER', principalId: '8', permission: 'download', alreadyGranted: false }], grantedCount: 1, alreadyGrantedCount: 0, clearanceBlockedPrincipalCount: 0, replayed: false });
    if (path.endsWith('/acl/batch-revoke')) return json({ spaceId: '10', items: [{ principalType: 'USER', principalId: '8', permission: 'view', removed: true }], removedCount: 1, absentCount: 0, replayed: false });
    if (path.endsWith('/acl') && init?.method === 'DELETE') return json(null);
    if (path.endsWith('/acl') && init?.method === 'POST') {
      if (controls.failWrite) throw new TypeError('offline');
      return json({ alreadyGranted: false, clearanceBlockedPrincipalCount: 0 });
    }
    if (path.endsWith('/acl')) return json([{ id: '1', docId: '20', principalType: 'USER', principalId: '8', principalName: '目标用户', principalKey: '1:8', permission: 'view' }]);
    // 其他管理区块的依赖保持独立不可用，不向 ACL 用例注入额外的资格重核。
    if (production && (!init?.method || init.method === 'GET')) return json({ code: 503, data: null }, false, 503);
    throw new Error(`Unexpected in-memory request: ${path}`);
  });
  const session = new Session(storage(), transport); await session.refresh();
  let recheck = () => {};
  const Resource = defineComponent({ setup() {
    const access = useAdminAccess(), auth = useSession();
    const owner = useAdminResource<QueryPage<ManagedDocument>>(signal => session.json('/api/admin/documents?documentId=20', { signal }), 'diagnostic-owner:20');
    provideAclFormRecovery(() => ({ identity: `${auth.state.epoch}:${kind}:${binding.id}`,
      spaceId: owner.data?.items[0]?.spaceId ?? null, checking: owner.loading || access.checking,
      allowed: !owner.error && !!owner.data?.items.length && access.status === 'ready' && access.spaceStatus === 'ready' }));
    recheck = () => { access.check(true); owner.reload(); };
    return () => !owner.loading && !owner.error && owner.data?.items.length && !access.checking
      ? h(AclPanel, { key: owner.data.items[0].spaceId, kind, resourceId: binding.id, spaceId: owner.data.items[0].spaceId, onRecheck: () => owner.reload() }) : h('p', '管理范围核对中或不可用');
  } });
  const mounted = shallowRef(true);
  const Host = defineComponent({ setup() {
    useSessionStore().bind(session);
    return () => h(AdminAccessProvider, null, { default: () => mounted.value
      ? production ? kind === 'documents' ? h(DocumentPanel, { id: '20' }) : h(SpaceDetail, { id: '10' }) : h(Resource) : null });
  } });
  const view = production ? await renderWith(Host) : renderHost(Host);
  await screen.findByRole('button', { name: '权限诊断' });
  await flushPromises();
  const calls = (suffix: string) => transport.mock.calls.filter(([path]) => String(path).endsWith(suffix));
  const writes = () => transport.mock.calls.filter(([path, init]) => /\/acl(?:\/batch(?:-revoke)?)?$/.test(String(path)) && ['POST', 'DELETE'].includes(init?.method ?? ''));
  return { ...view, binding, controls, transport, calls, writes, session, mounted, recheck: () => recheck() };
}

it.each([404, 405])('旧端点 %s 在真实资格重核及父级重挂载后保留提示，原授权仍能主动提交', async status => {
  const page = await managedPanel(); page.controls.status = status;
  await fireEvent.click(screen.getByRole('button', { name: '权限诊断' }));
  await screen.findByText('权限诊断暂不可用，请稍后重试。');
  await flushPromises();
  expect(page.calls('/users/search')).toHaveLength(1);
  expect(page.calls('/api/auth/permissions')).toHaveLength(status === 404 ? 2 : 1);
  expect(screen.queryByLabelText('诊断目标用户')).toBeNull();
  expect(page.writes()).toHaveLength(0);
  await fireEvent.click(screen.getByRole('button', { name: '授予授权' }));
  await screen.findByRole('option', { name: '目标用户' });
  await fireEvent.update(screen.getByLabelText('授权主体'), '8');
  await fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
  await waitFor(() => expect(page.writes()).toHaveLength(1));
  expect(page.calls('/preview')).toHaveLength(0);
});

it('诊断 403 后仅失去诊断资格时清除目标和结果，保留授权操作', async () => {
  const page = await managedPanel();
  await fireEvent.click(screen.getByRole('button', { name: '权限诊断' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  page.controls.status = 403; page.controls.diagnose = false;
  await fireEvent.click(screen.getByRole('button', { name: '查看权限解释' }));
  await waitFor(() => expect(page.calls('/api/auth/permissions')).toHaveLength(2));
  await screen.findByRole('button', { name: '授予授权' });
  expect(screen.queryByRole('button', { name: '权限诊断' })).toBeNull();
  expect(screen.queryByText('所选用户：目标用户')).toBeNull();
  expect(screen.queryByText(/计算时间/)).toBeNull();
  expect(page.calls('/explain')).toHaveLength(1); expect(page.writes()).toHaveLength(0);
});

it('诊断 404 后资源管理范围失效，旧响应不能恢复来源，恢复后不自动诊断', async () => {
  const page = await managedPanel(), late = deferred<Response>();
  await fireEvent.click(screen.getByRole('button', { name: '权限诊断' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  page.controls.explain = late.promise;
  await fireEvent.click(screen.getByRole('button', { name: '查看权限解释' }));
  page.controls.status = 404; page.controls.managed = false;
  await fireEvent.click(screen.getByRole('button', { name: '搜索诊断用户' }));
  await waitFor(() => expect(page.calls('/api/auth/permissions')).toHaveLength(2));
  await flushPromises(); late.resolve(json(diagnosticFixture(page.binding))); await flushPromises();
  expect(screen.queryByRole('button', { name: '权限诊断' })).toBeNull();
  expect(screen.queryByText(/计算时间/)).toBeNull();
  page.controls.managed = true; page.controls.status = 0; page.controls.explain = null; page.recheck();
  await screen.findByRole('button', { name: '权限诊断' });
  expect(screen.queryByLabelText('诊断目标用户')).toBeNull();
  expect(page.calls('/explain')).toHaveLength(1); expect(page.calls('/users/search')).toHaveLength(2);
});

it('未知正式写入跨真实资格重核保留原请求标识，迟到诊断不能解除绑定', async () => {
  const page = await managedPanel(), late = deferred<Response>();
  await fireEvent.click(screen.getByRole('button', { name: '权限诊断' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  page.controls.explain = late.promise;
  await fireEvent.click(screen.getByRole('button', { name: '查看权限解释' }));
  await fireEvent.click(screen.getByRole('button', { name: '授予授权' }));
  await screen.findByRole('option', { name: '目标用户' });
  await fireEvent.update(screen.getByLabelText('授权主体'), '8');
  page.controls.failWrite = true;
  await fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
  await screen.findByRole('button', { name: '按原内容重试' });
  const first = page.writes()[0][1]!;
  page.controls.diagnose = false; page.recheck();
  await screen.findByRole('button', { name: '授予授权' });
  late.resolve(json(diagnosticFixture(page.binding))); await flushPromises();
  expect(screen.queryByText(/计算时间/)).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '授予授权' }));
  await fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
  await waitFor(() => expect(page.writes()).toHaveLength(2));
  expect(page.writes()[1][1]!.body).toBe(first.body);
  expect(new Headers(page.writes()[1][1]!.headers).get('X-Request-Id')).toBe(new Headers(first.headers).get('X-Request-Id'));
  expect(page.calls('/explain')).toHaveLength(1); expect(page.calls('/preview')).toHaveLength(0);
});

async function draftPreview(page: Awaited<ReturnType<typeof managedPanel>>) {
  await fireEvent.click(screen.getByRole('button', { name: '授予授权' }));
  if (page.binding.kind === 'documents') {
    await screen.findByRole('option', { name: '目标用户' });
    await fireEvent.update(screen.getByLabelText('授权主体'), '8');
    await fireEvent.update(screen.getByLabelText('权限位'), 'download');
  } else {
    await fireEvent.click(await screen.findByRole('button', { name: '添加 目标用户' }));
    await fireEvent.click(screen.getByRole('checkbox', { name: 'view' }));
    await fireEvent.click(screen.getByRole('checkbox', { name: 'download' }));
  }
  await fireEvent.click(screen.getByRole('button', { name: '预演影响' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
}

it.each([403, 404, 405])('文档草稿预演 %s 后保留提示和原主体权限，用户可直接提交原内容', async status => {
  const page = await managedPanel(); await draftPreview(page);
  page.controls.status = status;
  if (status === 403) page.controls.diagnose = false;
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await screen.findByText(status === 403 ? '权限诊断请求被拒绝，请核对当前资格。' : '权限诊断暂不可用，请稍后重试。');
  await flushPromises();
  expect((screen.getByLabelText('授权主体') as HTMLSelectElement).value).toBe('8');
  expect((screen.getByLabelText('权限位') as HTMLSelectElement).value).toBe('download');
  expect(screen.queryByLabelText('诊断目标用户')).toBeNull();
  expect(page.calls('/preview')).toHaveLength(1); expect(page.writes()).toHaveLength(0);
  await fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
  await waitFor(() => expect(page.writes()).toHaveLength(1));
  expect(JSON.parse(String(page.writes()[0][1]!.body))).toEqual({ principalType: 'USER', principalId: 8, permission: 'download' });
});

it.each([403, 404, 405])('空间批量草稿预演 %s 后恢复同一批次并保持可见反馈', async status => {
  const page = await managedPanel('spaces'); await draftPreview(page); page.controls.status = status;
  if (status === 403) page.controls.diagnose = false;
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await screen.findByText(status === 403 ? '权限诊断请求被拒绝，请核对当前资格。' : '权限诊断暂不可用，请稍后重试。'); await flushPromises();
  expect((screen.getByRole('checkbox', { name: 'view' }) as HTMLInputElement).checked).toBe(false);
  expect((screen.getByRole('checkbox', { name: 'download' }) as HTMLInputElement).checked).toBe(true);
  expect(screen.getByLabelText('批量确认摘要').textContent).toContain('1 个主体、1 个权限位');
  expect(page.writes()).toHaveLength(0);
  await fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
  await waitFor(() => expect(page.writes()).toHaveLength(1));
  expect(JSON.parse(String(page.writes()[0][1]!.body))).toEqual({ principals: [{ principalType: 'USER', principalId: 8 }], permissions: ['download'] });
});

it.each(['失权', '搬迁', '离页', '身份刷新'] as const)('预演重核%s时不能恢复旧文档表单或自动提交', async change => {
  const page = await managedPanel(); await draftPreview(page);
  const membership = deferred<Response>(); page.controls.membership = membership.promise; page.controls.status = 404;
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: '确认提交' })).toBeNull());
  if (change === '离页') { page.mounted.value = false; await flushPromises(); }
  if (change === '身份刷新') { await page.session.refresh(); await flushPromises(); }
  const items = change === '失权' ? [] : [{ documentId: '20', spaceId: change === '搬迁' ? '11' : '10', spaceArchived: false }];
  membership.resolve(json({ items, hasMore: false, nextCursor: null })); await flushPromises();
  expect(screen.queryByRole('button', { name: '确认提交' })).toBeNull();
  page.controls.membership = null; page.controls.status = 0;
  if (change === '离页') page.mounted.value = true;
  else page.recheck();
  await screen.findByRole('button', { name: '授予授权' });
  expect(screen.queryByRole('button', { name: '确认提交' })).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '授予授权' }));
  await screen.findByRole('option', { name: '目标用户' });
  expect((screen.getByLabelText('授权主体') as HTMLSelectElement).value).toBe('');
  expect((screen.getByLabelText('权限位') as HTMLSelectElement).value).toBe('view');
  expect(page.writes()).toHaveLength(0); expect(page.calls('/preview')).toHaveLength(1);
});

it.each(['documents', 'spaces'] as const)('实际 %s 详情页在预演 404 后恢复草稿和提示，且写入需用户主动提交', async kind => {
  const page = await managedPanel(kind, true); await draftPreview(page); page.controls.status = 404;
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await screen.findByText('权限诊断暂不可用，请稍后重试。'); await flushPromises();
  if (kind === 'documents') {
    expect((screen.getByLabelText('授权主体') as HTMLSelectElement).value).toBe('8');
    expect((screen.getByLabelText('权限位') as HTMLSelectElement).value).toBe('download');
  } else {
    expect((screen.getByRole('checkbox', { name: 'download' }) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByLabelText('批量确认摘要').textContent).toContain('1 个主体、1 个权限位');
  }
  expect(page.calls('/preview')).toHaveLength(1); expect(page.writes()).toHaveLength(0);
  await fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
  await waitFor(() => expect(page.writes()).toHaveLength(1));
  const body = JSON.parse(String(page.writes()[0][1]!.body));
  expect(body).toEqual(kind === 'documents' ? { principalType: 'USER', principalId: 8, permission: 'download' }
    : { principals: [{ principalType: 'USER', principalId: 8 }], permissions: ['download'] });
});

it.each(['documents', 'spaces'] as const)('%s 撤销预演 404 后恢复原撤销动作，提交不变成授予', async kind => {
  const page = await managedPanel(kind);
  if (kind === 'spaces') {
    await fireEvent.click(screen.getByRole('checkbox', { name: '选择撤销 USER 目标用户 view' }));
    await fireEvent.click(screen.getByRole('button', { name: '批量撤销' }));
  } else await fireEvent.click(screen.getByRole('button', { name: '撤销 目标用户 view' }));
  await fireEvent.click(screen.getByRole('button', { name: '预演影响' }));
  await fireEvent.update(await screen.findByLabelText('诊断目标用户'), '8');
  page.controls.status = 404;
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await screen.findByText('权限诊断暂不可用，请稍后重试。'); await flushPromises();
  expect(page.writes()).toHaveLength(0);
  await fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
  await waitFor(() => expect(page.writes()).toHaveLength(1));
  const [path, request] = page.writes()[0];
  expect(String(path)).toBe(kind === 'spaces' ? '/api/admin/spaces/10/acl/batch-revoke' : '/api/admin/documents/20/acl');
  expect(request!.method).toBe(kind === 'spaces' ? 'POST' : 'DELETE');
  expect(JSON.parse(String(request!.body))).toEqual(kind === 'spaces'
    ? { items: [{ principalType: 'USER', principalId: 8, permission: 'view' }] }
    : { principalType: 'USER', principalId: 8, permission: 'view' });
});

it('恢复的文档主体不在候选首页时保留原选择，修改搜索条件才清除选择', async () => {
  const page = await managedPanel(); await draftPreview(page);
  page.controls.status = 404; page.controls.candidateMissing = true;
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await screen.findByText('权限诊断暂不可用，请稍后重试。'); await flushPromises();
  expect((screen.getByLabelText('授权主体') as HTMLSelectElement).value).toBe('8');
  expect(screen.getByRole('button', { name: '确认提交' }).hasAttribute('disabled')).toBe(false);
  await fireEvent.update(screen.getByLabelText('搜索主体'), '新的搜索'); await flushPromises();
  expect((screen.getByLabelText('授权主体') as HTMLSelectElement).value).toBe('');
  expect(screen.getByRole('button', { name: '确认提交' }).hasAttribute('disabled')).toBe(true);
  expect(page.writes()).toHaveLength(0);
});

it('明确关闭已恢复表单后，后续重核不自动再次恢复', async () => {
  const page = await managedPanel(); await draftPreview(page); page.controls.status = 404;
  await fireEvent.click(screen.getByRole('button', { name: '计算预演' }));
  await screen.findByText('权限诊断暂不可用，请稍后重试。'); await flushPromises();
  await fireEvent.click(screen.getByRole('button', { name: /^(关闭此对话框|Close this dialog)$/ }));
  page.recheck(); await screen.findByRole('button', { name: '授予授权' });
  expect(screen.queryByRole('button', { name: '确认提交' })).toBeNull();
  expect(page.calls('/preview')).toHaveLength(1); expect(page.writes()).toHaveLength(0);
});
