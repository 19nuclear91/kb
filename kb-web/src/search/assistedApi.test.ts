import { expect, it, vi } from 'vitest';
import { encodeJson } from '../api';
import { json } from '../test/fixtures';
import type { Session } from '../session';
import { assistedApi, assistedBody, decodeAssisted, AssistanceProtocolError, type AssistedInput } from './assistedApi';
import { assistedResult, assistedResponse } from './assistedFixtures';

const input = (): AssistedInput => ({ originalQuery: '😀PM', mode: 'ASSISTED', filters: {}, candidates: 50, topK: 20, selections: [], selectionContext: null });
const client = (response: Response) => ({ send: vi.fn().mockResolvedValue(response), getSnapshot: () => ({ epoch: 1 }) }) as unknown as Session;
function replacementExpansion() {
  const submitted: AssistedInput = { ...input(), originalQuery: 'PM 项目', selectionContext: 'opaque',
    selections: [{ kind: 'DISAMBIGUATION', start: 0, end: 2, termId: '2' }] };
  const value = assistedResult(submitted.originalQuery);
  value.assistance.selections = [...submitted.selections];
  value.assistance.effectiveQuery = '产品 项目';
  value.assistance.appliedTerms = [
    { start: 0, end: 2, matchedText: 'PM', termId: '2', termVersion: '0', canonicalName: '产品', expressions: [] },
    { start: 3, end: 5, matchedText: '项目', termId: '1', termVersion: '0', canonicalName: 'PM', expressions: ['PM'] },
  ];
  return { submitted, value };
}
it('完整双版本、原文位置、空命中建议和大整数保持精确', async () => {
  const value = assistedResult('😀PM');
  value.assistance.dictionaryRevision = '9223372036854775807'; value.assistance.selectionContext = 'opaque';
  value.assistance.suggestions = [{ kind: 'DISAMBIGUATION', start: 1, end: 3, sourceText: 'PM', options: [
    { termId: '9007199254740993', termVersion: '0', canonicalName: '项目管理', description: '管理项目' },
    { termId: '9223372036854775807', termVersion: '9223372036854775807', canonicalName: '产品经理', description: '管理产品' }] }];
  const response = json(value); response.headers.set('X-Search-Filter-Version', '1'); response.headers.set('X-Search-Assistance-Version', '1');
  const result = await assistedApi.search(client(response), input());
  expect(result.hits).toEqual([]); expect(result.assistance.suggestions[0].options[1].termId).toBe('9223372036854775807');
  const selected = { ...input(), filters: { spaceId: '9223372036854775807' }, selectionContext: 'opaque', selections: [{ kind: 'DISAMBIGUATION' as const, start: 1, end: 3, termId: '9223372036854775807' }] };
  expect(encodeJson(assistedBody(selected))).toContain('"termId":9223372036854775807');
  expect(encodeJson(assistedBody(selected))).toContain('"spaceId":9223372036854775807');
});
it.each([null, '2', '1, 1', '1, 2'])('非法增强头 %s 整体丢弃，不重发旧入口', async version => {
  const response = assistedResponse('😀PM');
  if (version === null) response.headers.delete('X-Search-Assistance-Version'); else response.headers.set('X-Search-Assistance-Version', version);
  const session = client(response);
  await expect(assistedApi.search(session, input())).rejects.toBeInstanceOf(AssistanceProtocolError);
  expect(session.send).toHaveBeenCalledOnce();
});
it('旧数组、缺字段、模式冲突和损坏表达不能保留合法 hits', () => {
  for (const mutate of [
    (value: any) => { value.assistance = null; }, (value: any) => { value.assistance.mode = 'ORIGINAL'; },
    (value: any) => { value.assistance.effectiveQuery = '产品经理'; }, (value: any) => { value.assistance.originalQuery = 'PM'; },
    (value: any) => { value.assistance.dictionaryRevision = 1; }, (value: any) => { value.assistance.selectionContext = 'unexpected'; },
    (value: any) => { value.hits = [{ content: '看似合法' }]; },
  ]) { const value = assistedResult('😀PM'); mutate(value); expect(() => decodeAssisted(value, input())).toThrow(AssistanceProtocolError); }
  expect(() => decodeAssisted([], input())).toThrow(AssistanceProtocolError);
});
it('选择与有效表达同源且建议位置必须属于原文', () => {
  const selected = { ...input(), selections: [{ kind: 'DISAMBIGUATION' as const, start: 1, end: 3, termId: '3' }], selectionContext: 'opaque' };
  const value = assistedResult('😀PM'); value.assistance.selections = [...selected.selections]; value.assistance.effectiveQuery = '😀项目管理';
  value.assistance.appliedTerms = [{ start: 1, end: 3, matchedText: 'PM', termId: '3', termVersion: '0', canonicalName: '项目管理', expressions: [] }];
  expect(decodeAssisted(value, selected).assistance.effectiveQuery).toBe('😀项目管理');
  value.assistance.appliedTerms[0].end = 4; expect(() => decodeAssisted(value, selected)).toThrow(AssistanceProtocolError);
});
it.each([
  { query: 'PM 项目', selectedStart: 0, automaticStart: 3, matchedText: 'PM', effectiveQuery: '产品 项目' },
  { query: '😀pm 项目', selectedStart: 1, automaticStart: 4, matchedText: 'pm', effectiveQuery: '😀产品 项目' },
  { query: '项目 PM', selectedStart: 3, automaticStart: 0, matchedText: 'PM', effectiveQuery: '项目 产品' },
])('替换后的旧表达可由另一处术语补充，完整响应保留命中：$query', async ({ query, selectedStart, automaticStart, matchedText, effectiveQuery }) => {
  const { submitted, value } = replacementExpansion();
  submitted.originalQuery = query;
  submitted.selections = [{ kind: 'DISAMBIGUATION', start: selectedStart, end: selectedStart + 2, termId: '2' }];
  value.assistance.originalQuery = query;
  value.assistance.effectiveQuery = effectiveQuery;
  value.assistance.selections = [...submitted.selections];
  Object.assign(value.assistance.appliedTerms[0], { start: selectedStart, end: selectedStart + 2, matchedText });
  Object.assign(value.assistance.appliedTerms[1], { start: automaticStart, end: automaticStart + 2 });
  value.assistance.appliedTerms.sort((a, b) => a.start - b.start);
  const hit = { chunkId: '1', docId: '10', versionId: '20', versionNo: '1', spaceId: '3', securityLevel: 1,
    chunkIdx: 0, docTitle: '项目说明', content: '匹配 PM 的资料', score: 0.1 };
  const response = json({ ...value, hits: [hit] });
  response.headers.set('X-Search-Filter-Version', '1'); response.headers.set('X-Search-Assistance-Version', '1');
  const session = client(response), result = await assistedApi.search(session, submitted);
  expect(result.assistance).toEqual(value.assistance);
  expect(result.hits).toEqual([{ chunkId: '1', docId: '10', versionId: '20', versionNo: 1, docTitle: '项目说明', content: '匹配 PM 的资料' }]);
  expect(session.send).toHaveBeenCalledOnce();
});
it.each(['产品', '项目'])('替换与扩展混合时仍拒绝有效查询中已存在的表达：%s', expression => {
  const { submitted, value } = replacementExpansion();
  value.assistance.appliedTerms[1].expressions = [expression];
  expect(() => decodeAssisted(value, submitted)).toThrow(AssistanceProtocolError);
});
it.each(['PM', 'pm'])('其他位置仍待选择时，相同旧表达不能重复补充：%s', expression => {
  const { submitted, value } = replacementExpansion();
  submitted.originalQuery = 'PM 项目 pm';
  value.assistance.originalQuery = submitted.originalQuery; value.assistance.effectiveQuery = '产品 项目 pm';
  value.assistance.selectionContext = 'next-context';
  value.assistance.suggestions = [{ kind: 'DISAMBIGUATION', start: 6, end: 8, sourceText: 'pm', options: [
    { termId: '1', termVersion: '0', canonicalName: 'PM', description: '项目' },
    { termId: '2', termVersion: '0', canonicalName: '产品', description: '产品' },
  ] }];
  value.assistance.appliedTerms[1].expressions = [];
  expect(decodeAssisted(value, submitted).assistance).toEqual(value.assistance);
  value.assistance.appliedTerms[1].expressions = [expression];
  expect(() => decodeAssisted(value, submitted)).toThrow(AssistanceProtocolError);
});
it('混合替换时同组重复位置共享扩展并集，组内规范等价重复仍拒绝', () => {
  const { submitted, value } = replacementExpansion();
  submitted.originalQuery = 'PM 项目 项目'; submitted.maxQueryCodepoints = 11;
  value.assistance.originalQuery = submitted.originalQuery; value.assistance.effectiveQuery = '产品 项目 项目';
  value.assistance.appliedTerms.push({ ...value.assistance.appliedTerms[1], start: 6, end: 8 });
  expect(decodeAssisted(value, submitted).assistance).toEqual(value.assistance);
  value.assistance.appliedTerms[1].expressions = ['PM', 'pm'];
  value.assistance.appliedTerms[2].expressions = ['PM', 'pm'];
  expect(() => decodeAssisted(value, submitted)).toThrow(AssistanceProtocolError);
});
it('ORIGINAL 必须恒等且实际命中固定版本，不接受数值型身份', async () => {
  const original = { ...input(), mode: 'ORIGINAL' as const };
  const response = assistedResponse('😀PM', [{ chunkId: '1', docId: '9223372036854775807', versionId: '20', content: '正文' }], 'ORIGINAL');
  const value = await assistedApi.search(client(response), original);
  expect(value.hits[0].docId).toBe('9223372036854775807'); expect(value.hits[0].versionNo).toBe(1);
});
it.each([[409, 'ASSISTANCE_STALE'], [400, 'ASSISTANCE_LIMIT_EXCEEDED'], [413, 'PAYLOAD_TOO_LARGE'], [429, 'USER_LIMIT'], [503, 'ASSISTANCE_UNAVAILABLE'], [500, 'UNKNOWN']] as const)('固定错误 %i/%s 不回显远程文本也不自动重试', async (status, reason) => {
  const session = client(new Response(encodeJson({ code: 500, message: 'private-message', data: null, reasonCode: reason }), { status, headers: { 'Content-Type': 'application/json', 'Retry-After': '2' } }));
  await expect(assistedApi.search(session, input())).rejects.toMatchObject({ status, reason: reason === 'UNKNOWN' ? undefined : reason });
  expect(session.send).toHaveBeenCalledOnce();
});
it.each(['ORIGINAL', 'ASSISTED'] as const)('非默认上限的 %s 合法长查询完整响应可解码，本地上限不进入协议正文', async mode => {
  const query = '😀'.repeat(8001), submitted = { ...input(), originalQuery: query, mode, maxQueryCodepoints: 9000 };
  const result = await assistedApi.search(client(assistedResponse(query, [], mode)), submitted);
  expect(result.assistance.effectiveQuery).toBe(query);
  expect(assistedBody(submitted)).not.toHaveProperty('maxQueryCodepoints');
  expect(() => decodeAssisted(assistedResult(query, mode), { ...submitted, maxQueryCodepoints: 8000 })).toThrow(AssistanceProtocolError);
});
it.each([100, 9000])('辅助最终表达按实际上限 %i 校验，换行和补充字符计入边界', maximum => {
  const query = `OA${'😀'.repeat(maximum - 5)}`, submitted = { ...input(), originalQuery: query, maxQueryCodepoints: maximum };
  const value = assistedResult(query); value.assistance.appliedTerms = [{ start: 0, end: 2, matchedText: 'OA', termId: '1', termVersion: '0', canonicalName: '办公', expressions: ['办公'] }];
  expect(decodeAssisted(value, submitted).assistance.appliedTerms[0].expressions).toEqual(['办公']);
  value.assistance.appliedTerms[0].expressions = ['办公😀'];
  expect(() => decodeAssisted(value, submitted)).toThrow(AssistanceProtocolError);
});
it('提高查询上限不放宽独立的 32 表达和 2048 新增 code point 限额', () => {
  const submitted = { ...input(), originalQuery: 'OA', maxQueryCodepoints: 12000 }, value = assistedResult('OA');
  // 固定 63 个 code point，加换行共 2048。
  const expressions = Array.from({ length: 32 }, (_, index) => { const prefix = String(index); return prefix + '😀'.repeat(63 - prefix.length); });
  value.assistance.appliedTerms = [{ start: 0, end: 2, matchedText: 'OA', termId: '1', termVersion: '0', canonicalName: '办公', expressions }];
  expect(decodeAssisted(value, submitted).assistance.appliedTerms[0].expressions).toHaveLength(32);
  value.assistance.appliedTerms[0].expressions = expressions.map((text, index) => index === 0 ? `${text}😀` : text);
  expect(() => decodeAssisted(value, submitted)).toThrow(AssistanceProtocolError);
  value.assistance.appliedTerms[0].expressions = [...expressions, '第33项'];
  expect(() => decodeAssisted(value, submitted)).toThrow(AssistanceProtocolError);
});
