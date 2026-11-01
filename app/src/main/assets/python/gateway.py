import json
import logging
import threading
import time
import random
import re
from http.server import HTTPServer, BaseHTTPRequestHandler
import requests

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("Gateway")

def extract_name_from_url(base_url):
    try:
        m = re.search(r'https?://([^/]+)', base_url)
        if m:
            host = m.group(1)
            host = re.sub(r'^(www\.|api\.)', '', host)
            return host.split('.')[0]
    except:
        pass
    return "provider"

class Provider:
    def __init__(self, name, base_url, api_keys, models=None, weight=1):
        self.name = name or extract_name_from_url(base_url)
        self.base_url = base_url.rstrip('/')
        # 支持多 key：按行分割，去空
        self.api_keys = [k.strip() for k in api_keys.split('\n') if k.strip()] if isinstance(api_keys, str) else (api_keys or [])
        self.key_index = 0
        self.models = models or []  # 空 = 全部可用
        self.weight = weight
        self.healthy = True
        self.last_error = None
        self.error_count = 0
    
    def get_next_key(self):
        """轮询获取下一个 key"""
        if not self.api_keys:
            return None
        key = self.api_keys[self.key_index % len(self.api_keys)]
        self.key_index += 1
        return key
    
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
                name=p.get("name"),
                base_url=p.get("base_url", ""),
                api_keys=p.get("api_keys", p.get("api_key", "")),  # 兼容旧字段
                models=p.get("models", []),
                weight=p.get("weight", 1)
            ))
        logger.info(f"Loaded {len(self.providers)} providers")
    
    def _select_provider(self, model=None):
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
    
    def fetch_models_for_provider(self, provider):
        """自动拉取模型列表"""
        try:
            # 尝试第一个 key
            for key in provider.api_keys:
                resp = requests.get(f"{provider.base_url}/v1/models", 
                    headers={'Authorization': f'Bearer {key}'}, timeout=10)
                if resp.status_code == 200:
                    data = resp.json()
                    models = [m.get('id') for m in data.get('data', []) if m.get('id')]
                    if models:
                        provider.models = models
                        provider.mark_success()
                        logger.info(f"Auto-fetched {len(models)} models for {provider.name}")
                        return models
                elif resp.status_code in (401, 403):
                    continue  # 试下一个 key
                else:
                    break
        except Exception as e:
            logger.warning(f"Auto-fetch models failed for {provider.name}: {e}")
        return None
    
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
                    "key_count": len(p.api_keys),
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
                
                target_model = model if model and model != 'auto' else None
                provider = gateway._select_provider(target_model)
                if not provider:
                    log_entry["status"] = 503
                    self._send_json(503, {"error": "No healthy provider available"}, log_entry)
                    return
                
                target_url = provider.base_url + '/v1/chat/completions'
                
                # 多 key 故障转移：最多尝试所有 key
                last_error = None
                for _ in range(len(provider.api_keys)):
                    api_key = provider.get_next_key()
                    headers = {
                        'Authorization': f'Bearer {api_key}',
                        'Content-Type': 'application/json'
                    }
                    if target_model:
                        req['model'] = target_model
                    
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
                            return
                        elif resp.status_code in (401, 403, 429):
                            # key 失效/限流，尝试下一个
                            last_error = f"HTTP {resp.status_code}: {resp.text[:200]}"
                            provider.mark_error(last_error)
                            continue
                        else:
                            provider.mark_error(f"HTTP {resp.status_code}: {resp.text[:200]}")
                            self._send_json(resp.status_code, resp.json() if resp.headers.get('content-type','').startswith('application/json') else {"error": resp.text}, log_entry)
                            return
                    except Exception as e:
                        last_error = str(e)
                        provider.mark_error(e)
                        continue
                
                # 所有 key 都失败
                log_entry["status"] = 502
                self._send_json(502, {"error": "Bad gateway", "detail": last_error or "All keys failed", "provider": provider.name}, log_entry)
            
            def _handle_models(self, log_entry):
                all_models = []
                seen = set()
                for p in gateway.providers:
                    if not p.healthy:
                        continue
                    # 自动拉取（如果模型列表为空）
                    if not p.models:
                        gateway.fetch_models_for_provider(p)
                    for m in p.models:
                        if m not in seen:
                            seen.add(m)
                            all_models.append({"id": m, "object": "model", "owned_by": p.name})
                    p.mark_success()
                
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
            {"base_url": "https://api.openai.com", "api_keys": "sk-xxx\nsk-yyy", "models": ["gpt-4o", "gpt-4o-mini"]},
            {"base_url": "https://api.deepseek.com", "api_keys": "sk-xxx", "models": ["deepseek-chat", "deepseek-coder"]}
        ]
    }
    gw = Gateway(json.dumps(test_cfg))
    gw.run()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        gw.stop()
