import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const config = readFileSync(new URL('./alloy/config.alloy', import.meta.url), 'utf8');
assert.match(config, /values\s*=\s*\["com\.docker\.compose\.project=blueai-auth"\]/);
const firstRule = config.match(/rule\s*\{([\s\S]*?)\}/)?.[1];
assert.match(firstRule, /__meta_docker_container_label_com_docker_compose_project/);
assert.match(firstRule, /regex\s*=\s*"blueai-auth"/);
assert.match(firstRule, /action\s*=\s*"keep"/);
assert.match(config, /relabel_rules\s*=\s*discovery\.relabel\.blueai\.rules/);
assert.match(config, /loki\.process\.redacted\.receiver/);
assert.match(config, /\[REDACTED\]/);
assert.match(config, /\\"log\.level\\"/);
assert.match(config, /\\"log\.logger\\"/);
assert.doesNotMatch(config, /target_label\s*=\s*"(?:trace|request|user|email)/);
console.log('Alloy configuration contracts PASS: project allowlist, relabeling, ECS labels and redaction pipeline.');
