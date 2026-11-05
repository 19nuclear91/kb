import { VersionPreview } from './office/VersionPreview';
import { useEffect, useState } from 'react';
import { Alert, Drawer, Space, Button, Tag } from 'antd';
import { useSession } from './SessionProvider';
import { useResource } from './useResource';
import { citationPath } from './logic';
import { Feedback, SafeText } from './components';
import { DownloadButton } from './FileAccess';
import type { Citation, CitationDetail } from './types';
import { ApiError } from './api';

function CitationContent({ citation }: { citation: Citation }) {
  const { session } = useSession();
  const path = citationPath(citation);
  const query = useResource(signal => path ? session.json<CitationDetail>(path, { signal }) : Promise.reject(new ApiError(400)), path ?? 'invalid');
  const [preview, setPreview] = useState<string | null>(null);
  useEffect(() => { if (query.error) setPreview(null); }, [query.error]);
  if (!path) return <Alert type="info" title="该参考材料缺少版本身份，无法定位原版本。" />;
  return <><Feedback {...query} retry={query.reload} />{query.data && <>
    <h2>{query.data.docTitle}</h2><Space wrap><Tag>版本 {query.data.versionNo ?? '未知'}</Tag><Tag>来源位置仅作提示</Tag></Space>
    {query.data.status === 'CHUNK_UNAVAILABLE' ? <Alert style={{ marginTop: 20 }} type="info" title="当前没有匹配片段，可查看原版本文件。" /> : query.data.chunk && <div style={{ margin: '24px 0' }}>
      <p className="muted">{query.data.chunk.headingPath ?? '章节未知'} · {query.data.chunk.pageNo != null ? `第 ${query.data.chunk.pageNo} 页` : '页码未知'}</p>
      <SafeText text={query.data.chunk.content} />
    </div>}
    <Space style={{ marginTop: 20 }}><Button onClick={() => setPreview(path.replace(/\/citations\/[1-9]\d*$/, '/preview'))}>预览原版本</Button>
      {query.data.downloadPath && <DownloadButton path={query.data.downloadPath} name={query.data.docTitle ?? '原版本文件'} onDenied={() => { setPreview(null); query.reload(); }} />}</Space>
  </>}<VersionPreview path={preview} onClose={() => setPreview(null)} /></>;
}
export function CitationDrawer({ citation, onClose }: { citation: Citation | null; onClose: () => void }) {
  return <Drawer title="版本引用" open={!!citation} onClose={onClose} size="large" destroyOnHidden>
    {citation && <CitationContent key={citationPath(citation) ?? 'unknown'} citation={citation} />}
  </Drawer>;
}
