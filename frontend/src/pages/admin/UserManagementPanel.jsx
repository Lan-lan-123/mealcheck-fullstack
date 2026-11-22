import Button from '../../components/Button'
import { formatTime, Pagination } from './AdminUi'

export default function UserManagementPanel({ applyUserFilters, currentUser, defaultUserFilters, deletingUserId, handleDeleteUser, updatingUserId, handleSetUserEnabled, loading, setUserFilters, setUserFiltersDraft, setUserPage, setUserSize, userFiltersDraft, userPageData, userSize, users }) {
  return (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>用户管理</h2>
              <p>查看账号状态并封禁或解封普通用户。解封后需要重新登录。</p>
            </div>
          </div>
          <form className="filter-grid compact" onSubmit={applyUserFilters}>
            <input value={userFiltersDraft.username} onChange={e => setUserFiltersDraft({ ...userFiltersDraft, username: e.target.value })} placeholder="搜索用户名或昵称" />
            <select value={userFiltersDraft.role} onChange={e => setUserFiltersDraft({ ...userFiltersDraft, role: e.target.value })}>
              <option value="ALL">全部角色</option>
              <option value="ADMIN">管理员</option>
              <option value="USER">普通用户</option>
            </select>
            <Button variant="secondary" className="secondary-btn" type="submit">筛选</Button>
            <Button variant="secondary" className="secondary-btn" type="button" onClick={() => { setUserFiltersDraft(defaultUserFilters); setUserFilters(defaultUserFilters); setUserPage(0) }}>重置</Button>
          </form>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr><th>ID</th><th>用户名</th><th>昵称</th><th>角色</th><th>账号状态</th><th>最后上传</th><th>注册时间</th><th>操作</th></tr>
              </thead>
              <tbody>
                {users.length ? users.map(user => (
                  <tr key={user.id}>
                    <td>{user.id}</td>
                    <td>{user.username}</td>
                    <td>{user.displayName || '暂无'}</td>
                    <td><span className={user.role === 'ADMIN' ? 'role-tag admin' : 'role-tag'}>{user.role || 'USER'}</span></td>
                    <td>{user.enabled === false ? '已封禁' : '正常'}</td>
                    <td>{formatTime(user.lastUploadAt)}</td>
                    <td>{formatTime(user.createdAt)}</td>
                    <td>
                      {user.role !== 'ADMIN' && user.username !== currentUser?.username && <Button
                        variant={user.enabled === false ? 'secondary' : 'danger'} type="button"
                        onClick={() => handleSetUserEnabled(user)}
                        disabled={loading || updatingUserId != null || deletingUserId != null}
                      >{updatingUserId === user.id ? '处理中...' : user.enabled === false ? '解封' : '封禁'}</Button>}
                      <Button variant="danger" className="table-danger-btn" type="button" onClick={() => handleDeleteUser(user)} disabled={loading || updatingUserId != null || deletingUserId != null || user.username === currentUser?.username}>{deletingUserId === user.id ? '删除中...' : '删除'}</Button>
                    </td>
                  </tr>
                )) : <tr><td colSpan="8" className="empty-cell">暂无用户</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={userPageData} pageSize={userSize} setPage={setUserPage} setPageSize={setUserSize} loading={loading} />
        </section>
  )
}
