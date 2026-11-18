import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, render, screen, waitFor } from '@testing-library/vue';
import { json, deferred } from '../../test/fixtures';
import { renderHost } from '../../test/render';
import { DocumentOperations } from '../operations';
import { provideDocumentOperations } from '../OperationContext';
import MetadataEditor from './MetadataEditor.vue';
import MetadataPanel from './MetadataPanel.vue';
import MetadataFilters from './Filters.vue';

const f = reactive({ send: vi.fn(), epoch: 1, phase: 'ready' });
const session = { send: f.send, getSnapshot: () => ({ epoch: f.epoch }) };
vi.mock('../../SessionProvider', () => ({ useSession: () => ({ session, get state() { return { phase: f.phase, epoch: f.epoch }; } }) }));
const metadata = { documentId: '9', spaceId: '2', title: '原名', summary: null, category: null,
  tags: [{ id: '9223372036854775806', name: '停用主题', enabled: false }], metadataVersion: 3, updatedAt: '2026-11-01T00:00:00Z' };
beforeEach(() => {
  f.send.mockReset(); f.epoch++; f.phase = 'ready';
  f.send.mockImplementation(async (path: string) => path.endsWith('/metadata') ? json(metadata) : path.includes('categories')
    ? json({ items: [], treeVersion: 0, nextCursor: null, hasMore: false }) : json({ items: [], nextCursor: null, hasMore: false }));
});
const writes = () => f.send.mock.calls.filter(([, init]) => init?.method === 'PUT');
const save = async () => {
  const button = await screen.findByRole('button', { name: '保存资料' });
  await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
  await fireEvent.click(button);
};
function coordinated(operations: DocumentOperations, saved = vi.fn()) {
  return renderHost(defineComponent({ setup() {
    provideDocumentOperations(() => operations);
    return () => h(MetadataEditor, { documentId: '9', canEdit: true, onClose: vi.fn(), onSaved: saved });
  } }));
}

const startButton = '根据当前发布版本生成建议';
const suggested = { title: { value: '候选标题', sources: [{ index: 0, heading: '概述', page: 2 }] },
  summary: { value: '候选摘要', sources: [{ index: 1, heading: null, page: null }] }, category: null };
function suggestionView(key: string, status = 'COMPLETED', reasonCode: string | null = null) {
  return { requestId: key, sourceVersionId: '30', metadataVersion: 3, status, reasonCode,
    expiresAt: new Date(Date.now() + 30000).toISOString(), result: status === 'COMPLETED' ? suggested : null };
}
function editor(onSaved = vi.fn()) {
  return renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, sourceVersionId: '30', versionNo: 3, onClose: vi.fn(), onSaved } });
}

describe('AI 整理资料', () => {
  it('使用当前发布版本并允许等待时手改标题，只将采用的摘要带入辅助保存', async () => {
    const original = f.send.getMockImplementation()!, pending = deferred<Response>();
    let key = '';
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        key = (init.headers as Record<string, string>)['X-Request-Id'];
        expect(JSON.parse(init.body as string).sourceVersionId).toBe(30);
        return pending.promise;
      }
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.update(screen.getByLabelText('资料标题'), '手工标题');
    pending.resolve(json(suggestionView(key)));
    await screen.findByText('无合适分类建议');
    expect(screen.getByText(/V3 · 结构块 0/).textContent).toContain('第 2 页');
    expect(screen.getByText(/当前草稿：手工标题/)).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: '采用摘要' }));
    expect(writes()).toHaveLength(0);
    await fireEvent.update(screen.getByLabelText('人工摘要'), '人工修订摘要');
    await save();
    expect(writes()).toHaveLength(1);
    const [path, init] = writes()[0];
    expect(path).toBe(`/api/documents/9/metadata-suggestions/${key}/metadata`);
    expect(JSON.parse(init.body)).toMatchObject({ title: '手工标题', summary: '人工修订摘要', adoptedFields: ['summary'] });
    expect(init.headers['X-Request-Id']).not.toBe(key);
  });

  it.each([['SNAPSHOT_TRUNCATED', '解析时正文已截断。'], ['INPUT_TOO_LARGE', '正文完整，但超过单次整理上限。'],
    ['OUTPUT_TOO_LARGE', '模型输出超过整理上限，未生成可采用的建议。'],
    ['QUOTA_REJECTED', '部门额度不足。']])('失败原因 %s 保留手工草稿', async (reason, message) => {
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => path.endsWith('/metadata-suggestions')
      ? Promise.resolve(json(suggestionView((init.headers as Record<string, string>)['X-Request-Id'],
        reason === 'QUOTA_REJECTED' ? 'REJECTED' : 'FAILED', reason))) : original(path, init));
    editor();
    await fireEvent.update(await screen.findByLabelText('资料标题'), '手动输入');
    await fireEvent.click(screen.getByRole('button', { name: startButton }));
    await screen.findByText(message);
    expect(screen.getByDisplayValue('手动输入')).toBeTruthy();
    expect(writes()).toHaveLength(0);
  });

  it('创建确认丢失后复用标识，取消确认丢失后可查明完成', async () => {
    const original = f.send.getMockImplementation()!, keys: string[] = [];
    f.send.mockImplementation(async (path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        keys.push((init.headers as Record<string, string>)['X-Request-Id']);
        if (keys.length === 1) throw new TypeError('offline');
        return json(suggestionView(keys[0], 'RUNNING'));
      }
      if (path.endsWith('/cancel')) throw new TypeError('offline');
      if (path.includes('/metadata-suggestions/')) return json(suggestionView(keys[0]));
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '按原标识重试生成请求' }));
    await screen.findByText('正在整理当前发布版本…');
    await fireEvent.click(screen.getByRole('button', { name: '取消整理' }));
    await screen.findByText('取消结果未确认，请核对原请求。');
    await fireEvent.click(screen.getByRole('button', { name: '核对原请求' }));
    await screen.findByRole('button', { name: '采用摘要' });
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('创建回执丢失后重试遇到入口限流，重读仍能核对并取消原请求', async () => {
    const original = f.send.getMockImplementation()!, keys: string[] = [], queries: string[] = [];
    f.send.mockImplementation(async (path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        keys.push((init.headers as Record<string, string>)['X-Request-Id']);
        if (keys.length === 1) throw new TypeError('offline');
        return new Response(null, { status: 429 });
      }
      if (path.endsWith('/cancel')) return json(suggestionView(keys[0], 'CANCELLED', 'CANCELLED'));
      if (path.includes('/metadata-suggestions/')) {
        queries.push(path);
        return json(suggestionView(keys[0], 'RUNNING'));
      }
      if (path === '/api/documents/9') return json({ id: '9', currentVersionId: '30', currentVersionNo: 3 });
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '按原标识重试生成请求' }));
    await screen.findByText(/请求过于频繁/);
    expect(screen.queryByText(/部门额度不足/)).toBeNull();
    expect(screen.queryByRole('button', { name: '重新生成建议' })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    await screen.findByText('正在整理当前发布版本…');
    const path = '/api/documents/9/metadata-suggestions/' + keys[0];
    expect(queries).toEqual([path]);
    await fireEvent.click(screen.getByRole('button', { name: '取消整理' }));
    await screen.findByText('整理已取消。');
    expect(f.send.mock.calls.some(([url]) => url === path + '/cancel')).toBe(true);
    expect(keys).toEqual([keys[0], keys[0]]);
  });

  it('首次生成和重读查询都受到限流时仍可按原标识重试', async () => {
    const original = f.send.getMockImplementation()!, keys: string[] = [], queries: string[] = [];
    f.send.mockImplementation(async (path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        keys.push((init.headers as Record<string, string>)['X-Request-Id']);
        if (keys.length === 1) return new Response(null, { status: 429 });
        return json(suggestionView(keys[0]));
      }
      if (path.includes('/metadata-suggestions/')) {
        queries.push(path);
        return new Response(null, { status: 429 });
      }
      if (path === '/api/documents/9') return json({ id: '9', currentVersionId: '30', currentVersionNo: 3 });
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await screen.findByText(/请求过于频繁/);
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    await waitFor(() => expect(queries).toEqual(['/api/documents/9/metadata-suggestions/' + keys[0]]));
    expect(keys).toHaveLength(1);
    await fireEvent.click(await screen.findByRole('button', { name: '按原标识重试生成请求' }));
    await screen.findByRole('button', { name: '采用摘要' });
    expect(keys).toEqual([keys[0], keys[0]]);
  });

  it.each(['运行中', '创建确认在途', '创建确认丢失且来源已换版'])('%s 时重读只核对原请求，并可继续取消', async state => {
    const original = f.send.getMockImplementation()!, pendingCreate = deferred<Response>();
    const creates: string[] = [], queries: string[] = [], cancels: string[] = [];
    const changed = state === '创建确认丢失且来源已换版';
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        creates.push((init.headers as Record<string, string>)['X-Request-Id']);
        if (state === '创建确认在途') return pendingCreate.promise;
        if (changed) return Promise.reject(new TypeError('offline'));
        return Promise.resolve(json(suggestionView(creates[0], 'RUNNING')));
      }
      if (path.endsWith('/cancel')) {
        cancels.push(path);
        return Promise.resolve(json(suggestionView(creates[0], 'CANCELLED', 'CANCELLED')));
      }
      if (path.includes('/metadata-suggestions/')) {
        queries.push(path);
        if (changed && queries.length === 1) return Promise.resolve(new Response(null, { status: 404 }));
        return Promise.resolve(json(suggestionView(creates[0], 'RUNNING')));
      }
      if (path === '/api/documents/9') return Promise.resolve(json({ id: '9', currentVersionId: changed ? '31' : '30', currentVersionNo: changed ? 4 : 3 }));
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await waitFor(() => expect(creates).toHaveLength(1));
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    const originalPath = `/api/documents/9/metadata-suggestions/${creates[0]}`;
    await waitFor(() => expect(queries).toContain(originalPath));
    if (changed) {
      await screen.findByText('尚未查到原请求，资料已变化，可继续核对或重新生成。');
      expect(screen.queryByRole('button', { name: '按原标识重试生成请求' })).toBeNull();
      await fireEvent.click(screen.getByRole('button', { name: '核对原请求' }));
      expect(queries).toEqual([originalPath, originalPath]);
    }
    await screen.findByText(changed ? '原整理请求仍在运行。' : '正在整理当前发布版本…');
    expect(screen.queryByRole('button', { name: startButton })).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: '取消整理' }));
    await screen.findByText('整理已取消。');
    pendingCreate.resolve(json(suggestionView(creates[0])));
    await flushPromises();
    expect(screen.queryByRole('button', { name: '采用摘要' })).toBeNull();
    expect(cancels).toEqual([originalPath + '/cancel']);
    expect(creates).toHaveLength(1);
  });

  it.each([
    { status: 'COMPLETED', reason: null, initial: 'PENDING', final: 'SETTLED', label: '已确认扣除 10 token' },
    { status: 'CANCELLED', reason: 'CANCELLED', initial: 'RESERVED', final: 'REVIEW', label: '额度结算待核查' },
  ])('$status 后重读资料并按原标识核对结算至 $final', async ({ status, reason, initial, final, label }) => {
    const original = f.send.getMockImplementation()!, firstQuery = deferred<Response>();
    const creates: string[] = [], queries: string[] = [];
    const receipt = (settlementStatus: string) => json({
      ...suggestionView(creates[0], status, reason), settlementStatus, plannedCharge: 10,
      settledCharge: settlementStatus === 'SETTLED' ? 10 : null,
    });
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        creates.push((init.headers as Record<string, string>)['X-Request-Id']);
        return Promise.resolve(receipt(initial));
      }
      if (path.includes('/metadata-suggestions/')) {
        queries.push(path);
        expect(init.method).toBe('GET');
        return queries.length === 1 ? firstQuery.promise : Promise.resolve(receipt(final));
      }
      if (path === '/api/documents/9') return Promise.resolve(json({ id: '9', currentVersionId: '30', currentVersionNo: 3 }));
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await screen.findByText('额度结算待确认');
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    const path = '/api/documents/9/metadata-suggestions/' + creates[0];
    await waitFor(() => expect(queries).toEqual([path]));
    expect(screen.queryByRole('button', { name: '取消整理' })).toBeNull();
    firstQuery.resolve(receipt('PENDING'));
    await screen.findByText('额度结算待确认');
    await fireEvent.click(screen.getByRole('button', { name: '核对原请求' }));
    await screen.findByText(label);
    expect(queries).toEqual([path, path]);
    expect(creates).toHaveLength(1);
  });

  it.each(['发布版本', '资料版本'])('重读时%s变化，原请求已完成也不能采用旧建议', async changed => {
    const original = f.send.getMockImplementation()!;
    let key = '', refreshing = false;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        key = (init.headers as Record<string, string>)['X-Request-Id'];
        return Promise.resolve(json(suggestionView(key, 'RUNNING')));
      }
      if (path.endsWith('/metadata-suggestions/' + key)) return Promise.resolve(json(suggestionView(key)));
      if (path === '/api/documents/9') return Promise.resolve(json({ id: '9',
        currentVersionId: changed === '发布版本' ? '31' : '30', currentVersionNo: changed === '发布版本' ? 4 : 3 }));
      if (refreshing && changed === '资料版本' && path.endsWith('/metadata')) return Promise.resolve(json({ ...metadata, metadataVersion: 4 }));
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await screen.findByText('正在整理当前发布版本…');
    refreshing = true;
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    await screen.findByText(changed === '发布版本' ? '当前发布版本已变化，请重新读取。' : '文档资料已变化，请重新读取。');
    expect(screen.queryByRole('button', { name: '采用摘要' })).toBeNull();
    expect(f.send.mock.calls.filter(([path]) => path.endsWith('/metadata-suggestions'))).toHaveLength(1);
  });

  it.each(['SETTLED', 'PENDING'])('已采用建议的结算为 %s 时，多次重读后仍按原请求保存手工修订', async settlementStatus => {
    const original = f.send.getMockImplementation()!, firstRead = deferred<Response>(), saved = vi.fn();
    const creates: string[] = [], queries: string[] = [];
    f.send.mockImplementation(async (path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        creates.push((init.headers as Record<string, string>)['X-Request-Id']);
        return json({ ...suggestionView(creates[0]), settlementStatus });
      }
      if (init?.method === 'GET' && path.includes('/metadata-suggestions/')) {
        queries.push(path);
        if (queries.length === 1) return firstRead.promise;
        return json({ ...suggestionView(creates[0]), settlementStatus: 'SETTLED' });
      }
      if (init?.method === 'PUT') {
        return json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['summary'], replayed: false });
      }
      if (path === '/api/documents/9') return json({ id: '9', currentVersionId: '30', currentVersionNo: 3 });
      return original(path, init);
    });
    editor(saved);
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '采用摘要' }));
    await fireEvent.update(screen.getByLabelText('人工摘要'), '保留我的修订');
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    const path = '/api/documents/9/metadata-suggestions/' + creates[0];
    await waitFor(() => expect(queries).toEqual([path]));
    const saveButton = () => screen.getByRole<HTMLButtonElement>('button', { name: '保存资料' });
    expect(saveButton().disabled).toBe(true);
    firstRead.resolve(new Response(null, { status: 503 }));
    await screen.findByText('整理请求结果未确认，可按原标识核对。');
    expect(saveButton().disabled).toBe(true);
    await fireEvent.click(screen.getByRole('button', { name: '核对原请求' }));
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    await waitFor(() => expect(queries).toEqual([path, path, path]));
    await save();
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(writes()).toHaveLength(1);
    const [url, init] = writes()[0];
    expect(url).toBe(path + '/metadata');
    expect(JSON.parse(init.body)).toMatchObject({ summary: '保留我的修订', adoptedFields: ['summary'], expectedMetadataVersion: 3 });
    expect(creates).toHaveLength(1);
  });

  it.each(['TREE_VERSION_CONFLICT', 'SUGGESTION_EXPIRED'])('重读原建议遇到 %s 时保留手工修订并禁止保存', async reason => {
    const original = f.send.getMockImplementation()!;
    let key = '';
    f.send.mockImplementation(async (path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        key = (init.headers as Record<string, string>)['X-Request-Id'];
        return json(suggestionView(key));
      }
      if (path.endsWith('/metadata-suggestions/' + key)) {
        const view = suggestionView(key);
        return json(reason === 'TREE_VERSION_CONFLICT' ? { ...view, result: null, reasonCode: reason }
          : { ...view, expiresAt: new Date(Date.now() - 1000).toISOString() });
      }
      if (path === '/api/documents/9') return json({ id: '9', currentVersionId: '30', currentVersionNo: 3 });
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '采用摘要' }));
    await fireEvent.update(screen.getByLabelText('人工摘要'), '留待核对的摘要');
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    await screen.findByText(reason === 'TREE_VERSION_CONFLICT' ? '分类目录已变化，请重新读取。' : '建议已过期，请重新读取后生成。');
    expect(screen.getByDisplayValue('留待核对的摘要')).toBeTruthy();
    expect(screen.getByRole<HTMLButtonElement>('button', { name: '保存资料' }).disabled).toBe(true);
    await fireEvent.submit(screen.getByLabelText('人工摘要').closest('form')!);
    expect(writes()).toHaveLength(0);
  });

  it.each(['登出', '换号', '撤权', '切换文档'])('%s 后隔离旧请求的迟到建议', async mode => {
    const original = f.send.getMockImplementation()!, pending = deferred<Response>();
    let key = '';
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (!path.endsWith('/metadata-suggestions')) return original(path, init);
      key = (init.headers as Record<string, string>)['X-Request-Id'];
      return pending.promise;
    });
    const page = editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    if (mode === '登出') f.phase = 'anonymous';
    else if (mode === '换号') f.epoch++;
    else if (mode === '撤权') await page.rerender({ documentId: '9', canEdit: false });
    else await page.rerender({ documentId: '10', canEdit: true });
    await nextTick();
    pending.resolve(json(suggestionView(key)));
    await flushPromises();
    expect(screen.queryByText('建议：候选摘要')).toBeNull();
  });

  it.each(['核对原请求', '重新读取资料'])('首次创建未登记时，%s 后仍能用原标识重试', async action => {
    const original = f.send.getMockImplementation()!, keys: string[] = [];
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        keys.push((init.headers as Record<string, string>)['X-Request-Id']);
        return keys.length === 1 ? Promise.reject(new TypeError('offline')) : Promise.resolve(json(suggestionView(keys[0])));
      }
      if (path.includes('/metadata-suggestions/')) return Promise.resolve(new Response(null, { status: 404 }));
      if (path === '/api/documents/9') return Promise.resolve(json({ id: '9', currentVersionId: '30', currentVersionNo: 3 }));
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await screen.findByRole('button', { name: '按原标识重试生成请求' });
    await fireEvent.click(screen.getByRole('button', { name: action }));
    await screen.findByText('尚未查到原请求，可按原标识重试生成请求。');
    await fireEvent.click(screen.getByRole('button', { name: '按原标识重试生成请求' }));
    await screen.findByRole('button', { name: '采用摘要' });
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('查询未登记且资格核对暂时失败时保留草稿和原标识，明确撤权后关闭', async () => {
    const original = f.send.getMockImplementation()!;
    let key = '', probeStatus = 503;
    const queried: string[] = [];
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        key = (init.headers as Record<string, string>)['X-Request-Id'];
        return Promise.reject(new TypeError('offline'));
      }
      if (path.includes('/metadata-suggestions/')) {
        queried.push(path);
        return Promise.resolve(new Response(null, { status: 404 }));
      }
      if (key && path.includes('categories')) return Promise.resolve(new Response(null, { status: probeStatus }));
      return original(path, init);
    });
    const denied = vi.fn();
    renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, sourceVersionId: '30', onDenied: denied } });
    await fireEvent.update(await screen.findByLabelText('资料标题'), '保留的草稿');
    await fireEvent.click(screen.getByRole('button', { name: startButton }));
    await screen.findByRole('button', { name: '按原标识重试生成请求' });
    await fireEvent.click(screen.getByRole('button', { name: '核对原请求' }));
    await screen.findByText('暂时无法核对编辑资格，请稍后核对原请求。');
    expect(screen.getByDisplayValue('保留的草稿')).toBeTruthy();
    expect(denied).not.toHaveBeenCalled();
    probeStatus = 403;
    await fireEvent.click(screen.getByRole('button', { name: '核对原请求' }));
    await waitFor(() => expect(denied).toHaveBeenCalledWith(403));
    expect(screen.queryByDisplayValue('保留的草稿')).toBeNull();
    expect(queried).toEqual(Array(2).fill(`/api/documents/9/metadata-suggestions/${key}`));
  });

  it('取消先确认时迟到的创建响应不会恢复建议', async () => {
    const original = f.send.getMockImplementation()!, pending = deferred<Response>();
    let key = '';
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        key = (init.headers as Record<string, string>)['X-Request-Id'];
        return pending.promise;
      }
      if (path.endsWith('/cancel')) return Promise.resolve(json(suggestionView(key, 'CANCELLED', 'CANCELLED')));
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(screen.getByRole('button', { name: '取消整理' }));
    await screen.findByText('整理已取消。');
    pending.resolve(json(suggestionView(key)));
    await flushPromises();
    expect(screen.queryByRole('button', { name: '采用摘要' })).toBeNull();
    expect(screen.getByText('整理已取消。')).toBeTruthy();
  });

  it('到期后清除建议和定位并保留手动草稿', async () => {
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (!path.endsWith('/metadata-suggestions')) return original(path, init);
      return Promise.resolve(json({ ...suggestionView((init.headers as Record<string, string>)['X-Request-Id']),
        expiresAt: new Date(Date.now() + 250).toISOString() }));
    });
    editor();
    await fireEvent.update(await screen.findByLabelText('资料标题'), '手工保留');
    await fireEvent.click(screen.getByRole('button', { name: startButton }));
    await screen.findByRole('button', { name: '采用摘要' });
    await screen.findByText('建议已过期，请重新读取后生成。');
    expect(screen.queryByText(/V3 · 结构块/)).toBeNull();
    expect(screen.getByDisplayValue('手工保留')).toBeTruthy();
  });

  it('辅助保存丢失响应后只按原标识和载荷重试', async () => {
    const original = f.send.getMockImplementation()!;
    let creates = 0, attempts = 0;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        creates++;
        return Promise.resolve(json(suggestionView((init.headers as Record<string, string>)['X-Request-Id'])));
      }
      if (init?.method === 'PUT') {
        if (++attempts === 1) return Promise.reject(new TypeError('offline'));
        return Promise.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['summary'], replayed: true }));
      }
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '采用摘要' }));
    await save();
    await fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
    await waitFor(() => expect(writes()).toHaveLength(2));
    const [one, two] = writes();
    expect([one[0], one[1].body, one[1].headers['X-Request-Id']]).toEqual([two[0], two[1].body, two[1].headers['X-Request-Id']]);
    expect(creates).toBe(1);
  });

  it.each(['SOURCE_CHANGED', 'METADATA_VERSION_CONFLICT', 'TREE_VERSION_CONFLICT', 'SUGGESTION_EXPIRED'])(
    '辅助保存按原内容重试收到 %s 后，保留修订并阻止继续提交', async reason => {
      const original = f.send.getMockImplementation()!;
      let attempts = 0;
      f.send.mockImplementation(async (path: string, init: RequestInit) => {
        if (path.endsWith('/metadata-suggestions')) {
          return json(suggestionView((init.headers as Record<string, string>)['X-Request-Id']));
        }
        if (init?.method === 'PUT') {
          if (++attempts === 1) throw new TypeError('offline');
          return json({ code: 500, data: null, reasonCode: reason }, false, 409);
        }
        return original(path, init);
      });
      editor();
      await fireEvent.click(await screen.findByRole('button', { name: startButton }));
      await fireEvent.click(await screen.findByRole('button', { name: '采用摘要' }));
      await fireEvent.update(screen.getByLabelText('人工摘要'), '手工修订后的摘要');
      await save();
      await fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
      await flushPromises();
      expect(screen.getByDisplayValue('手工修订后的摘要')).toBeTruthy();
      const button = screen.getByRole<HTMLButtonElement>('button', { name: '保存资料' });
      expect(button.disabled).toBe(true);
      expect(screen.getByText('已采用的建议需要重新核对。请重新读取资料。')).toBeTruthy();
      await fireEvent.submit(button.closest('form')!);
      expect(writes()).toHaveLength(2);
      const [first, retry] = writes();
      expect([retry[0], retry[1].body, retry[1].headers['X-Request-Id']])
        .toEqual([first[0], first[1].body, first[1].headers['X-Request-Id']]);
    });

  it('旧保存重试确认建议过期时，已另行采用的新建议仍可保存', async () => {
    const original = f.send.getMockImplementation()!, generations: string[] = [];
    let attempts = 0;
    f.send.mockImplementation(async (path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        const key = (init.headers as Record<string, string>)['X-Request-Id'];
        generations.push(key);
        return json(suggestionView(key));
      }
      if (init?.method === 'PUT') {
        attempts++;
        if (attempts === 1) throw new TypeError('offline');
        if (attempts === 2) return json({ code: 500, data: null, reasonCode: 'SUGGESTION_EXPIRED' }, false, 409);
        return json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false });
      }
      return original(path, init);
    });
    const saved = vi.fn();
    editor(saved);
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '采用摘要' }));
    await save();
    const retry = await screen.findByRole('button', { name: '按原内容重试' });
    await fireEvent.click(screen.getByRole('button', { name: '舍弃已采用的建议并恢复手工草稿' }));
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '采用标题' }));
    await fireEvent.submit(screen.getByLabelText('资料标题').closest('form')!);
    expect(writes()).toHaveLength(1);
    await fireEvent.click(retry);
    await save();
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    const [first, replay, latest] = writes();
    expect(replay[0]).toBe(first[0]);
    expect(generations[1]).not.toBe(generations[0]);
    expect(latest[0]).toBe(`/api/documents/9/metadata-suggestions/${generations[1]}/metadata`);
    expect(JSON.parse(latest[1].body).adoptedFields).toEqual(['title']);
  });

  it('来源冲突后重读当前发布版本，明确舍弃摘要时保留手工草稿', async () => {
    const original = f.send.getMockImplementation()!;
    let changed = false;
    const sources: number[] = [];
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        const key = (init.headers as Record<string, string>)['X-Request-Id'];
        sources.push(JSON.parse(init.body as string).sourceVersionId);
        return Promise.resolve(json({ ...suggestionView(key), sourceVersionId: changed ? '31' : '30', metadataVersion: changed ? 4 : 3 }));
      }
      if (init?.method === 'PUT') {
        if (path.includes('metadata-suggestions')) {
          changed = true;
          return Promise.resolve(json({ code: 500, data: null, reasonCode: 'SOURCE_CHANGED' }, false, 409));
        }
        return Promise.resolve(json({ documentId: '9', metadataVersion: 5, changedFields: ['title', 'summary'], updatedAt: metadata.updatedAt, replayed: false }));
      }
      if (path === '/api/documents/9') return Promise.resolve(json({ id: '9', currentVersionId: '31', currentVersionNo: 4 }));
      if (changed && path.endsWith('/metadata')) return Promise.resolve(json({ ...metadata, metadataVersion: 4, tags: [] }));
      return original(path, init);
    });
    editor();
    await fireEvent.update(await screen.findByLabelText('资料标题'), '手工标题');
    await fireEvent.update(screen.getByLabelText('人工摘要'), '采用前的手工摘要');
    await fireEvent.click(screen.getByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '采用摘要' }));
    await fireEvent.update(screen.getByLabelText('人工摘要'), '修订后的候选摘要');
    await save();
    await screen.findByText(/已采用的建议需要重新核对/);
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    await screen.findByText(/来源为当前发布版本 V4/);
    expect(screen.getByDisplayValue('修订后的候选摘要')).toBeTruthy();
    expect((screen.getByRole('button', { name: '保存资料' }) as HTMLButtonElement).disabled).toBe(true);
    await fireEvent.click(screen.getByRole('button', { name: '舍弃已采用的建议并恢复手工草稿' }));
    expect(screen.getByDisplayValue('采用前的手工摘要')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: startButton }));
    await screen.findByRole('button', { name: '采用摘要' });
    expect(sources).toEqual([30, 31]);
    await save();
    const [path, init] = writes()[1];
    expect(path).toBe('/api/documents/9/metadata');
    expect(JSON.parse(init.body)).toEqual({ title: '手工标题', summary: '采用前的手工摘要', categoryId: null, tagIds: [], expectedMetadataVersion: 4 });
  });

  it.each([
    { first: 503, second: 404, firstPath: 'metadata' },
    { first: 404, second: 503, firstPath: 'document' },
    { first: 503, second: 403, firstPath: 'document' },
    { first: 401, second: 503, firstPath: 'metadata' },
  ])('重读时 $firstPath 先返回 $first，另一接口返回 $second，拒绝立即清除草稿', async ({ first, second, firstPath }) => {
    const original = f.send.getMockImplementation()!, denied = vi.fn();
    const metadataRead = deferred<Response>(), documentRead = deferred<Response>();
    let refreshing = false;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (refreshing && path === '/api/documents/9/metadata') return metadataRead.promise;
      if (refreshing && path === '/api/documents/9') return documentRead.promise;
      return original(path, init);
    });
    renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, onDenied: denied } });
    await fireEvent.update(await screen.findByLabelText('资料标题'), '私有草稿');
    refreshing = true;
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    const [one, two] = firstPath === 'metadata' ? [metadataRead, documentRead] : [documentRead, metadataRead];
    one.resolve(new Response(null, { status: first }));
    await flushPromises();
    if (first === 503) {
      expect(screen.getByDisplayValue('私有草稿')).toBeTruthy();
      expect(denied).not.toHaveBeenCalled();
    } else {
      expect(screen.queryByDisplayValue('私有草稿')).toBeNull();
      expect(denied).toHaveBeenCalledWith(first);
    }
    two.resolve(new Response(null, { status: second }));
    await flushPromises();
    expect(denied).toHaveBeenCalledExactlyOnceWith(first === 503 ? second : first);
    expect(screen.queryByLabelText('资料标题')).toBeNull();
  });

  it('切换文档后，旧文档重读的迟到拒绝不会清除新草稿', async () => {
    const original = f.send.getMockImplementation()!, denied = vi.fn(), oldDetail = deferred<Response>();
    let refreshing = false;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (refreshing && path === '/api/documents/9/metadata') return Promise.resolve(new Response(null, { status: 503 }));
      if (path === '/api/documents/9') return oldDetail.promise;
      if (path === '/api/documents/10/metadata') return Promise.resolve(json({ ...metadata, documentId: '10', title: '另一篇' }));
      return original(path, init);
    });
    const page = renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, onDenied: denied } });
    await screen.findByDisplayValue('原名');
    refreshing = true;
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    await page.rerender({ documentId: '10' });
    await fireEvent.update(await screen.findByDisplayValue('另一篇'), '新文档草稿');
    oldDetail.resolve(new Response(null, { status: 404 }));
    await flushPromises();
    expect(screen.getByDisplayValue('新文档草稿')).toBeTruthy();
    expect(denied).not.toHaveBeenCalled();
  });

  it.each([
    ['查询标签', 403], ['查询标签', 404], ['展开 章节', 403], ['展开 章节', 401],
  ] as const)('%s 遇到 %i 时清除草稿、建议和来源定位', async (action, status) => {
    const original = f.send.getMockImplementation()!, denied = vi.fn();
    let revoked = false;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        return Promise.resolve(json(suggestionView((init.headers as Record<string, string>)['X-Request-Id'])));
      }
      if (path.includes('categories')) {
        if (revoked && action === '展开 章节') return Promise.resolve(new Response(null, { status }));
        return Promise.resolve(json({ items: [{ id: '5', name: '章节', parentId: null, sortOrder: 1, path: [{ id: '5', name: '章节' }] }],
          hasMore: false, nextCursor: null, treeVersion: 1 }));
      }
      if (revoked && action === '查询标签' && path.includes('tags')) return Promise.resolve(new Response(null, { status }));
      return original(path, init);
    });
    renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, sourceVersionId: '30', versionNo: 3, onDenied: denied } });
    await fireEvent.update(await screen.findByLabelText('资料标题'), '手工草稿');
    await fireEvent.click(screen.getByRole('button', { name: startButton }));
    await screen.findByRole('button', { name: '采用摘要' });
    revoked = true;
    await fireEvent.click(screen.getByRole('button', { name: action }));
    await waitFor(() => expect(denied).toHaveBeenCalledExactlyOnceWith(status));
    expect(screen.queryByDisplayValue('手工草稿')).toBeNull();
    expect(screen.queryByText('建议：候选摘要')).toBeNull();
    expect(screen.queryByText(/V3 · 结构块 0/)).toBeNull();
    expect(screen.queryByRole('button', { name: '采用摘要' })).toBeNull();
  });

  it('重读仍能查看文档但已失去编辑资格时清除草稿', async () => {
    const original = f.send.getMockImplementation()!, denied = vi.fn();
    let revoked = false;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path === '/api/documents/9') return Promise.resolve(json({ id: '9', currentVersionId: '30', currentVersionNo: 3 }));
      if (revoked && path.includes('categories')) return Promise.resolve(new Response(null, { status: 403 }));
      return original(path, init);
    });
    renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, onDenied: denied } });
    await fireEvent.update(await screen.findByLabelText('资料标题'), '撤权前草稿');
    revoked = true;
    await fireEvent.click(screen.getByRole('button', { name: '重新读取资料' }));
    await waitFor(() => expect(denied).toHaveBeenCalledWith(403));
    expect(screen.queryByDisplayValue('撤权前草稿')).toBeNull();
    expect(writes()).toHaveLength(0);
  });

  it('失效的已采用字段无法按普通资料保存', async () => {
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (path.endsWith('/metadata-suggestions')) {
        return Promise.resolve(json(suggestionView((init.headers as Record<string, string>)['X-Request-Id'])));
      }
      if (init?.method === 'PUT') return Promise.resolve(json({ code: 500, data: null, reasonCode: 'SUGGESTION_EXPIRED' }, false, 409));
      return original(path, init);
    });
    editor();
    await fireEvent.click(await screen.findByRole('button', { name: startButton }));
    await fireEvent.click(await screen.findByRole('button', { name: '采用摘要' }));
    await save();
    await screen.findByText(/已采用的建议需要重新核对/);
    expect(writes()).toHaveLength(1);
    expect((screen.getByRole('button', { name: '保存资料' }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('文档级资料表单', () => {
  it('缺少独立能力时禁用编辑', async () => {
    renderHost(MetadataPanel, { props: { documentId: '9', canEdit: false, onTitle: vi.fn() } });
    await screen.findByText(/停用主题/);
    expect((screen.getByRole('button', { name: '编辑资料' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('保留停用标签并与删除协调，在途表单卸载不隐藏写入', async () => {
    const pending = deferred<Response>(), operations = new DocumentOperations(), saved = vi.fn();
    const view = coordinated(operations, saved);
    await screen.findByDisplayValue('原名');
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? pending.promise : original(path, init));
    await fireEvent.update(screen.getByLabelText('资料标题'), '新名'); await save();
    expect(writes()[0][1].body).toContain('9223372036854775806'); expect(writes()[0][1].body).toContain('"categoryId":null');
    expect(operations.getSnapshot().pending).toBe(1); operations.beginDelete(); expect(operations.canSubmitDelete()).toBe(false);
    view.unmount(); expect(operations.getSnapshot().pending).toBe(1);
    pending.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false })); await flushPromises();
    await waitFor(() => expect(operations.getSnapshot().pending).toBe(0)); expect(saved).not.toHaveBeenCalled();
  });
  it('结果不确定时匹配重试保留原标识和原载荷', async () => {
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? Promise.reject(new TypeError('offline')) : original(path, init));
    renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, onClose: vi.fn(), onSaved: vi.fn() } });
    await screen.findByDisplayValue('原名'); await fireEvent.update(screen.getByLabelText('资料标题'), '新名'); await save();
    await fireEvent.click(await screen.findByRole('button', { name: '按原内容重试' }));
    await waitFor(() => expect(writes()).toHaveLength(2));
    expect(writes()[0][1].body).toBe(writes()[1][1].body);
    expect(writes()[0][1].headers['X-Request-Id']).toBe(writes()[1][1].headers['X-Request-Id']);
  });
  it('删除确认期间禁止新的资料提交', async () => {
    const operations = new DocumentOperations(); operations.beginDelete();
    coordinated(operations);
    await screen.findByDisplayValue('原名');
    expect((screen.getByRole('button', { name: '保存资料' }) as HTMLButtonElement).disabled).toBe(true); expect(writes()).toHaveLength(0);
  });
  it('版本冲突保留草稿供人工核对，不自动覆盖或重试', async () => {
    const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? Promise.resolve(json({ code: 500, data: null, reasonCode: 'METADATA_VERSION_CONFLICT' }, false, 409)) : original(path, init));
    renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, onClose: vi.fn(), onSaved: vi.fn() } });
    await screen.findByDisplayValue('原名'); await fireEvent.update(screen.getByLabelText('资料标题'), '草稿'); await save();
    await screen.findByText('文档资料已变化，请重新读取后编辑。'); expect(screen.getByDisplayValue('草稿')).toBeTruthy(); expect(writes()).toHaveLength(1);
  });
  it('切换文档后忽略旧保存回调', async () => {
    const pending = deferred<Response>(), saved = vi.fn(), original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? pending.promise : path === '/api/documents/10/metadata' ? Promise.resolve(json({ ...metadata, documentId: '10', title: '另一篇' })) : original(path, init));
    const view = renderHost(MetadataEditor, { props: { documentId: '9', canEdit: true, onClose: vi.fn(), onSaved: saved } });
    await screen.findByDisplayValue('原名'); await fireEvent.update(screen.getByLabelText('资料标题'), '旧草稿'); await save();
    await view.rerender({ documentId: '10' }); await screen.findByDisplayValue('另一篇');
    pending.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false })); await flushPromises();
    expect(saved).not.toHaveBeenCalled(); expect(screen.getByDisplayValue('另一篇')).toBeTruthy();
  });
  it('切换身份清理旧写入回调，保存拒绝后不再沿用旧能力', async () => {
    const pending = deferred<Response>(), saved = vi.fn(), denied = vi.fn(), original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? pending.promise : original(path, init));
    renderHost(defineComponent({ setup: () => () => h(MetadataEditor, { key: f.epoch, documentId: '9', canEdit: true, onClose: vi.fn(), onSaved: saved, onDenied: denied }) }));
    await screen.findByDisplayValue('原名'); await save(); f.epoch++;
    await nextTick();
    await screen.findByDisplayValue('原名'); pending.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false })); await flushPromises();
    expect(saved).not.toHaveBeenCalled();
    f.send.mockImplementation((path: string, init: RequestInit) => init?.method === 'PUT' ? Promise.resolve(new Response(null, { status: 403 })) : original(path, init));
    await save(); await waitFor(() => expect(denied).toHaveBeenCalledWith(403));
    expect((screen.getByRole('button', { name: '保存资料' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('保存成功后重读故障仍保留确认回执', async () => {
    let completed = false; const original = f.send.getMockImplementation()!;
    f.send.mockImplementation((path: string, init: RequestInit) => {
      if (init?.method === 'PUT') {
        completed = true;
        return Promise.resolve(json({ documentId: '9', metadataVersion: 4, updatedAt: metadata.updatedAt, changedFields: ['title'], replayed: false }));
      }
      return path.endsWith('/metadata') && completed ? Promise.resolve(new Response(null, { status: 503 })) : original(path, init);
    });
    renderHost(MetadataPanel, { props: { documentId: '9', canEdit: true, onTitle: vi.fn() } }); await screen.findByText(/停用主题/);
    await fireEvent.click(screen.getByRole('button', { name: '编辑资料' })); await screen.findByDisplayValue('原名'); await save();
    await screen.findByText('资料已保存；下方读取显示当前资料。'); await screen.findByText('服务暂不可用，请稍后重试。'); expect(writes()).toHaveLength(1);
  });
});
it('筛选草稿只在主动应用时提交，换空间清分类', async () => {
  const submit = vi.fn();
  render(MetadataFilters, { props: { spaces: [{ spaceId: '2', name: 'A', archived: false, viewable: true, manageable: false }, { spaceId: '3', name: 'B', archived: false, viewable: true, manageable: false }], onSubmit: submit } });
  await fireEvent.update(screen.getByLabelText('标题关键词'), '制度%');
  await fireEvent.click(screen.getByRole('button', { name: '仅未分类' })); expect(submit).not.toHaveBeenCalled();
  await fireEvent.update(screen.getByLabelText('知识空间'), '3');
  await fireEvent.click(screen.getByRole('button', { name: '应用筛选' }));
  expect(submit.mock.calls[0][0]).toMatchObject({ titleQuery: '制度%', spaceId: '3', tagIds: [] });
  expect(submit.mock.calls[0][0].uncategorized).toBeUndefined();
});
