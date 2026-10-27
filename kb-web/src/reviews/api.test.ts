import { expect, it, vi } from 'vitest';
import { decodeJson, encodeJson } from '../api';
import { exact, policyIntent, reviewIntent, reviewPaths, reviewWrite, type ReviewState } from './api';
import type { Session } from '../session';
import { json } from '../test/fixtures';
import type { ReviewCommand } from './api';

const state: ReviewState = { documentId: '9223372036854775807', versionId: '9007199254740993', versionNo: 2,
  stateVersion: '9007199254740994', indexStatus: 'indexed', policy: 'REVIEW_REQUIRED', publicationEvidence: 'NONE',
  reviewState: 'IN_REVIEW', reviewId: '11111111-2222-3333-4444-555555555555', roundNo: 1 };
it('版本、轮次和并发值精确绑定，非法标识在发送前拒绝', () => {
  const intent = reviewIntent('approve', state);
  expect(intent.path).toContain(`${state.versionId}/reviews/${state.reviewId}/approve`);
  expect(encodeJson(intent.body)).toBe('{"expectedStateVersion":9007199254740994}');
  expect(encodeJson(policyIntent('7', 'AUTO', '9223372036854775807').body)).toContain('9223372036854775807');
  for (const id of ['0', '-1', '9223372036854775808', '1/2']) expect(() => reviewPaths.status(id, '1')).toThrow();
  expect(() => reviewIntent('withdraw', { ...state, reviewId: 'bad' })).toThrow();
  expect(() => exact(9007199254740992)).toThrow();
  const result = decodeJson<ReviewState>('{"documentId":9223372036854775807,"versionId":9007199254740993,"stateVersion":9007199254740994}');
  expect(result.documentId).toBe(state.documentId); expect(exact(result.stateVersion)).toBe(9007199254740994n);
});
it('只消费固定错误码，不显示服务端原文；不可读成功回执为不确定结果', async () => {
  const send = vi.fn().mockResolvedValueOnce(json({ code: 500, data: { errorCode: 'INDEX_REFRESH_REQUIRED' }, message: '私有正文' }, false, 409))
    .mockResolvedValueOnce(new Response('broken', { headers: { 'Content-Type': 'application/json' } }));
  const session = { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
  await expect(reviewWrite(session, reviewIntent('approve', state), 'r')).rejects.toMatchObject({ status: 409, code: 'INDEX_REFRESH_REQUIRED' });
  await expect(reviewWrite(session, reviewIntent('approve', state), 'r')).rejects.toMatchObject({ status: 0 });
  expect(send.mock.calls[0][1].headers['X-Request-Id']).toBe('r');
});
it('驳回原因规范化，空白及超长拒绝；游标作为参数编码', () => {
  expect(reviewIntent('reject', state, ' e\u0301\r\n原因 ').body?.reason).toBe('é\n原因');
  expect(() => reviewIntent('reject', state, ' ')).toThrow();
  expect(() => reviewIntent('reject', state, '😀'.repeat(2001))).toThrow();
  expect(reviewPaths.list('pending', 'a&b')).toContain('cursor=a%26b');
});
it('成功响应为空或属于其他目标时保持结果不确定', async () => {
  const send = vi.fn().mockResolvedValueOnce(json(null)).mockResolvedValueOnce(json({ documentId: '1', versionId: '2', stateVersion: '3' }));
  const session = { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
  await expect(reviewWrite(session, reviewIntent('approve', state), 'r')).rejects.toMatchObject({ status: 0 });
  await expect(reviewWrite(session, reviewIntent('approve', state), 'r')).rejects.toMatchObject({ status: 0 });
});

const approved = () => ({ documentId: state.documentId, versionId: state.versionId, reviewId: state.reviewId, roundNo: 1,
  stateVersion: '9007199254740995', indexStatus: 'indexed', policy: 'REVIEW_REQUIRED', reviewState: 'APPROVED',
  publicationEvidence: 'PUBLISHED', occurredAt: '2026-10-25T00:00:00.123456Z' });
const execute = (value: unknown, intent = reviewIntent('approve', state)) => reviewWrite({
  send: vi.fn().mockResolvedValue(json(value)), getSnapshot: () => ({ epoch: 1 }),
} as unknown as Session, intent, 'request');
it.each([
  ['approve', {}, {}],
  ['reject', {}, { reviewState: 'REJECTED', publicationEvidence: 'NONE' }],
  ['withdraw', {}, { reviewState: 'WITHDRAWN', publicationEvidence: 'NONE' }],
  ['submit', { reviewState: 'REJECTED' }, { reviewId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', roundNo: 2, reviewState: 'IN_REVIEW', publicationEvidence: 'NONE' }],
  ['submit', { reviewState: 'DRAFT', reviewId: undefined, roundNo: undefined }, { roundNo: 1, reviewState: 'IN_REVIEW', publicationEvidence: 'NONE' }],
  ['refresh', {}, { stateVersion: '9007199254740996', indexStatus: 'pending', reviewState: 'CANCELLED', publicationEvidence: 'NONE' }],
  ['refresh', { reviewState: 'REJECTED' }, { reviewId: null, roundNo: null, indexStatus: 'pending', reviewState: null, publicationEvidence: 'NONE' }],
] as const)('确认 %s 回执匹配命令结果与精确并发推进 %#', async (command, snapshot, result) => {
  const value = { ...approved(), ...result };
  await expect(execute(value, reviewIntent(command, { ...state, ...snapshot }, '需补充'))).resolves.toEqual(value);
});
it.each([
  { documentId: '1' }, { versionId: '1' }, { reviewId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' },
  { reviewId: null, roundNo: null }, { roundNo: 99 }, { roundNo: '1' },
  { stateVersion: state.stateVersion }, { stateVersion: '9007199254740996' }, { stateVersion: 9007199254740992 },
  { policy: 'AUTO' }, { reviewState: 'REJECTED' }, { publicationEvidence: 'NONE' }, { indexStatus: 'pending' },
  { occurredAt: 'bad' }, { occurredAt: null },
])('同版本错误轮次、命令结果或字段形状一律不确定 %j', async patch => {
  await expect(execute({ ...approved(), ...patch })).rejects.toMatchObject({ status: 0 });
});
it.each(['documentId', 'versionId', 'reviewId', 'roundNo', 'stateVersion', 'policy', 'reviewState', 'indexStatus', 'publicationEvidence', 'occurredAt'])('成功回执缺少 %s 仍为不确定', async field => {
  const value: Record<string, unknown> = approved(); delete value[field];
  await expect(execute(value)).rejects.toMatchObject({ status: 0 });
});
it.each([
  ['submit', { reviewId: state.reviewId, roundNo: 2, reviewState: 'IN_REVIEW', publicationEvidence: 'NONE' }],
  ['submit', { reviewId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', roundNo: 1, reviewState: 'IN_REVIEW', publicationEvidence: 'NONE' }],
  ['reject', { reviewState: 'WITHDRAWN', publicationEvidence: 'NONE' }],
  ['withdraw', { reviewState: 'REJECTED', publicationEvidence: 'NONE' }],
  ['refresh', { indexStatus: 'pending', reviewState: 'CANCELLED', publicationEvidence: 'NONE' }],
  ['refresh', { stateVersion: '9007199254740996', reviewState: 'CANCELLED', publicationEvidence: 'NONE' }],
] as const)('拒绝 %s 的错误命令回执 %#', async (command: ReviewCommand, patch) => {
  await expect(execute({ ...approved(), ...patch }, reviewIntent(command, { ...state, reviewState: command === 'submit' ? 'REJECTED' : 'IN_REVIEW' }, '需补充'))).rejects.toMatchObject({ status: 0 });
});
it.each([
  { spaceId: '99' }, { policy: 'AUTO' }, { version: '9007199254740994' }, { version: '9007199254740996' }, { version: undefined },
])('策略回执匹配空间、所选策略和精确版本，否则保持不确定 %j', async patch => {
  const send = vi.fn().mockResolvedValue(json({ spaceId: '9', policy: 'REVIEW_REQUIRED', version: '9007199254740995', ...patch }));
  await expect(reviewWrite({ send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session,
    policyIntent('9', 'REVIEW_REQUIRED', '9007199254740994'), 'p')).rejects.toMatchObject({ status: 0 });
});
it('策略合法回执保留大整数精度', async () => {
  const receipt = { spaceId: '9', policy: 'REVIEW_REQUIRED', version: '9007199254740995' };
  await expect(reviewWrite({ send: vi.fn().mockResolvedValue(json(receipt)), getSnapshot: () => ({ epoch: 1 }) } as unknown as Session,
    policyIntent('9', 'REVIEW_REQUIRED', '9007199254740994'), 'p')).resolves.toEqual(receipt);
});
