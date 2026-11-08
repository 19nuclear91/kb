import { beforeEach, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/vue';
import CitationDrawer from '../CitationDrawer.vue';

const f = vi.hoisted(() => ({ json: vi.fn() }));
const session = { json: f.json };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, state: { phase: 'ready', epoch: 1 } }) }));
vi.mock('./VersionPreview.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return { default: defineComponent({ props: { path: String }, setup: props => () => h('output', props.path) }) };
});
const citation = { docId: '9007199254740993', versionId: '9007199254740995', chunkId: '9007199254740997', versionNo: 1 };
beforeEach(() => f.json.mockReset().mockResolvedValue({ ...citation, docTitle: '引用 V1', status: 'AVAILABLE', chunk: { content: '引用片段', pageNo: 8 },
  previewPath: `/api/documents/${citation.docId}/versions/9007199254740996/preview`, downloadPath: null }));
it('V2 已发布也按引用 V1 的准确身份打开，页码只提示', async () => {
  render(CitationDrawer, { props: { citation, onClose: () => {} } });
  await fireEvent.click(await screen.findByRole('button', { name: '预览原版本' }));
  expect(screen.getByText(`/api/documents/${citation.docId}/versions/${citation.versionId}/preview`)).toBeTruthy();
  expect(screen.getByText('来源位置仅作提示')).toBeTruthy();
  expect(screen.queryByRole('button', { name: '下载原件' })).toBeNull();
  await waitFor(() => expect(f.json).toHaveBeenCalledWith(`/api/documents/${citation.docId}/versions/${citation.versionId}/citations/${citation.chunkId}`, expect.anything()));
});
it('缺失 versionId 不推断当前版本，也不读取文件', async () => {
  render(CitationDrawer, { props: { citation: { ...citation, versionId: null }, onClose: () => {} } });
  await screen.findByText('该参考材料缺少版本身份，无法定位原版本。');
  expect(f.json).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: '预览原版本' })).toBeNull();
});
