import { expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, waitFor } from '@testing-library/vue';
import SessionHost from './SessionHost.vue';
import { useResource } from './useResource';
import { Session } from './session';
import { ApiError } from './api';
import { deferred, json, me, storage } from './test/fixtures';
import { renderHook, renderHost } from './test/render';

it('页面隐藏挂起，聚焦/恢复重新确认，卸载移除监听', async () => {
  const session = { refresh: vi.fn(), suspend: vi.fn() } as unknown as Session;
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const view = renderHost(SessionHost, { props: { session }, slots: { default: '<div>内容探针</div>' } });
  expect(session.refresh).toHaveBeenCalledOnce(); visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange')); expect(session.suspend).toHaveBeenCalledOnce();
  await fireEvent(window, new Event('focus')); expect(session.refresh).toHaveBeenCalledOnce(); visibility.mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange')); expect(session.refresh).toHaveBeenCalledTimes(2);
  view.unmount(); await fireEvent(window, new Event('focus')); expect(session.refresh).toHaveBeenCalledTimes(2); expect(session.suspend).toHaveBeenCalledTimes(2);
});
it('切换上下文拒绝旧 Promise；刷新失败不回退旧正文', async () => {
  const first = deferred<string>(), retry = deferred<string>(), load = vi.fn().mockReturnValueOnce(first.promise).mockResolvedValueOnce('B 正文').mockReturnValueOnce(retry.promise);
  const hook = renderHook((props: { key: string }) => useResource(load, () => props.key), { initialProps: { key: 'A' } }); await hook.rerender({ key: 'B' }); await waitFor(() => expect(hook.result.current.data).toBe('B 正文'));
  first.resolve('A 迟到正文'); await flushPromises(); expect(hook.result.current.data).toBe('B 正文'); expect(load.mock.calls[0][0].aborted).toBe(true);
  hook.result.current.reload(); expect(hook.result.current.data).toBeUndefined(); retry.reject(new ApiError(503)); await flushPromises(); expect(hook.result.current.data).toBeUndefined(); expect(hook.result.current.error).toBe('服务暂不可用，请稍后重试。');
});
it('403 强制改密中止既有流，清除旧身份可达内容', async () => {
  const cancel = vi.fn(), held = new ReadableStream<Uint8Array>({ cancel });
  const transport = vi.fn().mockResolvedValueOnce(json(me)).mockResolvedValueOnce(new Response(held)).mockResolvedValueOnce(new Response(null, { status: 403 })).mockResolvedValueOnce(json({ ...me, mustChangePassword: true }));
  const session = new Session(storage(), transport); await session.refresh(); const response = await session.send('/api/ai/conversations/1/turns'); const reading = response.body!.getReader().read(); const assertion = expect(reading).rejects.toMatchObject({ name: 'AbortError' });
  await expect(session.json('/api/documents')).rejects.toBeDefined(); await assertion; expect(cancel).toHaveBeenCalledOnce(); expect(session.getSnapshot().phase).toBe('password');
});
