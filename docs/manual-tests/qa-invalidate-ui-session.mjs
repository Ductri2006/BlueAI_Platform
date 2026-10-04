import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
const project = 'blueai-preprod-20261003';
const inspected = spawnSync('docker', ['inspect', `${project}-auth-api-1`], { encoding: 'utf8' });
assert.equal(inspected.status, 0);
const container = JSON.parse(inspected.stdout)[0];
assert.equal(container.Config.Labels['com.docker.compose.project'], project);
assert(container.Config.Env.includes('SPRING_PROFILES_ACTIVE=qa'));
const user = JSON.parse(readFileSync(new URL('./qa-remediation-api-results-2026-10-04.json', import.meta.url))).fixtures.uiExpiryUser;
assert(user.email.startsWith('qa-ui-expiry-') && user.email.endsWith('@example.test'));
const login = await fetch('http://127.0.0.1:23081/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: 'acceptance-admin@example.test', password: 'QaAcceptance2026!Fixture-Only' }) });
assert.equal(login.status, 200);
const admin = await login.json();
const state = process.argv[2] === 'enable' ? 'ACTIVE' : 'DISABLED';
const changed = await fetch(`http://127.0.0.1:23081/api/v1/admin/users/${user.id}/status`, {
  method: 'PATCH', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${admin.accessToken}` }, body: JSON.stringify({ status: state }),
});
assert.equal(changed.status, 200);
const response = await changed.json();
assert.equal(response.id, user.id); assert.equal(response.status, state);
console.log(`Only named disposable UI QA user changed to ${state}; production users untouched.`);
