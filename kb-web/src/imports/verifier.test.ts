import { afterEach, expect, it, vi } from 'vitest';
import { ImportQueue } from './queue';
import { ImportVerifier } from './verifier';
import { ImportError, type ImportItem } from './api';
import type { Session } from '../session';
const item: ImportItem = { importId: '9', itemId: '2', ordinal: 1, revision: 1n, state: 'PROCESSING', canUpload: false };
function setup() {
  vi.useFakeTimers();
  const api = { item: vi.fn(async () => item), upload: vi.fn() };
  const session = { getSnapshot: () => ({ phase: 'ready', epoch: 1 }), subscribe: () => () => {} } as unknown as Session;
  const queue = new ImportQueue(session, '9', 2, api); queue.setItems([item]);
  const verifier = new ImportVerifier(queue, () => Date.now(), () => true); return { queue, verifier, api };
}
afterEach(() => vi.useRealTimers());
it('处理中只读，连续三次失败停止，手动核对可开启新窗口', async () => {
  const { queue, verifier, api } = setup(); api.item.mockRejectedValue(new ImportError(503));
  await vi.advanceTimersByTimeAsync(12000); expect(api.item).toHaveBeenCalledTimes(3);
  await vi.advanceTimersByTimeAsync(180000); expect(api.item).toHaveBeenCalledTimes(3); expect(api.upload).not.toHaveBeenCalled();
  api.item.mockResolvedValue({ ...item, state: 'ACCEPTED', receipt: { documentId: '10', versionId: '20', parseTaskId: '30' } });
  await verifier.manual('2'); expect(queue.getSnapshot().rows[0]!.item.state).toBe('ACCEPTED'); expect(api.upload).not.toHaveBeenCalled();
  verifier.dispose(); queue.dispose();
});
it('单次核对最多三分钟，终止后不自动重启或发送文件', async () => {
  const { queue, verifier, api } = setup();
  await vi.advanceTimersByTimeAsync(180000); const count = api.item.mock.calls.length;
  expect(count).toBe(59); await vi.advanceTimersByTimeAsync(180000); expect(api.item).toHaveBeenCalledTimes(count);
  queue.start(); expect(api.upload).not.toHaveBeenCalled(); verifier.dispose(); queue.dispose();
});
