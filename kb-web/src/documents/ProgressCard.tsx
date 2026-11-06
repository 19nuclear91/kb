import { useEffect, useRef } from 'react';
import { Alert, Tag } from 'antd';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { reviewPaths, type ReviewStatus } from '../reviews/api';
import { Feedback } from '../components';
import { progressLabel } from '../logic';
import { useProgress } from './useProgress';

export function ProgressCard(props: { documentId: string; versionId: string; revision?: number; submitted?: boolean; onDenied?: () => void }) {
  return props.submitted ? <SubmittedVersion documentId={props.documentId} versionId={props.versionId} /> : <ReadableProgress {...props} />;
}
function SubmittedVersion({ documentId, versionId }: { documentId: string; versionId: string }) {
  const { session, state } = useSession();
  const query = useResource(signal => session.json<ReviewStatus>(reviewPaths.status(documentId, versionId), { signal }), `${state.epoch}:${documentId}/${versionId}`);
  return <section className="panel"><h2>上传已受理</h2>
    {query.error ? <Alert type="info" title="提交成功，当前无法查看后续处理状态。" /> : <Feedback {...query} />}
    {query.data && <p>版本 {versionId} · {query.data.state.indexStatus} · {query.data.state.reviewState}。上传受理不表示已发布。</p>}
    <a href="/reviews">前往本人版本核对</a></section>;
}
function ReadableProgress({ documentId, versionId, revision, onDenied }: { documentId: string; versionId: string; revision?: number; onDenied?: () => void }) {
  const state = useProgress(documentId, versionId, revision);
  const denied = useRef(onDenied); denied.current = onDenied;
  useEffect(() => { if (state.denied) denied.current?.(); }, [state.denied]);
  return <section className="panel"><h2>选中版本处理状态</h2>
    <Feedback {...state} />
    {state.data && <><Tag color={state.data.indexStatus === 'indexed' ? 'green' : 'blue'}>{progressLabel(state.data)}</Tag>
      <p className="muted">文件受理与解析发布分别完成；只有已发布版本进入检索。</p>
      {state.data.parseTruncated && <Alert type="warning" title="解析结果被截断，文档尾部可能未处理。" />}
      {(state.data.parseIssueSummary || state.data.failureSummary) && <p>{state.data.parseIssueSummary ?? state.data.failureSummary}</p>}
    </>}
  </section>;
}
