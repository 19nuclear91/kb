import { beforeEach, expect, it, vi } from 'vitest';
import { h, nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import userEvent from '@testing-library/user-event';
import type { Session } from '../session';
import { deferred, json, me } from '../test/fixtures';
import SearchPage from '../pages/SearchPage.vue';

const context = reactive({ session: {} as Session, state: { phase: 'ready', epoch: 1, me: null as typeof me | null } });
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../CitationDrawer.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: ['citation'], setup: props => () => h('div', { 'data-testid': 'citation' }, props.citation ? `${props.citation.docId}/${props.citation.versionId}/${props.citation.chunkId}` : 'closed') }) };
});
const tags = [{ id: '9223372036854775807', name: '全局停用', enabled: false }];
const category = { id: '7', name: '制度', parentId: null, sortOrder: 0, path: [{ id: '7', name: '制度' }] };
const hit = (content: string) => ({ docId: '99', spaceId: '999', versionId: '22', chunkId: '33', docTitle: '例外文档', content });
function response(content = '可见正文', version: string | null = '1') {
  const result = json([hit(content)]);
  if (version !== null) {
    result.headers.set('X-Search-Filter-Version', version);
  }
  return result;
}
const directory = (path: string) => json({ items: path.endsWith('/tags') ? tags : [category], hasMore: false, nextCursor: null, treeVersion: 1 });
beforeEach(() => {
  context.state = { phase: 'ready', epoch: 1, me };
  context.session = { getSnapshot: () => context.state, subscribe: () => () => {},
    json: vi.fn().mockResolvedValue([{ spaceId: '1', name: '空间A', viewable: true, archived: false }, { spaceId: '2', name: '空间B', viewable: true, archived: false }]),
    send: vi.fn().mockImplementation(async (path: string) => path === '/api/search' ? response() : directory(path)),
    post: vi.fn().mockResolvedValue({ items: [{ documentId: '99', favorited: false }] }),
  } as unknown as Session;
});
const searches = () => vi.mocked(context.session.send).mock.calls.filter(([path]) => path === '/api/search');
const tagReads = () => vi.mocked(context.session.send).mock.calls.filter(([path]) => path === '/api/search/tags');
const change = (label: string, value: string) => fireEvent.update(screen.getByLabelText(label), value);
const submit = async (query = '问题') => { await change('搜索词', query); await fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!); };
const mount = () => mountRouter(() => h(SearchPage));

it('标签框回车仅查询标签，保留已选条件、搜索结果和原提交摘要', async () => {
  const user = userEvent.setup(); mount(); await user.click(await screen.findByRole('button', { name: '全局停用（停用）' }));
  await submit(); await screen.findByText('可见正文'); const summary = screen.getByLabelText('已提交条件').textContent;
  await user.type(screen.getByLabelText('查找标签'), '新的标签{Enter}'); await waitFor(() => expect(tagReads()).toHaveLength(2));
  expect(JSON.parse(tagReads()[1][1]!.body as string)).toEqual({ nameQuery: '新的标签' }); expect(searches()).toHaveLength(1);
  expect(screen.getByText('可见正文')).toBeTruthy(); expect(screen.getByLabelText('已提交条件').textContent).toBe(summary); expect(screen.getByRole('button', { name: '移除 全局停用' })).toBeTruthy();
});

it('五类草稿统一提交，摘要绑定旧快照，清除不自动搜索，保留文档例外与固定引用', async () => {
  mount(); await screen.findByRole('button', { name: '全局停用（停用）' });
  await change('知识空间', '1'); await fireEvent.click(await screen.findByRole('button', { name: '制度' }));
  await fireEvent.click(screen.getByRole('button', { name: '全局停用（停用）' })); await change('文件类型', 'PDF');
  await change('更新起点', '2026-10-01T09:00'); await change('更新终点', '2026-11-01T09:00'); expect(searches()).toHaveLength(0);
  await submit(); await screen.findByText('可见正文'); const body = searches()[0][1]!.body as string;
  expect(body).toContain('"tagIds":[9223372036854775807]'); expect(body).toContain('"categoryId":7'); expect(body).toContain('"fileType":"PDF"');
  expect(JSON.parse(body).updatedFrom).toMatch(/(Z|[+-]\d\d:\d\d)$/); const summary = screen.getByLabelText('已提交条件').textContent;
  await change('文件类型', 'WORD'); await change('搜索词', '草稿问题'); await fireEvent.click(screen.getByRole('button', { name: '清除筛选' }));
  expect(searches()).toHaveLength(1); expect(screen.getByLabelText('已提交条件').textContent).toBe(summary);
  expect(tagReads()).toHaveLength(1); expect(screen.queryByRole('option', { name: 'UNPUBLISHED' })).toBeNull();
  await submit('全部搜索'); await waitFor(() => expect(searches()).toHaveLength(2));
  expect(JSON.parse(searches()[1][1]!.body as string)).toEqual({ query: '全部搜索', candidates: 50, topK: 20 });
  await fireEvent.click(await screen.findByRole('button', { name: '例外文档' })); expect(screen.getByTestId('citation').textContent).toBe('99/22/33');
});
it('换空间清分类及未分类、保留全局标签，不发正文请求', async () => {
  mount(); await fireEvent.click(await screen.findByRole('button', { name: '全局停用（停用）' }));
  await change('知识空间', '1'); await fireEvent.click(await screen.findByRole('button', { name: '制度' }));
  await change('知识空间', '2'); expect(screen.getByText(/分类：不限分类/)).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: '仅未分类' })); await change('知识空间', '');
  expect(screen.getByText(/分类：不限分类/)).toBeTruthy(); expect(screen.getByRole('button', { name: '移除 全局停用' })).toBeTruthy();
  expect(searches()).toHaveLength(0); expect(tagReads()).toHaveLength(1);
  await submit(); await screen.findByText('可见正文'); expect(searches()[0][1]!.body).toContain('"tagIds":[9223372036854775807]');
});
it('无关键词及反向时间阻止请求，不删除或交换条件', async () => {
  mount(); await change('文件类型', 'PDF'); await submit(''); await screen.findByText('请输入搜索词'); expect(searches()).toHaveLength(0);
  await change('更新起点', '2026-11-02T00:00'); await change('更新终点', '2026-11-01T00:00'); await submit();
  await screen.findByText('搜索输入不符合要求，请修改后重试。'); expect(searches()).toHaveLength(0); expect((screen.getByLabelText('文件类型') as HTMLSelectElement).value).toBe('PDF');
});
it('新提交清理引用并隔离旧条件的迟到结果', async () => {
  const pending = deferred<Response>(); let count = 0;
  vi.mocked(context.session.send).mockImplementation(async path => path === '/api/search' ? (++count === 2 ? pending.promise : response(count === 1 ? '首次正文' : '最新正文')) : directory(path));
  mount(); await submit(); await screen.findByText('首次正文'); await fireEvent.click(screen.getByRole('button', { name: '例外文档' }));
  await change('文件类型', 'PDF'); await submit('旧在途'); await waitFor(() => expect(searches()).toHaveLength(2)); expect(screen.getByTestId('citation').textContent).toBe('closed');
  await change('文件类型', 'WORD'); await submit('新请求'); await screen.findByText('最新正文');
  pending.resolve(response('迟到正文')); await flushPromises(); expect(screen.queryByText('迟到正文')).toBeNull();
  expect(searches()[1][1]!.signal!.aborted).toBe(true); expect(within(screen.getByLabelText('已提交条件')).getByText(/WORD/)).toBeTruthy();
});
it.each(['hidden', 'unmount', 'identity', 'logout', 'password'])('%s 使搜索和候选失效，清空私有草稿，恢复仅读目录', async mode => {
  const search = deferred<Response>(), options = deferred<Response>();
  vi.mocked(context.session.send).mockImplementation(path => path === '/api/search' ? search.promise : options.promise);
  const view = mount(); await change('文件类型', 'PDF'); await submit('私有问题'); await waitFor(() => expect(searches()).toHaveLength(1));
  const signals = vi.mocked(context.session.send).mock.calls.map(([, init]) => init!.signal!);
  if (mode === 'hidden') {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange'));
  } else if (mode === 'unmount') {
    view.unmount();
  } else {
    context.state = { ...context.state, epoch: 2, phase: mode === 'identity' ? 'ready' : mode === 'logout' ? 'anonymous' : 'password' }; await nextTick();
  }
  expect(signals.every(signal => signal.aborted)).toBe(true);
  search.resolve(response('旧私有正文')); options.resolve(directory('/api/search/tags')); await flushPromises();
  expect(screen.queryByText('旧私有正文')).toBeNull(); expect(screen.queryByText('私有问题')).toBeNull(); expect(context.session.post).not.toHaveBeenCalled();
  if (mode === 'hidden') {
    vi.mocked(context.session.send).mockImplementation(async path => directory(path));
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange'));
    await screen.findByRole('button', { name: '全局停用（停用）' }); expect(searches()).toHaveLength(1);
    expect((screen.getByLabelText('搜索词') as HTMLInputElement).value).toBe(''); expect((screen.getByLabelText('文件类型') as HTMLSelectElement).value).toBe('');
  }
});
it.each([null, '2', '1, 2'])('未经版本 %s 确认的材料不进入收藏或引用，输入保留', async version => {
  vi.mocked(context.session.send).mockImplementation(async path => path === '/api/search' ? response('不可消费正文', version) : directory(path));
  mount(); await change('文件类型', 'PDF'); await submit(); await screen.findByText(/无法确认服务端筛选支持/);
  expect(context.session.post).not.toHaveBeenCalled(); expect(screen.queryByText('不可消费正文')).toBeNull(); expect(screen.getByTestId('citation').textContent).toBe('closed');
  expect(screen.queryByText('当前条件下未找到可用结果')).toBeNull(); expect(searches()).toHaveLength(1); expect((screen.getByLabelText('文件类型') as HTMLSelectElement).value).toBe('PDF');
});
it('候选 404 保留已选标签，合法空结果不重试或移除条件', async () => {
  mount(); await fireEvent.click(await screen.findByRole('button', { name: '全局停用（停用）' }));
  vi.mocked(context.session.send).mockImplementation(async path => {
    if (path !== '/api/search') {
      return new Response(null, { status: 404 });
    }
    const result = json([]); result.headers.set('X-Search-Filter-Version', '1'); return result;
  });
  await fireEvent.click(screen.getByRole('button', { name: '重新读取标签' })); await screen.findByText('搜索候选暂不可用，请主动重新读取。');
  expect(screen.getByRole('button', { name: '移除 全局停用' })).toBeTruthy(); await submit(); await screen.findByText('当前条件下未找到可用结果');
  expect(searches()).toHaveLength(1); expect(tagReads()).toHaveLength(2); expect(searches()[0][1]!.body).toContain('9223372036854775807');
});
