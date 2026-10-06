import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = dirname(fileURLToPath(import.meta.url)), root = resolve(directory, '../..');
const read = name => JSON.parse(readFileSync(resolve(directory, name), 'utf8'));
const write = (name, value) => writeFileSync(resolve(directory, name), JSON.stringify(value, null, 2) + '\n');
const project = 'blueai-preprod-20261003';
const counts = { suites: 0, tests: 0, skipped: 0, failures: 0, errors: 0 };
for (const name of readdirSync(resolve(root, 'BE/target/surefire-reports')).filter(name => /^TEST-.*\.xml$/.test(name))) {
  const header = readFileSync(resolve(root, 'BE/target/surefire-reports', name), 'utf8').match(/<testsuite\s[^>]*>/)[0];
  for (const key of ['tests', 'skipped', 'failures', 'errors']) counts[key] += Number(header.match(new RegExp(`\\b${key}="(\\d+)"`))[1]);
  counts.suites++;
}
assert.deepEqual(counts, { suites: 56, tests: 402, skipped: 30, failures: 0, errors: 0 });
counts.executed = counts.tests - counts.skipped;
const images = {};
for (const service of ['auth-api', 'rag-ai', 'frontend', 'mysql', 'redis']) {
  const inspected = spawnSync('docker', ['inspect', `${project}-${service}-1`], { encoding: 'utf8' });
  assert.equal(inspected.status, 0);
  const container = JSON.parse(inspected.stdout)[0];
  assert.equal(container.Config.Labels['com.docker.compose.project'], project);
  assert.equal(container.State.Health.Status, 'healthy');
  // Never serialize Config.Env or log output.
  images[service] = { tag: container.Config.Image, id: container.Image, health: 'healthy' };
}
const secrets = read('qa-image-secrets-followup-2026-10-04.json');
for (const service of ['auth-api', 'rag-ai', 'frontend']) {
  assert(secrets.images.some(item => item.id === images[service].id && item.result === 'PASS'));
}
const api = read('qa-followup-api-results-final-2026-10-04.json');
assert.equal(api.summary.fail, 0); assert.equal(api.summary.pass, 6);
assert(read('qa-ui-followup-results-2026-10-04.json').tests.every(item => item.result === 'PASS'));
const npm = {};
for (const [scope, flags] of [['all', []], ['runtime', ['--omit=dev']]]) {
  const audit = spawnSync(process.execPath, ['D:/node_modules/npm/bin/npm-cli.js', 'audit', '--json', ...flags],
    { cwd: resolve(root, 'FE'), encoding: 'utf8', timeout: 60000 });
  assert(!audit.error, 'Npm audit unavailable');
  const parsed = JSON.parse(audit.stdout);
  assert(parsed.metadata?.vulnerabilities);
  write(`npm-audit-${scope}-followup-2026-10-04.json`, parsed);
  npm[scope] = { exitCode: audit.status, counts: parsed.metadata.vulnerabilities };
}
const tracked = spawnSync('git', ['ls-files', '--cached', '--others', '--exclude-standard'], { cwd: root, encoding: 'utf8' });
assert.equal(tracked.status, 0);
const sources = [...new Set(tracked.stdout.split(/\r?\n/).filter(path =>
  /^(BE\/(src\/|pom\.xml$|Dockerfile$)|FE\/(src\/|public\/|package(?:-lock)?\.json$|(?:vite|tailwind|postcss)\.config\.|tsconfig|index\.html$|Dockerfile$|nginx\.conf$|security-headers\.conf$)|feature-rag-chatbot\/rag-chatbot\/(api\.py$|chatbot\.py$|ingestion\.py$|requirements\.txt$|Dockerfile$))/.test(path)))].sort();
write('qa-verification-summary-followup-2026-10-04.json', {
  generatedAt: new Date().toISOString(), scope: 'Uncommitted local QA; not an approved production release',
  images, backend: counts, frontend: { passed: 123, build: 'PASS', source: 'Observed Vitest and TypeScript/Vite runs' },
  rag: { passed: 70, source: 'Observed unittest run on the rebuilt runtime with tests/scripts mounted; network disabled' },
  separateLegacyMySql: { executed: 2, passed: 2, versions: [1, 3], target: 9,
    source: 'Observed qa-followup-mysql.ps1 run; empty disposable MySQL 8.4 databases, no baseline/repair',
    countNote: 'Do not sum as unique backend tests; this conditional class is skipped without environment variables in full verify.' },
  api, ui: read('qa-ui-followup-results-2026-10-04.json'), secrets,
  vulnerabilities: read('qa-image-vulnerabilities-followup-2026-10-04.json'), npm,
  buildInputHashes: sources.map(path => ({ path, sha256: createHash('sha256').update(readFileSync(resolve(root, path))).digest('hex') })),
  tailwind: 'Keep v3 per user decision', mainApplication: 'Not rebuilt/restarted/mutated', commitOrPush: 'Not performed',
});
const ledger = read('qa-manual-ledger-results-2026-10-04.json');
const upgrades = {
  'REL-02': ['3 Dockerfile builds succeeded; exact running API/RAG/FE image IDs scanned for secrets with zero candidates.', 'qa-image-secrets-followup-2026-10-04.json'],
  'KB-07': ['28 upload integration executions include empty/type/path/spoof/binary/ZIP bounds; real ingress fake PDF/DOCX/TXT returns 400 before DB/ingest.', 'qa-followup-api-results-final-2026-10-04.json'],
  'KB-08': ['Real ingress accepts 2/20 MiB, rejects 21 MiB with 413; Java actual-byte bounds and browser Vietnamese 20 MiB toast verified, client no-network regression passes.', 'qa-ui-followup-results-2026-10-04.json'],
  'KB-10': ['8 worker integration tests include expired/new attempt 409, deleted-document result 409 and bounded retry; private key/claim validation evidence retained.', 'BE/target/surefire-reports/TEST-com.blueai.rag.document.controller.WorkerDocumentIntegrationTest.xml'],
  'KB-11': ['3 failed attempts end FAILED, stale READY cannot overwrite; RAG tests cover empty extraction, provider exception redaction, timeout/HTTP status and 409 continuation. Representative deterministic regression, not live paid-provider chaos.', 'feature-rag-chatbot/rag-chatbot/tests/test_ingestion_security.py'],
  'SEC-01': ['Prior title evidence plus real browser user message/node/assistant preview render literal HTML; zero injected marker image/script DOM nodes. 12 SSR payload regression executions also pass.', 'qa-ui-followup-results-2026-10-04.json'],
  'SEC-03': ['Conversation integration tests verify four SQL-shaped title/search inputs, literal wildcard escaping, owner isolation, intact users and server-generated identity. No claim about arbitrary future endpoints.', 'BE/target/surefire-reports/TEST-com.blueai.rag.conversation.controller.ConversationIntegrationTest.xml'],
  'OPS-01': ['Verified legacy V1/V3 fixtures migrate directly to V9 retaining data/checksums, validate and rerun no-op; combine with earlier fresh/V5/V6/old-engine evidence, not rerun claims.', 'qa-verification-summary-followup-2026-10-04.json'],
};
ledger.previousCounts = ledger.counts;
for (const item of ledger.cases) {
  if (upgrades[item.id]) {
    item.previousResult = item.result; item.result = 'PASS';
    [item.actual, item.provenance] = upgrades[item.id]; item.evidenceDate = '2026-10-04';
    delete item.note;
  }
  if (item.id === 'API-06') {
    item.actual += ' Follow-up Redis outage returns 503 for new work, completed replay remains 200; recovery in 9742 ms and session quota 429 verified. Full quota/IP variants remain PARTIAL.';
    item.provenance = 'qa-followup-api-results-final-2026-10-04.json';
  }
}
ledger.counts = {};
for (const item of ledger.cases) ledger.counts[item.result] = (ledger.counts[item.result] || 0) + 1;
ledger.counts.BLOCKED ??= 0;
assert.equal(ledger.cases.length, 90);
ledger.generatedAt = new Date().toISOString(); ledger.decision = 'NO-GO for production; ready for manual QA review';
ledger.evidenceNote = 'Cumulative 90 original criteria. Carry-forward results are not new UI runs. Initial failed Redis drill and retest retained; final corrected drill PASS.';
write('qa-manual-ledger-followup-2026-10-04.json', ledger);
console.log(JSON.stringify({ backend: counts, ledger: ledger.counts, npm }));
