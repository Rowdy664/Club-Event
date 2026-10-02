import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';
// https://vitejs.dev/config/
export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    define: {
        global: 'globalThis',
    },
    server: {
        port: 5173,
        proxy: (function () {
            // Proxy API + websocket calls to the Spring Boot backend during development.
            // NOTE: use 127.0.0.1, NOT localhost. On Node 17+ `localhost` can resolve to
            // IPv6 (::1) first, but Spring Boot binds to IPv4 by default — the mismatch
            // surfaces as ECONNREFUSED / "Network Error" in the browser on login while
            // http://localhost:8080 still opens fine directly. 127.0.0.1 forces IPv4.
            var target = process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080';
            // Turn the noisy, repeating "[vite] ws proxy socket error: read ECONNRESET"
            // stack trace into a single readable hint. This error just means the backend
            // isn't reachable yet (not started, still booting, or failed to connect to the
            // DB). It is harmless — the browser reconnects once the API is up.
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            var softenErrors = function (proxy) {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                proxy.on('error', function (err) {
                    var _a, _b;
                    console.warn("[dev proxy] backend not reachable at ".concat(target, " (").concat((_b = (_a = err === null || err === void 0 ? void 0 : err.code) !== null && _a !== void 0 ? _a : err === null || err === void 0 ? void 0 : err.message) !== null && _b !== void 0 ? _b : 'no response', "). ") +
                        "Start the backend first (cd backend && mvn spring-boot:run), then reload the page.");
                });
            };
            return {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                '/api': { target: target, changeOrigin: true, configure: function (p) { return softenErrors(p); } },
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                '/ws': { target: target, changeOrigin: true, ws: true, configure: function (p) { return softenErrors(p); } },
            };
        })(),
    },
    build: {
        outDir: 'dist',
        sourcemap: false,
    },
});
