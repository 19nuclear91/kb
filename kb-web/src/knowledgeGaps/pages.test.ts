import { defineComponent, h, ref, shallowReactive } from 'vue';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Session, SessionState } from '../session';
import { mountRouter } from '../test/app';
import { deferred } from '../test/fixtures';
import { capabilities, detail, entry, eventId, history, item, page, participant, ready, receipt, response, solutionId } from './testSupport';

const context = shallowReactive({ session: {} as Session, state: { phase: 'anonymous', me: null, epoch: 0 } as SessionState });
const codes = ref<string[]>([]), previewPaths = vi.fn();
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../admin/access', async importOriginal => ({ ...await importOriginal<typeof import('../admin/access')>(),
  useAdminAccess: () => ({ has: (code: string) => codes.value.includes(code), get permissionFactsKey() { return codes.value.join('|'); } }) }));
vi.mock('../office/VersionPreview.vue', () => ({ default: defineComponent({ props: ['path'], setup(props) {
  previewPaths(props.path); return () => h('output', props.path);
} }) }));
import GapListWorkspace from './GapListWorkspace.vue';
import GapDetailWorkspace from './GapDetailWorkspace.vue';

const target = '9007199254740993';
const inProgress = { state: 'IN_PROGRESS', version: 1n, reporter: participant(2n), assignee: participant(1n),
  capabilities: { ...capabilities, withdraw: false, returnToReception: true, submitSolution: true } };
beforeEach(() => { codes.value = []; previewPaths.mockClear(); });
afterEach(() => { Reflect.deleteProperty(document, 'visibilityState'); });
async function connect(next: (path: string, options: RequestInit) => Response | Promise<Response>) {
  const value = await ready(next); context.session = value.session; context.state = value.session.getSnapshot();
  value.session.subscribe(() => { context.state = value.session.getSnapshot(); }); return value;
}
function showDetail() { return mountRouter(() => h(GapDetailWorkspace, { gapId: target }), { path: `/knowledge-gaps/${target}` }); }
function noDirectories(transport: ReturnType<typeof vi.fn>) {
  expect(transport.mock.calls.some(([path]) => /\/api\/(admin\/identity|admin\/spaces|search|chat|conversations)/.test(path))).toBe(false);
}
async function approveSharing() { await fireEvent.click(screen.getByRole('checkbox', { name: '我明确确认此说明按问题范围分享' })); }

it('本人列表区分加载/空/失败/末页并消费服务端游标，不显示隐藏总数', async () => {
  const pending = deferred<Response>();
  const { transport } = await connect(() => pending.promise);
  const view = mountRouter(() => h(GapListWorkspace), { path: '/knowledge-gaps' });
  expect(screen.getByText('正在读取问题…')).toBeTruthy(); pending.resolve(response(page()));
  await screen.findByText('当前视图没有可见问题。'); expect(screen.getByText('已到当前可见范围末页。')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '读取下一页' })).toBeNull(); noDirectories(transport); view.unmount();
  await connect(() => new Response(null, { status: 503 }));
  const failed = mountRouter(() => h(GapListWorkspace), { path: '/knowledge-gaps' });
  await screen.findByText('服务暂不可用，请稍后重试。'); expect(screen.queryByText('当前视图没有可见问题。')).toBeNull(); failed.unmount();
  const paged = await connect(path => response(path.includes('cursor=abc') ? page([item({ gapId: 7n })]) : page([item()], { hasMore: true, nextCursor: 'abc' })));
  mountRouter(() => h(GapListWorkspace), { path: '/knowledge-gaps' }); await screen.findByRole('link', { name: `问题 ${target}` });
  await fireEvent.click(screen.getByRole('button', { name: '读取下一页' })); await screen.findByRole('link', { name: '问题 7' });
  expect(screen.getAllByRole('link', { name: /^问题 / }).map(link => link.textContent)).toEqual([`问题 ${target}`, '问题 7']);
  expect(paged.transport.mock.calls[1][0]).toBe('/api/knowledge-gaps?view=mine&limit=20&cursor=abc');
});

it('assigned 仅当前 handle，只有受理/身份资格不替代，撤权清空原列表', async () => {
  const { transport } = await connect(path => response(page([item({ gapId: path.includes('assigned') ? 8n : 9n,
    state: 'IN_PROGRESS', version: 1n, assignee: participant(1n) })])));
  mountRouter(() => h(GapListWorkspace), { path: '/knowledge-gaps' }); await screen.findByRole('link', { name: '问题 9' });
  codes.value = ['knowledge:gap:read', 'identity:user:read']; await flushPromises();
  expect(screen.queryByRole('button', { name: '分派给我' })).toBeNull();
  codes.value = ['knowledge:gap:handle']; await fireEvent.click(await screen.findByRole('button', { name: '分派给我' }));
  await screen.findByRole('link', { name: '问题 8' });
  codes.value = []; await flushPromises();
  expect(screen.queryByRole('link', { name: '问题 8' })).toBeNull();
  expect(screen.getByText('当前处理资格未确认或已失效，已清理原分派列表。')).toBeTruthy();
  expect(transport.mock.calls.filter(([path]) => path.includes('view=assigned'))).toHaveLength(1); noDirectories(transport);
});

it('详情与独立历史有界正序分页，历史失败不伪装为空记录', async () => {
  const note = history({ eventId: solutionId, kind: 'NOTE_ADDED', resultVersion: 1n,
    entry: entry({ kind: 'NOTE', position: 1n, author: participant(), text: '历史共享说明' }) });
  const { transport } = await connect(path => path.includes('/history?')
    ? response(path.includes('cursor=abc') ? page([note]) : page([history()], { hasMore: true, nextCursor: 'abc' })) : response(detail()));
  const view = showDetail(); await screen.findByText('明确分享的描述'); await screen.findByRole('button', { name: '读取更多历史' });
  await fireEvent.click(screen.getByRole('button', { name: '读取更多历史' })); await screen.findByText('历史共享说明');
  expect(screen.getByText('已到处理历史末页。')).toBeTruthy();
  expect(transport.mock.calls.some(([path]) => path === `/api/knowledge-gaps/${target}/history?limit=20&cursor=abc`)).toBe(true); noDirectories(transport);
  view.unmount();
  await connect(path => path.includes('/history?') ? new Response(null, { status: 503 }) : response(detail()));
  showDetail(); await screen.findByText('明确分享的描述');
  const region = screen.getByRole('region', { name: '问题处理历史' });
  expect(await within(region).findByText('服务暂不可用，请稍后重试。')).toBeTruthy();
  expect(within(region).queryByText('当前页没有处理记录。')).toBeNull();
});

it('当前能力决定说明/方案入口；只读详情不能借角色名称处理', async () => {
  codes.value = ['ROLE_ADMIN', 'knowledge:gap:read'];
  const { transport } = await connect(path => path.includes('/history?') ? response(page([history()]))
    : response(detail({ capabilities: { assign: false, note: false, returnToReception: false, submitSolution: false,
      confirm: false, rejectSolution: false, withdraw: false, close: false } })));
  showDetail(); await screen.findByText('明确分享的描述');
  expect(screen.queryByRole('textbox', { name: '共享说明' })).toBeNull();
  expect(screen.queryByRole('button', { name: '准备解决方案' })).toBeNull(); noDirectories(transport);
});

it('共享说明明确确认后提交精确版本，成功重读而不依赖通知、AI 或身份目录', async () => {
  let version = 1n, saved = false;
  const { transport } = await connect(path => {
    if (path.endsWith('/notes')) { version = 2n; saved = true; return response(receipt({ resultVersion: version, state: 'IN_PROGRESS' })); }
    if (path.includes('/history?')) return response(page(saved ? [history(), history({ eventId: solutionId, kind: 'NOTE_ADDED', resultVersion: 2n,
      entry: entry({ kind: 'NOTE', position: 2n, author: participant(), text: '明确分享的新说明' }) })] : [history()]));
    return response(detail({ ...inProgress, version }));
  });
  showDetail(); await fireEvent.update(await screen.findByRole('textbox', { name: '共享说明' }), '明确分享的新说明');
  expect((screen.getByRole('button', { name: '提交共享说明' }) as HTMLButtonElement).disabled).toBe(true);
  await approveSharing(); await fireEvent.click(screen.getByRole('button', { name: '提交共享说明' }));
  await screen.findByText('明确分享的新说明');
  const write = transport.mock.calls.find(([path]) => path.endsWith('/notes'))!;
  expect(write[1].body).toBe('{"expectedVersion":1,"text":"明确分享的新说明"}');
  expect(new Headers(write[1].headers).get('X-Request-Id')).toMatch(/^[0-9a-f-]{36}$/);
  expect((screen.getByRole('textbox', { name: '共享说明' }) as HTMLTextAreaElement).value).toBe(''); noDirectories(transport);
});

it('说明型方案无需上传/模型，提交后只显示待确认，不能自动解决', async () => {
  let awaiting = false;
  const { transport } = await connect(path => {
    if (path.endsWith('/solutions')) { awaiting = true; return response(receipt({ resultVersion: 2n, state: 'AWAITING_CONFIRMATION', solutionId })); }
    if (path.includes('/history?')) return response(page([history()]));
    return response(detail(awaiting ? { ...inProgress, state: 'AWAITING_CONFIRMATION', version: 2n,
      capabilities: { ...inProgress.capabilities, submitSolution: false }, currentSolution: entry({ author: participant(1n), text: '纯说明方案' }) } : inProgress));
  });
  showDetail(); await fireEvent.click(await screen.findByRole('button', { name: '准备解决方案' }));
  await fireEvent.update(screen.getByRole('textbox', { name: '处理方案说明' }), '纯说明方案'); await approveSharing();
  await fireEvent.click(screen.getByRole('button', { name: '提交待确认方案' })); await screen.findByText('纯说明方案');
  expect(screen.getByText(/问题 9007199254740993 · 待确认/)).toBeTruthy();
  expect(transport.mock.calls.find(([path]) => path.endsWith('/solutions'))![1].body).toBe('{"expectedVersion":1,"text":"纯说明方案"}');
  expect(transport.mock.calls.some(([path]) => /\/confirm$|\/upload|\/solution-documents/.test(path))).toBe(false); noDirectories(transport);
});

it('资料只能从当前受控 POST 候选选择，提交并展示原固定版本，不自动预览', async () => {
  let awaiting = false;
  const reference = { documentId: 9007199254740995n, versionId: 9223372036854775807n, title: '当前可读发布资料' };
  const { transport } = await connect(path => {
    if (path.endsWith('/solution-documents')) return response(page([reference]));
    if (path.endsWith('/solutions')) { awaiting = true; return response(receipt({ resultVersion: 2n, state: 'AWAITING_CONFIRMATION', solutionId })); }
    if (path.includes('/history?')) return response(page([history()]));
    return response(detail(awaiting ? { ...inProgress, state: 'AWAITING_CONFIRMATION', version: 2n,
      capabilities: { ...inProgress.capabilities, submitSolution: false },
      currentSolution: entry({ author: participant(1n), referenceState: 'AVAILABLE', reference }) } : inProgress));
  });
  showDetail(); await fireEvent.click(await screen.findByRole('button', { name: '准备解决方案' }));
  await fireEvent.update(screen.getByRole('textbox', { name: '处理方案说明' }), '人工资料方案');
  await fireEvent.click(screen.getByRole('checkbox', { name: '关联已发布资料' }));
  expect(transport.mock.calls.some(([path]) => path.endsWith('/solution-documents'))).toBe(false);
  await fireEvent.update(screen.getByRole('textbox', { name: '资料名称' }), '  发布资料名称  ');
  await fireEvent.click(screen.getByRole('button', { name: '查询方案资料' }));
  await fireEvent.click(await screen.findByRole('button', { name: '选择此固定版本' }));
  await approveSharing(); await fireEvent.click(screen.getByRole('button', { name: '提交待确认方案' }));
  await screen.findByRole('button', { name: '查看固定资料版本' }); expect(previewPaths).not.toHaveBeenCalled();
  const candidate = transport.mock.calls.find(([path]) => path.endsWith('/solution-documents'))!;
  expect(candidate[0]).toBe(`/api/knowledge-gaps/${target}/solution-documents`);
  expect(candidate[1].body).toBe('{"nameQuery":"发布资料名称","limit":20,"cursor":null}');
  expect(transport.mock.calls.find(([path]) => path.endsWith('/solutions'))![1].body)
    .toBe('{"expectedVersion":1,"text":"人工资料方案","documentId":9007199254740995,"versionId":9223372036854775807}');
  await fireEvent.click(screen.getByRole('button', { name: '查看固定资料版本' }));
  await screen.findByText('/api/documents/9007199254740995/versions/9223372036854775807/preview'); noDirectories(transport);
});

it('候选条件改变或读取失败清除旧选择，不回退目录或以旧资料继续提交', async () => {
  let failing = false;
  const { transport } = await connect(path => path.endsWith('/solution-documents') ? failing ? new Response(null, { status: 503 })
    : response(page([{ documentId: 7n, versionId: 8n, title: '旧候选' }])) : path.includes('/history?') ? response(page([history()])) : response(detail(inProgress)));
  showDetail(); await fireEvent.click(await screen.findByRole('button', { name: '准备解决方案' }));
  await fireEvent.update(screen.getByRole('textbox', { name: '处理方案说明' }), '资料说明');
  await fireEvent.click(screen.getByRole('checkbox', { name: '关联已发布资料' }));
  await fireEvent.click(screen.getByRole('button', { name: '查询方案资料' }));
  await fireEvent.click(await screen.findByRole('button', { name: '选择此固定版本' }));
  expect(screen.getByText('已选择：旧候选 · 固定版本 8')).toBeTruthy();
  await fireEvent.update(screen.getByRole('textbox', { name: '资料名称' }), '新查询');
  expect(screen.queryByText('已选择：旧候选 · 固定版本 8')).toBeNull();
  failing = true; await fireEvent.click(screen.getByRole('button', { name: '查询方案资料' }));
  await screen.findByText('服务暂不可用，请稍后重试。');
  expect(screen.queryByText('旧候选 · 固定版本 8')).toBeNull();
  expect(screen.queryByText('当前条件没有可用资料候选。')).toBeNull();
  expect((screen.getByRole('button', { name: '提交待确认方案' }) as HTMLButtonElement).disabled).toBe(true); noDirectories(transport);
});

it('资料后续不可用清空旧标题/身份/入口，保留本人确认事实及共享人工文本', async () => {
  let unavailable = false;
  await connect(path => path.includes('/history?') ? response(page([history()])) : response(detail({
    state: 'AWAITING_CONFIRMATION', version: 2n, assignee: participant(2n), capabilities: { ...capabilities, confirm: true, rejectSolution: true },
    currentSolution: entry({ referenceState: unavailable ? 'UNAVAILABLE' : 'AVAILABLE', reference: unavailable ? null : { documentId: 7n, versionId: 8n, title: '旧授权标题' } }) })));
  showDetail(); await screen.findByText('旧授权标题 · 文档 7 · 固定版本 8'); unavailable = true;
  await fireEvent.click(screen.getByRole('button', { name: '重新读取问题' }));
  await screen.findByText('关联资料当前不可用，不提供旧身份或标题。');
  expect(screen.queryByText('旧授权标题 · 文档 7 · 固定版本 8')).toBeNull();
  expect(screen.queryByRole('button', { name: '查看固定资料版本' })).toBeNull(); expect(screen.getByText('人工说明')).toBeTruthy();
});

it('历史 404 清理整个问题而非保留旧正文，也不会循环重读或扩权查询', async () => {
  const pending = deferred<Response>();
  const { transport } = await connect(path => path.includes('/history?') ? pending.promise : response(detail()));
  showDetail(); await screen.findByText('明确分享的描述'); pending.resolve(new Response(null, { status: 404 }));
  await screen.findByText('当前问题不可访问，已清理旧内容，请由当前合格人员核对。');
  expect(screen.queryByText('明确分享的描述')).toBeNull(); expect(transport).toHaveBeenCalledTimes(2); noDirectories(transport);
});
