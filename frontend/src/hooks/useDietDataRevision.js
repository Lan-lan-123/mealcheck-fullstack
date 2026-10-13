import { useEffect, useState } from 'react'
import { subscribeDietChanges } from '../utils/dietDataChanges'

export default function useDietDataRevision(kinds) {
  const [revision, setRevision] = useState(0)
  useEffect(() => subscribeDietChanges(kind => { if (kinds.includes(kind)) setRevision(value => value + 1) }), [kinds])
  return revision
}
