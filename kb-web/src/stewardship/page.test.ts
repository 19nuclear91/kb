import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { ApiError } from '../api';
import StewardshipPage from './StewardshipPage.vue';

const f = vi.hoisted(() => ({ mine: vi.fn(), write: vi.fn(), count: vi.fn() }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { getSnapshot: () => ({ epoch: 1, phase: 'ready' }) }, state: { epoch: 1, phase: 'ready' } }) }));
vi.mock('../admin/access', () => ({ useAdminAccess: () => ({ check: vi.fn(), factsKey: 'ready' }) }));
vi.mock('../validity/api', async original => ({ ...await original<object>(), validityWrite: f.write, validityApi: { mine: f.mine, count: f.count } }));
beforeEach(() => { f.mine.mockReset().mockResolvedValue({ items: [], nextCursor: null, hasMore: false, asOfDate: '2026-11-15' }); f.write.mockReset(); f.count.mockResolvedValue({ count: '0', asOfDate: '2026-11-15' }); });
const show = () => mount(StewardshipPage, { global: { stubs: { RouterLink: true, Teleport: true } } });
it('成功空清单与故障清单严格区分', async () => {
  const wrapper = show(); await flushPromises(); expect(wrapper.text()).toContain('暂无需要处理的资料'); wrapper.unmount();
  f.mine.mockRejectedValue(new ApiError(503)); const failed = show(); await flushPromises(); expect(failed.text()).not.toContain('暂无需要处理的资料'); expect(failed.text()).toContain('服务暂不可用'); failed.unmount();
});
it('结果未知不自动重发，原标识重试才确认', async () => {
  f.mine.mockResolvedValue({ items: [{ documentId: '10', title: '本人资料', state: 'DUE_SOON', dueDate: '2026-11-30', lastConfirmedOn: null, cycleMonths: 3, stewardshipVersion: 1, attentionReasons: ['REVIEW_DUE_SOON'], currentVersion: null }], nextCursor: null, hasMore: false, asOfDate: '2026-11-15' });
  f.write.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce({ documentId: '10', stewardshipVersion: 2 });
  const wrapper = show(); await flushPromises(); await wrapper.findAll('button').find(button => button.text() === '确认仍然准确')!.trigger('click');
  await wrapper.findAll('button').find(button => button.text() === '确认此资料仍然准确')!.trigger('click'); await flushPromises();
  expect(f.write).toHaveBeenCalledOnce(); expect(wrapper.text()).toContain('提交结果尚未确认');
  await wrapper.findAll('button').find(button => button.text() === '按原内容重试')!.trigger('click'); await flushPromises();
  expect(f.write.mock.calls[0]![2]).toBe(f.write.mock.calls[1]![2]); expect(wrapper.text()).toContain('复审确认成功'); wrapper.unmount();
});

it('切换视图后旧清单迟到不能覆盖新视图', async () => {
  let resolve!: (value: unknown) => void;
  f.mine.mockImplementationOnce(() => new Promise(done => { resolve = done; })).mockResolvedValue({ items: [], nextCursor: null, hasMore: false, asOfDate: '2026-11-15' });
  const wrapper = show(); await wrapper.findAll('button').find(button => button.text() === '全部')!.trigger('click'); await flushPromises();
  resolve({ items: [{ documentId: '10', title: '旧视图资料' }], nextCursor: null, hasMore: false, asOfDate: '2026-11-15' }); await flushPromises();
  expect(wrapper.text()).toContain('暂无本人负责的资料'); expect(wrapper.text()).not.toContain('旧视图资料'); wrapper.unmount();
});
