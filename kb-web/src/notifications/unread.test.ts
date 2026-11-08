import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { defineComponent, h as render$, nextTick, reactive } from 'vue';
import { fireEvent, render, screen } from '@testing-library/vue';
import { deferred } from '../test/fixtures';
import { pollInterval, useUnreadCount } from './unread';
import UnreadBadge from './UnreadBadge.vue';

const h = reactive({ value: { session: {} as unknown, state: { phase: 'ready', epoch: 1 } as { phase: string; epoch: number } } });
vi.mock('../SessionProvider', () => ({ useSession: () => ({ get session() { return h.value.session; }, get state() { return h.value.state; } }) }));

const session = () => ({ json: vi.fn(), refresh: vi.fn().mockResolvedValue(undefined), getSnapshot: () => h.value.state });
const count = (value: string) => ({ count: value, asOf: '2026-10-25T00:00:00Z' });
const calls = (s: ReturnType<typeof session>) => s.json.mock.calls.filter(([path]) => path === '/api/notifications/unread-count').length;
const advance = async (ms: number) => { await vi.advanceTimersByTimeAsync(ms); await nextTick(); };
const Refresh = defineComponent({ setup() { const unread = useUnreadCount(); return () => render$('button', { onClick: unread.refresh }, '刷新计数'); } });
function use(phase: string, epoch: number) { const s = session(); h.value = { session: s, state: { phase, epoch } }; return s; }

beforeEach(() => { vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }); });
afterEach(() => { vi.useRealTimers(); });

it('同一标签页共享单个在途请求，默认 60 秒低频轮询', async () => {
  const s = use('ready', 1), first = deferred<unknown>();
  s.json.mockReturnValueOnce(first.promise).mockResolvedValue(count('2'));
  render({ setup: () => () => [render$(UnreadBadge), render$(UnreadBadge), render$(Refresh)] });
  await advance(0);
  expect(calls(s)).toBe(1);
  await fireEvent.click(screen.getByRole('button', { name: '刷新计数' })); await fireEvent.click(screen.getByRole('button', { name: '刷新计数' }));
  expect(calls(s)).toBe(1);
  first.resolve(count('3')); await advance(0);
  expect(calls(s)).toBe(2);
  expect(screen.getAllByLabelText('未读 2 条')).toHaveLength(2);
  await advance(59_999); expect(calls(s)).toBe(2);
  await advance(1); expect(calls(s)).toBe(3);
});

it('配置间隔不得低于 30 秒，非法值回落到 60 秒', () => {
  expect(pollInterval('1000')).toBe(30_000);
  expect(pollInterval('45000')).toBe(45_000);
  for (const bad of ['abc', '-5', '0', '']) expect(pollInterval(bad)).toBe(60_000);
});

it('隐藏时取消请求并清除旧数，后台无查询；恢复先核对身份，新代次后才读取', async () => {
  const s = use('ready', 1); s.json.mockResolvedValue(count('4'));
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  render(UnreadBadge);
  await advance(0); expect(screen.getByLabelText('未读 4 条')).toBeTruthy();
  const late = deferred<unknown>(); s.json.mockReturnValue(late.promise);
  await advance(60_000);
  const signal = s.json.mock.calls.at(-1)![1].signal as AbortSignal;
  visibility.mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange')); await nextTick();
  expect(signal.aborted).toBe(true); expect(screen.queryByLabelText(/未读/)).toBeNull();
  const before = calls(s);
  await advance(300_000); expect(calls(s)).toBe(before);
  visibility.mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange')); await nextTick();
  await advance(0);
  expect(s.refresh).toHaveBeenCalledTimes(1); expect(calls(s)).toBe(before);
  late.resolve(count('9')); await advance(0); expect(screen.queryByLabelText('未读 9 条')).toBeNull();
  s.json.mockResolvedValue(count('1')); h.value = { session: s, state: { phase: 'ready', epoch: 2 } };
  await advance(0);
  expect(calls(s)).toBe(before + 1); expect(screen.getByLabelText('未读 1 条')).toBeTruthy();
});

it('失败与离线显示待确认，不把旧数字当作当前事实；恢复联网先核对身份', async () => {
  const s = use('ready', 1);
  s.json.mockResolvedValueOnce(count('5')).mockRejectedValueOnce(new Error('503'));
  render(UnreadBadge);
  await advance(0); expect(screen.getByLabelText('未读 5 条')).toBeTruthy();
  await advance(60_000);
  expect(screen.queryByLabelText('未读 5 条')).toBeNull(); expect(screen.getByLabelText('未读数待确认')).toBeTruthy();
  s.json.mockResolvedValue(count('6'));
  window.dispatchEvent(new Event('offline')); await nextTick();
  expect(screen.getByLabelText('未读数待确认')).toBeTruthy();
  const before = calls(s);
  await advance(120_000); expect(calls(s)).toBe(before);
  window.dispatchEvent(new Event('online')); await advance(0);
  expect(s.refresh).toHaveBeenCalledTimes(1); expect(calls(s)).toBe(before);
});

it('身份未确认、匿名、强制改密或身份故障时不请求计数', async () => {
  for (const phase of ['loading', 'anonymous', 'password', 'unavailable']) {
    const s = use(phase, 3);
    const view = render(UnreadBadge); await advance(120_000);
    expect(s.json).not.toHaveBeenCalled(); view.unmount();
  }
});

it('账号切换后旧计数迟到也不填充新身份', async () => {
  const s = use('ready', 1), old = deferred<unknown>();
  s.json.mockReturnValueOnce(old.promise).mockResolvedValue(count('1'));
  render(UnreadBadge); await advance(0);
  h.value = { session: s, state: { phase: 'ready', epoch: 2 } }; await advance(0);
  old.resolve(count('77')); await advance(0);
  expect(screen.queryByLabelText('未读 77 条')).toBeNull(); expect(screen.getByLabelText('未读 1 条')).toBeTruthy();
  expect((s.json.mock.calls[0][1].signal as AbortSignal).aborted).toBe(true);
});

it('超过 99 条显示 99+，可访问名称保留精确值', async () => {
  const s = use('ready', 1); s.json.mockResolvedValue(count('9223372036854775807'));
  render(UnreadBadge); await advance(0);
  expect(screen.getByLabelText('未读 9223372036854775807 条').textContent).toBe('99+');
});
