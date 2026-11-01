import json
import logging
import threading
import time
import random
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse
import requests

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("Gateway")

class Provider:
    def __init__(self, name, base_url, api_key, models=None, weight=1):
        self.name = name
        self.base_url = base_url.rstrip('/')
        self.api_key = api_key
        self.models = models or []  # 空 = 全部可用
        self.weight = weight
        self.healthy = True
        self.last_error = None
        self.error_count = 0
    
    def matches_model(self, model):
        if not self.models:
            return True
        return model in self.models
    
    def mark_error(self, err):
        self.error_count += 1
        self.last_error = str(err)
        if self.error_count >= 3:
            self.healthy = False
    
    def mark_success(self):
        self.error_count = 0
        self.healthy = True
        self.last_error = None

class Gateway:
    def __init__(self, config_json):
        try:
            self.config = json.loads(config_json) if config_json else {}
        except:
            self.config = {}
        self.port = self.config.get("port", 8128)
        self.master_key = self.config.get("master_key", "gateway-master-2024")
        self.providers = []
        self._load_providers()
        self.server = None
        self.server_thread = None
        self.running = False
        self.request_logs = []
        self.max_logs = 100
        self._lock = threading.Lock()
    
    def _load_providers(self):
        providers_cfg = self.config.get("providers", [])
        for p in providers_cfg:
            self.providers.append(Provider(
                name=p.get("name", "unknown"),
                base_url=p.get("base_url", ""),
                api_key=p.get("api_key", ""),
                models=p.get("models", []),
                weight=p.get("weight", 1)
            ))
        logger.info(f"Loaded {len(self.providers)} providers")
    
    def _select_provider(self, model=None):
        """按权重随机选一个健康的、支持该模型的 provider"""
        candidates = [p for p in self.providers if p.healthy and p.matches_model(model)]
        if not candidates:
            candidates = [p for p in self.providers if p.healthy]
        if not candidates:
            return None
        total = sum(p.weight for p in candidates)
        r = random.uniform(0, total)
        for p in candidates:
            r -= p.weight
            if r <= 0:
                return p
        return candidates[-1]
    
    def run(self):
        if self.running:
            return
        self.running = True
        self.server = HTTPServer(('0.0.0.0', self.port), self._make_handler())
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        logger.info(f"AI Gateway started on port {self.port}")
    
    def stop(self):
        self.running = False
        if self.server:
            self.server.shutdown()
            self.server.server_close()
            self.server = None
        logger.info("Gateway stopped")
    
    def get_logs(self):
        return json.dumps(self.request_logs[-self.max_logs:], ensure_ascii=False)
    
    def get_status(self):
        return {
            "running": self.running,
            "port": self.port,
            "providers": [
                {
                    "name": p.name,
                    "base_url": p.base_url,
                    "models": p.models,
                    "healthy": p.healthy,
                    "error_count": p.error_count,
                    "last_error": p.last_error
                } for p in self.providers
            ]
        }
    
    def _make_handler(self):
        gateway = self
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.handle_request()
            def do_POST(self):
                self.handle_request()
            def do_OPTIONS(self):
                self.send_response(200)
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
                self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization')
                self.end_headers()
            
            def handle_request(self):
                start_time = time.time()
                client_ip = self.client_address[0]
                
                content_length = int(self.headers.get('Content-Length', 0))
                body = self.rfile.read(content_length) if content_length > 0 else b''
                
                log_entry = {
                    "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "method": self.command,
                    "path": self.path,
                    "ip": client_ip,
                    "body_preview": body.decode('utf-8', errors='ignore')[:200]
                }
                
                self.send_header('Access-Control-Allow-Origin', '*')
                
                auth_header = self.headers.get('Authorization', '')
                if not auth_header.startswith('Bearer ') or auth_header[7:] != gateway.master_key:
                    log_entry["status"] = 401
                    self._send_json(401, {"error": "Unauthorized"}, log_entry)
                    return
                
                if self.path.startswith('/v1/chat/completions'):
                    self._handle_chat_completions(body, log_entry)
                elif self.path.startswith('/v1/models'):
                    self._handle_models(log_entry)
                elif self.path.startswith('/v1/providers'):
                    self._handle_providers(log_entry)
                elif self.path == '/health':
                    self._send_json(200, {"status": "ok"}, log_entry)
                else:
                    log_entry["status"] = 404
                    self._send_json(404, {"error": "Not found"}, log_entry)
            
            def _handle_chat_completions(self, body, log_entry):
                try:
                    req = json.loads(body.decode('utf-8')) if body else {}
                    model = req.get('model', 'auto')
                except:
                    model = 'auto'
                
                provider = gateway._select_provider(model if model != 'auto' else None)
                if not provider:
                    log_entry["status"] = 503
                    self._send_json(503, {"error": "No healthy provider available"}, log_entry)
                    return
                
                target_url = provider.base_url + '/v1/chat/completions'
                headers = {
                    'Authorization': f'Bearer {provider.api_key}',
                    'Content-Type': 'application/json'
                }
                
                if model != 'auto':
                    req['model'] = model
                
                try:
                    resp = requests.post(target_url, headers=headers, json=req, timeout=60)
                    
                    log_entry["provider"] = provider.name
                    log_entry["status"] = resp.status_code
                    
                    if resp.status_code == 200:
                        provider.mark_success()
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.end_headers()
                        self.wfile.write(resp.content)
                    else:
                        provider.mark_error(f"HTTP {resp.status_code}: {resp.text[:200]}")
                        self._send_json(resp.status_code, resp.json() if resp.headers.get('content-type','').startswith('application/json') else {"error": resp.text}, log_entry)
                
                except Exception as e:
                    provider.mark_error(e)
                    log_entry["status"] = 502
                    self._send_json(502, {"error": "Bad gateway", "detail": str(e), "provider": provider.name}, log_entry)
            
            def _handle_models(self, log_entry):
                all_models = []
                seen = set()
                for p in gateway.providers:
                    if not p.healthy:
                        continue
                    try:
                        resp = requests.get(f"{p.base_url}/v1/models", 
                            headers={'Authorization': f'Bearer {p.api_key}'}, timeout=10)
                        if resp.status_code == 200:
                            data = resp.json()
                            for m in data.get('data', []):
                                mid = m.get('id')
                                if mid and mid not in seen:
                                    seen.add(mid)
                                    all_models.append(m)
                            p.mark_success()
                        else:
                            p.mark_error(f"models HTTP {resp.status_code}")
                    except Exception as e:
                        p.mark_error(e)
                
                self._send_json(200, {"object": "list", "data": all_models}, log_entry)
            
            def _handle_providers(self, log_entry):
                self._send_json(200, gateway.get_status(), log_entry)
            
            def _send_json(self, status, data, log_entry):
                log_entry["response_preview"] = json.dumps(data, ensure_ascii=False)[:200]
                gateway.request_logs.append(log_entry)
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.end_headers()
                self.wfile.write(json.dumps(data, ensure_ascii=False).encode())
            
            def log_message(self, format, *args):
                logger.info(f"{self.client_address[0]} - {format % args}")
        return Handler

if __name__ == "__main__":
    test_cfg = {
        "port": 8128,
        "master_key": "gateway-master-2024",
        "providers": [
            {"name": "openai", "base_url": "https://api.openai.com", "api_key": "sk-xxx", "models": ["gpt-4o", "gpt-4o-mini"]},
            {"name": "deepseek", "base_url": "https://api.deepseek.com", "api_key": "sk-xxx", "models": ["deepseek-chat", "deepseek-coder"]}
        ]
    }
    gw = Gateway(json.dumps(test_cfg))
    gw.run()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        gw.stop()
