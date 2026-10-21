import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { isLoopback, networkAccess } from './config/network-access';

const allowRequest = networkAccess(process.env.NEWSAPP_LAN_IP, process.env.NEWSAPP_LAN_PREFIX);

export default defineConfig({
  plugins: [
    react(),
    {
      name: 'newsapp-local-identity',
      configureServer(server) {
        server.httpServer?.prependListener('upgrade', (request, socket) => {
          if (!allowRequest(request)) socket.destroy();
        });
        server.middlewares.use((request, response, next) => {
          response.setHeader('X-NewsApp-Web', '1');
          response.setHeader('X-NewsApp-Mode', process.env.NEWSAPP_LAN_IP ? 'wifi' : 'local');
          if (process.env.NEWSAPP_LAN_IP)
            response.setHeader('X-NewsApp-Wifi-Ip', process.env.NEWSAPP_LAN_IP);
          let pathname: string;
          try {
            pathname = new URL(request.url || '/', 'http://localhost').pathname;
            // Match the proxy's decoded route too, including encoded slashes.
            for (let attempt = 0; attempt < 3; attempt++) {
              const decoded = decodeURIComponent(pathname);
              if (decoded === pathname) break;
              pathname = decoded;
            }
          } catch {
            response.writeHead(400).end();
            return;
          }
          const mailbox = /^\/(?:api\/)?dev\/mailbox(?:[\/;]|$)/i.test(pathname);
          if (!allowRequest(request) || (mailbox && !isLoopback(request.socket.remoteAddress))) {
            response.writeHead(403, { 'Content-Type': 'application/json; charset=utf-8' });
            response.end(
              JSON.stringify({
                message:
                  'Доступ разрешён только с ноутбука или выбранной Wi-Fi-сети. Тестовая почта доступна только на ноутбуке через localhost.',
              }),
            );
            return;
          }
          next();
        });
      },
    },
  ],
  server: {
    port: 5173,
    strictPort: true,
    fs: {
      deny: [
        '.env',
        '.env.*',
        '**/.git/**',
        '**/.local/**',
        '**/.cache/**',
        '**/backend/**',
        '**/scripts/**',
      ],
    },
    watch: { ignored: ['**/.local/**', '**/.cache/**', '**/backend/target/**'] },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyReq', (proxyRequest, request) => {
            // Overwrite any client-supplied marker; Spring only listens on loopback.
            proxyRequest.setHeader(
              'X-NewsApp-Mailbox',
              isLoopback(request.socket.remoteAddress) ? 'local' : 'remote',
            );
          });
        },
      },
    },
  },
});
