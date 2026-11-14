import { h, shallowReactive } from 'vue';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { encodeJson } from '../api';
import type { Session, SessionState } from '../session';
import { mountRouter } from '../test/app';
import { deferred, turn } from '../test/fixtures';
import { savedFeedbackQuestion } from './feedback';
import { ready, receipt, response } from './testSupport';

const context = shallowReactive({ session: {} as Session, state: { phase: 'anonymous', me: null, epoch: 0 } as SessionState });
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false }) }));
vi.mock('../favorites/FavoriteButton.vue', () => ({ default: { render: () => null } }));
vi.mock('../CitationDrawer.vue', () => ({ default: { render: () => null } }));
vi.mock('../stream', () => ({ openTurn: vi.fn() }));
import SearchWorkspace from '../search/SearchWorkspace.vue';
import ConversationContent from '../assistant/ConversationContent.vue';
import { openTurn } from '../stream';

beforeEach(() => { vi.mocked(openTurn).mockReset(); });
afterEach(() => { Reflect.deleteProperty(document, 'visibilityState'); });
const raw = (value: unknown) => new Response(encodeJson(value), { headers: { 'Content-Type': 'application/json' } });
const meta = { id: '1', title: '本人私有会话', createdAt: '2026-11-14T00:00:00Z', lastTurnSeq: 1n, activeTurnId: null };
async function connect(next: (path: string, options: RequestInit) => Response | Promise<Response>) {
  const value = await ready(next); context.session = value.session; context.state = value.session.getSnapshot();
  value.session.subscribe(() => { context.state = value.session.getSnapshot(); });
  return value;
}
async function search(query: string) {
  await fireEvent.update(screen.getByLabelText('搜索词'), query);
  await fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!);
}
async function openSearchFeedback() {
  await fireEvent.click(await screen.findByRole('button', { name: '反馈知识需求' }));
  return screen.findByRole('dialog', { name: '知识需求反馈' });
}
async function share(dialog: HTMLElement) {
  await fireEvent.click(within(dialog).getByRole('checkbox', { name: '我已预览并明确确认上述分享与保留范围' }));
  await fireEvent.click(within(dialog).getByRole('button', { name: '提交问题' }));
}
const writes = (transport: ReturnType<typeof vi.fn>) => transport.mock.calls.filter(([path]) => path === '/api/knowledge-gaps');

it('搜索仅预填实际已提交的本人词，不取未提交编辑或片段；取消零创建、重开不恢复草稿', async () => {
  const { transport, saved } = await connect(path => response(path === '/api/search' ? [{ chunkId: '7', versionId: null, content: '检索片段不可自动分享' }] : []));
  const view = mountRouter(() => h(SearchWorkspace, { maximum: 8000 }), { path: '/search' });
  await search('本人已提交搜索词'); await screen.findByText('检索片段不可自动分享');
  await fireEvent.update(screen.getByLabelText('搜索词'), '未提交的新词');
  let dialog = await openSearchFeedback();
  const field = within(dialog).getByRole('textbox', { name: '明确分享的描述' }) as HTMLTextAreaElement;
  expect(field.value).toBe('本人已提交搜索词'); expect(field.value).not.toContain('片段');
  expect(within(dialog).getByText(/原提问者、当前合格处理人和授权受理人员可读/)).toBeTruthy();
  expect(within(dialog).getByText(/撤回停止处理，不等于删除/)).toBeTruthy();
  expect((within(dialog).getByRole('button', { name: '提交问题' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.update(field, '取消的编辑草稿'); await fireEvent.click(within(dialog).getByRole('button', { name: '取消' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(writes(transport)).toHaveLength(0); expect(saved.setItem).not.toHaveBeenCalled();
  expect(screen.getByText('检索片段不可自动分享')).toBeTruthy(); expect(view.router.currentRoute.value.fullPath).toBe('/search');
  dialog = await openSearchFeedback();
  expect((within(dialog).getByRole('textbox', { name: '明确分享的描述' }) as HTMLTextAreaElement).value).toBe('本人已提交搜索词');
});

it('检索故障仍保持真实错误，用户明确分享后才创建；不把故障断言为知识缺失', async () => {
  const { transport } = await connect(path => path === '/api/search' ? new Response(null, { status: 503 })
    : path === '/api/knowledge-gaps' ? response(receipt(), 201) : response([]));
  mountRouter(() => h(SearchWorkspace, { maximum: 8000 }), { path: '/search' });
  await search('故障时的本人输入'); await screen.findByText('检索暂不可用，请稍后重试。');
  expect(writes(transport)).toHaveLength(0);
  const dialog = await openSearchFeedback();
  expect(within(dialog).getByText(/来源仅表示提交入口，不证明检索故障/)).toBeTruthy();
  await share(dialog); await screen.findByText(/已提交问题 9007199254740993，等待人工受理/);
  expect(writes(transport)).toHaveLength(1);
  expect(writes(transport)[0][1].body).toBe('{"category":"OTHER","description":"故障时的本人输入","source":"SEARCH","shareAcknowledged":true}');
  expect(screen.getByText('检索暂不可用，请稍后重试。')).toBeTruthy();
});

it('超限原提问完整预填不静默裁剪；人工缩短后须重新确认分享', async () => {
  const original = '😀'.repeat(8000);
  const { transport } = await connect(path => path === '/api/knowledge-gaps' ? response(receipt(), 201) : response([]));
  mountRouter(() => h(SearchWorkspace, { maximum: 8000 }), { path: '/search' });
  await search(original); const dialog = await openSearchFeedback();
  const field = within(dialog).getByRole('textbox', { name: '明确分享的描述' }) as HTMLTextAreaElement;
  expect(field.value).toBe(original); expect(field.hasAttribute('maxlength')).toBe(false);
  await fireEvent.click(within(dialog).getByRole('checkbox'));
  expect((within(dialog).getByRole('button', { name: '提交问题' }) as HTMLButtonElement).disabled).toBe(true);
  expect(within(dialog).getByText(/8000 \/ 4000 个字符/)).toBeTruthy(); expect(writes(transport)).toHaveLength(0);
  await fireEvent.update(field, '人工缩短后的明确描述');
  expect((within(dialog).getByRole('checkbox') as HTMLInputElement).checked).toBe(false);
  await share(dialog); await screen.findByText(/^已提交问题 /);
  expect(writes(transport)[0][1].body).toContain('"description":"人工缩短后的明确描述"');
});

it('已保存轮次只分享本人原问题，回答/引用/完整会话/轮次身份不进入载荷', async () => {
  const saved = { ...turn, status: 'COMPLETED', question: '已保存的本人提问', answer: '不得自动分享的模型回答',
    citations: [{ chunkId: '99', versionId: '88', documentId: '77', title: '引用标题不能附带' }] };
  const { transport } = await connect(path => path === '/api/knowledge-gaps' ? response(receipt(), 201)
    : path.includes('/turns?') ? raw({ items: [saved], nextCursor: null }) : raw(meta));
  const view = mountRouter(() => h(ConversationContent, { conversationId: '1' }), { path: '/assistant/1' });
  await screen.findByText('不得自动分享的模型回答');
  await fireEvent.click(screen.getByRole('button', { name: '反馈此提问' }));
  const dialog = await screen.findByRole('dialog', { name: '知识需求反馈' });
  const field = within(dialog).getByRole('textbox', { name: '明确分享的描述' }) as HTMLTextAreaElement;
  expect(field.value).toBe('已保存的本人提问'); await share(dialog); await screen.findByText(/^已提交问题 /);
  const body = String(writes(transport)[0][1].body);
  expect(body).toBe('{"category":"OTHER","description":"已保存的本人提问","source":"CONVERSATION","shareAcknowledged":true}');
  for (const excluded of ['模型回答', '引用标题', 'answer', 'citations', 'turnId', turn.id, 'conversationId', 'searchId']) expect(body).not.toContain(excluded);
  expect(transport.mock.calls.filter(([path]) => path.includes('/turns?'))).toHaveLength(1);
  expect(view.router.currentRoute.value.fullPath).toBe('/assistant/1');
});

it('来源被屏蔽也不搬运回答；预填函数根本不读取回答或引用字段', () => {
  const saved = { question: '自己的提问', status: 'COMPLETED', get answer(): never { throw new Error('禁止读取答案'); },
    get citations(): never { throw new Error('禁止读取引用'); } };
  expect(savedFeedbackQuestion(saved)).toBe('自己的提问');
  const blocked = { ...turn, visibility: 'BLOCKED' };
  expect(savedFeedbackQuestion(blocked)).toBe('原问题');
  expect(savedFeedbackQuestion({ question: '临时提问', status: 'RUNNING' })).toBeNull();
  expect(savedFeedbackQuestion({ question: '未知状态', status: 'UNKNOWN' })).toBeNull();
});

it('已保存但仍 RUNNING 以及未核对保存的临时轮次均无轮次反馈入口', async () => {
  const pending = deferred<'eof'>();
  const { transport } = await connect(path => path.includes('/turns?') ? raw({ items: [], nextCursor: null }) : raw(meta));
  vi.mocked(openTurn).mockImplementation((_session, _id, _binding, _signal, started, emit) => {
    started(turn.id); emit({ type: 'delta', text: '未保存临时回答' }); return pending.promise;
  });
  const view = mountRouter(() => h(ConversationContent, { conversationId: '1' }), { path: '/assistant/1' });
  await fireEvent.update(await screen.findByLabelText('问题'), '当前正在生成的提问');
  await fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
  await screen.findByText('未保存临时回答'); expect(screen.queryByRole('button', { name: '反馈此提问' })).toBeNull();
  expect(writes(transport)).toHaveLength(0); view.unmount(); pending.resolve('eof'); await flushPromises();
  await connect(path => path.includes('/turns?') ? raw({ items: [{ ...turn, status: 'RUNNING' }], nextCursor: null }) : raw(meta));
  mountRouter(() => h(ConversationContent, { conversationId: '1' }), { path: '/assistant/1' });
  await screen.findByText('原问题'); expect(screen.queryByRole('button', { name: '反馈此提问' })).toBeNull();
});

it.each(['hidden', 'identity', 'unmount'])('%s 清理反馈草稿，取消不影响来源或创建业务', async mode => {
  const { session, transport } = await connect(() => response([]));
  const view = mountRouter(() => h(SearchWorkspace, { maximum: 8000 }), { path: '/search' });
  await search('原搜索输入'); const dialog = await openSearchFeedback();
  await fireEvent.update(within(dialog).getByRole('textbox', { name: '明确分享的描述' }), '当前身份敏感草稿');
  if (mode === 'hidden') {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange'));
  } else if (mode === 'identity') session.clear();
  else view.unmount();
  await flushPromises();
  expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByText('当前身份敏感草稿')).toBeNull();
  expect(writes(transport)).toHaveLength(0);
});
