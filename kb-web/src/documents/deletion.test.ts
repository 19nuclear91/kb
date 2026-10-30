import { afterEach, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { SessionState } from '../session';
import { deferred, json, me } from '../test/fixtures';
import type { Capabilities, DocumentSummary } from '../types';
import { deletionTransport, DocumentDeletion } from './deletion';
import { DocumentOperations } from './operations';

afterEach(() => vi.useRealTimers());

const id = '9223372036854775807';
const detail: DocumentSummary = { id, spaceId: '2', title: '<img src=x onerror=alert(1)>', securityLevel: 1,
  currentVersionId: null, currentVersionNo: null, currentFileName: null, currentMimeType: null, currentIndexStatus: null, updatedAt: '' };
const capabilities: Capabilities = { documentId: id, canDownload: false, canUploadVersion: true, canRetry: true, canDelete: true };
function setup() {
  const identity: SessionState = { epoch: 1, phase: 'ready', me };
  const session = { getSnapshot: () => identity };
  const transport = { detail: vi.fn(async (_id: string, _signal: AbortSignal) => detail), capabilities: vi.fn(async () => capabilities), remove: vi.fn(async () => {}) };
  const operations = new DocumentOperations(), confirmed = vi.fn(), visibility = { visible: true };
  const controller = new DocumentDeletion(id, operations, session, transport, confirmed, () => visibility.visible);
  return { identity, session, transport, operations, confirmed, visibility, controller };
}

it('打开确认重读事实，未发布文档仍可删除；取消从不发 DELETE', async () => {
  const f = setup();
  const pending = deferred<DocumentSummary>(); f.transport.detail.mockReturnValueOnce(pending.promise);
  const opened = f.controller.open();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'checking', facts: undefined });
  await f.controller.submit(); expect(f.transport.remove).not.toHaveBeenCalled();
  pending.resolve(detail); await opened;
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'confirming', facts: { detail: { currentVersionId: null } } });
  expect(f.transport.detail).toHaveBeenCalledWith(id, expect.any(AbortSignal));
  f.controller.cancel();
  expect(f.controller.getSnapshot().phase).toBe('idle');
  expect(f.controller.getSnapshot().facts).toBeUndefined();
  expect(f.operations.getSnapshot().exclusive).toBe(false);
  expect(f.transport.remove).not.toHaveBeenCalled();
});

it.each([false, undefined, 'true', 1])('重读 canDelete=%s 时不能使用旧资格提交', async value => {
  const f = setup();
  f.transport.capabilities.mockResolvedValue({ ...capabilities, canDelete: value } as Capabilities);
  await f.controller.open(); await f.controller.submit();
  expect(f.transport.remove).not.toHaveBeenCalled();
});

it('确认刷新故障和错目标响应均不启用删除', async () => {
  for (const failure of [new ApiError(503), new ApiError(404)]) {
    const f = setup(); f.transport.detail.mockRejectedValue(failure);
    await f.controller.open(); await f.controller.submit();
    expect(f.controller.getSnapshot().facts).toBeUndefined();
    expect(f.transport.remove).not.toHaveBeenCalled();
    if (failure.status === 404) expect(f.controller.getSnapshot().showContent).toBe(false);
  }
  const f = setup(); f.transport.detail.mockResolvedValue({ ...detail, id: '1' });
  await f.controller.open(); await f.controller.submit(); expect(f.transport.remove).not.toHaveBeenCalled();
});

it.each([
  { status: 403, allowed: true }, { status: 403, allowed: false },
  { status: 404, allowed: true }, { status: 404, allowed: false },
])('未提交删除时从 $status 恢复为可查看，canDelete=$allowed 不产生拒绝事实或保留删除锁', async ({ status, allowed }) => {
  const f = setup(); f.transport.detail.mockRejectedValueOnce(new ApiError(status));
  await f.controller.open();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'unavailable', attempted: false });
  f.transport.capabilities.mockResolvedValue({ ...capabilities, canDelete: allowed });
  await f.controller.verify();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'idle', showContent: true, attempted: false,
    uncertainResult: false, facts: undefined, message: undefined, rejection: undefined, acknowledgement: undefined });
  expect(f.operations.getSnapshot().exclusive).toBe(false);
  const write = f.operations.beginWrite('comment'); expect(write).not.toBeNull(); write!.settle('confirmed');
  expect(f.transport.remove).not.toHaveBeenCalled(); expect(f.confirmed).not.toHaveBeenCalled();
});

it('未提交删除时核对再次失败可继续重读，不伪造删除拒绝', async () => {
  const f = setup(); f.transport.detail.mockRejectedValueOnce(new ApiError(404)).mockRejectedValueOnce(new ApiError(503));
  await f.controller.open(); await f.controller.verify();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'unavailable', attempted: false, rejection: undefined, showContent: false });
  await f.controller.verify();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'idle', showContent: true, message: undefined });
  expect(f.operations.getSnapshot().exclusive).toBe(false); expect(f.transport.remove).not.toHaveBeenCalled();
});

it('未提交删除的恢复只释放删除独占，保留普通操作的在途和未确认记录', async () => {
  const f = setup(), pending = f.operations.beginWrite('upload')!, uncertain = f.operations.beginWrite('comment')!;
  uncertain.settle('uncertain'); f.transport.detail.mockRejectedValueOnce(new ApiError(404));
  await f.controller.open(); await f.controller.verify();
  expect(f.controller.getSnapshot().phase).toBe('idle');
  expect(f.operations.getSnapshot()).toMatchObject({ exclusive: false, pending: 1, uncertain: 1 });
  pending.settle('confirmed'); expect(f.operations.getSnapshot().uncertain).toBe(1);
  expect(f.transport.remove).not.toHaveBeenCalled();
});

it('普通写入在途时不得提交，转未确认后必须再明确确认一次', async () => {
  const f = setup(), write = f.operations.beginWrite('comment')!;
  await f.controller.open(); await f.controller.submit(); expect(f.transport.remove).not.toHaveBeenCalled();
  write.settle('uncertain'); await f.controller.submit();
  expect(f.controller.getSnapshot().acknowledgement).toBe(1);
  expect(f.transport.remove).not.toHaveBeenCalled();
  await f.controller.submit(true); expect(f.transport.remove).toHaveBeenCalledOnce();
});

it('确认期间独立读取发现撤权或资源不可访问时，旧确认立即失效', async () => {
  const f = setup(); await f.controller.open(); f.controller.invalidateConfirmation(); await f.controller.submit();
  expect(f.controller.getSnapshot().facts).toBeUndefined(); expect(f.transport.remove).not.toHaveBeenCalled();
  f.controller.cancel(); await f.controller.open(); f.controller.invalidateConfirmation(true); await f.controller.submit();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'unavailable', showContent: false, facts: undefined });
});

it('重复事件只发一次 DELETE，提交时隐藏全部旧内容，成功回调仅一次', async () => {
  const f = setup(), pending = deferred<void>(); f.transport.remove.mockReturnValueOnce(pending.promise);
  await f.controller.open();
  const submitted = f.controller.submit(); await f.controller.submit(); await f.controller.open(); f.controller.cancel();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'submitting', showContent: false, facts: undefined });
  expect(f.transport.remove).toHaveBeenCalledOnce();
  pending.resolve(); await submitted;
  expect(f.controller.getSnapshot().phase).toBe('confirmed'); expect(f.confirmed).toHaveBeenCalledOnce();
});

it.each([new ApiError(0), new ApiError(408), new ApiError(500), new ApiError(503), new TypeError('body interrupted'),
  new DOMException('aborted', 'AbortError')])('无法确认的响应仅标记未确认，不自动重发：%s', async error => {
  const f = setup(); f.transport.remove.mockRejectedValue(error);
  await f.controller.open(); await f.controller.submit();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'uncertain', uncertainResult: true, showContent: false });
  expect(f.transport.remove).toHaveBeenCalledOnce(); expect(f.confirmed).not.toHaveBeenCalled();
});

it.each([400, 401, 403, 404, 409])('明确 HTTP %i 拒绝保留事实，有效身份重新只读核对', async status => {
  const f = setup(); f.transport.remove.mockRejectedValue(new ApiError(status));
  await f.controller.open(); await f.controller.submit();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: status === 404 ? 'unavailable' : 'rejected', rejection: status, uncertainResult: false });
  expect(f.transport.remove).toHaveBeenCalledOnce(); expect(f.confirmed).not.toHaveBeenCalled();
  expect(f.transport.detail).toHaveBeenCalledTimes(status === 404 ? 1 : 2);
});

it('有界等待到期仅表示结果未知，迟到成功不导航也不重发', async () => {
  vi.useFakeTimers();
  const f = setup(), pending = deferred<void>(); f.transport.remove.mockReturnValueOnce(pending.promise);
  await f.controller.open(); const submitted = f.controller.submit();
  await vi.advanceTimersByTimeAsync(15001); await submitted;
  expect(f.controller.getSnapshot().phase).toBe('uncertain');
  pending.resolve(); await Promise.resolve();
  expect(f.confirmed).not.toHaveBeenCalled(); expect(f.transport.remove).toHaveBeenCalledOnce();
});

it.each([true, false])('核对 200 canDelete=%s 仅描述当前事实，重新删除必须再重读与确认', async allowed => {
  const f = setup(); f.transport.remove.mockRejectedValueOnce(new ApiError(0));
  await f.controller.open(); await f.controller.submit();
  f.transport.capabilities.mockResolvedValue({ ...capabilities, canDelete: allowed });
  await f.controller.verify();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'uncertain', uncertainResult: true, showContent: true });
  expect(f.transport.remove).toHaveBeenCalledOnce(); expect(f.confirmed).not.toHaveBeenCalled();
  await f.controller.submit(); expect(f.transport.remove).toHaveBeenCalledOnce();
  await f.controller.open();
  expect(f.controller.getSnapshot().phase).toBe(allowed ? 'confirming' : 'uncertain');
  if (allowed) {
    expect(f.transport.detail).toHaveBeenCalledTimes(3);
    await f.controller.submit(); expect(f.transport.remove).toHaveBeenCalledTimes(2);
  }
});

it.each([404, 503, 403])('核对失败 %i 不构造删除成功或保留旧内容', async status => {
  const f = setup(); f.transport.remove.mockRejectedValueOnce(new ApiError(0));
  await f.controller.open(); await f.controller.submit(); f.transport.detail.mockRejectedValue(new ApiError(status));
  await f.controller.verify();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: status === 503 ? 'uncertain' : 'unavailable', facts: undefined, showContent: false, uncertainResult: true });
  expect(f.confirmed).not.toHaveBeenCalled(); expect(f.transport.remove).toHaveBeenCalledOnce();
});

it('先前结果未确认后，新删除被明确拒绝仍保留本次拒绝事实', async () => {
  const f = setup(); f.transport.remove.mockRejectedValueOnce(new ApiError(0)).mockRejectedValueOnce(new ApiError(409));
  await f.controller.open(); await f.controller.submit(); await f.controller.verify(); await f.controller.open(); await f.controller.submit();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'rejected', rejection: 409, uncertainResult: true });
  expect(f.transport.remove).toHaveBeenCalledTimes(2); expect(f.confirmed).not.toHaveBeenCalled();
});

it('核对成功后又失去访问资格立即清除事实，取消重新确认也不解除未确认写入门禁', async () => {
  const f = setup(); f.transport.remove.mockRejectedValueOnce(new ApiError(0));
  await f.controller.open(); await f.controller.submit(); await f.controller.verify();
  await f.controller.open(); f.controller.cancel();
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'uncertain', showContent: false });
  expect(f.operations.getSnapshot().exclusive).toBe(true);
  await f.controller.verify(); f.controller.invalidateConfirmation(true);
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'unavailable', facts: undefined, showContent: false });
});

it('成功后的页面回调故障不推翻合法删除成功', async () => {
  const f = setup(); f.confirmed.mockImplementation(() => { throw new Error('navigation failed'); });
  await f.controller.open(); await expect(f.controller.submit()).rejects.toThrow('navigation failed');
  expect(f.controller.getSnapshot().phase).toBe('confirmed'); expect(f.transport.remove).toHaveBeenCalledOnce();
});

it('核对也有有界等待，失败后可由用户再次主动核对', async () => {
  vi.useFakeTimers();
  const f = setup(); f.transport.remove.mockRejectedValueOnce(new ApiError(503));
  await f.controller.open(); await f.controller.submit();
  f.transport.detail.mockReturnValueOnce(new Promise(() => {})); const verifying = f.controller.verify();
  await vi.advanceTimersByTimeAsync(15001); await verifying;
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'uncertain', facts: undefined });
  await f.controller.verify(); expect(f.controller.getSnapshot().facts).toBeDefined();
  expect(f.transport.remove).toHaveBeenCalledOnce();
});

it.each([
  { status: 0, lateFailure: false }, { status: 0, lateFailure: true },
  { status: 409, lateFailure: false }, { status: 409, lateFailure: true },
])('删除结果 $status 的在途核对被作废后可重试，迟到故障=$lateFailure 不覆盖新事实', async ({ status, lateFailure }) => {
  const f = setup(); f.transport.remove.mockRejectedValueOnce(new ApiError(status));
  await f.controller.open(); await f.controller.submit();
  const late = deferred<DocumentSummary>(); f.transport.detail.mockReturnValueOnce(late.promise);
  const verifying = f.controller.verify();
  expect(f.controller.getSnapshot().phase).toBe('verifying');
  const signal = f.transport.detail.mock.calls.at(-1)![1];
  f.controller.invalidateConfirmation(false);
  expect(signal.aborted).toBe(true);
  expect(f.controller.getSnapshot()).toMatchObject({ phase: status === 0 ? 'uncertain' : 'rejected', attempted: true,
    uncertainResult: status === 0, rejection: status === 0 ? undefined : status, facts: undefined, showContent: false });
  expect(f.operations.getSnapshot().exclusive).toBe(true);
  await f.controller.verify();
  expect(f.controller.getSnapshot().facts?.detail.title).toBe(detail.title);
  if (lateFailure) late.reject(new ApiError(503));
  else late.resolve({ ...detail, title: '迟到的旧标题' });
  await verifying;
  expect(f.controller.getSnapshot()).toMatchObject({ phase: status === 0 ? 'uncertain' : 'rejected',
    facts: { detail: { title: detail.title } }, showContent: true });
  expect(f.operations.getSnapshot().exclusive).toBe(true);
  expect(f.transport.remove).toHaveBeenCalledOnce(); expect(f.confirmed).not.toHaveBeenCalled();
});

it('未提交删除的在途核对被作废后保留重读入口，恢复成功才释放锁', async () => {
  const f = setup(); f.transport.detail.mockRejectedValueOnce(new ApiError(404)); await f.controller.open();
  const late = deferred<DocumentSummary>(); f.transport.detail.mockReturnValueOnce(late.promise);
  const verifying = f.controller.verify(); f.controller.invalidateConfirmation(false);
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'unavailable', attempted: false, facts: undefined, showContent: false });
  expect(f.operations.getSnapshot().exclusive).toBe(true);
  await f.controller.verify(); late.resolve({ ...detail, title: '迟到的旧标题' }); await verifying;
  expect(f.controller.getSnapshot()).toMatchObject({ phase: 'idle', facts: undefined, message: undefined, showContent: true });
  expect(f.operations.getSnapshot().exclusive).toBe(false);
  expect(f.transport.remove).not.toHaveBeenCalled(); expect(f.confirmed).not.toHaveBeenCalled();
});

it.each(['换用户', '退出', '强制改密', '切换文档', '隐藏', '离开页面'])('%s 后旧成功不导航，也不向新作用域反馈', async reason => {
  const f = setup(), pending = deferred<void>(); f.transport.remove.mockReturnValueOnce(pending.promise);
  await f.controller.open(); const submitted = f.controller.submit();
  if (reason === '换用户') f.identity.epoch++;
  else if (reason === '退出') f.identity.phase = 'anonymous';
  else if (reason === '强制改密') f.identity.phase = 'password';
  else { if (reason === '隐藏') f.visibility.visible = false; f.controller.dispose(); }
  pending.resolve(); await submitted;
  expect(f.confirmed).not.toHaveBeenCalled(); expect(f.controller.getSnapshot().phase).not.toBe('confirmed');
});

it('确认旧读取在取消、隐藏恢复后不能恢复能力，新控制器不恢复旧 DELETE', async () => {
  const f = setup(), late = deferred<Capabilities>(); f.transport.capabilities.mockReturnValueOnce(late.promise);
  const reading = f.controller.open(); f.controller.cancel(); late.resolve(capabilities); await reading;
  expect(f.controller.getSnapshot().phase).toBe('idle');
  expect(f.controller.getSnapshot().facts).toBeUndefined();
  await f.controller.open(); f.controller.dispose(); f.controller.activate();
  expect(f.controller.getSnapshot().phase).toBe('idle');
  expect(f.controller.getSnapshot().facts).toBeUndefined();
  expect(f.transport.remove).not.toHaveBeenCalled();
});

it('请求适配仅提交精确父文档 ID、DELETE 和 signal，没有版本、正文或伪造请求标识', async () => {
  const session = { send: vi.fn(async () => json(null)), json: vi.fn() }, signal = new AbortController().signal;
  await deletionTransport(session).remove(id, signal);
  expect(session.send).toHaveBeenCalledExactlyOnceWith(`/api/documents/${id}`, { method: 'DELETE', signal });
});

it.each([() => new Response(null, { status: 204 }), () => json({}), () => json(undefined),
  () => json({ code: 500, data: null }, false), () => new Response('<html>ok</html>'),
  () => new Response('{"code":200,', { headers: { 'Content-Type': 'application/json' } })])('非既有成功契约不能冒充删除成功', async response => {
  const session = { send: vi.fn(async () => response()), json: vi.fn() };
  await expect(deletionTransport(session).remove(id, new AbortController().signal)).rejects.toBeDefined();
});

it('正文读取中断不能被当作合法成功', async () => {
  const body = new ReadableStream({ start(controller) { controller.error(new TypeError('interrupted')); } });
  const session = { send: vi.fn(async () => new Response(body, { headers: { 'Content-Type': 'application/json' } })), json: vi.fn() };
  await expect(deletionTransport(session).remove(id, new AbortController().signal)).rejects.toThrow('interrupted');
});
