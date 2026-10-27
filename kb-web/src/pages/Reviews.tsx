import { useState } from 'react';
import { Alert, Button, Empty, Space } from 'antd';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { reviewPaths, type ReviewItem, type ReviewPage } from '../reviews/api';
import { reviewLabel, VersionReviewPanel } from '../reviews/VersionReviewPanel';
import { useReviewScope } from '../reviews/lifecycle';

export function ReviewsPage() {
  const scope = useReviewScope();
  return <><PageHeader title="版本审核" description="本人版本与当前合格审核待办" />
    {scope.active ? <ReviewWorkspace key={scope.key} /> : <Alert type="info" title="请先完成登录与身份确认" />}</>;
}
function ReviewWorkspace() {
  const [kind, setKind] = useState<'mine' | 'pending'>('mine');
  return <><Space><Button onClick={() => setKind('mine')}>本人版本</Button><Button onClick={() => setKind('pending')}>合格审核待办</Button></Space>
    <ReviewList key={kind} kind={kind} /></>;
}
function ReviewList({ kind }: { kind: 'mine' | 'pending' }) {
  const { session } = useSession(), [cursor, setCursor] = useState<string | null>(null);
  const [selected, setSelected] = useState<ReviewItem | null>(null);
  const query = useResource(signal => session.json<ReviewPage>(reviewPaths.list(kind, cursor), { signal }), `${kind}/${cursor}`);
  return <><section className="panel"><h2>{kind === 'mine' ? '本人上传或送审版本' : '当前合格审核待办'}</h2>
    <Button onClick={() => { setSelected(null); query.reload(); }}>刷新列表</Button><Feedback {...query} retry={query.reload} />
    {query.data?.items.length === 0 && <Empty description="当前无可展示版本" />}
    <ul>{query.data?.items.map(item => <li key={item.state.versionId}>
      {kind === 'pending' && item.title ? <span>{item.title} · </span> : null}
      <span>文档 {item.state.documentId} · v{item.state.versionNo} · {reviewLabel(item.state)}</span>
      <Button onClick={() => setSelected(item)}>查看版本 {item.state.versionId} 状态</Button>
    </li>)}</ul>
    {query.data?.hasMore && query.data.nextCursor && <Button onClick={() => { setSelected(null); setCursor(query.data!.nextCursor); }}>下一页版本</Button>}
    {cursor && <Button onClick={() => { setSelected(null); setCursor(null); }}>回到第一页</Button>}
  </section>
    {selected && <VersionReviewPanel key={`${selected.state.documentId}/${selected.state.versionId}`} documentId={selected.state.documentId} versionId={selected.state.versionId} onChanged={query.reload} />}
  </>;
}
