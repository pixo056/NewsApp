import type { IncomingMessage } from 'node:http';
import { isIP } from 'node:net';

function ipv4(value: string): number | null {
  if (isIP(value) !== 4) return null;
  return value.split('.').reduce((result, octet) => (result * 256 + Number(octet)) >>> 0, 0);
}

function normalize(value = '') {
  return value.startsWith('::ffff:') ? value.slice(7) : value;
}

export function isLoopback(value?: string) {
  const address = normalize(value);
  return address === '::1' || (isIP(address) === 4 && address.startsWith('127.'));
}

export function networkAccess(lanIp?: string, prefixText?: string) {
  const address = lanIp ? ipv4(lanIp) : null;
  const prefix = Number(prefixText);
  if (
    lanIp &&
    (address === null || !prefixText || !Number.isInteger(prefix) || prefix < 1 || prefix > 32)
  ) {
    throw new Error('Invalid Wi-Fi IPv4 address or prefix. Use start-wifi.cmd.');
  }
  const mask = (0xffffffff << (32 - prefix)) >>> 0;
  return (request: Pick<IncomingMessage, 'socket'>) => {
    if (isLoopback(request.socket.remoteAddress)) return true;
    if (address === null || normalize(request.socket.localAddress) !== lanIp) return false;
    const remote = ipv4(normalize(request.socket.remoteAddress));
    return remote !== null && (remote & mask) === (address & mask);
  };
}
