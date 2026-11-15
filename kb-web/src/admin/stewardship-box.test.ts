import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { defineComponent, h, reactive } from 'vue';
import { ApiError } from '../api';
import { DocumentOperations } from '../documents/operations';
import { provideDocumentOperations } from '../documents/OperationContext';
import StewardshipBox from './StewardshipBox.vue';

const f = vi.hoisted(() => ({ read: vi.fn(), candidates: vi.fn(), write: vi.fn(), state: { epoch: 1, phase: 'ready' } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { getSnapshot: () => f.state }, state: f.state }) }));
vi.mock('./access', () => ({ useAdminAccess: () => ({ check: vi.fn(), factsKey: 'ready' }) }));
vi.mock('../validity/api', async original => ({ ...await original<object>(), validityWrite: f.write, validityApi: { stewardship: f.read, candidates: f.candidates } }));
const data = { documentId: '10', spaceId: '2', state: 'NEEDS_REASSIGN', responsible: { userId: '8', displayName: '原责任人' }, cycleMonths: 3, firstAssignedOn: '2026-11-30', lastConfirmedOn: null, dueDate: '2027-02-28', stewardshipVersion: 1, asOfDate: '2026-11-15' };
beforeEach(() => { f.state = reactive({ epoch: 1, phase: 'ready' }); f.read.mockReset().mockResolvedValue(data); f.candidates.mockReset().mockResolvedValue({ items: [{ userId: '9', displayName: '新责任人' }], nextCursor: null, hasMore: false, asOfDate: '2026-11-15' }); f.write.mockReset().mockResolvedValue({ documentId: '10', stewardshipVersion: 2 }); });
it('需重新指定只展示原显示名与状态，归档只读不读取候选', async () => {
  const wrapper = mount(StewardshipBox, { props: { id: '10', archived: true } }); await flushPromises();
  expect(wrapper.text()).toContain('需重新指定'); expect(wrapper.text()).toContain('原责任人'); expect(wrapper.text()).not.toContain('停用');
  expect(wrapper.findAll('button').some(button => button.text() === '改派责任人')).toBe(false); expect(f.candidates).not.toHaveBeenCalled(); wrapper.unmount();
});

const mounted: Array<{ unmount(): void }> = [];
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); });
function showBox() {
  const operations = new DocumentOperations();
  const host = defineComponent({ props: { id: { default: '10' }, archived: { default: false } }, setup(props) {
    provideDocumentOperations(() => operations); return () => h(StewardshipBox, props);
  } });
  const wrapper = mount(host, { global: { stubs: { Teleport: true } } }); mounted.push(wrapper);
  return { wrapper, operations };
}
type BoxWrapper = ReturnType<typeof showBox>['wrapper'];
const button = (wrapper: BoxWrapper, text: string) => wrapper.findAll('button').find(candidate => candidate.text() === text)!;
async function changeCycle(wrapper: BoxWrapper) {
  await button(wrapper, '修改复审周期').trigger('click');
  await wrapper.find('select[aria-label="复审周期"]').setValue('6');
  await wrapper.find('form').trigger('submit'); await flushPromises();
}
it.each(['旧信息', '新信息', '503'])('未知写入关闭后重读%s，再打开仍以原内容原标识重试', async result => {
  f.write.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce({});
  const { wrapper, operations } = showBox(); await flushPromises(); await changeCycle(wrapper);
  const original = f.write.mock.calls[0]!;
  await wrapper.find('button.el-dialog__headerbtn').trigger('click'); await flushPromises();
  expect(wrapper.find('form').exists()).toBe(false); expect(operations.hasValidityUncertain()).toBe(true);
  if (result === '503') f.read.mockRejectedValueOnce(new ApiError(503));
  else f.read.mockResolvedValueOnce(result === '新信息' ? { ...data, cycleMonths: 6, stewardshipVersion: 2 } : data);
  await button(wrapper, '继续核对复审结果').trigger('click'); await flushPromises();
  await button(wrapper, '重新读取核对').trigger('click'); await flushPromises();
  await wrapper.find('button.el-dialog__headerbtn').trigger('click'); await flushPromises();
  await button(wrapper, '继续核对复审结果').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('按原内容重试'); expect(button(wrapper, '确认提交').attributes('disabled')).toBeDefined();
  operations.beginDelete(); expect(operations.canSubmitDelete()).toBe(false); await flushPromises();
  expect(button(wrapper, '按原内容重试').attributes('disabled')).toBeDefined();
  operations.cancelDelete(); await flushPromises(); await button(wrapper, '按原内容重试').trigger('click'); await flushPromises();
  expect(f.write.mock.calls[1]![1]).toEqual(original[1]); expect(f.write.mock.calls[1]![2]).toBe(original[2]);
  expect(operations.hasValidityUncertain()).toBe(false); expect(wrapper.text()).toContain('已保存');
});
it.each(['区块', '整页'])('%s显式放弃后才能开始新管理写入，新请求不复用旧标识', async target => {
  f.write.mockRejectedValue(new ApiError(0));
  const { wrapper, operations } = showBox(); await flushPromises(); await changeCycle(wrapper);
  const original = f.write.mock.calls[0]![2];
  if (target === '区块') await button(wrapper, '放弃核对').trigger('click');
  else operations.abandonValidityUncertainties();
  await flushPromises(); expect(operations.hasValidityUncertain()).toBe(false); expect(wrapper.find('form').exists()).toBe(false);
  await changeCycle(wrapper); expect(f.write.mock.calls[1]![2]).not.toBe(original);
});
it.each(['文档', '身份', '强制改密', '隐藏', '离开'])('%s变化不恢复旧管理草稿，也不接受迟到回调', async target => {
  let complete!: (value: unknown) => void;
  f.write.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
  const { wrapper } = showBox(); await flushPromises(); await changeCycle(wrapper);
  const oldBox = wrapper.findComponent(StewardshipBox);
  if (target === '文档') await wrapper.setProps({ id: '11' });
  else if (target === '身份') f.state.epoch++;
  else if (target === '强制改密') f.state.phase = 'password';
  else if (target === '隐藏') { vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange')); }
  else wrapper.unmount();
  await flushPromises(); expect(f.write.mock.calls[0]![3].aborted).toBe(true);
  complete({}); await flushPromises(); expect(oldBox.emitted('changed')).toBeUndefined();
  if (target !== '离开') { expect(wrapper.find('form').exists()).toBe(false); expect(wrapper.text()).not.toContain('继续核对复审结果'); }
});
it('区块503独立显示，删除独占时改派禁用，取消后恢复', async () => {
  f.read.mockRejectedValueOnce(new ApiError(503)); const failed = mount(StewardshipBox, { props: { id: '10', archived: false } }); await flushPromises(); expect(failed.text()).toContain('服务暂不可用'); expect(failed.emitted('recheck')).toBeUndefined(); failed.unmount();
  const operations = new DocumentOperations(); const host = defineComponent({ setup() { provideDocumentOperations(() => operations); return () => h(StewardshipBox, { id: '10', archived: false }); } });
  const wrapper = mount(host); await flushPromises(); operations.beginDelete(); await flushPromises();
  const button = () => wrapper.findAll('button').find(button => button.text() === '改派责任人')!;
  expect(button().attributes('disabled')).toBeDefined(); operations.cancelDelete(); await flushPromises(); expect(button().attributes('disabled')).toBeUndefined(); wrapper.unmount();
});

it('指定、改派、周期和取消均明确确认，候选只使用服务端显示名和ID', async () => {
  for (const kind of ['指定责任人', '改派责任人', '修改复审周期', '取消责任人']) {
    f.write.mockClear(); f.read.mockResolvedValue(kind === '指定责任人' ? { ...data, state: 'UNASSIGNED', responsible: null } : data);
    const wrapper = mount(StewardshipBox, { props: { id: '10', archived: false }, global: { stubs: { Teleport: true } } }); await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === kind)!.trigger('click'); await flushPromises();
    expect(wrapper.text()).toContain('改派不重新计时'); expect(f.write).not.toHaveBeenCalled();
    if (kind === '指定责任人' || kind === '改派责任人') await wrapper.find('select[aria-label="合格责任人"]').setValue('9');
    if (kind === '修改复审周期') await wrapper.find('select[aria-label="复审周期"]').setValue('6');
    await wrapper.find('form').trigger('submit'); await flushPromises(); expect(f.write).toHaveBeenCalledOnce();
    const intent = f.write.mock.calls[0]![1]; expect(intent.body.expectedStewardshipVersion).toBe(1n);
    expect(intent.body.responsibleUserId).toBe(kind === '取消责任人' ? null : kind === '修改复审周期' ? 8n : 9n);
    expect(wrapper.text()).toContain('责任人与复审设置已保存'); wrapper.unmount();
  }
});
