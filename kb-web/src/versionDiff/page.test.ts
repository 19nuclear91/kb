import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { ApiError } from '../api';
import { mountRouter } from '../test/app';
import { deferred } from '../test/fixtures';
import { diff, note, operation } from '../test/versionDiff';
import { statusLabels } from './api';
import VersionComparePage from './VersionComparePage.vue';

const mocks = vi.hoisted(() => ({ diff: vi.fn(), note: vi.fn(), refresh: vi.fn(), state: { epoch: 1, phase: 'ready' } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: { getSnapshot: () => mocks.state, refresh: mocks.refresh }, state: mocks.state }) }));
vi.mock('../admin/access', async original => ({ ...await original<object>(), useAdminAccess: () => ({ check: vi.fn(), factsKey: 'ready' }) }));
vi.mock('./api', async original => ({ ...await original<object>(), requestDiff: mocks.diff }));
vi.mock('../changeNotes/api', async original => ({ ...await original<object>(), changeNotesApi: { get: mocks.note } }));
vi.mock('../office/VersionPreview.vue', () => ({ default: { props: ['path'], template: '<output v-if="path">{{ path }}</output>' } }));
beforeEach(() => { mocks.state = reactive({ epoch: 1, phase: 'ready' }); mocks.diff.mockReset().mockResolvedValue(diff); mocks.note.mockReset().mockResolvedValue(note); mocks.refresh.mockReset().mockResolvedValue(undefined); });
afterEach(() => Reflect.deleteProperty(document, 'visibilityState'));
const show = (path = '/documents/10/compare/20/21') => mountRouter(() => h(VersionComparePage), { path });
it.each(['/documents/0/compare/20/21', '/documents/10/compare/x/21', '/documents/10/compare/20/20', '/documents/10/compare/-1/21'])('非法链接 %s 不发起任何读取', async path => {
  show(path); await screen.findByText('对比链接无效'); expect(mocks.diff).not.toHaveBeenCalled(); expect(mocks.note).not.toHaveBeenCalled();
});
it('明确绑定路径，HTML 纯文本、逐字片段、未变化折叠及移动来源去向按结果显示', async () => {
  mocks.diff.mockResolvedValue({ ...diff, operations: [operation({ op: 'EQUAL', segments: [], target: { index: 0, pageNo: 1, text: '折叠原文' } }),
    operation({ segments: [{ type: 'DELETE', text: '<img src="https://example.test/x">' }, { type: 'INSERT', text: '<script>新文本</script>' }] }),
    operation({ op: 'MOVED', base: { index: 7, pageNo: null, text: '移动原文' }, target: { index: 3, pageNo: null, text: '移动原文' } })] });
  const view = show(); await screen.findByText('从 v1 到 v2');
  expect(mocks.diff.mock.calls[0]!.slice(1, 4)).toEqual(['10', '20', '21']);
  expect(view.container.querySelector('img, script')).toBeNull(); expect(view.container.querySelector('del')?.textContent).toContain('<img'); expect(view.container.querySelector('ins')?.textContent).toContain('<script>');
  const details = view.container.querySelector('details')!; expect(details.open).toBe(false); await fireEvent.click(details.querySelector('summary')!); expect(details.open).toBe(true);
  expect(screen.getByText('原文移动：来源 7，去向 3')).toBeTruthy();
});
it('过长修改块并列全文，表格重构并列显示两侧，单元格修改标记前后值', async () => {
  mocks.diff.mockResolvedValue({ ...diff, operations: [operation({ segmentsOmitted: true }), operation({ kind: 'TABLE', table: { status: 'RESTRUCTURED', rows: [], base: [['旧列']], target: [['新列', '新增列']] } }),
    operation({ kind: 'TABLE', table: { status: 'CELLS', base: [], target: [], rows: [{ op: 'MODIFIED', baseIndex: 0, targetIndex: 0, before: ['5元'], after: ['8元'], cells: [{ column: 0, before: '5元', after: '8元' }] }] } })] });
  const view = show(); await screen.findByText('该块未逐字比较，以下为两侧全文');
  expect(screen.getByText('原文')).toBeTruthy(); expect(screen.getByText('新文')).toBeTruthy(); expect(screen.getByText(/RESTRUCTURED/)).toBeTruthy();
  for (const text of ['基准表格', '目标表格', '旧列', '新列', '新增列']) expect(screen.getByText(text)).toBeTruthy();
  expect(view.container.querySelector('td del')?.textContent).toBe('5元'); expect(view.container.querySelector('td ins')?.textContent).toBe('8元');
});
it('数值按组显示、标注仅前值和仅后值，点击定位修改块', async () => {
  mocks.diff.mockResolvedValue({ ...diff, valueChanges: [{ type: 'MONEY', before: '5,000元', after: '8,000元', operationIndex: 0, headingPath: '第一章' },
    { type: 'DATE', before: null, after: '2026-11-17', operationIndex: 0, headingPath: null }, { type: 'PERCENT', before: '5%', after: null, operationIndex: 0, headingPath: null }] });
  const view = show(); const button = await screen.findByRole('button', { name: /5,000元 → 8,000元/ });
  const scroll = vi.fn(); Object.defineProperty(view.container.querySelector('#diff-op-0'), 'scrollIntoView', { value: scroll });
  await fireEvent.click(button); expect(scroll).toHaveBeenCalledOnce(); expect(screen.getByText(/不代表全部变化/)).toBeTruthy();
  expect(screen.getByRole('button', { name: /仅后值/ })).toBeTruthy(); expect(screen.getByRole('button', { name: /仅前值/ })).toBeTruthy();
});
it('空数值列表明确识别范围，解析器及截断提示不阻止差异', async () => {
  mocks.diff.mockResolvedValue({ ...diff, warnings: [{ code: 'PARSER_DIFFERS', side: null }, { code: 'TRUNCATED', side: 'TARGET' }] });
  show(); await screen.findByText('修改内容中未识别到日期、金额、百分比或条款编号变化');
  expect(screen.getByText(/部分差异可能来自解析器/)).toBeTruthy(); expect(screen.getByText('目标版本的解析内容被截断')).toBeTruthy(); expect(screen.getByRole('heading', { name: '修改 · 第一章' })).toBeTruthy();
});
it.each(Object.entries(statusLabels))('%s 说明原因，不显示明细并提供指定版本预览', async (status, label) => {
  mocks.diff.mockResolvedValue({ ...diff, status, missingSides: status === 'SNAPSHOT_MISSING' ? ['TARGET'] : [] });
  const view = show(); await screen.findByText(new RegExp(label)); expect(view.container.querySelector('[aria-label="结构差异"]')).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '预览基准版本' })); expect(screen.getByText('/api/documents/10/versions/20/preview')).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: '预览目标版本' })); expect(screen.getByText('/api/documents/10/versions/21/preview')).toBeTruthy();
  if (status === 'SNAPSHOT_MISSING') expect(screen.getByText('· 目标侧')).toBeTruthy();
});
it.each([404, 429, 503])('%s 安全提示，不自动重发，429 只手动重试', async status => {
  mocks.diff.mockRejectedValue(new ApiError(status)); show(); await screen.findByText(status === 404 ? '版本不存在或当前不可查看' : status === 429 ? '对比繁忙，请稍后手动重试' : '暂时无法完成对比');
  await flushPromises(); expect(mocks.diff).toHaveBeenCalledOnce();
  if (status === 429) { await fireEvent.click(screen.getByRole('button', { name: '手动重试对比' })); await waitFor(() => expect(mocks.diff).toHaveBeenCalledTimes(2)); }
});
it.each([true, false])('说明基准一致=%s 时正确显示对应关系与入口', async matches => {
  mocks.note.mockResolvedValue({ ...note, baseVersionId: matches ? '20' : '19', baseVersionNo: matches ? 1 : 7 });
  show(); await screen.findByText(note.text!);
  if (matches) expect(screen.getByText('本次对比的变更说明')).toBeTruthy();
  else { expect(screen.getByText(/此说明对应 v7 到 v2/)).toBeTruthy(); expect(screen.getByRole('link', { name: '按说明基准打开对比' }).getAttribute('href')).toBe('/documents/10/compare/19/21'); }
  expect(screen.queryByRole('button', { name: '编辑变更说明' })).toBeNull();
});
it('说明读取失败不影响差异，也不显示为没有说明', async () => {
  mocks.note.mockRejectedValue(new ApiError(503)); show(); await screen.findByText('变更说明暂不可确认'); await screen.findByText('从 v1 到 v2'); expect(screen.queryByText('没有变更说明')).toBeNull();
});
it('切换版本后旧读取被停止、迟到结果丢弃', async () => {
  const old = deferred<unknown>(); mocks.diff.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ ...diff, target: { ...diff.target, versionId: '22', versionNo: 3 } });
  const view = show(); const signal = mocks.diff.mock.calls[0]![4]; await view.router.push('/documents/10/compare/20/22'); await screen.findByText('从 v1 到 v3');
  expect(signal.aborted).toBe(true); old.resolve(diff); await flushPromises(); expect(screen.queryByText('从 v1 到 v2')).toBeNull();
});
it('换号、退出和改密清除旧内容，新身份重新读取', async () => {
  show(); await screen.findByText('从 v1 到 v2'); mocks.state.phase = 'anonymous'; await screen.findByText('请先完成登录与身份确认'); expect(screen.queryByText(note.text!)).toBeNull();
  mocks.diff.mockReturnValue(deferred().promise); mocks.note.mockReturnValue(deferred().promise); mocks.state.epoch++; mocks.state.phase = 'ready'; await flushPromises();
  expect(screen.queryByText('从 v1 到 v2')).toBeNull(); expect(mocks.diff).toHaveBeenCalledTimes(2);
  mocks.state.phase = 'password'; await screen.findByText('请先完成登录与身份确认');
});
it('隐藏即清空，恢复先核对身份才重新读取', async () => {
  show(); await screen.findByText('从 v1 到 v2');
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange')); await flushPromises(); expect(screen.queryByText('从 v1 到 v2')).toBeNull();
  const refreshed = deferred<void>(); mocks.refresh.mockReturnValueOnce(refreshed.promise); visibility.mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange')); await flushPromises(); expect(mocks.diff).toHaveBeenCalledOnce();
  refreshed.resolve(); await screen.findByText('从 v1 到 v2'); expect(mocks.diff).toHaveBeenCalledTimes(2);
});
