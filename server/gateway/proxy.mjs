import fs from "fs";
import path from "path";
import https from "https";
import httpProxy from "http-proxy";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.HTTPS_PORT || 8443);
const BACKEND = process.env.BACKEND_TARGET || "http://127.0.0.1:8000";
const STATIC_DIR = path.resolve(__dirname, "../web");
const TLS_KEY_FILE = process.env.TLS_KEY_FILE || path.resolve(__dirname, "../certs/dev.key");
const TLS_CERT_FILE = process.env.TLS_CERT_FILE || path.resolve(__dirname, "../certs/dev.crt");

const tls = {
  key: fs.readFileSync(TLS_KEY_FILE),
  cert: fs.readFileSync(TLS_CERT_FILE),
};

const proxy = httpProxy.createProxyServer({ target: BACKEND, changeOrigin: true, ws: true, xfwd: true });

// 错误日志必须遮蔽 URL 中的鉴权令牌（WS 握手 query 携带 ?token=），防止令牌进日志。
function maskUrl(raw) {
  if (!raw) return raw;
  try {
    const u = new URL(raw, "http://gateway.local");
    if (u.searchParams.has("token")) u.searchParams.set("token", "***");
    return `${u.pathname}${u.search}`;
  } catch {
    return String(raw).replace(/([?&]token=)[^&]*/gi, "$1***");
  }
}

proxy.on("error", (err, req, res) => {
  console.error("[proxy]", maskUrl(req?.url), err.message);
  if (res && typeof res.writeHead === "function" && !res.headersSent) {
    res.writeHead(502, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "bad gateway" }));
  }
});

const MIME = {
  ".html": "text/html",
  ".js": "application/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".png": "image/png",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
};

const ADMIN_PUBLIC = process.env.ADMIN_PUBLIC === "true";

// 安全解析静态文件路径：拒绝任何逃出 web 目录的路径（防路径穿越）。
// 先解码（%2e%2e → ..），再归一化，最后校验结果仍位于 STATIC_DIR 之下。
function resolveStatic(url) {
  const pathname = (url.split("?")[0] || "/");
  let decoded = pathname;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    decoded = pathname;
  }
  const root = path.resolve(STATIC_DIR);
  const fp = path.normalize(path.join(root, decoded === "/" ? "index.html" : decoded));
  const rootPrefix = root.endsWith(path.sep) ? root : root + path.sep;
  if (fp !== root && !fp.startsWith(rootPrefix)) return null;
  return fp;
}

const server = https.createServer(tls, (req, res) => {
  const url = req.url || "/";
  // Block /admin on public gateway unless explicitly allowed
  if ((url === "/admin" || url.startsWith("/admin/")) && !ADMIN_PUBLIC) {
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not found" }));
    return;
  }
  if (url.startsWith("/healthz") || url.startsWith("/api/") || url.startsWith("/ws/") || url === "/admin" || url.startsWith("/admin/")) {
    proxy.web(req, res);
    return;
  }
  const fp = resolveStatic(url);
  if (!fp) {
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not found" }));
    return;
  }
  let finalPath = fp;
  if (!fs.existsSync(fp)) finalPath = path.join(STATIC_DIR, "index.html");
  const ext = path.extname(finalPath);
  fs.readFile(finalPath, (err, data) => {
    if (err) { res.writeHead(404); res.end("Not Found"); return; }
    res.writeHead(200, { "Content-Type": MIME[ext] || "application/octet-stream", "Cache-Control": "no-cache" });
    res.end(data);
  });
});

server.on("upgrade", (req, socket, head) => {
  if ((req.url || "").startsWith("/ws/")) proxy.ws(req, socket, head);
  else socket.destroy();
});

server.listen(PORT, "0.0.0.0", () => console.log(`[gateway] https://0.0.0.0:${PORT} -> ${BACKEND}`));
