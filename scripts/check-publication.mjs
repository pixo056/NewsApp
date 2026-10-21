import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

// Inspect staged Git blobs, not ignored runtime files. Never print matched values.
const entries = execFileSync('git', ['ls-files', '--stage', '-z'], { encoding: 'utf8' })
  .split('\0')
  .filter(Boolean)
  .map((entry) => {
    const [metadata, path] = entry.split('\t');
    return { sha: metadata.split(' ')[1], path };
  });
let secrets = [];
try {
  const config = JSON.parse(readFileSync('.local/config.json', 'utf8'));
  secrets = Object.values(config).filter(
    (value) => typeof value === 'string' && value.length >= 12,
  );
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
const forbiddenPath =
  /(?:^|\/)(?:\.local|\.cache|\.idea|node_modules|dist|target|backups|xcuserdata)(?:\/|$)|(?:^|\/)local\.properties$|\.(?:iml|log|pem|key|p12|pfx|jks|keystore|cer|crt|dump|backup)$/i;
const patterns = [
  ['private key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/],
  ['GitHub token', /\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b/],
  ['AWS access key', /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/],
  ['credential in URL', /https?:\/\/[^\s/:]+:[^\s/@]+@/],
  ['personal Windows path', /[A-Za-z]:[\\/]+Users[\\/]+[A-Za-z0-9_.-]+[\\/]/],
  [
    'private network address',
    /\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b/,
  ],
];
const failures = [];
let totalBytes = 0;
for (const { sha, path } of entries) {
  if (forbiddenPath.test(path) || (/(?:^|\/)\.env(?:\..*)?$/.test(path) && path !== '.env.example'))
    failures.push(`${path}: local/private file`);
  const content = execFileSync('git', ['cat-file', 'blob', sha], { maxBuffer: 32 * 1024 * 1024 });
  totalBytes += content.length;
  if (secrets.some((secret) => content.includes(Buffer.from(secret))))
    failures.push(`${path}: current local secret`);
  if (content.includes(0)) continue;
  const text = content.toString('utf8');
  for (const [name, pattern] of patterns) {
    if (pattern.test(text)) failures.push(`${path}: ${name}`);
  }
}
if (!entries.length)
  failures.push('No indexed files: run git add for reviewed source files first.');
if (failures.length) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
} else {
  console.log(
    `Publication check passed: ${entries.length} indexed files, ${(totalBytes / 1024 / 1024).toFixed(2)} MiB. No local files, private addresses, known token patterns or current local secrets detected.`,
  );
  console.log(
    'This pattern check covers the index, not existing remote history; manual review is still required.',
  );
}
