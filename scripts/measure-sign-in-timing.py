#!/usr/bin/env python3
"""
Can a stranger tell a listed alpha address from an unlisted one by timing, or
by anything else the email sign-in endpoints return? (docs/13 §5, "Measured")

    python3 scripts/measure-sign-in-timing.py                   # 500 of each, the default
    python3 scripts/measure-sign-in-timing.py --pairs 1000 --status-pairs 500
    python3 scripts/measure-sign-in-timing.py --jar backend/build/libs/almira-backend-0.1.0.jar

Everything it talks to it starts itself, on free loopback ports, and removes at
the end, pass or fail:

  ws-decoy-outbox-pg-<run>     postgres:16-alpine, page checksums, the two roles
  ws-decoy-outbox-redis-<run>  redis:7-alpine
  an SMTP sink                 a thread in this process, so the email provider
                               is `live` (the production shape: no development
                               echo) and nothing leaves the machine
  the application              java -jar, ALMIRA_ENV=development only so the
                               startup checks accept a throwaway database; the
                               email channel is live, so the response carries no
                               code and nothing is echoed

Five addresses are listed and five are not, as in the alpha. Three phases:

  A. The request path. --pairs pairs of POST /auth/otp/email/request, one
     listed and one unlisted per pair, the order inside a pair alternating
     (ABBA) so drift in the server lands on both. One keep-alive connection,
     cooldown and hourly caps lifted so every request is answered 200.
     Per request: wall time, status, body length, body with the request id
     taken out, header names and values with Date taken out.
  B. The delivery status. --status-pairs pairs, alternating the same way. After
     each request, GET /auth/otp/email/delivery/{id} every 100 ms, then every
     5 ms from 150 ms before the configured settle moment, until it says sent.
     Per request: when it was last seen sending and first seen sent, how many
     polls, and every poll's status, length, body and headers.
  C. Everything else a stranger can ask, on a server restarted with the
     packaged cooldown and caps: the 429 for asking again and its Retry-After,
     a stale request id, five wrong codes to the lock, the right-looking code
     after the lock, an unknown request id, a malformed address — each compared
     listed against unlisted.

For A and B it reports the median, p95 and p99 of each group, the difference of
medians, a Mann-Whitney U test and a two-sample Kolmogorov-Smirnov test with
their p-values, and whether any non-timing field ever differed. The raw samples
are written as JSON next to --out.

Stdlib only. Exit status 0 when nothing but timing noise differs (no p < 0.01
and no field difference), 1 otherwise, 2 when the harness could not run.
"""
import argparse
import http.client
import json
import math
import os
import random
import re
import shutil
import socket
import socketserver
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PREFIX = "ws-decoy-outbox"


# --- the SMTP sink --------------------------------------------------------------

class SmtpSink(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self):
        self.recipients = []
        self.lock = threading.Lock()
        super().__init__(("127.0.0.1", 0), SmtpHandler)


class SmtpHandler(socketserver.StreamRequestHandler):
    def say(self, line):
        self.wfile.write((line + "\r\n").encode())
        self.wfile.flush()

    def handle(self):
        self.say("220 sink ready")
        while True:
            raw = self.rfile.readline()
            if not raw:
                return
            line = raw.decode(errors="replace").rstrip("\r\n")
            upper = line.upper()
            if upper.startswith("EHLO") or upper.startswith("HELO"):
                self.say("250 sink")
            elif upper.startswith("MAIL FROM:") or upper == "RSET" or upper == "NOOP":
                self.say("250 OK")
            elif upper.startswith("RCPT TO:"):
                address = line.split(":", 1)[1].strip().strip("<>").lower()
                with self.server.lock:
                    self.server.recipients.append(address)
                self.say("250 OK")
            elif upper == "DATA":
                self.say("354 go ahead")
                while True:
                    d = self.rfile.readline()
                    if not d or d.rstrip(b"\r\n") == b".":
                        break
                self.say("250 OK queued")
            elif upper == "QUIT":
                self.say("221 bye")
                return
            else:
                self.say("502 not implemented")


# --- statistics ---------------------------------------------------------------------

def pct(values, p):
    """Nearest-rank percentile."""
    s = sorted(values)
    if not s:
        return float("nan")
    k = max(0, min(len(s) - 1, int(math.ceil(p / 100.0 * len(s))) - 1))
    return s[k]


def mann_whitney(a, b):
    """Two-sided Mann-Whitney U with the normal approximation, tie-corrected. Returns (U, z, p)."""
    n1, n2 = len(a), len(b)
    combined = sorted([(v, 0) for v in a] + [(v, 1) for v in b])
    ranks = [0.0] * len(combined)
    ties = 0.0
    i = 0
    while i < len(combined):
        j = i
        while j + 1 < len(combined) and combined[j + 1][0] == combined[i][0]:
            j += 1
        r = (i + j) / 2.0 + 1
        for k in range(i, j + 1):
            ranks[k] = r
        t = j - i + 1
        ties += t ** 3 - t
        i = j + 1
    r1 = sum(r for r, (_, g) in zip(ranks, combined) if g == 0)
    u1 = r1 - n1 * (n1 + 1) / 2.0
    n = n1 + n2
    mu = n1 * n2 / 2.0
    sigma = math.sqrt(n1 * n2 / 12.0 * ((n + 1) - ties / (n * (n - 1))))
    if sigma == 0:
        return u1, 0.0, 1.0
    z = (u1 - mu - math.copysign(0.5, u1 - mu)) / sigma if u1 != mu else 0.0
    p = math.erfc(abs(z) / math.sqrt(2))
    return u1, z, min(1.0, p)


def ks_two_sample(a, b):
    """Two-sample Kolmogorov-Smirnov: D and the asymptotic two-sided p-value."""
    a, b = sorted(a), sorted(b)
    n1, n2 = len(a), len(b)
    i = j = 0
    d = 0.0
    while i < n1 and j < n2:
        x = min(a[i], b[j])
        while i < n1 and a[i] == x:
            i += 1
        while j < n2 and b[j] == x:
            j += 1
        d = max(d, abs(i / n1 - j / n2))
    ne = n1 * n2 / (n1 + n2)
    lam = (math.sqrt(ne) + 0.12 + 0.11 / math.sqrt(ne)) * d
    if lam < 1e-9:
        return d, 1.0
    p = 2 * sum((-1) ** (k - 1) * math.exp(-2 * k * k * lam * lam) for k in range(1, 101))
    return d, max(0.0, min(1.0, p))


def summary(name, listed, unlisted):
    u, z, p_mw = mann_whitney(listed, unlisted)
    d, p_ks = ks_two_sample(listed, unlisted)
    return {
        "measure": name,
        "n": [len(listed), len(unlisted)],
        "listed": {"median": statistics.median(listed), "p95": pct(listed, 95), "p99": pct(listed, 99),
                   "mean": statistics.fmean(listed)},
        "unlisted": {"median": statistics.median(unlisted), "p95": pct(unlisted, 95), "p99": pct(unlisted, 99),
                     "mean": statistics.fmean(unlisted)},
        "median_difference_ms": statistics.median(listed) - statistics.median(unlisted),
        "mann_whitney": {"U": u, "z": z, "p": p_mw},
        "ks": {"D": d, "p": p_ks},
    }


# --- the harness ----------------------------------------------------------------------

def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def sh(*args, check=True, capture=True):
    return subprocess.run(args, check=check, text=True,
                          stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)


class Stack:
    def __init__(self, run, jar, workdir):
        self.run, self.jar, self.workdir = run, jar, workdir
        self.pg = f"{PREFIX}-pg-{run}"
        self.redis = f"{PREFIX}-redis-{run}"
        self.pg_port, self.redis_port = free_port(), free_port()
        self.server = None
        self.log = None

    def start_containers(self):
        for name in (self.pg, self.redis):
            if sh("docker", "inspect", name, check=False).returncode == 0:
                raise SystemExit(f"a container called {name} already exists; not touching it")
        sh("docker", "run", "-d", "--name", self.pg, "-p", f"127.0.0.1:{self.pg_port}:5432",
           "-e", "POSTGRES_USER=almira", "-e", "POSTGRES_PASSWORD=measure", "-e", "POSTGRES_DB=almira",
           "-e", "POSTGRES_INITDB_ARGS=--data-checksums",
           "-v", f"{ROOT}/infra/postgres-init:/docker-entrypoint-initdb.d:ro", "postgres:16-alpine")
        sh("docker", "run", "-d", "--name", self.redis, "-p", f"127.0.0.1:{self.redis_port}:6379", "redis:7-alpine")
        for _ in range(90):
            ready = sh("docker", "exec", self.pg, "psql", "-U", "almira", "-d", "almira", "-tAc",
                       "select 1 from pg_roles where rolname='almira_app'", check=False)
            if ready.returncode == 0 and "1" in ready.stdout:
                time.sleep(2)
                return
            time.sleep(1)
        raise SystemExit("postgres did not become ready")

    def start_server(self, port, smtp_port, allowlist, extra_args, settle_timeout):
        env = dict(os.environ)
        env.update({
            "ALMIRA_ENV": "development",
            "ALMIRA_PORT": str(port),
            "ALMIRA_DB_URL": f"jdbc:postgresql://127.0.0.1:{self.pg_port}/almira",
            "ALMIRA_DB_OWNER_USER": "almira", "ALMIRA_DB_OWNER_PASSWORD": "measure",
            "ALMIRA_DB_APP_USER": "almira_app", "ALMIRA_DB_APP_PASSWORD": "app_dev_password",
            "ALMIRA_REDIS_HOST": "127.0.0.1", "ALMIRA_REDIS_PORT": str(self.redis_port),
            "ALMIRA_STORAGE_ROOT": os.path.join(self.workdir, "documents"),
            "ALMIRA_SIGN_IN_CHANNELS": "email",
            "ALMIRA_ALPHA_EMAIL_ALLOWLIST": ",".join(allowlist),
            "ALMIRA_PROVIDER_EMAIL_MODE": "live",
            "ALMIRA_PROVIDER_EMAIL_SMTP_HOST": "127.0.0.1",
            "ALMIRA_PROVIDER_EMAIL_SMTP_PORT": str(smtp_port),
            "ALMIRA_PROVIDER_EMAIL_SMTP_FROM": "Almira <codes@almira.test>",
            "ALMIRA_PROVIDER_EMAIL_SMTP_START_TLS": "false",
            "ALMIRA_OTP_SEND_TIMEOUT": settle_timeout,
            "ALMIRA_MEASUREMENT_ENABLED": "false",
        })
        env.pop("ALMIRA_TEST_DB_URL", None)
        self.log = open(os.path.join(self.workdir, f"server-{port}.log"), "w")
        self.server = subprocess.Popen(
            ["java", "-jar", self.jar, "--server.tomcat.max-keep-alive-requests=-1", *extra_args],
            env=env, stdout=self.log, stderr=subprocess.STDOUT, cwd=self.workdir,
        )
        deadline = time.time() + 180
        while time.time() < deadline:
            if self.server.poll() is not None:
                raise SystemExit(f"the application stopped; see {self.log.name}")
            try:
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
                c.request("GET", "/health")
                if c.getresponse().status == 200:
                    return
            except OSError:
                pass
            time.sleep(1)
        raise SystemExit(f"the application did not answer /health; see {self.log.name}")

    def stop_server(self):
        if self.server and self.server.poll() is None:
            self.server.terminate()
            try:
                self.server.wait(30)
            except subprocess.TimeoutExpired:
                self.server.kill()
        if self.log:
            self.log.close()
        self.server = None

    def rls_suite(self):
        sh("docker", "cp", f"{ROOT}/db/tests/rls_privacy_test.sql", f"{self.pg}:/tmp/rls.sql")
        r = sh("docker", "exec", "-e", "PGPASSWORD=app_dev_password", self.pg, "psql", "-h", "127.0.0.1",
               "-U", "almira_app", "-d", "almira", "-v", "ON_ERROR_STOP=1", "-q", "-f", "/tmp/rls.sql", check=False)
        return r.returncode, r.stdout

    def owner_sql(self, sql):
        return sh("docker", "exec", self.pg, "psql", "-U", "almira", "-d", "almira", "-tAc", sql).stdout.strip()

    def remove(self):
        self.stop_server()
        sh("docker", "rm", "-f", self.pg, self.redis, check=False)


class Client:
    def __init__(self, port):
        self.port = port
        self.conn = http.client.HTTPConnection("127.0.0.1", port, timeout=30)

    def call(self, method, path, body=None):
        payload = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"} if payload is not None else {}
        for attempt in (1, 2):
            try:
                start = time.perf_counter()
                self.conn.request(method, path, body=payload, headers=headers)
                resp = self.conn.getresponse()
                data = resp.read()
                elapsed = (time.perf_counter() - start) * 1000.0
                return resp.status, resp.getheaders(), data, elapsed
            except (http.client.HTTPException, OSError):
                self.conn.close()
                self.conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
                if attempt == 2:
                    raise


VARYING_HEADERS = {"date"}


def shape(status, headers, data):
    """Everything about a response a caller could compare, with the values that must vary taken out."""
    text = data.decode(errors="replace")
    text = re.sub(r'"requestId":"[0-9a-f-]{36}"', '"requestId":"#"', text)
    text = re.sub(r'"at":"[^"]*"', '"at":"#"', text)
    text = re.sub(r"[0-9]+ seconds", "N seconds", text)
    text = re.sub(r'"retryAfterSeconds":[0-9]+', '"retryAfterSeconds":N', text)
    hdrs = sorted((k.lower(), v if k.lower() != "retry-after" else "N") for k, v in headers
                  if k.lower() not in VARYING_HEADERS)
    return json.dumps([status, hdrs, text])


def differences(listed_shapes, unlisted_shapes):
    return sorted(set(listed_shapes) ^ set(unlisted_shapes))


def phase_a(client, listed, unlisted, pairs, warmup):
    samples = {"listed": [], "unlisted": []}
    shapes = {"listed": set(), "unlisted": set()}
    sizes = {"listed": set(), "unlisted": set()}
    statuses = {"listed": set(), "unlisted": set()}
    ids = []

    def one(group, address, keep):
        status, headers, data, ms = client.call("POST", "/api/v1/auth/otp/email/request", {"email": address})
        if keep:
            samples[group].append(ms)
            shapes[group].add(shape(status, headers, data))
            sizes[group].add(len(data))
            statuses[group].add(status)
        return status, data

    for i in range(warmup):
        one("listed", listed[i % len(listed)], False)
        one("unlisted", unlisted[i % len(unlisted)], False)
    for i in range(pairs):
        order = [("listed", listed[i % len(listed)]), ("unlisted", unlisted[i % len(unlisted)])]
        if i % 2:
            order.reverse()
        for group, address in order:
            status, data = one(group, address, True)
            if status != 200:
                raise SystemExit(f"phase A: {group} request answered {status}: {data[:200]!r}")
    return samples, shapes, sizes, statuses


def phase_b(client, listed, unlisted, pairs, settle_ms):
    first_sent = {"listed": [], "unlisted": []}
    last_sending = {"listed": [], "unlisted": []}
    polls = {"listed": [], "unlisted": []}
    shapes = {"listed": set(), "unlisted": set()}
    for i in range(pairs):
        order = [("listed", listed[i % len(listed)]), ("unlisted", unlisted[i % len(unlisted)])]
        if i % 2:
            order.reverse()
        for group, address in order:
            t0 = time.perf_counter()
            status, headers, data, _ = client.call("POST", "/api/v1/auth/otp/email/request", {"email": address})
            if status != 200:
                raise SystemExit(f"phase B: request answered {status}")
            request_id = json.loads(data)["requestId"]
            n = 0
            seen_sending = 0.0
            while True:
                elapsed = (time.perf_counter() - t0) * 1000.0
                if elapsed < settle_ms - 150:
                    time.sleep(0.1)
                else:
                    time.sleep(0.005)
                before = (time.perf_counter() - t0) * 1000.0
                s, h, d, _ = client.call("GET", f"/api/v1/auth/otp/email/delivery/{request_id}")
                n += 1
                shapes[group].add(shape(s, h, d))
                state = json.loads(d).get("status") if s == 200 else f"http {s}"
                if state == "sending":
                    seen_sending = before
                    continue
                if state != "sent":
                    raise SystemExit(f"phase B: status {state}")
                first_sent[group].append(before)
                last_sending[group].append(seen_sending)
                polls[group].append(n)
                break
    return first_sent, last_sending, polls, shapes


def phase_c(client, listed, unlisted):
    """Everything else, on a server with the packaged cooldown and caps. Returns (per-step equal?, detail)."""
    results = []

    def both(label, fn):
        a = fn(listed)
        b = fn(unlisted)
        results.append({"step": label, "equal": a == b, "listed": a if a != b else None, "unlisted": b if a != b else None})

    def req(addr):
        s, h, d, _ = client.call("POST", "/api/v1/auth/otp/email/request", {"email": addr})
        return s, h, d

    ids = {}

    def first(addr):
        s, h, d = req(addr)
        ids[addr] = json.loads(d).get("requestId")
        return shape(s, h, d)

    both("request", first)
    both("asking again at once (cooldown)", lambda a: shape(*req(a)))
    both("a stale request id", lambda a: shape(*client.call("POST", "/api/v1/auth/otp/email/verify",
                                                               {"email": a, "code": "123456", "requestId": "not-the-request"})[:3]))
    for k in range(1, 6):
        both(f"wrong code {k} of 5", lambda a: shape(*client.call("POST", "/api/v1/auth/otp/email/verify",
                                                                  {"email": a, "code": "000000", "requestId": ids[a]})[:3]))
    both("a code after the lock", lambda a: shape(*client.call("POST", "/api/v1/auth/otp/email/verify",
                                                               {"email": a, "code": "111111", "requestId": ids[a]})[:3]))
    both("delivery status of its id", lambda a: shape(*client.call("GET", f"/api/v1/auth/otp/email/delivery/{ids[a]}")[:3]))
    both("an unknown request id", lambda a: shape(*client.call("GET", "/api/v1/auth/otp/email/delivery/00000000-0000-4000-8000-000000000000")[:3]))
    both("the sign-in contact", lambda a: shape(*client.call("GET", "/api/v1/auth/otp/contact")[:3]))
    return results


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pairs", type=int, default=500)
    ap.add_argument("--status-pairs", type=int, default=500)
    ap.add_argument("--warmup", type=int, default=100)
    ap.add_argument("--send-timeout-ms", type=int, default=200)
    ap.add_argument("--jar", default=None)
    ap.add_argument("--out", default=os.path.join(tempfile.gettempdir(), "sign-in-timing.json"))
    ap.add_argument("--keep", action="store_true", help="leave the containers running")
    args = ap.parse_args()

    if not shutil.which("docker") or not shutil.which("java"):
        print("needs docker and java", file=sys.stderr)
        return 2
    jar = args.jar
    if jar is None:
        libs = os.path.join(ROOT, "backend", "build", "libs")
        found = [f for f in os.listdir(libs) if f.endswith(".jar") and not f.endswith("-plain.jar")] if os.path.isdir(libs) else []
        if not found:
            print("no application jar; run (cd backend && ./gradlew bootJar) or pass --jar", file=sys.stderr)
            return 2
        jar = os.path.join(libs, found[0])

    run = f"{int(time.time())}-{random.randint(1000, 9999)}"
    workdir = tempfile.mkdtemp(prefix="sign-in-timing-")
    stack = Stack(run, os.path.abspath(jar), workdir)
    sink = SmtpSink()
    threading.Thread(target=sink.serve_forever, daemon=True).start()
    listed = [f"alpha.tester{i}@example.test" for i in range(5)]
    unlisted = [f"stranger{i}@example.test" for i in range(5)]
    listed_c, unlisted_c = "alpha.fresh@example.test", "stranger.fresh@example.test"
    settle_ms = args.send_timeout_ms + 1000
    report = {"run": run, "config": vars(args), "settle_ms": settle_ms}
    try:
        print(f"containers {stack.pg} {stack.redis}")
        stack.start_containers()

        port = free_port()
        print(f"application on 127.0.0.1:{port} (cooldown and caps lifted)")
        stack.start_server(port, sink.server_address[1], listed + [listed_c], [
            "--almira.otp.resend-cooldown=0s", "--almira.otp.max-per-hour=1000000",
            "--almira.otp.max-per-ip-per-hour=1000000",
        ], f"{args.send_timeout_ms}ms")
        client = Client(port)

        print(f"phase A: {args.pairs} pairs on the request path")
        a, a_shapes, a_sizes, a_status = phase_a(client, listed, unlisted, args.pairs, args.warmup)
        report["A"] = {
            "latency": summary("request latency (ms)", a["listed"], a["unlisted"]),
            "field_differences": differences(a_shapes["listed"], a_shapes["unlisted"]),
            "body_sizes": {k: sorted(v) for k, v in a_sizes.items()},
            "statuses": {k: sorted(v) for k, v in a_status.items()},
        }
        time.sleep(3)
        emails_after_a = list(sink.recipients)
        report["A"]["emails"] = {
            "to_listed": sum(1 for r in emails_after_a if r in listed),
            "to_unlisted": sum(1 for r in emails_after_a if r in unlisted),
        }

        print(f"phase B: {args.status_pairs} pairs through the delivery status")
        fs, ls, polls, b_shapes = phase_b(client, listed, unlisted, args.status_pairs, settle_ms)
        report["B"] = {
            "first_seen_sent": summary("first poll seeing sent, ms after the request", fs["listed"], fs["unlisted"]),
            "last_seen_sending": summary("last poll seeing sending, ms after the request", ls["listed"], ls["unlisted"]),
            "polls": summary("polls until sent", [float(x) for x in polls["listed"]], [float(x) for x in polls["unlisted"]]),
            "field_differences": differences(b_shapes["listed"], b_shapes["unlisted"]),
            "distinct_poll_shapes": len(b_shapes["listed"] | b_shapes["unlisted"]),
        }
        time.sleep(3)
        report["worker"] = {
            "emails_to_listed": sum(1 for r in sink.recipients if r in listed),
            "emails_to_unlisted": sum(1 for r in sink.recipients if r in unlisted),
            "records": stack.owner_sql("select string_agg(status || '=' || n, ',' order by status) from "
                                       "(select status, count(*) n from sign_in_code_emails group by status) s"),
            "bodies_left": stack.owner_sql("select count(*) from sign_in_code_email_bodies"),
        }
        stack.stop_server()

        port = free_port()
        print(f"phase C: restarted on 127.0.0.1:{port} with the packaged cooldown and caps")
        stack.start_server(port, sink.server_address[1], listed + [listed_c], [], f"{args.send_timeout_ms}ms")
        report["C"] = phase_c(Client(port), listed_c, unlisted_c)
        stack.stop_server()

        rc, out = stack.rls_suite()
        report["rls_suite"] = {"exit": rc, "passed": "ALL PRIVACY ASSERTIONS PASSED" in out,
                               "tail": out.strip().splitlines()[-5:]}
    finally:
        sink.shutdown()
        if args.keep:
            stack.stop_server()
            print(f"kept {stack.pg} {stack.redis}")
        else:
            stack.remove()

    samples_path = args.out
    with open(samples_path, "w") as f:
        json.dump({"report": report, "samples": {
            "A": a, "B_first_seen_sent": fs, "B_last_seen_sending": ls, "B_polls": polls,
        }}, f, indent=1)

    def row(s):
        return (f"| {s['measure']} | {s['listed']['median']:.2f} / {s['unlisted']['median']:.2f} "
                f"| {s['listed']['p95']:.2f} / {s['unlisted']['p95']:.2f} "
                f"| {s['listed']['p99']:.2f} / {s['unlisted']['p99']:.2f} "
                f"| {s['mann_whitney']['p']:.3f} | {s['ks']['D']:.3f} / {s['ks']['p']:.3f} |")

    print()
    print("| measure (listed / unlisted) | median | p95 | p99 | Mann-Whitney p | KS D / p |")
    print("|---|---|---|---|---|---|")
    for s in (report["A"]["latency"], report["B"]["first_seen_sent"], report["B"]["last_seen_sending"], report["B"]["polls"]):
        print(row(s))
    print()
    print("A field differences:", report["A"]["field_differences"] or "none",
          "| body sizes", report["A"]["body_sizes"], "| statuses", report["A"]["statuses"])
    print("A emails:", report["A"]["emails"])
    print("B field differences:", report["B"]["field_differences"] or "none",
          "| distinct poll shapes", report["B"]["distinct_poll_shapes"])
    print("worker:", report["worker"])
    print("C:", "all equal" if all(r["equal"] for r in report["C"]) else [r for r in report["C"] if not r["equal"]])
    print("RLS suite:", report["rls_suite"])
    print("samples:", samples_path)

    timing_ps = [report["A"]["latency"]["mann_whitney"]["p"], report["A"]["latency"]["ks"]["p"],
                 report["B"]["first_seen_sent"]["mann_whitney"]["p"], report["B"]["first_seen_sent"]["ks"]["p"]]
    clean = (min(timing_ps) >= 0.01 and not report["A"]["field_differences"] and not report["B"]["field_differences"]
             and all(r["equal"] for r in report["C"]) and report["worker"]["emails_to_unlisted"] == 0)
    return 0 if clean else 1


if __name__ == "__main__":
    sys.exit(main())
