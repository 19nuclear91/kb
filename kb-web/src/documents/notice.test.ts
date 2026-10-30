import { afterEach, expect, it, vi } from 'vitest';
import type { SessionState } from '../session';
import { consumeDocumentDeleted, rememberDocumentDeleted } from './notice';

afterEach(() => vi.useRealTimers());
function fixture() {
  const state: SessionState = { phase: 'ready', epoch: 1, me: null };
  return { state, session: { getSnapshot: () => state } };
}

it('删除提示只在当前会话消费一次，不使用浏览器持久存储', () => {
  const f = fixture(), saved = vi.spyOn(Storage.prototype, 'setItem');
  rememberDocumentDeleted(f.session);
  expect(consumeDocumentDeleted(f.session)).toBe(true); expect(consumeDocumentDeleted(f.session)).toBe(false);
  expect(saved).not.toHaveBeenCalled();
});

it('提示过期、换身份或更换 Session 对象后不恢复旧成功', () => {
  vi.useFakeTimers();
  const f = fixture(); rememberDocumentDeleted(f.session); vi.advanceTimersByTime(10001);
  expect(consumeDocumentDeleted(f.session)).toBe(false);
  rememberDocumentDeleted(f.session); f.state.epoch++;
  expect(consumeDocumentDeleted(f.session)).toBe(false);
  rememberDocumentDeleted(f.session);
  expect(consumeDocumentDeleted({ getSnapshot: () => f.state })).toBe(false);
  f.state.phase = 'anonymous'; expect(consumeDocumentDeleted(f.session)).toBe(false);
});
