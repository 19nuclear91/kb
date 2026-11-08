import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { fireEvent, screen } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import { ApiError } from '../api';
import CitationDrawer from '../CitationDrawer.vue';
import VersionReviewPanel from '../reviews/VersionReviewPanel.vue';

const f = reactive({ json: vi.fn(), send: vi.fn(), state: { phase: 'ready', epoch: 1 } });
const session = { json: f.json, send: f.send, getSnapshot: () => f.state, subscribe: () => () => {} };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return f.state; } }) }));
const base = '/api/documents/9007199254740993/versions/9007199254740995';
const citation = { docId: '9007199254740993', versionId: '9007199254740995', chunkId: '9007199254740997', versionNo: 1 };
beforeEach(() => { f.state = { phase: 'ready', epoch: f.state.epoch + 1 }; f.json.mockReset(); f.send.mockReset(); });
afterEach(() => vi.restoreAllMocks());
async function open(entry: string, status: number) {
  f.json.mockImplementation(async (url: string) => {
    if (url.endsWith('/preview-info')) {
      throw new ApiError(status);
    }
    if (url.endsWith('/review')) {
      return {
        state: { documentId: citation.docId, versionId: citation.versionId, versionNo: 1, policy: 'REVIEW_REQUIRED', indexStatus: 'indexed', reviewState: 'IN_REVIEW' },
        capabilities: { canRead: true, canDownload: false }, rounds: [], hasMore: false,
      };
    }
    return { ...citation, docTitle: '原版本', status: 'AVAILABLE', chunk: { content: '引用片段', pageNo: 8 }, downloadPath: null };
  });
  mountRouter(() => entry === 'citation' ? h(CitationDrawer, { citation, onClose: () => {} }) : h(VersionReviewPanel, { documentId: citation.docId, versionId: citation.versionId }));
  await fireEvent.click(await screen.findByRole('button', { name: entry === 'citation' ? '预览原版本' : '预览此版本' }));
}

it.each([['citation', 404], ['citation', 405], ['review', 404], ['review', 405]] as const)
  ('%s 缺少文件名且旧端点返回 %i 时，Office 显示服务不支持', async (entry, status) => {
    const cancel = vi.fn(), blob = vi.fn();
    const response = status === 404 ? new Response(null, { status: 415 })
      : new Response('不得展示的 Office 字节', { headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' } });
    vi.spyOn(response, 'blob').mockImplementation(blob);
    if (response.body) {
      vi.spyOn(response.body, 'cancel').mockImplementation(async () => { cancel(); });
    }
    f.send.mockResolvedValue(response);
    await open(entry, status);
    await screen.findByText(status === 404 ? '服务不支持此预览，或该版本当前不可访问。' : '当前服务尚未支持 Office 预览。');
    expect(screen.getByRole('button', { name: '重试预览' })).toBeTruthy();
    expect(screen.queryByText('当前文件格式不受支持。')).toBeNull();
    expect(screen.queryByTitle('Office 只读版本预览')).toBeNull();
    expect(screen.queryByRole('button', { name: '下载原件' })).toBeNull();
    expect(f.send).toHaveBeenCalledExactlyOnceWith(`${base}/preview`, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    expect(blob).not.toHaveBeenCalled();
    if (response.body) {
      expect(cancel).toHaveBeenCalledOnce();
    }
    expect(f.json.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
  });

it.each([['citation', 'text'], ['citation', 'pdf'], ['review', 'text'], ['review', 'pdf']] as const)
  ('%s 在旧后端仍按实际响应读取 %s，关闭释放资源', async (entry, kind) => {
    const create = vi.fn(() => 'blob:legacy-preview'), revoke = vi.fn();
    vi.stubGlobal('URL', class extends URL { static createObjectURL = create; static revokeObjectURL = revoke; });
    f.send.mockResolvedValue(new Response('原版本正文', { headers: { 'Content-Type': kind === 'pdf' ? 'application/pdf' : 'text/plain' } }));
    await open(entry, kind === 'pdf' ? 404 : 405);
    if (kind === 'pdf') {
      await screen.findByTitle('PDF 版本预览');
    } else {
      await screen.findByText('原版本正文');
    }
    expect(f.send).toHaveBeenCalledExactlyOnceWith(`${base}/preview`, expect.objectContaining({ signal: expect.any(AbortSignal) }));
    const signal = f.send.mock.calls[0][1].signal;
    await fireEvent.click(screen.getAllByRole('button', { name: /close|关闭/i }).at(-1)!);
    expect(signal.aborted).toBe(true);
    if (kind === 'pdf') {
      expect(revoke).toHaveBeenCalledWith('blob:legacy-preview');
    }
  });
