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
const docker = args => {
  const result = spawnSync('docker', args, { encoding: 'utf8' });
  assert.equal(result.status, 0, 'QA metadata lookup failed'); return result.stdout;
};
const images = {};
for (const service of ['auth-api', 'rag-ai', 'frontend', 'mysql', 'redis']) {
  const container = JSON.parse(docker(['inspect', `${project}-${service}-1`]))[0];
  assert.equal(container.Config.Labels['com.docker.compose.project'], project);
  assert.equal(container.State.Health.Status, 'healthy');
  // Deliberately retain no Config.Env, credentials or log contents.
  images[service] = { tag: container.Config.Image, imageId: container.Image, health: container.State.Health.Status };
}
const counts = { tests: 0, skipped: 0, failures: 0, errors: 0, suites: 0 };
for (const name of readdirSync(resolve(root, 'BE/target/surefire-reports')).filter(name => /^TEST-.*\.xml$/.test(name))) {
  const xml = readFileSync(resolve(root, 'BE/target/surefire-reports', name), 'utf8');
  const header = xml.match(/<testsuite\s[^>]*>/)[0];
  for (const key of ['tests', 'skipped', 'failures', 'errors']) counts[key] += Number(header.match(new RegExp(`\\b${key}="(\\d+)"`))[1]);
  counts.suites++;
}
assert.deepEqual(counts, { tests: 367, skipped: 29, failures: 0, errors: 0, suites: 55 });
counts.executed = counts.tests - counts.skipped;
for (const name of ['qa-api-results-2026-10-04.json', 'qa-negative-results-2026-10-04.json', 'qa-session-edge-results-2026-10-04.json', 'qa-dialog-validation-results-2026-10-04.json']) {
  assert(read(name).tests.every(test => test.result === 'PASS'), name + ' has a failed check');
}
assert.equal(read('qa-context-retest-results-2026-10-04.json').after.result, 'PASS');
assert.equal(read('qa-kb-delete-results-2026-10-04.json').after.result, 'PASS');
assert.equal(read('qa-real-backup-results-2026-10-04.json').result, 'PASS');
assert.equal(read('qa-conversation-pagination-results-2026-10-04.json').result, 'PASS');

const scan = name => {
  const report = read(name), vulnerabilities = (report.Results ?? []).flatMap(result =>
    (result.Vulnerabilities ?? []).map(finding => ({ ...finding, class: result.Class })));
  return { artifact: report.ArtifactName,
    high: vulnerabilities.filter(value => value.Severity === 'HIGH').length,
    critical: vulnerabilities.filter(value => value.Severity === 'CRITICAL').length,
    applicationHigh: vulnerabilities.filter(value => value.class === 'lang-pkgs' && value.Severity === 'HIGH').length,
    highWithFixAvailable: vulnerabilities.filter(value => value.Severity === 'HIGH' && value.FixedVersion).length };
};
const scans = { api: scan('trivy-api-final-2026-10-04.json'), frontend: scan('trivy-frontend-final-2026-10-04.json'), rag: scan('trivy-rag-final-2026-10-04.json') };
assert.equal(scans.api.high + scans.api.critical + scans.frontend.high + scans.frontend.critical, 0);
assert.equal(scans.rag.applicationHigh + scans.rag.critical, 0);
const npmAudit = productionOnly => {
  const result = spawnSync('D:/node.exe', ['D:/node_modules/npm/bin/npm-cli.js', 'audit', '--json', ...(productionOnly ? ['--omit=dev'] : [])], {
    cwd: resolve(root, 'FE'), encoding: 'utf8', timeout: 60000 });
  assert(!result.error, 'npm audit did not complete');
  const report = JSON.parse(result.stdout);
  assert(report.metadata, 'npm audit service unavailable');
  write(productionOnly ? 'npm-audit-runtime-2026-10-04.json' : 'npm-audit-all-2026-10-04.json', report);
  return { exitCode: result.status, vulnerabilities: report.metadata.vulnerabilities };
};
const npm = { all: npmAudit(false), runtime: npmAudit(true), tailwindDecision: 'User explicitly deferred v4; retain Tailwind 3' };
assert.equal(npm.runtime.vulnerabilities.total, 0);

const tracked = spawnSync('git', ['ls-files', '--cached', '--others', '--exclude-standard'], { cwd: root, encoding: 'utf8' });
assert.equal(tracked.status, 0);
const buildInputs = tracked.stdout.split(/\r?\n/).filter(path =>
  /^(BE\/(src\/|pom\.xml$|Dockerfile$)|FE\/(src\/|public\/|package(?:-lock)?\.json$|(?:vite|tailwind|postcss)\.config\.|tsconfig|index\.html$|Dockerfile$|nginx\.conf$|security-headers\.conf$)|feature-rag-chatbot\/rag-chatbot\/(?:api\.py$|chatbot\.py$|ingestion\.py$|requirements\.txt$|Dockerfile$|Papers\/))/.test(path));
const inputHashes = [...new Set(buildInputs)].sort().map(path => ({ path, sha256: createHash('sha256').update(readFileSync(resolve(root, path))).digest('hex') }));
write('qa-verification-summary-2026-10-04.json', {
  scope: 'Local uncommitted QA remediation; not approved release provenance', generatedAt: new Date().toISOString(),
  images, backend: counts, frontend: { passed: 106, build: 'PASS' }, rag: { passed: 63, realFaissNonRoot: 'PASS' },
  separateMySqlRuns: read('qa-mysql-recovery-results-2026-10-04.json'),
  countNote: 'MySQL executions overlap conditional suites; do not sum these as unique tests. FE/RAG counts reflect separately observed successful runs.',
  scans, npm, buildInputHashes: inputHashes,
  credentials: 'Not captured or serialized', mainApplication: 'Not rebuilt, restarted or mutated', commitOrPush: 'Not performed',
});

// Preserve the original 90 criteria. Only upgrade a case with explicit evidence;
// carried-forward PASS means original evidence plus unchanged relevant code.
const changes = {
  'REL-01': ['PARTIAL', 'Ba image working-tree đã build/test; bản vá chưa commit/push và chưa có release registry/approval. Image IDs và source hashes được lưu, không nhận là release hoàn chỉnh.'],
  'AUTH-02': ['PASS', 'UI trống/sai credential từ lượt trước; UI malformed email bị native validation chặn trong lượt Oct4.'],
  'AUTH-03': ['PASS', 'UI ADMIN mở đủ Chat, Documents, Custom, Token Gemini và Quản trị; runtime ready, đúng role. Không có cảnh báo CSP ghi nhận ở các trang đã mở.'],
  'AUTH-05': ['PASS', 'API JWT hợp lệ control 200 / chữ ký tương tự nhưng expired 401; UI tài khoản bị disable → protected request/refresh 401 → xóa workspace, về login. Không mô tả đây là chờ cookie 30 ngày tự hết hạn.'],
  'AUTH-07': ['PASS', 'API logout revoke/refresh replay; UI logout/reload và back→about:blank/forward→login không khôi phục workspace cũ.'],
  'AUTH-08': ['PASS', 'API tạo user, tìm email/name, paging và ACTIVE/DISABLED; response không credential. UI danh sách admin tải được; không khẳng định UI có search/paging riêng.'],
  'AUTH-10': ['PASS', 'API disable/soft-delete/login từ chối; includeDeleted/filter đã chạy. UI disable giữa phiên xử lý terminal 401 đúng; fixture này đã bật lại.'],
  'CTX-01': ['PASS', 'API 6 hội thoại/3 trang không thiếu/trùng, navigation đúng, USER B thấy 0; UI reload giữ lịch sử. Bài paging không gọi provider.'],
  'CTX-07': ['PASS', 'UI unlink ở baseline; integration H2 xóa nguồn liên kết và kiểm request provider mới không chứa snapshot/source đã xóa. Không xóa dữ kiện đã chép vào history đích.'],
  'CTX-08': ['PASS', 'UI provider thật trước sửa FAIL, sau sửa đúng QATarvos 37/2.9 triệu/TV-4712 ở phiên độc lập; RELATED_HISTORY đúng owner, ghi chú làm vườn bị loại, reload giữ kết quả.'],
  'CTX-09': ['PASS', 'API/UI memory owner/scope; integration xác nhận CONFIRMED được dùng, PROPOSED/REPLACED/expired/scope phiên khác không vào request provider.'],
  'CTX-10': ['PASS', 'Integration qua controller/H2 xóa memory rồi tạo phiên độc lập: nội dung và key đã xóa vắng trong request provider. Không giả nhận là chat provider trả tiền.'],
  'CTX-11': ['PASS', 'API self/cross-user/bad mode và duplicate link 409, snapshot hiện có không đổi.'],
  'CTX-12': ['PASS', 'Integration 100 tin nhắn persist + summary oversized: <=20 history entries, <=6500 ký tự history, summary <=3000; giữ correction mới nhất, API latest100. Kiểm MockRestServiceServer, không phải 100 lượt chat model thật.'],
  'KB-01': ['PASS', 'UI upload tài liệu mẫu READY/1 chunk; regression non-root/staging/rollback sửa lỗi FAILED cũ. Upload lại sau tombstone test cũng READY.'],
  'KB-02': ['PASS', 'API search READY/FAILED và limits/paging; UI search theo ID, reload READY/1 chunk phản ánh API. UI không có status filter/paging riêng để khẳng định.'],
  'KB-03': ['PASS', 'UI Standard sau restart: 1.250.000 VND/tháng, 25 sales, source document 61b78d0d-ae15-4743-a0e9-a91d6aed2d7b.'],
  'KB-04': ['PASS', 'UI evidence panel tên qa-sales-policy.md, ID/chunk :0, đoạn gốc và READY tại truy xuất; không chỉ marker S1.'],
  'KB-05': ['PASS', 'UI cả KB rỗng (baseline) và có READY document: không tự bịa chính sách ngày lễ; nói thiếu căn cứ.'],
  'KB-12': ['PASS', 'Exact QA fixture DELETE204/GET404/list absent; phiên mới không cite/không đoán giá; đọc FAISS xác nhận vector cũ vắng. Bản mẫu mới được upload lại sau khi đã lưu negative evidence.'],
  'DLG-01': ['PASS', 'API Sales 3 intents/4 entities/13 nodes/Core off và Starter mới; UI cấu hình/reload. Chỉ bot QA, không áp dụng bot cho hệ thống thật.'],
  'DLG-09': ['PASS', 'API unknown quantity35/cancel reset và sản phẩm lạ không thành confirmed slot.'],
  'DLG-12': ['PASS', 'UI đổi role text thật → Cancel giữ draft → Discard → reopen đúng role gốc và revision3; không save/publish draft.'],
  'DLG-14': ['PASS', 'API 7 invalid saves parent/self-cycle/two-node jump cycle/slot/entity/intent/jump unknown đều400; deep equality toàn cấu hình/revision không đổi. 43 validator unit tests cũng đạt.'],
  'API-01': ['PASS', 'API published welcome/release, unpublished và unknown key bị404.'],
  'API-02': ['PASS', 'API thiếu/sai/cross-session401; expired token410 theo contract với valid-control200, không coi410 là lỗi ứng dụng.'],
  'API-09': ['PASS', 'API capability/unknown field/revoke, owned READY allowlist201/cross-admin400; không thực thi task có provider.'],
  'SEC-06': ['PASS', 'Nginx live headers cả lỗi429; access token RAM, refresh HttpOnly/SameSite Strict cookie, prod Secure, Origin+marker CSRF; FE106 tests và UI restore/revoke/logout đạt. HTTPS staging là OPS09 chưa chạy.'],
  'OPS-02': ['PASS', 'Probe dùng chính URL Compose: MySQL global+07 nhưng từng connection được ép+00, driver9.7.0. Disposable fixture đã dọn.'],
  'OPS-03': ['PASS', 'Thay API/RAG image và restart FE QA; history/config/READY persisted FAISS dùng được, UI Standard/citation đúng sau restart, tất cả5services healthy.'],
  'OPS-05': ['PASS', 'Quiesced snapshot SQL27tables/documents/FAISS34vectors restore isolated đúng dữ liệu; failure-injection fail-closed regression đạt; plaintext test temps đã dọn. Off-host/encryption/RPO thuộc OPS12 chưa chạy.'],
  'OPS-08': ['PARTIAL', 'Thay Promtail bằng Alloy pinned; native validate + Loki live/redaction/owner project/nested-flat ECS/fresh logs cả2formats sau restart đạt. Retention168h là config, chưa quan sát đủ7ngày; lifecycle/version/monitoring images cần rà định kỳ.'],
  'SEC-05': ['FAIL', 'API và FE runtime image0HIGH/0CRITICAL; RAG0PythonHIGH/0CRITICAL nhưng45OSHIGH (1fixavailable), npm all5HIGH build-chain Tailwind3. User hoãn v4. Không suppress/ignore để lấy PASS.'],
};
const baseline = read('qa-manual-ledger-results.json');
assert.equal(baseline.cases.length, 90);
const cases = baseline.cases.map(value => ({ ...value, previousResult: value.result,
  ...(changes[value.id] ? { result: changes[value.id][0], actual: changes[value.id][1], evidenceDate: '2026-10-04' }
    : { evidenceDate: '2026-10-03', note: 'Original criterion/evidence retained; not an assertion that every UI variant was rerun.' }) }));
const totals = Object.fromEntries(['PASS', 'PARTIAL', 'FAIL', 'BLOCKED', 'NOT RUN'].map(status => [status, cases.filter(value => value.result === status).length]));
assert.equal(Object.values(totals).reduce((a, b) => a + b, 0), 90);
write('qa-manual-ledger-results-2026-10-04.json', {
  baseCommit: baseline.commit, branch: 'feature/conversation-context', scope: '90-case cumulative local acceptance; working tree uncommitted',
  generatedAt: new Date().toISOString(), decision: 'NO-GO for production', counts: totals, previousCounts: baseline.counts,
  evidenceNote: 'Case PASS may combine UI/API/integration evidence; read actual/provenance. Not all PASS cases are UI tests, nor all executed on October4.', cases,
});
console.log('90-case cumulative ledger: ' + JSON.stringify(totals));
console.log('Backend338 executed PASS,29 skipped; FE106 PASS; RAG63 PASS. MySQL runs counted separately.');
console.log('Runtime image findings: ' + JSON.stringify(scans));
console.log('NO-GO: dependencies and real-infrastructure acceptance remain open. No commit/push performed.');
