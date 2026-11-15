import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { defineComponent, h, reactive } from 'vue';
import { ApiError } from '../api';
import { DocumentOperations } from '../documents/operations';
import { provideDocumentOperations } from '../documents/OperationContext';
import ApplicabilityPanel from './ApplicabilityPanel.vue';

const mocks = vi.hoisted(() => ({ get: vi.fn(), write: vi.fn(), state: { epoch: 1, phase: 'ready' } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { getSnapshot: () => mocks.state }, state: mocks.state }) }));
vi.mock('../admin/access', () => ({ useAdminAccess: () => ({ check: vi.fn(), factsKey: 'ready' }) }));
vi.mock('./api', async original => ({ ...await original<object>(), validityWrite: mocks.write, validityApi: { get: mocks.get } }));
const data = { documentId: '10', versionId: '20', state: 'NOT_YET_EFFECTIVE', effectiveFrom: '2027-01-01', untilKind: 'UNDECLARED', effectiveUntil: null, corrected: true, applicabilityVersion: 1, asOfDate: '2026-12-15', canEdit: false, reviewFrozen: false, visibleToReaders: true };
beforeEach(() => { mocks.get.mockReset(); mocks.write.mockReset(); mocks.state = reactive({ epoch: 1, phase: 'ready' }); });
it('显示选中版本声明、已更正和当前版本尚未生效提示', async () => {
  mocks.get.mockResolvedValue(data);
  const wrapper = mount(ApplicabilityPanel, { props: { documentId: '10', versionId: '20', current: true }, global: { stubs: { ApplicabilityHistory: true } } });
  await flushPromises(); expect(wrapper.text()).toContain('尚未生效'); expect(wrapper.text()).toContain('2027-01-01'); expect(wrapper.text()).toContain('已更正'); expect(wrapper.text()).toContain('上一发布版本可能仍在施行');
  expect(wrapper.text()).toContain('查看适用期修改历史'); wrapper.unmount();
});
it('读取故障不伪装为未声明，切换版本后旧响应不覆盖', async () => {
  let done!: (value: unknown) => void;
  mocks.get.mockImplementationOnce(() => new Promise(resolve => { done = resolve; })).mockRejectedValue(new ApiError(503));
  const wrapper = mount(ApplicabilityPanel, { props: { documentId: '10', versionId: '20' } });
  await wrapper.setProps({ versionId: '21' }); await flushPromises(); done(data); await flushPromises();
  expect(wrapper.text()).toContain('适用期暂不可确认'); expect(wrapper.text()).not.toContain('已更正'); wrapper.unmount();
});

const writable = { ...data, state: 'EFFECTIVE', effectiveFrom: null, untilKind: 'OPEN_ENDED', canEdit: true };
const mounted: Array<{ unmount(): void }> = [];
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); });
function showEditor() {
  const operations = new DocumentOperations();
  const host = defineComponent({ props: { documentId: { default: '10' }, versionId: { default: '20' } }, setup(props) {
    provideDocumentOperations(() => operations); return () => h(ApplicabilityPanel, props);
  } });
  const wrapper = mount(host); mounted.push(wrapper);
  return { wrapper, operations };
}
type EditorWrapper = ReturnType<typeof showEditor>['wrapper'];
const button = (wrapper: EditorWrapper, text: string) => wrapper.findAll('button').find(candidate => candidate.text() === text)!;
async function editAndSubmit(wrapper: EditorWrapper) {
  await button(wrapper, '编辑适用期').trigger('click');
  await wrapper.find('select[aria-label="截止类型"]').setValue('DATE');
  await wrapper.find('input[aria-label="截止日"]').setValue('2026-12-31');
  await wrapper.find('textarea').setValue('更正原因');
  await wrapper.find('form').trigger('submit'); await flushPromises();
}
it.each(['旧声明', '新声明', '503'])('未确认后重读%s保留原精确内容与标识，不能提交新意图', async result => {
  mocks.get.mockResolvedValue(writable); mocks.write.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce({});
  const { wrapper, operations } = showEditor(); await flushPromises(); await editAndSubmit(wrapper);
  const original = mocks.write.mock.calls[0]!;
  expect(operations.hasValidityUncertain()).toBe(true);
  if (result === '503') mocks.get.mockRejectedValueOnce(new ApiError(503));
  else mocks.get.mockResolvedValueOnce(result === '新声明' ? { ...writable, untilKind: 'DATE', effectiveUntil: '2026-12-31', applicabilityVersion: 2 } : writable);
  await button(wrapper, '重新读取核对').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('按原内容重试');
  expect(button(wrapper, '保存适用期').attributes('disabled')).toBeDefined();
  await wrapper.find('form').trigger('submit'); expect(mocks.write).toHaveBeenCalledOnce();
  expect(operations.hasValidityUncertain()).toBe(true);
  await button(wrapper, '按原内容重试').trigger('click'); await flushPromises();
  expect(mocks.write.mock.calls[1]![1]).toEqual(original[1]); expect(mocks.write.mock.calls[1]![2]).toBe(original[2]);
  expect(wrapper.text()).toContain('适用期已保存'); expect(operations.hasValidityUncertain()).toBe(false);
});
it('关闭未知编辑后可恢复核对；删除独占阻止原绑定重试，取消后恢复', async () => {
  mocks.get.mockResolvedValue(writable); mocks.write.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce({});
  const { wrapper, operations } = showEditor(); await flushPromises(); await editAndSubmit(wrapper);
  const original = mocks.write.mock.calls[0]![2];
  await button(wrapper, '关闭编辑').trigger('click'); await flushPromises();
  expect(wrapper.find('form').exists()).toBe(false);
  expect(button(wrapper, '编辑适用期').attributes('disabled')).toBeDefined();
  operations.beginDelete(); expect(operations.canSubmitDelete()).toBe(false);
  await button(wrapper, '继续核对适用期结果').trigger('click'); await flushPromises();
  expect(button(wrapper, '按原内容重试').attributes('disabled')).toBeDefined();
  operations.cancelDelete(); await flushPromises();
  await button(wrapper, '按原内容重试').trigger('click'); await flushPromises();
  expect(mocks.write.mock.calls[1]![2]).toBe(original);
});
it.each(['区块', '整页'])('%s显式放弃才解除未知绑定，重新编辑使用新标识', async target => {
  mocks.get.mockResolvedValue(writable); mocks.write.mockRejectedValue(new ApiError(0));
  const { wrapper, operations } = showEditor(); await flushPromises(); await editAndSubmit(wrapper);
  const original = mocks.write.mock.calls[0]![2];
  if (target === '区块') await button(wrapper, '放弃核对').trigger('click');
  else operations.abandonValidityUncertainties();
  await flushPromises(); expect(operations.hasValidityUncertain()).toBe(false);
  expect(wrapper.text()).not.toContain('继续核对适用期结果');
  await editAndSubmit(wrapper); expect(mocks.write.mock.calls[1]![2]).not.toBe(original);
});
it.each(['版本', '身份', '强制改密', '隐藏', '离开'])('%s变化丢弃草稿与迟到写回调，不显示旧版本成功', async target => {
  let complete!: (value: unknown) => void;
  mocks.get.mockResolvedValue(writable); mocks.write.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
  const { wrapper } = showEditor(); await flushPromises(); await editAndSubmit(wrapper);
  const oldPanel = wrapper.findComponent(ApplicabilityPanel);
  if (target === '版本') await wrapper.setProps({ versionId: '21' });
  else if (target === '身份') mocks.state.epoch++;
  else if (target === '强制改密') mocks.state.phase = 'password';
  else if (target === '隐藏') { vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange')); }
  else wrapper.unmount();
  await flushPromises();
  expect(mocks.write.mock.calls[0]![3].aborted).toBe(true);
  complete({}); await flushPromises();
  expect(oldPanel.emitted('changed')).toBeUndefined();
  if (target !== '离开') { expect(wrapper.find('form').exists()).toBe(false); expect(wrapper.text()).not.toContain('适用期已保存'); }
});
