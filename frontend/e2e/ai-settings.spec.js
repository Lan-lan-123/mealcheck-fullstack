import { test, expect } from './fixtures/authTest.js'

test('administrator changes AI connection, tests saved values and restores deployment defaults', async ({ page }) => {
  const errors=[]
  page.on('pageerror', error=>errors.push(error.message))
  let settings={model:'qwen3.8-max',baseUrl:'https://dashscope.aliyuncs.com/compatible-mode/v1',apiKeyConfigured:true,source:'ENVIRONMENT',version:0}
  let tested=false
  await page.addInitScript(()=>{
    localStorage.setItem('mealcheck_token','settings-admin-test')
    sessionStorage.setItem('mealcheck_fixture_user',JSON.stringify({username:'admin',role:'ADMIN'}))
  })
  await page.route('**/api/**',route=>{
    const request=route.request(), path=new URL(request.url()).pathname
    if(!path.startsWith('/api/')) return route.continue()
    const send=data=>route.fulfill({contentType:'application/json',body:JSON.stringify(data)})
    if(path==='/api/admin/ai-settings' && request.method()==='PUT') {
      expect(request.headers()['content-type']).toBe('application/json')
      const body=request.postDataJSON()
      expect(body.apiKey).toBe('')
      expect(body.version).toBe(settings.version)
      settings={...settings,model:body.model,baseUrl:body.baseUrl,source:'ADMIN',version:settings.version+1}
      return send(settings)
    }
    if(path==='/api/admin/ai-settings/test') {
      tested=true
      expect(request.postDataJSON()).toEqual({version:settings.version})
      return send({success:true,model:settings.model,message:'连接成功'})
    }
    if(path==='/api/admin/ai-settings/reset') {
      settings={...settings,model:'qwen3.8-max',source:'ENVIRONMENT',version:settings.version+1}
      return send(settings)
    }
    if(path==='/api/admin/ai-settings') return send(settings)
    return send({})
  })
  await page.goto('/admin/monitor')
  await page.getByRole('button',{name:'AI 调用',exact:true}).click()
  const panel=page.getByRole('region',{name:'AI 模型配置'})
  await expect(panel.getByLabel('API Key')).toHaveValue('')
  await expect(panel.getByLabel('API Key')).toHaveAttribute('type','password')
  await panel.getByLabel('模型名称').fill('qwen3.7-flash')
  await expect(panel.getByRole('button',{name:'测试已保存配置'})).toBeDisabled()
  await panel.getByRole('button',{name:'保存并生效'}).click()
  await expect(panel).toContainText('当前生效模型：qwen3.7-flash')
  await expect(panel.getByLabel('API Key')).toHaveValue('')
  await panel.getByRole('button',{name:'测试已保存配置'}).click()
  await expect(panel.getByRole('status')).toContainText('连接成功')
  expect(tested).toBe(true)
  await page.reload()
  await page.getByRole('button',{name:'AI 调用',exact:true}).click()
  await expect(panel.getByLabel('模型名称')).toHaveValue('qwen3.7-flash')
  await panel.getByRole('button',{name:'恢复部署配置'}).click()
  await page.getByRole('dialog').getByRole('button',{name:'确认',exact:true}).click()
  await expect(panel.getByLabel('模型名称')).toHaveValue('qwen3.8-max')
  expect(errors).toEqual([])
})

test('configuration conflicts keep edits available and expose a refresh action',async({page})=>{
  await page.addInitScript(()=>{
    localStorage.setItem('mealcheck_token','settings-admin-test')
    sessionStorage.setItem('mealcheck_fixture_user',JSON.stringify({username:'admin',role:'ADMIN'}))
  })
  await page.route('**/api/**',route=>{
    const path=new URL(route.request().url()).pathname
    if(!path.startsWith('/api/')) return route.continue()
    if(path==='/api/admin/ai-settings' && route.request().method()==='PUT') return route.fulfill({status:409,
      contentType:'application/json',body:JSON.stringify({message:'配置已被修改，请刷新后重试'})})
    const data=path==='/api/admin/ai-settings'?{model:'old-model',baseUrl:'https://provider.test/v1',apiKeyConfigured:true,source:'ADMIN',version:1}:{}
    return route.fulfill({contentType:'application/json',body:JSON.stringify(data)})
  })
  await page.goto('/admin/monitor')
  await page.getByRole('button',{name:'AI 调用',exact:true}).click()
  const panel=page.getByRole('region',{name:'AI 模型配置'})
  await panel.getByLabel('模型名称').fill('new-model')
  await panel.getByRole('button',{name:'保存并生效'}).click()
  await expect(panel.getByRole('alert')).toContainText('请刷新后重试')
  await expect(panel.getByLabel('模型名称')).toHaveValue('new-model')
  await panel.getByRole('button',{name:'刷新配置',exact:true}).click()
  await expect(panel.getByLabel('模型名称')).toHaveValue('old-model')
})
