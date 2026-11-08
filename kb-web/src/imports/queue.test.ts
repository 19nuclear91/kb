import { expect, it, vi } from 'vitest';
import { ImportQueue, type QueueApi } from './queue';
import { ImportError, type ImportItem, type Receipt } from './api';
import type { Session } from '../session';
import { deferred } from '../test/fixtures';
const receipt: Receipt = { documentId: '10', versionId: '20', parseTaskId: '30' };
function item(id: string): ImportItem { return { itemId: id, importId: '9', ordinal: Number(id), revision: 0n, state: 'PENDING', canUpload: true,
  file: { itemKey: id, fileName: 'x.txt', title: '标题', sizeBytes: 5, sha256: 'a'.repeat(64) } }; }
function setup() {
  const callbacks = new Set<() => void>(); let epoch = 1;
  const session = { getSnapshot: () => ({ epoch, phase: 'ready' }), subscribe: (fn: () => void) => { callbacks.add(fn); return () => callbacks.delete(fn); } } as unknown as Session;
  const pending: { resolve: (value: Receipt) => void; reject: (error: Error) => void }[] = [];
  const api: QueueApi = { item: vi.fn(async (_s, _batch, id) => item(id)), upload: vi.fn(() => new Promise<Receipt>((resolve, reject) => pending.push({ resolve, reject }))) };
  const queue = new ImportQueue(session, '9', 2, api); queue.setItems(['1', '2', '3'].map(item));
  ['1', '2', '3'].forEach(id => queue.attach(id, new File(['hello'], 'x.txt')));
  return { queue, api, pending, change: () => { epoch++; callbacks.forEach(fn => fn()); } };
}
it('重复开始同项互斥，最多两项在途，普通失败后继续余项', async () => {
  const { queue, api, pending } = setup(); queue.start(); queue.start();
  await vi.waitFor(() => expect(pending).toHaveLength(2));
  pending[0]!.reject(new ImportError(415)); pending[1]!.resolve(receipt);
  await vi.waitFor(() => expect(pending).toHaveLength(3)); pending[2]!.resolve(receipt);
  await vi.waitFor(() => expect(queue.getSnapshot().active).toBe(0));
  expect(api.upload).toHaveBeenCalledTimes(3); expect(queue.getSnapshot().rows.filter(row => row.item.state === 'ACCEPTED')).toHaveLength(2);
  queue.dispose();
});
it('暂停只停止新增发送，已发结果仍可受理', async () => {
  const { queue, api, pending } = setup(); queue.start(); await vi.waitFor(() => expect(pending).toHaveLength(2)); queue.pause();
  pending.forEach(request => request.resolve(receipt)); await vi.waitFor(() => expect(queue.getSnapshot().active).toBe(0));
  expect(api.upload).toHaveBeenCalledTimes(2); expect(queue.getSnapshot().rows[2]!.item.state).toBe('PENDING'); queue.dispose();
});
it('429 暂停并等待主动继续，未知核对成功不重传', async () => {
  const { queue, api, pending } = setup(); queue.start(); await vi.waitFor(() => expect(pending).toHaveLength(2));
  vi.mocked(api.item).mockImplementation(async (_s, _b, id) => ({ ...item(id), state: 'ACCEPTED', canUpload: false, receipt }));
  pending[0]!.reject(new ImportError(429)); pending[1]!.resolve(receipt);
  await vi.waitFor(() => expect(queue.getSnapshot().active).toBe(0)); expect(api.upload).toHaveBeenCalledTimes(2);
  expect(queue.getSnapshot().notice).toContain('上传容量'); expect(queue.getSnapshot().rows[0]!.item.receipt).toEqual(receipt); queue.dispose();
});
it('换号清空文件和回执，迟到响应不调度或串号', async () => {
  const { queue, api, pending, change } = setup(); queue.start(); await vi.waitFor(() => expect(pending).toHaveLength(2)); change();
  pending.forEach(request => request.resolve(receipt)); await Promise.resolve();
  expect(queue.getSnapshot().rows).toEqual([]); expect(api.upload).toHaveBeenCalledTimes(2);
});
it('旧尝试 409 后只核对，主动继续使用最新 revision 与新 requestId', async () => {
  const { queue, api, pending } = setup(); queue.start(['1']); await vi.waitFor(() => expect(pending).toHaveLength(1));
  vi.mocked(api.item).mockImplementation(async (_s, _b, id) => ({ ...item(id), state: 'FAILED', revision: 2n, failureCode: 'EXPIRED' }));
  pending[0]!.reject(new ImportError(409)); await vi.waitFor(() => expect(queue.getSnapshot().active).toBe(0));
  expect(api.upload).toHaveBeenCalledTimes(1); queue.start(['1']); await vi.waitFor(() => expect(pending).toHaveLength(2));
  const calls = vi.mocked(api.upload).mock.calls; expect(calls[1]![1].revision).toBe(2n); expect(calls[1]![3]).not.toBe(calls[0]![3]);
  pending[1]!.resolve(receipt); await vi.waitFor(() => expect(queue.getSnapshot().active).toBe(0)); queue.dispose();
});
it('核对期间资格拒绝停止调度并清理旧清单和文件', async () => {
  const { queue, api, pending } = setup(); queue.start(); await vi.waitFor(() => expect(pending).toHaveLength(2));
  vi.mocked(api.item).mockRejectedValue(new ImportError(403));
  await expect(queue.check('3')).rejects.toMatchObject({ status: 403 });
  expect(queue.getSnapshot().rows.every(row => !row.item.file && !row.selected)).toBe(true);
  pending.forEach(request => request.resolve(receipt)); await vi.waitFor(() => expect(queue.getSnapshot().active).toBe(0));
  expect(api.upload).toHaveBeenCalledTimes(2); expect(queue.getSnapshot().rows.every(row => !row.item.file)).toBe(true); queue.dispose();
});

it.each([
  ['page', 0n], ['check', 0n], ['send', 0n], ['page', 4n], ['check', 4n], ['send', 4n],
] as const)('%s 的 revision=%s 最小投影清理全任务，保留已有受理事实', async (source, revision) => {
  const { queue, api } = setup();
  const newer: ImportItem = { ...item('1'), revision: 4n, state: 'ACCEPTED', canUpload: false, receipt };
  const minimal = { ...item('1'), revision, file: undefined, canUpload: false };
  try {
    if (source === 'send') {
      const reading = deferred<ImportItem>(); vi.mocked(api.item).mockReturnValueOnce(reading.promise);
      queue.start(['1']); queue.setItems([newer]); reading.resolve(minimal);
      await vi.waitFor(() => expect(queue.getSnapshot().active).toBe(0));
    } else {
      queue.setItems([newer]);
      if (source === 'page') queue.setItems([minimal]);
      else { vi.mocked(api.item).mockResolvedValueOnce(minimal); await queue.check('1').catch(() => {}); }
    }
    expect(queue.getSnapshot().denied).toBe(true);
    expect(queue.getSnapshot().rows.every(row => !row.item.file && !row.selected)).toBe(true);
    expect(queue.getSnapshot().rows[0]!.item).toMatchObject({ state: 'ACCEPTED', revision: 4n, receipt });
    expect(api.upload).not.toHaveBeenCalled();
  } finally { queue.dispose(); }
});

it('同 revision 的最小页投影也清理其他页的文件，暂停全部后续发送', () => {
  const { queue, api } = setup();
  try {
    queue.setItems([{ ...item('1'), file: undefined, canUpload: false }, item('2')]);
    expect(queue.getSnapshot().denied).toBe(true);
    expect(queue.getSnapshot().rows.every(row => !row.item.file && !row.selected)).toBe(true);
    queue.start(); expect(api.item).not.toHaveBeenCalled(); expect(api.upload).not.toHaveBeenCalled();
  } finally { queue.dispose(); }
});

it.each(['check', 'send', 'receipt'] as const)('新核对恢复资格后，旧 %s 结果仍不能带回描述或选中文件', async source => {
  const { queue, api, pending } = setup();
  const oldGeneration = queue.captureAccess(), late = deferred<ImportItem>();
  let checking: Promise<ImportItem | null> | undefined;
  try {
    if (source === 'receipt') {
      queue.start(['1']); await vi.waitFor(() => expect(pending).toHaveLength(1));
    } else {
      vi.mocked(api.item).mockReturnValueOnce(late.promise);
      if (source === 'check') checking = queue.check('1');
      else queue.start(['1']);
    }
    queue.revoke();
    expect(queue.authorize(oldGeneration)).toBe(false);
    const currentGeneration = queue.captureAccess();
    expect(queue.authorize(currentGeneration)).toBe(true);
    queue.setItems([item('2'), item('3')], currentGeneration);
    queue.attach('2', new File(['hello'], 'x.txt'), oldGeneration);
    expect(queue.getSnapshot().rows.every(row => !row.selected)).toBe(true);
    if (source === 'receipt') pending[0]!.resolve(receipt);
    else late.resolve(source === 'check' ? { ...item('1'), state: 'ACCEPTED', canUpload: false, receipt } : item('1'));
    if (checking) expect((await checking)?.file).toBeUndefined();
    await vi.waitFor(() => expect(queue.getSnapshot().active).toBe(0));
    expect(queue.getSnapshot().denied).toBe(false);
    expect(queue.getSnapshot().rows[0]!.item.file).toBeUndefined();
    expect(queue.getSnapshot().rows[1]!.item.file).toBeDefined();
    expect(queue.getSnapshot().rows.every(row => !row.selected)).toBe(true);
    if (source !== 'send') expect(queue.getSnapshot().rows[0]!.item.receipt).toEqual(receipt);
    expect(api.upload).toHaveBeenCalledTimes(source === 'receipt' ? 1 : 0);
  } finally { queue.dispose(); }
});
