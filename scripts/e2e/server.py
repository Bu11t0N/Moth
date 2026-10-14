#!/usr/bin/env python3
"""E2E host side: sing-box test servers, HTTPS subscription, echo and control endpoints (CI only, synthetic data)."""
import base64, http.server, json, os, ssl, subprocess, sys, threading, time, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
SING_BOX = sys.argv[1] if len(sys.argv) > 1 else "sing-box"
HOST = "10.0.2.2"  # the runner host as seen from the emulator; also an alias on the runner's loopback
UUID = "11111111-2222-4333-8444-555555555555"
PASSWORD = "moth-e2e-password"
SERVERS = [  # inbound type, port, local source address used for traffic to the echo server (identifies the server)
    ("vless", 20001, "127.0.0.11"), ("trojan", 20002, "127.0.0.12"), ("shadowsocks", 20003, "127.0.0.13")]
ss_user = base64.urlsafe_b64encode(f"aes-128-gcm:{PASSWORD}".encode()).decode().rstrip("=")
LINKS = "\n".join([
    f"vless://{UUID}@{HOST}:20001?type=tcp&security=none&encryption=none#E2E%20VLESS",
    f"trojan://{PASSWORD}@{HOST}:20002?type=tcp&security=none#E2E%20Trojan",
    f"ss://{ss_user}@{HOST}:20003#E2E%20Shadowsocks"]) + "\n"
# Second subscription: same test servers, different links (so different server ids), with a traffic quota at 92 %.
LINKS2 = "\n".join([
    f"vless://{UUID}@{HOST}:20001?type=tcp&security=none&encryption=none&sub=2#Second%20VLESS",
    f"trojan://{PASSWORD}@{HOST}:20002?type=tcp&security=none&sub=2#Second%20Trojan"]) + "\n"
# 0.5.4: SOCKS5 and HTTP with username/password, WireGuard (synthetic test keys, CI only).
PROXY_USER, PROXY_PASS = "moth-user", "moth p@ss"
WG_SERVER_PRIV, WG_SERVER_PUB = "KO/Z9gtGTCwqIqin0kUlRJVQVeuzzsTRKU3qDOCiMWs=", "GbOmk5x5V9XRtDZQPaBtmxuI99wgeSYorhVHQPq/vgM="
WG_CLIENT_PRIV, WG_CLIENT_PUB = "2E56FQv7AvD1c0XspyCoR294+UvV3XsTFgh10swBS2I=", "JoSDfNCygyHo6m5agrym3eTdEAGM5/jv8puE1LPjZXo="
from urllib.parse import quote
LINKS3 = "\n".join([
    f"socks5://{quote(PROXY_USER, safe='')}:{quote(PROXY_PASS, safe='')}@{HOST}:20004#E2E%20SOCKS5",
    f"http://{quote(PROXY_USER, safe='')}:{quote(PROXY_PASS, safe='')}@{HOST}:20005#E2E%20HTTP",
    f"wireguard://{quote(WG_CLIENT_PRIV, safe='')}@{HOST}:20006?publickey={quote(WG_SERVER_PUB, safe='')}&address=10.66.0.2%2F32&mtu=1280#E2E%20WireGuard"]) + "\n"
QUOTA2 = "upload=2000000000; download=7200000000; total=10000000000; expire=4102444800"

def config():
    inbounds, outbounds, rules = [], [{"type": "direct", "tag": "direct"}], []
    for kind, port, src in SERVERS:
        ib = {"type": kind, "tag": f"{kind}-in", "listen": "0.0.0.0", "listen_port": port}
        if kind == "vless": ib["users"] = [{"uuid": UUID}]; ib["multiplex"] = {"enabled": True}
        elif kind == "trojan": ib["users"] = [{"password": PASSWORD}]
        else: ib.update(method="aes-128-gcm", password=PASSWORD)
        inbounds.append(ib)
        outbounds.append({"type": "direct", "tag": f"{kind}-out", "inet4_bind_address": src})
        rules.append({"inbound": [f"{kind}-in"], "ip_cidr": [f"{HOST}/32"], "outbound": f"{kind}-out"})
    for kind, port, src in [("socks", 20004, "127.0.0.14"), ("http", 20005, "127.0.0.15")]:
        inbounds.append({"type": kind, "tag": f"{kind}-in", "listen": "0.0.0.0", "listen_port": port, "users": [{"username": PROXY_USER, "password": PROXY_PASS}]})
        outbounds.append({"type": "direct", "tag": f"{kind}-out", "inet4_bind_address": src})
        rules.append({"inbound": [f"{kind}-in"], "ip_cidr": [f"{HOST}/32"], "outbound": f"{kind}-out"})
    outbounds.append({"type": "direct", "tag": "wg-out", "inet4_bind_address": "127.0.0.16"})
    rules.append({"inbound": ["wg-ep"], "ip_cidr": [f"{HOST}/32"], "outbound": "wg-out"})
    endpoints = [{"type": "wireguard", "tag": "wg-ep", "system": False, "address": ["10.66.0.1/24"], "private_key": WG_SERVER_PRIV,
        "listen_port": 20006, "peers": [{"public_key": WG_CLIENT_PUB, "allowed_ips": ["10.66.0.2/32"]}]}]
    return {"log": {"level": "warn"}, "inbounds": inbounds, "outbounds": outbounds, "endpoints": endpoints, "route": {"rules": rules, "final": "direct"}}
    return {"log": {"level": "warn"}, "inbounds": inbounds, "outbounds": outbounds, "route": {"rules": rules, "final": "direct"}}

proc = None
lock = threading.Lock()
def start():
    global proc
    with lock:
        if proc and proc.poll() is None: return
        path = os.path.join(HERE, "sing-box-e2e.json")
        json.dump(config(), open(path, "w"))
        proc = subprocess.Popen([SING_BOX, "run", "-c", path])
        print("sing-box started", flush=True)
def stop():
    with lock:
        if proc and proc.poll() is None: proc.terminate(); proc.wait(10); print("sing-box stopped", flush=True)

class Echo(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, body, code=200, headers=None):
        data = body.encode(); self.send_response(code); self.send_header("Content-Type", "text/plain")
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        if self.path.startswith("/ip"): self.reply(self.client_address[0])
        elif self.path.startswith("/pause"):
            seconds = int(self.path.split("=")[-1]) if "=" in self.path else 20
            def run(): time.sleep(1); stop(); time.sleep(seconds); start()
            threading.Thread(target=run, daemon=True).start(); self.reply("paused")
        elif self.path.startswith("/health"): self.reply("ok" if proc and proc.poll() is None else "down")
        else: self.reply("not found", 404)

class Sub(Echo):
    def do_GET(self):
        if self.path.startswith("/sub2"): self.reply(base64.b64encode(LINKS2.encode()).decode(), headers={"profile-title": "Second E2E", "subscription-userinfo": QUOTA2})
        elif self.path.startswith("/sub3"): self.reply(base64.b64encode(LINKS3.encode()).decode())
        elif self.path.startswith("/sub"): self.reply(base64.b64encode(LINKS.encode()).decode())
        else: self.reply("not found", 404)

def serve(port, handler, tls=False):
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", port), handler)
    if tls:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(os.path.join(HERE, "server.pem"), os.path.join(HERE, "server.key"))
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

if __name__ == "__main__":
    start()
    serve(8081, Echo); serve(8443, Sub, tls=True)
    print("e2e host ready", flush=True)
    while True: time.sleep(3600)
