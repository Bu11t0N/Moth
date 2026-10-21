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
# 0.5.4 beta 2: AnyTLS (sing-box 1.12), served as a sing-box JSON subscription that carries the test CA.
ANYTLS_PASSWORD = "moth-anytls-password"
def singbox_sub():
    ca = open(os.path.join(HERE, "ca.pem")).read()
    return json.dumps({"outbounds": [{"type": "anytls", "tag": "E2E AnyTLS", "server": HOST, "server_port": 20008, "password": ANYTLS_PASSWORD,
        "tls": {"enabled": True, "server_name": HOST, "certificate": ca}}]})
# 0.6.1: Xray XHTTP servers (plain, and TLS with the user-installed CA) and a UDP echo server.
XRAY = sys.argv[2] if len(sys.argv) > 2 else None
UDP_ECHO_PORT = 9999
LINKS5 = f"vless://{UUID}@{HOST}:20011?type=xhttp&security=none&encryption=none&path=%2Fmoth&mode=packet-up#E2E%20XHTTP\n"
# Served over HTTPS with the user-installed CA (port 8444): Trojan/TLS (sing-box) and VLESS/XHTTP/TLS (Xray engine).
LINKS_USER_CA = "\n".join([
    f"trojan://{PASSWORD}@{HOST}:20010?type=tcp&security=tls&sni={HOST}#UserCA%20Trojan",
    f"vless://{UUID}@{HOST}:20012?type=xhttp&security=tls&sni={HOST}&encryption=none&path=%2Fmoth&mode=packet-up#UserCA%20XHTTP"]) + "\n"
def xray_config():
    def inbound(port, tag, tls):
        stream = {"network": "xhttp", "xhttpSettings": {"path": "/moth"}}
        if tls: stream.update(security="tls", tlsSettings={"certificates": [{"certificateFile": os.path.join(HERE, "user-server.pem"), "keyFile": os.path.join(HERE, "user-server.key")}]})
        return {"tag": tag, "listen": "0.0.0.0", "port": port, "protocol": "vless", "settings": {"clients": [{"id": UUID}], "decryption": "none"}, "streamSettings": stream}
    return {"log": {"loglevel": "warning"},
        "inbounds": [inbound(20011, "xhttp-in", False), inbound(20012, "xhttp-tls-in", True)],
        # Xray 26 freedom blocks private destinations for VLESS inbounds by default; the test host is 10.0.2.2.
        "outbounds": [{"tag": t, "protocol": "freedom", "sendThrough": src, "settings": {"finalRules": [{"action": "allow"}]}} for t, src in (("xhttp-out", "127.0.0.19"), ("xhttp-tls-out", "127.0.0.20"))],
        "routing": {"rules": [{"inboundTag": ["xhttp-in"], "outboundTag": "xhttp-out"}, {"inboundTag": ["xhttp-tls-in"], "outboundTag": "xhttp-tls-out"}]}}
def start_xray():
    if not XRAY: return
    path = os.path.join(HERE, "xray-e2e.json")
    json.dump(xray_config(), open(path, "w"))
    subprocess.run([XRAY, "run", "-test", "-c", path], check=True)
    subprocess.Popen([XRAY, "run", "-c", path]); print("xray started", flush=True)
def udp_echo(address):
    """Replies to every datagram with "<source ip> <payload>" (the source identifies the server that relayed it).
    Bound per address, so replies leave from the address the client sent to (Xray drops replies from another source)."""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind((address, UDP_ECHO_PORT))
    while True:
        data, addr = s.recvfrom(2048)
        s.sendto(addr[0].encode() + b" " + data, addr)
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
    inbounds.append({"type": "anytls", "tag": "anytls-in", "listen": "0.0.0.0", "listen_port": 20008, "users": [{"password": ANYTLS_PASSWORD}],
        "tls": {"enabled": True, "certificate_path": os.path.join(HERE, "server.pem"), "key_path": os.path.join(HERE, "server.key")}})
    outbounds.append({"type": "direct", "tag": "anytls-out", "inet4_bind_address": "127.0.0.18"})
    rules.append({"inbound": ["anytls-in"], "ip_cidr": [f"{HOST}/32"], "outbound": "anytls-out"})
    outbounds.append({"type": "direct", "tag": "wg-out", "inet4_bind_address": "127.0.0.16"})
    rules.append({"inbound": ["wg-ep"], "ip_cidr": [f"{HOST}/32"], "outbound": "wg-out"})
    endpoints = [{"type": "wireguard", "tag": "wg-ep", "system": False, "address": ["10.66.0.1/24"], "private_key": WG_SERVER_PRIV,
        "listen_port": 20006, "peers": [{"public_key": WG_CLIENT_PUB, "allowed_ips": ["10.66.0.2/32"]}]}]
    # 0.6.1: Trojan over TLS with a certificate from a CA that the test installs as a user CA on the emulator.
    inbounds.append({"type": "trojan", "tag": "trojan-user-ca-in", "listen": "0.0.0.0", "listen_port": 20010, "users": [{"password": PASSWORD}],
        "tls": {"enabled": True, "certificate_path": os.path.join(HERE, "user-server.pem"), "key_path": os.path.join(HERE, "user-server.key")}})
    outbounds.append({"type": "direct", "tag": "trojan-user-ca-out", "inet4_bind_address": "127.0.0.21"})
    rules.append({"inbound": ["trojan-user-ca-in"], "ip_cidr": [f"{HOST}/32"], "outbound": "trojan-user-ca-out"})
    return {"log": {"level": "warn"}, "inbounds": inbounds, "outbounds": outbounds, "endpoints": endpoints, "route": {"rules": rules, "final": "direct"}}

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

# 0.7.1: two Shadowsocks servers in their own sing-box processes, so the failover test can stop just one of them.
FAILOVER = {"a": (20021, "127.0.0.22"), "b": (20022, "127.0.0.23")}
fo_procs = {}
def fo_start(name):
    port, src = FAILOVER[name]
    with lock:
        if name in fo_procs and fo_procs[name].poll() is None: return
        path = os.path.join(HERE, f"sing-box-fo-{name}.json")
        json.dump({"log": {"level": "warn"}, "inbounds": [{"type": "shadowsocks", "tag": "in", "listen": "0.0.0.0", "listen_port": port, "method": "aes-128-gcm", "password": PASSWORD}],
            "outbounds": [{"type": "direct", "tag": "direct"}, {"type": "direct", "tag": "src", "inet4_bind_address": src}],
            "route": {"rules": [{"ip_cidr": [f"{HOST}/32"], "outbound": "src"}], "final": "direct"}}, open(path, "w"))
        fo_procs[name] = subprocess.Popen([SING_BOX, "run", "-c", path]); print(f"failover server {name} started", flush=True)
def fo_stop(name):
    with lock:
        p = fo_procs.get(name)
        if p and p.poll() is None: p.terminate(); p.wait(10); print(f"failover server {name} stopped", flush=True)

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
        elif self.path.startswith("/fo/stop=") or self.path.startswith("/fo/start="):
            name = self.path.split("=")[-1]
            if name not in FAILOVER: self.reply("unknown", 404); return
            if "/stop=" in self.path:  # the reply may travel through the server being stopped: stop it a moment later
                threading.Thread(target=lambda: (time.sleep(1), fo_stop(name)), daemon=True).start()
            else: fo_start(name)
            self.reply("ok")
        elif self.path.startswith("/health"): self.reply("ok" if proc and proc.poll() is None else "down")
        else: self.reply("not found", 404)

class Sub(Echo):
    def do_GET(self):
        if self.path.startswith("/sub2"): self.reply(base64.b64encode(LINKS2.encode()).decode(), headers={"profile-title": "Second E2E", "subscription-userinfo": QUOTA2})
        elif self.path.startswith("/sub4"): self.reply(singbox_sub())
        elif self.path.startswith("/sub5"): self.reply(base64.b64encode(LINKS5.encode()).decode())
        elif self.path.startswith("/sub3"): self.reply(base64.b64encode(LINKS3.encode()).decode())
        elif self.path.startswith("/sub"): self.reply(base64.b64encode(LINKS.encode()).decode())
        else: self.reply("not found", 404)

class UserCaSub(Echo):
    def do_GET(self):
        if self.path.startswith("/sub"): self.reply(base64.b64encode(LINKS_USER_CA.encode()).decode())
        else: self.reply("not found", 404)

def serve(port, handler, tls=False, cert="server"):
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", port), handler)
    if tls:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(os.path.join(HERE, f"{cert}.pem"), os.path.join(HERE, f"{cert}.key"))
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

if __name__ == "__main__":
    start()
    for n in FAILOVER: fo_start(n)
    start_xray()
    for a in ("127.0.0.1", HOST): threading.Thread(target=udp_echo, args=(a,), daemon=True).start()
    serve(8081, Echo); serve(8443, Sub, tls=True); serve(8444, UserCaSub, tls=True, cert="user-server")
    print("e2e host ready", flush=True)
    while True: time.sleep(3600)
