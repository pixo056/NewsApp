import assert from 'node:assert/strict';
import test from 'node:test';
import type { IncomingMessage } from 'node:http';
import { isLoopback, networkAccess } from './network-access.ts';

// RFC 5737 documentation addresses: these are not anyone's Wi-Fi addresses.
const request = (remoteAddress: string, localAddress = '192.0.2.10') =>
  ({ socket: { remoteAddress, localAddress } }) as Pick<IncomingMessage, 'socket'>;

test('local mode only permits loopback clients', () => {
  const allow = networkAccess();
  for (const ip of ['127.0.0.1', '::1', '::ffff:127.0.0.1']) assert.equal(allow(request(ip)), true);
  assert.equal(allow(request('192.0.2.20')), false);
  assert.equal(isLoopback('127.attacker.example'), false);
});
test('Wi-Fi mode permits its own subnet only on the chosen local address', () => {
  const allow = networkAccess('192.0.2.10', '24');
  assert.equal(allow(request('192.0.2.20')), true);
  assert.equal(allow(request('::ffff:192.0.2.20', '::ffff:192.0.2.10')), true);
  assert.equal(allow(request('198.51.100.20')), false);
  assert.equal(allow(request('203.0.113.2', '203.0.113.1')), false);
  assert.equal(allow(request('192.0.2.20', '203.0.113.1')), false);
  assert.equal(allow(request('2001:db8::1')), false);
});
test('Wi-Fi mode also permits loopback and validates network settings', () => {
  assert.equal(networkAccess('192.0.2.10', '24')(request('127.0.0.1')), true);
  for (const [ip, prefix] of [
    ['invalid', '24'],
    ['192.0.2.10', '0'],
    ['192.0.2.10', '33'],
    ['192.0.2.10', ''],
  ]) {
    assert.throws(() => networkAccess(ip, prefix));
  }
});
