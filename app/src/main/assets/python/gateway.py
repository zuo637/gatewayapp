import json
import logging
import threading
import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
import requests

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("Gateway")

class Gateway:
    def __init__(self, config_json):
        try:
            self.config = json.loads(config_json) if config_json else {}
        except:
            self.config = {}
        self.port = self.config.get("port", 8128)
        self.master_key = self.config.get("master_key", "gateway-master-2024")
        self.upstreams = self.config.get("upstreams", [])
        self.server = None
        self.server_thread = None
        self.running = False
        self.request_logs = []
        self.max_logs = 100

    def run(self):
        if self.running:
            return
        self.running = True
        self.server = HTTPServer(('0.0.0.0', self.port), self._make_handler())
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        logger.info(f"Gateway started on port {self.port}")

    def stop(self):
        self.running = False
        if self.server:
            self.server.shutdown()
            self.server.server_close()
            self.server = None
        logger.info("Gateway stopped")

    def get_logs(self):
        return json.dumps(self.request_logs[-self.max_logs:], ensure_ascii=False)

    def _make_handler(self):
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.handle_request()

            def do_POST(self):
                self.handle_request()

            def do_PUT(self):
                self.handle_request()

            def do_DELETE(self):
                self.handle_request()

            def do_PATCH(self):
                self.handle_request()

            def handle_request(self):
                start_time = time.time()
                client_ip = self.client_address[0]
                
                # 读取请求体
                content_length = int(self.headers.get('Content-Length', 0))
                body = self.rfile.read(content_length) if content_length > 0 else b''
                
                # 记录日志
                log_entry = {
                    "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "method": self.command,
                    "path": self.path,
                    "ip": client_ip,
                    "headers": dict(self.headers),
                    "body": body.decode('utf-8', errors='ignore')[:500]
                }

                # 鉴权检查
                auth_header = self.headers.get('Authorization', '')
                if not auth_header.startswith('Bearer ') or auth_header[7:] != gateway.master_key:
                    log_entry["status"] = 401
                    log_entry["response"] = "Unauthorized"
                    gateway.request_logs.append(log_entry)
                    self.send_response(401)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(b'{"error":"Unauthorized"}')
                    return

                # 路由转发
                if not gateway.upstreams:
                    log_entry["status"] = 503
                    log_entry["response"] = "No upstreams configured"
                    gateway.request_logs.append(log_entry)
                    self.send_response(503)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(b'{"error":"No upstreams configured"}')
                    return

                # 简单轮询选择上游
                upstream = gateway.upstreams[0]
                target_url = upstream.rstrip('/') + self.path
                
                try:
                    resp = requests.request(
                        method=self.command,
                        url=target_url,
                        headers={k: v for k, v in self.headers.items() if k.lower() not in ('host', 'content-length')},
                        data=body if body else None,
                        timeout=30
                    )
                    
                    log_entry["status"] = resp.status_code
                    log_entry["response"] = resp.text[:500]
                    gateway.request_logs.append(log_entry)
                    
                    self.send_response(resp.status_code)
                    for k, v in resp.headers.items():
                        if k.lower() not in ('transfer-encoding', 'content-encoding'):
                            self.send_header(k, v)
                    self.end_headers()
                    self.wfile.write(resp.content)
                    
                except Exception as e:
                    log_entry["status"] = 502
                    log_entry["response"] = str(e)
                    gateway.request_logs.append(log_entry)
                    self.send_response(502)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(json.dumps({"error": "Bad gateway", "detail": str(e)}).encode())

            def log_message(self, format, *args):
                logger.info(f"{self.client_address[0]} - {format % args}")

        return Handler


if __name__ == "__main__":
    # 测试用
    gw = Gateway('{"port": 8128, "master_key": "gateway-master-2024", "upstreams": ["http://httpbin.org"]}')
    gw.run()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        gw.stop()