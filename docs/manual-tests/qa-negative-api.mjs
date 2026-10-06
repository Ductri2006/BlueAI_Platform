import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
const base='http://127.0.0.1:23081', frontend='http://127.0.0.1:23000';
const suffix = process.env.QA_RESULT_SUFFIX || '';
assert(!suffix || /^-\d{4}-\d{2}-\d{2}$/.test(suffix));
const run=Date.now().toString(36), result={startedAt:new Date().toISOString(),tests:[],fixtures:{}};
const inspect=spawnSync('docker',['inspect','blueai-preprod-20261003-auth-api-1'],{encoding:'utf8'});
assert.equal(inspect.status,0);
const qa=JSON.parse(inspect.stdout)[0];assert.equal(qa.Config.Labels['com.docker.compose.project'],'blueai-preprod-20261003');assert(qa.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
const req=async(path,token,method='GET',body)=>{
  const res=await fetch(base+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} :{}),...(body!==undefined?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});
  const text=await res.text();let json;try{json=JSON.parse(text);}catch{json=null;}return {status:res.status,json};
};
const check=async(id,name,fn)=>{try{await fn();result.tests.push({id,name,result:'PASS'});console.log('PASS '+id+': '+name);}catch(e){result.tests.push({id,name,result:'FAIL',actual:e.message});console.log('FAIL '+id+': '+e.message);}};
const status=(r,expected)=>assert.equal(r.status,expected,'Unexpected HTTP status');
const admin=await req('/api/v1/auth/login',null,'POST',{email:'acceptance-admin@example.test',password:'QaAcceptance2026!Fixture-Only'});status(admin,200);const token=admin.json.accessToken;
const users=JSON.parse(readFileSync(new URL(`./qa-api-results${suffix}.json`,import.meta.url)));
const b=await req('/api/v1/auth/login',null,'POST',{email:users.fixtures['user-b'].email,password:'QaSynthetic2026!NoRealCredential'});status(b,200);
const upload=async(filename,content,fields={})=>{
  const form=new FormData();form.append('file',new Blob([content]),filename);
  for(const [k,v]of Object.entries(fields))form.append(k,String(v));
  const res=await fetch(base+'/api/v1/documents',{method:'POST',headers:{Authorization:`Bearer ${token}`},body:form});return {status:res.status,json:await res.json()};
};
await check('KB-07-negative','Empty, executable and traversal filenames rejected before ingest',async()=>{
  for(const [name,body]of [['empty.txt',''],['fake.exe','QA fake executable text'],['../../outside.txt','QA traversal text']])status(await upload(name,body),400);
});
await check('KB-09','Invalid chunk sizes and overlaps rejected before creating jobs',async()=>{
  for(const fields of [{chunkSize:-1},{chunkSize:4001},{chunkSize:200,chunkOverlap:-1},{chunkSize:200,chunkOverlap:200}])status(await upload('qa-invalid.txt','QA harmless text',fields),400);
});
await check('SEC-02-owner','Client-supplied owner/role cannot assign a conversation to another user',async()=>{
  const r=await req('/api/v1/conversations',token,'POST',{title:'QA tamper '+run,ownerId:users.fixtures['user-b'].id,role:'ADMIN',status:'DELETED'});status(r,201);
  status(await req('/api/v1/conversations/'+r.json.id,token),200);status(await req('/api/v1/conversations/'+r.json.id,b.json.accessToken),404);
});
await check('API-09','Limited client rejects ungranted capability and unknown fields',async()=>{
  const provision=await req('/api/v1/admin/integration-clients',token,'POST',{name:'QA limited '+run,capabilities:['CLASSIFY'],documentIds:[]});status(provision,201);
  const credential=provision.json.credential;result.fixtures.integrationClientId=provision.json.id;
  const submit=async(body,key)=>{const r=await fetch(base+'/api/v1/ai/tasks',{method:'POST',headers:{Authorization:`Bearer ${credential}`,'Content-Type':'application/json','Idempotency-Key':key},body:JSON.stringify(body)});return {status:r.status,json:await r.json()};};
  status(await submit({capability:'EXTRACT',input:{text:'QA harmless'},configuration:{fields:[{name:'company',type:'STRING',required:false}]}},'ungranted:'+run),403);
  status(await submit({capability:'CLASSIFY',input:{text:'QA harmless'},configuration:{allowedLabels:['sales']},ownerId:users.fixtures['user-b'].id},'tamper:'+run),400);
  const list=await req('/api/v1/admin/integration-clients',token);status(list,200);assert(!JSON.stringify(list.json).includes(credential));
  status(await req('/api/v1/admin/integration-clients/'+provision.json.id,token,'DELETE'),204);
  status(await submit({capability:'CLASSIFY',input:{text:'QA harmless'},configuration:{allowedLabels:['sales']}},'revoked:'+run),401);
});
await check('API-06-local','Shared IP quota across sessions cannot be bypassed by spoofed XFF',async()=>{
  const bot=await req('/api/v1/studio/bots/'+users.fixtures.bot,token);status(bot,200);assert.equal(bot.json.bot.blueAiCoreEnabled,false);
  const sessions=[];
  for(let i=0;i<3;i++){const r=await req('/api/v1/widget/'+bot.json.bot.publicKey+'/sessions',null,'POST');status(r,201);sessions.push(r.json);}
  let limited=false;
  for(let i=0;i<33;i++){
    const s=sessions[Math.floor(i/11)];
    const r=await fetch(base+'/api/v1/runtime/sessions/'+s.sessionId+'/messages',{method:'POST',headers:{'Content-Type':'application/json','X-Widget-Token':s.accessToken,'X-Forwarded-For':`198.51.100.${i+1}`},body:JSON.stringify({requestId:`quota:${run}:${i}`,message:'hỏi giờ Hà Nội QA-'+i})});
    if(r.status===429){assert(r.headers.get('retry-after'));limited=true;result.fixtures.firstIpLimitRequest=i+1;break;}assert.equal(r.status,200);
  }
  assert(limited,'Client quota must apply even when each request claims a different XFF');
});
await check('REL-04','RAG liveness/startup/worker gates respond independently',async()=>{
  for(const path of ['/health','/ready','/readiness']){const r=await fetch('http://127.0.0.1:23800'+path);assert.equal(r.status,200,path);const body=await r.json();result.fixtures[path]={status:body.status,indexPresent:body.indexPresent,documentWorker:typeof body.documentWorker==='string'?body.documentWorker:undefined};}
});
await check('OPS-07-private','Public frontend blocks internal routes and actuator',async()=>{
  for(const p of ['/actuator/health','/actuator/prometheus','/api/v1/internal/documents/claim']){const r=await fetch(frontend+p,{method:p.includes('/claim')?'POST':'GET'});assert.equal(r.status,404,p);}
});
await check('SEC-06-headers','Frontend HTML has CSP/nosniff/frame/referrer headers',async()=>{
  const r=await fetch(frontend);result.fixtures.htmlHeaders=Object.fromEntries(['content-security-policy','x-content-type-options','x-frame-options','referrer-policy'].map(k=>[k,r.headers.get(k)]));
  assert(r.headers.get('content-security-policy'),'Missing Content-Security-Policy');
  assert.equal(r.headers.get('x-content-type-options'),'nosniff');assert(r.headers.get('x-frame-options'));assert(r.headers.get('referrer-policy'));
});
const xssTitle='<img src=x onerror=alert("QA-XSS-20261003")>';
const xss=await req('/api/v1/conversations',token,'POST',{title:xssTitle});status(xss,201);result.fixtures.xssConversationId=xss.json.id;result.fixtures.xssTitle=xssTitle;
result.finishedAt=new Date().toISOString();result.summary={pass:result.tests.filter(x=>x.result==='PASS').length,fail:result.tests.filter(x=>x.result==='FAIL').length};
writeFileSync(new URL(`./qa-negative-results${suffix}.json`,import.meta.url),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result.summary));process.exitCode=result.summary.fail?1:0;
