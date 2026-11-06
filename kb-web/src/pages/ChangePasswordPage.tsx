import { Button } from 'antd';
import { Navigate } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { useSso } from '../sso/useSso';
import { PasswordForm } from '../auth/PasswordForm';

export function ChangePasswordPage() {
  const { session, state } = useSession();
  const { flow } = useSso();
  if (state.phase === 'anonymous') return <Navigate to="/login" replace />;
  if (state.phase === 'ready') return <Navigate to={flow.hints.navigation()?.returnPath ?? '/documents'} replace />;
  return <div className="auth-page"><div className="auth-card"><PasswordForm forced /><Button type="link" onClick={() => void session.logout()}>退出当前账号</Button></div></div>;
}
