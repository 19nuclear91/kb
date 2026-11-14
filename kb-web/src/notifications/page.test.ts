import { beforeEach, expect, it, vi } from 'vitest';
import { h as vnode, nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';
import NotificationsPage from '../pages/NotificationsPage.vue';

type Panel = { documentId: string; versionId: string; files?: boolean; onStatus?: (value: unknown) => void; onDenied?: () => void };
type CommentTarget = { documentId: string; versionId: string; commentId: string };
const h = reactive({ state: { phase: 'ready', epoch: 1 }, json: vi.fn(), refresh: vi.fn(), panels: [] as Panel[], comments: [] as CommentTarget[] });
const session = { json: h.json, refresh: h.refresh, getSnapshot: () => h.state };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return h.state; } }) }));
vi.mock('../reviews/VersionReviewPanel.vue', async () => {
  const { defineComponent, h: vnode } = await import('vue');
  return { default: defineComponent({ props: { documentId: { type: String, required: true }, versionId: { type: String, required: true }, files: Boolean }, emits: ['status', 'denied'], setup(props, { emit }) {
    h.panels.push({ ...props, onStatus: value => emit('status', value), onDenied: () => emit('denied') });
    return () => vnode('div', `固定版本 ${props.versionId} 审核面板`);
  } }) };
});
vi.mock('../comments/CommentTargetPanel.vue', async () => {
  const { defineComponent, h: vnode } = await import('vue');
  return { default: defineComponent({ props: { documentId: { type: String, required: true }, versionId: { type: String, required: true }, commentId: { type: String, required: true } }, setup(props) {
    h.comments.push({ ...props });
    return () => vnode('div', `评论目标 ${props.documentId}/${props.versionId}/${props.commentId}`);
  } }) };
});
const id = (n: number) => `aaaaaaaa-bbbb-cccc-dddd-${String(n).padStart(12, '0')}`;
const review = '11111111-2222-3333-4444-555555555555';
const item = (n: number, type = 'REVIEW_SUBMITTED', readAt: string | null = null, versionId = '21') => ({ notificationId: id(n), type,
  prompt: '<img src=x onerror=alert(1)> 源标题', createdAt: '2026-10-25T00:00:00Z', readAt,
  target: { kind: 'DOCUMENT_VERSION_REVIEW', documentId: '10', versionId, reviewId: review, commentId: null } });
const page = (items: unknown[], nextCursor: string | null = null) => ({ items, hasMore: nextCursor !== null, nextCursor, asOf: '2026-10-25T00:00:00Z' });
const count = { count: '1', asOf: '2026-10-25T00:00:00Z' };
function routes(list: (path: string) => unknown, put: (path: string) => unknown = () => ({})) {
  h.json.mockImplementation(async (path: string, init: RequestInit = {}) => {
    if (path === '/api/notifications/unread-count') {
      return count;
    }
    if (init.method === 'PUT') {
      return put(path);
    }
    return list(path);
  });
}
const show = () => mountRouter(() => vnode(NotificationsPage));
const puts = () => h.json.mock.calls.filter(([, init]) => init?.method === 'PUT');
const counts = () => h.json.mock.calls.filter(([path]) => path === '/api/notifications/unread-count').length;
beforeEach(() => { h.state = { phase: 'ready', epoch: h.state.epoch + 1 }; h.json.mockReset(); h.refresh.mockReset().mockResolvedValue(undefined); h.panels = []; h.comments = []; });

it('合法空页、故障与末页分开显示，不渲染服务端文本', async () => {
  routes(() => page([])); const empty = show(); expect(await screen.findByText('暂无可见通知')).toBeTruthy(); empty.unmount();
  routes(() => Promise.reject(new ApiError(503))); const failed = show(); expect(await screen.findByText('服务暂不可用，请稍后重试。')).toBeTruthy();
  expect(screen.queryByText('暂无可见通知')).toBeNull(); expect(screen.queryByText('已读')).toBeNull(); expect(screen.queryByText(/全部已读/)).toBeNull(); failed.unmount();
  routes(() => page([item(1)])); show(); expect(await screen.findByText('有版本等待你审核')).toBeTruthy();
  expect(screen.getByText('已显示全部可见通知')).toBeTruthy(); expect(screen.queryByText(/源标题/)).toBeNull(); expect(document.querySelector('img')).toBeNull();
});

it('续页失败保留已有条目，按同一游标重试，重复点击互斥', async () => {
  const retry = deferred<unknown>(); let more = 0;
  routes(path => path.includes('cursor=c1') ? (++more === 1 ? Promise.reject(new ApiError(503)) : retry.promise) : page([item(1)], 'c1'));
  show(); const button = await screen.findByRole('button', { name: '更多通知' });
  await fireEvent.click(button); expect(await screen.findByText('服务暂不可用，请稍后重试。')).toBeTruthy(); expect(screen.getByText('有版本等待你审核')).toBeTruthy();
  void fireEvent.click(screen.getByRole('button', { name: /更多通知/ })); void fireEvent.click(screen.getByRole('button', { name: /更多通知/ }));
  expect(h.json.mock.calls.filter(([path]) => String(path).includes('cursor=c1'))).toHaveLength(2);
  retry.resolve(page([item(1), item(2, 'REVIEW_REJECTED')])); await flushPromises();
  expect(screen.getAllByText('有版本等待你审核')).toHaveLength(1); expect(screen.getByText('你送审的版本被驳回')).toBeTruthy(); expect(screen.queryByRole('button', { name: /更多通知/ })).toBeNull();
});

it('打开目标不自动已读；标记已读互斥，成功后更新条目并刷新计数', async () => {
  const sending = deferred<unknown>(); routes(() => page([item(1)]), () => sending.promise);
  show(); await fireEvent.click(await screen.findByRole('button', { name: '打开目标' }));
  expect(await screen.findByText('固定版本 21 审核面板')).toBeTruthy(); expect(puts()).toHaveLength(0); expect(screen.getByText('未读')).toBeTruthy();
  const before = counts();
  void fireEvent.click(screen.getByRole('button', { name: '标记已读' })); void fireEvent.click(screen.getByRole('button', { name: /标记已读/ }));
  expect(puts()).toHaveLength(1); expect(puts()[0][0]).toBe(`/api/notifications/${id(1)}/read`);
  sending.resolve({ notificationId: id(1), readAt: '2026-10-25T01:00:00Z' }); await flushPromises();
  expect(screen.getByText('已读')).toBeTruthy(); await waitFor(() => expect(counts()).toBe(before + 1));
});

it('已读结果不确定时提示待核对，只显式重试同一通知；不存在则移除', async () => {
  let attempt = 0;
  routes(() => page([item(1), item(2)]), path => path.includes(id(2)) ? Promise.reject(new ApiError(404))
    : ++attempt === 1 ? Promise.reject(new ApiError(0)) : { notificationId: id(1), readAt: '2026-10-25T01:00:00Z' });
  show(); const [first] = await screen.findAllByRole('button', { name: '标记已读' });
  await fireEvent.click(first); expect(await screen.findByText(/已读结果待核对/)).toBeTruthy(); expect(puts()).toHaveLength(1);
  await fireEvent.click(screen.getByRole('button', { name: /重试标记已读/ })); await waitFor(() => expect(screen.queryByText(/已读结果待核对/)).toBeNull());
  expect(puts().map(([path]) => path)).toEqual([`/api/notifications/${id(1)}/read`, `/api/notifications/${id(1)}/read`]);
  await fireEvent.click(screen.getByRole('button', { name: '标记已读' })); expect(await screen.findByText('该通知已不存在或不属于当前账号。')).toBeTruthy();
  expect(screen.getAllByText('有版本等待你审核')).toHaveLength(1);
});

it('目标固定原版本与轮次：轮次变化提示核对，当前版本不同也不改指，撤权清理', async () => {
  routes(() => page([item(1)])); show(); await fireEvent.click(await screen.findByRole('button', { name: '打开目标' }));
  await screen.findByText('固定版本 21 审核面板'); const panel = h.panels.at(-1)!;
  expect(panel).toMatchObject({ documentId: '10', versionId: '21', files: false });
  panel.onStatus?.({ state: { documentId: '10', versionId: '21', reviewId: review, currentVersionId: '22' } }); await nextTick();
  expect(screen.queryByText(/审核轮次已变化/)).toBeNull();
  panel.onStatus?.({ state: { documentId: '10', versionId: '21', reviewId: '99999999-2222-3333-4444-555555555555' } }); await nextTick();
  expect(screen.getByText(/审核轮次已变化/)).toBeTruthy(); expect(h.panels.every(props => props.versionId === '21')).toBe(true);
  panel.onDenied?.(); await nextTick();
  expect(screen.getByText(/目标当前不可访问/)).toBeTruthy(); expect(screen.queryByText('固定版本 21 审核面板')).toBeNull();
});

it('切换目标时重建固定上下文，不沿用上一目标的轮次提示或撤权状态', async () => {
  routes(() => page([item(1), item(2, 'REVIEW_APPROVED', null, '22')]));
  show(); const [first, second] = await screen.findAllByRole('button', { name: '打开目标' });
  await fireEvent.click(first); await screen.findByText('固定版本 21 审核面板');
  h.panels.at(-1)!.onStatus?.({ state: { documentId: '10', versionId: '21', reviewId: '99999999-2222-3333-4444-555555555555' } }); await nextTick();
  expect(screen.getByText(/审核轮次已变化/)).toBeTruthy(); h.panels.at(-1)!.onDenied?.(); await nextTick(); await fireEvent.click(second);
  expect(await screen.findByText('固定版本 22 审核面板')).toBeTruthy(); expect(screen.queryByText('固定版本 21 审核面板')).toBeNull();
  expect(screen.queryByText(/审核轮次已变化/)).toBeNull(); expect(screen.queryByText(/目标当前不可访问/)).toBeNull();
});

it('回复通知打开固定版本的评论目标，不调用审核面板，也不改指最新版本', async () => {
  const comment = 'cccccccc-0000-0000-0000-000000000005';
  routes(() => page([{ ...item(3, 'COMMENT_REPLIED'), target: { kind: 'DOCUMENT_VERSION_COMMENT', documentId: '10', versionId: '21', reviewId: null, commentId: comment } }]));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '打开目标' }));
  expect(await screen.findByText('评论目标 10/21/' + comment)).toBeTruthy(); expect(h.panels).toHaveLength(0);
  expect(h.comments).toEqual([{ documentId: '10', versionId: '21', commentId: comment }]); expect(puts()).toHaveLength(0);
});

it('同页混合审核、回复和问题通知时分别匹配目标，显式已读不改变业务', async () => {
  const comment = 'cccccccc-0000-0000-0000-000000000005', gapId = '9007199254740993';
  routes(() => page([item(1), {
    ...item(2, 'COMMENT_REPLIED'),
    target: { kind: 'DOCUMENT_VERSION_COMMENT', documentId: '10', versionId: '21', reviewId: null, commentId: comment },
  }, { ...item(3, 'GAP_ASSIGNED'), target: { kind: 'KNOWLEDGE_GAP', gapId } }]),
  () => ({ notificationId: id(3), readAt: '2026-10-25T01:00:00Z' }));
  const view = show();
  await screen.findByText('有知识问题分派给你');
  expect(screen.getByText('有版本等待你审核')).toBeTruthy();
  expect(screen.getByText('你的评论有新回复')).toBeTruthy();
  expect(document.body.textContent).not.toContain('源标题');
  const rows = screen.getAllByRole('listitem');
  expect(rows).toHaveLength(3);
  await fireEvent.click(within(rows[0]).getByRole('button', { name: '打开目标' }));
  await screen.findByText('固定版本 21 审核面板');
  await fireEvent.click(within(rows[1]).getByRole('button', { name: '打开目标' }));
  await screen.findByText('评论目标 10/21/' + comment);
  expect(h.panels).toHaveLength(1);
  expect(h.comments).toEqual([{ documentId: '10', versionId: '21', commentId: comment }]);
  expect(puts()).toHaveLength(0);
  await fireEvent.click(within(rows[2]).getByRole('button', { name: '标记已读' }));
  await waitFor(() => expect(within(rows[2]).queryByRole('button', { name: '标记已读' })).toBeNull());
  expect(puts()).toHaveLength(1);
  expect(puts()[0][0]).toBe(`/api/notifications/${id(3)}/read`);
  await fireEvent.click(within(rows[2]).getByRole('button', { name: '打开目标' }));
  await waitFor(() => expect(view.router.currentRoute.value.path).toBe(`/knowledge-gaps/${gapId}`));
  expect(puts()).toHaveLength(1);
  expect(h.json.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
});

it('账号切换后旧列表迟到不进入新身份，并取消旧请求', async () => {
  const old = deferred<unknown>(); routes(() => old.promise);
  show(); await waitFor(() => expect(h.json.mock.calls.some(([path]) => String(path).startsWith('/api/notifications?'))).toBe(true));
  const signal = h.json.mock.calls.find(([path]) => String(path).startsWith('/api/notifications?'))![1].signal as AbortSignal;
  routes(() => page([])); h.state = { phase: 'ready', epoch: h.state.epoch + 1 }; await nextTick(); expect(signal.aborted).toBe(true);
  old.resolve(page([item(9)])); await flushPromises();
  expect(await screen.findByText('暂无可见通知')).toBeTruthy(); expect(screen.queryByText('有版本等待你审核')).toBeNull();
  h.state = { phase: 'loading', epoch: h.state.epoch + 1 }; await nextTick();
  expect(screen.getByText('请先完成登录与身份确认')).toBeTruthy(); expect(screen.queryByText('暂无可见通知')).toBeNull();
});
