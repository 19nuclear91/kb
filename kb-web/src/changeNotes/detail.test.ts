import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { ApiError } from '../api';
import { note } from '../test/versionDiff';
import ChangeNotePanel from './ChangeNotePanel.vue';

const mocks = vi.hoisted(() => ({ get: vi.fn(), history: vi.fn(), state: { epoch: 1, phase: 'ready' } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { getSnapshot: () => mocks.state }, state: mocks.state }) }));
vi.mock('../admin/access', () => ({ useAdminAccess: () => ({ check: vi.fn(), factsKey: 'ready' }) }));
vi.mock('./api', async original => ({ ...await original<object>(), changeNotesApi: { get: mocks.get, history: mocks.history } }));
const mounted: Array<{ unmount(): void }> = [];
beforeEach(() => { mocks.get.mockReset().mockResolvedValue(note); mocks.history.mockReset(); mocks.state = reactive({ epoch: 1, phase: 'ready' }); });
afterEach(() => mounted.splice(0).forEach(view => view.unmount()));
function show() { const view = mount(ChangeNotePanel, { props: { documentId: '10', versionId: '21' }, global: { stubs: { RouterLink: true } } }); mounted.push(view); return view; }
it('没有说明按服务端明确状态展示', async () => {
  mocks.get.mockResolvedValue({ ...note, hasNote: false, text: null, baseVersionId: null, baseVersionNo: null });
  const view = show(); await flushPromises(); expect(view.text()).toContain('没有变更说明');
});
it('显示基准、已更正和补写标记，说明与历史均按纯文本展示且不显示操作者', async () => {
  mocks.get.mockResolvedValue({ ...note, text: '<img src="https://example.test/x">说明', corrected: true, createdAfterVisible: true });
  mocks.history.mockResolvedValue({ items: [{ id: '1', documentId: '10', versionId: '21', changedAt: '2026-11-17T00:00:00Z',
    textBefore: '旧说明', textAfter: '<script>新说明</script>', baseBefore: '20', baseAfter: '20', reason: '<b>原因</b>', visibleToReaders: true, actorUserId: '绝不显示' }], nextCursor: 'next', hasMore: true });
  const view = show(); await flushPromises();
  for (const expected of ['相对 v1', '已更正', '发布后补写', '<img']) expect(view.text()).toContain(expected);
  await view.findAll('button').find(button => button.text() === '查看说明修改历史')!.trigger('click'); await flushPromises();
  for (const expected of ['2026-11-17', '旧说明', '<script>新说明</script>', '<b>原因</b>', '发布后写入']) expect(view.text()).toContain(expected);
  expect(view.find('img, script, b').exists()).toBe(false); expect(view.text()).not.toContain('绝不显示');
  mocks.history.mockResolvedValue({ items: [], nextCursor: null, hasMore: false });
  await view.findAll('button').find(button => button.text() === '继续读取修改历史')!.trigger('click'); await flushPromises();
  expect(mocks.history.mock.calls[1]![2]).toBe('next');
});
it('503 不伪装为没有说明，版本切换后丢弃旧读取', async () => {
  let resolve!: (value: unknown) => void;
  mocks.get.mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockRejectedValue(new ApiError(503));
  const view = show(); await view.setProps({ versionId: '22' }); await flushPromises(); resolve(note); await flushPromises();
  expect(view.text()).toContain('变更说明暂不可确认'); expect(view.text()).not.toContain('没有变更说明'); expect(view.text()).not.toContain(note.text);
});
