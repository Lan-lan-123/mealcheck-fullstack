import Button from './Button'
import { Component } from 'react'

export default class WorkspaceBoundary extends Component {
  state = { failed: false }

  static getDerivedStateFromError() { return { failed: true } }

  componentDidUpdate(previous) {
    if (this.state.failed && previous.resetKey !== this.props.resetKey) this.setState({ failed: false })
  }

  render() {
    if (!this.state.failed) return this.props.children
    return <main className="workspace-fallback" role="alert">
      <h1>页面暂时无法显示</h1>
      <p>请重新加载页面后继续。尚未保存的填写内容可能需要重新输入。</p>
      <Button variant="primary" className="primary-btn" type="button" onClick={() => window.location.reload()}>重新加载页面</Button>
    </main>
  }
}
