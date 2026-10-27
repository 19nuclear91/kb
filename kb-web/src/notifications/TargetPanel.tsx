import { useCallback, useState } from 'react';
import { Alert, Button } from 'antd';
import { VersionReviewPanel } from '../reviews/VersionReviewPanel';
import { CommentTargetPanel } from '../comments/CommentPanel';
import type { ReviewStatus } from '../reviews/api';
import type { NotificationItem } from './api';

/**
 * 只打开通知记录的固定版本／轮次或固定版本上的回复；不跟随“最新版本”，不显示外链或文件入口。
 * 打开不代表已读或已处理，授权由源业务接口当次核对。
 */
export function NotificationTargetPanel({ item, onClose }: { item: NotificationItem; onClose: () => void }) {
  const [status, setStatus] = useState<ReviewStatus | undefined>();
  const [denied, setDenied] = useState(false);
  const onStatus = useCallback((value: ReviewStatus | undefined) => setStatus(value), []);
  const onDenied = useCallback(() => { setStatus(undefined); setDenied(true); }, []);
  const target = item.target;
  if (target.kind === 'DOCUMENT_VERSION_COMMENT') {
    return <section className="panel" aria-label="通知目标">
      <div className="toolbar"><span>文档 {target.documentId} · 固定版本 {target.versionId}</span>
        <Button onClick={onClose}>关闭目标</Button></div>
      <p className="muted">打开目标不会标记已读；评论按当前权限重新核对，不改为显示最新版本的讨论。</p>
      <CommentTargetPanel key={`${target.documentId}/${target.versionId}/${target.commentId}`} documentId={target.documentId}
        versionId={target.versionId} commentId={target.commentId} />
    </section>;
  }
  const matches = status && status.state.documentId === target.documentId && status.state.versionId === target.versionId;
  const drifted = matches && (status.state.reviewId ?? '').toLowerCase() !== target.reviewId;
  return <section className="panel" aria-label="通知目标">
    <div className="toolbar"><span>文档 {target.documentId} · 固定版本 {target.versionId}</span>
      <Button onClick={onClose}>关闭目标</Button></div>
    <p className="muted">打开目标不会标记已读，也不会提交任何审核决定；内容和操作按当前权限重新核对。</p>
    {denied ? <Alert type="warning" title="目标当前不可访问，已清理旧内容。请核对通知或稍后重试。" />
      : <>{drifted && <Alert type="warning" title="该版本的审核轮次已变化，以下为当前状态，请核对后再操作。" />}
        <VersionReviewPanel key={`${target.documentId}/${target.versionId}`} documentId={target.documentId}
          versionId={target.versionId} files={false} onStatus={onStatus} onDenied={onDenied} /></>}
  </section>;
}
