// Local acceptance: creates and removes its own test account, and makes a few real AI calls.
// Requires the local Docker stack and frontend Playwright dependencies.
import { createRequire } from 'node:module'
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomBytes, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { cookieFetch as fetch, installBrowserSession } from './cookie-browser.mjs'
const { chromium } = createRequire(resolve('frontend/package.json'))('@playwright/test')
const base='http://127.0.0.1:5173'
const username='ai_settings_smoke_'+randomBytes(6).toString('hex')
const password=randomBytes(24).toString('base64url')
const checks=[]
const configurationOnly=process.argv.includes('--configuration-only')
const expectedModel=process.env.MEALCHECK_VERIFY_AI_MODEL || 'qwen3.8-flash'
const outputDir=resolve('logs/deploy/ai-settings-live-'+new Date().toISOString().replace(/[:.]/g,'-'))
mkdirSync(outputDir,{recursive:false})
let token, original, savedVersion, browser, accountCleanedUp=false
function sql(query) {
  return execFileSync('docker',['exec','mealcheck-postgres','psql','-U','mealcheck','-d','mealcheck','-At','-v','ON_ERROR_STOP=1','-c',query],{encoding:'utf8'}).trim()
}
function check(name,condition) { if(!condition) throw new Error('Failed: '+name); checks.push(name); console.log('PASS '+name) }
async function http(path,{method='GET',body,form}={}) {
  const response=await fetch(base+path,{method,signal:AbortSignal.timeout(90000),headers:{
    ...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{}),
    ...(form?{'Idempotency-Key':form.key}:{})},body:form?form.body:body?JSON.stringify(body):undefined})
  const text=await response.text()
  if(!response.ok) throw new Error('HTTP '+response.status+' at '+path)
  return text?JSON.parse(text):null
}
try {
  const captcha=await http('/api/auth/captcha')
  const operands=captcha.question.match(/(\d+)\s*\+\s*(\d+)/)
  const auth=await http('/api/auth/register',{method:'POST',body:{username,password,displayName:'AI配置临时验收账户',
    captchaId:captcha.captchaId,captchaAnswer:String(Number(operands[1])+Number(operands[2]))}})
  token=auth.token
  // Only this randomly named account is promoted; existing accounts are never changed.
  const id=Number(sql(`UPDATE users SET role='ADMIN' WHERE username='${username}' RETURNING id`).split('\n')[0])
  check('disposable administrator created',Number.isSafeInteger(id)&&id>0)
  const cleanupArgument=process.argv.find(arg=>arg.startsWith('--cleanup-user='))
  if(cleanupArgument) {
    const previousUsername=cleanupArgument.slice('--cleanup-user='.length)
    if(!/^ai_settings_smoke_[a-f0-9]{12}$/.test(previousUsername)||previousUsername===username) throw new Error('Invalid previous smoke account')
    const previousId=Number(sql(`SELECT id FROM users WHERE username='${previousUsername}'`))
    if(Number.isSafeInteger(previousId)&&previousId>0) await http('/api/admin/users/'+previousId,{method:'DELETE'})
    check('previous temporary smoke account removed',sql(`SELECT count(*) FROM users WHERE username='${previousUsername}'`)==='0')
  }
  original=await http('/api/admin/ai-settings')
  check('running backend uses expected model',original.model===expectedModel)
  check('configuration never exposes API key',!Object.hasOwn(original,'apiKey'))
  if(!configurationOnly) {
  const saved=await http('/api/admin/ai-settings',{method:'PUT',body:{model:original.model,baseUrl:original.baseUrl,apiKey:'',version:original.version}})
  savedVersion=saved.version
  check('saved configuration retains the key without returning it',saved.source==='ADMIN'&&saved.apiKeyConfigured&&!Object.hasOwn(saved,'apiKey'))
  check('stored credential is encrypted',sql('SELECT length(api_key_ciphertext)>40 FROM ai_connection_settings WHERE id=1')==='t')
  const connection=await http('/api/admin/ai-settings/test',{method:'POST',body:{version:saved.version}})
  check('real saved-configuration connection test',connection.success&&connection.model===expectedModel)
  }
  const dashboard=await http('/api/admin/dashboard')
  check('dashboard reports the active model',dashboard.system.aiModel===expectedModel)

  browser=await chromium.launch({headless:true})
  const page=await browser.newPage({viewport:{width:1440,height:1100}})
  const errors=[]; page.on('pageerror',e=>errors.push(e.message))
  await installBrowserSession(page.context(), base, token)
  await page.goto(base+'/admin/monitor')
  await page.getByRole('button',{name:'AI 调用',exact:true}).click()
  const panel=page.getByRole('region',{name:'AI 模型配置'})
  await page.getByLabel('模型名称').waitFor()
  check('deployed configuration form and blank secret field',await panel.getByLabel('模型名称').inputValue()===expectedModel&&await panel.getByLabel('API Key').inputValue()==='')
  mkdirSync('logs/deploy',{recursive:true})
  await panel.screenshot({path:resolve(outputDir,'desktop.png')})
  await page.setViewportSize({width:390,height:844})
  await panel.screenshot({path:resolve(outputDir,'mobile.png')})
  check('mobile configuration fits viewport',await panel.evaluate(el=>el.getBoundingClientRect().right<=window.innerWidth+1))
  check('deployed AI monitor renders without browser errors',errors.length===0)
  await browser.close(); browser=null

  if(!configurationOnly) {
  const form=new FormData()
  form.set('image',new Blob([readFileSync('tools/datasets/synthetic-meals-100/images/synth-001.png')],{type:'image/png'}),'synthetic-acceptance.png')
  form.set('goal','balanced')
  const key=randomUUID()
  let task=await http('/api/meals/analysis-requests',{method:'POST',form:{body:form,key}})
  const deadline=Date.now()+90000
  while(['PENDING','PROCESSING'].includes(task.status)&&Date.now()<deadline) {
    await new Promise(done=>setTimeout(done,2000))
    task=await http('/api/meals/analysis-requests/'+key)
  }
  check('real asynchronous image recognition completes',task.status==='SUCCEEDED'&&task.result?.recognition?.foods?.length>0)
  const recordId=task.result.recordId
  check('meal stores the actual recognition model',Number.isSafeInteger(recordId)&&sql(`SELECT recognition_model FROM meal_records WHERE id=${recordId} AND user_id=${id}`)===expectedModel)
  const health=await http('/api/health')
  check('vision call status reports success',health.aiCalls.some(call=>call.operation==='vision-recognition'&&call.success))
  const assistant=await http('/api/assistant/ask',{method:'POST',body:{question:'怎样让午餐搭配更均衡？请简短回答。'}})
  check('real assistant reply completes',assistant.deliveryStatus==='COMPLETE'&&assistant.answer?.length>0)
  const after=await http('/api/health')
  check('assistant model call reports success',after.aiCalls.some(call=>call.operation.startsWith('diet-assistant')&&call.success))
  }
} finally {
  if(browser) await browser.close()
  if(token) {
    try {
      if(original?.source==='ENVIRONMENT' && savedVersion!==undefined) {
        const current=await http('/api/admin/ai-settings')
        if(current.version===savedVersion) await http('/api/admin/ai-settings/reset',{method:'POST',body:{version:current.version}})
      }
    } finally {
      sql(`UPDATE users SET role='USER' WHERE username='${username}'`)
      await http('/api/profile/account',{method:'DELETE',body:{password}})
      accountCleanedUp=sql(`SELECT count(*) FROM users WHERE username='${username}'`)==='0'
    }
  }
  mkdirSync('logs/deploy',{recursive:true})
  writeFileSync(resolve(outputDir,'summary.json'),JSON.stringify({checks,accountCleanedUp,configurationOnly,expectedModel,providerCallsRequested:configurationOnly?0:'LIVE_PIPELINE'},null,2))
  console.log('Evidence: '+outputDir)
  console.log('Temporary account cleaned up: '+accountCleanedUp)
}
