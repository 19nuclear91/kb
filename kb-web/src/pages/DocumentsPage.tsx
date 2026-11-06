import { useEffect, useState } from 'react';
import { Alert, Button, Pagination, Table, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { MetadataFilters } from '../documents/metadata/Filters';
import { metadataApi, type Filters } from '../documents/metadata/api';
import { consumeDocumentDeleted } from '../documents/notice';
import { useResource } from '../useResource';
import { Feedback, PageHeader } from '../components';
import { useFavorites } from '../favorites/useFavorites';
import { FavoriteButton } from '../favorites/FavoriteButton';
import { useReviewScope } from '../reviews/lifecycle';
import { ProgressCard } from '../documents/ProgressCard';
import { UploadDialog } from '../documents/UploadDialog';
import type { DocumentSummary, Space as KnowledgeSpace, UploadResult } from '../types';

export function DocumentsPage() {
  const scope = useReviewScope();
  return scope.active ? <DocumentsContent key={scope.key} /> : <Alert type="info" title="请先完成登录与身份确认" />;
}
function DocumentsContent() {
  const { session, state: identity } = useSession();
  const [deletionNotice, setDeletionNotice] = useState<number | null>(null);
  useEffect(() => {
    if (consumeDocumentDeleted(session)) setDeletionNotice(identity.epoch);
    const timer = setTimeout(() => setDeletionNotice(null), 5000);
    return () => clearTimeout(timer);
  }, [session, identity.epoch]);
  const [page, setPage] = useState(1), [filters, setFilters] = useState<Filters>({});
  const [upload, setUpload] = useState(false), [submitted, setSubmitted] = useState<UploadResult | null>(null);
  const query = useResource(signal => metadataApi.query(session, filters, page, signal), `${page}/${JSON.stringify(filters)}`);
  const spaces = useResource(signal => session.json<KnowledgeSpace[]>('/api/admin/spaces', { signal }), 'spaces');
  const favorites = useFavorites(query.data?.items.map(item => item.id) ?? [], query.data);
  return <><PageHeader title="文档中心" description="整理团队知识，在权限范围内发现可靠资料" extra={<Button type="primary" size="large" onClick={() => setUpload(true)}>上传文档</Button>} />
    {deletionNotice === identity.epoch && <Alert type="success" title="文档已删除" />}
    {submitted && !favorites.unavailable(submitted.documentId) && <ProgressCard key={submitted.versionId} documentId={submitted.documentId} versionId={submitted.versionId} submitted />}
    <section className="panel"><MetadataFilters spaces={spaces.data ?? []} onSubmit={value => { setFilters(value); setPage(1); }} />
      {spaces.error && <Alert type="warning" title="空间目录暂不可用，仍可查看全部文档。" style={{ marginBottom: 16 }} />}
      <Feedback {...query} retry={query.reload} />{query.data && <><Table<DocumentSummary> rowKey="id" pagination={false} dataSource={query.data.items.filter(item => !favorites.unavailable(item.id))} columns={[
        { title: '文档', dataIndex: 'title', render: (title, row) => <Link to={`/documents/${row.id}`}>{title}</Link> },
        { title: '当前版本', render: (_, row) => row.currentVersionNo == null ? '尚未发布' : `v${row.currentVersionNo}` },
        { title: '密级', dataIndex: 'securityLevel', render: value => <Tag>{`密级 ${value}`}</Tag> },
        { title: '最近更新', dataIndex: 'updatedAt', render: value => value ? new Date(value).toLocaleDateString('zh-CN') : '—' },
        { title: '个人收藏', render: (_, row) => <FavoriteButton id={row.id} favorites={favorites} /> },
      ]} /><Pagination style={{ marginTop: 24 }} current={page} total={Number(query.data.total)} pageSize={20} showSizeChanger={false} onChange={setPage} /></>}
    </section><UploadDialog open={upload} onClose={() => setUpload(false)} onUploaded={result => { setSubmitted(result); query.reload(); }} />
  </>;
}
