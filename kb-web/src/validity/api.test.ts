import { describe, expect, it, vi } from 'vitest';
import { ApiError, decodeJson, encodeJson } from '../api';
import type { Session } from '../session';
import { decodeApplicability, decodeBadge, ValidityBatch, validityApi, validityWrites } from './api';

const badge = { documentId: '9007199254740993', versionId: '9007199254740994', state: 'EXPIRED', effectiveFrom: null, untilKind: 'DATE', effectiveUntil: '2026-12-31', corrected: true, current: false };
const response = (data: unknown) => new Response(encodeJson({ code: 200, data }), { headers: { 'Content-Type': 'application/json' } });
describe('适用期接口', () => {
  it('严格保留身份与版本，拒绝错误类型和日期组合', () => {
    const decoded = decodeBadge(decodeJson('{"documentId":9007199254740993,"versionId":9007199254740994,"state":"EXPIRED","effectiveFrom":null,"untilKind":"DATE","effectiveUntil":"2026-12-31","corrected":true,"current":false}'));
    expect(decoded.documentId).toBe('9007199254740993');
    for (const change of [{ state: 'UNKNOWN' }, { current: 1 }, { effectiveUntil: '2026-02-30' }, { untilKind: 'OPEN_ENDED' }, { documentId: 9007199254740992 }])
      expect(() => decodeBadge({ ...badge, ...change })).toThrow(ApiError);
    expect(() => decodeApplicability(badge)).toThrow(ApiError);
    expect(validityWrites.applicability(badge, { effectiveFrom: null, untilKind: 'DATE', effectiveUntil: '2026-12-31', reason: '原因', expectedApplicabilityVersion: 9007199254740995n }).body!.expectedApplicabilityVersion).toBe(9007199254740995n);
  });
  it('每批不超过100，去重并精确传输ID', async () => {
    const send = vi.fn(async (_: string, options: RequestInit) => { const input = decodeJson<{ items: { documentId: string; versionId: string }[] }>(String(options.body)); expect(input.items.length).toBeLessThanOrEqual(100);
      return response({ items: input.items.map(pair => ({ ...badge, ...pair })), asOfDate: '2027-01-01' }); });
    const session = { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
    const pairs = Array.from({ length: 201 }, (_, index) => ({ documentId: String(9007199254740993n + BigInt(index)), versionId: String(index + 1) }));
    const result = await new ValidityBatch(session).load([...pairs, pairs[0]!]);
    expect(send).toHaveBeenCalledTimes(3); expect(result?.size).toBe(201);
    await expect(validityApi.batch(session, [])).rejects.toMatchObject({ status: 400 });
    await expect(validityApi.batch(session, [pairs[0]!, pairs[0]!])).rejects.toMatchObject({ status: 400 });
  });
  it('丢弃迟到响应及跨身份响应', async () => {
    let resolve!: (value: Response) => void, epoch = 1;
    const session = { send: () => new Promise<Response>(done => { resolve = done; }), getSnapshot: () => ({ epoch }) } as unknown as Session;
    const batch = new ValidityBatch(session), old = batch.load([badge]);
    batch.clear(); epoch++;
    resolve(response({ items: [badge], asOfDate: '2027-01-01' }));
    expect(await old).toBeNull();
  });
});
