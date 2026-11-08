import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, render, screen, waitFor } from '@testing-library/vue';
import VersionPreview from './VersionPreview.vue';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';

const f = reactive({ json: vi.fn(), send: vi.fn(), state: { phase: 'ready', epoch: 1 } });
const session = { json: f.json, send: f.send };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return f.state; } }) }));
const id = 'a'.repeat(32), origin = 'https://preview.example.test';
const path = '/api/documents/9007199254740993/versions/11/preview';
const launch = () => ({ protocolVersion: 1, sessionId: id, launchAction: `${origin}/s/${id}/bootstrap`, launchToken: 'b'.repeat(43),
  expiresAt: Date.now() + 1800000, launchExpiresAt: Date.now() + 60000, heartbeatSeconds: 15 });
const descriptor = (mode = 'OFFICE') => ({ protocolVersion: 1, documentId: '9007199254740993', versionId: '11', mode,
  fileType: mode === 'DIRECT' ? 'txt' : 'docx', previewPath: mode === 'DIRECT' ? path : null });
beforeEach(() => {
  vi.stubEnv('VITE_OFFICE_PREVIEW_ORIGIN', origin);
  f.state = { phase: 'ready', epoch: f.state.epoch + 1 };
  vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(() => {});
  f.json.mockReset().mockImplementation(async (url: string) => url.endsWith('/preview-info') ? descriptor() : url.endsWith('/office-preview/sessions') ? launch() : undefined);
  f.send.mockReset().mockResolvedValue(new Response('原版本文本', { headers: { 'Content-Type': 'text/plain' } }));
});
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllEnvs(); });
it('只提交一次定向表单；匹配 Origin/source/SID 的 ready 才可阅读', async () => {
  render(VersionPreview, { props: { path, onClose: () => {} } });
  const iframe = await screen.findByTitle('Office 只读版本预览') as HTMLIFrameElement;
  await waitFor(() => expect(HTMLFormElement.prototype.submit).toHaveBeenCalledOnce());
  expect(document.querySelector('input[name="launchToken"]')).toBeNull();
  expect(iframe.src).toBe('about:blank');
  expect(iframe.sandbox?.toString() ?? iframe.getAttribute('sandbox')).not.toContain('allow-downloads');
  const data = { type: 'kb-office-ready', protocolVersion: 1, sessionId: id };
  window.dispatchEvent(new MessageEvent('message', { origin: 'https://evil.test', source: iframe.contentWindow, data })); await nextTick();
  expect(screen.queryByText('文档已就绪 · 只读预览')).toBeNull();
  window.dispatchEvent(new MessageEvent('message', { origin, source: window, data })); await nextTick();
  expect(screen.queryByText('文档已就绪 · 只读预览')).toBeNull();
  window.dispatchEvent(new MessageEvent('message', { origin, source: iframe.contentWindow, data })); await nextTick();
  await screen.findByText('文档已就绪 · 只读预览');
  expect(screen.queryByRole('button', { name: '下载原件' })).toBeNull();
});
it('隐藏即卸载并关闭，恢复需要主动打开，旧 ready 无效', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible'), close = vi.fn();
  render(VersionPreview, { props: { path, onClose: close } });
  const iframe = await screen.findByTitle('Office 只读版本预览') as HTMLIFrameElement;
  visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange'));
  expect(screen.queryByTitle('Office 只读版本预览')).toBeNull();
  expect(close).toHaveBeenCalled();
  await waitFor(() => expect(f.json).toHaveBeenCalledWith(`/api/office-preview/sessions/${id}`, expect.objectContaining({ method: 'DELETE' })));
  visibility.mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange'));
  window.dispatchEvent(new MessageEvent('message', { origin, source: iframe.contentWindow, data: { type: 'kb-office-ready', protocolVersion: 1, sessionId: id } })); await nextTick();
  expect(screen.queryByTitle('Office 只读版本预览')).toBeNull();
});
it('版本切换中止旧创建，迟到会话只关闭，不能替换新目标', async () => {
  const old = deferred<ReturnType<typeof launch>>();
  f.json.mockImplementation(async (url: string) => url.endsWith('/preview-info') ? descriptor() : url.endsWith('/office-preview/sessions') ? old.promise : undefined);
  const view = render(VersionPreview, { props: { path, onClose: () => {} } });
  await waitFor(() => expect(f.json).toHaveBeenCalledTimes(2));
  const signal = f.json.mock.calls[1][1].signal;
  await view.rerender({ path: null });
  expect(signal.aborted).toBe(true);
  old.resolve(launch()); await flushPromises();
  expect(screen.queryByTitle('Office 只读版本预览')).toBeNull();
  expect(HTMLFormElement.prototype.submit).not.toHaveBeenCalled();
});
it.each(['DIRECT', 'old'])('文本 %s 分支沿用受保护二进制入口', async mode => {
  f.json.mockImplementation(async () => {
    if (mode === 'old') {
      throw new ApiError(405);
    }
    return descriptor('DIRECT');
  });
  render(VersionPreview, { props: { path, onClose: () => {}, fileName: '旧版本.txt' } });
  await screen.findByText('原版本文本');
  expect(f.send).toHaveBeenCalledWith(path, expect.objectContaining({ signal: expect.any(AbortSignal) }));
});
it.each(['UNAVAILABLE', 'UNSUPPORTED'])('不可用状态 %s 不建立会话', async mode => {
  f.json.mockResolvedValue(descriptor(mode));
  render(VersionPreview, { props: { path, onClose: () => {} } });
  await screen.findByRole('button', { name: '重试预览' });
  expect(f.json).toHaveBeenCalledOnce();
  expect(screen.queryByTitle('Office 只读版本预览')).toBeNull();
});
it('创建结果未知只在用户点击后再试；身份代次变化清理查看器', async () => {
  f.json.mockImplementation(async (url: string) => {
    if (url.endsWith('/preview-info')) {
      return descriptor();
    }
    throw new ApiError(0);
  });
  const close = vi.fn(); render(VersionPreview, { props: { path, onClose: close } });
  await screen.findByText(/打开结果尚未确认/);
  expect(f.json).toHaveBeenCalledTimes(2);
  await fireEvent.click(screen.getByRole('button', { name: '重试预览' }));
  await waitFor(() => expect(f.json).toHaveBeenCalledTimes(4));
  f.state = { phase: 'ready', epoch: f.state.epoch + 1 }; await nextTick();
  expect(close).toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: '重试预览' })).toBeNull();
});
it('查看器失败立即停止保活，关闭结果未知也不恢复实例', async () => {
  const original = globalThis.setTimeout;
  vi.spyOn(globalThis, 'setTimeout').mockImplementation(((handler: TimerHandler, delay?: number, ...args: unknown[]) =>
    delay === 15000 ? 50000 : original(handler, delay, ...args)) as typeof setTimeout);
  const clear = vi.spyOn(globalThis, 'clearTimeout');
  f.json.mockImplementation(async (url: string, options?: RequestInit) => {
    if (options?.method === 'DELETE') {
      throw new ApiError(0);
    }
    return url.endsWith('/preview-info') ? descriptor() : launch();
  });
  render(VersionPreview, { props: { path, onClose: () => {} } });
  const iframe = await screen.findByTitle('Office 只读版本预览') as HTMLIFrameElement;
  await waitFor(() => expect(HTMLFormElement.prototype.submit).toHaveBeenCalledOnce());
  window.dispatchEvent(new MessageEvent('message', { origin, source: iframe.contentWindow,
    data: { type: 'kb-office-error', protocolVersion: 1, sessionId: id } })); await nextTick();
  expect(screen.queryByTitle('Office 只读版本预览')).toBeNull();
  expect(clear).toHaveBeenCalledWith(50000);
  await waitFor(() => expect(f.json.mock.calls.filter(([, options]) => options?.method === 'DELETE')).toHaveLength(1));
  expect(f.json.mock.calls.filter(([url]) => String(url).endsWith('/office-preview/sessions'))).toHaveLength(1);
});

it.each(['info', 'create'])('%s 请求悬挂有界失败，不自动重发，迟到结果不能恢复预览', async stage => {
  vi.useFakeTimers();
  const pending = deferred<unknown>(), denied = vi.fn();
  f.json.mockImplementation(async (url: string) => {
    if (url.endsWith('/preview-info')) {
      return stage === 'info' ? pending.promise : descriptor();
    }
    if (url.endsWith('/office-preview/sessions')) {
      return pending.promise;
    }
  });
  render(VersionPreview, { props: { path, onClose: () => {}, onDenied: denied } });
  await flushPromises();
  const requests = stage === 'info' ? 1 : 2;
  expect(f.json).toHaveBeenCalledTimes(requests);
  const signal = f.json.mock.calls[requests - 1][1].signal;
  await vi.advanceTimersByTimeAsync(14999); await nextTick();
  expect(screen.queryByRole('button', { name: '重试预览' })).toBeNull();
  await vi.advanceTimersByTimeAsync(1); await nextTick();
  expect(signal.aborted).toBe(true);
  expect(screen.getByText(/打开结果尚未确认/)).toBeTruthy();
  expect(screen.getByRole('button', { name: '重试预览' })).toBeTruthy();
  expect(denied).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(60000);
  expect(f.json).toHaveBeenCalledTimes(requests);
  pending.resolve(stage === 'info' ? descriptor() : launch()); await flushPromises();
  expect(screen.queryByTitle('Office 只读版本预览')).toBeNull();
  expect(HTMLFormElement.prototype.submit).not.toHaveBeenCalled();
  const closes = f.json.mock.calls.filter(([, options]) => options?.method === 'DELETE');
  expect(closes).toHaveLength(stage === 'create' ? 1 : 0);
});

it('创建超时后主动重试使用新实例，旧请求迟到只关闭旧会话', async () => {
  vi.useFakeTimers();
  const pending = deferred<ReturnType<typeof launch>>();
  f.json.mockImplementation(async (url: string) => url.endsWith('/preview-info') ? descriptor()
    : url.endsWith('/office-preview/sessions') ? pending.promise : undefined);
  render(VersionPreview, { props: { path, onClose: () => {} } });
  await flushPromises();
  await vi.advanceTimersByTimeAsync(15000); await nextTick();
  const newId = 'd'.repeat(32);
  f.json.mockImplementation(async (url: string) => url.endsWith('/preview-info') ? descriptor()
    : url.endsWith('/office-preview/sessions') ? { ...launch(), sessionId: newId, launchAction: `${origin}/s/${newId}/bootstrap` } : undefined);
  await fireEvent.click(screen.getByRole('button', { name: '重试预览' })); await flushPromises();
  expect(screen.getByTitle('Office 只读版本预览').getAttribute('name')).toBe(`office-${newId}`);
  pending.resolve(launch()); await flushPromises();
  expect(screen.getByTitle('Office 只读版本预览').getAttribute('name')).toBe(`office-${newId}`);
  expect(f.json.mock.calls.filter(([, options]) => options?.method === 'DELETE').map(([url]) => url))
    .toEqual([`/api/office-preview/sessions/${id}`]);
  expect(HTMLFormElement.prototype.submit).toHaveBeenCalledOnce();
});

it.each(['info', 'create'])('主动关闭 %s 请求不会被报告为超时', async stage => {
  vi.useFakeTimers();
  const pending = deferred<unknown>();
  f.json.mockImplementation(async (url: string) => url.endsWith('/preview-info') && stage !== 'info' ? descriptor() : pending.promise);
  const view = render(VersionPreview, { props: { path, onClose: () => {} } });
  await flushPromises();
  const signal = f.json.mock.calls.at(-1)![1].signal;
  await view.rerender({ path: null });
  expect(signal.aborted).toBe(true);
  await vi.advanceTimersByTimeAsync(60000); await nextTick();
  expect(screen.queryByText(/打开结果尚未确认/)).toBeNull();
  expect(screen.queryByRole('button', { name: '重试预览' })).toBeNull();
});

it.each([404, 405])('已知 Office 的旧端点 %i 只提示服务能力，不误判撤权或读取原件', async status => {
  const denied = vi.fn();
  f.json.mockRejectedValue(new ApiError(status));
  render(VersionPreview, { props: { path, fileName: '原件.docx', onClose: () => {}, onDenied: denied } });
  await screen.findByText(status === 404 ? '服务不支持此预览，或该版本当前不可访问。' : '当前服务尚未支持 Office 预览。');
  expect(denied).not.toHaveBeenCalled();
  expect(f.send).not.toHaveBeenCalled();
});

it.each([401, 403, 404])('旧后端实际文件返回 %i 仍执行拒绝清理', async status => {
  const denied = vi.fn();
  f.json.mockRejectedValue(new ApiError(405));
  f.send.mockResolvedValue(new Response(null, { status }));
  render(VersionPreview, { props: { path, onClose: () => {}, onDenied: denied } });
  await waitFor(() => expect(denied).toHaveBeenCalledOnce());
  expect(screen.queryByText('当前服务尚未支持 Office 预览。')).toBeNull();
});
