import { expect, it, vi } from 'vitest';
import { ApiError, encodeJson } from '../api';
import { Session } from '../session';
import { storage } from '../test/fixtures';
import { gapAssignees, gapDetail, gapDocuments, gapHistory, gapList, gapPaths, gapQueue, gapWrite } from './api';
import { gapIntent, type GapIntent, type GapSubmission } from './intents';
import { gapId, gapRequestId, gapText, gapTextLength, gapUuid, gapVersion } from './input';
import { capabilities, detail, entry, eventId, history, item, page, participant, ready, receipt, response, signal, solutionId } from './testSupport';

const target = '9007199254740993';
const create: GapSubmission = { action: 'create', category: 'MISSING_KNOWLEDGE', description: '明确分享', source: 'MANUAL', shareAcknowledged: true };

it('全部读路径固定且只允许有界非敏感参数，名称只走候选 POST 正文', async () => {
  expect(gapPaths.list()).toBe('/api/knowledge-gaps?view=mine&limit=20');
  expect(gapPaths.list({ view: 'assigned', status: 'AWAITING_CONFIRMATION', limit: 100, cursor: 'abc_-' }))
    .toBe('/api/knowledge-gaps?view=assigned&limit=100&cursor=abc_-&status=AWAITING_CONFIRMATION');
  expect(gapPaths.queue({ status: 'CLOSED' })).toBe('/api/admin/knowledge-gaps?limit=20&status=CLOSED');
  expect(gapPaths.detail(target)).toBe(`/api/knowledge-gaps/${target}`);
  expect(gapPaths.history(target, { cursor: 'abc' })).toBe(`/api/knowledge-gaps/${target}/history?limit=20&cursor=abc`);
  const { session, transport } = await ready(() => response(page([participant(2n)])));
  expect((await gapAssignees(session, target, { nameQuery: '  候选名称  ', cursor: 'abc', limit: 3 }, signal)).items[0].userId).toBe('2');
  const [path, options] = transport.mock.calls[0];
  expect(path).toBe(`/api/admin/knowledge-gaps/${target}/assignees/search`);
  expect(options.method).toBe('POST');
  expect(options.body).toBe('{"nameQuery":"候选名称","limit":3,"cursor":"abc"}');
  expect(new Headers(options.headers).has('X-Request-Id')).toBe(false);
  expect(path).not.toContain('候选');
});

it('实际 Session 原始整数响应精确解码，写出 JSON 整数，不先转成不安全 number', async () => {
  const { session, transport, saved } = await ready(() => response(page([item({ version: 9223372036854775807n })])));
  const result = await gapList(session, {}, signal);
  expect(result.items[0].gapId).toBe(target);
  expect(result.items[0].version).toBe(9223372036854775807n);
  expect(transport).toHaveBeenCalledWith(gapPaths.list(), expect.objectContaining({ cache: 'no-store', credentials: 'omit' }));
  expect(saved.setItem).not.toHaveBeenCalled();
  const intent = gapIntent({ action: 'assign', gapId: target, expectedVersion: '9007199254740995', assigneeUserId: '9223372036854775807' });
  expect(encodeJson(intent.body)).toBe('{"expectedVersion":9007199254740995,"assigneeUserId":9223372036854775807}');
});

it('拒绝非法 ID、版本、游标、枚举和额外参数，不发任何业务请求', async () => {
  for (const bad of ['0', '01', '-1', '1.0', '1e2', '9223372036854775808', '../admin', '1?description=x', 9007199254740992, {}, true]) {
    expect(() => gapId(bad)).toThrow(ApiError);
  }
  for (const bad of ['-1', '01', '1e2', '9223372036854775808', 1.5, 9007199254740992, null]) expect(() => gapVersion(bad)).toThrow(ApiError);
  expect(gapVersion(0)).toBe(0n); expect(gapId(9223372036854775807n)).toBe('9223372036854775807');
  const { session, transport } = await ready();
  for (const query of [{ description: '私人内容' }, { cursor: 'bad cursor!' }, { limit: 0 }, { limit: 101 },
    { status: 'OPEN' }, { status: '' }, { status: 1 }, { status: {} }, { view: 'queue' }]) {
    await expect(async () => gapList(session, query as never, signal)).rejects.toMatchObject({ status: 400 });
  }
  await expect(async () => gapDetail(session, '../1', signal)).rejects.toMatchObject({ status: 400 });
  await expect(async () => gapHistory(session, target, { source: 'x' } as never, signal)).rejects.toMatchObject({ status: 400 });
  await expect(async () => gapQueue(session, { view: 'mine' } as never, signal)).rejects.toMatchObject({ status: 400 });
  await expect(async () => gapDocuments(session, target, { nameQuery: '😀'.repeat(129) }, signal)).rejects.toMatchObject({ status: 400 });
  expect(transport).not.toHaveBeenCalled();
});

it('code point/Unicode 与服务端 strip 一致，超限不静默裁剪、不折叠或改写原文', () => {
  expect(gapText('　 😀\r\n原文　 ', 4000)).toBe('😀\r\n原文');
  expect(gapText('\u00a0', 1)).toBe('\u00a0');
  expect(gapTextLength('　😀　')).toBe(1);
  expect(gapText('😀'.repeat(4000), 4000)).toHaveLength(8000);
  for (const bad of ['', '　', '\u0000', '\ud800', '\udc00', '😀'.repeat(4001)]) expect(() => gapText(bad, 4000)).toThrow(ApiError);
  expect(gapUuid(solutionId.toUpperCase())).toBe(solutionId);
  for (const bad of ['', '\n', 'a\u0085', 'a'.repeat(129), '\ud800']) expect(() => gapRequestId(bad)).toThrow(ApiError);
});

it('创建仅分享明确确认描述，严格拒绝回答、引用、会话身份及其他夹带字段', () => {
  const intent = gapIntent({ ...create, description: '　原问题　', source: 'CONVERSATION' });
  expect(intent.body).toEqual({ category: 'MISSING_KNOWLEDGE', description: '原问题', source: 'CONVERSATION', shareAcknowledged: true });
  expect(intent.gapId).toBeNull(); expect(intent.expectedVersion).toBeNull();
  expect(Object.isFrozen(intent)).toBe(true); expect(Object.isFrozen(intent.body)).toBe(true);
  for (const bad of [{ ...create, shareAcknowledged: false }, { ...create, shareAcknowledged: 'true' }, { ...create, category: 'FAILURE' },
    { ...create, source: 'AI' }, ...['answer', 'citations', 'conversationId', 'turnId', 'searchId', 'reporterUserId'].map(key => ({ ...create, [key]: 'x' }))]) {
    expect(() => gapIntent(bad as GapSubmission)).toThrow(ApiError);
  }
});

it('各动作固定端点与载荷，确认/拒绝绑定版本和原方案，不存在编辑/删除/重开', () => {
  const at = { gapId: target, expectedVersion: 2n };
  const commands: Array<[GapSubmission, string, object]> = [
    [{ ...at, action: 'assign', assigneeUserId: '2' }, 'admin/knowledge-gaps/' + target + '/assign', { expectedVersion: 2n, assigneeUserId: 2n }],
    [{ ...at, action: 'note', text: '说明' }, `knowledge-gaps/${target}/notes`, { expectedVersion: 2n, text: '说明' }],
    [{ ...at, action: 'return', reason: '退回' }, `knowledge-gaps/${target}/return`, { expectedVersion: 2n, reason: '退回' }],
    [{ ...at, action: 'solution', text: '方案' }, `knowledge-gaps/${target}/solutions`, { expectedVersion: 2n, text: '方案' }],
    [{ ...at, action: 'confirm', solutionId }, `knowledge-gaps/${target}/confirm`, { expectedVersion: 2n, solutionId }],
    [{ ...at, action: 'reject-solution', solutionId, reason: '仍未解决' }, `knowledge-gaps/${target}/reject-solution`, { expectedVersion: 2n, solutionId, reason: '仍未解决' }],
    [{ ...at, action: 'withdraw', reason: '撤回' }, `knowledge-gaps/${target}/withdraw`, { expectedVersion: 2n, reason: '撤回' }],
    [{ ...at, action: 'close', reason: '关闭' }, `admin/knowledge-gaps/${target}/close`, { expectedVersion: 2n, reason: '关闭' }],
  ];
  for (const [input, path, body] of commands) expect(gapIntent(input)).toMatchObject({ method: 'POST', path: `/api/${path}`, body });
  for (const input of [{ ...at, action: 'edit' }, { ...at, action: 'delete' }, { ...at, action: 'reopen' },
    { ...at, action: 'confirm', solutionId: '2' }, { ...at, action: 'note', text: 'x'.repeat(2001) },
    { ...at, action: 'solution', text: 'x', documentId: '1' }, { ...at, action: 'solution', text: 'x', versionId: '2' },
    { ...at, action: 'solution', text: 'x', documentId: '1', versionId: '2', title: '旧标题' }]) {
    expect(() => gapIntent(input as GapSubmission)).toThrow(ApiError);
  }
  expect(gapIntent({ ...at, action: 'solution', text: '方案', documentId: target, versionId: '9223372036854775807' }).body)
    .toEqual({ expectedVersion: 2n, text: '方案', documentId: 9007199254740993n, versionId: 9223372036854775807n });
});

it('只读列表/队列/详情/历史及资料候选严格解析最小 DTO', async () => {
  const { session } = await ready(path => {
    if (path.endsWith('/solution-documents')) return response(page([{ documentId: 7n, versionId: 8n, title: '当前授权标题' }]));
    if (path.includes('/history?')) return response(page([history()]));
    if (path.endsWith('/' + target)) return response(detail());
    return response(page([item()]));
  });
  expect((await gapDetail(session, target, signal)).description).toBe('明确分享的描述');
  expect((await gapHistory(session, target, {}, signal)).items[0]).toMatchObject({ eventId, resultVersion: 0n, entry: null });
  expect((await gapQueue(session, {}, signal)).items[0].reporter.userId).toBe('1');
  expect((await gapDocuments(session, target, {}, signal)).items[0]).toEqual({ documentId: '7', versionId: '8', title: '当前授权标题' });
});

it('详情只接受当前方案及独立资料投影，终态不能夹带能力', async () => {
  const awaiting = { state: 'AWAITING_CONFIRMATION', version: 2n, assignee: participant(2n),
    capabilities: { ...capabilities, confirm: true, rejectSolution: true }, currentSolution: entry({ referenceState: 'UNAVAILABLE' }) };
  const { session } = await ready(() => response(detail(awaiting)));
  expect((await gapDetail(session, target, signal)).currentSolution).toMatchObject({ entryId: solutionId, referenceState: 'UNAVAILABLE', reference: null });
  for (const bad of [detail({ gapId: 1n }), detail({ title: '夹带' }), detail({ consentVersion: 'OTHER' }), detail({ history: [] }),
    detail({ ...awaiting, currentSolution: null }), detail({ ...awaiting, currentSolution: entry({ referenceState: 'UNAVAILABLE', reference: { documentId: 3n, versionId: 4n, title: '旧快照' } }) }),
    detail({ ...awaiting, currentSolution: entry({ referenceState: 'AVAILABLE', reference: { documentId: 3n, versionId: 4n, title: '当前', url: 'https://evil.test' } }) }),
    detail({ state: 'RESOLVED', capabilities }), detail({ capabilities: { ...capabilities, confirm: true } })]) {
    const test = await ready(() => response(bad));
    await expect(gapDetail(test.session, target, signal)).rejects.toMatchObject({ status: 503 });
  }
});

it('响应失败不变成空集合，拒绝额外字段、重复项、隐藏总数与错误游标', async () => {
  for (const bad of [page([item()], { total: 100 }), page([item(), item()]), page([], { hasMore: true, nextCursor: 'abc' }),
    page([item()], { nextCursor: 'abc' }), page([item()], { hasMore: true, nextCursor: 'bad cursor' }), page([item({ gapId: target })]),
    page([item({ version: '0' })]), page([item({ reporter: { ...participant(), departmentId: 9n } })]), page([item({ description: '多余正文' })])]) {
    const { session } = await ready(() => response(bad));
    await expect(gapList(session, {}, signal)).rejects.toMatchObject({ status: 503 });
  }
  const { session } = await ready(() => response(page()));
  expect(await gapList(session, {}, signal)).toEqual({ items: [], hasMore: false, nextCursor: null });
});

it('历史校验事件/entry/方案身份一致，不能返回未知事件或隐式正文', async () => {
  const solution = history({ kind: 'GAP_SOLUTION_SUBMITTED', resultVersion: 2n, actor: participant(2n), assignee: participant(2n), solutionId, entry: entry() });
  const { session } = await ready(() => response(page([solution])));
  expect((await gapHistory(session, target, {}, signal)).items[0].entry?.kind).toBe('SOLUTION');
  for (const bad of [history({ kind: 'UNKNOWN' }), history({ kind: 'NOTE_ADDED', entry: null }), { ...solution, solutionId: eventId },
    { ...solution, entry: entry({ position: 1n }) }, history({ text: '多余' }), history({ kind: 'GAP_CREATED', resultVersion: 1n })]) {
    const test = await ready(() => response(page([bad])));
    await expect(gapHistory(test.session, target, {}, signal)).rejects.toMatchObject({ status: 503 });
  }
});

it('原始 JSON 拒绝重复键、尾随文档、小数、指数和损坏 Unicode', async () => {
  const good = encodeJson({ code: 200, message: 'ok', data: detail() });
  for (const bad of [good.replace('"code":200', '"code":200,"code":200'), good + '{}', good.replace('"version":0', '"version":0.0'),
    good.replace('"version":0', '"version":0e0'), good.replace('明确分享的描述', '\\ud800')]) {
    const { session } = await ready(() => new Response(bad, { headers: { 'Content-Type': 'application/json' } }));
    await expect(gapDetail(session, target, signal)).rejects.toMatchObject({ status: 503 });
  }
});

it('创建首次 201/匹配重放 200，原请求标识只放头，不提前生成问题 ID', async () => {
  let calls = 0;
  const { session, transport } = await ready(() => response(receipt({ replayed: calls++ > 0 }), calls === 1 ? 201 : 200));
  const intent = gapIntent(create);
  expect(await gapWrite(session, intent, eventId, signal)).toMatchObject({ gapId: target, resultVersion: 0n, replayed: false });
  expect(await gapWrite(session, intent, eventId, signal)).toMatchObject({ gapId: target, replayed: true });
  expect(transport.mock.calls[0][0]).toBe('/api/knowledge-gaps');
  expect(new Headers(transport.mock.calls[0][1].headers).get('X-Request-Id')).toBe(eventId);
  expect(transport.mock.calls[0][1].body).toBe(encodeJson(intent.body));
  expect(transport.mock.calls[0][1].body).not.toContain('requestId');
});

it('方案提交/本人确认/拒绝的回执精确绑定目标、版本、状态与方案', async () => {
  for (const [action, state] of [['solution', 'AWAITING_CONFIRMATION'], ['confirm', 'RESOLVED'], ['reject-solution', 'IN_PROGRESS']] as const) {
    const input = action === 'solution' ? { action, gapId: target, expectedVersion: 9007199254740993n, text: '方案' }
      : { action, gapId: target, expectedVersion: 9007199254740993n, solutionId, ...(action === 'reject-solution' ? { reason: '未解决' } : {}) };
    const intent = gapIntent(input as GapSubmission);
    const { session } = await ready(() => response(receipt({ resultVersion: 9007199254740994n, state, solutionId })));
    expect(await gapWrite(session, intent, eventId, signal)).toMatchObject({ resultVersion: 9007199254740994n, solutionId, state });
  }
});

it('成功但非法/不一致/不可读回执为未知结果，不误报成功或展示额外正文', async () => {
  const intent = gapIntent({ action: 'confirm', gapId: target, expectedVersion: 2n, solutionId });
  for (const bad of [receipt({ resultVersion: 3n, state: 'RESOLVED', solutionId: eventId }), receipt({ resultVersion: 2n, state: 'RESOLVED', solutionId }),
    receipt({ gapId: 7n, resultVersion: 3n, state: 'RESOLVED', solutionId }), receipt({ resultVersion: 3n, state: 'CLOSED', solutionId }),
    receipt({ resultVersion: 3n, state: 'RESOLVED', solutionId, description: '多余正文' }), receipt({ resultVersion: 3n, state: 'RESOLVED', solutionId, replayed: 'true' })]) {
    const { session } = await ready(() => response(bad));
    await expect(gapWrite(session, intent, eventId, signal)).rejects.toMatchObject({ status: 0 });
  }
  for (const body of ['<html>内部 SQL 及私人内容</html>', 'x'.repeat(65537)]) {
    const { session } = await ready(() => new Response(body, { headers: { 'Content-Type': 'application/json' } }));
    await expect(gapWrite(session, intent, eventId, signal)).rejects.toMatchObject({ status: 0 });
  }
  const { session, transport } = await ready();
  await expect(async () => gapWrite(session, { ...intent, path: '/api/admin/identity/users/search' } as GapIntent, eventId, signal)).rejects.toMatchObject({ status: 400 });
  expect(transport).not.toHaveBeenCalled();
});

it.each([400, 403, 404, 409, 413, 503])('HTTP %i 只显示本地固定提示，不输出失败体或异常', async status => {
  const { session } = await ready(() => new Response('私人描述/候选/SQL/satoken=secret', { status }));
  await expect(gapDetail(session, target, signal)).rejects.toMatchObject({ status, message: expect.not.stringMatching(/私人|SQL|secret/) });
});

it('身份未确认/强制改密/未登录时不发问题读取，传输丢失保持未知错误', async () => {
  const transport = vi.fn();
  const loading = new Session(storage(), transport as typeof fetch);
  await expect(gapDetail(loading, target, signal)).rejects.toMatchObject({ status: 503 });
  const anonymous = new Session(storage(false), transport as typeof fetch);
  await expect(gapDetail(anonymous, target, signal)).rejects.toMatchObject({ status: 401 });
  expect(transport).not.toHaveBeenCalled();
  const { session } = await ready(() => { throw new Error('raw sensitive network'); });
  await expect(gapWrite(session, gapIntent(create), eventId, signal)).rejects.toMatchObject({ status: 0 });
});
