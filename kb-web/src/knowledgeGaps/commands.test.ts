import { expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';
import { GapCommands, gapIntent, gapIntentKey, type GapSubmission } from './commands';
import type { gapWrite } from './api';
import type { GapReceipt } from './types';
import { eventId, ready, solutionId } from './testSupport';

const create: GapSubmission = { action: 'create', category: 'OTHER', description: '明确分享', source: 'MANUAL', shareAcknowledged: true };
const receipt: GapReceipt = { gapId: '9', resultVersion: 0n, state: 'SUBMITTED', eventId, solutionId: null, replayed: false };

it('同一逻辑提交规范化键包含目标、动作、版本、资料和当前方案身份', () => {
  expect(gapIntentKey(gapIntent({ ...create, description: '　明确分享　' }))).toBe(gapIntentKey(gapIntent(create)));
  const base = { action: 'confirm', gapId: '9', expectedVersion: 2n, solutionId } as const;
  const key = gapIntentKey(gapIntent(base));
  for (const input of [{ ...base, gapId: '10' }, { ...base, expectedVersion: 3n }, { ...base, solutionId: eventId },
    { ...base, action: 'reject-solution', reason: '未解决' } as const]) expect(gapIntentKey(gapIntent(input))).not.toBe(key);
  expect(gapIntentKey(gapIntent({ action: 'solution', gapId: '9', expectedVersion: 2n, text: '方案' })))
    .not.toBe(gapIntentKey(gapIntent({ action: 'solution', gapId: '9', expectedVersion: 2n, text: '方案', documentId: '1', versionId: '2' })));
});

it('同步互斥阻止双击，确认后不允许无绑定重试', async () => {
  const pending = deferred<GapReceipt>(), execute = vi.fn(() => pending.promise);
  const { session } = await ready();
  const commands = new GapCommands(session, 'create', execute);
  const first = commands.submit(create);
  expect(commands.getSnapshot().phase).toBe('submitting');
  expect(await commands.submit(create)).toEqual({ status: 'ignored' });
  expect(await commands.retry()).toEqual({ status: 'ignored' });
  expect(execute).toHaveBeenCalledTimes(1);
  pending.resolve(receipt);
  expect(await first).toEqual({ status: 'confirmed', value: receipt });
  expect(commands.getSnapshot()).toEqual({ phase: 'confirmed' });
  expect(await commands.retry()).toEqual({ status: 'ignored' });
  commands.dispose();
});

it.each([0, 503])('结果未知 %i 不自动重发；显式匹配重试复用相同标识和冻结载荷', async status => {
  const { session, saved } = await ready();
  const execute = vi.fn().mockRejectedValueOnce(new ApiError(status)).mockResolvedValueOnce({ ...receipt, replayed: true });
  const id = vi.fn(() => eventId), commands = new GapCommands(session, 'create', execute, id);
  expect(await commands.submit(create)).toEqual({ status: 'uncertain' });
  expect(execute).toHaveBeenCalledTimes(1);
  expect(commands.getSnapshot().message).toContain('不会自动重发');
  expect(await commands.retry()).toMatchObject({ status: 'confirmed', value: { replayed: true } });
  expect(execute.mock.calls[1][1]).toBe(execute.mock.calls[0][1]);
  expect(execute.mock.calls[1][2]).toBe(eventId);
  expect(id).toHaveBeenCalledTimes(1);
  expect(saved.setItem).not.toHaveBeenCalled();
  commands.dispose();
});

it('未知结果后同内容提交复用绑定，改输入经新提交使用新标识；原输入修改不污染冻结值', async () => {
  const { session } = await ready();
  const execute = vi.fn().mockRejectedValue(new ApiError(0)), id = vi.fn().mockReturnValueOnce(eventId).mockReturnValueOnce(solutionId);
  const commands = new GapCommands(session, 'create', execute, id);
  const original = { ...create };
  await commands.submit(original);
  original.description = '不同内容';
  await commands.submit(create);
  expect(execute.mock.calls[1][2]).toBe(eventId);
  expect(execute.mock.calls[1][1].body.description).toBe('明确分享');
  await commands.submit(original);
  expect(execute.mock.calls[2][2]).toBe(solutionId);
  expect(execute.mock.calls[2][1].body.description).toBe('不同内容');
  commands.dispose();
});

it('409/404 拒绝清除绑定，不自动覆盖状态、换方案或查询受理目录', async () => {
  const { session } = await ready();
  const execute = vi.fn().mockRejectedValueOnce(new ApiError(409)).mockRejectedValueOnce(new ApiError(404));
  const commands = new GapCommands(session, '9', execute);
  const confirm = { action: 'confirm', gapId: '9', expectedVersion: 2n, solutionId } as const;
  expect(await commands.submit(confirm)).toMatchObject({ status: 'rejected', error: { status: 409 } });
  expect(commands.getSnapshot().status).toBe(409);
  expect(await commands.retry()).toEqual({ status: 'ignored' });
  expect(await commands.submit({ action: 'return', gapId: '9', expectedVersion: 2n, reason: '退回受理' })).toMatchObject({ status: 'rejected', error: { status: 404 } });
  expect(await commands.retry()).toEqual({ status: 'ignored' });
  expect(execute).toHaveBeenCalledTimes(2);
  commands.dispose();
});

it('非法输入在发送前拒绝，不销毁仍可显式核对的旧未知结果绑定', async () => {
  const { session } = await ready(), execute = vi.fn().mockRejectedValue(new ApiError(0));
  const commands = new GapCommands(session, 'create', execute);
  expect(await commands.submit({ ...create, shareAcknowledged: false })).toMatchObject({ status: 'rejected', error: { status: 400 } });
  expect(execute).not.toHaveBeenCalled();
  await commands.submit(create);
  await commands.submit({ ...create, description: '' });
  expect(commands.getSnapshot().phase).toBe('uncertain');
  await commands.retry();
  expect(execute.mock.calls[1][2]).toBe(execute.mock.calls[0][2]);
  commands.dispose();
});

it('清理/切换目标取消旧请求、丢弃绑定，旧发送结束不能污染新状态或解除新互斥', async () => {
  const { session } = await ready(), old = deferred<GapReceipt>(), next = deferred<GapReceipt>();
  const execute = vi.fn().mockReturnValueOnce(old.promise).mockReturnValueOnce(next.promise);
  const commands = new GapCommands(session, 'create', execute);
  const first = commands.submit(create);
  commands.setScope('new');
  expect(execute.mock.calls[0][3].aborted).toBe(true);
  expect(commands.getSnapshot().phase).toBe('idle');
  const second = commands.submit(create);
  old.resolve(receipt);
  expect(await first).toEqual({ status: 'ignored' });
  expect(commands.getSnapshot().phase).toBe('submitting');
  expect(await commands.submit(create)).toEqual({ status: 'ignored' });
  next.resolve(receipt); await second;
  commands.clear();
  expect(await commands.retry()).toEqual({ status: 'ignored' });
  commands.dispose();
});

it('会话 epoch 改变立即清理，迟到回执不写入新身份；重新创建对象不会重发', async () => {
  const { session } = await ready(), pending = deferred<GapReceipt>(), execute = vi.fn<typeof gapWrite>().mockReturnValue(pending.promise);
  const commands = new GapCommands(session, 'create', execute);
  const submitted = commands.submit(create);
  session.clear();
  expect(commands.getSnapshot().phase).toBe('idle');
  expect(execute.mock.calls[0][3].aborted).toBe(true);
  pending.resolve(receipt); expect(await submitted).toEqual({ status: 'ignored' });
  expect(await commands.retry()).toEqual({ status: 'ignored' });
  commands.dispose();
  const refreshed = new GapCommands(session, 'create', execute);
  expect(await refreshed.retry()).toEqual({ status: 'ignored' });
  expect(execute).toHaveBeenCalledTimes(1);
  refreshed.dispose();
});
