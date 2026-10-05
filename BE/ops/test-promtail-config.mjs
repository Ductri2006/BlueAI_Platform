import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const configPath = fileURLToPath(new URL('./promtail/promtail-config.yml', import.meta.url));
const config = readFileSync(configPath, 'utf8');
const dockerDiscovery = config.match(/docker_sd_configs:\s*\n([\s\S]*?)\n\s*relabel_configs:/)?.[1];
assert.ok(dockerDiscovery, 'expected a Docker service discovery config');
assert.match(dockerDiscovery, /filters:\s*\n\s+- name:\s*label\s*\n\s+values:\s*\['com\.docker\.compose\.project=blueai-auth'\]/);

const relabelSection = config.match(/relabel_configs:\s*\n([\s\S]*?)\n\s*pipeline_stages:/)?.[1];
assert.ok(relabelSection, 'expected a Docker relabel config before pipeline stages');

const firstRelabel = relabelSection
  .split(/\r?\n/)
  .filter((line) => line.trim() && !line.trim().startsWith('#'))
  .slice(0, 3)
  .join('\n');
assert.match(firstRelabel, /source_labels:\s*\['__meta_docker_container_label_com_docker_compose_project'\]/);
assert.match(firstRelabel, /regex:\s*'\^blueai-auth\$'/);
assert.match(firstRelabel, /action:\s*keep/);

const projectRegex = /^blueai-auth$/;
assert.equal(projectRegex.test('blueai-auth'), true, 'the intended Compose project must be retained');
assert.equal(projectRegex.test('astronagent'), false, 'unrelated Compose projects must be excluded');
assert.equal(projectRegex.test('blueai-auth-other'), false, 'project matching must be exact');

assert.match(config, /level:\s*log\.level/);
assert.match(config, /logger:\s*log\.logger/);
const labelStage = config.match(/\n\s+- labels:\s*\n([\s\S]*)$/)?.[1] ?? '';
assert.match(labelStage, /\blevel:/);
assert.match(labelStage, /\blogger:/);
assert.doesNotMatch(labelStage, /trace[_-]?id:/i, 'per-request trace IDs must not become Loki labels');

console.log('Promtail config tests PASS (project allowlist, unrelated project exclusion, ECS fields, no trace-ID label).');
