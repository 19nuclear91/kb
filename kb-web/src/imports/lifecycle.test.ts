import { mount, flushPromises } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { afterEach, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { Session } from '../session';
import { ApiError } from '../api';
import { useSessionStore } from '../stores/session';
import { deferred, json, me, storage } from '../test/fixtures';
import { renderWith } from '../test/render';
import ImportDetailPage from '../pages/ImportDetailPage.vue';
import ImportProgress from './ImportProgress.vue';
const receipt = { documentId: '10', versionId: '20', parseTaskId: '30' };
afterEach(() => { Reflect.deleteProperty(document, 'visibilityState'); vi.restoreAllMocks(); });
it('没有 view 的固定版本拒绝保留受理事实且不持续读取', async () => {
  const pinia = createPinia(), read = vi.fn().mockRejectedValue(new ApiError(404));
  useSessionStore(pinia).bind({ getSnapshot: () => ({ epoch: 1, phase: 'ready', me }), subscribe: () => () => {}, json: read } as unknown as Session);
  const view = mount(ImportProgress, { props: { receipt }, global: { plugins: [pinia] } });
  await view.get('button').trigger('click'); await flushPromises();
  expect(read.mock.calls[0]![0]).toBe('/api/documents/10/progress?versionId=20');
  expect(view.text()).toContain('已受理，当前无法查看处理状态'); expect(view.get('button').attributes('disabled')).toBeDefined();
  await view.get('button').trigger('click'); expect(read).toHaveBeenCalledTimes(1); view.unmount();
});
it('解析器尚未就绪显示排队而非上传失败或发布', async () => {
  const pinia = createPinia();
  useSessionStore(pinia).bind({ getSnapshot: () => ({ epoch: 1, phase: 'ready', me }), subscribe: () => () => {},
    json: vi.fn(async () => ({ versionId: '20', indexStatus: 'pending', taskStatus: 'pending' })) } as unknown as Session);
  const view = mount(ImportProgress, { props: { receipt }, global: { plugins: [pinia] } });
  await view.get('button').trigger('click'); await flushPromises(); expect(view.text()).toContain('排队中'); expect(view.text()).not.toContain('已发布'); view.unmount();
});
it('隐藏、恢复、换号与离页清理私有内容，迟到响应不能恢复旧文件或自动发送', async () => {
  let identity = me;
  let block = false;
  const late = deferred<Response>();
  const transport = vi.fn(async (path: RequestInfo | URL) => {
    const url = String(path);
    if (url === '/api/auth/me') return json(identity);
    if (url.endsWith('/capabilities')) return json({ protocolVersion: 1, maxItems: 100, maxTotalBytes: 1024, maxFileBytes: 100, maxManifestBytes: 1000, uploadConcurrency: 2, extensions: ['txt'] });
    if (url.includes('/items?')) return block ? late.promise : json({ items: [{ itemId: 2, importId: 9, ordinal: 1, revision: 0, state: 'PENDING', canUpload: true,
      file: { itemKey: 'one', fileName: 'private.txt', title: '私有标题', sizeBytes: 5, sha256: 'a'.repeat(64) } }], hasMore: false, nextCursor: null });
    return json({ importId: 9, createdAt: '2026-11-08T00:00:00Z', state: 'OPEN', counts: { pending: 1, processing: 0, accepted: 0, failed: 0 }, canContinue: true, spaceId: 8, securityLevel: 0 });
  });
  const session = new Session(storage(), transport as typeof fetch); await session.refresh();
  const view = await renderWith(ImportDetailPage, { path: '/imports/9', routePath: '/imports/:importId', setup: pinia => useSessionStore(pinia).bind(session) });
  await screen.findByText(/private.txt/);
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange')); await flushPromises();
  expect(screen.queryByText(/private.txt/)).toBeNull();
  block = true; Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' }); document.dispatchEvent(new Event('visibilitychange'));
  await waitFor(() => expect(transport.mock.calls.filter(([path]) => String(path).includes('/items?')).length).toBe(2));
  identity = { ...me, user: { ...me.user, userId: '2' } }; session.clear();
  late.resolve(json({ items: [], hasMore: false, nextCursor: null })); await flushPromises();
  expect(screen.queryByText(/private.txt/)).toBeNull(); expect(transport.mock.calls.some(([path]) => String(path).endsWith('/file'))).toBe(false);
  await view.router.push('/documents'); view.unmount();
});
