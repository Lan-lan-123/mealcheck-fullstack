import { formatTime, Pagination } from './AdminUi'

export default function UserManagementPanel({ applyUserFilters, currentUser, defaultUserFilters, deletingUserId, handleDeleteUser, loading, setUserFilters, setUserFiltersDraft, setUserPage, setUserSize, userFiltersDraft, userPageData, userSize, users }) {
  return (
        <section className="admin-section">
          <div className="admin-section-head">
            <div>
              <h2>用户管理</h2>
              <p>查看注册用户、角色、注册时间和最后一次上传时间。</p>
            </div>
          </div>
          <form className="filter-grid compact" onSubmit={applyUserFilters}>
            <input value={userFiltersDraft.username} onChange={e => setUserFiltersDraft({ ...userFiltersDraft, username: e.target.value })} placeholder="搜索用户名或昵称" />
            <select value={userFiltersDraft.role} onChange={e => setUserFiltersDraft({ ...userFiltersDraft, role: e.target.value })}>
              <option value="ALL">全部角色</option>
              <option value="ADMIN">管理员</option>
              <option value="USER">普通用户</option>
            </select>
            <button className="secondary-btn" type="submit">筛选</button>
            <button className="secondary-btn" type="button" onClick={() => { setUserFiltersDraft(defaultUserFilters); setUserFilters(defaultUserFilters); setUserPage(0) }}>重置</button>
          </form>
          <div className="admin-table-wrap">
            <table className="admin-table">
              <thead>
                <tr><th>ID</th><th>用户名</th><th>昵称</th><th>角色</th><th>最后上传</th><th>注册时间</th><th>操作</th></tr>
              </thead>
              <tbody>
                {users.length ? users.map(user => (
                  <tr key={user.id}>
                    <td>{user.id}</td>
                    <td>{user.username}</td>
                    <td>{user.displayName || '暂无'}</td>
                    <td><span className={user.role === 'ADMIN' ? 'role-tag admin' : 'role-tag'}>{user.role || 'USER'}</span></td>
                    <td>{formatTime(user.lastUploadAt)}</td>
                    <td>{formatTime(user.createdAt)}</td>
                    <td><button className="table-danger-btn" type="button" onClick={() => handleDeleteUser(user)} disabled={loading || deletingUserId === user.id || user.username === currentUser?.username}>{deletingUserId === user.id ? '删除中...' : '删除'}</button></td>
                  </tr>
                )) : <tr><td colSpan="7" className="empty-cell">暂无用户</td></tr>}
              </tbody>
            </table>
          </div>
          <Pagination pageData={userPageData} pageSize={userSize} setPage={setUserPage} setPageSize={setUserSize} loading={loading} />
        </section>
  )
}
