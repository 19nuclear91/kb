import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { defineComponent, h, reactive } from 'vue';
import { ApiError } from '../api';
import { DocumentOperations } from '../documents/operations';
import { provideDocumentOperations } from '../documents/OperationContext';
import { note } from '../test/versionDiff';
import ChangeNotePanel from './ChangeNotePanel.vue';
import { ChangeNoteError } from './api';

const mocks = vi.hoisted(() => ({ get: vi.fn(), write: vi.fn(), state: { epoch: 1, phase: 'ready' } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { getSnapshot: () => mocks.state }, state: mocks.state }) }));
vi.mock('../admin/access', () => ({ useAdminAccess: () => ({ check: vi.fn(), factsKey: 'ready' }) }));
vi.mock('./api', async original => ({ ...await original<object>(), changeNoteWrite: mocks.write, changeNotesApi: { get: mocks.get } }));
const mounted: Array<{ unmount(): void }> = [];
beforeEach(() => { mocks.get.mockReset().mockResolvedValue(note); mocks.write.mockReset(); mocks.state = reactive({ epoch: 1, phase: 'ready' }); });
afterEach(() => mounted.splice(0).forEach(view => view.unmount()));
function show() {
  const operations = new DocumentOperations();
  const Host = defineComponent({ props: { versionId: { default: '21' } }, setup(props) { provideDocumentOperations(() => operations);
    return () => h(ChangeNotePanel, { documentId: '10', versionId: props.versionId }); } });
  const view = mount(Host, { global: { stubs: { RouterLink: true, ChangeNoteHistory: true } } }); mounted.push(view); return { view, operations };
}
type View = ReturnType<typeof show>['view'];
const button = (view: View, label: string) => view.findAll('button').find(button => button.text() === label)!;
async function edit(view: View) { await button(view, '编辑变更说明').trigger('click'); await view.find('textarea[aria-label="变更说明正文"]').setValue('提交的新文本'); }
async function submit(view: View) { await edit(view); await view.find('form').trigger('submit'); await flushPromises(); }
it('待审显示基准和历史可见提示，可无原因保存及清除', async () => {
  mocks.write.mockResolvedValue({}); const { view } = show(); await flushPromises(); await edit(view);
  expect(view.text()).toContain('相对 v1'); expect(view.text()).toContain('修改历史对可读此版本的用户可见');
  expect(view.find('textarea[aria-label="变更说明更正原因"]').exists()).toBe(false);
  await button(view, '清除变更说明').trigger('click'); await flushPromises();
  expect(mocks.write.mock.calls[0]![1].body).toMatchObject({ text: null, reason: null, expectedNoteVersion: 1n });
});
it.each([false, true])('发布后已有说明=%s 时显示对应规则与原因输入', async hasNote => {
  mocks.get.mockResolvedValue({ ...note, visibleToReaders: true, hasNote, text: hasNote ? note.text : null });
  const { view } = show(); await flushPromises(); await edit(view);
  expect(view.find('textarea[aria-label="变更说明更正原因"]').exists()).toBe(hasNote);
  expect(button(view, '清除变更说明')).toBeUndefined();
  expect(view.text()).toContain(hasNote ? '修改立即生效，必须填写原因' : '发布后首次补写，不要求原因');
});
it.each(['冻结', '无基准'])('%s 时不提供编辑提交入口', async kind => {
  mocks.get.mockResolvedValue({ ...note, ...(kind === '冻结' ? { reviewFrozen: true } : { writeBaseVersionId: null, writeBaseVersionNo: null }) });
  const { view } = show(); await flushPromises(); expect(button(view, '编辑变更说明')).toBeUndefined();
  expect(view.text()).toContain(kind === '冻结' ? '审核期间变更说明已冻结' : '第一个版本不能写变更说明');
});
it.each(['REASON_REQUIRED', 'NOTE_VERSION_CONFLICT'] as const)('%s 拒绝后重读，由用户重新编辑，不自动覆盖', async reason => {
  mocks.write.mockRejectedValue(new ChangeNoteError(reason)); const { view } = show(); await flushPromises();
  mocks.get.mockResolvedValue({ ...note, text: '最新说明', visibleToReaders: true, noteVersion: 2 }); await submit(view);
  expect(mocks.get).toHaveBeenCalledTimes(2); expect(view.find('form').exists()).toBe(false); expect(view.text()).toContain('最新说明');
  expect(view.text()).toContain(reason === 'REASON_REQUIRED' ? '填写更正原因' : '他人修改');
  await edit(view); expect((view.find('textarea[aria-label="变更说明更正原因"]').element as HTMLTextAreaElement).value).toBe(''); expect(mocks.write).toHaveBeenCalledOnce();
});
it.each(['旧文本', '新文本', '503'])('未确认后重读%s 仍以原精确载荷和标识重试', async kind => {
  mocks.write.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce({}); const { view, operations } = show(); await flushPromises(); await submit(view);
  const original = mocks.write.mock.calls[0]!;
  if (kind === '503') mocks.get.mockRejectedValueOnce(new ApiError(503));
  else mocks.get.mockResolvedValueOnce({ ...note, text: kind === '新文本' ? '提交的新文本' : note.text, noteVersion: kind === '新文本' ? 2 : 1 });
  await button(view, '重新读取核对').trigger('click'); await flushPromises();
  expect(operations.hasValidityUncertain()).toBe(true); expect(button(view, '保存变更说明').attributes('disabled')).toBeDefined();
  await view.find('form').trigger('submit'); expect(mocks.write).toHaveBeenCalledOnce();
  await button(view, '关闭说明编辑').trigger('click'); await button(view, '继续核对说明结果').trigger('click');
  await button(view, '按原内容重试').trigger('click'); await flushPromises();
  expect(mocks.write.mock.calls[1]![1]).toEqual(original[1]); expect(mocks.write.mock.calls[1]![2]).toBe(original[2]);
  expect(operations.hasValidityUncertain()).toBe(false); expect(view.text()).toContain('变更说明已保存');
});
it('说明在途及未确认阻止删除，删除确认阻止重试；显式放弃解除绑定', async () => {
  mocks.write.mockRejectedValueOnce(new ApiError(0)); const { view, operations } = show(); await flushPromises(); await submit(view);
  operations.beginDelete(); await flushPromises(); expect(operations.canSubmitDelete()).toBe(false);
  expect(button(view, '按原内容重试').attributes('disabled')).toBeDefined();
  operations.cancelDelete(); await flushPromises(); await button(view, '放弃核对').trigger('click'); await flushPromises();
  expect(operations.hasValidityUncertain()).toBe(false);
});
it.each(['版本', '身份', '改密', '隐藏', '离开'])('%s 变化后清除草稿和迟到写回调', async kind => {
  let complete!: (value: unknown) => void;
  mocks.write.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; })); const { view, operations } = show(); await flushPromises(); await submit(view);
  const panel = view.findComponent(ChangeNotePanel); operations.beginDelete(); expect(operations.canSubmitDelete()).toBe(false);
  if (kind === '版本') await view.setProps({ versionId: '22' });
  else if (kind === '身份') mocks.state.epoch++;
  else if (kind === '改密') mocks.state.phase = 'password';
  else if (kind === '隐藏') { vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange')); }
  else view.unmount();
  await flushPromises(); expect(mocks.write.mock.calls[0]![3].aborted).toBe(true); complete({}); await flushPromises();
  expect(panel.emitted('changed')).toBeUndefined();
  if (kind !== '离开') { expect(view.find('form').exists()).toBe(false); expect(view.text()).not.toContain('变更说明已保存'); }
});
