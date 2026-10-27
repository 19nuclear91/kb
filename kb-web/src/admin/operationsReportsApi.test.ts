import { describe, expect, it, vi } from 'vitest';
import type { Session } from '../session';
import { deferred, json } from '../test/fixtures';
import { operationsReportData, unknownComments } from '../test/operationsReportsFixtures';
import { operationsReport, operationsReportsPath, parseOperationsReport } from './operationsReportsApi';

describe('运营报表精确协议', () => {
  it('默认窗口由服务端确认，固定字段丢弃意外身份和正文', async () => {
    const source = { ...operationsReportData(), userId: 'private-id', body: 'private-body' };
    const send = vi.fn().mockResolvedValue(json(source)), session = { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
    const signal = new AbortController().signal, result = await operationsReport(session, {}, signal);
    expect(result).toEqual(operationsReportData()); expect(JSON.stringify(result)).not.toContain('private-');
    expect(send).toHaveBeenCalledWith('/api/admin/reports/operations', { signal, cache: 'no-store' }, false, false);
  });
  it('计数、时长总量与两位平均不经过浮点数', () => {
    const source = operationsReportData(), huge = '999999999999999999999999999999999999999';
    source.inventory.documentCount = huge;
    for (const review of [source.review, source.days[0].review]) Object.assign(review, { approvedCount: huge, rejectedCount: '0', durationSampleCount: huge, durationUnknownCount: '0', decisionElapsedMsTotal: huge, meanDecisionMs: '1.00' });
    const result = parseOperationsReport(source); expect(result.review.approvedCount).toBe(huge); expect(result.review.decisionElapsedMsTotal).toBe(huge);
    expect(result.inventory.documentCount).toBe(huge); expect(result.review.meanDecisionMs).toBe('1.00');
  });
  it('真零、未知、部分覆盖和无样本各自保留', () => {
    const source = unknownComments();
    for (const review of [source.review, source.days[0].review]) Object.assign(review, { approvedCount: '0', rejectedCount: '0', durationSampleCount: '0', decisionElapsedMsTotal: '0', meanDecisionMs: null });
    expect(parseOperationsReport(source).review.meanDecisionMs).toBeNull(); expect(parseOperationsReport(source).comments.rootCommentCount).toBeNull();
    const partial = operationsReportData();
    for (const comments of [partial.comments, partial.days[0].comments]) Object.assign(comments.coverage,
      { status: 'PARTIAL', coverageStartedAt: '2026-10-26T12:00:00Z', effectiveFrom: '2026-10-26T12:00:00Z' });
    expect(parseOperationsReport(partial).comments.rootCommentCount).toBe('0');
  });
  it.each(['number', 'bucket', 'mean', 'day', 'coverage', 'sum', 'missing'])('异常响应整体 503：%s', reason => {
    const source = operationsReportData();
    if (reason === 'number') (source.inventory as unknown as { documentCount: number }).documentCount = 9007199254740992;
    if (reason === 'bucket') source.inventory.formats.pdf = '9';
    if (reason === 'mean') source.review.meanDecisionMs = '1.49';
    if (reason === 'day') source.days[0].partialDay = true;
    if (reason === 'coverage') source.comments.coverage.status = 'NOT_STARTED';
    if (reason === 'sum') source.days[0].comments.replyCount = '2';
    if (reason === 'missing') delete (source.inventory.formats as Partial<typeof source.inventory.formats>).UNKNOWN;
    expect(() => parseOperationsReport(source)).toThrow(expect.objectContaining({ status: 503 }));
  });
  it('严格成对窗口、31 天和微秒边界，不接受任何身份筛选', () => {
    expect(operationsReportsPath({})).toBe('/api/admin/reports/operations');
    expect(operationsReportsPath({ from: '2026-09-01T00:00:00.123456Z', to: '2026-10-02T08:00:00.123456+08:00' })).toContain('from=');
    for (const filters of [{ from: '' }, { to: '2026-10-01T00:00:00Z' }, { from: '2026-09-01T00:00:00.123456Z', to: '2026-10-02T00:00:00.123457Z' },
      { from: '2026-10-01T00:00:00', to: '2026-10-02T00:00:00Z' }, { userId: '7' }])
      expect(() => operationsReportsPath(filters)).toThrow(expect.objectContaining({ status: 400 }));
  });
  it('浏览器时钟落后不拒绝服务端 asOf 允许的窗口', async () => {
    const localClock = vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-26T12:00:00Z'));
    try {
      const filters = { from: '2026-10-26T00:00:00Z', to: '2026-10-27T00:00:00Z' };
      const send = vi.fn().mockResolvedValue(json(operationsReportData()));
      const session = { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
      expect(await operationsReport(session, filters, new AbortController().signal)).toEqual(operationsReportData());
      expect(send).toHaveBeenCalledTimes(1); expect(localClock).not.toHaveBeenCalled();
    } finally { localClock.mockRestore(); }
  });
  it('未来终点交由固定数据库 asOf 校验，保留服务端 400', async () => {
    const filters = { from: '2099-01-01T00:00:00Z', to: '2099-01-02T00:00:00Z' };
    const send = vi.fn().mockResolvedValue(new Response(null, { status: 400 }));
    const session = { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
    await expect(operationsReport(session, filters, new AbortController().signal)).rejects.toMatchObject({ status: 400 });
    expect(send).toHaveBeenCalledTimes(1);
  });
  it('Session epoch 改变后不消费迟到响应', async () => {
    let epoch = 1; const pending = deferred<Response>();
    const session = { send: () => pending.promise, getSnapshot: () => ({ epoch }) } as unknown as Session;
    const result = operationsReport(session, {}, new AbortController().signal); epoch++;
    pending.resolve(json(operationsReportData())); await expect(result).rejects.toMatchObject({ name: 'AbortError' });
  });
  it.each([401, 403, 503])('保留安全 HTTP %s，不用其他接口补数', async status => {
    const send = vi.fn().mockResolvedValue(new Response(null, { status }));
    const session = { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
    await expect(operationsReport(session, {}, new AbortController().signal)).rejects.toMatchObject({ status }); expect(send).toHaveBeenCalledTimes(1);
  });
});
