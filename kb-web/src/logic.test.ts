import { afterEach, expect, it, vi } from 'vitest';
import { bindTurn, citationPath, mergeTurns, pendingProgress, progressLabel, reconcileTurn, retryable, usageLabel } from './logic';
import { turn } from './test/fixtures';

afterEach(() => vi.useRealTimers());
it('版本状态不混淆发布、维护、退避和失败，不生成百分比', () => {
  expect(progressLabel({ indexStatus: 'indexed' })).toBe('解析完成');
  expect(progressLabel({ indexStatus: 'pending', waitingReason: 'EMBEDDING_MAINTENANCE' })).toBe('维护等待');
  expect(progressLabel({ nextAttemptAt: new Date(5000).toISOString() }, 0)).toBe('退避等待');
  expect(progressLabel({ taskStatus: 'running' })).toBe('处理中');
  expect(progressLabel({ taskStatus: 'failed' })).toBe('解析失败');
  expect(pendingProgress({ indexStatus: 'indexed' })).toBe(false); expect(pendingProgress({ indexStatus: 'pending' })).toBe(true);
  expect(retryable({ taskStatus: 'running', deadLetterStatus: 'OPEN' })).toBe(false);
  expect(retryable({ attempts: 3, maxAttempts: 3 })).toBe(true);
  expect(retryable({ indexStatus: 'indexed' })).toBe(false);
});
it('引用必须有固定三元组，不补最新版本', () => {
  expect(citationPath({ docId: '1', versionId: '2', chunkId: '3' })).toBe('/api/documents/1/versions/2/citations/3');
  expect(citationPath({ documentId: '1', versionId: null, chunkId: '3' })).toBeNull();
});
it('序号精确合并，BLOCKED 覆盖全部旧助手内容，未知用量不转零', () => {
  const blocked = { ...turn, visibility: 'BLOCKED', answer: '不应显示', citations: [{ docId: '1', chunkId: '3', versionId: '2' }] };
  const result = mergeTurns([turn, { ...turn, id: 'new', turnSeq: 9007199254740993n }], [blocked]);
  expect(result).toHaveLength(2); expect(result[0]).toMatchObject({ answer: null, citations: [], question: '原问题' });
  expect(result[1].turnSeq).toBe(9007199254740993n); expect(usageLabel(turn)).toBe('真实用量未知；扣除额度 9');
});
it('匹配重试绑定原问题、标识和序号，Unicode 限额不静默截断', () => {
  const first = bindTurn('😀'.repeat(8000), 9007199254740993n);
  expect(bindTurn(first.question, first.expectedLastTurnSeq, first)).toBe(first);
  expect(Object.keys(first).sort()).toEqual(['expectedLastTurnSeq', 'question', 'requestId']);
  expect(bindTurn('新问题', 2n, first).requestId).not.toBe(first.requestId);
  expect(() => bindTurn('😀'.repeat(8001), 0n)).toThrow(); expect(() => bindTurn('  ', 0n)).toThrow();
});
it('RUNNING 有上限核对，停止与完成竞争采用持久状态', async () => {
  vi.useFakeTimers(); const load = vi.fn().mockResolvedValue({ ...turn, status: 'RUNNING' }), onTurn = vi.fn();
  const operation = reconcileTurn(load, new AbortController().signal, onTurn, 3); await vi.runAllTimersAsync();
  expect(await operation).toBe(false); expect(load).toHaveBeenCalledTimes(3);
  load.mockReset().mockResolvedValue({ ...turn, status: 'COMPLETED' });
  expect(await reconcileTurn(load, new AbortController().signal, onTurn)).toBe(true); expect(onTurn.mock.lastCall?.[0].status).toBe('COMPLETED');
});
it('退出/卸载取消核对，不继续保护请求', async () => {
  vi.useFakeTimers(); const controller = new AbortController(), load = vi.fn().mockResolvedValue({ ...turn, status: 'RUNNING' });
  const operation = reconcileTurn(load, controller.signal, vi.fn()); const assertion = expect(operation).rejects.toMatchObject({ name: 'AbortError' });
  await Promise.resolve(); controller.abort(); await vi.runAllTimersAsync(); await assertion; expect(load).toHaveBeenCalledTimes(1);
});
