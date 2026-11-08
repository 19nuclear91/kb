import { mount, flushPromises } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { afterEach, expect, it, vi } from 'vitest';
import { useSessionStore } from '../stores/session';
import type { Session } from '../session';
import { importApi, ImportError, type Batch, type ImportItem, type Page } from './api';
import { deferred } from '../test/fixtures';
import ImportTask from './ImportTask.vue';
import ImportWorkspace from './ImportWorkspace.vue';
import { FileHasher } from './files';
const batch: Batch = { importId: '9223372036854775807', createdAt: '2026-11-08T00:00:00Z', state: 'OPEN', counts: { pending: 1, processing: 0, accepted: 1, failed: 0 }, canContinue: true, spaceId: '8', securityLevel: 9 };
const item = (id: string, ordinal: number): ImportItem => ({ itemId: id, importId: batch.importId, ordinal, revision: 9007199254740993n, state: 'PENDING', canUpload: true,
  file: { itemKey: id, fileName: '<b>same.txt', title: '标题', sizeBytes: 5, sha256: 'a'.repeat(64) } });
function global() {
  const pinia = createPinia(); useSessionStore(pinia).bind({ getSnapshot: () => ({ epoch: 1, phase: 'ready', me: null }), subscribe: () => () => {},
    json: vi.fn(async () => ({ items: [], nextCursor: null })) } as unknown as Session);
  return { plugins: [pinia], stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } };
}
afterEach(() => vi.restoreAllMocks());
it('本人列表区分空、故障和不支持，不回退旧上传', async () => {
  const list = vi.spyOn(importApi, 'list').mockResolvedValue({ items: [], hasMore: false, nextCursor: null });
  vi.spyOn(importApi, 'capabilities').mockRejectedValue(new ImportError(404));
  const view = mount(ImportWorkspace, { global: global() }); await flushPromises();
  expect(view.text()).toContain('暂无导入任务'); expect(view.text()).toContain('暂不支持批量导入');
  list.mockRejectedValue(new ImportError(503)); await view.findAll('button').find(button => button.text() === '刷新任务列表')!.trigger('click'); await flushPromises();
  expect(view.text()).not.toContain('暂无导入任务'); expect(view.text()).toContain('尚未确认'); view.unmount();
});
it('任务列表分页保留精确身份并显示末页', async () => {
  vi.spyOn(importApi, 'capabilities').mockRejectedValue(new ImportError(404));
  const list = vi.spyOn(importApi, 'list').mockResolvedValueOnce({ items: [batch], hasMore: true, nextCursor: 'cursor' })
    .mockResolvedValueOnce({ items: [{ ...batch, importId: '7' }], hasMore: false, nextCursor: null });
  const view = mount(ImportWorkspace, { global: global() }); await flushPromises();
  expect(view.get('a').attributes('href')).toBe(`/imports/${batch.importId}`);
  await view.findAll('button').find(button => button.text() === '更多导入任务')!.trigger('click'); await flushPromises();
  expect(list.mock.calls[1]![1]).toBe('cursor'); expect(view.text()).toContain('已到末页'); view.unmount();
});
it('项分页按身份区分同名文件、按文本显示名称并独立展示计数', async () => {
  vi.spyOn(importApi, 'batch').mockResolvedValue(batch);
  const items = vi.spyOn(importApi, 'items').mockResolvedValueOnce({ items: [item('9223372036854775806', 1)], hasMore: true, nextCursor: 'cursor' })
    .mockResolvedValueOnce({ items: [item('9223372036854775805', 2)], hasMore: false, nextCursor: null });
  const view = mount(ImportTask, { props: { importId: batch.importId }, global: global() }); await flushPromises();
  expect(view.text()).toContain('待上传 1 / 处理中 0 / 已受理 1 / 失败 0'); expect(view.text()).toContain('本页回执待确认 0 项');
  await view.findAll('button').find(button => button.text() === '更多文件项')!.trigger('click'); await flushPromises();
  expect(items.mock.calls[1]![2]).toBe('cursor'); expect(view.findAll('h3')).toHaveLength(2); expect(view.find('b').exists()).toBe(false);
  expect(view.text()).toContain('文件项已到末页'); view.unmount();
});
it('撤权投影清理原名称摘要，仍保留本人受理回执', async () => {
  const summary = vi.spyOn(importApi, 'batch').mockResolvedValue(batch);
  const items = vi.spyOn(importApi, 'items').mockResolvedValue({ items: [item('2', 1)], hasMore: false, nextCursor: null });
  const view = mount(ImportTask, { props: { importId: batch.importId }, global: global() }); await flushPromises();
  summary.mockResolvedValue({ ...batch, canContinue: false, spaceId: undefined, securityLevel: undefined });
  items.mockResolvedValue({ items: [{ ...item('2', 1), file: undefined, canUpload: false, state: 'ACCEPTED', receipt: { documentId: '10', versionId: '20', parseTaskId: '30' } }], hasMore: false, nextCursor: null });
  await view.findAll('button').find(button => button.text() === '刷新当前任务')!.trigger('click'); await flushPromises();
  expect(view.text()).not.toContain('same.txt'); expect(view.text()).not.toContain('固定空间'); expect(view.text()).toContain('固定版本 20'); view.unmount();
});
it('重新选择匹配文件只准备；用户继续时用最新 revision，新标识，成功项不能重传', async () => {
  vi.spyOn(importApi, 'capabilities').mockResolvedValue({ protocolVersion: 1, maxItems: 2, maxTotalBytes: 20, maxFileBytes: 10, maxManifestBytes: 1000, uploadConcurrency: 2, extensions: ['txt'] });
  vi.spyOn(importApi, 'batch').mockResolvedValue(batch);
  const original = item('2', 1), latest = { ...original, revision: 9007199254740995n };
  vi.spyOn(importApi, 'items').mockResolvedValue({ items: [original], hasMore: false, nextCursor: null });
  vi.spyOn(importApi, 'item').mockResolvedValue(latest);
  const upload = vi.spyOn(importApi, 'upload').mockResolvedValue({ documentId: '10', versionId: '20', parseTaskId: '30' });
  const hash = vi.spyOn(FileHasher.prototype, 'hash').mockResolvedValue('b'.repeat(64));
  const view = mount(ImportTask, { props: { importId: batch.importId }, global: global() }); await flushPromises();
  const input = view.get('input[type=file]'); Object.defineProperty(input.element, 'files', { value: [new File(['hello'], '<b>same.txt')] });
  await input.trigger('change'); await flushPromises(); expect(view.text()).not.toContain('已匹配，等待主动继续'); expect(upload).not.toHaveBeenCalled();
  hash.mockResolvedValue('a'.repeat(64)); await input.trigger('change'); await flushPromises();
  expect(view.text()).toContain('已匹配，等待主动继续'); expect(upload).not.toHaveBeenCalled();
  await view.findAll('button').find(button => button.text() === '继续第 1 项')!.trigger('click'); await flushPromises();
  expect(upload).toHaveBeenCalledTimes(1); expect(upload.mock.calls[0]![1].revision).toBe(latest.revision);
  expect(upload.mock.calls[0]![3]).toMatch(/^[a-f0-9-]{36}$/); expect(view.find('input[type=file]').exists()).toBe(false); view.unmount();
});
it('单项资格拒绝立即清除批次目标及清单描述', async () => {
  vi.spyOn(importApi, 'batch').mockResolvedValue(batch);
  vi.spyOn(importApi, 'items').mockResolvedValue({ items: [item('2', 1)], hasMore: false, nextCursor: null });
  vi.spyOn(importApi, 'capabilities').mockRejectedValue(new ImportError(503));
  vi.spyOn(importApi, 'item').mockRejectedValue(new ImportError(403));
  const view = mount(ImportTask, { props: { importId: batch.importId }, global: global() }); await flushPromises();
  expect(view.text()).toContain('固定空间');
  await view.findAll('button').find(button => button.text() === '核对第 1 项')!.trigger('click'); await flushPromises();
  expect(view.text()).not.toContain('固定空间'); expect(view.text()).not.toContain('same.txt');
  expect(view.text()).toContain('仅显示本人最小历史'); view.unmount();
});

it('撤权前的任务读取迟到不能恢复清单，新核对恢复后仍需重选并主动继续', async () => {
  const original = item('2', 1), page = { items: [original], hasMore: false, nextCursor: null };
  const summary = vi.spyOn(importApi, 'batch').mockResolvedValue(batch);
  const pages = vi.spyOn(importApi, 'items').mockResolvedValue(page);
  vi.spyOn(importApi, 'capabilities').mockResolvedValue({ protocolVersion: 1, maxItems: 2, maxTotalBytes: 20, maxFileBytes: 10, maxManifestBytes: 1000, uploadConcurrency: 2, extensions: ['txt'] });
  const read = vi.spyOn(importApi, 'item').mockResolvedValue(original);
  const upload = vi.spyOn(importApi, 'upload').mockResolvedValue({ documentId: '10', versionId: '20', parseTaskId: '30' });
  vi.spyOn(FileHasher.prototype, 'hash').mockResolvedValue('a'.repeat(64));
  const view = mount(ImportTask, { props: { importId: batch.importId }, global: global() });
  const click = async (name: string) => { await view.findAll('button').find(button => button.text() === name)!.trigger('click'); await flushPromises(); };
  const select = async () => {
    const input = view.get('input[type=file]');
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['hello'], '<b>same.txt')] });
    await input.trigger('change'); await flushPromises();
  };
  try {
    await flushPromises(); await select(); expect(view.text()).toContain('已匹配，等待主动继续');
    const checking = deferred<ImportItem>(); read.mockReturnValueOnce(checking.promise);
    await click('核对第 1 项');
    const oldSummary = deferred<Batch>(), oldPage = deferred<Page<ImportItem>>();
    summary.mockReturnValueOnce(oldSummary.promise); pages.mockReturnValueOnce(oldPage.promise);
    await click('刷新当前任务');
    checking.reject(new ImportError(403)); await flushPromises();
    expect(view.text()).not.toContain('same.txt'); expect(view.text()).not.toContain('固定空间');
    oldSummary.resolve(batch); oldPage.resolve(page); await flushPromises();
    expect(view.text()).not.toContain('same.txt'); expect(view.text()).not.toContain('固定空间');
    expect(view.find('input[type=file]').exists()).toBe(false);
    await click('刷新当前任务');
    expect(view.text()).toContain('same.txt'); expect(view.text()).toContain('固定空间');
    expect(view.text()).not.toContain('已匹配，等待主动继续'); expect(upload).not.toHaveBeenCalled();
    await click('主动继续已选文件'); expect(upload).not.toHaveBeenCalled();
    await select(); expect(upload).not.toHaveBeenCalled();
    await click('继续第 1 项'); expect(upload).toHaveBeenCalledTimes(1);
  } finally { view.unmount(); }
});

it('下一页最小投影覆盖旧摘要资格，清理已加载页并保留受理回执', async () => {
  vi.spyOn(importApi, 'batch').mockResolvedValue(batch);
  vi.spyOn(importApi, 'capabilities').mockRejectedValue(new ImportError(503));
  vi.spyOn(importApi, 'items').mockResolvedValueOnce({ items: [item('2', 1)], hasMore: true, nextCursor: 'cursor' })
    .mockResolvedValueOnce({ items: [{ ...item('3', 2), file: undefined, canUpload: false, state: 'ACCEPTED', receipt: { documentId: '10', versionId: '20', parseTaskId: '30' } }], hasMore: false, nextCursor: null });
  const view = mount(ImportTask, { props: { importId: batch.importId }, global: global() });
  try {
    await flushPromises();
    await view.findAll('button').find(button => button.text() === '更多文件项')!.trigger('click'); await flushPromises();
    expect(view.text()).not.toContain('same.txt'); expect(view.text()).not.toContain('固定空间');
    expect(view.find('input[type=file]').exists()).toBe(false); expect(view.text()).toContain('固定版本 20');
  } finally { view.unmount(); }
});

it.each(['summary', 'items'] as const)('%s 先返回最小投影时立即撤权，不等待另一个请求结束', async first => {
  const summary = vi.spyOn(importApi, 'batch').mockResolvedValue(batch);
  const pages = vi.spyOn(importApi, 'items').mockResolvedValue({ items: [item('2', 1)], hasMore: false, nextCursor: null });
  vi.spyOn(importApi, 'capabilities').mockRejectedValue(new ImportError(503));
  const view = mount(ImportTask, { props: { importId: batch.importId }, global: global() });
  try {
    await flushPromises();
    const nextSummary = deferred<Batch>(), nextPage = deferred<Page<ImportItem>>();
    summary.mockReturnValueOnce(nextSummary.promise); pages.mockReturnValueOnce(nextPage.promise);
    await view.findAll('button').find(button => button.text() === '刷新当前任务')!.trigger('click');
    if (first === 'summary') nextSummary.resolve({ ...batch, canContinue: false, spaceId: undefined, securityLevel: undefined });
    else nextPage.resolve({ items: [{ ...item('2', 1), file: undefined, canUpload: false }], hasMore: false, nextCursor: null });
    await flushPromises();
    expect(view.text()).not.toContain('same.txt'); expect(view.text()).not.toContain('固定空间');
    nextSummary.resolve(batch); nextPage.resolve({ items: [item('2', 1)], hasMore: false, nextCursor: null });
    await flushPromises();
    expect(view.text()).not.toContain('same.txt'); expect(view.text()).not.toContain('固定空间');
  } finally { view.unmount(); }
});

it('摘要计算期间撤权，即使新读取恢复资格，旧摘要结果也不能重新选中文件', async () => {
  const original = item('2', 1);
  vi.spyOn(importApi, 'batch').mockResolvedValue(batch);
  vi.spyOn(importApi, 'items').mockResolvedValue({ items: [original], hasMore: false, nextCursor: null });
  vi.spyOn(importApi, 'capabilities').mockResolvedValue({ protocolVersion: 1, maxItems: 2, maxTotalBytes: 20, maxFileBytes: 10, maxManifestBytes: 1000, uploadConcurrency: 2, extensions: ['txt'] });
  const read = vi.spyOn(importApi, 'item').mockResolvedValue(original);
  const upload = vi.spyOn(importApi, 'upload');
  const late = deferred<string>(); vi.spyOn(FileHasher.prototype, 'hash').mockReturnValue(late.promise);
  const view = mount(ImportTask, { props: { importId: batch.importId }, global: global() });
  try {
    await flushPromises();
    const input = view.get('input[type=file]'); Object.defineProperty(input.element, 'files', { value: [new File(['hello'], '<b>same.txt')] });
    await input.trigger('change'); await flushPromises();
    read.mockRejectedValueOnce(new ImportError(403));
    await view.findAll('button').find(button => button.text() === '核对第 1 项')!.trigger('click'); await flushPromises();
    expect(view.text()).not.toContain('same.txt');
    await view.findAll('button').find(button => button.text() === '刷新当前任务')!.trigger('click'); await flushPromises();
    expect(view.text()).toContain('same.txt');
    late.resolve('a'.repeat(64)); await flushPromises();
    expect(view.text()).not.toContain('已匹配，等待主动继续'); expect(upload).not.toHaveBeenCalled();
    await view.findAll('button').find(button => button.text() === '主动继续已选文件')!.trigger('click'); await flushPromises();
    expect(upload).not.toHaveBeenCalled();
  } finally { view.unmount(); }
});
