import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const outputDir = dirname(fileURLToPath(import.meta.url));
const images = ['blueai-preprod-api:followup-20261004', 'blueai-preprod-rag:followup-20261004',
  'blueai-preprod-frontend:followup-20261004'];
const report = { generatedAt: new Date().toISOString(), scope: 'Local runtime image secret scan; not provider revocation proof', images: [] };
for (const image of images) {
  const inspected = spawnSync('docker', ['image', 'inspect', image], { encoding: 'utf8' });
  assert.equal(inspected.status, 0, `Image must exist: ${image}`);
  const id = JSON.parse(inspected.stdout)[0].Id;
  const scan = spawnSync('docker', ['run', '--rm', '-v', '/var/run/docker.sock:/var/run/docker.sock:ro',
    '-v', 'blueai-preprod-trivy-cache-20261003:/root/.cache/trivy', 'aquasec/trivy:0.75.0',
    'image', '--scanners', 'secret', '--skip-version-check', '--quiet', '--format', 'json', id],
    { encoding: 'utf8', timeout: 300000, maxBuffer: 32 * 1024 * 1024 });
  // Never print raw scanner output: findings can contain credential values.
  assert.equal(scan.status, 0, `Secret scanner failed for ${image}; raw output withheld`);
  const result = JSON.parse(scan.stdout);
  const findings = (result.Results || []).flatMap(target => (target.Secrets || []).map(secret => ({
    path: target.Target, rule: secret.RuleID, severity: secret.Severity,
    startLine: secret.StartLine, endLine: secret.EndLine,
  })));
  report.images.push({ image, id, findings, result: findings.length ? 'REVIEW REQUIRED' : 'PASS' });
  console.log(`${image}: ${findings.length} candidate secret findings (values withheld)`);
}
writeFileSync(resolve(outputDir, 'qa-image-secrets-followup-2026-10-04.json'), JSON.stringify(report, null, 2) + '\n');
if (report.images.some(image => image.findings.length)) { process.exitCode = 1; }
