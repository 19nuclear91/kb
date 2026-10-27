import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ApiError } from '../api';
import { deferred, json } from '../test/fixtures';
import type { ReviewStatus } from '../reviews/api';
import { CommentPanel, CommentTargetPanel, VersionComments } from './CommentPanel';

const h = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn(), refresh: vi.fn(), state: { phase: 'ready', epoch: 1 } }));
const session = { json: h.json, send: h.send, refresh: h.refresh, getSnapshot: () => h.state };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, state: h.state }) }));

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
const panel = (versionId = '21') => <CommentPanel documentId="10" versionId={versionId} />;

it('仅在当前状态确认发布证据且可读时请求评论；待审与无证据旧版只显示说明', async () => {
  const view = render(<VersionComments documentId="10" versionId="21" status={status('21', 'NONE')} />);
  expect(screen.getByText(/尚无发布证据，不开放评论/)).toBeTruthy();
  view.rerender(<VersionComments documentId="10" versionId="21" status={status('21', 'PUBLISHED', false)} />);
  view.rerender(<VersionComments documentId="10" versionId="21" status={status('22', 'PUBLISHED')} />);
  view.rerender(<VersionComments documentId="10" versionId="21" status={undefined} />);
  expect(h.json).not.toHaveBeenCalled();
  view.rerender(<VersionComments documentId="10" versionId="21" status={status('21', 'OBSERVED_CURRENT')} />);
  expect(await screen.findByText('该版本暂无评论。')).toBeTruthy();
  expect(reads()).toEqual(['/api/documents/10/versions/21/comments?limit=20']);
});

it('V1 切换到 V2：旧请求中止，迟到结果与草稿不进入 V2，V2 只读自己的讨论', async () => {
  const v1 = deferred<unknown>();
  h.json.mockImplementation(async (path: string) => path.includes('/versions/21/') ? v1.promise : listing([comment(2, { versionId: '22' })]));
  const view = render(panel('21'));
  const signal = h.json.mock.calls[0][1].signal as AbortSignal;
  view.rerender(panel('22'));
  expect(signal.aborted).toBe(true);
  expect(await screen.findByText('正文2')).toBeTruthy();
  await act(async () => v1.resolve(listing([comment(1)])));
  expect(screen.queryByText('正文1')).toBeNull();
  expect(screen.getByText(/固定版本 22/)).toBeTruthy();
  fireEvent.change(screen.getByLabelText('评论内容'), { target: { value: 'V2 草稿' } });
  view.rerender(panel('21'));
  expect(screen.queryByDisplayValue('V2 草稿')).toBeNull();
});

it('根列表不预取回复；热门回复按独立游标有界续页', async () => {
  h.json.mockImplementation(async (path: string) => {
    if (path.includes('/replies')) return path.includes('cursor=r1')
      ? listing([comment(5, { parentId: id(1) })]) : listing([comment(3, { parentId: id(1) }), comment(4, { parentId: id(1) })], 'r1');
    return listing([comment(1), comment(2)]);
  });
  render(panel());
  await screen.findByText('正文1');
  expect(reads().some(path => path.includes('/replies'))).toBe(false);
  fireEvent.click(within(screen.getByLabelText(`根评论 ${id(1)}`)).getByRole('button', { name: '查看回复' }));
  expect(await screen.findByText('正文4')).toBeTruthy();
  expect(reads().filter(path => path.includes('/replies'))).toEqual([`/api/documents/10/versions/21/comments/${id(1)}/replies?limit=20`]);
  fireEvent.click(screen.getByRole('button', { name: '更多回复' }));
  expect(await screen.findByText('正文5')).toBeTruthy();
  expect(screen.getByText('已显示全部可见回复。')).toBeTruthy();
  expect(reads().filter(path => path.includes(id(2)))).toHaveLength(0);
});

it('首页故障不冒充空讨论或启用发表；续页故障保留已显示项并按同一游标重试', async () => {
  let attempt = 0;
  h.json.mockImplementation(async (path: string) => path.includes('cursor=c1')
    ? (++attempt === 1 ? Promise.reject(new ApiError(503)) : listing([comment(2)])) : Promise.reject(new ApiError(503)));
  const view = render(panel());
  expect(await screen.findByText(/评论读取失败/)).toBeTruthy();
  expect(screen.queryByText('该版本暂无评论。')).toBeNull(); expect(screen.queryByLabelText('评论内容')).toBeNull();
  view.unmount();
  h.json.mockImplementation(async (path: string) => path.includes('cursor=c1')
    ? (++attempt === 1 ? Promise.reject(new ApiError(503)) : listing([comment(2)])) : listing([comment(1)], 'c1'));
  render(panel());
  fireEvent.click(await screen.findByRole('button', { name: '更多评论' }));
  expect(await screen.findByText(/评论读取失败/)).toBeTruthy();
  expect(screen.getByText('正文1')).toBeTruthy(); expect(screen.queryByText('已显示全部可见根评论。')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '重新读取评论' }));
  expect(await screen.findByText('正文2')).toBeTruthy(); expect(screen.getByText('正文1')).toBeTruthy();
  expect(reads().filter(path => path.includes('cursor=c1'))).toHaveLength(2);
});

it('只读用户可读不可发表；正文与标记按纯文本显示，不执行脚本或加载外链', async () => {
  const markup = '<img src=x onerror=alert(1)> ![p](https://example.invalid/a.png) [链接](https://example.invalid)';
  h.json.mockResolvedValue(listing([comment(1, { body: markup })], null, false));
  render(panel());
  expect(await screen.findByText(markup)).toBeTruthy();
  expect(document.querySelector('img')).toBeNull(); expect(document.querySelector('a')).toBeNull();
  expect(screen.getByText(/没有发表评论的资格/)).toBeTruthy(); expect(screen.queryByLabelText('评论内容')).toBeNull();
  expect(screen.queryByRole('button', { name: '编辑' })).toBeNull();
});

it('按 code point 校验正文：补充字符计 1，超限提示但不截断，NUL 不能提交', async () => {
  render(panel());
  const input = await screen.findByLabelText('评论内容') as HTMLTextAreaElement;
  const button = () => screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement;
  expect(button().disabled).toBe(true);
  fireEvent.change(input, { target: { value: ` ${'😀'.repeat(4000)} ` } });
  expect(screen.getByText('4000 / 4000')).toBeTruthy(); expect(button().disabled).toBe(false);
  fireEvent.change(input, { target: { value: '😀'.repeat(4000) + 'a' } });
  expect(screen.getByText('4001 / 4000')).toBeTruthy(); expect(screen.getByRole('alert').textContent).toContain('不会截断');
  expect(input.value).toHaveLength(8001); expect(button().disabled).toBe(true);
  fireEvent.change(input, { target: { value: 'a\u0000b' } });
  expect(button().disabled).toBe(true);
  expect(h.send).not.toHaveBeenCalled();
});

it('结果不确定只按原内容和原标识显式重试；放弃核对后改动正文使用新标识，成功后重读而不乐观追加', async () => {
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(receipt({}, 200)).mockResolvedValueOnce(receipt({ commentId: id(91) }));
  render(panel());
  fireEvent.change(await screen.findByLabelText('评论内容'), { target: { value: '  第一次  ' } });
  fireEvent.click(screen.getByRole('button', { name: '发表评论' })); fireEvent.click(screen.getByRole('button', { name: /发表评论/ }));
  expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  expect(writes()).toHaveLength(1);
  expect((screen.getByRole('button', { name: /发表评论/ }) as HTMLButtonElement).disabled).toBe(true);
  const reads0 = reads().length;
  fireEvent.click(screen.getByRole('button', { name: '按原内容重试' }));
  expect(await screen.findByText('已核对为同一次提交，未重复发表。')).toBeTruthy();
  const [first, retry] = writes();
  expect(retry[1].headers['X-Request-Id']).toBe(first[1].headers['X-Request-Id']);
  expect(retry[1].body).toBe(first[1].body); expect(first[1].body).toBe('{"body":"第一次"}');
  await waitFor(() => expect(reads().length).toBeGreaterThan(reads0));
  expect(screen.queryByText('第一次')).toBeNull();
  fireEvent.change(screen.getByLabelText('评论内容'), { target: { value: '第二次' } });
  // 成功后的重读确认资格前不能再次发表。
  await waitFor(() => expect((screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  expect(await screen.findByText('已发表。')).toBeTruthy();
  expect(writes()[2][1].headers['X-Request-Id']).not.toBe(first[1].headers['X-Request-Id']);
});

it('单层回复：只能回复根评论，回复项没有回复、编辑或附件入口', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? listing([comment(2, { parentId: id(1) })]) : listing([comment(1)]));
  h.send.mockResolvedValue(receipt({ parentId: id(1) }));
  render(panel());
  fireEvent.click(await screen.findByRole('button', { name: '查看回复' }));
  const reply = await screen.findByLabelText(`回复 ${id(2)}`);
  expect(within(reply).queryByRole('button')).toBeNull();
  expect(screen.getAllByRole('button', { name: /回复/ }).map(button => button.textContent)).toEqual(['收起回复', '提交回复']);
  expect(screen.queryByText(/编辑|附件|提及/)).toBeNull();
  fireEvent.change(screen.getByLabelText('回复内容'), { target: { value: '回复正文' } });
  fireEvent.click(screen.getByRole('button', { name: '提交回复' }));
  await screen.findByText('已发表。');
  expect(writes()[0][1].body).toBe(`{"parentId":"${id(1)}","body":"回复正文"}`);
});

it('本人删除需确认：成功立即清除正文与回复草稿，他人回复保留，删除根后不能追加回复；他人评论没有删除入口', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? listing([comment(2, { parentId: id(1) })]) : listing([own(1), comment(3)]));
  h.send.mockResolvedValue(new Response(null, { status: 204 }));
  render(panel());
  await screen.findByText('正文1');
  expect(within(screen.getByLabelText(`根评论 ${id(3)}`)).queryByRole('button', { name: '删除评论' })).toBeNull();
  const root = screen.getByLabelText(`根评论 ${id(1)}`);
  fireEvent.click(within(root).getByRole('button', { name: '查看回复' }));
  fireEvent.change(await screen.findByLabelText('回复内容'), { target: { value: '未提交的回复草稿' } });
  fireEvent.click(within(root).getByRole('button', { name: '删除评论' }));
  expect(screen.getByText(/删除后正文立即清除，无法恢复/)).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  await waitFor(() => expect(screen.queryByText('正文1')).toBeNull());
  expect(screen.getAllByText(/该评论已删除/)).toHaveLength(1);
  expect(screen.queryByDisplayValue('未提交的回复草稿')).toBeNull();
  expect(await screen.findByText('正文2')).toBeTruthy();
  expect(await screen.findByText(/根评论已删除，已有回复保留/)).toBeTruthy();
  expect(screen.queryByLabelText('回复内容')).toBeNull();
  expect(writes()).toHaveLength(1);
  expect(writes()[0][0]).toBe(`/api/documents/10/versions/21/comments/${id(1)}`);
  expect(writes()[0][1]).toMatchObject({ method: 'DELETE', body: undefined });
});

it('删除结果不确定时可重试同一目标；已不存在提示且不再提交', async () => {
  h.json.mockResolvedValue(listing([own(1), own(2)]));
  h.send.mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(json({ code: 500, message: 'x', data: { errorCode: 'NOT_FOUND' } }, false, 404));
  render(panel());
  fireEvent.click(within(await screen.findByLabelText(`根评论 ${id(1)}`)).getByRole('button', { name: '删除评论' }));
  fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
  await waitFor(() => expect(screen.queryByText('正文1')).toBeNull());
  expect(writes().map(([path]) => path)).toEqual([`/api/documents/10/versions/21/comments/${id(1)}`, `/api/documents/10/versions/21/comments/${id(1)}`]);
  fireEvent.click(within(screen.getByLabelText(`根评论 ${id(2)}`)).getByRole('button', { name: '删除评论' }));
  fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  expect(await screen.findByText('该评论已不存在或不属于当前账号。')).toBeTruthy();
  expect((screen.getByRole('button', { name: /确认删除/ }) as HTMLButtonElement).disabled).toBe(true);
  expect(screen.getByText('正文2')).toBeTruthy();
});

it('发表前撤销 comment：拒绝后停止提交并重新核对，重读返回前不以旧按钮放行', async () => {
  const recheck = deferred<unknown>();
  h.json.mockResolvedValueOnce(listing([comment(1)])).mockReturnValueOnce(recheck.promise);
  h.send.mockResolvedValue(json({ code: 500, message: 'x', data: { errorCode: 'FORBIDDEN' } }, false, 403));
  render(panel());
  fireEvent.change(await screen.findByLabelText('评论内容'), { target: { value: '想发表' } });
  fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  // 拒绝后立即重读；资格未重新确认前，已显示内容保留，但发表按钮不可用，再次点击不会发送。
  expect(await screen.findByText('正在重新核对发表资格…')).toBeTruthy();
  const button = screen.getByRole('button', { name: '发表评论' }) as HTMLButtonElement;
  expect(button.disabled).toBe(true);
  fireEvent.click(button);
  expect(screen.getByText('正文1')).toBeTruthy();
  expect(writes()).toHaveLength(1);
  await act(async () => recheck.resolve(listing([comment(1)], null, false)));
  expect(await screen.findByText(/没有发表评论的资格/)).toBeTruthy();
  expect(screen.queryByLabelText('评论内容')).toBeNull();
  expect(writes()).toHaveLength(1);
});

it('结果待核对期间若列表正在重读，按原内容重试同样暂停', async () => {
  const recheck = deferred<unknown>();
  h.json.mockResolvedValueOnce(listing([])).mockReturnValueOnce(recheck.promise);
  h.send.mockRejectedValueOnce(new ApiError(0));
  render(panel());
  fireEvent.change(await screen.findByLabelText('评论内容'), { target: { value: '待核对' } });
  fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  fireEvent.click(await screen.findByRole('button', { name: '重新读取核对' }));
  const retry = await screen.findByRole('button', { name: '按原内容重试' }) as HTMLButtonElement;
  await waitFor(() => expect(retry.disabled).toBe(true));
  fireEvent.click(retry);
  expect(writes()).toHaveLength(1);
  await act(async () => recheck.resolve(listing([])));
  await waitFor(() => expect((screen.getByRole('button', { name: '按原内容重试' }) as HTMLButtonElement).disabled).toBe(false));
});

it('撤权立即遮蔽：读取发现资源不可访问时清理正文，只保留已知本人评论的无正文删除且不重读讨论', async () => {
  h.json.mockImplementation(async (path: string) => path.includes('/replies') ? Promise.reject(new ApiError(404)) : listing([own(1), comment(2)]));
  h.send.mockResolvedValue(new Response(null, { status: 204 }));
  render(panel());
  await screen.findByText('正文1');
  fireEvent.click(within(screen.getByLabelText(`根评论 ${id(2)}`)).getByRole('button', { name: '查看回复' }));
  expect(await screen.findByText(/评论当前不可访问，已清理/)).toBeTruthy();
  expect(screen.queryByText('正文1')).toBeNull(); expect(screen.queryByText('正文2')).toBeNull(); expect(screen.queryByText('成员1')).toBeNull();
  const count = reads().length;
  fireEvent.click(screen.getByRole('button', { name: '删除本人评论' }));
  fireEvent.click(screen.getByRole('button', { name: '确认删除' }));
  await waitFor(() => expect(screen.queryByRole('button', { name: '删除本人评论' })).toBeNull());
  expect(writes()).toHaveLength(1); expect(writes()[0][1].method).toBe('DELETE');
  expect(reads()).toHaveLength(count);
  expect(screen.getAllByRole('button').map(button => button.textContent)).not.toContain('删除评论');
});

it('通知目标先核对原版本与目标评论；失效时清理且不改用其他版本讨论', async () => {
  h.json.mockResolvedValueOnce({ item: comment(2, { parentId: id(1) }), canReply: true });
  const view = render(<CommentTargetPanel documentId="10" versionId="21" commentId={id(2)} />);
  expect(await screen.findByText('正文2')).toBeTruthy();
  expect(screen.getByText(/固定版本 21 · 回复/)).toBeTruthy();
  expect(reads()).toEqual([`/api/documents/10/versions/21/comments/${id(2)}`]);
  h.json.mockReset().mockRejectedValue(new ApiError(404));
  view.rerender(<CommentTargetPanel documentId="10" versionId="21" commentId={id(3)} />);
  expect(await screen.findByText(/评论目标当前不可访问.*不会改为显示其他版本的讨论/)).toBeTruthy();
  expect(screen.queryByText('正文2')).toBeNull();
  expect(reads().every(path => path.includes('/versions/21/'))).toBe(true);
});
