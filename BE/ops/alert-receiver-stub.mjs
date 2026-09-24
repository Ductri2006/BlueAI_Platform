import { createServer } from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';

const output = process.env.RECEIPT_FILE ?? '/data/received.json';
mkdirSync(output.substring(0, output.lastIndexOf('/')) || '.', { recursive: true });
const server = createServer((request, response) => {
  const chunks = [];
  request.on('data', (chunk) => chunks.push(chunk));
  request.on('end', () => {
    if (request.method === 'POST') {
      writeFileSync(output, Buffer.concat(chunks));
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end('{"received":true}');
      return;
    }
    response.writeHead(405);
    response.end();
  });
});
server.listen(8080, '0.0.0.0');
