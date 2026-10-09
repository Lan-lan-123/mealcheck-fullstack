// A response may update the view only while its request still owns that view.
export function createRequestScope() {
  let version = 0
  return {
    begin() { const current = ++version; return () => current === version },
    invalidate() { version++ }
  }
}
