import { execFileSync, spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

// Read-only application audit; writes generated reports only beside this harness.
const outputDir = dirname(fileURLToPath(import.meta.url));
const repo = resolve(outputDir, '../..');
const commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repo, encoding: 'utf8' }).trim();
const summary = { commit, generatedAt: new Date().toISOString(), npm: {}, images: {}, secrets: {} };
const npmCli = process.env.QA_NPM_CLI || 'D:/node_modules/npm/bin/npm-cli.js';
for (const [scope, extra] of [['all', []], ['runtime', ['--omit=dev']]]) {
  const run = spawnSync(process.execPath, [npmCli, 'audit', '--json', ...extra], {
    cwd: resolve(repo, 'FE'), encoding: 'utf8', timeout: 120000,
  });
  if (run.error) throw run.error;
  const report = JSON.parse(run.stdout);
  if (!report.metadata?.vulnerabilities) throw new Error(`Invalid npm ${scope} audit response`);
  writeFileSync(resolve(outputDir, `npm-audit-${scope}.json`), JSON.stringify(report, null, 2) + '\n');
  summary.npm[scope] = { exitCode: run.status, counts: report.metadata.vulnerabilities };
}
for (const service of ['api', 'frontend', 'rag']) {
  const report = JSON.parse(readFileSync(resolve(outputDir, `trivy-${service}.json`), 'utf8'));
  const rows = (report.Results || []).flatMap(result => (result.Vulnerabilities || []).map(v => ({
    target: result.Target, class: result.Class, severity: v.Severity, id: v.VulnerabilityID,
    package: v.PkgName, installed: v.InstalledVersion, fixed: v.FixedVersion || null,
  })));
  summary.images[service] = {
    artifact: report.ArtifactName,
    counts: { high: rows.filter(v => v.severity === 'HIGH').length, critical: rows.filter(v => v.severity === 'CRITICAL').length },
    findings: rows,
    interpretation: 'Package/advisory pairs, not proven exploitable vulnerabilities. RAG Python SBOM versions require reconciliation with installed distribution metadata.',
  };
}
for (const scope of ['head', 'history']) {
  const findings = JSON.parse(readFileSync(resolve(outputDir, `gitleaks-${scope}-redacted.json`), 'utf8'));
  summary.secrets[scope] = {
    findings: findings.length,
    rules: [...new Set(findings.map(f => f.RuleID))],
    interpretation: 'Test-path candidates require owner review. This scan does not prove real provider keys were revoked.',
  };
}
writeFileSync(resolve(outputDir, 'qa-audit-summary-results.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify({ npm: summary.npm, images: Object.fromEntries(Object.entries(summary.images).map(([name, value]) => [name, value.counts])), secrets: summary.secrets }, null, 2));
