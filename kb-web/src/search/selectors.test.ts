import { beforeEach, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, render, screen, waitFor } from '@testing-library/vue';
import userEvent from '@testing-library/user-event';
import CategoryBrowser from '../documents/metadata/CategoryBrowser.vue';
import TagPicker from '../documents/metadata/TagPicker.vue';
import { deferred, json } from '../test/fixtures';

const f = vi.hoisted(() => ({ send: vi.fn(), epoch: 1 }));
const session = { send: f.send, getSnapshot: () => ({ epoch: f.epoch }) };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session }) }));
const node = (id: string, name: string) => ({ id, name, parentId: null, sortOrder: 0, path: [{ id, name }] });
const page = (items: unknown[], cursor: string | null = null) => json({ items, hasMore: !!cursor, nextCursor: cursor, treeVersion: 3 });
beforeEach(() => { f.send.mockReset(); f.epoch++; });

it.each([['search', '/api/search/tags'], ['filter', '/api/documents/query/tags'], ['edit', '/api/documents/8/metadata-options/tags']] as const)('%s 标签回车不提交外层表单，并保留原候选端点', async (kind, path) => {
  f.send.mockImplementation(async () => page([])); const submit = vi.fn(), user = userEvent.setup();
  render(defineComponent({ setup: () => () => h('form', { onSubmit: (event: Event) => { event.preventDefault(); submit(); } }, [
    h(TagPicker, { kind, documentId: '8', value: [], onChange: vi.fn() }), h('button', { type: 'submit' }, '提交外层'),
  ]) }));
  await screen.findByText('暂无可选标签'); await user.type(screen.getByLabelText('查找标签'), '独立名称{Enter}');
  await waitFor(() => expect(f.send).toHaveBeenCalledTimes(2)); expect(f.send.mock.calls[1][0]).toBe(path);
  expect(JSON.parse(f.send.mock.calls[1][1].body)).toEqual({ nameQuery: '独立名称' }); expect(submit).not.toHaveBeenCalled();
});

it('输入法确认文字时不提交标签或外层表单', async () => {
  f.send.mockImplementation(async () => page([])); const keydown = vi.fn();
  render(defineComponent({ setup: () => () => h('div', { onKeydown: keydown }, [h(TagPicker, { kind: 'search', value: [], onChange: vi.fn() })]) }));
  await screen.findByText('暂无可选标签'); const input = screen.getByLabelText('查找标签'); await fireEvent.update(input, '中文');
  expect(input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, bubbles: true, cancelable: true }))).toBe(false);
  expect(input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 229, bubbles: true, cancelable: true }))).toBe(false);
  expect(f.send).toHaveBeenCalledOnce(); expect(keydown).not.toHaveBeenCalled();
});

it('搜索分类展示祖先并分层分页，409 停止拼接且主动重读', async () => {
  f.send.mockResolvedValueOnce(page([node('1', '祖先')], 'next')).mockResolvedValueOnce(new Response(null, { status: 409 }))
    .mockResolvedValueOnce(page([node('1', '祖先')])).mockResolvedValueOnce(page([{ id: '2', name: '后代', parentId: '1', sortOrder: 0, path: [{ id: '1', name: '祖先' }, { id: '2', name: '后代' }] }]));
  const select = vi.fn(); render(CategoryBrowser, { props: { context: { kind: 'search', id: '8' }, onSelect: select } });
  await fireEvent.click(await screen.findByRole('button', { name: '继续读取分类' }));
  await screen.findByText('分类目录已变化，请重新读取。'); expect(screen.queryByRole('button', { name: '祖先' })).toBeNull();
  expect(f.send).toHaveBeenCalledTimes(2); expect(select).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: '重新读取目录' })); await fireEvent.click(await screen.findByRole('button', { name: '展开 祖先' }));
  await fireEvent.click(await screen.findByRole('button', { name: '后代' })); expect(select.mock.calls[0][0].path).toHaveLength(2);
  expect(f.send.mock.calls.every(([path]) => path === '/api/search/categories')).toBe(true);
  expect(JSON.parse(f.send.mock.calls[3][1].body)).toEqual({ spaceId: 8, parentId: 1 });
});
it('新空间忽略旧分类迟到响应', async () => {
  const pending = deferred<Response>(); f.send.mockReturnValueOnce(pending.promise).mockResolvedValueOnce(page([node('2', '新空间')]));
  const view = render(CategoryBrowser, { props: { context: { kind: 'search', id: '1' }, onSelect: vi.fn() } }); const signal = f.send.mock.calls[0][1].signal;
  await view.rerender({ context: { kind: 'search', id: '2' } }); await screen.findByRole('button', { name: '新空间' });
  pending.resolve(page([node('1', '旧空间')])); await flushPromises(); expect(signal.aborted).toBe(true); expect(screen.queryByText('旧空间')).toBeNull();
});
it('全局标签可选停用项，名称改变废弃旧页，错误提供重读', async () => {
  const pending = deferred<Response>();
  f.send.mockResolvedValueOnce(page([{ id: '1', name: '停用项', enabled: false }], 'next')).mockReturnValueOnce(pending.promise)
    .mockResolvedValueOnce(page([{ id: '2', name: '新名称', enabled: true }])).mockResolvedValueOnce(new Response(null, { status: 404 }));
  const select = vi.fn(); render(TagPicker, { props: { kind: 'search', value: [], onChange: select } });
  await fireEvent.click(await screen.findByRole('button', { name: '停用项（停用）' })); expect(select).toHaveBeenCalledWith([{ id: '1', name: '停用项', enabled: false }]);
  await fireEvent.click(screen.getByRole('button', { name: '继续读取标签' }));
  await fireEvent.update(screen.getByLabelText('查找标签'), '新'); await fireEvent.click(screen.getByRole('button', { name: '查询标签' }));
  await screen.findByRole('button', { name: '新名称' }); pending.resolve(page([{ id: '3', name: '旧页', enabled: true }])); await flushPromises();
  expect(screen.queryByText('旧页')).toBeNull(); expect(f.send.mock.calls.every(([path]) => path === '/api/search/tags')).toBe(true);
  expect(JSON.parse(f.send.mock.calls[2][1].body)).toEqual({ nameQuery: '新' });
  await fireEvent.click(screen.getByRole('button', { name: '重新读取标签' })); await screen.findByText('搜索候选暂不可用，请主动重新读取。'); expect(f.send).toHaveBeenCalledTimes(4);
});
it.each([['filter', '/api/documents/query/categories'], ['edit', '/api/documents/8/metadata-options/categories'], ['manage', '/api/admin/spaces/8/categories']] as const)('原分类 %s 用途保持端点', async (kind, path) => {
  f.send.mockResolvedValue(page([])); render(CategoryBrowser, { props: { context: { kind, id: '8' }, onSelect: vi.fn() } });
  await screen.findByText('此层暂无分类'); expect(f.send.mock.calls[0][0]).toBe(path);
});
it.each([['filter', '/api/documents/query/tags'], ['edit', '/api/documents/8/metadata-options/tags']] as const)('原标签 %s 用途保持端点', async (kind, path) => {
  f.send.mockResolvedValue(page([])); render(TagPicker, { props: { kind, documentId: '8', value: [], onChange: vi.fn() } });
  await waitFor(() => expect(f.send).toHaveBeenCalledOnce()); expect(f.send.mock.calls[0][0]).toBe(path);
});
