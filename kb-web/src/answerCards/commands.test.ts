import { expect, it, vi } from 'vitest';
import type { Session } from '../session';
import { deferred, me } from '../test/fixtures';
import { cardApi } from './api';
import { cardCommands } from './commands';
import { CardError } from './protocol';
import { receipt } from './fixtures';
import type { Intent, Receipt } from './types';
const intent: Intent = { operation: 'ANSWER_CARD_SAVE', cardId: receipt.cardId, reviewId: null, payload: { spaceId: '9', expectedCardVersion: '2', branchId: null, revisionId: '21', content: { answer: '私有草稿' } } };
function session() {
  const state = { phase: 'ready', me, epoch: 1 }, listeners = new Set<() => void>();
  const value = { getSnapshot: () => state, subscribe: (listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); } } as unknown as Session;
  return { value, change: () => { state.epoch++; listeners.forEach(listener => listener()); } };
}
it('关闭重开仍持原意图，NOT_FOUND不解冻，重试使用同标识载荷', async () => {
  const auth = session(), store = cardCommands(auth.value), key = `card:${receipt.cardId}`;
  vi.spyOn(cardApi, 'write').mockRejectedValueOnce(new CardError(0)).mockResolvedValueOnce(receipt);
  vi.spyOn(cardApi, 'command').mockResolvedValue({ status: 'NOT_FOUND', receipt: null });
  await store.start(key, intent); expect(store.entries.get(key)?.phase).toBe('unknown');
  expect(cardCommands(auth.value)).toBe(store);
  await store.check(key); expect(store.entries.get(key)?.phase).toBe('unknown');
  expect(() => store.start(key, intent)).toThrow();
  await store.retry(key); expect(store.entries.get(key)?.phase).toBe('committed');
  const calls = vi.mocked(cardApi.write).mock.calls;
  expect(calls[1]?.[1]).toEqual(calls[0]?.[1]); expect(calls[1]?.[2]).toBe(calls[0]?.[2]);
});
it('显式放弃后迟到成功不能恢复原回执，换号清空全部意图', async () => {
  const auth = session(), store = cardCommands(auth.value), late = deferred<Receipt>();
  vi.spyOn(cardApi, 'write').mockReturnValueOnce(late.promise).mockRejectedValueOnce(new CardError(0));
  const pending = store.start('card:1', intent); store.abandon('card:1'); late.resolve(receipt);
  expect(await pending).toEqual({ kind: 'stale' }); expect(store.entries.size).toBe(0);
  await store.start('card:2', intent); expect(store.entries.size).toBe(1); auth.change(); expect(store.entries.size).toBe(0);
});
it('未知创建在未取得卡片ID时仍可主动核对，撤权清掉原载荷', async () => {
  const auth = session(), store = cardCommands(auth.value);
  vi.spyOn(cardApi, 'write').mockRejectedValue(new CardError(0));
  vi.spyOn(cardApi, 'command').mockResolvedValueOnce({ status: 'COMMITTED', receipt }).mockRejectedValueOnce(new CardError(403));
  const create: Intent = { operation: 'ANSWER_CARD_CREATE', cardId: null, reviewId: null, payload: { spaceId: '9', content: {} } };
  await store.start('create:9', create); expect((await store.check('create:9')).receipt?.cardId).toBe(receipt.cardId);
  store.acknowledge('create:9'); await store.start('create:9', create); expect(await store.check('create:9')).toEqual({ kind: 'rejected', status: 403 });
  expect(store.entries.size).toBe(0);
});
