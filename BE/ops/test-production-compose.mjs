import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('../../', import.meta.url));
// Explicitly use the non-secret frontend example instead of BE/.env, then
// supply synthetic production settings. Never print the rendered environment.
const result = spawnSync('docker', ['compose', '--env-file', 'FE/.env.example',
  '-f', 'BE/compose.yaml', '-f', 'BE/compose.production.yaml', 'config', '--format', 'json'], {
  cwd: repoRoot, encoding: 'utf8', timeout: 30_000,
  env: {
    ...process.env,
    GOOGLE_API_KEY: 'ci-not-a-real-provider-key',
    DB_PASSWORD: 'ci-not-a-real-db-password',
    MYSQL_ROOT_PASSWORD: 'ci-not-a-real-root-password',
    APP_JWT_SECRET: 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=',
    APP_WORKER_API_KEY: 'ci-worker-secret-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
    APP_CORS_ALLOWED_ORIGINS: 'https://app.example.test',
    APP_WIDGET_ALLOWED_ORIGINS: 'https://widget.example.test',
    APP_WIDGET_CLIENT_KEY_SALT: 'ci-widget-salt-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
    APP_WIDGET_TRUSTED_PROXY_CIDRS: '172.30.0.10/32',
    APP_BOOTSTRAP_ADMIN_EMAIL: '', APP_BOOTSTRAP_ADMIN_PASSWORD: '',
    ALERTMANAGER_WEBHOOK_URL: 'https://alerts.example.test/webhook',
    BLUEAI_ORIGIN_BIND_ADDRESS: '',
  },
});
if (result.error || result.status !== 0) throw new Error(`Compose validation failed: ${result.error?.message ?? result.stderr}`);
const { services } = JSON.parse(result.stdout);
for (const [name, service] of Object.entries(services)) {
  if (name !== 'frontend') assert.equal(service.ports?.length ?? 0, 0, `${name} must not publish a host port`);
}
assert.equal(services.frontend.ports.length, 1);
assert.equal(services.frontend.ports[0].target, 80);
assert.equal(services.frontend.ports[0].published, '80');
assert.equal(services.frontend.ports[0].host_ip, '127.0.0.1');
for (const name of ['mysql', 'redis', 'rag-ai', 'auth-api', 'frontend']) {
  assert.equal(services[name].restart, 'unless-stopped', `${name} restart policy`);
  assert(services[name].healthcheck?.test?.length, `${name} healthcheck missing`);
  assert.notEqual(services[name].healthcheck.disable, true, `${name} healthcheck disabled`);
}
assert.equal(services.frontend.depends_on['auth-api'].condition, 'service_healthy');
assert.equal(services['auth-api'].depends_on.mysql.condition, 'service_healthy');
assert.equal(services['auth-api'].depends_on['rag-ai'].condition, 'service_healthy');
assert.equal(services['auth-api'].depends_on.redis.condition, 'service_healthy');
assert.equal(services['auth-api'].environment.SPRING_PROFILES_ACTIVE, 'prod');
assert.equal(services['auth-api'].environment.APP_RAG_WORKER_ORIGIN, 'http://rag-ai:8000');
assert.equal(services['auth-api'].environment.APP_PUBLIC_REGISTRATION_ENABLED, 'false');
assert.equal(services['auth-api'].environment.APP_RAG_ALLOW_SHARED_DEMO_KNOWLEDGE, 'false');
assert.equal(services['auth-api'].environment.APP_WIDGET_TRUST_FORWARDED_HEADERS, 'true');
assert(services['auth-api'].healthcheck.test.includes('http://127.0.0.1:8080/actuator/health'));
assert(services.frontend.healthcheck.test.includes('http://127.0.0.1/index.html'));
console.log('Production Compose PASS: private ports, production gates, restart/health and startup dependencies.');
