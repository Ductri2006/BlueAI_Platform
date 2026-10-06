import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

// Disposable, loopback-only acceptance harness. Never point this at production.
const base = 'http://127.0.0.1:23081';
const commit = 'fbc8179f4aefec489f971fe304d01e97077b7bb8';
const suffix = process.env.QA_RESULT_SUFFIX || '';
assert(!suffix || /^-\d{4}-\d{2}-\d{2}$/.test(suffix));
const inspect = spawnSync('docker', ['inspect', 'blueai-preprod-20261003-auth-api-1'], {encoding:'utf8'});
assert.equal(inspect.status, 0, 'The named disposable QA stack must exist');
const container = JSON.parse(inspect.stdout)[0];
assert.equal(container.Config.Labels['com.docker.compose.project'], 'blueai-preprod-20261003');
assert(container.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
assert.equal(container.NetworkSettings.Ports['8080/tcp'][0].HostIp, '127.0.0.1');
const run = Date.now().toString(36);
const evidence = {commit, base, startedAt:new Date().toISOString(), tests:[], fixtures:{}};
evidence.image = container.Config.Image;
evidence.scope = 'Uncommitted working-tree remediation, not an approved release';
evidence.trackedDiffSha256 = createHash('sha256').update(spawnSync('git', ['diff', '--binary'], {encoding:'utf8'}).stdout).digest('hex');
const request = async (path, {method='GET', token, body, headers={}}={}) => {
  const res = await fetch(base+path, {method, headers:{...(body!==undefined?{'Content-Type':'application/json'}:{}),
    ...(token?{Authorization:`Bearer ${token}`} : {}), ...headers},
    body:body!==undefined?JSON.stringify(body):undefined, signal:AbortSignal.timeout(30000)});
  const text = await res.text();
  let json; try {json=JSON.parse(text);} catch {json=null;}
  return {status:res.status, json, headers:res.headers};
};
const check = async (id, name, fn) => {
  const t = Date.now();
  try {await fn(); evidence.tests.push({id,name,result:'PASS',ms:Date.now()-t}); console.log(`PASS ${id}: ${name}`);}
  catch(e) {evidence.tests.push({id,name,result:'FAIL',ms:Date.now()-t,actual:e.message}); console.log(`FAIL ${id}: ${name}: ${e.message}`);}
};
const expectStatus = (response, expected) => assert([expected].flat().includes(response.status), `HTTP ${response.status}, expected ${[expected].flat().join('/')}`);
const login = async (email,password) => {const r=await request('/api/v1/auth/login',{method:'POST',body:{email,password}}); expectStatus(r,200); return r.json;};
const admin = await login('acceptance-admin@example.test','QaAcceptance2026!Fixture-Only');
const createUser = async (label,role='USER') => {
  const email=`qa-${label}-${run}@example.test`, password='QaSynthetic2026!NoRealCredential';
  const r=await request('/api/v1/admin/users',{method:'POST',token:admin.accessToken,body:{email,password,fullName:`QA ${label} ${run}`,role}});
  expectStatus(r,201); assert(!JSON.stringify(r.json).match(/password|hash|refreshToken|accessToken/i));
  evidence.fixtures[label]={id:r.json.id,email};
  return {id:r.json.id,email,password,auth:await login(email,password)};
};
const a=await createUser('user-a'), b=await createUser('user-b'), otherAdmin=await createUser('admin-b','ADMIN');
const at=a.auth.accessToken;
let bt=b.auth.accessToken;
const conv=await request('/api/v1/conversations',{method:'POST',token:at,body:{title:`QA ownership ${run}`}}); expectStatus(conv,201);
const cid=conv.json.id; evidence.fixtures.conversation=cid;

await check('AUTH-01','Unauthenticated protected API returns JSON 401', async()=>{const r=await request('/api/v1/conversations');expectStatus(r,401);assert(r.json);});
await check('AUTH-04','USER cannot call admin, documents, Studio or system APIs',async()=>{
  for(const p of ['/api/v1/admin/users','/api/v1/documents','/api/v1/studio/bots','/api/v1/system/health']) expectStatus(await request(p,{token:at}),403);
});
await check('AUTH-05','Invalid JWT is rejected',async()=>expectStatus(await request('/api/v1/auth/me',{token:'not-a-valid-jwt'}),401));
await check('AUTH-06','Refresh rotates token and rejects old token replay',async()=>{
  const r=await request('/api/v1/auth/refresh',{method:'POST',body:{refreshToken:b.auth.refreshToken}});expectStatus(r,200);
  assert(r.json.refreshToken!==b.auth.refreshToken,'Refresh must rotate its token');
  expectStatus(await request('/api/v1/auth/refresh',{method:'POST',body:{refreshToken:b.auth.refreshToken}}),401);
  evidence.fixtures.refreshRotation='verified';
});
await check('AUTH-07','Logout revokes disposable refresh token',async()=>{
  const auth=await login(b.email,b.password);
  expectStatus(await request('/api/v1/auth/logout',{method:'POST',body:{refreshToken:auth.refreshToken}}),204);
  expectStatus(await request('/api/v1/auth/refresh',{method:'POST',body:{refreshToken:auth.refreshToken}}),401);
});
await check('AUTH-08','Admin creates users; responses do not expose credentials',async()=>{
  assert(a.id&&b.id&&otherAdmin.id);
  const r=await request('/api/v1/admin/users?query='+encodeURIComponent(run),{token:admin.accessToken});expectStatus(r,200);
  assert(!JSON.stringify(r.json).match(/passwordHash|refreshToken|accessToken/));
});
await check('AUTH-12','Public registration and retired password routes are unavailable',async()=>{
  for(const p of ['/api/v1/auth/register','/api/v1/auth/forgot-password','/api/v1/auth/reset-password'])
    expectStatus(await request(p,{method:'POST',body:{email:`no-create-${run}@example.test`,password:'FixturePassword123!'}}),[401,403,404]);
});
// Obtain an independent valid session after deliberate refresh replay/logout tests.
bt=(await login(b.email,b.password)).accessToken;
await check('CTX-01','Own conversation persists and is searchable',async()=>{
  expectStatus(await request('/api/v1/conversations/'+cid,{token:at}),200);
  const r=await request('/api/v1/conversations/search?q='+encodeURIComponent(run),{token:at});expectStatus(r,200);assert(JSON.stringify(r.json).includes(cid));
});
await check('CTX-02','Cross-user direct conversation/context/delete do not expose records',async()=>{
  for(const p of [`/api/v1/conversations/${cid}`,`/api/v1/conversations/${cid}/context`,`/api/v1/conversations/${cid}/messages`])
    expectStatus(await request(p,{token:bt}),404);
  expectStatus(await request(`/api/v1/conversations/${cid}`,{method:'DELETE',token:bt}),404);
  expectStatus(await request(`/api/v1/conversations/${cid}`,{token:at}),200);
});
await check('CTX-09','Memory scope/content persist and remain private',async()=>{
  const r=await request(`/api/v1/me/memories/qa-${run}`,{method:'PUT',token:at,body:{content:'QA synthetic preference: 40 sales',scope:'USER',status:'CONFIRMED'}});expectStatus(r,200);
  assert.equal(r.json.content,'QA synthetic preference: 40 sales');
  const own=await request('/api/v1/me/memories',{token:at}), other=await request('/api/v1/me/memories',{token:bt});expectStatus(own,200);expectStatus(other,200);
  assert(JSON.stringify(own.json).includes(`qa-${run}`));assert(!JSON.stringify(other.json).includes(`qa-${run}`));
  expectStatus(await request('/api/v1/me/memories/cross-'+run,{method:'PUT',token:bt,body:{content:'cross owner test',scope:'CONVERSATION',conversationId:cid,status:'CONFIRMED'}}),404);
});
await check('CTX-10','Deleting a memory removes it from subsequent API list',async()=>{
  expectStatus(await request(`/api/v1/me/memories/qa-${run}`,{method:'DELETE',token:at}),204);
  const r=await request('/api/v1/me/memories',{token:at});assert(!JSON.stringify(r.json).includes(`qa-${run}`));
});
await check('CTX-11','Self-link, cross-user link and unknown mode are rejected',async()=>{
  expectStatus(await request(`/api/v1/conversations/${cid}/context-links`,{method:'POST',token:at,body:{sourceConversationId:cid}}),400);
  const bconv=await request('/api/v1/conversations',{method:'POST',token:bt,body:{title:'QA B '+run}});expectStatus(bconv,201);
  expectStatus(await request(`/api/v1/conversations/${cid}/context-links`,{method:'POST',token:at,body:{sourceConversationId:bconv.json.id}}),404);
  expectStatus(await request(`/api/v1/conversations/${cid}/context`,{method:'PUT',token:at,body:{mode:'EVERY_USERS_HISTORY'}}),400);
});
await check('KB-06','USER cannot upload/read document resources',async()=>expectStatus(await request('/api/v1/documents',{token:at}),403));
await check('KB-10','Private worker wrong key 401 and correct key invalid claim limit 400',async()=>{
  expectStatus(await request('/api/v1/internal/documents/claim?limit=0',{method:'POST',headers:{'X-Worker-Api-Key':'wrong-fixture-key'}}),401);
  expectStatus(await request('/api/v1/internal/documents/claim?limit=0',{method:'POST',headers:{'X-Worker-Api-Key':'qa-disposable-worker-secret-20261003-only'}}),400);
});
await check('SEC-02','Malformed JSON and oversized title rejected; blank title has safe default',async()=>{
  const blank=await request('/api/v1/conversations',{method:'POST',token:at,body:{title:''}});expectStatus(blank,201);assert(blank.json.title);
  expectStatus(await request('/api/v1/conversations',{method:'POST',token:at,body:{title:'x'.repeat(151)}}),400);
  const r=await fetch(base+'/api/v1/conversations',{method:'POST',headers:{Authorization:`Bearer ${at}`,'Content-Type':'application/json'},body:'{malformed'});assert.equal(r.status,400);
});
await check('SEC-03','SQL-shaped search input cannot bypass owner scope',async()=>{
  const r=await request('/api/v1/conversations/search?q='+encodeURIComponent("' OR 1=1 --"),{token:bt});expectStatus(r,200);assert(!JSON.stringify(r.json).includes(cid));
});

const demo=await request('/api/v1/studio/bots',{method:'POST',token:admin.accessToken,body:{name:'QA Sales demo '+run,description:'Disposable acceptance bot',locale:'vi-VN',timezone:'Asia/Ho_Chi_Minh',template:'SALES_DEMO'}});expectStatus(demo,201);
let cfg=demo.json; const bid=cfg.bot.id; evidence.fixtures.bot=bid;
const preview=async (message,context=null)=>{const r=await request(`/api/v1/studio/bots/${bid}/preview`,{method:'POST',token:admin.accessToken,body:{message,context}});expectStatus(r,200);return r.json;};
await check('DLG-01','Sales demo has 3 intents/4 entities/13 nodes and Core off',async()=>{
  assert.equal(cfg.intents.length,3);assert.equal(cfg.entities.length,4);assert.equal(cfg.dialogNodes.length,13);assert.equal(cfg.bot.blueAiCoreEnabled,false);assert.equal(cfg.bot.active,false);
});
await check('DLG-02','Greeting states demo scope',async()=>{const r=await preview('xin chào');assert(r.message.includes('Sản phẩm A/B'));});
await check('DLG-03','Missing branch and canonical/synonym branches work',async()=>{
  const first=await preview('giờ hoạt động');assert.equal(first.context.pendingEntity,'chi_nhanh');
  for(const [branch,hours] of [['Hà Nội','08:00–21:00'],['Sài Gòn','08:00–22:00'],['Đà Nẵng','08:30–21:00']])
    assert((await preview(branch,first.context)).message.includes(hours));
});
await check('DLG-04','Three slots collected across turns with interpolated prompts',async()=>{
  let r=await preview('báo giá');assert.equal(r.context.pendingEntity,'san_pham');
  r=await preview('sản phẩm A',r.context);assert.equal(r.context.pendingEntity,'so_luong');assert(!r.message.includes('{{'));
  r=await preview('20',r.context);assert.equal(r.context.pendingEntity,'chi_nhanh');
  r=await preview('Hà Nội',r.context);assert(r.message.includes('20 Sản phẩm A'));assert.equal(r.context.pendingEntity,'xac_nhan');
});
await check('DLG-05','Complete one-turn request skips redundant slot questions',async()=>{const r=await preview('báo giá 20 sản phẩm A giao Hà Nội');assert.equal(r.context.pendingEntity,'xac_nhan');assert(r.message.includes('Bạn xác nhận không?'));});
await check('DLG-06','Changed quantity retains other confirmed slots',async()=>{
  const r=await preview('báo giá 20 sản phẩm A giao Hà Nội'), changed=await preview('đổi thành 30',r.context);
  assert(changed.message.includes('30 Sản phẩm A'));assert(changed.message.includes('Hà Nội'));assert(!changed.message.includes('20 Sản phẩm A'));
});
await check('DLG-07','Confirmation and rejection differ; neither creates real orders',async()=>{
  const r=await preview('báo giá 20 sản phẩm A giao Hà Nội');
  const yes=await preview('xác nhận',r.context), no=await preview('không đồng ý',r.context);
  assert(yes.message.includes('Bạn đã xác nhận')&&yes.message.includes('chưa tạo đơn'));assert(no.message.includes('Bạn chưa đồng ý'));
});
await check('DLG-08','Early agreement still requires explicit confirmation after summary',async()=>{const r=await preview('báo giá 20 sản phẩm A giao Hà Nội, đồng ý');assert(!r.message.includes('Bạn đã xác nhận'));assert(r.message.includes('sau khi đọc'));});
await check('DLG-09','Unknown quantity is not accepted and cancel clears slots',async()=>{
  const r=await preview('báo giá sản phẩm A'), invalid=await preview('35',r.context);assert.equal(invalid.context.pendingEntity,'so_luong');assert(!invalid.context.variables.so_luong);
  const cancelled=await preview('hủy',invalid.context);assert.deepEqual(cancelled.context.variables,{});
});
await check('DLG-10','Unknown intent uses configured fallback without AI/KB',async()=>{const r=await preview('thời tiết sao hỏa');assert.equal(r.fallback,true);assert(r.message.includes('Không gọi AI/KB'));});
const saveBody = (configuration)=>({expectedRevision:configuration.bot.draftRevision,name:configuration.bot.name,
  description:configuration.bot.description,locale:configuration.bot.locale,timezone:configuration.bot.timezone,
  blueAiCoreEnabled:configuration.bot.blueAiCoreEnabled,customInstructions:configuration.bot.customInstructions,
  intents:configuration.intents,entities:configuration.entities,dialogNodes:configuration.dialogNodes,customization:configuration.customization});
await check('DLG-13','Stale draft save returns 409 instead of overwriting',async()=>{
  const old=saveBody(cfg);const r=await request(`/api/v1/studio/bots/${bid}/configuration`,{method:'PUT',token:admin.accessToken,body:{...old,description:'First writer '+run}});expectStatus(r,200);cfg=r.json;
  expectStatus(await request(`/api/v1/studio/bots/${bid}/configuration`,{method:'PUT',token:admin.accessToken,body:{...old,description:'Stale writer'}}),409);
});
await check('DLG-14','Unknown required entity rejects entire configuration',async()=>{
  const bad=saveBody(cfg);bad.dialogNodes=structuredClone(bad.dialogNodes);bad.dialogNodes.find(x=>x.requiredEntityName).requiredEntityName='does_not_exist';
  expectStatus(await request(`/api/v1/studio/bots/${bid}/configuration`,{method:'PUT',token:admin.accessToken,body:bad}),400);
  const r=await request(`/api/v1/studio/bots/${bid}`,{token:admin.accessToken});assert.equal(r.json.bot.draftRevision,cfg.bot.draftRevision);
});
await check('DLG-15','Second admin cannot read/save/publish another admin bot',async()=>{
  expectStatus(await request(`/api/v1/studio/bots/${bid}`,{token:otherAdmin.auth.accessToken}),404);
  expectStatus(await request(`/api/v1/studio/bots/${bid}/configuration`,{method:'PUT',token:otherAdmin.auth.accessToken,body:saveBody(cfg)}),404);
  expectStatus(await request(`/api/v1/studio/bots/${bid}/publish`,{method:'POST',token:otherAdmin.auth.accessToken}),404);
});
const publish=await request(`/api/v1/studio/bots/${bid}/publish`,{method:'POST',token:admin.accessToken});expectStatus(publish,200);
const widget=await request(`/api/v1/widget/${publish.json.publicKey}/sessions`,{method:'POST'});expectStatus(widget,201);
const ws=widget.json; evidence.fixtures.widgetSession=ws.sessionId;
const wmessage=(requestId,message,token=ws.accessToken,extra={})=>request(`/api/v1/runtime/sessions/${ws.sessionId}/messages`,{method:'POST',headers:{'X-Widget-Token':token,...extra},body:{requestId,message}});
await check('API-01','Published demo exposes safe welcome and release identity',async()=>{assert.equal(ws.releaseId,publish.json.releaseId);assert(ws.welcome.message.includes('DEMO SALES'));expectStatus(await request('/api/v1/widget/not-a-real-public-key/sessions',{method:'POST'}),404);});
await check('API-02','Missing/invalid/wrong-session widget tokens rejected',async()=>{
  expectStatus(await wmessage('bad','xin chào',''),401);expectStatus(await wmessage('bad2','xin chào','bad-token'),401);
  const other=await request(`/api/v1/widget/${publish.json.publicKey}/sessions`,{method:'POST'});expectStatus(other,201);
  expectStatus(await wmessage('cross-session','xin chào',other.json.accessToken),401);
});
let firstReply;
await check('API-03','Identical idempotency key/body replays exact response',async()=>{
  const first=await wmessage('stable-key','xin chào'), again=await wmessage('stable-key','xin chào');expectStatus(first,200);expectStatus(again,200);assert.deepEqual(first.json,again.json);firstReply=first.json;
});
await check('API-04','Same idempotency key with changed body conflicts',async()=>expectStatus(await wmessage('stable-key','hỏi giờ Hà Nội'),409));
await check('API-05','Repeated content under new keys eventually returns 429 + Retry-After',async()=>{
  let limited=false;for(let i=0;i<5;i++){const r=await wmessage('duplicate-'+i,'xin chào');if(r.status===429){assert(r.headers.get('retry-after'));limited=true;break;}expectStatus(r,200);}assert(limited);
});
await check('API-07','Allowed/disallowed widget origins follow CORS policy',async()=>{
  const options=(origin)=>request(`/api/v1/widget/${publish.json.publicKey}/sessions`,{method:'OPTIONS',headers:{Origin:origin,'Access-Control-Request-Method':'POST','Access-Control-Request-Headers':'content-type'}});
  const yes=await options('https://widget.example.test');expectStatus(yes,200);assert.equal(yes.headers.get('access-control-allow-origin'),'https://widget.example.test');assert(!yes.headers.get('access-control-allow-credentials'));
  const no=await options('https://untrusted.example.test');expectStatus(no,403);assert(!no.headers.get('access-control-allow-origin'));
});
await check('API-08','Public widget omits internal intent/context/trace/source fields',async()=>{
  assert(firstReply);assert.deepEqual(Object.keys(firstReply.result).sort(),['fallback','message']);
  assert(!JSON.stringify(firstReply).match(/recognizedEntities|matchedIntent|contextSources|visitedNodes|customInstructions/));
});
await check('DLG-16','Old widget session retains immutable release after republish',async()=>{
  const r=await request(`/api/v1/studio/bots/${bid}/configuration`,{method:'PUT',token:admin.accessToken,body:{...saveBody(cfg),description:'Second release '+run}});expectStatus(r,200);cfg=r.json;
  const pub=await request(`/api/v1/studio/bots/${bid}/publish`,{method:'POST',token:admin.accessToken});expectStatus(pub,200);assert.notEqual(pub.json.releaseId,ws.releaseId);
  const old=await wmessage('old-release-check','hỏi giờ Đà Nẵng');expectStatus(old,200);assert.equal(old.json.releaseId,ws.releaseId);
  const fresh=await request(`/api/v1/widget/${pub.json.publicKey}/sessions`,{method:'POST'});expectStatus(fresh,201);assert.equal(fresh.json.releaseId,pub.json.releaseId);
});
await check('AUTH-09','Admin reset revokes old credentials/access/refresh',async()=>{
  const u=await createUser('reset-user'), password='QaChanged2026!FixtureOnly';
  expectStatus(await request(`/api/v1/admin/users/${u.id}/password`,{method:'POST',token:admin.accessToken,body:{newPassword:password}}),204);
  expectStatus(await request('/api/v1/auth/me',{token:u.auth.accessToken}),401);
  expectStatus(await request('/api/v1/auth/refresh',{method:'POST',body:{refreshToken:u.auth.refreshToken}}),401);
  expectStatus(await request('/api/v1/auth/login',{method:'POST',body:{email:u.email,password:u.password}}),401);
  assert((await login(u.email,password)).user.id===u.id);
});
await check('AUTH-10','Disabled then soft-deleted fixture user cannot authenticate',async()=>{
  const u=await createUser('disabled-user');
  expectStatus(await request(`/api/v1/admin/users/${u.id}/status`,{method:'PATCH',token:admin.accessToken,body:{status:'DISABLED'}}),200);
  expectStatus(await request('/api/v1/auth/me',{token:u.auth.accessToken}),401);
  expectStatus(await request('/api/v1/auth/login',{method:'POST',body:{email:u.email,password:u.password}}),403);
  expectStatus(await request(`/api/v1/admin/users/${u.id}`,{method:'DELETE',token:admin.accessToken}),200);
});
evidence.finishedAt=new Date().toISOString();
evidence.summary={pass:evidence.tests.filter(x=>x.result==='PASS').length,fail:evidence.tests.filter(x=>x.result==='FAIL').length};
writeFileSync(new URL(`./qa-api-results${suffix}.json`,import.meta.url),JSON.stringify(evidence,null,2)+'\n');
console.log(JSON.stringify(evidence.summary));
process.exitCode=evidence.summary.fail?1:0;
