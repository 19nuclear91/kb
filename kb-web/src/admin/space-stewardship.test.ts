import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import SpaceStewardship from './SpaceStewardship.vue';

const f = vi.hoisted(() => ({ space: vi.fn(), json: vi.fn() }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { json: f.json }, state: { epoch: 1, phase: 'ready' } }) }));
vi.mock('./access', () => ({ useAdminAccess: () => ({ check: vi.fn(), factsKey: 'ready' }) }));
vi.mock('../validity/api', async original => ({ ...await original<object>(), validityApi: { space: f.space } }));
const page = { items: [], nextCursor: null, hasMore: false, asOfDate: '2026-11-15' };
beforeEach(() => { f.space.mockReset().mockResolvedValue(page); f.json.mockReset(); });
const show = () => mount(SpaceStewardship, { props: { id: '2', archived: true }, global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } });
it('四个标签只列ID，不补标题，归档清单只读', async () => {
  f.space.mockResolvedValue({ ...page, items: [{ documentId: '10', state: 'UNASSIGNED', dueDate: null, responsible: null }] });
  const wrapper = show(); await flushPromises(); expect(wrapper.text()).toContain('文档 10'); expect(wrapper.text()).toContain('空间已归档，复审暂停'); expect(f.json).not.toHaveBeenCalled();
  for (const label of ['未指定', '需重新指定', '即将到期', '已逾期']) expect(wrapper.findAll('button').some(button => button.text() === label)).toBe(true);
  await wrapper.findAll('button').find(button => button.text() === '已逾期')!.trigger('click'); await flushPromises(); expect(f.space.mock.calls.at(-1)![2]).toBe('OVERDUE'); wrapper.unmount();
});
it('503不显示空清单，404清除已显示清单并重新核对资格', async () => {
  f.space.mockRejectedValueOnce(new ApiError(503)); const failed = show(); await flushPromises(); expect(failed.text()).toContain('服务暂不可用'); expect(failed.text()).not.toContain('此状态下暂无文档'); failed.unmount();
  f.space.mockResolvedValueOnce({ ...page, items: [{ documentId: '10', state: 'UNASSIGNED', dueDate: null, responsible: null }], nextCursor: 'cursor', hasMore: true }).mockRejectedValue(new ApiError(404));
  const wrapper = show(); await flushPromises(); await wrapper.findAll('button').find(button => button.text() === '加载更多复审文档')!.trigger('click'); await flushPromises();
  expect(wrapper.text()).not.toContain('文档 10'); expect(wrapper.emitted('recheck')).toHaveLength(1); wrapper.unmount();
});
it('切换标签后前一标签迟到不能恢复旧ID或游标', async () => {
  let done!: (page: unknown) => void;
  f.space.mockImplementationOnce(() => new Promise(resolve => { done = resolve; })).mockResolvedValue(page);
  const wrapper = show(); await wrapper.findAll('button').find(button => button.text() === '已逾期')!.trigger('click'); await flushPromises();
  done({ ...page, items: [{ documentId: '99', state: 'UNASSIGNED', dueDate: null, responsible: null }], nextCursor: 'old', hasMore: true }); await flushPromises();
  expect(wrapper.text()).not.toContain('文档 99'); expect(wrapper.text()).not.toContain('加载更多复审文档'); wrapper.unmount();
});
