import { useSession } from '../SessionProvider';
import { PageHeader } from '../components';
import { BindingPanel } from '../sso/BindingPanel';
import { PasswordForm } from '../auth/PasswordForm';

export function AccountPage() {
  const { state } = useSession();
  const user = state.me!.user;
  return <><PageHeader title="个人账户" description="你的身份信息由服务器提供" /><div className="account-grid"><section className="panel"><h2>{user.displayName}</h2>
    <p>用户名：{user.username}</p><p>主部门 ID：{user.departmentId ?? '未提供'}</p><p>密级：{user.clearanceLevel}</p>
  </section><section className="panel"><PasswordForm /></section></div><BindingPanel key={state.epoch} /></>;
}
