import { afterEach, expect, it, vi } from 'vitest';
import type { Session } from '../session';
import { validityApi } from '../validity/api';
import { countLabel, pollInterval, StewardshipPoller } from './poller';

afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); });
it('99+、?、零数量与轮询配置保持精确边界', () => {
  expect(countLabel({ phase: 'ready', count: '9007199254740993', asOfDate: '2026-11-15' })).toBe('99+');
  expect(countLabel({ phase: 'unavailable' })).toBe('?'); expect(countLabel({ phase: 'ready', count: '0', asOfDate: '2026-11-15' })).toBe('');
  expect(pollInterval(1)).toBe(60000); expect(pollInterval('invalid')).toBe(300000);
});
it('共享读取、后台暂停与切换账号清除并丢弃旧请求', async () => {
  vi.useFakeTimers(); let done!: (value: { count: string; asOfDate: string }) => void;
  const read = vi.spyOn(validityApi, 'count').mockImplementation(() => new Promise(resolve => { done = resolve; }));
  const refresh = vi.fn().mockResolvedValue(undefined), poller = new StewardshipPoller({ refresh } as unknown as Session);
  const detach = poller.attach(1, true), second = poller.attach(1, true); await Promise.resolve(); expect(read).toHaveBeenCalledOnce();
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange'));
  expect(poller.getSnapshot().phase).toBe('idle'); done({ count: '99', asOfDate: '2026-11-15' }); await Promise.resolve(); await Promise.resolve();
  expect(poller.getSnapshot().phase).toBe('idle');
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange')); await Promise.resolve(); expect(refresh).toHaveBeenCalledOnce();
  const switched = poller.attach(2, true); expect(countLabel(poller.getSnapshot())).toBe(''); await Promise.resolve(); expect(read).toHaveBeenCalledTimes(2);
  done({ count: '1', asOfDate: '2026-11-15' }); await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  expect(countLabel(poller.getSnapshot())).toBe('1'); switched(); second(); detach(); expect(poller.getSnapshot().phase).toBe('idle');
});
