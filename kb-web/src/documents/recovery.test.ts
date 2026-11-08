import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, reactive } from 'vue';
import { RouterLink, RouterView } from 'vue-router';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, render, screen, waitFor } from '@testing-library/vue';
import { ApiError } from '../api';
import DocumentDetailPage from '../pages/DocumentDetailPage.vue';
import UploadDialog from './UploadDialog.vue';
import { deferred } from '../test/fixtures';
import { mountRouter } from '../test/app';
import { renderHost } from '../test/render';
import type { ReviewStatus } from '../reviews/api';
import type { Capabilities } from '../types';
import { provideDocumentOperations } from './OperationContext';
import { DocumentOperations } from './operations';

const f = reactive({ json: vi.fn(), send: vi.fn(), refresh: vi.fn(), state: { phase: 'ready', epoch: 1 } });
const session = { json: f.json, send: f.send, refresh: f.refresh, getSnapshot: () => f.state, subscribe: () => () => {} };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return f.state; } }) }));
// 详情、上传、版本状态与文件组件使用实际实现，其余区块保留原测试隔离。
vi.mock('../favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false }) }));
vi.mock('../favorites/FavoriteButton.vue', () => ({ default: { render: () => null } }));
vi.mock('./metadata/MetadataPanel.vue', () => ({ default: { render: () => null } }));
vi.mock('../comments/VersionComments.vue', () => ({ default: { render: () => null } }));

const documentData = (id = '1') => ({ id, spaceId: '9', title: `文档 ${id}`, securityLevel: 1,
  currentVersionId: '12', currentVersionNo: 2, versions: [
    { id: '11', versionNo: 1, current: false, indexStatus: 'indexed', fileName: 'v1.txt' },
    { id: '12', versionNo: 2, current: true, indexStatus: 'indexed', fileName: 'v2.txt' },
  ] });
const abilities = (overrides: Partial<Capabilities> = {}): Capabilities => ({ documentId: '1', canDownload: true,
  canUploadVersion: true, canRetry: false, canDelete: true, ...overrides });
const versionData = (versionId = '12', canRead = true, canDownload = true, documentId = '1'): ReviewStatus => ({
  state: { documentId, versionId, versionNo: versionId === '11' ? 1 : 2, stateVersion: '1', indexStatus: 'indexed',
    policy: 'AUTO', publicationEvidence: 'PUBLISHED', reviewState: 'DRAFT', currentVersionId: '12' },
  capabilities: { canRead, canDownload, canSubmit: false, canApprove: false, canReject: false,
    canWithdraw: false, canRefresh: false, canReadOpinions: false }, rounds: [], hasMore: false, nextCursor: null,
});
let readDocument: (id: string) => unknown;
let readCapabilities: () => unknown;
let readVersion: (versionId: string, documentId: string) => unknown;
let upload: () => unknown;

beforeEach(() => {
  f.state = { phase: 'ready', epoch: f.state.epoch + 1 };
  readDocument = documentData;
  readCapabilities = () => abilities();
  readVersion = (id, doc) => versionData(id, true, true, doc);
  upload = () => { throw new ApiError(403); };
  f.refresh.mockReset().mockResolvedValue(undefined);
  f.send.mockReset().mockImplementation(async () => new Response(null, { status: 403 }));
  f.json.mockReset().mockImplementation(async (path: string, options?: RequestInit) => {
    if (options?.method === 'POST') {
      return upload();
    }
    if (path.endsWith('/capabilities')) {
      return readCapabilities();
    }
    if (path.endsWith('/review')) {
      return readVersion(path.split('/')[5], path.split('/')[3]);
    }
    if (path.includes('/progress')) {
      return { versionId: new URL(path, 'http://test').searchParams.get('versionId'), indexStatus: 'indexed' };
    }
    if (/^\/api\/documents\/\d+$/.test(path)) {
      return readDocument(path.split('/')[3]);
    }
    throw new ApiError(503);
  });
});
afterEach(() => vi.restoreAllMocks());

const detail = () => mountRouter(() => [h(RouterLink, { to: '/documents/2' }, () => '另一文档'), h(RouterView)], {
  path: '/documents/1', routes: [{ path: '/documents/:documentId', component: DocumentDetailPage }],
});
const button = (name: string) => screen.getByRole('button', { name: value => value === name || value === `loading ${name}` }) as HTMLButtonElement;
const reads = (suffix: string) => f.json.mock.calls.filter(([path, options]) => String(path).endsWith(suffix) && !options?.method);
const uploads = () => f.json.mock.calls.filter(([, options]) => options?.method === 'POST');
async function selectHistory() {
  await fireEvent.click(await screen.findByRole('button', { name: 'v1' }));
  await waitFor(() => expect(button('下载原件').disabled).toBe(false));
}
async function submitVersion() {
  await waitFor(() => expect(button('上传新版本').disabled).toBe(false));
  await fireEvent.click(button('上传新版本'));
  await fireEvent.change(await screen.findByLabelText('原始文件'), { target: { files: [new File(['正文'], 'new.txt')] } });
  await fireEvent.click(button('提交文件'));
  await waitFor(() => expect(uploads()).toHaveLength(1));
}

it.each([403, 404])('下载 %i 后重核，保留仍可读的历史版本且不自动下载', async code => {
  f.send.mockImplementation(async () => {
    readCapabilities = () => abilities({ canDownload: false });
    readVersion = id => versionData(id, true, false);
    return new Response(null, { status: code });
  });
  detail(); await selectHistory(); await fireEvent.click(button('下载原件'));
  await waitFor(() => expect(reads('/capabilities').length).toBeGreaterThan(1));
  await screen.findByRole('button', { name: 'v1' });
  await waitFor(() => expect(button('预览选中版本').disabled).toBe(false));
  expect(button('下载原件').disabled).toBe(true);
  expect(reads('/review').at(-1)?.[0]).toBe('/api/documents/1/versions/11/review');
  expect(f.send).toHaveBeenCalledOnce();
});

it('能力查询失败提供主动恢复，并以最新独立资格控制按钮', async () => {
  readCapabilities = () => { throw new ApiError(503); };
  detail(); await screen.findByText('操作资格暂不可确认，相关操作已禁用。');
  expect(button('上传新版本').disabled).toBe(true);
  readCapabilities = () => abilities({ canDownload: false });
  await fireEvent.click(button('重新核对操作资格'));
  await waitFor(() => expect(button('上传新版本').disabled).toBe(false));
  expect(button('下载原件').disabled).toBe(true);
  expect(reads('/capabilities')).toHaveLength(2);
});

it.each([403, 404])('上传 %i 后立即暂停，等待新资格且不自动重传', async code => {
  const pending = deferred<Capabilities>();
  upload = () => { readCapabilities = () => pending.promise; throw new ApiError(code); };
  detail(); await screen.findByRole('button', { name: 'v1' }); await submitVersion();
  await waitFor(() => expect(reads('/capabilities')).toHaveLength(2));
  expect(button('提交文件').disabled).toBe(true);
  await fireEvent.submit(screen.getByLabelText('原始文件').closest('form')!); await flushPromises();
  expect(uploads()).toHaveLength(1);
  pending.resolve(abilities({ canUploadVersion: false })); await flushPromises();
  await waitFor(() => expect(button('提交文件').disabled).toBe(true));
  expect(uploads()).toHaveLength(1);
  expect(screen.getByRole('heading', { name: '文档 1' })).toBeTruthy();
});

it.each(['denied', 'minimal', 'removed'])('下载后的 %s 阅读结果不恢复原文件入口', async outcome => {
  f.send.mockImplementation(async () => {
    if (outcome === 'removed') {
      readDocument = id => ({ ...documentData(id), versions: [documentData(id).versions[1]] });
    } else {
      readVersion = id => {
        if (id === '11' && outcome === 'denied') {
          throw new ApiError(404);
        }
        return versionData(id, id !== '11', false);
      };
    }
    return new Response(null, { status: 403 });
  });
  detail(); await selectHistory(); await fireEvent.click(button('下载原件'));
  await waitFor(() => expect(reads('/capabilities').length).toBeGreaterThan(1));
  await flushPromises();
  expect(button('预览选中版本').disabled).toBe(true);
  const download = screen.queryByRole('button', { name: '下载原件' }) as HTMLButtonElement | null;
  expect(download === null || download.disabled).toBe(true);
  expect(f.send).toHaveBeenCalledOnce();
  expect(reads('/review').length).toBeLessThan(5);
});

it('下载后详情重核故障可主动恢复原历史选择，不自动打开文件', async () => {
  f.send.mockImplementation(async () => {
    readDocument = () => { throw new ApiError(503); };
    return new Response(null, { status: 403 });
  });
  detail(); await selectHistory(); await fireEvent.click(button('下载原件'));
  await screen.findByText('服务暂不可用，请稍后重试。');
  expect(screen.queryByRole('button', { name: '下载原件' })).toBeNull();
  readDocument = documentData;
  readVersion = id => versionData(id, true, false);
  await fireEvent.click(button('重新核对操作资格'));
  await waitFor(() => expect(button('预览选中版本').disabled).toBe(false));
  expect(reads('/review').at(-1)?.[0]).toBe('/api/documents/1/versions/11/review');
  expect(button('下载原件').disabled).toBe(true);
  expect(f.send).toHaveBeenCalledOnce();
});

it('切换到 V2 后 V1 下载迟到拒绝不隐藏或重核 V2', async () => {
  const late = deferred<Response>(); f.send.mockReturnValue(late.promise);
  detail(); await selectHistory(); await fireEvent.click(button('下载原件'));
  const signal = f.send.mock.calls[0][1].signal as AbortSignal;
  await fireEvent.click(button('v2 · 当前发布'));
  await waitFor(() => expect(button('下载原件').disabled).toBe(false));
  expect(signal.aborted).toBe(true);
  const count = f.json.mock.calls.length;
  late.resolve(new Response(null, { status: 403 })); await flushPromises();
  expect(f.json).toHaveBeenCalledTimes(count);
  expect(button('预览选中版本').disabled).toBe(false);
  expect(button('下载原件').disabled).toBe(false);
  expect(screen.getByRole('button', { name: 'v2 · 当前发布' })).toBeTruthy();
});

it('重新核对后旧版本资格晚到不能覆盖新拒绝', async () => {
  const late = deferred<ReviewStatus>();
  detail(); await waitFor(() => expect(button('下载原件').disabled).toBe(false));
  readVersion = () => late.promise;
  await fireEvent.click(button('v1'));
  await waitFor(() => expect(reads('/review').at(-1)?.[0]).toBe('/api/documents/1/versions/11/review'));
  const signal = reads('/review').at(-1)![1].signal as AbortSignal;
  readVersion = id => versionData(id, false, false);
  await fireEvent.click(button('重新核对操作资格'));
  await screen.findByText(/当前仅可查看本人最小状态/);
  expect(signal.aborted).toBe(true);
  late.resolve(versionData('11')); await flushPromises();
  expect(button('预览选中版本').disabled).toBe(true);
  expect(button('下载原件').disabled).toBe(true);
});

it.each(['checking', 'denied'])('同文档旧能力 true 晚于上传拒绝，新核对 %s 时不能恢复提交', async stage => {
  const posting = deferred<unknown>(), old = deferred<Capabilities>(), current = deferred<Capabilities>();
  upload = () => posting.promise;
  detail(); await screen.findByRole('button', { name: 'v1' }); await submitVersion();
  readCapabilities = () => old.promise;
  await fireEvent.click(button('重新核对操作资格'));
  await waitFor(() => expect(reads('/capabilities')).toHaveLength(2));
  const oldSignal = reads('/capabilities')[1][1].signal as AbortSignal;
  readCapabilities = () => current.promise;
  posting.reject(new ApiError(403)); await flushPromises();
  await waitFor(() => expect(reads('/capabilities')).toHaveLength(3));
  expect(oldSignal.aborted).toBe(true);
  if (stage === 'denied') {
    current.resolve(abilities({ canUploadVersion: false, canDownload: false })); await flushPromises();
    await waitFor(() => expect(button('重新核对操作资格').disabled).toBe(false));
  }
  old.resolve(abilities()); await flushPromises();
  expect(button('上传新版本').disabled).toBe(true);
  expect(button('提交文件').disabled).toBe(true);
  expect(button('下载原件').disabled).toBe(true);
  await fireEvent.click(button('提交文件'));
  await fireEvent.submit(screen.getByLabelText('原始文件').closest('form')!); await flushPromises();
  expect(uploads()).toHaveLength(1);
  if (stage === 'checking') {
    expect(button('重新核对操作资格').disabled).toBe(true);
    current.resolve(abilities({ canUploadVersion: false, canDownload: false })); await flushPromises();
    await waitFor(() => expect(button('重新核对操作资格').disabled).toBe(false));
    expect(button('提交文件').disabled).toBe(true);
  }
  expect(reads('/capabilities').map(([path]) => path)).toEqual(Array(3).fill('/api/documents/1/capabilities'));
  expect(uploads()).toHaveLength(1);
});

it.each(['document', 'identity', 'hidden'])('重核期间 %s 变化后旧资格不能恢复', async change => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const late = deferred<Capabilities>();
  detail(); await waitFor(() => expect(button('上传新版本').disabled).toBe(false));
  readCapabilities = () => late.promise;
  await fireEvent.click(button('重新核对操作资格'));
  await waitFor(() => expect(reads('/capabilities')).toHaveLength(2));
  const signal = reads('/capabilities').at(-1)![1].signal as AbortSignal;
  readCapabilities = () => abilities({ canUploadVersion: false, canDownload: false });
  if (change === 'document') {
    await fireEvent.click(screen.getByRole('link', { name: '另一文档' }));
  } else if (change === 'identity') {
    f.state = { phase: 'ready', epoch: f.state.epoch + 1 }; await nextTick();
  } else {
    visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange'));
  }
  await waitFor(() => expect(signal.aborted).toBe(true));
  late.resolve(abilities()); await flushPromises();
  if (change === 'hidden') {
    expect(screen.queryByRole('button', { name: '上传新版本' })).toBeNull();
  } else {
    await screen.findByRole('heading', { name: change === 'document' ? '文档 2' : '文档 1' });
    expect(button('上传新版本').disabled).toBe(true);
    expect(button('下载原件').disabled).toBe(true);
  }
});

it('上传重核成功不自动发送，用户可以用保留文件主动再提交', async () => {
  const pending = deferred<Capabilities>();
  upload = () => { readCapabilities = () => pending.promise; throw new ApiError(403); };
  detail(); await screen.findByRole('button', { name: 'v1' }); await submitVersion();
  await waitFor(() => expect(reads('/capabilities')).toHaveLength(2));
  expect(button('提交文件').disabled).toBe(true);
  pending.resolve(abilities()); await flushPromises();
  await waitFor(() => expect(button('提交文件').disabled).toBe(false));
  expect(uploads()).toHaveLength(1);
  upload = () => ({ documentId: '1', versionId: '13' });
  await fireEvent.click(button('提交文件'));
  await waitFor(() => expect(uploads()).toHaveLength(2));
  expect((uploads()[1][1].body as FormData).get('file')).toBeInstanceOf(File);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

it.each(['capabilities', 'document'])('上传后 %s 重核失败保持暂停，主动重读不重传', async failed => {
  upload = () => {
    if (failed === 'capabilities') {
      readCapabilities = () => { throw new ApiError(503); };
    } else {
      readDocument = () => { throw new ApiError(503); };
    }
    throw new ApiError(403);
  };
  detail(); await screen.findByRole('button', { name: 'v1' }); await submitVersion();
  await waitFor(() => expect(button('重新核对上传资格').disabled).toBe(false));
  expect(button('提交文件').disabled).toBe(true);
  const pending = deferred<Capabilities>(); readDocument = documentData; readCapabilities = () => pending.promise;
  await fireEvent.click(button('重新核对上传资格'));
  await waitFor(() => expect(reads('/capabilities')).toHaveLength(3));
  expect(button('提交文件').disabled).toBe(true);
  expect(button('重新核对上传资格').disabled).toBe(true);
  await fireEvent.click(button('重新核对上传资格'));
  pending.resolve(abilities()); await flushPromises();
  await waitFor(() => expect(button('提交文件').disabled).toBe(false));
  expect(reads('/capabilities')).toHaveLength(3);
  expect(uploads()).toHaveLength(1);
});

it.each([403, 404])('上传后的文档核对 %i 清理旧内容及上传窗口，不显示成功', async code => {
  upload = () => { readDocument = () => { throw new ApiError(code); }; throw new ApiError(403); };
  detail(); await screen.findByRole('button', { name: 'v1' }); await submitVersion();
  await screen.findByText('文档当前不可访问。');
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.queryByRole('heading', { name: '文档 1' })).toBeNull();
  expect(screen.queryByText('v1.txt')).toBeNull();
  expect(screen.queryByText('文档已删除')).toBeNull();
  expect(uploads()).toHaveLength(1);
});

it('新版本弹窗缺少明确资格时，按钮和直接表单提交均禁用', async () => {
  render(UploadDialog, { props: { open: true, documentId: '1', onClose: vi.fn(), onUploaded: vi.fn() } }); await nextTick();
  await fireEvent.change(screen.getByLabelText('原始文件'), { target: { files: [new File(['正文'], 'new.txt')] } });
  expect(button('提交文件').disabled).toBe(true);
  await fireEvent.submit(screen.getByLabelText('原始文件').closest('form')!); await flushPromises();
  expect(uploads()).toHaveLength(0);
});

it('弹窗拒绝后即使父页仍给旧 true，也不能复用原资格直接提交', async () => {
  const denied = vi.fn();
  const props = { open: true, documentId: '1', onClose: vi.fn(), onUploaded: vi.fn(), onDenied: denied };
  const view = render(UploadDialog, { props: { ...props, access: { key: 'old', canUploadVersion: true, checking: false } } }); await nextTick();
  await fireEvent.change(screen.getByLabelText('原始文件'), { target: { files: [new File(['正文'], 'new.txt')] } });
  await fireEvent.click(button('提交文件'));
  await waitFor(() => expect(denied).toHaveBeenCalledOnce());
  expect(button('提交文件').disabled).toBe(true);
  await fireEvent.submit(screen.getByLabelText('原始文件').closest('form')!); await flushPromises();
  expect(uploads()).toHaveLength(1);
  await view.rerender({ access: { key: 'new', canUploadVersion: true, checking: true } });
  expect(button('提交文件').disabled).toBe(true);
  await view.rerender({ access: { key: 'new', canUploadVersion: true, checking: false } });
  await waitFor(() => expect(button('提交文件').disabled).toBe(false));
  expect(uploads()).toHaveLength(1);
});

it('上传在途、只读刷新及网络未确认不破坏删除协调', async () => {
  const pending = deferred<unknown>(), operations = new DocumentOperations();
  upload = () => pending.promise;
  const props = { open: true, documentId: '1', onClose: vi.fn(), onUploaded: vi.fn() };
  const access = reactive({ key: 'old', canUploadVersion: true, checking: false });
  renderHost(defineComponent({ setup() {
    provideDocumentOperations(() => operations);
    return () => h(UploadDialog, { ...props, access });
  } })); await nextTick();
  await fireEvent.change(screen.getByLabelText('原始文件'), { target: { files: [new File(['正文'], 'new.txt')] } });
  await fireEvent.click(button('提交文件'));
  await waitFor(() => expect(operations.getSnapshot().pending).toBe(1));
  Object.assign(access, { key: 'new', canUploadVersion: false, checking: true }); await nextTick();
  expect(operations.getSnapshot().pending).toBe(1);
  operations.beginDelete(); await nextTick();
  expect(operations.canSubmitDelete()).toBe(false);
  pending.reject(new ApiError(0)); await flushPromises();
  await screen.findByText(/提交结果尚未确认/);
  expect(operations.getSnapshot()).toMatchObject({ pending: 0, uncertain: 1, exclusive: true });
  expect(button('提交文件').disabled).toBe(true);
  expect(uploads()).toHaveLength(1);
});

it('重复选择当前版本保持资格，StrictMode 下仍可发起下载和重核', async () => {
  // Vue 无 StrictMode，显式卸载后重挂载检验清理后的重新进入。
  const first = detail(); first.unmount(); detail();
  await waitFor(() => expect(button('下载原件').disabled).toBe(false));
  await fireEvent.click(button('v2 · 当前发布'));
  expect(button('下载原件').disabled).toBe(false);
  await fireEvent.click(button('下载原件'));
  await waitFor(() => expect(f.send).toHaveBeenCalledOnce());
  await waitFor(() => expect(button('下载原件').disabled).toBe(false));
});

it.each([true, false])('默认版本下载被拒后恰逢新版发布，原版本可见=%s 也不能改选新版', async retained => {
  f.send.mockImplementation(async () => {
    readDocument = id => ({ ...documentData(id), currentVersionId: '13', currentVersionNo: 3,
      versions: [...documentData(id).versions.filter(version => retained || version.id !== '12').map(version => ({ ...version, current: false })),
        { id: '13', versionNo: 3, current: true, indexStatus: 'indexed', fileName: 'v3.txt' }] });
    return new Response(null, { status: 403 });
  });
  detail(); await waitFor(() => expect(button('下载原件').disabled).toBe(false));
  await fireEvent.click(button('下载原件'));
  await screen.findByText('当前发布：v3'); await flushPromises();
  expect(reads('/review').some(([path]) => path === '/api/documents/1/versions/13/review')).toBe(false);
  if (retained) {
    await waitFor(() => expect(button('预览选中版本').disabled).toBe(false));
    expect(reads('/review').at(-1)?.[0]).toBe('/api/documents/1/versions/12/review');
  } else {
    expect(button('预览选中版本').disabled).toBe(true);
    expect(screen.queryByRole('button', { name: '下载原件' })).toBeNull();
  }
  expect(f.send).toHaveBeenCalledOnce();
});
