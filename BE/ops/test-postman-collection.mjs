import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { Script } from 'node:vm';

const directory = new URL('../postman/', import.meta.url);
const collection = JSON.parse(readFileSync(new URL('BlueAI.postman_collection.json', directory)));
const environment = JSON.parse(readFileSync(new URL('BlueAI.local.postman_environment.json', directory)));
const declared = new Set(environment.values.map(variable => variable.key));
const requests = [];
let compiledScripts = 0;

function inspect(item) {
  for (const event of item.event ?? []) {
    new Script(event.script.exec.join('\n'), { filename: item.name + ':' + event.listen });
    compiledScripts++;
  }
  if (item.request) {
    requests.push(item);
    assert.ok(item.event?.some(event => event.listen === 'test'), item.name + ' needs response assertions');
  }
  for (const child of item.item ?? []) inspect(child);
}
inspect(collection);
for (const [, variable] of JSON.stringify(collection).matchAll(/{{([^{}]+)}}/g)) {
  assert.ok(variable.startsWith('$') || declared.has(variable), 'Undeclared variable: ' + variable);
}
for (const variable of environment.values.filter(variable => variable.type === 'secret')) {
  assert.equal(variable.value, '', 'Exported credential must be blank: ' + variable.key);
}
function find(method, path) {
  const request = requests.find(item => item.request.method === method && item.request.url.raw === '{{baseUrl}}' + path);
  assert.ok(request, 'Missing request: ' + method + ' ' + path);
  return request;
}
const reset = find('POST', '/api/v1/admin/users/{{userId}}/password');
assert.deepEqual(JSON.parse(reset.request.body.raw), { currentPassword: '{{password}}' });
const resetTests = reset.event.find(event => event.listen === 'test').script.exec.join('\n');
assert.match(resetTests, /response\.code\)\.to\.eql\(200\)/);
assert.match(resetTests, /environment\.set\("temporaryPassword"/);
assert.match(resetTests, /no-store/);
find('GET', '/api/v1/auth/quota');
find('GET', '/api/v1/admin/quotas');
find('POST', '/api/v1/auth/browser/password/change');
for (const request of requests.filter(item => /test USER|personal password|restricted session|Temporary password/.test(item.name))) {
  for (const event of request.event ?? []) {
    assert.doesNotMatch(event.script.exec.join('\n'), /environment\.set\("(?:accessToken|refreshToken)"/,
      'Recovery workflow must keep the administrator session: ' + request.name);
  }
}
const changes = requests.filter(item => item.request.method === 'POST' && item.request.url.raw === '{{baseUrl}}/api/v1/auth/password/change');
assert.ok(changes.some(item => !JSON.parse(item.request.body.raw).currentPassword), 'Forced password change is covered');
assert.ok(changes.some(item => JSON.parse(item.request.body.raw).currentPassword), 'Normal password change is covered');
console.log(JSON.stringify({ requests: requests.length, compiledScripts, passwordRecoveryContract: 'passed', credentialsBlank: true }));
