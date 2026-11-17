import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { h, nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import { renderHook } from '../test/render';
import DocumentDetailPage from '../pages/DocumentDetailPage.vue';
import { useProgress } from '../documents/useProgress';
import { ApiError } from '../api';
import { deferred, json } from '../test/fixtures';
import type { ReviewStatus } from './api';
import { note } from '../test/versionDiff';
import type { Note } from '../changeNotes/api';

const changeNotes = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../changeNotes/api', async original => ({ ...await original<object>(), changeNotesApi: { get: changeNotes.get } }));

const f = reactive({ json: vi.fn(), send: vi.fn(), epoch: 1, phase: 'ready' });
const unitSession = { json: f.json, send: f.send, getSnapshot: () => ({ epoch: f.epoch, phase: f.phase }), subscribe: () => () => {} };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: unitSession, get state() { return { epoch: f.epoch, phase: f.phase }; } }) }));
vi.mock('../favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false, status: () => ({ phase: 'loading' }) }) }));
vi.mock('../favorites/FavoriteButton.vue', () => ({ default: { render: () => null } }));
vi.mock('../office/VersionPreview.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: { path: String }, emits: ['denied'], setup: (props, { emit }) => () => h('div', [
    h('output', props.path ?? '预览已清除'), props.path && h('button', { onClick: () => emit('denied') }, '模拟文件拒绝'),
  ]) }) };
});
vi.mock('../DownloadButton.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: { path: String, disabled: Boolean }, setup: props => () => h('button', { disabled: props.disabled, 'data-path': props.path }, '下载原件') }) };
});
const versions = [
  { id: '12', versionNo: 2, current: false, fileName: 'v2-private.pdf', indexStatus: 'indexed', publicationPolicy: 'REVIEW_REQUIRED', publicationEvidence: 'NONE', reviewState: 'IN_REVIEW' },
  { id: '11', versionNo: 1, current: true, fileName: 'v1.pdf', indexStatus: 'indexed', publicationPolicy: 'AUTO', publicationEvidence: 'PUBLISHED' },
];
const document = { id: '1', title: '文档', spaceId: '9', currentVersionId: '11', currentVersionNo: 1, securityLevel: 1, versions };
function status(id = '11', canRead = true): ReviewStatus {
  return { state: { documentId: '1', versionId: id, versionNo: id === '11' ? 1 : 2, stateVersion: '7', indexStatus: 'indexed', policy: id === '11' ? 'AUTO' : 'REVIEW_REQUIRED',
    publicationEvidence: id === '11' ? 'PUBLISHED' : 'NONE', reviewState: id === '11' ? 'DRAFT' : 'IN_REVIEW' },
    capabilities: { canRead, canDownload: false, canSubmit: false, canApprove: false, canReject: false, canWithdraw: false, canRefresh: false, canReadOpinions: false }, rounds: [], hasMore: false, nextCursor: null };
}
beforeEach(() => {
  changeNotes.get.mockReset().mockRejectedValue(new ApiError(503));
  f.epoch++; f.phase = 'ready'; f.json.mockReset(); f.send.mockReset().mockImplementation(async () => new Response(null, { status: 503 }));
  f.json.mockImplementation(async (path: string) => {
    if (path.endsWith('/capabilities')) {
      return { canDownload: true, canRetry: false, canUploadVersion: false };
    }
    if (path.endsWith('/review')) {
      return status(path.split('/')[5]);
    }
    if (path.includes('/progress')) {
      return { versionId: path.endsWith('12') ? '12' : '11', indexStatus: 'indexed' };
    }
    return document;
  });
});
afterEach(() => { vi.useRealTimers(); });
const show = () => mountRouter(() => h(DocumentDetailPage), { path: '/documents/1' });
it.each(['说明基准', '没有说明', '基准不可见'])('详情对比默认值：%s；选项只包含可见版本', async kind => {
  const visible = [
    { ...versions[1], id: '13', versionNo: 3, current: true },
    { ...versions[1], id: '12', versionNo: 2, current: false }, { ...versions[1], current: false },
  ];
  const original = f.json.getMockImplementation()!;
  f.json.mockImplementation((path: string) => path === '/api/documents/1' ? Promise.resolve({ ...document, currentVersionId: '13', currentVersionNo: 3, versions: visible }) : original(path));
  changeNotes.get.mockResolvedValue({ ...note, documentId: '1', versionId: '13', hasNote: kind !== '没有说明', text: kind === '没有说明' ? null : '说明基准',
    baseVersionId: kind === '说明基准' ? '11' : kind === '基准不可见' ? '19' : null, baseVersionNo: kind === '没有说明' ? null : 1 });
  show(); const link = await screen.findByRole('link', { name: '打开对比' });
  await waitFor(() => expect(link.getAttribute('href')).toBe(`/documents/1/compare/${kind === '说明基准' ? '11' : '12'}/13`));
  const base = screen.getByRole('combobox', { name: '基准版本' }) as HTMLSelectElement;
  const target = screen.getByRole('combobox', { name: '目标版本' }) as HTMLSelectElement;
  expect(target.value).toBe('13'); expect([...base.options].map(option => option.value)).toEqual(['', '13', '12', '11']);
  expect([...target.options].map(option => option.value)).not.toContain('19');
});
it('一个可见版本时详情不显示对比入口或其他版本选项', async () => {
  const original = f.json.getMockImplementation()!;
  f.json.mockImplementation((path: string) => path === '/api/documents/1' ? Promise.resolve({ ...document, versions: [versions[1]] }) : original(path));
  show(); await screen.findByText('当前发布：v1'); await flushPromises();
  expect(screen.queryByRole('combobox', { name: '基准版本' })).toBeNull(); expect(screen.queryByRole('link', { name: '打开对比' })).toBeNull();
});
const selectedNote: Note = { ...note, documentId: '1', versionId: '11', baseVersionId: '10', writeBaseVersionId: '10', visibleToReaders: true };
const noteWrites = () => f.send.mock.calls.filter(([path, init]) => String(path).endsWith('/change-note') && init?.method === 'PUT');
async function unconfirmedNote() {
  changeNotes.get.mockResolvedValue(selectedNote);
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init: RequestInit) => path.endsWith('/change-note') && init?.method === 'PUT'
    ? Promise.reject(new ApiError(0)) : original(path, init));
  const page = show();
  await fireEvent.click(await screen.findByRole('button', { name: '编辑变更说明' }));
  await fireEvent.update(screen.getByRole('textbox', { name: '变更说明正文' }), '刷新后仍保留的原载荷');
  await fireEvent.update(screen.getByRole('textbox', { name: '变更说明更正原因' }), '原更正原因');
  await fireEvent.submit(screen.getByRole('button', { name: '保存变更说明' }).closest('form')!);
  await screen.findByRole('button', { name: '按原内容重试' });
  return page;
}
it.each(['旧文本', '新文本', '503'])('资格重读说明为%s 后仍以原请求和精确载荷重试', async result => {
  await unconfirmedNote();
  const first = noteWrites()[0]!;
  if (result === '503') changeNotes.get.mockRejectedValue(new ApiError(503));
  else changeNotes.get.mockResolvedValue({ ...selectedNote, ...(result === '新文本' ? { text: '刷新后仍保留的原载荷', noteVersion: 2 } : {}) });
  await fireEvent.click(screen.getByRole('button', { name: '重新核对操作资格' }));
  await fireEvent.click(await screen.findByRole('button', { name: '继续核对说明结果' }));
  expect((screen.getByRole('textbox', { name: '变更说明正文' }) as HTMLTextAreaElement).value).toBe('刷新后仍保留的原载荷');
  expect((screen.getByRole('textbox', { name: '变更说明更正原因' }) as HTMLTextAreaElement).value).toBe('原更正原因');
  expect((screen.getByRole('button', { name: '保存变更说明' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '关闭说明编辑' }));
  await fireEvent.click(screen.getByRole('button', { name: '继续核对说明结果' }));
  expect(noteWrites()).toHaveLength(1);
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init: RequestInit) => path.endsWith('/change-note') && init?.method === 'PUT'
    ? Promise.resolve(json({ documentId: '1', versionId: '11', noteVersion: 2, corrected: true, createdAfterVisible: false,
      updatedAt: '2026-11-17T00:00:00Z', replayed: true })) : original(path, init));
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' }));
  await screen.findByText('变更说明已保存。');
  const retried = noteWrites()[1]!;
  expect(retried[0]).toBe(first[0]); expect(retried[1].headers['X-Request-Id']).toBe(first[1].headers['X-Request-Id']);
  expect(retried[1].body).toBe(first[1].body);
});
it('详情读取失败再恢复仍保留未确认说明；显式放弃后不恢复原请求', async () => {
  await unconfirmedNote();
  const original = f.json.getMockImplementation()!;
  f.json.mockImplementation((path: string) => path === '/api/documents/1' ? Promise.reject(new ApiError(503)) : original(path));
  await fireEvent.click(screen.getByRole('button', { name: '重新核对操作资格' })); await flushPromises();
  expect(screen.queryByRole('button', { name: '继续核对说明结果' })).toBeNull();
  f.json.mockImplementation(original);
  await fireEvent.click(screen.getByRole('button', { name: '重新核对操作资格' }));
  await screen.findByRole('button', { name: '继续核对说明结果' });
  f.json.mockImplementation((path: string) => path === '/api/documents/1' ? Promise.reject(new ApiError(503)) : original(path));
  await fireEvent.click(screen.getByRole('button', { name: '重新核对操作资格' })); await flushPromises();
  await fireEvent.click(screen.getByRole('button', { name: '放弃适用期或说明未确认结果核对（不会撤回写入）' }));
  f.json.mockImplementation(original);
  await fireEvent.click(screen.getByRole('button', { name: '重新核对操作资格' })); await flushPromises();
  expect(screen.queryByRole('button', { name: '继续核对说明结果' })).toBeNull(); expect(noteWrites()).toHaveLength(1);
});
it('资格重读卸载在途说明时保留绑定，旧成功响应不更新新面板', async () => {
  changeNotes.get.mockResolvedValue(selectedNote);
  const pending = deferred<Response>(), original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init: RequestInit) => path.endsWith('/change-note') && init?.method === 'PUT'
    ? pending.promise : original(path, init));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '编辑变更说明' }));
  await fireEvent.update(screen.getByRole('textbox', { name: '变更说明正文' }), '在途原载荷');
  await fireEvent.update(screen.getByRole('textbox', { name: '变更说明更正原因' }), '原原因');
  await fireEvent.submit(screen.getByRole('button', { name: '保存变更说明' }).closest('form')!);
  const first = noteWrites()[0]!;
  await fireEvent.click(screen.getByRole('button', { name: '重新核对操作资格' }));
  await fireEvent.click(await screen.findByRole('button', { name: '继续核对说明结果' }));
  expect(first[1].signal.aborted).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); expect(noteWrites()).toHaveLength(1);
  const receipt = { documentId: '1', versionId: '11', noteVersion: 2, corrected: true, createdAfterVisible: false,
    updatedAt: '2026-11-17T00:00:00Z', replayed: true };
  pending.resolve(json(receipt)); await flushPromises();
  expect(screen.queryByText('变更说明已保存。')).toBeNull();
  f.send.mockImplementation((path: string, init: RequestInit) => path.endsWith('/change-note') && init?.method === 'PUT'
    ? Promise.resolve(json(receipt)) : original(path, init));
  await fireEvent.click(screen.getByRole('button', { name: '按原内容重试' })); await screen.findByText('变更说明已保存。');
  expect(noteWrites()[1]![1].headers['X-Request-Id']).toBe(first[1].headers['X-Request-Id']);
  expect(noteWrites()[1]![1].body).toBe(first[1].body);
});
it('切换详情版本并返回不恢复旧说明的请求或草稿', async () => {
  await unconfirmedNote();
  changeNotes.get.mockImplementation(async (_session, pair) => ({ ...selectedNote, versionId: pair.versionId }));
  await fireEvent.click(screen.getByRole('button', { name: 'v2' })); await flushPromises();
  await fireEvent.click(screen.getByRole('button', { name: 'v1 · 当前发布' })); await flushPromises();
  expect(screen.queryByRole('button', { name: '继续核对说明结果' })).toBeNull();
  expect(screen.queryByRole('textbox', { name: '变更说明正文' })).toBeNull(); expect(noteWrites()).toHaveLength(1);
});
function comparisonVersions() {
  const visible = [
    { ...versions[1], id: '14', versionNo: 4, current: true }, { ...versions[1], id: '13', versionNo: 3, current: false },
    { ...versions[1], id: '12', versionNo: 2, current: false }, { ...versions[1], current: false },
  ];
  const original = f.json.getMockImplementation()!;
  f.json.mockImplementation((path: string) => path === '/api/documents/1'
    ? Promise.resolve({ ...document, currentVersionId: '14', currentVersionNo: 4, versions: visible }) : original(path));
}
it.each(['说明基准', '没有说明', '基准不可见', '503'])('切换对比目标后按该目标的%s 确定基准', async result => {
  comparisonVersions();
  changeNotes.get.mockImplementation(async (_session, pair) => {
    if (pair.versionId === '13' && result === '503') throw new ApiError(503);
    return { ...note, documentId: '1', versionId: pair.versionId, hasNote: result !== '没有说明',
      baseVersionId: pair.versionId === '13' ? result === '没有说明' ? null : result === '基准不可见' ? '19' : '11' : '13' };
  });
  show(); const target = await screen.findByRole('combobox', { name: '目标版本' });
  const base = screen.getByRole('combobox', { name: '基准版本' }) as HTMLSelectElement;
  await waitFor(() => expect(base.value).toBe('13'));
  await fireEvent.update(target, '13'); await flushPromises();
  expect(changeNotes.get.mock.calls.some(([, pair]) => pair.versionId === '13')).toBe(true);
  expect(base.value).toBe(result === '说明基准' ? '11' : result === '503' ? '' : '12');
  if (result === '503') {
    expect(screen.queryByRole('link', { name: '打开对比' })).toBeNull();
    await fireEvent.update(base, '11');
  }
  expect(screen.getByRole('link', { name: '打开对比' }).getAttribute('href')).toBe(`/documents/1/compare/${base.value}/13`);
});
it('旧对比目标说明迟到不覆盖新目标，手选基准不被在途读取覆盖', async () => {
  comparisonVersions();
  const old = deferred<Note>(), current = deferred<Note>(); let oldSignal: AbortSignal | undefined;
  changeNotes.get.mockImplementation((_session, pair, signal) => {
    if (pair.versionId === '13') { oldSignal = signal; return old.promise; }
    if (pair.versionId === '12') return current.promise;
    return Promise.resolve({ ...note, documentId: '1', versionId: pair.versionId, baseVersionId: '13' });
  });
  show(); const target = await screen.findByRole('combobox', { name: '目标版本' });
  const base = screen.getByRole('combobox', { name: '基准版本' }) as HTMLSelectElement;
  await fireEvent.update(target, '13'); await fireEvent.update(target, '12');
  expect(oldSignal?.aborted).toBe(true); expect(base.value).toBe('');
  old.resolve({ ...note, documentId: '1', versionId: '13', baseVersionId: '11' }); await flushPromises();
  expect(base.value).toBe(''); expect(screen.queryByRole('link', { name: '打开对比' })).toBeNull();
  await fireEvent.update(base, '14');
  current.resolve({ ...note, documentId: '1', versionId: '12', baseVersionId: '11' }); await flushPromises();
  expect(base.value).toBe('14');
  expect(screen.getByRole('link', { name: '打开对比' }).getAttribute('href')).toBe('/documents/1/compare/14/12');
});
it('历史版本下编辑仍使用整文档当前资料及独立版本', async () => {
  const original = f.json.getMockImplementation()!;
  f.json.mockImplementation((path: string) => path.endsWith('/capabilities') ? Promise.resolve({ canEditMetadata: true })
    : path === '/api/documents/1' ? Promise.resolve({ ...document, currentVersionId: '12', currentVersionNo: 2, versions: versions.map(v => ({ ...v, current: v.id === '12' })) }) : original(path));
  f.send.mockImplementation(async (path: string) => path.endsWith('/metadata') ? json({ documentId: '1', spaceId: '9', title: '当前资料', summary: '人工摘要', category: null, tags: [], metadataVersion: 7, updatedAt: '2026-11-01T00:00:00Z' }) : json({ items: [], hasMore: false, nextCursor: null, treeVersion: 0 }));
  show(); await fireEvent.click(await screen.findByRole('button', { name: 'v1' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '编辑资料' }) as HTMLButtonElement).disabled).toBe(false));
  await fireEvent.click(screen.getByRole('button', { name: '编辑资料' })); await screen.findByDisplayValue('当前资料');
  expect(screen.getByDisplayValue('人工摘要')).toBeTruthy(); expect(screen.getByText(/资料版本 7/)).toBeTruthy();
  expect(f.send.mock.calls.filter(([path]) => path.endsWith('/metadata')).map(([path]) => path)).toEqual(['/api/documents/1/metadata', '/api/documents/1/metadata']);
});
it('最高 V2 待审不成为默认文件；显式选中后区分索引和发布且下载独立', async () => {
  show(); await waitFor(() => expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(false));
  await fireEvent.click(screen.getByRole('button', { name: '预览选中版本' })); expect(screen.getByText('/api/documents/1/versions/11/preview')).toBeTruthy();
  expect((screen.getByRole('button', { name: '下载原件' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: 'v2' })); await screen.findByText('审核中', { selector: '.el-tag__content' });
  expect(screen.getByText(/当前发布：v1/)).toBeTruthy(); expect(screen.queryByText('/api/documents/1/versions/11/preview')).toBeNull();
});
it('未确认候选能力不读取进度或文件；没有出现在版本投影中的 ID 不补猜', async () => {
  const pending = deferred<ReviewStatus>();
  f.json.mockImplementation(async (path: string) => path.endsWith('/capabilities') ? { canDownload: true }
    : path.endsWith('/versions/12/review') ? pending.promise : path.endsWith('/review') ? status() : path.includes('/progress') ? { indexStatus: 'indexed' } : document);
  show(); await fireEvent.click(await screen.findByRole('button', { name: 'v2' }));
  expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(true);
  expect(f.json.mock.calls.some(([path]) => path === '/api/documents/1/progress?versionId=12')).toBe(false);
  pending.resolve(status('12', false)); await flushPromises(); expect(f.json.mock.calls.some(([path]) => path.includes('/versions/13') || path.endsWith('versionId=12'))).toBe(false);
});
it('候选文件拒绝清理预览和候选元数据，不沿用父文档下载标记', async () => {
  show(); await fireEvent.click(await screen.findByRole('button', { name: 'v2' }));
  await waitFor(() => expect((screen.getByRole('button', { name: '预览选中版本' }) as HTMLButtonElement).disabled).toBe(false));
  await fireEvent.click(screen.getByRole('button', { name: '预览选中版本' })); await fireEvent.click(screen.getByRole('button', { name: '模拟文件拒绝' }));
  await waitFor(() => expect(screen.queryByText('v2-private.pdf')).toBeNull());
  expect(screen.queryByText('/api/documents/1/versions/12/preview')).toBeNull(); expect(screen.getByText('预览已清除')).toBeTruthy();
});
it('评论区跟随所选固定版本：已发布 V1 读取自己的讨论，切换到待审 V2 不请求评论正文', async () => {
  const discussion = { items: [{ commentId: 'eeeeeeee-0000-0000-0000-000000000001', parentId: null, documentId: '1', versionId: '11',
    createdAt: '2026-10-26T00:00:00Z', deleted: false, author: { userId: '3', label: '甲' }, body: 'V1 的讨论', isOwn: false, canDelete: false }],
    hasMore: false, nextCursor: null, canCreate: false };
  const original = f.json.getMockImplementation()!;
  f.json.mockImplementation(async (path: string) => path.includes('/comments') ? discussion : original(path));
  show(); expect(await screen.findByText('V1 的讨论')).toBeTruthy(); await fireEvent.click(screen.getByRole('button', { name: 'v2' }));
  expect(await screen.findByText(/尚无发布证据，不开放评论/)).toBeTruthy(); expect(screen.queryByText('V1 的讨论')).toBeNull();
  expect(f.json.mock.calls.map(([path]) => String(path)).filter(path => path.includes('/comments'))).toEqual(['/api/documents/1/versions/11/comments?limit=20']);
});
it('首版没有发布指针时不默认选择候选；普通投影无隐藏版本不发请求', async () => {
  f.json.mockImplementation(async (path: string) => path.endsWith('/capabilities') ? {} : { ...document, currentVersionId: null, currentVersionNo: null, versions: [] });
  show(); await screen.findByText(/默认读取当前发布版本/); expect(f.json.mock.calls.some(([path]) => path.includes('/review') || path.includes('/progress'))).toBe(false);
});
it('隐藏停止读取，拒绝和三次临时故障均停止轮询', async () => {
  vi.useFakeTimers(); f.json.mockResolvedValue({ indexStatus: 'pending' });
  const hook = renderHook(() => useProgress('1', '12')); await flushPromises();
  Object.defineProperty(globalThis.document, 'visibilityState', { configurable: true, value: 'hidden' });
  await fireEvent(globalThis.document, new Event('visibilitychange')); await vi.advanceTimersByTimeAsync(30000); await nextTick();
  expect(f.json).toHaveBeenCalledOnce(); expect(hook.result.current.data).toBeUndefined(); hook.unmount();
  Object.defineProperty(globalThis.document, 'visibilityState', { configurable: true, value: 'visible' });
  f.json.mockClear().mockRejectedValue(new ApiError(503)); const failed = renderHook(() => useProgress('1', '12')); await flushPromises();
  await vi.advanceTimersByTimeAsync(60000); expect(f.json).toHaveBeenCalledTimes(3); failed.unmount();
});
