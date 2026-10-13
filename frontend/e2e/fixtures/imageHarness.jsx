import React from 'react'
import { createRoot } from 'react-dom/client'
import AuthImage from '../../src/components/AuthImage'

const element = document.createElement('div')
element.id = 'image-test'
document.body.append(element)
const root = createRoot(element)
export function render(url) { root.render(<AuthImage url={url} alt="测试餐食图片" />) }
export function dispose() { root.unmount() }
