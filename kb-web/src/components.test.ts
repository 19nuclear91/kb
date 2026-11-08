import { beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { fireEvent, render, screen, waitFor } from '@testing-library/vue';
import { ApiError } from './api';
import { mountRouter } from './test/app';
import { json, me, turn } from './test/fixtures';
import type { Session } from './session';
const context = reactive({ session: {} as Session, state: { phase: 'ready', me: null as typeof me | null, epoch: 1 } });
vi.mock('./SessionProvider', () => ({ useSession: () => context }));
import SafeText from './ui/SafeText.vue';
import TurnCard from './ui/TurnCard.vue';
import AccountPage from './pages/AccountPage.vue';
import LoginPage from './pages/LoginPage.vue';
import PasswordForm from './auth/PasswordForm.vue';
import { NAV_KEY } from './sso/storage';
import FilePreview from './FilePreview.vue';
import CitationDrawer from './CitationDrawer.vue';
import SearchPage from './pages/SearchPage.vue';
beforeEach(() => {
  context.state = { phase: 'ready', me, epoch: 1 };
  context.session = { json: vi.fn(), post: vi.fn(), send: vi.fn(), sso: vi.fn().mockResolvedValue([]), login: vi.fn(), logout: vi.fn(), refresh: vi.fn(), getSnapshot: () => context.state } as unknown as Session;
});
it('正文作为文本/代码呈现，不生成 HTML、危险链接或远程资源节点', () => {
  const text = '<img src="https://evil.test/a" onerror="alert(1)"><script>alert(1)</script> [点击](javascript:alert(1))\n```js\nalert(1)\n```';
  const { container } = render(SafeText, { props: { text } }); expect(container.textContent).toBe(text); expect(container.querySelector('script,img,a,iframe')).toBeNull(); expect(fetch).not.toHaveBeenCalled();
});
it('BLOCKED 替换旧助手文本与引用，本人问题保留；用量和生成分开显示', async () => {
  const visible = { ...turn, status: 'COMPLETED', citations: [{ documentId: '1', versionId: '2', chunkId: '3', title: '旧来源' }] };
  const view = render(TurnCard, { props: { value: visible, onOpen: vi.fn() } }); expect(screen.getByText('已保存前缀')).toBeTruthy(); expect(screen.getByText('用量确认中')).toBeTruthy();
  await view.rerender({ value: { ...visible, visibility: 'BLOCKED' }, onOpen: vi.fn() });
  expect(screen.queryByText('已保存前缀')).toBeNull(); expect(screen.queryByText('旧来源')).toBeNull(); expect(screen.getByText('原问题')).toBeTruthy(); expect(screen.getByText('真实用量未知；扣除额度 9')).toBeTruthy();
});
it('PDF Blob 在切换、关闭和卸载时释放，文本按纯文本显示', async () => {
  const create = vi.fn().mockReturnValueOnce('blob:first').mockReturnValueOnce('blob:second'), revoke = vi.fn();
  vi.stubGlobal('URL', class extends URL { static createObjectURL = create; static revokeObjectURL = revoke; });
  vi.mocked(context.session.send).mockResolvedValueOnce(new Response('pdf', { headers: { 'Content-Type': 'application/pdf' } })).mockResolvedValueOnce(new Response('pdf2', { headers: { 'Content-Type': 'application/pdf' } })).mockResolvedValueOnce(new Response('<img>文本', { headers: { 'Content-Type': 'text/plain' } }));
  const view = render(FilePreview, { props: { path: '/api/documents/1/versions/2/preview', onClose: vi.fn() } });
  await screen.findByTitle('PDF 版本预览'); await view.rerender({ path: '/api/documents/1/versions/3/preview' });
  await waitFor(() => expect(create).toHaveBeenCalledTimes(2)); expect(revoke).toHaveBeenCalledWith('blob:first');
  await view.rerender({ path: null }); expect(revoke).toHaveBeenCalledWith('blob:second');
  await view.rerender({ path: '/api/documents/1/versions/4/preview' }); await screen.findByText('<img>文本'); view.unmount();
});
it('固定版本引用、缺失版本不查询，404 清除原片段和文件入口', async () => {
  const citation = { docId: '1', versionId: '2', chunkId: '3' };
  vi.mocked(context.session.json).mockResolvedValueOnce({ status: 'AVAILABLE', docTitle: '参考', versionNo: 2, chunk: { content: '完整片段', pageNo: null }, previewPath: '/api/documents/1/versions/2/preview', downloadPath: null }).mockRejectedValueOnce(new ApiError(404));
  const view = render(CitationDrawer, { props: { citation, onClose: vi.fn() } }); await screen.findByText('完整片段'); expect(screen.getByText(/章节未知/)).toBeTruthy();
  expect(vi.mocked(context.session.json).mock.calls[0][0]).toBe('/api/documents/1/versions/2/citations/3');
  await view.rerender({ citation: { ...citation, chunkId: '4' } }); await screen.findByText('目标不存在或当前不可访问。'); expect(screen.queryByText('完整片段')).toBeNull(); expect(screen.queryByText('预览原版本')).toBeNull();
  await view.rerender({ citation: { ...citation, versionId: null } }); await screen.findByText('该参考材料缺少版本身份，无法定位原版本。'); expect(context.session.json).toHaveBeenCalledTimes(2);
});
it('片段不可用仍保留服务器提供的原版本入口', async () => {
  vi.mocked(context.session.json).mockResolvedValue({ status: 'CHUNK_UNAVAILABLE', docTitle: '旧文件', versionNo: 2, chunk: null, previewPath: '/api/documents/1/versions/2/preview', downloadPath: null });
  render(CitationDrawer, { props: { citation: { docId: '1', versionId: '2', chunkId: '3' }, onClose: vi.fn() } }); await screen.findByText('当前没有匹配片段，可查看原版本文件。'); expect(screen.getByText('预览原版本')).toBeTruthy(); expect(screen.queryByText('下载原件')).toBeNull();
});
it('搜索仅 POST 支持字段，结果有序；503 不伪装成功空结果', async () => {
  let searches = 0;
  vi.mocked(context.session.json).mockResolvedValue([]);
  vi.mocked(context.session.post).mockResolvedValue({ items: [{ documentId: '1', favorited: false }] });
  vi.mocked(context.session.send).mockImplementation(async path => {
    if (path === '/api/search') {
      if (searches++) {
        throw new ApiError(503);
      }
      return json([{ docId: '1', versionId: '2', chunkId: '3', content: '第一片段' }, { docId: '1', versionId: '2', chunkId: '4', content: '第二片段' }]);
    }
    return json({ items: [], hasMore: false, nextCursor: null });
  });
  render(SearchPage); await fireEvent.update(screen.getByLabelText('搜索词'), '秘密搜索词'); await fireEvent.click(screen.getByRole('button', { name: /^搜\s*索$/ }));
  await screen.findByText('第一片段'); expect(context.session.send).toHaveBeenCalledWith('/api/search', expect.objectContaining({ method: 'POST', body: '{"query":"秘密搜索词","candidates":50,"topK":20}', signal: expect.any(AbortSignal) }));
  expect(screen.getAllByText(/第[一二]片段/).map(node => node.textContent)).toEqual(['第一片段', '第二片段']);
  await fireEvent.click(screen.getByRole('button', { name: /^搜\s*索$/ })); await screen.findByText('检索暂不可用，请稍后重试。');
  expect(screen.queryByText('当前条件下未找到可用结果')).toBeNull(); expect(screen.queryByText('第一片段')).toBeNull();
});
it('本地登录凭据失败使用统一提示', async () => {
  context.state.phase = 'anonymous'; vi.mocked(context.session.login).mockRejectedValue(new ApiError(401));
  mountRouter(() => h(LoginPage)); await fireEvent.update(screen.getByLabelText('用户名'), 'alice'); await fireEvent.update(screen.getByLabelText('密码'), 'secret');
  await fireEvent.click(screen.getByRole('button', { name: /^登\s*录$/ })); await screen.findByText('用户名或密码不正确。'); expect(context.session.login).toHaveBeenCalledWith('alice', 'secret');
  expect(sessionStorage.length).toBe(1);
  expect(JSON.parse(sessionStorage.getItem(NAV_KEY)!)).toEqual({ version: 1, returnPath: '/documents', expiresAt: expect.any(Number) });
});
it('改密不确定重试使用同一绑定，成功重新读取 me', async () => {
  vi.mocked(context.session.post).mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce(undefined);
  render(PasswordForm, { props: { forced: true } }); await fireEvent.update(screen.getByLabelText('原密码'), 'old-secret'); await fireEvent.update(screen.getByLabelText('新密码', { exact: true }), 'new-secret'); await fireEvent.update(screen.getByLabelText('确认新密码'), 'new-secret');
  await fireEvent.click(screen.getByRole('button', { name: '保存新密码' })); await screen.findByText('网络结果尚未确认，请稍后核对。'); await fireEvent.click(screen.getByRole('button', { name: '保存新密码' }));
  await waitFor(() => expect(context.session.refresh).toHaveBeenCalledOnce()); const calls = vi.mocked(context.session.post).mock.calls;
  expect(calls[0]).toEqual(calls[1]); expect(calls[0][1]).toMatchObject({ oldPassword: 'old-secret', newPassword: 'new-secret', requestId: expect.any(String) }); expect(sessionStorage.length).toBe(0);
});
it('账户只显示 me 事实，不请求身份目录', async () => {
  render(AccountPage); expect(screen.getByRole('heading', { name: '小林' })).toBeTruthy(); expect(screen.getByText('用户名：alice')).toBeTruthy(); expect(context.session.json).not.toHaveBeenCalled(); expect(context.session.post).not.toHaveBeenCalled();
  await screen.findByRole('button', { name: '重新读取绑定状态' });
});
