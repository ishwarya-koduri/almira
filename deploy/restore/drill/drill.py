#!/usr/bin/env python3
"""
Seeds a drill stack with data a restore can get wrong, and later checks that a
restored stack gives every piece of it back — not "the tables have rows", but
"the thing each person put in comes out, byte for byte, through the API".

    python3 deploy/restore/drill/drill.py seed   --base http://127.0.0.1:18090 --compose-args "…" --state state.json
    python3 deploy/restore/drill/drill.py verify --base http://127.0.0.1:18091 --compose-args "…" --state state.json

What is seeded, and which layer each one proves survived:

  two members, one private holding  row-level security: the second member must still
                                    see a different total after the restore
  a full account number             server-side envelope encryption: reveals only if the
                                    household key rows AND the KMS key both came back
  an uploaded document              the documents volume and its encryption: the bytes
                                    downloaded must hash to what was uploaded
  sealed zero-knowledge values,     the client-side envelopes: each must OPEN with the
  including an empty one            passphrase, in a real implementation of the client
                                    (ZkEnvelope.java, checked against docs/12's vector)

Sign-in codes: taken from the response if the server echoes them, otherwise from
the application log via `docker compose logs`. Drill stacks only — it writes.
"""
import argparse, hashlib, json, os, re, shlex, subprocess, sys, time, urllib.request, uuid

HERE = os.path.dirname(os.path.abspath(__file__))
PASSPHRASE = "a quiet afternoon in kakinada "  # trailing space on purpose (docs/12)
FAILS = []


def call(base, method, path, token=None, body=None, raw=None, headers=None):
    data = None
    hdrs = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        hdrs["Content-Type"] = "application/json"
    if raw is not None:
        data = raw
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(base + path, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def jcall(*a, **k):
    status, content, _ = call(*a, **k)
    try:
        return status, json.loads(content or b"null")
    except ValueError:
        return status, None


def code_for(response, compose_args):
    if response.get("developmentCode"):
        return response["developmentCode"]
    logs = subprocess.run(["docker", "compose", *shlex.split(compose_args), "logs", "--since", "2m", "app"],
                          capture_output=True, text=True).stdout
    codes = re.findall(r"DEV OTP for \S+ : (\d+)", logs)
    if not codes:
        sys.exit("No sign-in code in the response or the log.")
    return codes[-1]


def challenge_for(base, path, compose_args, token=None, body=None):
    """A fresh code. Waits out the resend cooldown rather than reusing an old code
    from the log, which would test the rate limit instead of the restore."""
    for _ in range(4):
        status, challenge = jcall(base, "POST", path, token=token, body=body)
        if status == 200:
            return challenge
        time.sleep(31)
    sys.exit(f"could not get a code from {path}: {status} {challenge}")


def sign_in(base, phone, compose_args):
    challenge = challenge_for(base, "/api/v1/auth/otp/request", compose_args, body={"phone": phone})
    code = code_for(challenge, compose_args)
    status, session = jcall(base, "POST", "/api/v1/auth/otp/verify",
                            body={"phone": phone, "code": code, "requestId": challenge.get("requestId")})
    if status != 200:
        sys.exit(f"sign-in failed for {phone}: {status} {session}")
    return session["accessToken"]


def step_up(base, token, compose_args):
    challenge = challenge_for(base, "/api/v1/auth/step-up/request", compose_args, token=token)
    code = code_for(challenge, compose_args)
    status, body = jcall(base, "POST", "/api/v1/auth/step-up/verify", token=token,
                         body={"code": code, "requestId": challenge.get("requestId")})
    if status != 200:
        sys.exit(f"step-up failed: {status} {body} (challenge: {challenge})")


def zk(*args):
    out = subprocess.run(["java", os.path.join(HERE, "ZkEnvelope.java"), *args], capture_output=True, text=True)
    return out.returncode, out.stdout.rstrip("\n"), out.stderr.strip()


def check(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("" if ok else f"  — {detail}"))
    if not ok:
        FAILS.append(label)


def total(base, token, hid):
    _, d = jcall(base, "GET", f"/api/v1/households/{hid}/dashboard?scope=household", token=token)
    return str(d.get("totalAssets")) if d else None


def seed(a):
    stamp = int(time.time())
    phone_a, phone_b = f"+9190{stamp % 100000000:08d}", f"+9180{stamp % 100000000:08d}"
    A = sign_in(a.base, phone_a, a.compose_args)
    B = sign_in(a.base, phone_b, a.compose_args)

    _, hh = jcall(a.base, "POST", "/api/v1/households", token=A,
                  body={"name": f"Restore drill {stamp}", "mode": "family", "defaultVisibility": "private", "displayName": "Drill A"})
    hid = hh["id"]
    _, member_b = jcall(a.base, "POST", f"/api/v1/households/{hid}/members", token=A,
                        body={"displayName": "Drill B", "relationship": "spouse"})
    _, invite = jcall(a.base, "POST", f"/api/v1/households/{hid}/invitations", token=A,
                      body={"memberId": member_b["id"], "phone": phone_b, "role": "admin"})
    jcall(a.base, "POST", "/api/v1/invitations/accept", token=B, body={"token": invite["token"]})

    _, taxonomy = jcall(a.base, "GET", f"/api/v1/households/{hid}/taxonomy", token=A)
    gold = next(t["id"] for c in taxonomy for t in c["types"] if t["code"] == "gold_physical")
    _, shared = jcall(a.base, "POST", f"/api/v1/households/{hid}/investments", token=A,
                      body={"typeId": gold, "title": "Shared gold", "investedAmount": 100000, "visibility": "household"})
    _, private = jcall(a.base, "POST", f"/api/v1/households/{hid}/investments", token=A,
                       body={"typeId": gold, "title": "Private gold", "investedAmount": 900000, "visibility": "private"})

    number = f"50100{stamp % 10**9:09d}"
    _, account = jcall(a.base, "POST", f"/api/v1/households/{hid}/accounts", token=A,
                       body={"label": "Drill savings", "number": number, "storeFullNumber": True})

    document = os.urandom(4096) + b"restore drill document"
    boundary = uuid.uuid4().hex
    multipart = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"drill.bin\"\r\n"
                 f"Content-Type: application/octet-stream\r\n\r\n").encode() + document + f"\r\n--{boundary}--\r\n".encode()
    status, doc_body, _ = call(a.base, "POST", f"/api/v1/households/{hid}/documents?docType=other", token=A,
                               raw=multipart, headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    doc = json.loads(doc_body)

    rc, key_json, err = zk("key", PASSPHRASE)
    key = json.loads(key_json)
    status, _ = jcall(a.base, "PUT", f"/api/v1/households/{hid}/e2e/key", token=A, body=key)
    assert status == 200, f"key upload {status}"
    sealed = []
    for field, plaintext in [("locker_address", "Locker 12, ఖజానా, Kakinada "),
                             ("nominee_note", "The will is with Ramana garu."),
                             ("empty_on_purpose", "")]:
        aad = f"{hid.lower()}|investment|{private['id'].lower()}|{field}"
        rc, ciphertext, err = zk("seal", PASSPHRASE, key["kdfSalt"], str(key["iterations"]), key["wrappedKey"], aad, plaintext)
        status, _ = jcall(a.base, "PUT", f"/api/v1/households/{hid}/e2e/values/investment/{private['id']}/{field}",
                          token=A, body={"ciphertext": ciphertext, "keyVersion": 1})
        assert status == 200, f"seal {field}: {status}"
        sealed.append({"recordId": private["id"], "fieldKey": field, "plaintext": plaintext})

    state = {"phone_a": phone_a, "phone_b": phone_b, "household": hid,
             "total_a": total(a.base, A, hid), "total_b": total(a.base, B, hid),
             "account": account["id"], "number": number,
             "document": doc["id"], "document_sha256": hashlib.sha256(document).hexdigest(),
             "sealed": sealed}
    json.dump(state, open(a.state, "w"), indent=2, ensure_ascii=False)
    print(f"Seeded household {hid}: totals A={state['total_a']} B={state['total_b']}, "
          f"1 revealable account number, 1 document ({len(document)} bytes), {len(sealed)} sealed values.")
    # Verified with the sessions just used: signing in again inside the 30-second
    # resend window would be refused, and that would test the rate limit, not the data.
    verify(a, state, (A, B))


def verify(a, state=None, sessions=None):
    state = state or json.load(open(a.state))
    hid = state["household"]
    print(f"Verifying household {hid} at {a.base}")
    A, B = sessions or (sign_in(a.base, state["phone_a"], a.compose_args),
                        sign_in(a.base, state["phone_b"], a.compose_args))

    check("the owner's total is what it was", total(a.base, A, hid) == state["total_a"], total(a.base, A, hid))
    check("the second member still sees a different, smaller total (RLS)",
          total(a.base, B, hid) == state["total_b"] and state["total_a"] != state["total_b"], total(a.base, B, hid))

    step_up(a.base, A, a.compose_args)
    status, revealed = jcall(a.base, "POST", f"/api/v1/households/{hid}/accounts/{state['account']}/reveal-number", token=A)
    check("the full account number decrypts (household key + KMS key)",
          status == 200 and revealed and revealed.get("number") == state["number"], f"{status} {revealed}")

    status, ticket = jcall(a.base, "POST", f"/api/v1/households/{hid}/documents/{state['document']}/access", token=A)
    if status == 200:
        s2, content, _ = call(a.base, "GET", f"/api/v1/documents/download?token={ticket['token']}")
        check("the document downloads byte for byte", s2 == 200 and hashlib.sha256(content).hexdigest() == state["document_sha256"],
              f"{s2} sha256 {hashlib.sha256(content).hexdigest()}")
    else:
        check("the document downloads byte for byte", False, f"ticket {status} {ticket}")

    _, e2e = jcall(a.base, "GET", f"/api/v1/households/{hid}/e2e", token=A)
    key = e2e["key"]
    _, values = jcall(a.base, "GET", f"/api/v1/households/{hid}/e2e/values", token=A)
    by_field = {(v["recordId"], v["fieldKey"]): v["ciphertext"] for v in values}
    for s in state["sealed"]:
        ciphertext = by_field.get((s["recordId"], s["fieldKey"]))
        aad = f"{hid.lower()}|investment|{s['recordId'].lower()}|{s['fieldKey']}"
        rc, plain, err = zk("open", PASSPHRASE, key["kdfSalt"], str(key["iterations"]), key["wrappedKey"], aad, ciphertext or "")
        check(f"sealed '{s['fieldKey']}' opens with the passphrase and says what was sealed",
              rc == 0 and plain == s["plaintext"], err or repr(plain))

    print(f"{'ALL CHECKS PASSED' if not FAILS else str(len(FAILS)) + ' FAILED'}")
    sys.exit(1 if FAILS else 0)


p = argparse.ArgumentParser()
p.add_argument("command", choices=["seed", "verify"])
p.add_argument("--base", required=True)
p.add_argument("--compose-args", required=True, help="-p … -f … --env-file …, for reading sign-in codes from logs")
p.add_argument("--state", required=True)
a = p.parse_args()
seed(a) if a.command == "seed" else verify(a)
