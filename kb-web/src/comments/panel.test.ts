import { beforeEach, expect, it, vi } from 'vitest';
import { nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { renderHost } from '../test/render';
import { ApiError } from '../api';
import { deferred, json } from '../test/fixtures';
import type { ReviewStatus } from '../reviews/api';
import type { WriteBindings } from '../admin/hooks';
import CommentPanel from './CommentPanel.vue';
import CommentTargetPanel from './CommentTargetPanel.vue';
import VersionComments from './VersionComments.vue';

const h = reactive({ json: vi.fn(), send: vi.fn(), refresh: vi.fn(), state: { phase: 'ready', epoch: 1 } });
const session = { json: h.json, send: h.send, refresh: h.refresh, getSnapshot: () => h.state };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return h.state; } }) }));
const id = (n: number) => `cccccccc-0000-0000-0000-${String(n).padStart(12, '0')}`;
const comment = (n: number, over: Record<string, unknown> = {}) => ({ commentId: id(n), parentId: null, documentId: '10', versionId: '21',
  createdAt: '2026-10-26T00:00:00Z', deleted: false, author: { userId: '7', label: `成员${n}` }, body: `正文${n}`, isOwn: false, canDelete: false, ...over });
const own = (n: number, over: Record<string, unknown> = {}) => comment(n, { isOwn: true, canDelete: true, ...over });
const listing = (items: unknown[], nextCursor: string | null = null, canCreate = true) => ({ items, hasMore: nextCursor !== null, nextCursor, canCreate });
const status = (versionId: string, evidence: 'NONE' | 'PUBLISHED' | 'OBSERVED_CURRENT', canRead = true): ReviewStatus => ({
  state: { documentId: '10', versionId, versionNo: 1, stateVersion: '1', indexStatus: 'indexed', policy: 'AUTO', publicationEvidence: evidence, reviewState: 'DRAFT' },
  capabilities: { canRead, canDownload: false, canSubmit: false, canApprove: false, canReject: false, canWithdraw: false, canRefresh: false, canReadOpinions: false },
  rounds: [], hasMore: false, nextCursor: null });
const reads = () => h.json.mock.calls.map(([path]) => String(path));
const writes = () => h.send.mock.calls;
const receipt = (over: Record<string, unknown> = {}, code = 201) => json({ commentId: id(90), parentId: null, documentId: '10', versionId: '21',
  createdAt: '2026-10-26T00:00:00Z', deleted: false, replayed: code === 200, ...over }, true, code);
beforeEach(() => {
  h.state = { phase: 'ready', epoch: h.state.epoch + 1 };
  h.json.mockReset().mockResolvedValue(listing([]));
  h.send.mockReset(); h.refresh.mockReset().mockResolvedValue(undefined);
});
const panel = (versionId = '21') => renderHost(CommentPanel, { props: { documentId: '10', versionId } });
const versionPanel = (value: ReviewStatus | undefined) => renderHost(VersionComments, { props: { documentId: '10', versionId: '21', status: value } });
// ElDialog 首次打开后在下一轮更新挂载内容；不等待动画，也不延长断言超时。
async function openDelete(root: HTMLElement) {
  await fireEvent.click(within(root).getByRole('button', { name: '删除评论' }));
  await nextTick();
}

it('仅在当前状态确认发布证据且可读时请求评论；待审与无证据旧版只显示说明', async () => {
  const view = versionPanel(status('21', 'NONE'));
  expect(screen.getByText(/尚无发布证据，不开放评论/)).toBeTruthy();
  await view.rerender({ status: status('21', 'PUBLISHED', false) });
  await view.rerender({ status: status('22', 'PUBLISHED') });
  await view.rerender({ status: undefined });
  expect(h.json).not.toHaveBeenCalled();
  await view.rerender({ status: status('21', 'OBSERVED_CURRENT') });
  expect(await screen.findByText('该版本暂无评论。')).toBeTruthy();
  expect(reads()).toEqual(['/api/documents/10/versions/21/comments?limit=20']);
  expect(screen.getByText(/固定版本 v1/)).toBeTruthy(); expect(screen.queryByText(/固定版本 21/)).toBeNull();
});

it('版本状态重读期间面板卸载、不显示旧讨论；重新确认后仍显示待核对并沿用原标识', async () => {
  h.json.mockResolvedValue(listing([comment(1)]));
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(receipt({}, 200));
  const view = versionPanel(status('21', 'PUBLISHED'));
  await fireEvent.update(await screen.findByLabelText('评论内容'), '状态重读前');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  await view.rerender({ status: undefined });
  expect(screen.queryByText('正文1')).toBeNull(); expect(screen.queryByText(/提交结果尚未确认/)).toBeNull();
  await view.rerender({ status: status('21', 'PUBLISHED') });
  await fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
  expect(await screen.findByText('已核对为同一次提交，未重复发表。')).toBeTruthy();
  const [first, retry] = writes();
  expect(retry[1].headers['X-Request-Id']).toBe(first[1].headers['X-Request-Id']); expect(retry[1].body).toBe('{"body":"状态重读前"}');
});

it('版本状态确认不可评论即丢弃未决绑定，之后同内容使用新标识', async () => {
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(receipt({}));
  const view = versionPanel(status('21', 'PUBLISHED'));
  await fireEvent.update(await screen.findByLabelText('评论内容'), '同一内容');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  await view.rerender({ status: status('21', 'NONE') });
  expect(screen.getByText(/尚无发布证据/)).toBeTruthy();
  await view.rerender({ status: status('21', 'PUBLISHED') });
  await fireEvent.update(await screen.findByLabelText('评论内容'), '同一内容');
  expect(screen.queryByText(/提交结果尚未确认/)).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  expect(await screen.findByText('已发表。')).toBeTruthy();
  expect(writes()[1][1].headers['X-Request-Id']).not.toBe(writes()[0][1].headers['X-Request-Id']);
});

it('V1 切换到 V2：旧请求中止，迟到结果与草稿不进入 V2，V2 只读自己的讨论', async () => {
  const v1 = deferred<unknown>();
  h.json.mockImplementation(async (path: string) => path.includes('/versions/21/') ? v1.promise : listing([comment(2, { versionId: '22' })]));
  const view = panel('21');
  const signal = h.json.mock.calls[0][1].signal as AbortSignal;
  await view.rerender({ versionId: '22' });
  expect(signal.aborted).toBe(true);
  expect(await screen.findByText('正文2')).toBeTruthy();
  v1.resolve(listing([comment(1)])); await flushPromises();
  expect(screen.queryByText('正文1')).toBeNull();
  expect(screen.getByText(/固定版本 22/)).toBeTruthy();
  await fireEvent.update(screen.getByLabelText('评论内容'), 'V2 草稿');
  await view.rerender({ versionId: '21' });
  expect(screen.queryByDisplayValue('V2 草稿')).toBeNull();
});

it('根列表不预取回复；热门回复按独立游标有界续页', async () => {
  h.json.mockImplementation(async (path: string) => {
    if (path.includes('/replies')) {
      return path.includes('cursor=r1') ? listing([comment(5, { parentId: id(1) })]) : listing([comment(3, { parentId: id(1) }), comment(4, { parentId: id(1) })], 'r1');
    }
    return listing([comment(1), comment(2)]);
  });
  panel(); await screen.findByText('正文1');
  expect(reads().some(path => path.includes('/replies'))).toBe(false);
  await fireEvent.click(within(screen.getByLabelText(`根评论 ${id(1)}`)).getByRole('button', { name: '查看回复' }));
  expect(await screen.findByText('正文4')).toBeTruthy();
  expect(reads().filter(path => path.includes('/replies'))).toEqual([`/api/documents/10/versions/21/comments/${id(1)}/replies?limit=20`]);
  await fireEvent.click(screen.getByRole('button', { name: '更多回复' }));
  expect(await screen.findByText('正文5')).toBeTruthy();
  expect(screen.getByText('已显示全部可见回复。')).toBeTruthy();
  expect(reads().filter(path => path.includes(id(2)))).toHaveLength(0);
});

it('首页故障不冒充空讨论或启用发表；续页故障保留已显示项并按同一游标重试', async () => {
  let attempt = 0;
  h.json.mockImplementation(async (path: string) => path.includes('cursor=c1')
    ? (++attempt === 1 ? Promise.reject(new ApiError(503)) : listing([comment(2)])) : Promise.reject(new ApiError(503)));
  const view = panel();
  expect(await screen.findByText(/评论读取失败/)).toBeTruthy();
  expect(screen.queryByText('该版本暂无评论。')).toBeNull(); expect(screen.queryByLabelText('评论内容')).toBeNull();
  view.unmount();
  h.json.mockImplementation(async (path: string) => path.includes('cursor=c1')
    ? (++attempt === 1 ? Promise.reject(new ApiError(503)) : listing([comment(2)])) : listing([comment(1)], 'c1'));
  panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '续页失败时的草稿');
  await fireEvent.click(screen.getByRole('button', { name: '更多评论' }));
  expect(await screen.findByText(/评论读取失败/)).toBeTruthy();
  expect(screen.getByText('正文1')).toBeTruthy(); expect(screen.queryByText('已显示全部可见根评论。')).toBeNull();
  expect(screen.getByText('读取未完成，请在列表末尾重新读取后再发表。')).toBeTruthy();
  expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '重新读取评论' }));
  expect(await screen.findByText('正文2')).toBeTruthy(); expect(screen.getByText('正文1')).toBeTruthy();
  expect(reads().filter(path => path.includes('cursor=c1'))).toHaveLength(2);
  expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(false);
  expect(screen.getByDisplayValue('续页失败时的草稿')).toBeTruthy();
});

it('续页失败在续页位置显示故障与重试、不再提供续页按钮；同一游标重试在途期间发表仍暂停，成功后才恢复', async () => {
  const page = deferred<unknown>(); let attempt = 0;
  h.json.mockImplementation(async (path: string) => !path.includes('cursor=c1') ? listing([comment(1)], 'c1') : ++attempt === 1 ? Promise.reject(new ApiError(503)) : page.promise);
  panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '等待续页');
  await fireEvent.click(screen.getByRole('button', { name: '更多评论' }));
  expect(await screen.findByText(/评论读取失败/)).toBeTruthy(); expect(screen.queryByText('更多评论')).toBeNull();
  const retry = screen.getByRole('button', { name: '重新读取评论' });
  expect(screen.getAllByRole('button', { name: '重新读取评论' })).toHaveLength(1);
  expect(screen.getByLabelText(`根评论 ${id(1)}`).compareDocumentPosition(retry) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  await fireEvent.click(retry);
  await waitFor(() => expect(reads().filter(path => path.includes('cursor=c1'))).toHaveLength(2));
  expect(screen.queryByText(/评论读取失败/)).toBeNull();
  expect(screen.getByText('正在重新读取，完成后才能发表。')).toBeTruthy();
  expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(true);
  page.resolve(listing([comment(2)])); await flushPromises();
  expect(await screen.findByText('正文2')).toBeTruthy();
  expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(false);
});

it('同一续页游标第二次失败改为从首页重读，避免确定性故障一直暂停发表', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('cursor=c1') ? Promise.reject(new ApiError(503)) : listing([comment(1)], 'c1'));
  panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '等待恢复');
  await fireEvent.click(screen.getByRole('button', { name: '更多评论' }));
  await fireEvent.click(await screen.findByRole('button', { name: '重新读取评论' }));
  await waitFor(() => expect(reads().filter(path => path.includes('cursor=c1'))).toHaveLength(2));
  await fireEvent.click(await screen.findByRole('button', { name: '重新读取评论' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(false));
  expect(reads().filter(path => path.includes('cursor=c1'))).toHaveLength(2); expect(reads().filter(path => !path.includes('cursor='))).toHaveLength(2);
  expect(screen.queryByText(/评论读取失败/)).toBeNull(); expect(screen.getByText('正文1')).toBeTruthy();
});

it('续页游标被拒绝（400）时直接从首页重读，不重复提交同一游标', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('cursor=c1') ? Promise.reject(new ApiError(400)) : listing([comment(1)], 'c1'));
  panel(); await fireEvent.click(await screen.findByRole('button', { name: '更多评论' }));
  expect(await screen.findByText('评论请求不符合要求，请刷新后重试。')).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: '重新读取评论' }));
  await waitFor(() => expect(reads().filter(path => !path.includes('cursor='))).toHaveLength(2));
  await waitFor(() => expect(screen.queryByText('评论请求不符合要求，请刷新后重试。')).toBeNull());
  expect(reads().filter(path => path.includes('cursor=c1'))).toHaveLength(1); expect(screen.getByText('正文1')).toBeTruthy();
});

it('只读用户可读不可发表；正文与标记按纯文本显示，不执行脚本或加载外链', async () => {
  const markup = '<img src=x onerror=alert(1)> ![p](https://example.invalid/a.png) [链接](https://example.invalid)';
  h.json.mockResolvedValue(listing([comment(1, { body: markup })], null, false)); panel();
  expect(await screen.findByText(markup)).toBeTruthy();
  expect(document.querySelector('img')).toBeNull(); expect(document.querySelector('a')).toBeNull();
  expect(screen.getByText(/没有发表评论的资格/)).toBeTruthy(); expect(screen.queryByLabelText('评论内容')).toBeNull();
  expect(screen.queryByRole('button', { name: '编辑' })).toBeNull();
});

it('按 code point 校验正文：补充字符计 1，超限提示但不截断，NUL 不能提交', async () => {
  panel(); const input = await screen.findByLabelText('评论内容') as HTMLTextAreaElement;
  const button = () => screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement;
  expect(button().disabled).toBe(true);
  await fireEvent.update(input, ` ${'😀'.repeat(4000)} `);
  expect(screen.getByText('4000 / 4000')).toBeTruthy(); expect(button().disabled).toBe(false);
  await fireEvent.update(input, '😀'.repeat(4000) + 'a');
  expect(screen.getByText('4001 / 4000')).toBeTruthy(); expect(screen.getByRole('alert').textContent).toContain('不会截断');
  expect(input.value).toHaveLength(8001); expect(button().disabled).toBe(true);
  await fireEvent.update(input, 'a\u0000b'); expect(button().disabled).toBe(true); expect(h.send).not.toHaveBeenCalled();
});

it('结果不确定只按原内容和原标识显式重试；确认后改动正文使用新标识，成功后重读而不乐观追加', async () => {
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(receipt({}, 200)).mockResolvedValueOnce(receipt({ commentId: id(91) }));
  panel(); await fireEvent.update(await screen.findByLabelText('评论内容'), '  第一次  ');
  void fireEvent.click(screen.getByRole('button', { name: '发表评论' })); void fireEvent.click(screen.getByRole('button', { name: /发表评论/ }));
  expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy(); expect(writes()).toHaveLength(1);
  expect((screen.getByRole('button', { name: /发表评论/ }) as HTMLButtonElement).disabled).toBe(true);
  const reads0 = reads().length;
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' }));
  expect(await screen.findByText('已核对为同一次提交，未重复发表。')).toBeTruthy();
  const [first, retry] = writes();
  expect(retry[1].headers['X-Request-Id']).toBe(first[1].headers['X-Request-Id']);
  expect(retry[1].body).toBe(first[1].body); expect(first[1].body).toBe('{"body":"第一次"}');
  await waitFor(() => expect(reads().length).toBeGreaterThan(reads0)); expect(screen.queryByText('第一次')).toBeNull();
  await fireEvent.update(screen.getByLabelText('评论内容'), '第二次');
  await waitFor(() => expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(false));
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' })); expect(await screen.findByText('已发表。')).toBeTruthy();
  expect(writes()[2][1].headers['X-Request-Id']).not.toBe(first[1].headers['X-Request-Id']);
});

it('单层回复：只能回复根评论，回复项没有回复、编辑或附件入口', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? listing([comment(2, { parentId: id(1) })]) : listing([comment(1)]));
  h.send.mockResolvedValue(receipt({ parentId: id(1) })); panel();
  await fireEvent.click(await screen.findByRole('button', { name: '查看回复' }));
  const reply = await screen.findByLabelText(`回复 ${id(2)}`); expect(within(reply).queryByRole('button')).toBeNull();
  expect(screen.getAllByRole('button', { name: /回复/ }).map(button => button.textContent)).toEqual(['收起回复', '提交回复']);
  expect(screen.queryByText(/编辑|附件|提及/)).toBeNull();
  await fireEvent.update(screen.getByLabelText('回复内容'), '回复正文');
  await fireEvent.click(screen.getByRole('button', { name: '提交回复' })); await screen.findByText('已发表。');
  expect(writes()[0][1].body).toBe(`{"parentId":"${id(1)}","body":"回复正文"}`);
});

it('本人删除需确认：成功立即清除正文与回复草稿，他人回复保留，删除根后不能追加回复；他人评论没有删除入口', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? listing([comment(2, { parentId: id(1) })]) : listing([own(1), comment(3)]));
  h.send.mockResolvedValue(new Response(null, { status: 204 })); panel(); await screen.findByText('正文1');
  expect(within(screen.getByLabelText(`根评论 ${id(3)}`)).queryByRole('button', { name: '删除评论' })).toBeNull();
  const root = screen.getByLabelText(`根评论 ${id(1)}`);
  await fireEvent.click(within(root).getByRole('button', { name: '查看回复' }));
  await fireEvent.update(await screen.findByLabelText('回复内容'), '未提交的回复草稿');
  await openDelete(root); expect(screen.getByText(/删除后正文立即清除，无法恢复/)).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  await waitFor(() => expect(screen.queryByText('正文1')).toBeNull()); expect(screen.getAllByText(/该评论已删除/)).toHaveLength(1);
  expect(screen.queryByDisplayValue('未提交的回复草稿')).toBeNull(); expect(await screen.findByText('正文2')).toBeTruthy();
  expect(await screen.findByText(/根评论已删除，已有回复保留/)).toBeTruthy(); expect(screen.queryByLabelText('回复内容')).toBeNull();
  expect(writes()).toHaveLength(1); expect(writes()[0][0]).toBe(`/api/documents/10/versions/21/comments/${id(1)}`);
  expect(writes()[0][1]).toMatchObject({ method: 'DELETE', body: undefined });
});

it('发表结果待核对、核对后删除该评论：同一内容的未决绑定一并丢弃，不再提供按原内容重试', async () => {
  h.json.mockResolvedValueOnce(listing([])).mockResolvedValue(listing([own(1, { body: '待核对正文' })]));
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(new Response(null, { status: 204 })); panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '待核对正文');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  await fireEvent.click(await screen.findByRole('button', { name: '重新读取核对' }));
  const root = await screen.findByLabelText(`根评论 ${id(1)}`); expect(screen.getByText(/提交结果尚未确认/)).toBeTruthy();
  await openDelete(root); await fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  await waitFor(() => expect(screen.queryByText(/提交结果尚未确认/)).toBeNull());
  expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull();
  expect(within(screen.getByLabelText(`根评论 ${id(1)}`)).queryByText('待核对正文')).toBeNull();
  expect(writes().map(([, init]) => init.method)).toEqual(['POST', 'DELETE']);
});

it('发表在途时删除本人同正文评论：在途绑定保留，结果不确定照常待核对并按原标识重试', async () => {
  h.json.mockResolvedValue(listing([own(1, { body: '同一段正文' })])); const post = deferred<Response>();
  h.send.mockReturnValueOnce(post.promise).mockResolvedValueOnce(new Response(null, { status: 204 })).mockResolvedValueOnce(receipt({}, 200)); panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '同一段正文');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' })); const root = screen.getByLabelText(`根评论 ${id(1)}`);
  await openDelete(root); await fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  expect(await within(root).findByText(/该评论已删除/)).toBeTruthy(); post.reject(new ApiError(0)); await flushPromises();
  expect(screen.getByText(/提交结果尚未确认/)).toBeTruthy();
  expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); expect(await screen.findByText('已核对为同一次提交，未重复发表。')).toBeTruthy();
  expect(writes().map(([, init]) => init.method)).toEqual(['POST', 'DELETE', 'POST']);
  expect(writes()[2][1].headers['X-Request-Id']).toBe(writes()[0][1].headers['X-Request-Id']);
});

it('删除确认打开时发表仍在途、删除完成前变为不确定：绑定保留，删除后仍待核对并按原标识重试', async () => {
  h.json.mockResolvedValue(listing([own(1, { body: '同一段正文' })])); const post = deferred<Response>(), remove = deferred<Response>();
  h.send.mockReturnValueOnce(post.promise).mockReturnValueOnce(remove.promise).mockResolvedValueOnce(receipt({}, 200)); panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '同一段正文');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' })); const root = screen.getByLabelText(`根评论 ${id(1)}`);
  await openDelete(root); await fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  post.reject(new ApiError(0)); await flushPromises(); expect(screen.getByText(/提交结果尚未确认/)).toBeTruthy();
  remove.resolve(new Response(null, { status: 204 })); await flushPromises(); expect(await within(root).findByText(/该评论已删除/)).toBeTruthy();
  expect(screen.getByText(/提交结果尚未确认/)).toBeTruthy();
  expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); expect(await screen.findByText('已核对为同一次提交，未重复发表。')).toBeTruthy();
  expect(writes().map(([, init]) => init.method)).toEqual(['POST', 'DELETE', 'POST']);
  expect(writes()[2][1].headers['X-Request-Id']).toBe(writes()[0][1].headers['X-Request-Id']);
});

it('删除确认期间快照中的绑定被重新发出、结果仍不确定：删除完成时不丢弃在途绑定，照常待核对', async () => {
  h.json.mockResolvedValue(listing([own(1, { body: '同一段正文' })])); const remove = deferred<Response>(), again = deferred<Response>();
  h.send.mockRejectedValueOnce(new ApiError(0)).mockReturnValueOnce(remove.promise).mockReturnValueOnce(again.promise); panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '同一段正文');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' })); expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  const root = screen.getByLabelText(`根评论 ${id(1)}`); await openDelete(root);
  await fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  // jsdom 不以遮罩拦截事件，保留原测试对确认期间绑定重发的覆盖。
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' }));
  remove.resolve(new Response(null, { status: 204 })); await flushPromises(); expect(await within(root).findByText(/该评论已删除/)).toBeTruthy();
  again.reject(new ApiError(0)); await flushPromises(); expect(screen.getByText(/提交结果尚未确认/)).toBeTruthy();
  expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(true);
  expect(writes().map(([, init]) => init.method)).toEqual(['POST', 'DELETE', 'POST']);
  expect(writes()[2][1].headers['X-Request-Id']).toBe(writes()[0][1].headers['X-Request-Id']);
});

it('本人删除根评论时丢弃其下回复的未决绑定', async () => {
  const bindings: WriteBindings = new Map();
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? listing([]) : listing([own(1)]));
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(new Response(null, { status: 204 }));
  renderHost(CommentPanel, { props: { documentId: '10', versionId: '21', bindings } });
  const root = await screen.findByLabelText(`根评论 ${id(1)}`);
  await fireEvent.click(within(root).getByRole('button', { name: '查看回复' }));
  await fireEvent.update(await screen.findByLabelText('回复内容'), '根下回复');
  await fireEvent.click(screen.getByRole('button', { name: '提交回复' })); expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  expect([...bindings.keys()].some(key => key.endsWith(`|comment:10/21/${id(1)}`))).toBe(true);
  await openDelete(root); await fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  expect(await screen.findByText(/根评论已删除，已有回复保留/)).toBeTruthy();
  expect([...bindings.keys()].some(key => key.includes('|comment:'))).toBe(false);
});

it('删除结果不确定后关闭确认框再打开：仍待核对，确认按钮不可用，重试沿用原标识', async () => {
  h.json.mockResolvedValue(listing([own(1)]));
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(new Response(null, { status: 204 })); panel();
  const root = await screen.findByLabelText(`根评论 ${id(1)}`); await openDelete(root);
  await fireEvent.click(screen.getByRole('button', { name: '确认删除' })); expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Close this dialog' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: /确认删除/ })).toBeNull());
  await openDelete(root); expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  expect((screen.getByRole('button', { name: /确认删除/ }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); await waitFor(() => expect(screen.queryByText('正文1')).toBeNull());
  expect(writes()).toHaveLength(2); expect(writes()[1][1].headers['X-Request-Id']).toBe(writes()[0][1].headers['X-Request-Id']);
});

it('删除结果不确定时可重试同一目标；已不存在提示且不再提交', async () => {
  h.json.mockResolvedValue(listing([own(1), own(2)]));
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(json({ code: 500, message: 'x', data: { errorCode: 'NOT_FOUND' } }, false, 404)); panel();
  await openDelete(await screen.findByLabelText(`根评论 ${id(1)}`));
  await fireEvent.click(screen.getByRole('button', { name: '确认删除' })); await fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
  await waitFor(() => expect(screen.queryByText('正文1')).toBeNull());
  expect(writes().map(([path]) => path)).toEqual([`/api/documents/10/versions/21/comments/${id(1)}`, `/api/documents/10/versions/21/comments/${id(1)}`]);
  await openDelete(screen.getByLabelText(`根评论 ${id(2)}`)); await fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  expect(await screen.findByText('该评论已不存在或不属于当前账号。')).toBeTruthy();
  expect((screen.getByRole('button', { name: /确认删除/ }) as HTMLButtonElement).disabled).toBe(true); expect(screen.getByText('正文2')).toBeTruthy();
});

it('发表前撤销 comment：拒绝后停止提交并重新核对，重读返回前不以旧按钮放行', async () => {
  const recheck = deferred<unknown>();
  h.json.mockResolvedValueOnce(listing([comment(1)])).mockReturnValueOnce(recheck.promise);
  h.send.mockResolvedValue(json({ code: 500, message: 'x', data: { errorCode: 'FORBIDDEN' } }, false, 403)); panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '想发表'); const submit = screen.getByRole('button', { name: '发表评论' });
  // 保留 MutationObserver：从拒绝到重读开始之间不能短暂开放提交。
  const enabled: MutationRecord[] = [], collect = (records: MutationRecord[]) => enabled.push(...records.filter(record => record.oldValue !== null));
  const watch = new MutationObserver(collect); watch.observe(submit, { attributes: true, attributeFilter: ['disabled'], attributeOldValue: true });
  await fireEvent.click(submit); expect(await screen.findByText('正在重新核对发表资格…')).toBeTruthy();
  collect(watch.takeRecords()); watch.disconnect(); expect(enabled).toHaveLength(0);
  const button = screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement; expect(button.disabled).toBe(true);
  await fireEvent.click(button); expect(screen.getByText('正文1')).toBeTruthy(); expect(writes()).toHaveLength(1);
  recheck.resolve(listing([comment(1)], null, false)); await flushPromises(); expect(await screen.findByText(/没有发表评论的资格/)).toBeTruthy();
  expect(screen.queryByLabelText('评论内容')).toBeNull(); expect(writes()).toHaveLength(1);
});

it('回复结果不确定后收起再展开：仍显示待核对，按原内容重试沿用原标识且不自动重发', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? listing([]) : listing([comment(1)]));
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(receipt({ parentId: id(1) }, 200)); panel();
  const root = await screen.findByLabelText(`根评论 ${id(1)}`); await fireEvent.click(within(root).getByRole('button', { name: '查看回复' }));
  await fireEvent.update(await screen.findByLabelText('回复内容'), '丢失响应的回复');
  await fireEvent.click(screen.getByRole('button', { name: '提交回复' })); expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  await fireEvent.click(within(root).getByRole('button', { name: '收起回复' })); expect(screen.queryByText(/提交结果尚未确认/)).toBeNull();
  await fireEvent.click(within(root).getByRole('button', { name: '查看回复' })); expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  await fireEvent.update(screen.getByLabelText('回复内容'), '另一段回复');
  expect((screen.getByRole('button', { name: '提交回复' }) as HTMLButtonElement).disabled).toBe(true); expect(writes()).toHaveLength(1);
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); expect(await screen.findByText('已核对为同一次提交，未重复发表。')).toBeTruthy();
  const [first, retry] = writes(); expect(retry[1].headers['X-Request-Id']).toBe(first[1].headers['X-Request-Id']);
  expect(retry[1].body).toBe(`{"parentId":"${id(1)}","body":"丢失响应的回复"}`);
});

it('重新读取核对失败卸载输入框后，恢复读取仍显示待核对并沿用原标识', async () => {
  h.json.mockResolvedValueOnce(listing([])).mockRejectedValueOnce(new ApiError(503)).mockResolvedValue(listing([]));
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(receipt({}, 200)); panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '待核对'); await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  await fireEvent.click(await screen.findByRole('button', { name: '重新读取核对' })); expect(await screen.findByText(/评论读取失败/)).toBeTruthy();
  expect(screen.queryByLabelText('评论内容')).toBeNull(); await fireEvent.click(screen.getByRole('button', { name: '重新读取评论' }));
  expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  await waitFor(() => expect((screen.getByRole('button', { name: '按原内容重试' }) as HTMLButtonElement).disabled).toBe(false));
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); expect(await screen.findByText('已核对为同一次提交，未重复发表。')).toBeTruthy();
  expect(writes()[1][1].headers['X-Request-Id']).toBe(writes()[0][1].headers['X-Request-Id']); expect(writes()[1][1].body).toBe('{"body":"待核对"}');
});

it('任一入口被拒绝即重读根列表与已展开的回复，确认前回复框同样暂停', async () => {
  const roots = deferred<unknown>(), replies = deferred<unknown>(); let rootReads = 0, replyReads = 0;
  h.json.mockImplementation((path: string) => path.includes('/replies')
    ? (++replyReads === 1 ? Promise.resolve(listing([])) : replies.promise) : (++rootReads === 1 ? Promise.resolve(listing([comment(1)])) : roots.promise));
  h.send.mockResolvedValue(json({ code: 500, message: 'x', data: { errorCode: 'FORBIDDEN' } }, false, 403)); panel();
  await fireEvent.click(within(await screen.findByLabelText(`根评论 ${id(1)}`)).getByRole('button', { name: '查看回复' }));
  await fireEvent.update(await screen.findByLabelText('回复内容'), '待发回复'); await fireEvent.update(screen.getByLabelText('评论内容'), '根评论');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  await waitFor(() => expect(screen.getAllByText('正在重新核对发表资格…')).toHaveLength(2));
  const reply = screen.getByRole('button', { name: '提交回复' }) as HTMLButtonElement; expect(reply.disabled).toBe(true); await fireEvent.click(reply);
  expect(writes()).toHaveLength(1); roots.resolve(listing([comment(1)], null, false)); replies.resolve(listing([], null, false)); await flushPromises();
  expect(screen.queryByLabelText('评论内容')).toBeNull(); expect(screen.queryByLabelText('回复内容')).toBeNull(); expect(writes()).toHaveLength(1);
});

it('回复遇到根已删除（409）：根列表与回复一并重读，显示根删除说明且不再提供回复框', async () => {
  let rootReads = 0;
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? listing([comment(2, { parentId: id(1) })])
    : ++rootReads === 1 ? listing([comment(1)]) : listing([comment(1, { deleted: true, author: null, body: null })]));
  h.send.mockResolvedValue(json({ code: 500, message: 'x', data: { errorCode: 'STATE_CONFLICT' } }, false, 409)); panel();
  await fireEvent.click(within(await screen.findByLabelText(`根评论 ${id(1)}`)).getByRole('button', { name: '查看回复' }));
  await fireEvent.update(await screen.findByLabelText('回复内容'), '迟到的回复'); await fireEvent.click(screen.getByRole('button', { name: '提交回复' }));
  expect(await screen.findByText(/根评论已删除，已有回复保留/)).toBeTruthy();
  expect(screen.queryByText('正文1')).toBeNull(); expect(screen.getByText('正文2')).toBeTruthy(); expect(screen.queryByLabelText('回复内容')).toBeNull(); expect(writes()).toHaveLength(1);
});

it('结果待核对期间若列表正在重读，按原内容重试同样暂停', async () => {
  const recheck = deferred<unknown>(); h.json.mockResolvedValueOnce(listing([])).mockReturnValueOnce(recheck.promise);
  h.send.mockRejectedValueOnce(new ApiError(0)); panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '待核对'); await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  await fireEvent.click(await screen.findByRole('button', { name: '重新读取核对' }));
  const retry = await screen.findByRole('button', { name: '按原内容重试' }) as HTMLButtonElement;
  await waitFor(() => expect(retry.disabled).toBe(true)); await fireEvent.click(retry); expect(writes()).toHaveLength(1);
  recheck.resolve(listing([])); await flushPromises();
  await waitFor(() => expect((screen.getByRole('button', { name: '按原内容重试' }) as HTMLButtonElement).disabled).toBe(false));
});

it('撤权立即遮蔽：读取发现资源不可访问时清理正文，只保留已知本人评论的无正文删除且不重读讨论', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? Promise.reject(new ApiError(404)) : listing([own(1), comment(2)]));
  h.send.mockResolvedValue(new Response(null, { status: 204 })); panel(); await screen.findByText('正文1');
  await fireEvent.click(within(screen.getByLabelText(`根评论 ${id(2)}`)).getByRole('button', { name: '查看回复' }));
  expect(await screen.findByText(/评论当前不可访问，已清理/)).toBeTruthy();
  expect(screen.queryByText('正文1')).toBeNull(); expect(screen.queryByText('正文2')).toBeNull(); expect(screen.queryByText('成员1')).toBeNull();
  const count = reads().length; await fireEvent.click(screen.getByRole('button', { name: '删除本人评论' })); await nextTick();
  await fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: '删除本人评论' })).toBeNull());
  expect(writes()).toHaveLength(1); expect(writes()[0][1].method).toBe('DELETE'); expect(reads()).toHaveLength(count);
  expect(screen.getAllByRole('button').map(button => button.textContent)).not.toContain('删除评论');
});

it('通知目标先核对原版本与目标评论；失效时清理且不改用其他版本讨论', async () => {
  h.json.mockResolvedValueOnce({ item: comment(2, { parentId: id(1) }), canReply: true });
  const view = renderHost(CommentTargetPanel, { props: { documentId: '10', versionId: '21', commentId: id(2) } });
  expect(await screen.findByText('正文2')).toBeTruthy(); expect(screen.getByText(/固定版本 21 · 回复/)).toBeTruthy();
  expect(reads()).toEqual([`/api/documents/10/versions/21/comments/${id(2)}`]); h.json.mockReset().mockRejectedValue(new ApiError(404));
  await view.rerender({ commentId: id(3) }); expect(await screen.findByText(/评论目标当前不可访问.*不会改为显示其他版本的讨论/)).toBeTruthy();
  expect(screen.queryByText('正文2')).toBeNull(); expect(reads().every(path => path.includes('/versions/21/'))).toBe(true);
});

it('通知目标展开的讨论被拒绝时，目标正文与作者一并清理，须显式重新核对目标', async () => {
  h.json.mockResolvedValueOnce({ item: comment(2, { parentId: id(1) }), canReply: true }).mockRejectedValueOnce(new ApiError(404))
    .mockResolvedValueOnce({ item: comment(2, { parentId: id(1) }), canReply: false });
  renderHost(CommentTargetPanel, { props: { documentId: '10', versionId: '21', commentId: id(2) } });
  expect(await screen.findByText('正文2')).toBeTruthy(); await fireEvent.click(screen.getByRole('button', { name: '查看本版本讨论' }));
  expect(await screen.findByText(/本版本讨论当前不可访问，已清理评论目标内容/)).toBeTruthy();
  expect(screen.queryByText('正文2')).toBeNull(); expect(screen.queryByText('成员2')).toBeNull(); expect(screen.getByText(/该版本的评论当前不可访问/)).toBeTruthy();
  expect(screen.queryByRole('button', { name: '重新核对' })).toBeNull(); await fireEvent.click(screen.getByRole('button', { name: '重新核对目标' }));
  expect(await screen.findByText('正文2')).toBeTruthy(); expect(screen.queryByText(/该版本的评论当前不可访问/)).toBeNull();
  expect(reads()).toEqual([`/api/documents/10/versions/21/comments/${id(2)}`, '/api/documents/10/versions/21/comments?limit=20', `/api/documents/10/versions/21/comments/${id(2)}`]);
});
