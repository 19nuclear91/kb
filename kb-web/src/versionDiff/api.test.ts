import { expect, it, vi } from 'vitest';
import { decodeJson, encodeJson } from '../api';
import type { Session } from '../session';
import { diff } from '../test/versionDiff';
import { decodeDiff, requestDiff, statuses, validComparison } from './api';

it.each(statuses)('解析 %s，只有 AVAILABLE 接收差异明细', status => {
  const result = decodeDiff({ ...diff, status, ...(status === 'AVAILABLE' ? {} : { stats: '无效', operations: [{ op: 'SECRET' }], valueChanges: '无效' }) });
  expect(result.status).toBe(status); expect(result.operations).toHaveLength(status === 'AVAILABLE' ? 1 : 0);
  if (status !== 'AVAILABLE') expect(result.stats).toBeNull();
});
it('解析缺失侧、解析器差异和截断提示；拒绝未知类型或缺少侧', () => {
  expect(decodeDiff({ ...diff, missingSides: ['BASE'], warnings: [{ code: 'PARSER_DIFFERS', side: null }, { code: 'TRUNCATED', side: 'TARGET' }] }).warnings).toHaveLength(2);
  for (const patch of [{ status: 'UNKNOWN' }, { warnings: [{ code: 'SECRET' }] }, { warnings: [{ code: 'TRUNCATED' }] },
    { operations: [{ ...diff.operations[0], op: 'UNKNOWN' }] }, { operations: [{ ...diff.operations[0], base: null }] }])
    expect(() => decodeDiff({ ...diff, ...patch })).toThrow();
});
it('ID 保持精确，数值只允许引用修改操作；非法地址不发送', async () => {
  const value = decodeJson(encodeJson({ ...diff, documentId: 9223372036854775807n, base: { ...diff.base, versionId: 9223372036854775806n } }));
  expect(decodeDiff(value).documentId).toBe('9223372036854775807');
  expect(() => decodeDiff({ ...diff, valueChanges: [{ type: 'MONEY', before: '5元', after: '8元', operationIndex: 9, headingPath: null }] })).toThrow();
  for (const ids of [['0', '20', '21'], ['10', 'x', '21'], ['10', '21', '21'], ['10', '-1', '21']]) expect(validComparison(...ids as [string, string, string])).toBe(false);
  const send = vi.fn(), session = { send } as unknown as Session;
  await expect(requestDiff(session, '10', '21', '21')).rejects.toMatchObject({ status: 400 }); expect(send).not.toHaveBeenCalled();
});
