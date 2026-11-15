import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import ApplicabilityEditor from './ApplicabilityEditor.vue';
import { ValidityError } from './api';

const f = vi.hoisted(() => ({ submit: vi.fn(), retry: vi.fn(), pending: false, state: { exclusive: false } }));
vi.mock('../documents/OperationContext', () => ({ useDocumentOperations: () => ({ state: f.state }) }));
vi.mock('../admin/hooks', () => ({ useAdminWrite: () => ({ submit: f.submit, retry: f.retry, get pending() { return f.pending; }, blocked: false, busy: false, phase: 'idle' }) }));
const data = { documentId: '10', versionId: '20', state: 'EFFECTIVE' as const, effectiveFrom: null, untilKind: 'OPEN_ENDED' as const, effectiveUntil: null, corrected: true, applicabilityVersion: 1, asOfDate: '2026-11-15', canEdit: true, reviewFrozen: false, visibleToReaders: true };
beforeEach(() => { f.submit.mockReset(); f.pending = false; f.state = reactive({ exclusive: false }); });
it('已发布版本显示原因可见范围，候选不要求原因，删除独占时不保存', async () => {
  const wrapper = mount(ApplicabilityEditor, { props: { data }, global: { stubs: { WriteNotice: true } } });
  expect(wrapper.text()).toContain('所有可读此版本的用户可见'); expect(wrapper.find('textarea').exists()).toBe(true);
  await wrapper.setProps({ data: { ...data, visibleToReaders: false } }); expect(wrapper.find('textarea').exists()).toBe(false);
  f.state.exclusive = true; await wrapper.find('form').trigger('submit'); expect(f.submit).not.toHaveBeenCalled(); wrapper.unmount();
});
it('REASON_REQUIRED 和冲突向父区块发出重读信号，不显示保存成功', async () => {
  f.submit.mockResolvedValue({ status: 'rejected', error: new ValidityError('REASON_REQUIRED') });
  const wrapper = mount(ApplicabilityEditor, { props: { data: { ...data, visibleToReaders: false } }, global: { stubs: { WriteNotice: true } } });
  await wrapper.find('form').trigger('submit'); await flushPromises(); expect(wrapper.emitted('rejected')).toHaveLength(1); expect(wrapper.emitted('saved')).toBeUndefined(); wrapper.unmount();
});
