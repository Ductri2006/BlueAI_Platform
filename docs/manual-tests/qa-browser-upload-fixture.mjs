import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Synthetic bytes only. The browser should reject this before an API request.
const directory = mkdtempSync(join(tmpdir(), 'blueai-qa-upload-'));
const path = join(directory, 'qa-oversized-21MiB.txt');
writeFileSync(path, Buffer.alloc(21 * 1024 * 1024, 97));
console.log(path);
