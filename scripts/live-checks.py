"""Checks the deployed site through its host (Day 35, C35.16 and C35.17): the certificate, the pages
and the redirect, the task ports closed to the internet, and a new user's path through the API.
Every check runs even after one fails; a check that needs an earlier result records a skip.
Google sign-in (C35.17) is checked by hand, and C35.18-C35.20 are not this script's.

    python scripts/live-checks.py --host <ALB DNS name or domain> [--insecure]
                                  [--out docs/dashboard/deployment.json]
                                  [--task-ip IP ...] [--ports 3000,8080,8081,9090]

Prints one line per check and exits 1 when any check fails.
"""
import argparse
import datetime
import json
import os
import secrets
import socket
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

REGION = os.environ.get("AWS_REGION") or os.environ.get("AWS_DEFAULT_REGION") or "eu-west-1"
TIMEOUT = 10
# The profile the checks save: UpdateProfileRequest wants 5-20 distinct skills, and top matches
# wants a saved profile
PROFILE = {
    "skills": ["Java", "SQL", "Docker", "Spring Boot", "PostgreSQL"], "category": "software_engineering",
    "preferredCity": "Amsterdam", "workMode": "hybrid", "experienceLevel": "medior",
    "employmentType": "full_time", "salaryPreference": 45000.00}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # The http:// check needs the redirect itself, so a redirect is an answer, never followed
    def redirect_request(self, *args):
        return None


def make_opener(insecure):
    # --insecure is for the self-signed certificate made when the domain is empty: no client trusts it
    context = ssl.create_default_context()
    if insecure:
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
    return urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=context))


OPENER = make_opener(False)


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def aws(*args):
    done = subprocess.run(["aws", *args, "--region", REGION, "--output", "json"],
                          capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(f"aws {args[0]} {args[1]} failed: {done.stderr.strip()[-200:]}")
    return json.loads(done.stdout)


def request(url, method="GET", body=None, cookie=None):
    # (status, headers, text); an HTTP error is an answer too, not a crash
    headers = {"Cookie": cookie} if cookie else {}
    if body is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(body).encode()
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with OPENER.open(req, timeout=TIMEOUT) as response:
            return response.status, response.headers, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read().decode()


def expect(code, low=200, high=200):
    if not low <= code <= high:
        raise RuntimeError(f"HTTP {code}, wanted {low}-{high}")
    return f"HTTP {code}"


def certificate(host, insecure):
    arns = [c["CertificateArn"] for c in aws("acm", "list-certificates")["CertificateSummaryList"]
            if c["DomainName"] == host]
    if not arns:
        raise RuntimeError("no certificate for the host")
    status, kind = aws("acm", "describe-certificate", "--certificate-arn", arns[0],
                       "--query", "Certificate.[Status,Type]")
    if status != "ISSUED":
        raise RuntimeError(f"certificate status {status}, wanted ISSUED")
    if insecure and kind != "IMPORTED":
        raise RuntimeError(f"certificate type {kind}, wanted IMPORTED with --insecure")
    return f"ISSUED, {kind}" if insecure else status


def jobs(base, state):
    code, _, text = request(base + "/api/jobs?size=100")
    expect(code)
    page = json.loads(text)
    if page["totalElements"] != 24:
        raise RuntimeError(f"totalElements {page['totalElements']}, wanted 24")
    state["job"] = page["content"][0]
    return "24 jobs"


def redirect(domain):
    code, headers, _ = request(f"http://{domain}/")
    location = headers.get("Location") or ""
    if code not in (301, 302, 307, 308) or not location.startswith("https://"):
        raise RuntimeError(f"HTTP {code}, Location {location!r}")
    return f"HTTP {code} to https"


def open_ports(ips, ports, timeout=3):
    """The "ip:port" pairs that accept a TCP connection within the timeout."""
    opened = []
    for ip in ips:
        for port in ports:
            try:
                with socket.create_connection((ip, port), timeout=timeout):
                    opened.append(f"{ip}:{port}")
            except OSError:
                pass
    return opened


def task_ips():
    # The public IPs of the running tasks, found through their network interfaces
    arns = aws("ecs", "list-tasks", "--cluster", "jobmatch")["taskArns"]
    if not arns:
        return []
    tasks = aws("ecs", "describe-tasks", "--cluster", "jobmatch", "--tasks", *arns)["tasks"]
    eni_ids = [d["value"] for t in tasks for a in t["attachments"] for d in a["details"]
               if d["name"] == "networkInterfaceId"]
    if not eni_ids:
        return []
    enis = aws("ec2", "describe-network-interfaces", "--network-interface-ids", *eni_ids)
    return [e["Association"]["PublicIp"] for e in enis["NetworkInterfaces"] if "Association" in e]


def ports_closed(args):
    ips = args.task_ip or task_ips()
    if not ips:
        raise RuntimeError("no task IPs found")
    opened = open_ports(ips, args.ports)
    if opened:
        raise RuntimeError("; ".join(f"{pair} open" for pair in opened))
    return f"{len(ips)} task IPs, every port closed"


def register(base, state):
    # The password is made per run and only sent; no result or log line carries it
    state["email"] = f"live-{uuid.uuid4().hex[:12]}@example.com"
    state["password"] = secrets.token_urlsafe(12) + "Aa1!"
    code, _, _ = request(base + "/api/auth/register", "POST", {
        "name": "Live Check", "email": state["email"], "password": state["password"],
        "acceptedTerms": True})
    return expect(code, 200, 299)


def login(base, state):
    code, headers, _ = request(base + "/api/auth/login", "POST",
                               {"email": state["email"], "password": state["password"]})
    expect(code)
    cookies = headers.get_all("Set-Cookie") or []
    if not cookies:
        raise RuntimeError("no Set-Cookie")
    for cookie in cookies:
        flags = {part.strip().lower() for part in cookie.split(";")}
        if not {"secure", "httponly", "samesite=lax"} <= flags:
            raise RuntimeError(f"cookie {cookie.split('=')[0]} lacks Secure, HttpOnly or SameSite=Lax")
    state["cookie"] = "; ".join(cookie.split(";")[0] for cookie in cookies)
    return "cookie with Secure, HttpOnly, SameSite=Lax"


def profile_write(base, state):
    code, _, _ = request(base + "/api/profile", "PUT", PROFILE, cookie=state["cookie"])
    return expect(code)


def search(base, state):
    word = state["job"]["title"].split()[0]
    code, _, text = request(base + "/api/jobs?" + urllib.parse.urlencode({"q": word}))
    expect(code)
    if not json.loads(text)["content"]:
        raise RuntimeError(f"no jobs for {word!r}")
    return f"jobs for {word!r}"


def saved_list(base, state):
    code, _, text = request(base + "/api/saved-jobs", cookie=state["cookie"])
    expect(code)
    ids = [item["postingId"] for item in json.loads(text)["content"]]
    if state["job"]["postingId"] not in ids:
        raise RuntimeError("the saved job is not listed")
    return f"{len(ids)} saved"


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", required=True, help="the ALB's DNS name or a domain")
    parser.add_argument("--insecure", action="store_true",
                        help="accept a certificate no client trusts, the self-signed one made "
                             "when domain is empty")
    parser.add_argument("--out", default="docs/dashboard/deployment.json")
    parser.add_argument("--task-ip", action="append", help="repeatable; default: found in ECS")
    parser.add_argument("--ports", type=lambda text: [int(p) for p in text.split(",")],
                        default="3000,8080,8081,9090")
    args = parser.parse_args(argv)
    global OPENER
    OPENER = make_opener(args.insecure)
    base = f"https://{args.host}"
    state = {}
    results = []
    passed = set()

    def check(criterion, name, fn, needs=()):
        blocked = [n for n in needs if n not in passed]
        if blocked:
            ok, detail = False, f"skipped: {blocked[0]} did not pass"
        else:
            try:
                ok, detail = True, fn()
            except Exception as error:  # a failed check is a result; the rest still run
                ok, detail = False, f"{type(error).__name__}: {error}"
        if ok:
            passed.add(name)
        results.append({"criterion": criterion, "name": name, "ok": ok, "detail": detail,
                        "at": now()})
        print(f"{'ok  ' if ok else 'FAIL'} {criterion} {name}: {detail}")

    check("C35.16", "certificate", lambda: certificate(args.host, args.insecure))
    check("C35.16", "root page", lambda: expect(request(base + "/")[0]))
    check("C35.16", "jobs total", lambda: jobs(base, state))
    check("C35.16", "http redirects to https", lambda: redirect(args.host))
    check("C35.16", "task ports closed", lambda: ports_closed(args))
    check("C35.17", "register", lambda: register(base, state))
    check("C35.17", "login", lambda: login(base, state), needs=("register",))
    check("C35.17", "profile read", lambda: expect(request(
        base + "/api/profile", cookie=state["cookie"])[0]), needs=("login",))
    check("C35.17", "profile write", lambda: profile_write(base, state), needs=("login",))
    check("C35.17", "search", lambda: search(base, state), needs=("jobs total",))
    check("C35.17", "save a job", lambda: expect(request(
        base + "/api/saved-jobs", "POST", {"postingId": state["job"]["postingId"]},
        cookie=state["cookie"])[0], 200, 299), needs=("login", "jobs total"))
    check("C35.17", "saved list", lambda: saved_list(base, state), needs=("save a job",))
    check("C35.17", "move saved job to applied", lambda: expect(request(
        base + f"/api/saved-jobs/{state['job']['postingId']}", "PATCH", {"newState": "APPLIED"},
        cookie=state["cookie"])[0]), needs=("save a job",))
    check("C35.17", "top matches", lambda: expect(request(
        base + "/api/jobs/top-matches", cookie=state["cookie"])[0]), needs=("login",))

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps({"domain": args.host, "checkedAt": now(), "checks": results},
                           indent=2) + "\n")
    return 0 if all(r["ok"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
