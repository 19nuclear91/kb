import { h } from 'vue';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { expect, it, vi } from 'vitest';
import App from '../App.vue';
import { Session } from '../session';
import { mountRouter } from '../test/app';
import { me, storage } from '../test/fixtures';
import { detail, history, page, response } from '../knowledgeGaps/testSupport';
const notificationId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', target = '9007199254740993';
const none = { assign: false, note: false, returnToReception: false, submitSolution: false, confirm: false, rejectSolution: false, withdraw: false, close: false };
function harness(missing: boolean) {
  const transport = vi.fn(async (url: RequestInfo | URL, options: RequestInit = {}) => {
    const path = String(url);
    if (path === '/api/auth/me') return response(me);
    if (path === '/api/auth/permissions') return response([]);
    if (path === '/api/admin/spaces') return response([]);
    if (path === '/api/notifications/unread-count') return response({ count: '1', asOf: '2026-11-14T00:00:00Z' });
    if (path.startsWith('/api/notifications?')) return response({ ...page([{ notificationId, type: 'GAP_SOLUTION_SUBMITTED', prompt: '不可渲染的来源正文',
      createdAt: '2026-11-14T00:00:00Z', readAt: null, target: { kind: 'KNOWLEDGE_GAP', gapId: 9007199254740993n } }]), asOf: '2026-11-14T00:00:00Z' });
    if (path.includes('/history?')) return response(page([history()]));
    if (path === `/api/knowledge-gaps/${target}`) return missing ? new Response(null, { status: 404 })
      : response(detail({ state: 'CLOSED', version: 3n, capabilities: none }));
    throw new Error('Unexpected protected request');
  });
  const session = new Session(storage(), transport as typeof fetch), view = mountRouter(() => h(App, { session }), { path: '/notifications' });
  return { transport, view };
}
it.each([true, false])('问题通知打开相同 ID，重新鉴权/核对当前状态，missing=%s，无自动已读或确认', async missing => {
  const h = harness(missing); await screen.findByText('你的知识问题有方案待确认');
  expect(screen.queryByText('不可渲染的来源正文')).toBeNull(); await fireEvent.click(screen.getByRole('button', { name: '打开目标' }));
  await waitFor(() => expect(h.view.router.currentRoute.value.path).toBe(`/knowledge-gaps/${target}`));
  if (missing) { await screen.findByText('当前问题不可访问，已清理旧内容，请由当前合格人员核对。'); expect(screen.queryByText('明确分享的描述')).toBeNull(); }
  else { await screen.findByText(/问题 9007199254740993 · 已关闭/); expect(screen.getByText(/从旧通知进入也须核对/)).toBeTruthy(); }
  expect(h.transport.mock.calls.some(([, options]) => options?.method === 'PUT' || options?.method === 'POST')).toBe(false);
  const reads = h.transport.mock.calls.filter(([path]) => String(path).startsWith('/api/notifications'));
  expect(reads.length).toBeGreaterThanOrEqual(2);
  for (const [, options] of reads) expect(new Headers(options?.headers).get('X-Kb-Notification-Contract')).toBe('KNOWLEDGE_GAPS_V1');
});
