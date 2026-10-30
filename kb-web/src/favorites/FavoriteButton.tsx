import { Button, Space } from 'antd';
import { useDocumentOperations } from '../documents/OperationContext';
import type { Favorites } from './useFavorites';

export function FavoriteButton({ id, favorites }: { id: string; favorites: Favorites }) {
  const { state } = useDocumentOperations();
  const status = favorites.status(id);
  if (status.phase === 'unavailable') return null;
  const ready = status.phase === 'ready';
  const label = ready ? status.favorited ? '取消收藏' : '收藏文档' : '收藏状态待确认';
  return <Space orientation="vertical" size={4}>
    <Button size="small" aria-label={label} aria-pressed={ready ? status.favorited : undefined} loading={!!status.busy}
      disabled={!ready || status.busy || state.exclusive} onClick={() => void favorites.set(id, !status.favorited)}>
      {label}
    </Button>
    {(status.phase === 'error' || status.phase === 'uncertain') && <>
      <span role="status" className="muted">{status.message}</span>
      <Button type="link" size="small" disabled={status.busy} onClick={() => void favorites.check(id)}>核对收藏状态</Button>
    </>}
  </Space>;
}
