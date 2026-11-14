import { h } from 'vue';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { expect, it, vi } from 'vitest';
import App from '../App.vue';
import { Session } from '../session';
import { mountRouter } from '../test/app';
import { me, storage } from '../test/fixtures';
import { chooseOption } from '../test/render';
import { detail, history, item, page, participant, response } from '../knowledgeGaps/testSupport';
import type { GapState } from '../knowledgeGaps/types';

const none = { assign: false, note: false, returnToReception: false, submitSolution: false, confirm: false, rejectSolution: false, withdraw: false, close: false };
function harness(path: string, read: (path: string) => Response) {
  const transport = vi.fn(async (url: RequestInfo | URL) => {
    const path = String(url);
    if (path === '/api/auth/me') return response(me);
    if (path === '/api/auth/permissions') return response(['knowledge:gap:read']);
    if (path === '/api/notifications/unread-count') return response({ count: '0', asOf: '2026-11-14T00:00:00Z' });
    return read(path);
  });
  const session = new Session(storage(), transport as typeof fetch), view = mountRouter(() => h(App, { session }), { path });
  return { transport, view };
}
it('统一队列区分全部六态，失效负责人只显示需人工接管，不泄露生命周期原因', async () => {
  const states: GapState[] = ['SUBMITTED', 'IN_PROGRESS', 'AWAITING_CONFIRMATION', 'RESOLVED', 'CLOSED', 'WITHDRAWN'];
  const h = harness('/admin/knowledge-gaps', () => response(page(states.map((state, index) => item({ gapId: BigInt(10 - index), state,
    version: state === 'SUBMITTED' ? 0n : 1n, assignee: state === 'SUBMITTED' ? null : participant(2n), needsReassignment: state === 'IN_PROGRESS' })))));
  await screen.findByRole('link', { name: '问题 10' });
  for (const label of ['待受理', '处理中', '待确认', '已解决', '已关闭', '已撤回']) expect(screen.getByText(`缺少知识 · ${label}`)).toBeTruthy();
  expect(screen.getByText('需人工重新分派')).toBeTruthy(); expect(document.body.textContent).not.toMatch(/离职|部门停用|失去角色/);
  expect(h.transport.mock.calls.every(([path]) => !String(path).includes('/api/admin/identity'))).toBe(true);
});
it('状态筛选由服务端执行且改变条件从首页开始，续页按可见游标无隐藏总数', async () => {
  const h = harness('/admin/knowledge-gaps', path => response(path.includes('status=CLOSED') ? page([item({ gapId: 7n, state: 'CLOSED', version: 1n })])
    : path.includes('cursor=abc') ? page([item({ gapId: 8n })]) : page([item({ gapId: 9n })], { hasMore: true, nextCursor: 'abc' })));
  await screen.findByRole('link', { name: '问题 9' }); await fireEvent.click(screen.getByRole('button', { name: '读取下一页' })); await screen.findByRole('link', { name: '问题 8' });
  await chooseOption('状态筛选', '已关闭'); await screen.findByRole('link', { name: '问题 7' });
  expect(screen.queryByRole('link', { name: '问题 9' })).toBeNull();
  const filtered = h.transport.mock.calls.filter(([path]) => String(path).includes('status=CLOSED'));
  expect(filtered).toHaveLength(1); expect(String(filtered[0][0])).not.toContain('cursor'); expect(String(filtered[0][0])).not.toContain('view');
});
it('队列 503 不是空队列，详情 read 不补充文档/目录权限', async () => {
  const failure = harness('/admin/knowledge-gaps', () => new Response(null, { status: 503 })); await screen.findByText('服务暂不可用，请稍后重试。');
  expect(screen.queryByText('当前视图没有可见问题。')).toBeNull(); failure.view.unmount();
  const h = harness('/admin/knowledge-gaps/123', path => path.includes('/history?') ? response(page([history()]))
    : response(detail({ gapId: 123n, state: 'IN_PROGRESS', version: 1n, assignee: participant(2n), needsReassignment: true, capabilities: none })));
  await screen.findByText('明确分享的描述'); expect(screen.getByText('当前需人工重新分派；提示不会自动修改分派或问题状态。')).toBeTruthy();
  expect(screen.getByRole('link', { name: '返回受理队列' }).getAttribute('href')).toBe('/admin/knowledge-gaps');
  await waitFor(() => expect(h.transport.mock.calls.some(([path]) => String(path).includes('/history?'))).toBe(true));
  expect(h.transport.mock.calls.some(([path]) => /\/api\/(documents|admin\/identity|admin\/spaces)/.test(String(path)))).toBe(false);
});
