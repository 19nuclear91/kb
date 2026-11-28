import { describe, expect, it, vi } from 'vitest';
import { healthFixture } from '../test/healthFixtures';
import { safeReturnPath } from '../navigation';
import { healthReport, parseHealthReport } from './healthApi';
import { Session } from '../session';
import { json, storage, me } from '../test/fixtures';

describe('健康雷达响应', () => {
  it('实际API读取固定错误头并拒绝HTML成功体，保留完整成功协议', async () => {
    let response = json(healthFixture());
    const transport = vi.fn(async (path: RequestInfo | URL) => String(path) === '/api/auth/me' ? json(me) : response.clone());
    const session = new Session(storage(true), transport as typeof fetch); await session.refresh();
    const signal = new AbortController().signal;
    expect((await healthReport(session, '2', 'ALL', null, signal)).affectedDocumentCount).toBe('1');
    const incomplete = healthFixture(); incomplete.items = []; response = json(incomplete);
    await expect(healthReport(session, '2', 'ALL', null, signal)).rejects.toMatchObject({ status: 503 });
    expect((await healthReport(session, '2', 'ALL', 'cursor', signal)).items).toHaveLength(0);
    response = new Response(null, { status: 409, headers: { 'X-Kb-Health-Error': 'RADAR_DATE_CHANGED' } });
    await expect(healthReport(session, '2', 'ALL', 'cursor', signal)).rejects.toMatchObject({ status: 409, reason: 'RADAR_DATE_CHANGED' });
    response = new Response('<html>private</html>', { headers: { 'Content-Type': 'text/html' } });
    await expect(healthReport(session, '2', 'ALL', null, signal)).rejects.toMatchObject({ status: 503 });
    expect(transport.mock.calls.filter(([path]) => String(path).includes('health-radar'))).toHaveLength(5);
  });
  it('保留双原因并集、固定版本和大整数身份', () => {
    const value = parseHealthReport(healthFixture(), '2', 'ALL');
    expect(value.items[0].documentId).toBe('9223372036854775807');
    expect(value.affectedDocumentCount).toBe('1');
    expect(value.items[0].signals).toHaveLength(2);
  });
  it.each([404, 405])('旧服务端点返回%s时按服务不可用处理，协议内目标拒绝仍为404', async status => {
    let response = new Response(null, { status });
    const transport = vi.fn(async (path: RequestInfo | URL) => String(path) === '/api/auth/me' ? json(me) : response.clone());
    const session = new Session(storage(true), transport as typeof fetch); await session.refresh();
    const signal = new AbortController().signal;
    await expect(healthReport(session, '2', 'ALL', null, signal)).rejects.toMatchObject({ status: 503 });
    response = new Response(null, { status: 404, headers: { 'X-Kb-Health-Error': 'NOT_FOUND' } });
    await expect(healthReport(session, '2', 'ALL', null, signal)).rejects.toMatchObject({ status: 404 });
  });
  it.each(['2026-02-30T08:00:00Z', '2026-03-01T24:00:00Z'])('拒绝运行时会自动归一化的非法统计时间 %s', asOf => {
    const value = healthFixture(); value.asOf = asOf; value.asOfDate = '2026-03-02';
    value.items[0].stewardship.dueDate = '2026-02-01'; value.items[0].applicability!.effectiveUntil = '2026-02-01';
    expect(() => parseHealthReport(value, '2', 'ALL')).toThrow();
  });
  it('汇总只够当前页时不能宣称还有下一页', () => {
    const value = healthFixture(); value.limit = 1; value.hasMore = true; value.nextCursor = 'cursor';
    expect(() => parseHealthReport(value, '2', 'ALL', 1)).toThrow();
    value.affectedDocumentCount = '2'; value.counts.stewardUnassigned = '1';
    expect(parseHealthReport(value, '2', 'ALL', 1).hasMore).toBe(true);
    value.signal = 'REVIEW_OVERDUE';
    expect(() => parseHealthReport(value, '2', 'REVIEW_OVERDUE', 1)).toThrow();
  });
  it.each([
    (v: any) => { v.definitionVersion = 'OLD'; }, (v: any) => { delete v.counts.reviewOverdue; },
    (v: any) => { v.counts.reviewOverdue = '01'; }, (v: any) => { v.spaceId = '3'; },
    (v: any) => { v.items[0].documentId = '9223372036854775808'; }, (v: any) => { v.items[0].title = 'unexpected'; },
    (v: any) => { v.items[0].applicability = null; }, (v: any) => { v.items[0].stewardship.dueDate = '2026-02-30'; },
    (v: any) => { v.items[0].applicability.effectiveFrom = '2026-12-01'; }, (v: any) => { v.items.push(v.items[0]); },
    (v: any) => { v.hasMore = true; v.nextCursor = 'cursor'; }, (v: any) => { v.nextCursor = 'cursor'; },
    (v: any) => { v.asOfDate = '2026-11-28'; }, (v: any) => { v.affectedDocumentCount = '0'; },
  ])('拒绝不完整或矛盾协议 %#', mutate => {
    const value = healthFixture(); mutate(value);
    expect(() => parseHealthReport(value, '2', 'ALL')).toThrow();
  });
  it('未指定优先于旧到期日，可读范围外无适用期字段', () => {
    const value = healthFixture();
    value.items[0].signals = ['STEWARD_UNASSIGNED'];
    value.items[0].stewardship = { state: 'UNASSIGNED', responsible: null, dueDate: '2020-01-01' };
    value.items[0].applicability = null;
    value.counts = { stewardUnassigned: '9007199254740993', stewardNeedsReassign: '0', reviewOverdue: '0', reviewDueSoon: '0', applicabilityExpired: '0', applicabilityExpiringSoon: '0' };
    value.affectedDocumentCount = '9007199254740993';
    expect(parseHealthReport(value, '2', 'ALL').counts.stewardUnassigned).toBe('9007199254740993');
  });
  it('到期当天与第30天均为即将到期', () => {
    for (const date of ['2026-11-27', '2026-12-27']) {
      const value = healthFixture();
      value.items[0].signals = ['REVIEW_DUE_SOON', 'APPLICABILITY_EXPIRING_SOON'];
      value.items[0].stewardship.state = 'DUE_SOON'; value.items[0].stewardship.dueDate = date;
      value.items[0].applicability!.state = 'EXPIRING_SOON'; value.items[0].applicability!.effectiveUntil = date;
      value.counts = { stewardUnassigned: '0', stewardNeedsReassign: '0', reviewOverdue: '0', reviewDueSoon: '1', applicabilityExpired: '0', applicabilityExpiringSoon: '1' };
      expect(parseHealthReport(value, '2', 'ALL').items[0].signals).toHaveLength(2);
    }
  });
  it('只接受合法目标登录返回', () => {
    expect(safeReturnPath('/admin/spaces/9223372036854775807/health-radar')).toBe('/admin/spaces/9223372036854775807/health-radar');
    for (const path of ['/admin/spaces/0/health-radar', '/admin/spaces/9223372036854775808/health-radar', '/admin/spaces/2/health-radar?signal=ALL', '/admin/spaces/2/health-radar#x', '/admin/spaces/2/health-radar/']) expect(safeReturnPath(path)).toBe('/documents');
  });
});
