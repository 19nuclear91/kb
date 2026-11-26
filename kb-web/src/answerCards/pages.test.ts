import { beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { mountRouter } from '../test/app';
import { deferred, me } from '../test/fixtures';
import type { Session } from '../session';
import { cardApi } from './api';
import { cardCommands } from './commands';
import { CardError } from './protocol';
import { detail, item, management, page, receipt, revision, workspace } from './fixtures';
import type { Detail, Receipt, Search, Workspace } from './types';

const context = reactive({ session: {} as Session, state: { phase: 'ready', epoch: 1, me }, codes: ['answer:card:write', 'answer:card:review'], facts: '1', accessStatus: 'ready' });
const check = vi.hoisted(() => vi.fn());
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../admin/access', async original => ({ ...await original<typeof import('../admin/access')>(), useAdminAccess: () => ({
  has: (code: string) => context.codes.includes(code), canManageSpace: (id: string) => id === '9', spaces: [{ spaceId: '9' }],
  get status() { return context.accessStatus; }, spaceStatus: 'ready',
  get factsKey() { return context.facts; }, get permissionFactsKey() { return context.codes.join(','); }, check,
}) }));
import SearchPanel from './SearchPanel.vue';
import DetailPage from '../pages/AnswerCardDetailPage.vue';
import WorkspacePage from '../pages/AnswerCardWorkspacePage.vue';
import ManagementPage from '../pages/AnswerCardManagementPage.vue';
import WorkspacesPage from '../pages/AnswerCardWorkspacesPage.vue';
import ContentEditor from './ContentEditor.vue';
const path = `/answer-cards/${item.cardId}/workspace`;
const showWorkspace = () => mountRouter(() => h(WorkspacePage), { path });
beforeEach(() => {
  context.state = { phase: 'ready', epoch: 1, me }; context.codes = ['answer:card:write', 'answer:card:review']; context.facts = '1'; context.accessStatus = 'ready';
  context.session = { getSnapshot: () => context.state, subscribe: () => () => {}, json: vi.fn().mockResolvedValue([{ spaceId: '9', name: '业务空间', viewable: true, manageable: true, archived: false }]) } as unknown as Session;
  check.mockReset();
  vi.spyOn(cardApi, 'workspace').mockResolvedValue(structuredClone(workspace));
  vi.spyOn(cardApi, 'detail').mockResolvedValue(structuredClone(detail));
  vi.spyOn(cardApi, 'history').mockResolvedValue(page([]));
  vi.spyOn(cardApi, 'management').mockResolvedValue(page([structuredClone(management)]));
  vi.spyOn(cardApi, 'steward').mockResolvedValue(structuredClone(management));
  vi.spyOn(cardApi, 'candidates').mockResolvedValue(page([{ userId: '8', displayName: '新责任人' }]));
  vi.spyOn(cardApi, 'sources').mockResolvedValue(page([{ documentId: '31', versionId: '33', versionNo: '3', title: '新版依据', applicabilityVersion: '2', dates: revision.dates }]));
});
it('原问题仅主动预填，查询独立提交且保持已提交条件', async () => {
  vi.spyOn(cardApi, 'search').mockImplementation(async (_, input) => ({ items: [item], asOfDate: '2026-11-26', evaluatedAt: '2026-11-26T10:00:00+08:00', appliedQuery: input.query, appliedSpaceId: input.spaceId }));
  mountRouter(() => h(SearchPanel, { originalQuery: '原文档问题' }), { path: '/search' });
  await fireEvent.click(screen.getByRole('button', { name: '用原问题查标准答案' }));
  expect(cardApi.search).not.toHaveBeenCalled();
  expect((screen.getByLabelText('标准答案问题') as HTMLInputElement).value).toBe('原文档问题');
  await fireEvent.click(screen.getByRole('button', { name: '查询标准答案' })); await screen.findByText('标准问题');
  expect(vi.mocked(cardApi.search).mock.calls[0]?.[1]).toMatchObject({ query: '原文档问题', spaceId: null });
  await fireEvent.update(screen.getByLabelText('标准答案问题'), '尚未提交的新问题');
  expect(screen.getByText('原文档问题')).toBeTruthy(); expect(cardApi.search).toHaveBeenCalledTimes(1);
});
it('固定详情安全渲染、复审逾期仍可读，404清除旧正文和全部依据', async () => {
  mountRouter(() => h(DetailPage), { path: item.detailPath });
  await screen.findByText(revision.answer); expect(document.querySelector('script')).toBeNull();
  expect(screen.getByText(/周期复审逾期/)).toBeTruthy();
  vi.mocked(cardApi.detail).mockRejectedValueOnce(new CardError(404));
  await fireEvent.click(screen.getByRole('button', { name: '重新核对所选修订' })); await screen.findByText(/所选标准答案不存在/);
  expect(screen.queryByText(revision.answer)).toBeNull(); expect(screen.queryByText('固定依据')).toBeNull();
  expect(vi.mocked(cardApi.detail).mock.calls.every(call => call[2] === '21')).toBe(true);
});
it('换号和离开目标后迟到读取不能恢复原正文', async () => {
  const late = deferred<Detail>(); vi.mocked(cardApi.detail).mockReturnValueOnce(late.promise).mockRejectedValue(new CardError(404));
  const view = mountRouter(() => h(DetailPage), { path: item.detailPath });
  await waitFor(() => expect(cardApi.detail).toHaveBeenCalledTimes(1));
  context.state.epoch++; await flushPromises(); late.resolve(detail); await flushPromises();
  expect(screen.queryByText(revision.answer)).toBeNull(); view.unmount();
});
it('审核冻结、精确轮次和非自审能力决定动作，不用保存按钮发布', async () => {
  const draft = { ...revision, branchId: '20', availability: 'DRAFT' as const };
  const frozen: Workspace = { ...workspace, draft, review: { reviewId: '40', revisionId: '21', roundNo: '2', state: 'IN_REVIEW', version: '0', submittedBy: '7', submittedAt: '2026-11-26T10:00:00+08:00', decidedAt: null, reason: null },
    capabilities: { canEdit: false, canSubmit: false, canReview: false, canWithdraw: true, canConfirm: false } };
  vi.mocked(cardApi.workspace).mockResolvedValue(frozen); vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(0));
  showWorkspace(); await screen.findByText('审核轮次 2');
  expect(screen.queryByRole('button', { name: '批准此轮并发布' })).toBeNull(); expect(screen.queryByRole('button', { name: '编辑新草稿' })).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '撤回本人送审' })); await screen.findByText(/结果尚未确认/);
  expect(vi.mocked(cardApi.write).mock.calls[0]?.[1]).toMatchObject({ operation: 'ANSWER_CARD_WITHDRAW', reviewId: '40', payload: { revisionId: '21', branchId: '20', expectedReviewVersion: '0' } });
});
it('未知保存关闭重开仍冻结；NOT_FOUND不清空；明确放弃先重读', async () => {
  vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(0));
  vi.spyOn(cardApi, 'command').mockResolvedValue({ status: 'NOT_FOUND', receipt: null });
  let view = showWorkspace(); await screen.findByText(revision.answer);
  await fireEvent.click(screen.getByRole('button', { name: '编辑新草稿' })); await fireEvent.update(screen.getByLabelText('答案原文'), '保留原意图答案');
  await fireEvent.click(screen.getByRole('button', { name: '保存草稿' })); await screen.findByText(/结果尚未确认/);
  const original = vi.mocked(cardApi.write).mock.calls[0]?.[1]; view.unmount(); view = showWorkspace(); await screen.findByRole('button', { name: '核对原请求' });
  await fireEvent.click(screen.getByRole('button', { name: '核对原请求' })); await screen.findByText(/本次未查到提交记录/);
  expect(vi.mocked(cardApi.command).mock.calls[0]?.[1]).toEqual(original);
  expect((screen.getByRole('button', { name: '编辑新草稿' }) as HTMLButtonElement).disabled).toBe(true);
  const reads = vi.mocked(cardApi.workspace).mock.calls.length;
  await fireEvent.click(screen.getByRole('button', { name: '明确放弃并重读' })); await waitFor(() => expect(cardApi.workspace).toHaveBeenCalledTimes(reads + 1));
  expect(cardCommands(context.session as Session).entries.size).toBe(0); expect(cardApi.write).toHaveBeenCalledTimes(1);
});
it('来源变化只在明确选择后更改绑定，保存内容保留新三元组', async () => {
  const save = vi.fn();
  mountRouter(() => h(ContentEditor, { initial: { ...revision, branchId: '20' }, spaceId: '9', locked: false, onSave: save }), { path });
  await fireEvent.click(screen.getByRole('button', { name: '查询当前依据' })); await screen.findByText('新版依据');
  await fireEvent.click(screen.getByRole('button', { name: '保存草稿' })); expect(save.mock.calls[0]?.[0].sources[0].versionId).toBe('32');
  await fireEvent.click(screen.getByRole('button', { name: '明确选用此版本与日期' })); await fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  expect(save.mock.calls[1]?.[0].sources).toEqual([{ documentId: '31', versionId: '33', applicabilityVersion: '2' }]);
});
it('仅管理资格使用最小投影与候选，改派不加载正文', async () => {
  context.codes = []; vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(0));
  mountRouter(() => h(ManagementPage), { path: '/answer-cards/management' });
  await waitFor(() => expect(screen.getByRole('option', { name: '业务空间' })).toBeTruthy());
  await fireEvent.update(screen.getByLabelText('卡片管理空间'), '9'); await screen.findByRole('button', { name: `管理卡片 ${item.cardId}` });
  await fireEvent.click(screen.getByRole('button', { name: `管理卡片 ${item.cardId}` })); await screen.findByText(/首次指定 2026-01-31/);
  await fireEvent.click(screen.getByRole('button', { name: '查询合格责任人' })); await screen.findByText('新责任人');
  await fireEvent.click(screen.getByRole('button', { name: '选择此责任人' })); await fireEvent.click(screen.getByRole('button', { name: '保存责任人配置' }));
  expect(cardApi.workspace).not.toHaveBeenCalled(); expect(cardApi.detail).not.toHaveBeenCalled(); expect(screen.queryByText(revision.answer)).toBeNull();
  expect(vi.mocked(cardApi.write).mock.calls[0]?.[1]).toMatchObject({ operation: 'ANSWER_CARD_STEWARDSHIP_UPDATE', payload: { responsibleUserId: '8', expectedStewardshipVersion: '1', cycleMonths: 3 } });
});
it('功能撤权清理维护正文和输入，之后不会自动重发', async () => {
  showWorkspace(); await screen.findByText(revision.answer); await fireEvent.click(screen.getByRole('button', { name: '编辑新草稿' }));
  context.codes = []; await flushPromises(); expect(screen.queryByText(revision.answer)).toBeNull(); expect(screen.queryByLabelText('答案原文')).toBeNull();
});

it('驳回必须填写原因，旧轮次冲突后重读且不自动决定新轮次', async () => {
  const frozen: Workspace = { ...workspace, draft: { ...revision, branchId: '20', availability: 'DRAFT' },
    review: { reviewId: '40', revisionId: '21', roundNo: '2', state: 'IN_REVIEW', version: '0', submittedBy: '8', submittedAt: '2026-11-26T10:00:00+08:00', decidedAt: null, reason: null },
    capabilities: { canEdit: false, canSubmit: false, canReview: true, canWithdraw: false, canConfirm: false } };
  vi.mocked(cardApi.workspace).mockResolvedValueOnce(frozen).mockResolvedValue(workspace);
  vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(409));
  showWorkspace(); await screen.findByText('审核轮次 2');
  await fireEvent.click(screen.getByRole('button', { name: '驳回此轮' })); expect(cardApi.write).not.toHaveBeenCalled();
  await fireEvent.update(screen.getByLabelText('驳回原因'), '需重新核对制度');
  await fireEvent.click(screen.getByRole('button', { name: '驳回此轮' })); await waitFor(() => expect(cardApi.workspace).toHaveBeenCalledTimes(2));
  expect(vi.mocked(cardApi.write).mock.calls[0]?.[1]).toMatchObject({ operation: 'ANSWER_CARD_REJECT', reviewId: '40', payload: { reason: '需重新核对制度', expectedReviewVersion: '0' } });
  expect(cardApi.write).toHaveBeenCalledTimes(1); expect(screen.queryByLabelText('驳回原因')).toBeNull();
});

it('来源已变化时不显示周期确认；历史失权后清空正文与来源', async () => {
  vi.mocked(cardApi.workspace).mockResolvedValue({ ...workspace, published: { ...revision, branchId: '20', availability: 'SOURCE_CHANGED' },
    capabilities: { ...workspace.capabilities, canConfirm: false } });
  vi.mocked(cardApi.history).mockResolvedValueOnce(page([{ revision: { ...revision, branchId: '20' }, review: null }], 'cursor'))
    .mockRejectedValueOnce(new CardError(404));
  showWorkspace(); await screen.findByText(/发布版展示：依据已变化/);
  expect(screen.queryByRole('button', { name: '确认当前发布版仍准确' })).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '查看私有历史' })); await screen.findByRole('button', { name: '下一页历史' });
  await fireEvent.click(screen.getByRole('button', { name: '下一页历史' })); await flushPromises();
  expect(screen.queryByRole('button', { name: '下一页历史' })).toBeNull();
  expect(screen.queryByRole('button', { name: '关闭私有历史' })).toBeNull();
});

it('关闭工作区后迟到成功保留可核对回执，重开不自动重发', async () => {
  const late = deferred<Receipt>(); vi.spyOn(cardApi, 'write').mockReturnValueOnce(late.promise);
  const view = showWorkspace(); await screen.findByText(revision.answer);
  await fireEvent.click(screen.getByRole('button', { name: '编辑新草稿' })); await fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  view.unmount(); late.resolve(receipt); await flushPromises();
  expect(cardCommands(context.session as Session).entries.get(`card:${item.cardId}`)?.phase).toBe('committed');
  showWorkspace(); await screen.findByRole('button', { name: '读取已提交事实' });
  await fireEvent.click(screen.getByRole('button', { name: '读取已提交事实' })); await waitFor(() => expect(cardCommands(context.session as Session).entries.size).toBe(0));
  expect(cardApi.write).toHaveBeenCalledTimes(1);
});

it('资格正在重核不清除未知原意图，确认撤权才移除私有载荷', async () => {
  vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(0));
  showWorkspace(); await screen.findByText(revision.answer);
  await fireEvent.click(screen.getByRole('button', { name: '编辑新草稿' })); await fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await screen.findByText(/结果尚未确认/);
  context.accessStatus = 'loading'; context.codes = []; await flushPromises();
  expect(cardCommands(context.session as Session).entries.size).toBe(1);
  context.accessStatus = 'ready'; context.codes = ['answer:card:review']; await flushPromises();
  expect(cardCommands(context.session as Session).entries.size).toBe(0);
});

it('换号时不会把上一用户已提交的问题重新发给新会话', async () => {
  vi.spyOn(cardApi, 'search').mockResolvedValue({ items: [item], asOfDate: '2026-11-26', evaluatedAt: '2026-11-26T10:00:00+08:00', appliedQuery: '用户A私有查询', appliedSpaceId: null });
  mountRouter(() => h(SearchPanel), { path: '/search' });
  await fireEvent.update(screen.getByLabelText('标准答案问题'), '用户A私有查询');
  await fireEvent.click(screen.getByRole('button', { name: '查询标准答案' })); await screen.findByText('标准问题');
  context.state.epoch++; await flushPromises();
  expect(cardApi.search).toHaveBeenCalledTimes(1);
  expect(screen.queryByText('用户A私有查询')).toBeNull();
});

it('旧目标重读完成不能确认新目标的待处理回执', async () => {
  const late = deferred<Workspace>();
  const second = { ...workspace, cardId: '2', published: { ...workspace.published!, cardId: '2' } };
  vi.mocked(cardApi.workspace).mockResolvedValueOnce(workspace).mockReturnValueOnce(late.promise).mockResolvedValue(second);
  const view = showWorkspace(); await screen.findByText(revision.answer);
  const store = cardCommands(context.session as Session);
  store.entries.set('card:2', { intent: { operation: 'ANSWER_CARD_SAVE', cardId: '2', reviewId: null, payload: { spaceId: '9' } }, requestId: 'second', phase: 'committed', receipt: { ...receipt, cardId: '2' }, notice: '' });
  await fireEvent.click(screen.getByRole('button', { name: '重读当前事实' }));
  await view.router.push('/answer-cards/2/workspace'); await flushPromises();
  late.resolve(workspace); await flushPromises();
  expect(store.entries.get('card:2')?.phase).toBe('committed');
});

it('旧目标的拒绝后重读不能撤销新目标的未知意图', async () => {
  const late = deferred<Workspace>(), second = { ...workspace, cardId: '2', published: { ...workspace.published!, cardId: '2' } };
  vi.mocked(cardApi.workspace).mockResolvedValueOnce(workspace).mockReturnValueOnce(late.promise).mockResolvedValue(second);
  vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(403));
  const view = showWorkspace(); await screen.findByText(revision.answer);
  await fireEvent.click(screen.getByRole('button', { name: '编辑新草稿' })); await fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await waitFor(() => expect(cardApi.workspace).toHaveBeenCalledTimes(2));
  const store = cardCommands(context.session as Session);
  store.entries.set('card:2', { intent: { operation: 'ANSWER_CARD_SAVE', cardId: '2', reviewId: null, payload: { spaceId: '9' } }, requestId: 'second', phase: 'unknown', receipt: null, notice: '' });
  await view.router.push('/answer-cards/2/workspace'); await flushPromises(); late.resolve(workspace); await flushPromises();
  expect(store.entries.get('card:2')?.phase).toBe('unknown');
});

it('未知创建明确放弃后重读失败，成功核对前不能开启新意图', async () => {
  vi.spyOn(cardApi, 'workspaces').mockResolvedValueOnce(page([])).mockRejectedValueOnce(new CardError(503)).mockResolvedValue(page([]));
  vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(0));
  mountRouter(() => h(WorkspacesPage), { path: '/answer-cards/workspace' });
  await screen.findByText('当前没有此视图中的卡片。');
  const pickers = screen.getAllByLabelText('标准答案所属空间');
  await waitFor(() => expect(within(pickers[1]!).getByRole('option', { name: '业务空间' })).toBeTruthy());
  await fireEvent.update(pickers[1]!, '9');
  await fireEvent.click(screen.getByRole('button', { name: '新建标准答案' }));
  await fireEvent.update(screen.getByLabelText('标准问题'), '新卡片');
  await fireEvent.click(screen.getByRole('button', { name: '保存草稿' })); await screen.findByText(/结果尚未确认/);
  await fireEvent.click(screen.getByRole('button', { name: '明确放弃并重读' })); await flushPromises();
  expect((screen.getByRole('button', { name: '新建标准答案' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '重读工作清单' })); await flushPromises();
  expect((screen.getByRole('button', { name: '新建标准答案' }) as HTMLButtonElement).disabled).toBe(false);
  expect(cardApi.write).toHaveBeenCalledTimes(1);
});

it('资格重核导致离页后，确认撤权仍清理会话内未知载荷', async () => {
  vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(0));
  const view = showWorkspace(); await screen.findByText(revision.answer);
  await fireEvent.click(screen.getByRole('button', { name: '编辑新草稿' })); await fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
  await screen.findByText(/结果尚未确认/);
  context.accessStatus = 'loading'; context.codes = []; await flushPromises(); view.unmount();
  context.accessStatus = 'ready'; await flushPromises();
  expect(cardCommands(context.session as Session).entries.size).toBe(0);
});
