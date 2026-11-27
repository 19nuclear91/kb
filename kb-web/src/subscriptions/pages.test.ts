import { h, nextTick, shallowReactive } from 'vue';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { expect, it, vi } from 'vitest';
import type { Session, SessionState } from '../session';
import { mountRouter } from '../test/app';
import { chooseOption } from '../test/render';
import { deferred, me } from '../test/fixtures';
import { at, detail, generation, id, page, preference, ready, receipt, response, target } from './testSupport';

const context = shallowReactive({ session: {} as Session, state: { phase: 'anonymous', epoch: 0, me: null } as SessionState });
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
import SubscriptionWorkspace from './SubscriptionWorkspace.vue';
import UpdateWorkspace from './UpdateWorkspace.vue';
import UpdatesWorkspace from './UpdatesWorkspace.vue';
import KnowledgePage from '../pages/KnowledgePage.vue';
import DigestsWorkspace from './DigestsWorkspace.vue';
import { kinds, type Kind } from './types';

async function connect(next: (path: string, options: RequestInit) => Response | Promise<Response>, identity = me) {
  const value = await ready(next, identity); context.session = value.session; context.state = value.session.getSnapshot();
  value.session.subscribe(() => { context.state = value.session.getSnapshot(); }); return value;
}
it('响应丢失后重读状态不宣称成功，核对UNKNOWN后只重试同一意图', async () => {
  let failed = true, original = '';
  const { transport, saved } = await connect((path, options) => {
    if (path === '/api/knowledge-subscription-preferences') return response(preference);
    if (path.includes('/target-state')) return response(target);
    if (path.endsWith('/receipts/query')) return response({ requestId: original, status: 'UNKNOWN', receipt: null });
    if (path === '/api/knowledge-subscriptions' && options.method === 'POST') {
      original = new Headers(options.headers).get('X-Request-Id')!;
      if (failed) { failed = false; throw new Error('lost'); } return response(receipt(original));
    }
    return response(page());
  });
  mountRouter(() => h(SubscriptionWorkspace, { documentId: target.targetId }));
  await fireEvent.click(await screen.findByRole('button', { name: '确认订阅当前文档' }));
  await screen.findByText(/原请求结果尚未确认/);
  await fireEvent.click(screen.getByRole('button', { name: '重新读取当前状态' }));
  expect(screen.queryByText(/原请求已确认/)).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '核对原回执' })); await screen.findByText(/尚未查到原回执/);
  await fireEvent.click(screen.getByRole('button', { name: '重试同一请求' })); await screen.findByText(/原请求已确认/);
  const writes = transport.mock.calls.filter(([path, options]) => String(path) === '/api/knowledge-subscriptions' && options?.method === 'POST');
  expect(writes).toHaveLength(2); expect(writes[0]?.[1]?.body).toBe(writes[1]?.[1]?.body);
  expect(new Headers(writes[0]?.[1]?.headers).get('X-Request-Id')).toBe(original);
  expect(saved.setItem).not.toHaveBeenCalled();
  expect(transport.mock.calls.some(([path]) => /\/api\/(admin|search|chat|conversations)/.test(String(path)))).toBe(false);
});
it('提交超时后保留原意图，忽略迟到成功并允许同请求重试', async () => {
  const pending = deferred<Response>(); let attempts = 0, requestId = '';
  const { transport } = await connect((path, options) => {
    if (path === '/api/knowledge-subscription-preferences') return response(preference);
    if (path.includes('/target-state')) return response(target);
    if (path === '/api/knowledge-subscriptions' && options.method === 'POST') {
      requestId = new Headers(options.headers).get('X-Request-Id')!;
      return ++attempts === 1 ? pending.promise : response(receipt(requestId));
    }
    return response(page());
  });
  mountRouter(() => h(SubscriptionWorkspace, { documentId: target.targetId }));
  const button = await screen.findByRole('button', { name: '确认订阅当前文档' });
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  try {
    await fireEvent.click(button);
    await vi.advanceTimersByTimeAsync(15000); await nextTick();
    expect(screen.getByText(/原请求结果尚未确认/)).toBeTruthy();
    const first = transport.mock.calls.find(([, options]) => options?.method === 'POST' && new Headers(options.headers).has('X-Request-Id'))!;
    expect(first[1]?.signal?.aborted).toBe(true);
    pending.resolve(response(receipt(requestId)));
    await vi.advanceTimersByTimeAsync(0); await nextTick();
    expect(screen.queryByText(/原请求已确认/)).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: '重试同一请求' }));
    await vi.advanceTimersByTimeAsync(0); await nextTick();
    expect(screen.getByText(/原请求已确认/)).toBeTruthy();
    const writes = transport.mock.calls.filter(([, options]) => new Headers(options?.headers).has('X-Request-Id'));
    expect(writes).toHaveLength(2);
    expect(writes[1]?.[1]?.body).toBe(first[1]?.body);
    expect(new Headers(writes[1]?.[1]?.headers).get('X-Request-Id')).toBe(requestId);
  } finally { vi.useRealTimers(); }
});
it('更新V2/V3分别显示，翻页清除旧页，读取失败不伪装缺说明', async () => {
  const { transport } = await connect(path => response(path.includes('cursor=abc') ? page([{ ...detail.update, updateId: 'bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee', versionNo: 2 }]) : page([detail.update], 'abc')));
  const view = mountRouter(() => h(UpdatesWorkspace)); await screen.findByRole('link', { name: '发布资料 · V3' });
  await fireEvent.click(screen.getByRole('button', { name: '下一页更新' })); await screen.findByRole('link', { name: '发布资料 · V2' });
  expect(screen.queryByRole('link', { name: '发布资料 · V3' })).toBeNull(); expect(transport.mock.calls.some(([path]) => String(path).includes('cursor=abc'))).toBe(true); view.unmount();
  await connect(() => new Response(null, { status: 503 })); mountRouter(() => h(UpdateWorkspace, { id }));
  await screen.findByText('服务暂不可用，请稍后重试。'); expect(screen.queryByText('暂无变更说明')).toBeNull();
});
it('人工说明按固定目标显示补写与更正，V3可对比原V1基准', async () => {
  const { transport } = await connect(() => response(detail)); mountRouter(() => h(UpdateWorkspace, { id }));
  await screen.findByText('人工变更记录'); expect(screen.getByText(/发布后补写/)).toBeTruthy(); expect(screen.getByText(/说明已更正/)).toBeTruthy();
  expect(screen.getByRole('link', { name: '与固定基准 V1 对比' }).getAttribute('href')).toBe('/documents/9007199254740993/compare/9007199254740994/9007199254740995');
  expect(transport.mock.calls).toHaveLength(1);
});
it('隐藏卸载私有页面，晚到的详情不能重新展示', async () => {
  const pending = deferred<Response>(); await connect(() => pending.promise);
  mountRouter(() => h(KnowledgePage), { path: `/updates/${id}` });
  await waitFor(() => expect(screen.getByText('正在读取固定发布及人工说明…')).toBeTruthy());
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange'));
  pending.resolve(response(detail)); await screen.findByText('请先完成登录与身份确认'); expect(screen.queryByText('人工变更记录')).toBeNull();
  Reflect.deleteProperty(document, 'visibilityState');
});

it('目标不可用仍可本人取消，提交只绑定原关系和代次', async () => {
  const relation = { subscriptionId: id, generationId: generation, kind: 'TAG', state: 'ACTIVE', version: '7', available: false, targetId: null,
    label: null, enabled: null, createdAt: at, updatedAt: at };
  const { transport } = await connect((path, options) => {
    if (path === '/api/knowledge-subscription-preferences') return response(preference);
    if (options.method === 'PUT') return response({ ...receipt(new Headers(options.headers).get('X-Request-Id')!), operation: 'CANCEL', state: 'CANCELLED', version: '8' });
    return response(page([relation]));
  });
  mountRouter(() => h(SubscriptionWorkspace)); await screen.findByText(/目标当前不可用/);
  await fireEvent.click(screen.getByRole('button', { name: '确认取消订阅' })); await screen.findByText(/原请求已确认/);
  const request = transport.mock.calls.find(([, options]) => options?.method === 'PUT')!;
  expect(request[0]).toBe(`/api/knowledge-subscriptions/${id}/cancel`);
  expect(JSON.parse(String(request[1]?.body))).toEqual({ generationId: generation, expectedVersion: '7' });
});
it('取消结果未知时必须显式放弃并重读后才能按新版本重新订阅', async () => {
  let cancelled = false, reads = 0;
  const reread = deferred<Response>();
  const { transport } = await connect((path, options) => {
    if (path === '/api/knowledge-subscription-preferences') return response(preference);
    if (path.includes('/target-state')) {
      reads++;
      return cancelled ? reread.promise : response({ ...target, state: 'ACTIVE', version: '1', subscriptionId: id, generationId: generation });
    }
    if (options.method === 'PUT') { cancelled = true; throw new Error('lost cancellation response'); }
    if (options.method === 'POST') return response({ ...receipt(new Headers(options.headers).get('X-Request-Id')!), version: '3', generationId: 'cccccccc-dddd-eeee-ffff-aaaaaaaaaaaa' });
    return response(page());
  });
  mountRouter(() => h(SubscriptionWorkspace, { documentId: target.targetId }));
  await fireEvent.click(await screen.findByRole('button', { name: '取消当前文档订阅' }));
  await screen.findByText(/原请求结果尚未确认/);
  expect((screen.getByRole('button', { name: '取消当前文档订阅' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '放弃核对，重新确认' }));
  await waitFor(() => expect(reads).toBe(2));
  expect(screen.queryByRole('button', { name: '确认订阅当前文档' })).toBeNull();
  reread.resolve(response({ ...target, state: 'CANCELLED', version: '2', subscriptionId: id, generationId: generation }));
  await fireEvent.click(await screen.findByRole('button', { name: '确认订阅当前文档' }));
  await screen.findByText(/原请求已确认/);
  const writes = transport.mock.calls.filter(([, options]) => new Headers(options?.headers).has('X-Request-Id'));
  expect(writes).toHaveLength(2);
  expect(JSON.parse(String(writes[0]?.[1]?.body))).toEqual({ generationId: generation, expectedVersion: '1' });
  expect(JSON.parse(String(writes[1]?.[1]?.body))).toEqual({ kind: 'DOCUMENT', targetId: target.targetId, expectedVersion: '2' });
  expect(new Headers(writes[0]?.[1]?.headers).get('X-Request-Id')).not.toBe(new Headers(writes[1]?.[1]?.headers).get('X-Request-Id'));
});
it('换号会清除原命令和私有目标，旧身份的迟到回执不能进入新页面', async () => {
  const pending = deferred<Response>(); let requestId = '';
  const first = await connect((path, options) => {
    if (path === '/api/knowledge-subscription-preferences') return response(preference);
    if (path.includes('/target-state')) return response(target);
    if (options.method === 'POST') { requestId = new Headers(options.headers).get('X-Request-Id')!; return pending.promise; }
    return response(page());
  });
  mountRouter(() => h(KnowledgePage), { path: `/subscriptions/documents/${target.targetId}` });
  await fireEvent.click(await screen.findByRole('button', { name: '确认订阅当前文档' }));
  await screen.findByText('正在提交或核对原请求…');
  const second = await connect(path => path === '/api/knowledge-subscription-preferences' ? response(preference)
    : path.includes('/target-state') ? new Response(null, { status: 404 }) : response(page()), { ...me, user: { ...me.user, userId: '2' } });
  await screen.findByText('目标不存在或当前不可访问。');
  pending.resolve(response(receipt(requestId))); await nextTick();
  await waitFor(() => expect(first.transport.mock.calls.find(([, options]) => new Headers(options?.headers).has('X-Request-Id'))?.[1]?.signal?.aborted).toBe(true));
  expect(screen.queryByText(target.label)).toBeNull();
  expect(screen.queryByText(/原请求已确认/)).toBeNull();
  expect(screen.queryByRole('region', { name: '订阅请求结果' })).toBeNull();
  expect(second.transport.mock.calls.some(([, options]) => new Headers(options?.headers).has('X-Request-Id'))).toBe(false);
});

it('偏好和候选失败不显示未确认默认值或合法空候选', async () => {
  await connect(path => path === '/api/knowledge-subscription-preferences' || path.endsWith('/candidates') ? new Response(null, { status: 503 }) : response(page()));
  mountRouter(() => h(SubscriptionWorkspace)); await screen.findByText('服务暂不可用，请稍后重试。');
  expect(screen.queryByText(/当前设置：/)).toBeNull(); expect(screen.queryByRole('button', { name: '确认修改频率' })).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '查找可订阅目标' }));
  await waitFor(() => expect(screen.getAllByText('服务暂不可用，请稍后重试。')).toHaveLength(2));
  expect(screen.queryByText('当前没有匹配的可订阅目标。')).toBeNull();
});

it('周报按文档组展开V2/V3，撤权重读清空内容，不自动标记已读', async () => {
  let denied = false;
  const { transport } = await connect(path => {
    if (denied) return new Response(null, { status: 404 });
    if (path.includes('/documents/')) return response(page([detail.update, { ...detail.update, updateId: generation, versionNo: 2 }]));
    if (path.includes(`/knowledge-digests/${id}?`)) return response({ digest: { digestId: id, weekStart: '2026-11-23', weekEnd: '2026-11-30', generatedAt: at },
      groups: page([{ documentId: detail.update.documentId, title: '发布资料', publicationCount: '2', latestPublishedAt: at }]) });
    return response(page());
  });
  mountRouter(() => h(DigestsWorkspace, { id })); await screen.findByText(/当前可读 2 次发布/);
  await fireEvent.click(screen.getByRole('button', { name: '展开各次发布' }));
  await screen.findByRole('link', { name: '发布资料 · V2' }); expect(screen.getByRole('link', { name: '发布资料 · V3' })).toBeTruthy();
  denied = true; await fireEvent.click(screen.getByRole('button', { name: '重新读取周报' }));
  await screen.findByText('目标不存在或当前不可访问。'); expect(screen.queryByText(/当前可读 2 次发布/)).toBeNull();
  expect(transport.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
});

it.each(['DOCUMENT', 'SPACE', 'TAG'] as Kind[])('三类候选严格按当前选择查询，重复目标禁止再订阅：%s', async kind => {
  const { transport } = await connect(path => {
    if (path === '/api/knowledge-subscription-preferences') return response(preference);
    if (path.endsWith('/candidates')) return response(page([{ ...target, kind, state: 'ACTIVE', version: '1', subscriptionId: id, generationId: generation }]));
    return response(page());
  });
  mountRouter(() => h(SubscriptionWorkspace));
  await chooseOption('目标类型', kinds[kind]);
  await fireEvent.click(screen.getByRole('button', { name: '查找可订阅目标' }));
  expect((await screen.findByRole('button', { name: '已订阅' }) as HTMLButtonElement).disabled).toBe(true);
  const call = transport.mock.calls.find(([path]) => String(path).endsWith('/candidates'))!;
  expect(JSON.parse(String(call[1]?.body)).kind).toBe(kind);
  expect(transport.mock.calls.some(([path]) => String(path) === '/api/knowledge-subscriptions')).toBe(false);
});

it('频率冲突保留原意图结果，不自动按新版本重新提交', async () => {
  const { transport } = await connect((path, options) => path === '/api/knowledge-subscription-preferences'
    ? options.method === 'PUT' ? new Response(null, { status: 409 }) : response(preference) : response(page()));
  mountRouter(() => h(SubscriptionWorkspace)); await screen.findByText(/当前设置：每周汇总/);
  await chooseOption('新提醒频率', '逐次提醒'); await fireEvent.click(screen.getByRole('button', { name: '确认修改频率' }));
  await screen.findByText('状态已变化，请刷新后确认。');
  const requests = transport.mock.calls.filter(([, options]) => options?.method === 'PUT');
  expect(requests).toHaveLength(1); expect(JSON.parse(String(requests[0]?.[1]?.body))).toEqual({ mode: 'PER_PUBLICATION', expectedVersion: '0' });
  expect(screen.queryByRole('button', { name: '重试同一请求' })).toBeNull();
});

it.each(['anonymous', 'password'] as const)('身份门禁不读取订阅私有内容：%s', phase => {
  const request = vi.fn(); context.session = { getSnapshot: () => ({ phase, epoch: 10, me: null }), refresh: async () => {}, send: request } as unknown as Session;
  context.state = { phase, epoch: 10, me: null };
  mountRouter(() => h(KnowledgePage), { path: '/subscriptions' });
  expect(screen.getByText('请先完成登录与身份确认')).toBeTruthy(); expect(request).not.toHaveBeenCalled();
});
