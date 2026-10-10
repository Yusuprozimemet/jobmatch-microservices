#!/usr/bin/env python3
"""
Reads plan JSON (terraform show -json) and validates infrastructure criteria.
With --localstack, also checks applied state and resources against specifications.
Fails on C32.2 (bootstrap plan), C32.3 (backend blocks and bootstrap bucket match),
C32.4 (network and database), C32.5 (secret sweep), C32.6 (bus resources), C32.7 (queue policies
in the LocalStack state), C32.8 (alarms), C32.9 (score table on LocalStack), C36.1 (ECR
repositories), C36.2 (ECS cluster and services), C36.3 (secrets), C36.4 (task roles),
C36.5 (ALB and certificate), C36.6 (security groups), C36.7 (health checks), C36.8 (Service
Connect and the compose variables), C36.9 (the service variables), C36.10 (the one-off tasks),
C36.11 (the scaling), C34.3 (the collector sidecar and the telemetry variables), C34.4 (the task
role's telemetry policy), C34.5 (the metrics log group) and C34.8 (the five services' telemetry defaults).
Track B checks C32.4; Track C1 adds scores; Track C2 adds the bus.
Exits 1 with a list of failures, each prefixed with criterion ID.
"""

import argparse
import ipaddress
import json
import re
import sys
from pathlib import Path
from typing import Any, Generator


def find_blocks(content: str, header: str) -> list[str]:
    """Find each block with the given header (e.g. 'backend "s3"' or 'provider "aws"')
    and return the text between its { and the matching } by counting braces.
    header is matched with flexible whitespace via regex.
    """
    blocks = []
    # Build regex that allows flexible whitespace between header parts
    header_pattern = r'\s+'.join(re.escape(p) for p in header.split()) + r'\s*\{'

    pattern = f'({header_pattern})'

    for match in re.finditer(pattern, content, re.DOTALL):
        brace_start = match.end() - 1
        # Count braces to find the matching closing brace
        brace_count = 1
        brace_end = brace_start + 1
        while brace_end < len(content) and brace_count > 0:
            if content[brace_end] == "{":
                brace_count += 1
            elif content[brace_end] == "}":
                brace_count -= 1
            brace_end += 1

        block_content = content[brace_start + 1:brace_end - 1]
        blocks.append(block_content)

    return blocks


def resources(plan: dict) -> Generator[dict, None, None]:
    """Yield every resource dict in planned_values.root_module recursively."""
    if "root_module" not in plan.get("planned_values", {}):
        return

    def walk_module(module: dict):
        if "resources" in module:
            for resource in module["resources"]:
                yield resource
        if "child_modules" in module:
            for child in module["child_modules"]:
                yield from walk_module(child)

    yield from walk_module(plan["planned_values"]["root_module"])


def config_resources(plan: dict) -> Generator[dict, None, None]:
    """Yield every resource dict in configuration.root_module recursively with expressions."""
    if "configuration" not in plan:
        return

    def walk_module(module: dict):
        if "resources" in module:
            for resource in module["resources"]:
                yield resource
        if "module_calls" in module:
            for module_name, module_call in module["module_calls"].items():
                if "module" in module_call:
                    yield from walk_module(module_call["module"])

    yield from walk_module(plan["configuration"]["root_module"])


def check_c32_2(plan: dict) -> list[str]:
    """Check bootstrap plan: S3 bucket, versioning, public access block, encryption, and budget."""
    failures = []

    # Find S3 buckets and related resources
    buckets = []
    versionings = []
    access_blocks = []
    encryptions = []

    for resource in resources(plan):
        if resource["type"] == "aws_s3_bucket":
            buckets.append(resource)
        elif resource["type"] == "aws_s3_bucket_versioning":
            versionings.append(resource)
        elif resource["type"] == "aws_s3_bucket_public_access_block":
            access_blocks.append(resource)
        elif resource["type"] == "aws_s3_bucket_server_side_encryption_configuration":
            encryptions.append(resource)

    # Check exactly one bucket
    if len(buckets) != 1:
        failures.append(f"C32.2 expected exactly 1 aws_s3_bucket, got {len(buckets)}")
        return failures

    bucket = buckets[0]
    bucket_name = bucket["values"].get("bucket", "unknown")

    # Check versioning
    versioning_found = False
    for v in versionings:
        if v["values"].get("bucket") == bucket_name:
            versioning_found = True
            status = v["values"].get("versioning_configuration", [{}])[0].get("status")
            if status != "Enabled":
                failures.append(f"C32.2 {v['address']} (bucket {bucket_name}): status {status}, want Enabled")

    if not versioning_found:
        failures.append(f"C32.2 aws_s3_bucket_versioning for bucket {bucket_name} not found")

    # Check public access block
    access_block_found = False
    for ab in access_blocks:
        if ab["values"].get("bucket") == bucket_name:
            access_block_found = True
            values = ab["values"]
            if not all([
                values.get("block_public_acls"),
                values.get("block_public_policy"),
                values.get("ignore_public_acls"),
                values.get("restrict_public_buckets"),
            ]):
                failures.append(f"C32.2 {ab['address']} (bucket {bucket_name}): not all four blocks true")

    if not access_block_found:
        failures.append(f"C32.2 aws_s3_bucket_public_access_block for bucket {bucket_name} not found")

    # Check encryption
    encryption_found = False
    for enc in encryptions:
        if enc["values"].get("bucket") == bucket_name:
            encryption_found = True
            rules = enc["values"].get("rule", [])
            if not rules:
                failures.append(f"C32.2 {enc['address']} (bucket {bucket_name}): missing or empty sse_algorithm")
            else:
                rule = rules[0]
                apply_default = rule.get("apply_server_side_encryption_by_default", [])
                if not apply_default or not apply_default[0].get("sse_algorithm"):
                    failures.append(f"C32.2 {enc['address']} (bucket {bucket_name}): missing or empty sse_algorithm")

    if not encryption_found:
        failures.append(f"C32.2 aws_s3_bucket_server_side_encryption_configuration for bucket {bucket_name} not found")

    # Check budget
    budgets = [r for r in resources(plan) if r["type"] == "aws_budgets_budget"]
    if len(budgets) != 1:
        failures.append(f"C32.2 expected exactly 1 aws_budgets_budget, got {len(budgets)}")
        return failures

    budget = budgets[0]
    values = budget["values"]
    budget_name = values.get("name", "unknown")

    if values.get("budget_type") != "COST":
        failures.append(f"C32.2 {budget['address']} ({budget_name}): budget_type {values.get('budget_type')}, want COST")

    if values.get("time_unit") != "MONTHLY":
        failures.append(f"C32.2 {budget['address']} ({budget_name}): time_unit {values.get('time_unit')}, want MONTHLY")

    # Check that limit_amount and notification email are variables in config
    config_found = False
    for config_res in config_resources(plan):
        if config_res["type"] == "aws_budgets_budget":
            config_found = True
            config_values = config_res.get("expressions", {})

            # Check limit_amount references a variable
            limit_expr = config_values.get("limit_amount", {})
            if not isinstance(limit_expr, dict) or "references" not in limit_expr:
                failures.append(f"C32.2 {config_res['address']} ({budget_name}): limit_amount does not reference a variable")
            else:
                refs = limit_expr.get("references", [])
                if not any("var." in str(r) for r in refs):
                    failures.append(f"C32.2 {config_res['address']} ({budget_name}): limit_amount does not reference a var.")

            # Check notification has email and it references a variable
            notifications = config_values.get("notification", [])
            if not notifications:
                failures.append(f"C32.2 {config_res['address']} ({budget_name}): no notification block")
            else:
                notification_has_var = False
                for notif in notifications:
                    email_expr = notif.get("subscriber_email_addresses", {})
                    if isinstance(email_expr, dict) and "references" in email_expr:
                        refs = email_expr.get("references", [])
                        if any("var." in str(r) for r in refs):
                            notification_has_var = True
                            break
                if not notification_has_var:
                    failures.append(f"C32.2 {config_res['address']} ({budget_name}): notification subscriber_email_addresses does not reference a var.")

    return failures


def config_with_path(plan: dict) -> Generator[tuple[tuple, dict], None, None]:
    """Yield (module path, resource) for every resource in the configuration."""
    def walk(module: dict, path: tuple):
        for resource in module.get("resources", []):
            yield path, resource
        for name, call in module.get("module_calls", {}).items():
            yield from walk(call.get("module", {}), path + (name,))

    yield from walk(plan.get("configuration", {}).get("root_module", {}), ())


def resolve(plan: dict, path: tuple, references: list[str]) -> set[str]:
    """Follow references through variables and module outputs to full resource addresses.

    Subnet and security group ids are unknown until apply, so the plan's values cannot say
    which subnets the database is in; the configuration's references can.
    """
    def module_at(p: tuple) -> dict:
        module = plan["configuration"]["root_module"]
        for name in p:
            module = module["module_calls"][name]["module"]
        return module

    found = set()
    for ref in references:
        parts = ref.split("[")[0].split(".")
        if parts[0] == "var" and path:
            call = module_at(path[:-1])["module_calls"][path[-1]]
            expr = call.get("expressions", {}).get(parts[1], {})
            found |= resolve(plan, path[:-1], expr.get("references", []))
        elif parts[0] == "module" and len(parts) >= 3:
            sub = path + (parts[1],)
            expr = module_at(sub).get("outputs", {}).get(parts[2], {}).get("expression", {})
            found |= resolve(plan, sub, expr.get("references", []))
        elif parts[0] not in ("var", "module", "local", "data", "each", "count", "path") and len(parts) >= 2:
            found.add("".join(f"module.{name}." for name in path) + ".".join(parts[:2]))
    return found


def check_c32_4(plan: dict) -> list[str]:
    """Check the main plan's database: engine, access, password, private subnets, ingress."""
    failures = []

    instances = [r for r in resources(plan) if r["type"] == "aws_db_instance"]
    if len(instances) != 1:
        return [f"C32.4 expected exactly 1 aws_db_instance, got {len(instances)}"]
    address, values = instances[0]["address"], instances[0]["values"]
    want = {"engine": "postgres", "engine_version": "18", "publicly_accessible": False,
            "manage_master_user_password": True, "password": None}
    for key, expected in want.items():
        got = values.get(key)
        if (str(got) if key == "engine_version" else got) != expected:
            shown = "a value (not printing it)" if key == "password" else got
            failures.append(f"C32.4 {address}: {key} {shown}, want {expected}")

    # Configuration resources by full address, and their references resolved.
    config = {"".join(f"module.{n}." for n in p) + r["address"]: (p, r) for p, r in config_with_path(plan)}

    def refs(addr: str, attr: str, expressions: dict | None = None) -> set[str]:
        path, resource = config[addr]
        expr = (expressions or resource.get("expressions", {})).get(attr, {})
        return resolve(plan, path, expr.get("references", []) if isinstance(expr, dict) else [])

    def planned(addr: str) -> list[dict]:
        return [r for r in resources(plan) if r["address"].split("[")[0] == addr]

    def of_type(kind: str) -> list[str]:
        return [a for a, (_, r) in config.items() if r["type"] == kind]

    db = address.split("[")[0]

    # Subnet group: the private subnets only, none routed to an internet gateway.
    groups = refs(db, "db_subnet_group_name")
    subnets = set().union(*(refs(g, "subnet_ids") for g in groups if g in config))
    if not subnets or any(not s.split(".")[-2] == "aws_subnet" for s in subnets):
        failures.append(f"C32.4 {db}: subnet group subnets {sorted(subnets)}, want aws_subnet resources")
    public_tables = set().union(*(refs(r, "route_table_id") for r in of_type("aws_route")
                                  if any(g.split(".")[-2] == "aws_internet_gateway" for g in refs(r, "gateway_id"))))
    for association in of_type("aws_route_table_association"):
        if refs(association, "subnet_id") & subnets and refs(association, "route_table_id") & public_tables:
            failures.append(f"C32.4 {association}: puts a database subnet behind an internet gateway")
    for subnet in sorted(subnets):
        for instance in planned(subnet):
            if instance["values"].get("map_public_ip_on_launch"):
                failures.append(f"C32.4 {instance['address']}: map_public_ip_on_launch true, want false")

    # Security group: one, no inline rules, one ingress on 5432 from the tasks' group.
    groups = refs(db, "vpc_security_group_ids")
    if len(groups) != 1:
        return failures + [f"C32.4 {db}: security groups {sorted(groups)}, want exactly 1"]
    group = groups.pop()
    for instance in planned(group):
        if instance["values"].get("ingress"):
            failures.append(f"C32.4 {instance['address']}: inline ingress, want none")
    for legacy in of_type("aws_security_group_rule"):
        if group in refs(legacy, "security_group_id"):
            failures.append(f"C32.4 {legacy}: rule on {group}, want only the 5432 ingress rule")
    rules = [r for r in of_type("aws_vpc_security_group_ingress_rule") if group in refs(r, "security_group_id")]
    if len(rules) != 1:
        return failures + [f"C32.4 {group}: {len(rules)} ingress rules, want 1"]
    rule = rules[0]
    for instance in planned(rule):
        v = instance["values"]
        for key, expected in {"from_port": 5432, "to_port": 5432, "ip_protocol": "tcp",
                              "cidr_ipv4": None, "cidr_ipv6": None, "prefix_list_id": None}.items():
            if v.get(key) != expected:
                failures.append(f"C32.4 {instance['address']}: {key} {v.get(key)}, want {expected}")
    sources = refs(rule, "referenced_security_group_id")
    if len(sources) != 1 or not sources.issubset(set(of_type("aws_security_group"))) \
            or not next(iter(sources)).endswith(".tasks") or group in sources:
        failures.append(f"C32.4 {rule}: source {sorted(sources)}, want the tasks security group only")

    return failures


def check_c32_3_text(bootstrap_dir: str, main_dir: str, localstack_dir: str, bootstrap_plan: dict | None = None) -> list[str]:
    """Check backend and skip_* flags in text (grep), and bootstrap bucket matches main backend."""
    failures = []

    # Parse main root backends
    main_backends = parse_backends(main_dir)
    if len(main_backends) != 1:
        failures.append(f"C32.3 main root: expected 1 s3 backend, got {len(main_backends)}")
    elif main_backends[0].get("type") != "s3":
        failures.append(f"C32.3 main root: expected s3 backend, got {main_backends[0].get('type')}")
    else:
        backend = main_backends[0]

        # Check bucket literal
        if "bucket" not in backend:
            failures.append("C32.3 main root backend: bucket not found")

        # Check use_lockfile = true
        if backend.get("use_lockfile") != "true":
            failures.append(f"C32.3 main root backend: use_lockfile {backend.get('use_lockfile')}, want true")

        # Check no dynamodb_table
        if "dynamodb_table" in backend:
            failures.append(f"C32.3 main root backend: has dynamodb_table, should not")

    # Parse localstack root backends
    localstack_backends = parse_backends(localstack_dir)
    if len(localstack_backends) != 1:
        failures.append(f"C32.3 localstack root: expected 1 s3 backend, got {len(localstack_backends)}")
    else:
        ls_backend = localstack_backends[0]

        # Check it has same bucket, key, region, use_lockfile
        if main_backends:
            main_backend = main_backends[0]
            if ls_backend.get("bucket") != main_backend.get("bucket"):
                failures.append(f"C32.3 localstack backend bucket {ls_backend.get('bucket')} != main {main_backend.get('bucket')}")
            if ls_backend.get("key") != main_backend.get("key"):
                failures.append(f"C32.3 localstack backend key {ls_backend.get('key')} != main {main_backend.get('key')}")
            if ls_backend.get("region") != main_backend.get("region"):
                failures.append(f"C32.3 localstack backend region {ls_backend.get('region')} != main {main_backend.get('region')}")
            if ls_backend.get("use_lockfile") != main_backend.get("use_lockfile"):
                failures.append(f"C32.3 localstack backend use_lockfile {ls_backend.get('use_lockfile')} != main {main_backend.get('use_lockfile')}")

        # Check use_path_style = true
        if ls_backend.get("use_path_style") != "true":
            failures.append(f"C32.3 localstack backend: use_path_style {ls_backend.get('use_path_style')}, want true")

    # Check bootstrap has no backend
    bootstrap_backends = parse_backends(bootstrap_dir)
    if bootstrap_backends:
        failures.append(f"C32.3 bootstrap: has backend blocks, should not")

    # Check main root has no skip_* in non-override files
    skip_flags = check_skip_flags(main_dir)
    if skip_flags:
        failures.append(f"C32.3 main root: has skip_* flags in provider: {', '.join(skip_flags)}")

    # Check bootstrap bucket matches main backend bucket
    if bootstrap_plan and main_backends:
        bootstrap_bucket_name = None
        for resource in resources(bootstrap_plan):
            if resource["type"] == "aws_s3_bucket":
                bootstrap_bucket_name = resource["values"].get("bucket")
                break

        main_backend_bucket = main_backends[0].get("bucket")
        if bootstrap_bucket_name and main_backend_bucket:
            if bootstrap_bucket_name != main_backend_bucket:
                failures.append(f"C32.3 backend bucket {main_backend_bucket} is not the bootstrap's bucket {bootstrap_bucket_name}")

    return failures


def parse_backends(directory: str) -> list[dict]:
    """Parse s3 backend blocks from .tf files (skip *_override.tf)."""
    backends = []
    dir_path = Path(directory)

    if not dir_path.exists():
        return backends

    for tf_file in dir_path.glob("*.tf"):
        if "_override.tf" in str(tf_file):
            continue

        content = tf_file.read_text(encoding="utf-8")

        # Find all backend "s3" blocks using the helper
        backend_blocks = find_blocks(content, 'backend "s3"')

        for backend_content in backend_blocks:
            backend = {"type": "s3"}

            # Parse key = value pairs (handling HCL syntax)
            for line in backend_content.split("\n"):
                # Ignore lines starting with # or //
                stripped = line.strip()
                if stripped.startswith("#") or stripped.startswith("//"):
                    continue

                if "=" in stripped:
                    # Handle lines with = sign
                    key, value = stripped.split("=", 1)
                    key = key.strip()
                    value = value.strip()

                    # Skip nested blocks (endpoints = { ... })
                    if value.startswith("{"):
                        continue

                    # Remove trailing comma
                    value = value.rstrip(",")

                    # Remove quotes if present
                    if value.startswith('"') and value.endswith('"'):
                        value = value[1:-1]
                    elif value == "true" or value == "false":
                        pass  # Keep as is

                    backend[key] = value

            backends.append(backend)

    return backends


def check_skip_flags(directory: str) -> list[str]:
    """Check if provider block has skip_* flags as argument names."""
    dir_path = Path(directory)
    skip_flags = []

    if not dir_path.exists():
        return skip_flags

    for tf_file in dir_path.glob("*.tf"):
        if "_override.tf" in str(tf_file):
            continue

        content = tf_file.read_text(encoding="utf-8")
        # Find provider "aws" blocks using the helper
        provider_blocks = find_blocks(content, 'provider "aws"')

        for provider_content in provider_blocks:
            if "skip_" in provider_content:
                # Extract only skip_* that are argument names (^\s*skip_\w+\s*=)
                skip_pattern = r'^\s*skip_\w+(?=\s*=)'
                found_skips = re.findall(skip_pattern, provider_content, re.MULTILINE)
                skip_flags.extend([s.strip() for s in found_skips])

    return skip_flags


def check_c32_5_sweep(plan: dict, path_label: str) -> list[str]:
    """Sweep for secrets: AWS access key IDs, private key headers, and password values."""
    failures = []

    # AWS access key ID pattern: AKIA or ASIA followed by 16 chars
    access_key_pattern = re.compile(r'\b(AKIA|ASIA)[A-Z0-9]{16}\b')

    # Private key header pattern
    private_key_pattern = re.compile(r'-----BEGIN.*PRIVATE KEY-----')

    def sweep_value(value: Any, path: str):
        if isinstance(value, str):
            if access_key_pattern.search(value):
                failures.append(f"C32.5 {path_label} {path}: AWS access key ID found (not printing value)")
            if private_key_pattern.search(value):
                failures.append(f"C32.5 {path_label} {path}: Private key header found (not printing value)")
        elif isinstance(value, dict):
            for k, v in value.items():
                # Skip sensitivity markers: they mark password even when null
                if k in ("after_sensitive", "before_sensitive", "sensitive_values", "after_unknown"):
                    continue

                new_path = f"{path}.{k}" if path else k
                if k == "password" and v is not None:
                    failures.append(f"C32.5 {path_label} {new_path}: password has a non-null value (not printing value)")
                else:
                    sweep_value(v, new_path)
        elif isinstance(value, list):
            for idx, item in enumerate(value):
                new_path = f"{path}[{idx}]" if path else f"[{idx}]"
                sweep_value(item, new_path)

    sweep_value(plan, "")

    return failures


def bus_expected(repo_root: Path) -> tuple[str, set[str], int, str]:
    """Read TOPIC, queue names, MAX_RECEIVES, and DLQ suffix from EventBus.java.

    Returns (topic_name, set of queue names, max_receive_count, dlq_suffix).
    Raises ValueError if constants are missing.
    """
    event_bus_path = repo_root / "services" / "identity-service" / "app" / "src" / "test" / \
                     "java" / "nl" / "hackyourfuture" / "project" / "backend" / "support" / "EventBus.java"

    try:
        content = event_bus_path.read_text(encoding="utf-8")
    except Exception as e:
        raise ValueError(f"EventBus.java: error reading: {e}")

    patterns = {
        "TOPIC": r'static final String TOPIC = "([^"]*)"',
        "APPLICATIONS_QUEUE": r'static final String APPLICATIONS_QUEUE = "([^"]*)"',
        "MATCHING_QUEUE": r'static final String MATCHING_QUEUE = "([^"]*)"',
        "MAX_RECEIVES": r'static final int MAX_RECEIVES = (\d+)',
    }
    values = {}
    for name, pattern in patterns.items():
        match = re.search(pattern, content)
        if not match:
            raise ValueError(f"EventBus.java: {name} not found")
        values[name] = match.group(1)

    topic = values["TOPIC"]
    eventbus_queues = {values["APPLICATIONS_QUEUE"], values["MATCHING_QUEUE"]}
    max_receives = int(values["MAX_RECEIVES"])

    # Read DLQ suffix from deadLetterQueue method
    dlq_match = re.search(r'return queue \+ "([^"]*)";', content)
    if not dlq_match:
        raise ValueError("EventBus.java: DLQ suffix not found in deadLetterQueue")
    dlq_suffix = dlq_match.group(1)

    # Read queue names from docker-compose.yml
    compose_path = repo_root / "docker-compose.yml"
    try:
        compose_content = compose_path.read_text(encoding="utf-8")
    except Exception as e:
        raise ValueError(f"docker-compose.yml: error reading: {e}")

    # Find all EVENTS_USER_DELETED_QUEUE_URL lines and extract queue names
    compose_queues = set()
    for match in re.finditer(r'EVENTS_USER_DELETED_QUEUE_URL:\s*\S+/([^\s/]+)\s*$', compose_content, re.MULTILINE):
        queue_name = match.group(1)
        compose_queues.add(queue_name)

    if compose_queues != eventbus_queues:
        raise ValueError(f"docker-compose.yml: queues {sorted(compose_queues)}, EventBus.java {sorted(eventbus_queues)}")

    return topic, eventbus_queues, max_receives, dlq_suffix


def bus_snapshot(endpoint: str, topic: str, queues: set[str], dlq_suffix: str) -> dict:
    """Snapshot bus resources on LocalStack: queues, DLQs, policies, subscriptions.

    Returns dict with topic_exists and per-queue dicts containing:
    {exists, dlq_exists, max_receive_count, dead_letter_target, dlq_retention, raw_delivery}.
    Raises exceptions on error.
    """
    import boto3
    from botocore.exceptions import ClientError

    result = {"topic_exists": False, "queues": {}}

    sns = boto3.client("sns", endpoint_url=endpoint, region_name="eu-west-1",
                      aws_access_key_id="test", aws_secret_access_key="test")
    sqs = boto3.client("sqs", endpoint_url=endpoint, region_name="eu-west-1",
                      aws_access_key_id="test", aws_secret_access_key="test")

    # Check topic exists
    topics = sns.list_topics().get("Topics", [])
    topic_arns = [t["TopicArn"] for t in topics]
    result["topic_exists"] = any(arn.endswith(":" + topic) for arn in topic_arns)

    # Check queues and DLQs
    for queue_name in sorted(queues):
        q_info = {
            "exists": None,
            "dlq_exists": None,
            "max_receive_count": None,
            "dead_letter_target": None,
            "dlq_retention": None,
            "raw_delivery": None,
        }

        try:
            queue_url = sqs.get_queue_url(QueueName=queue_name)["QueueUrl"]
            q_info["exists"] = True

            # Get queue attributes
            attrs = sqs.get_queue_attributes(QueueUrl=queue_url, AttributeNames=["All"])["Attributes"]
            redrive = attrs.get("RedrivePolicy")
            if redrive:
                policy = json.loads(redrive)
                q_info["max_receive_count"] = int(policy.get("maxReceiveCount", -1))
                dlq_arn = policy.get("deadLetterTargetArn")
                if dlq_arn:
                    q_info["dead_letter_target"] = dlq_arn.split(":")[-1]

            # Check DLQ
            dlq_name = f"{queue_name}{dlq_suffix}"
            try:
                dlq_url = sqs.get_queue_url(QueueName=dlq_name)["QueueUrl"]
                q_info["dlq_exists"] = True
                dlq_attrs = sqs.get_queue_attributes(QueueUrl=dlq_url, AttributeNames=["All"])["Attributes"]
                q_info["dlq_retention"] = int(dlq_attrs.get("MessageRetentionPeriod", 0))
            except ClientError:
                q_info["dlq_exists"] = False

            # Check subscription raw_message_delivery
            topic_arns = [t["TopicArn"] for t in topics]
            topic_arn = next((arn for arn in topic_arns if arn.endswith(":" + topic)), None)
            if topic_arn:
                subs = sns.list_subscriptions_by_topic(TopicArn=topic_arn)["Subscriptions"]
                for sub in subs:
                    sub_endpoint = sub.get("Endpoint")
                    if sub_endpoint and sub_endpoint.endswith(":" + queue_name):
                        attrs = sns.get_subscription_attributes(SubscriptionArn=sub["SubscriptionArn"])
                        raw = attrs.get("Attributes", {}).get("RawMessageDelivery")
                        q_info["raw_delivery"] = raw == "true" if raw else None
                        break
        except ClientError:
            q_info["exists"] = False

        result["queues"][queue_name] = q_info

    return result


def check_c32_6(terraform_endpoint: str, compose_endpoint: str, repo_root: Path) -> list[str]:
    """Check bus resources match between Terraform (LocalStack) and compose (LocalStack)."""
    failures = []

    try:
        topic, queues, max_receives, dlq_suffix = bus_expected(repo_root)
    except ValueError as e:
        failures.append(f"C32.6 {e}")
        return failures

    # Snapshot both LocalStacks, catching exceptions per side
    tf_snap = None
    compose_snap = None

    try:
        tf_snap = bus_snapshot(terraform_endpoint, topic, queues, dlq_suffix)
    except Exception as e:
        failures.append(f"C32.6 terraform LocalStack: {type(e).__name__}: {e}")

    try:
        compose_snap = bus_snapshot(compose_endpoint, topic, queues, dlq_suffix)
    except Exception as e:
        failures.append(f"C32.6 compose LocalStack: {type(e).__name__}: {e}")

    if not tf_snap or not compose_snap:
        return failures

    # Check topic exists on both sides
    if not tf_snap.get("topic_exists"):
        failures.append(f"C32.6 terraform topic {topic}: missing")
    if not compose_snap.get("topic_exists"):
        failures.append(f"C32.6 compose topic {topic}: missing")

    # Check queues and DLQs
    for queue_name in sorted(queues):
        for env, snap in [("terraform", tf_snap), ("compose", compose_snap)]:
            q_info = snap.get("queues", {}).get(queue_name, {})

            if not q_info.get("exists"):
                failures.append(f"C32.6 {env} queue {queue_name}: missing")
                continue

            if not q_info.get("dlq_exists"):
                failures.append(f"C32.6 {env} queue {queue_name}: dlq missing")
            if q_info.get("max_receive_count") != max_receives:
                failures.append(f"C32.6 {env} queue {queue_name}: maxReceiveCount "
                                f"{q_info.get('max_receive_count')}, EventBus.java {max_receives}")
            if q_info.get("dead_letter_target") != queue_name + dlq_suffix:
                failures.append(f"C32.6 {env} queue {queue_name}: deadLetterTargetArn "
                                f"{q_info.get('dead_letter_target')}, EventBus.java {queue_name + dlq_suffix}")
            if q_info.get("raw_delivery") is not True:
                failures.append(f"C32.6 {env} queue {queue_name}: RawMessageDelivery {q_info.get('raw_delivery')}")

        # Compare all attributes between terraform and compose
        tf_q_info = tf_snap.get("queues", {}).get(queue_name, {})
        compose_q_info = compose_snap.get("queues", {}).get(queue_name, {})

        names = {"max_receive_count": "maxReceiveCount", "dead_letter_target": "deadLetterTargetArn",
                 "dlq_retention": "MessageRetentionPeriod", "raw_delivery": "RawMessageDelivery"}
        for key in names:
            tf_val = tf_q_info.get(key)
            compose_val = compose_q_info.get(key)
            if tf_val != compose_val:
                failures.append(f"C32.6 queue {queue_name}: {names[key]} terraform {tf_val}, compose {compose_val}")

    return failures


def check_c32_7_state(state: dict, label: str) -> list[str]:
    """Check queue policies in Terraform state: Allow SendMessage from topic only.

    The policy is embedded in state after apply, not in the plan (which lacks the account
    to know the ARN). This check runs on the already-parsed state JSON from S3.
    """
    failures = []

    # Find the SNS topic ARN
    topic_arn = None
    for resource in state.get("resources", []):
        if resource.get("type") == "aws_sns_topic" and resource.get("name") == "user_deleted":
            for instance in resource.get("instances", []):
                topic_arn = instance.get("attributes", {}).get("arn")
                if topic_arn:
                    break

    if not topic_arn:
        failures.append(f"C32.7 {label}: topic user_deleted not in state")
        return failures

    # Count non-DLQ queues
    non_dlq_queues = []
    for resource in state.get("resources", []):
        if resource.get("type") == "aws_sqs_queue" and resource.get("name") == "queue":
            for instance in resource.get("instances", []):
                queue_name = instance.get("attributes", {}).get("name")
                if queue_name and not queue_name.endswith("-dlq"):
                    non_dlq_queues.append(queue_name)

    # Find and check queue policies
    queue_policies = []
    for resource in state.get("resources", []):
        if resource.get("type") == "aws_sqs_queue_policy" and resource.get("name") == "queue":
            for instance in resource.get("instances", []):
                queue_policies.append(instance)

    if len(queue_policies) != len(non_dlq_queues):
        failures.append(f"C32.7 {label}: {len(queue_policies)} queue policies, want {len(non_dlq_queues)} (one per non-dlq queue)")

    for policy_instance in queue_policies:
        attrs = policy_instance.get("attributes", {})
        queue_url = attrs.get("queue_url")
        queue_name = queue_url.split("/")[-1] if queue_url else "unknown"

        policy_str = attrs.get("policy")
        if not policy_str:
            failures.append(f"C32.7 {label} queue {queue_name}: policy missing")
            continue

        try:
            policy = json.loads(policy_str)
            statements = policy.get("Statement", [])

            if len(statements) != 1:
                failures.append(f"C32.7 {label} queue {queue_name}: {len(statements)} statements, want 1")
                continue

            stmt = statements[0]

            if stmt.get("Effect") != "Allow":
                failures.append(f"C32.7 {label} queue {queue_name}: Effect {stmt.get('Effect')}, want Allow")

            principal = stmt.get("Principal", {})
            service = principal.get("Service")
            if isinstance(service, str):
                service = [service]
            if service != ["sns.amazonaws.com"]:
                failures.append(f"C32.7 {label} queue {queue_name}: Principal.Service {service}, want ['sns.amazonaws.com']")

            action = stmt.get("Action")
            if isinstance(action, str):
                action = [action]
            if action != ["sqs:SendMessage"]:
                failures.append(f"C32.7 {label} queue {queue_name}: Action {action}, want ['sqs:SendMessage']")

            condition = stmt.get("Condition", {})

            # Require exactly "ArnEquals" key with only "aws:SourceArn"
            if set(condition.keys()) != {"ArnEquals"}:
                failures.append(f"C32.7 {label} queue {queue_name}: Condition keys {sorted(condition.keys())}, want ['ArnEquals']")

            arn_equals = condition.get("ArnEquals", {})
            if set(arn_equals.keys()) != {"aws:SourceArn"}:
                failures.append(f"C32.7 {label} queue {queue_name}: Condition ArnEquals keys {sorted(arn_equals.keys())}, want ['aws:SourceArn']")

            source_arn = arn_equals.get("aws:SourceArn")
            if source_arn != topic_arn:
                failures.append(f"C32.7 {label} queue {queue_name}: Condition ArnEquals aws:SourceArn {source_arn}, want {topic_arn}")
        except json.JSONDecodeError:
            failures.append(f"C32.7 {label} queue {queue_name}: policy is not valid JSON")

    return failures


def check_c32_8(plan: dict) -> list[str]:
    """Check CloudWatch alarms on DLQs: metric, threshold, comparison, actions."""
    failures = []

    # Find all DLQ names
    dlq_names = set()
    for resource in resources(plan):
        if resource["type"] == "aws_sqs_queue":
            name = resource["values"].get("name")
            if name and name.endswith("-dlq"):
                dlq_names.add(name)

    # Find all alarms from module.bus and count by DLQ
    all_alarms = [r for r in resources(plan) if r["type"] == "aws_cloudwatch_metric_alarm"]
    bus_alarms = [r for r in all_alarms if r["address"].startswith("module.bus.aws_cloudwatch_metric_alarm.dlq")]
    alarm_count_by_dlq = {}
    for alarm in bus_alarms:
        dims = alarm["values"].get("dimensions", {})
        queue_name = dims.get("QueueName")
        alarm_count_by_dlq[queue_name] = alarm_count_by_dlq.get(queue_name, 0) + 1
        if queue_name not in dlq_names:
            failures.append(f"C32.8 {alarm['address']}: dimensions.QueueName {queue_name} not in DLQ names {sorted(dlq_names)}")

    # Check each DLQ has exactly one alarm
    for dlq_name in sorted(dlq_names):
        count = alarm_count_by_dlq.get(dlq_name, 0)
        if count != 1:
            failures.append(f"C32.8 {dlq_name}: {count} alarms, want 1")

    # Configuration resources indexed by address for resolving references
    config_by_addr = {}
    for path, resource in config_with_path(plan):
        addr = "".join(f"module.{n}." for n in path) + resource["address"]
        config_by_addr[addr] = (path, resource)

    # Check email subscription to var once (outside per-alarm loop)
    has_email_var_sub = False
    config_addr = "module.bus.aws_cloudwatch_metric_alarm.dlq"
    if config_addr in config_by_addr:
        path, config_res = config_by_addr[config_addr]
        config_values = config_res.get("expressions", {})

        alarm_actions_expr = config_values.get("alarm_actions", {})
        if isinstance(alarm_actions_expr, dict):
            refs = alarm_actions_expr.get("references", [])
            resolved_topics = resolve(plan, path, refs)

            for topic_addr in resolved_topics:
                for sub_addr, (sub_path, sub_res) in config_by_addr.items():
                    if sub_res["type"] == "aws_sns_topic_subscription":
                        sub_expr = sub_res.get("expressions", {})
                        # Check protocol is "email"
                        protocol_expr = sub_expr.get("protocol", {})
                        protocol_val = protocol_expr.get("constant_value") if isinstance(protocol_expr, dict) else protocol_expr
                        if protocol_val != "email":
                            continue

                        topic_refs = sub_expr.get("topic_arn", {}).get("references", [])
                        if topic_addr in resolve(plan, sub_path, topic_refs):
                            endpoint_expr = sub_expr.get("endpoint", {})
                            if isinstance(endpoint_expr, dict):
                                endpoint_refs = endpoint_expr.get("references", [])
                                if any("var." in str(r) for r in endpoint_refs):
                                    has_email_var_sub = True
                                    break
                if has_email_var_sub:
                    break

    # Check each alarm has valid configuration
    for alarm in bus_alarms:
        addr = alarm["address"]
        values = alarm["values"]

        if values.get("namespace") != "AWS/SQS":
            failures.append(f"C32.8 {addr}: namespace {values.get('namespace')}, want AWS/SQS")

        if values.get("metric_name") != "ApproximateNumberOfMessagesVisible":
            failures.append(f"C32.8 {addr}: metric_name {values.get('metric_name')}, want ApproximateNumberOfMessagesVisible")

        if float(values.get("threshold", -1)) != 0.0:
            failures.append(f"C32.8 {addr}: threshold {values.get('threshold')}, want 0")

        if values.get("comparison_operator") != "GreaterThanThreshold":
            failures.append(f"C32.8 {addr}: comparison_operator {values.get('comparison_operator')}, want GreaterThanThreshold")

        if not has_email_var_sub:
            failures.append(f"C32.8 {addr}: alarm_actions do not reference an SNS topic with email subscription to a var")

    return failures


def check_c32_9(endpoint: str, repo_root: Path) -> list[str]:
    """Check score table on LocalStack: name, keys, billing mode, and TTL against source."""
    failures = []

    config_path = repo_root / "services" / "matching-service" / "src" / "main" / "java" / \
                  "nl" / "hackyourfuture" / "project" / "backend" / "matching" / "ScoreStoreConfig.java"

    try:
        config_content = config_path.read_text(encoding="utf-8")
    except Exception as e:
        failures.append(f"C32.9 ScoreStoreConfig.java: error reading: {e}")
        return failures

    patterns = {
        "PARTITION_KEY": r'static final String PARTITION_KEY = "([^"]*)"',
        "SORT_KEY": r'static final String SORT_KEY = "([^"]*)"',
        "TTL_ATTRIBUTE": r'static final String TTL_ATTRIBUTE = "([^"]*)"',
        "BillingMode": r"BillingMode\.(\w+)",
        "ScalarAttributeType": r"ScalarAttributeType\.(\w+)",
    }
    want = {}
    for name, pattern in patterns.items():
        match = re.search(pattern, config_content)
        if match:
            want[name] = match.group(1)
        else:
            failures.append(f"C32.9 ScoreStoreConfig.java: {name} not found")
    if failures:
        return failures

    partition_key = want["PARTITION_KEY"]
    sort_key = want["SORT_KEY"]
    ttl_attr = want["TTL_ATTRIBUTE"]
    billing_mode_str = want["BillingMode"]
    scalar_type = want["ScalarAttributeType"]

    # Read table name from application.yaml
    app_yaml_path = repo_root / "services" / "matching-service" / "src" / "main" / \
                    "resources" / "application.yaml"

    table_name = None
    try:
        app_content = app_yaml_path.read_text(encoding="utf-8")
        table_match = re.search(r'\$\{SCORES_TABLE:([^}]+)\}', app_content)
        if table_match:
            table_name = table_match.group(1)
        else:
            failures.append("C32.9 config: table name not found in application.yaml")
            return failures
    except Exception as e:
        failures.append(f"C32.9 config: error reading application.yaml: {e}")
        return failures

    # Check table on LocalStack
    try:
        import boto3
        from botocore.exceptions import ClientError
    except ImportError:
        failures.append("C32.9 table: boto3 not available")
        return failures

    try:
        dynamodb = boto3.client(
            "dynamodb",
            endpoint_url=endpoint,
            region_name="eu-west-1",
            aws_access_key_id="test",
            aws_secret_access_key="test"
        )

        response = dynamodb.describe_table(TableName=table_name)
        table = response["Table"]

        # Check KeySchema
        key_schema = table.get("KeySchema", [])
        key_by_type = {item["KeyType"]: item["AttributeName"] for item in key_schema}

        if len(key_schema) != 2:
            failures.append(f"C32.9 table {table_name}: {len(key_schema)} keys, want 2")

        hash_key = key_by_type.get("HASH")
        if hash_key != partition_key:
            failures.append(f"C32.9 table {table_name}: HASH key {hash_key}, want {partition_key}")

        range_key = key_by_type.get("RANGE")
        if range_key != sort_key:
            failures.append(f"C32.9 table {table_name}: RANGE key {range_key}, want {sort_key}")

        # Check AttributeDefinitions for types
        attr_types = {item["AttributeName"]: item["AttributeType"] for item in table.get("AttributeDefinitions", [])}

        if attr_types.get(partition_key) != scalar_type:
            failures.append(f"C32.9 table {table_name}: {partition_key} type {attr_types.get(partition_key)}, want {scalar_type}")

        if attr_types.get(sort_key) != scalar_type:
            failures.append(f"C32.9 table {table_name}: {sort_key} type {attr_types.get(sort_key)}, want {scalar_type}")

        # Check BillingMode
        billing_mode = table.get("BillingModeSummary", {}).get("BillingMode")
        if billing_mode != billing_mode_str:
            failures.append(f"C32.9 table {table_name}: billing mode {billing_mode}, want {billing_mode_str}")

        # Check TTL
        ttl_response = dynamodb.describe_time_to_live(TableName=table_name)
        ttl_desc = ttl_response.get("TimeToLiveDescription", {})
        ttl_status = ttl_desc.get("TimeToLiveStatus")
        ttl_attr_name = ttl_desc.get("AttributeName")

        if ttl_status != "ENABLED":
            failures.append(f"C32.9 table {table_name}: TTL status {ttl_status}, want ENABLED")

        if ttl_attr_name != ttl_attr:
            failures.append(f"C32.9 table {table_name}: TTL attribute {ttl_attr_name}, want {ttl_attr}")

    except ClientError as e:
        if e.response["Error"]["Code"] == "ResourceNotFoundException":
            failures.append(f"C32.9 table {table_name}: not found")
        else:
            failures.append(f"C32.9 table {table_name}: {type(e).__name__}: {e}")
    except Exception as e:
        failures.append(f"C32.9 table {table_name}: {type(e).__name__}: {e}")

    return failures


C36_SERVICES = {"identity-service", "job-service", "matching-service", "application-service", "api-gateway", "frontend"}
C36_IMAGES = C36_SERVICES | {"db-setup"}
# db-setup.py's ROLES: the seven database roles, each with a password secret.
C36_DB_ROLES = ("app_user", "analytics_user", "analytics_dev_user", "identity_user", "applications_user",
                "matching_user", "jobs_user")
C36_SECRETS = {f"db-password-{role}" for role in C36_DB_ROLES} | {
    "jwt-private-key", "service-jwt-private-key-job-service", "service-jwt-private-key-matching-service",
    "service-jwt-private-key-application-service", "google-client-id", "google-client-secret", "llm-api-key",
}
# The keys of the resources each task role may use, as task_policy names them in locals.tf.
C36_TASK_POLICY_RESOURCES = {
    "identity-service": {"user-deleted-topic"},
    "matching-service": {"scores-table", "matching-user-deleted"},
    "application-service": {"applications-user-deleted"},
}
# The one-off tasks in locals.tf: two migrate tasks and db-setup. None has a service.
C36_TASKS = {"identity-service-migrate", "application-service-migrate", "db-setup"}


# Compose variables that have no services entry, each with the reason it is left out.
C36_COMPOSE_ONLY = {
    r"_FILE$": "the key files; the content variables replace them",
    r"^EVENTS_S[NQ]S_(ENDPOINT|ACCESS_KEY|SECRET_KEY)$": "the emulator's endpoints and keys; the topic and queue URLs replace them",
    r"^SCORES_DYNAMODB_ENDPOINT$": "the emulator's endpoint; the task role reaches the table",
    r"^AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)$": "the emulator's dummy keys; the task role replaces them",
    r"^SCORES_CREATE_TABLE$": "Terraform creates the table",
    r"^OTEL_TRACES_ENDPOINT$": "the default is the sidecar (Day 34)",
}


def check_c36_1(plan: dict) -> list[str]:
    """Check the ECR repositories: one per image, named jobmatch/<image>, emptied by destroy."""
    failures = []

    repos = [r for r in resources(plan) if r["type"] == "aws_ecr_repository"]
    if not repos:
        return ["C36.1 no aws_ecr_repository in the plan"]

    if len(repos) != len(C36_IMAGES):
        failures.append(f"C36.1 expected exactly {len(C36_IMAGES)} aws_ecr_repository, got {len(repos)}")

    names = {r["values"].get("name") for r in repos}
    want = {"jobmatch/" + i for i in C36_IMAGES}
    for name in sorted(want - names):
        failures.append(f"C36.1 repository {name}: missing")
    for name in sorted(names - want, key=str):
        failures.append(f"C36.1 repository {name}: extra, want only {sorted(want)}")

    for repo in repos:
        if repo["values"].get("force_delete") is not True:
            failures.append(f"C36.1 {repo['address']}: force_delete {repo['values'].get('force_delete')}, want true")

    return failures


def check_c36_2(plan: dict) -> list[str]:
    """Check the ECS cluster and services: Fargate on the public subnets, task definitions, and the
    services output and module input that local.services feeds."""
    failures = []

    clusters = [r for r in resources(plan) if r["type"] == "aws_ecs_cluster"]
    if len(clusters) != 1:
        failures.append(f"C36.2 expected exactly 1 aws_ecs_cluster, got {len(clusters)}")

    services = [r for r in resources(plan) if r["type"] == "aws_ecs_service"]
    if not services:
        failures.append("C36.2 no aws_ecs_service in the plan")
        return failures

    names = set()
    for service in services:
        address, values = service["address"], service["values"]
        names.add(values.get("name"))
        if not address.startswith("module.service["):
            failures.append(f"C36.2 {address}: not in module.service")
        if values.get("launch_type") != "FARGATE":
            failures.append(f"C36.2 {address}: launch_type {values.get('launch_type')}, want FARGATE")
        network = (values.get("network_configuration") or [{}])[0]
        if network.get("assign_public_ip") is not True:
            failures.append(f"C36.2 {address}: assign_public_ip {network.get('assign_public_ip')}, want true")

    for name in sorted(C36_SERVICES - names):
        failures.append(f"C36.2 service {name}: missing")
    for name in sorted(names - C36_SERVICES, key=str):
        failures.append(f"C36.2 service {name}: extra, want only {sorted(C36_SERVICES)}")
    if len(services) != len(C36_SERVICES):
        failures.append(f"C36.2 expected exactly {len(C36_SERVICES)} aws_ecs_service, got {len(services)}")

    # Subnets and security groups are unknown in the plan: read their references from the configuration.
    config = [r for p, r in config_with_path(plan) if p == ("service",) and r["type"] == "aws_ecs_service"]
    if not config:
        failures.append("C36.2 module.service aws_ecs_service not in the configuration")
    else:
        network = config[0].get("expressions", {}).get("network_configuration", [{}])[0]
        subnets = resolve(plan, ("service",), network.get("subnets", {}).get("references", []))
        groups = resolve(plan, ("service",), network.get("security_groups", {}).get("references", []))
        if subnets != {"module.network.aws_subnet.public"}:
            failures.append(f"C36.2 aws_ecs_service subnets {sorted(subnets)}, want the public subnets")
        if groups != {"module.network.aws_security_group.tasks"}:
            failures.append(f"C36.2 aws_ecs_service security groups {sorted(groups)}, want the tasks security group")

    task_defs = [r for r in resources(plan) if r["type"] == "aws_ecs_task_definition"
                 and r["address"].startswith("module.service[")]
    if len(task_defs) != len(C36_SERVICES):
        failures.append(f"C36.2 expected {len(C36_SERVICES)} aws_ecs_task_definition in module.service, got {len(task_defs)}")
    for task_def in task_defs:
        values = task_def["values"]
        if values.get("requires_compatibilities") != ["FARGATE"]:
            failures.append(f"C36.2 {task_def['address']}: requires_compatibilities {values.get('requires_compatibilities')}, want FARGATE")
        if values.get("network_mode") != "awsvpc":
            failures.append(f"C36.2 {task_def['address']}: network_mode {values.get('network_mode')}, want awsvpc")

    outputs = plan.get("planned_values", {}).get("outputs", {})
    if "services" not in outputs:
        failures.append("C36.2 no services output")
    elif set(outputs["services"].get("value", {})) != C36_SERVICES:
        failures.append(f"C36.2 services output keys {sorted(outputs['services'].get('value', {}))}, want {sorted(C36_SERVICES)}")

    module_call = plan.get("configuration", {}).get("root_module", {}).get("module_calls", {}).get("service")
    if not module_call:
        failures.append("C36.2 module service not in the configuration")
    elif "local.services" not in module_call.get("for_each_expression", {}).get("references", []):
        failures.append("C36.2 module service: for_each does not reference local.services")

    return failures


def services_output(plan: dict) -> dict | None:
    """The services output's value (each service's declaration from locals.tf), or None if absent."""
    return plan.get("planned_values", {}).get("outputs", {}).get("services", {}).get("value")


def tasks_output(plan: dict) -> dict | None:
    """The tasks output's value (each one-off task's declaration from locals.tf), or None if absent."""
    return plan.get("planned_values", {}).get("outputs", {}).get("tasks", {}).get("value")


def refers_to(references: list[str], address: str) -> bool:
    """True if a configuration reference is the resource at address, or one of its attributes."""
    return any(ref.split("[")[0] == address or ref.split("[")[0].startswith(address + ".") for ref in references)


def check_c36_3(plan: dict) -> list[str]:
    """Check the secrets: the fourteen secrets with no version in the plan, every name a service
    reads, and no environment variable that holds a secret or a PEM block."""
    failures = []

    secrets = [r for r in resources(plan) if r["type"] == "aws_secretsmanager_secret"]
    if not secrets:
        return ["C36.3 no aws_secretsmanager_secret in the plan"]

    names = {r["values"].get("name") for r in secrets}
    want = {"jobmatch/" + s for s in C36_SECRETS}
    for name in sorted(want - names):
        failures.append(f"C36.3 secret {name}: missing")
    for name in sorted(names - want, key=str):
        failures.append(f"C36.3 secret {name}: extra, want only the {len(want)} in C36_SECRETS")

    for version in resources(plan):
        if version["type"] == "aws_secretsmanager_secret_version":
            failures.append(f"C36.3 {version['address']}: secret version in the plan, want none (values are written outside Terraform)")

    services = services_output(plan)
    if services is None:
        return failures + ["C36.3 no services output"]

    named = {secret for svc in services.values() for secret in svc.get("secrets", {}).values()}
    if not named:
        failures.append("C36.3 no service names a secret")
    for secret in sorted(named - C36_SECRETS):
        failures.append(f"C36.3 services name secret {secret}, not one of the {len(C36_SECRETS)} in C36_SECRETS")

    for role in C36_DB_ROLES:
        if f"jobmatch/db-password-{role}" not in names:
            failures.append(f"C36.3 jobmatch/db-password-{role}: db-setup needs it, not in the plan")

    secret_env = re.compile(r"PASSWORD|SECRET|PRIVATE_KEY|ACCESS_KEY")
    declared = {**services, **(tasks_output(plan) or {})}
    for name in sorted(declared):
        for env, value in declared[name].get("environment", {}).items():
            if secret_env.search(env):
                failures.append(f"C36.3 {name}: environment {env} looks like a secret, want none")
            if "-----BEGIN" in str(value):
                failures.append(f"C36.3 {name}: environment {env} holds a PEM block (not printing it)")

    return failures


def check_c36_4(plan: dict) -> list[str]:
    """Check the IAM: each task has an execution and a task role, the execution role reads only the
    secrets its task names and its one "*" is ecr:GetAuthorizationToken, each task role reaches only
    the resources its service names and its only "*" is the collector's two X-Ray actions, and no
    environment variable is an access key or an endpoint."""
    failures = []

    services = services_output(plan)
    if services is None:
        return ["C36.4 no services output"]

    planned = {r["address"] for r in resources(plan)}
    for name in sorted(C36_SERVICES):
        for role in ("execution", "task"):
            address = f'module.service["{name}"].aws_iam_role.{role}'
            if address not in planned:
                failures.append(f"C36.4 {address}: missing")

    # The roles and policy documents are unknown in the plan: read them from the configuration.
    module_call = plan.get("configuration", {}).get("root_module", {}).get("module_calls", {}).get("service")
    if not module_call:
        return failures + ["C36.4 module service not in the configuration"]
    config = {(r["type"], r["name"]): r for p, r in config_with_path(plan) if p == ("service",)}

    task_def = config.get(("aws_ecs_task_definition", "service"), {})
    for attr, role in (("execution_role_arn", "execution"), ("task_role_arn", "task")):
        refs = task_def.get("expressions", {}).get(attr, {}).get("references", [])
        if not refers_to(refs, f"aws_iam_role.{role}"):
            failures.append(f"C36.4 aws_ecs_task_definition {attr} does not reference aws_iam_role.{role}")

    statements = config.get(("aws_iam_policy_document", "execution"), {}).get("expressions", {}).get("statement", [])
    if not statements:
        failures.append("C36.4 data aws_iam_policy_document.execution in module service: no statements")
    stars = 0
    for statement in statements:
        actions = statement.get("actions", {}).get("constant_value")
        if actions is None:
            failures.append("C36.4 execution policy: a statement's actions are not literal")
            continue
        if any("*" in action for action in actions):
            failures.append(f"C36.4 execution policy: action with * in {actions}")
        if "*" in statement.get("resources", {}).get("constant_value", []):
            stars += 1
            if actions != ["ecr:GetAuthorizationToken"]:
                failures.append(f"C36.4 execution policy: * resource for {actions}, want only ecr:GetAuthorizationToken")
    if stars != 1:
        failures.append(f"C36.4 execution policy: {stars} statements with a * resource, want 1")

    # Inline policies by service: the secrets one for each service that names a secret, the task
    # one for each service with a task_policy.
    inline: dict[str, set[str]] = {}
    for r in resources(plan):
        match = re.fullmatch(r'module\.service\["([^"]+)"\]\.aws_iam_role_policy\.(\w+)(\[\d+\])?', r["address"])
        if match:
            inline.setdefault(match.group(2), set()).add(match.group(1))

    with_secrets = {name for name, svc in services.items() if svc.get("secrets")}
    if inline.get("secrets", set()) != with_secrets:
        failures.append(f"C36.4 aws_iam_role_policy.secrets for {sorted(inline.get('secrets', set()))}, want {sorted(with_secrets)}")
    secret_refs = module_call.get("expressions", {}).get("secret_arns", {}).get("references", [])
    if not refers_to(secret_refs, "aws_secretsmanager_secret.secret"):
        failures.append("C36.4 module service: secret_arns does not reference aws_secretsmanager_secret.secret")
    if "each.value.secrets" not in secret_refs:
        failures.append("C36.4 module service: secret_arns not built from each.value.secrets, want only that task's secrets")

    with_task = {name for name, svc in services.items() if svc.get("task_policy")}
    if inline.get("task", set()) != with_task:
        failures.append(f"C36.4 aws_iam_role_policy.task for {sorted(inline.get('task', set()))}, want {sorted(with_task)}")
    for name in sorted(services):
        task_policy = services[name].get("task_policy", [])
        actions = [action for statement in task_policy for action in statement["actions"]]
        keys = {key for statement in task_policy for key in statement["resources"]}
        if any("*" in value for value in actions + sorted(keys)):
            failures.append(f"C36.4 {name}: task policy has a *")
        want = C36_TASK_POLICY_RESOURCES.get(name, set())
        if keys != want:
            failures.append(f"C36.4 {name}: task policy resources {sorted(keys)}, want {sorted(want)}")

    # A task role's policies are the task one and the telemetry one; the telemetry policy's "*" is the
    # collector's X-Ray actions alone. A missing telemetry document is C34.4's failure, not this one.
    for path, r in config_with_path(plan):
        if path == ("service",) and r["type"] == "aws_iam_role_policy" and r["name"] not in ("task", "telemetry"):
            if refers_to(r.get("expressions", {}).get("role", {}).get("references", []), "aws_iam_role.task"):
                failures.append(f"C36.4 aws_iam_role_policy.{r['name']}: a policy on the task role, want only task and telemetry")
    telemetry = config.get(("aws_iam_policy_document", "telemetry"))
    if telemetry is not None:
        stars = 0
        for statement in telemetry.get("expressions", {}).get("statement", []):
            actions = statement.get("actions", {}).get("constant_value") or []
            if any("*" in action for action in actions):
                failures.append(f"C36.4 telemetry policy: action with * in {actions}")
            if "*" in statement.get("resources", {}).get("constant_value", []):
                stars += 1
                if sorted(actions) != ["xray:PutTelemetryRecords", "xray:PutTraceSegments"]:
                    failures.append(f"C36.4 telemetry policy: * resource for {actions}, want only the two X-Ray actions")
        if stars != 1:
            failures.append(f"C36.4 telemetry policy: {stars} statements with a * resource, want 1")

    policy_refs = module_call.get("expressions", {}).get("policy_resources", {}).get("references", [])
    if not policy_refs:
        failures.append("C36.4 module service: policy_resources references nothing")
    for ref in policy_refs:
        if ref.split(".")[:2] not in (["module", "bus"], ["module", "scores"]):
            failures.append(f"C36.4 module service: policy_resources references {ref}, want module.bus or module.scores")

    for name in sorted(services):
        for env in services[name].get("environment", {}):
            if env in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY") or env.endswith(("_ACCESS_KEY", "_SECRET_KEY", "_ENDPOINT")):
                failures.append(f"C36.4 {name}: environment {env}, want no access key or emulator endpoint")

    return failures


def config_expressions(plan: dict, address: str) -> tuple[tuple, dict]:
    """The module path and the expressions of the configuration resource at a full address
    (module.network.aws_security_group.tasks), or of a module call (module.service)."""
    path, module, parts = (), plan["configuration"]["root_module"], address.split(".")
    while parts[0] == "module" and len(parts) > 2:
        path += (parts[1],)
        module = module["module_calls"][parts[1]]["module"]
        parts = parts[2:]
    if parts[0] == "module":
        return path, module["module_calls"][parts[1]].get("expressions", {})
    resource = next((r for r in module.get("resources", []) if r["address"] == ".".join(parts)), {})
    return path, resource.get("expressions", {})


def config_refs(plan: dict, address: str, attr: str) -> list[str]:
    """The references of attr as written in the configuration block at a full address."""
    return config_expressions(plan, address)[1].get(attr, {}).get("references", [])


def resolved_refs(plan: dict, address: str, attr: str) -> set[str]:
    """The resource addresses attr of the configuration block at address refers to, followed through
    variables and module outputs: the ids and ARNs the plan leaves unknown are read here."""
    return resolve(plan, config_expressions(plan, address)[0], config_refs(plan, address, attr))


def check_c36_5(plan: dict) -> list[str]:
    """Check the ALB: one application load balancer on the public subnets, a DNS-validated certificate
    for var.domain with its records in var.route53_zone_id, a 443 listener forwarding to the frontend's
    target group, a 80 listener redirecting to 443, and the frontend's service as the only one with a
    load balancer."""
    failures = []

    lbs = [r for r in resources(plan) if r["type"] == "aws_lb"]
    if len(lbs) != 1:
        failures.append(f"C36.5 expected exactly 1 aws_lb, got {len(lbs)}")
    for lb in lbs:
        address, values = lb["address"], lb["values"]
        if values.get("load_balancer_type") != "application" or values.get("internal") is not False:
            failures.append(f"C36.5 {address}: load_balancer_type {values.get('load_balancer_type')}, internal {values.get('internal')}, want application and false")
        subnets = resolved_refs(plan, address, "subnets")
        if subnets != {"module.network.aws_subnet.public"}:
            failures.append(f"C36.5 {address}: subnets {sorted(subnets)}, want the public subnets")

    certs = [r for r in resources(plan) if r["type"] == "aws_acm_certificate"]
    if len(certs) != 1:
        failures.append(f"C36.5 expected exactly 1 aws_acm_certificate, got {len(certs)}")
    for cert in certs:
        if cert["values"].get("validation_method") != "DNS" or not refers_to(config_refs(plan, cert["address"], "domain_name"), "var.domain"):
            failures.append(f"C36.5 {cert['address']}: want DNS validation of var.domain")

    records = [r for r in resources(plan) if r["type"] == "aws_route53_record"]
    if not any(refers_to(config_refs(plan, r["address"].split("[")[0], "zone_id"), "var.route53_zone_id") for r in records):
        failures.append("C36.5 no planned aws_route53_record with zone_id from var.route53_zone_id")

    validations = [r for r in resources(plan) if r["type"] == "aws_acm_certificate_validation"]
    if len(validations) != 1:
        failures.append(f"C36.5 expected exactly 1 aws_acm_certificate_validation, got {len(validations)}")
    for validation in validations:
        if not refers_to(config_refs(plan, validation["address"], "validation_record_fqdns"), "aws_route53_record.certificate_validation"):
            failures.append(f"C36.5 {validation['address']}: validation_record_fqdns not from aws_route53_record.certificate_validation")

    listeners = [r for r in resources(plan) if r["type"] == "aws_lb_listener"]
    if len(listeners) != 2:
        failures.append(f"C36.5 expected exactly 2 aws_lb_listener, got {len(listeners)}")
    by_port = {r["values"].get("port"): r for r in listeners}
    https, http = by_port.get(443), by_port.get(80)
    if https is None or https["values"].get("protocol") != "HTTPS":
        failures.append("C36.5 no HTTPS listener on 443")
    else:
        address = https["address"]
        certificate = config_refs(plan, address, "certificate_arn")
        if not (refers_to(certificate, "aws_acm_certificate_validation.main") or refers_to(certificate, "aws_acm_certificate.main")):
            failures.append(f"C36.5 {address}: certificate_arn not from the certificate")
        forward = config_expressions(plan, address)[1].get("default_action", [{}])[0].get("target_group_arn", {})
        if (https["values"].get("default_action") or [{}])[0].get("type") != "forward" or not refers_to(forward.get("references", []), "aws_lb_target_group.frontend"):
            failures.append(f"C36.5 {address}: default action does not forward to aws_lb_target_group.frontend")
    if http is None:
        failures.append("C36.5 no listener on 80")
    else:
        action = (http["values"].get("default_action") or [{}])[0]
        redirect = (action.get("redirect") or [{}])[0]
        if action.get("type") != "redirect" or redirect.get("port") != "443" or redirect.get("protocol") != "HTTPS":
            failures.append(f"C36.5 {http['address']}: default action {action.get('type')}, want redirect to 443 HTTPS")

    groups = [r for r in resources(plan) if r["type"] == "aws_lb_target_group"]
    if len(groups) != 1:
        failures.append(f"C36.5 expected exactly 1 aws_lb_target_group, got {len(groups)}")
    for group in groups:
        values = group["values"]
        path = ((values.get("health_check") or [{}])[0]).get("path")
        if values.get("port") != 3000 or values.get("target_type") != "ip" or path != "/":
            failures.append(f"C36.5 {group['address']}: port {values.get('port')}, target_type {values.get('target_type')}, health path {path}, want 3000, ip and /")

    services_planned = [r for r in resources(plan) if r["type"] == "aws_ecs_service"]
    if not any(r["values"].get("name") == "frontend" for r in services_planned):
        failures.append("C36.5 no frontend aws_ecs_service in the plan")
    for svc in services_planned:
        blocks = [(b.get("container_name"), b.get("container_port")) for b in svc["values"].get("load_balancer") or []]
        want = [("frontend", 3000)] if svc["values"].get("name") == "frontend" else []
        if blocks != want:
            failures.append(f"C36.5 {svc['address']}: load_balancer {blocks}, want {want}")
    if not refers_to(config_refs(plan, "module.service", "target_group_arns"), "aws_lb_listener.https"):
        failures.append("C36.5 module service: target_group_arns not from aws_lb_listener.https")

    return failures


def check_c36_6(plan: dict) -> list[str]:
    """Check the security groups: the ALB's admits 80 and 443 from anywhere and nothing else, and the
    tasks' admits 3000 from the ALB's group and all ports from itself. Neither has inline rules."""
    failures = []

    lbs = [r for r in resources(plan) if r["type"] == "aws_lb"]
    if not lbs:
        return ["C36.6 no aws_lb in the plan"]
    groups = resolved_refs(plan, lbs[0]["address"], "security_groups")
    if len(groups) != 1:
        return [f"C36.6 {lbs[0]['address']}: security groups {sorted(groups)}, want exactly 1"]
    alb = groups.pop()
    tasks_groups = resolved_refs(plan, "module.service", "security_group_ids")
    if tasks_groups != {"module.network.aws_security_group.tasks"}:
        return [f"C36.6 module service: security_group_ids {sorted(tasks_groups)}, want the tasks security group"]
    tasks = tasks_groups.pop()

    planned = {r["address"]: r["values"] for r in resources(plan)}

    def rules_on(kind: str, group: str) -> list[str]:
        """Full addresses of the configuration resources of a kind whose security_group_id is group."""
        addresses = ["".join(f"module.{n}." for n in p) + r["address"] for p, r in config_with_path(plan) if r["type"] == kind]
        return [a for a in addresses if group in resolved_refs(plan, a, "security_group_id")]

    for group in (alb, tasks):
        if planned.get(group, {}).get("ingress"):
            failures.append(f"C36.6 {group}: inline ingress, want none")
        for legacy in rules_on("aws_security_group_rule", group):
            failures.append(f"C36.6 {legacy}: rule on {group}, want only ingress rules")

    alb_rules = rules_on("aws_vpc_security_group_ingress_rule", alb)
    if len(alb_rules) != 2:
        failures.append(f"C36.6 {alb}: {len(alb_rules)} ingress rules, want 2 (80 and 443)")
    if {planned.get(r, {}).get("from_port") for r in alb_rules} != {80, 443}:
        failures.append(f"C36.6 {alb}: ingress ports {sorted(str(planned.get(r, {}).get('from_port')) for r in alb_rules)}, want 80 and 443")
    for rule in alb_rules:
        v = planned.get(rule, {})
        if v.get("ip_protocol") != "tcp" or v.get("from_port") != v.get("to_port") or v.get("cidr_ipv4") != "0.0.0.0/0" \
                or v.get("cidr_ipv6") or v.get("prefix_list_id") or v.get("referenced_security_group_id"):
            failures.append(f"C36.6 {rule}: {v.get('ip_protocol')} {v.get('from_port')}-{v.get('to_port')} from {v.get('cidr_ipv4')}, want tcp 80 or 443 from 0.0.0.0/0 only")

    task_rules = rules_on("aws_vpc_security_group_ingress_rule", tasks)
    if len(task_rules) != 2:
        failures.append(f"C36.6 {tasks}: {len(task_rules)} ingress rules, want 2 (3000 from the ALB, all from itself)")
    seen = set()
    for rule in task_rules:
        v = planned.get(rule, {})
        source = resolved_refs(plan, rule, "referenced_security_group_id")
        if v.get("cidr_ipv4") or v.get("cidr_ipv6") or v.get("prefix_list_id"):
            failures.append(f"C36.6 {rule}: a CIDR source, want the security groups only")
        if v.get("ip_protocol") == "tcp" and (v.get("from_port"), v.get("to_port")) == (3000, 3000) and source == {alb}:
            seen.add("alb")
        elif v.get("ip_protocol") == "-1" and source == {tasks}:
            seen.add("self")
        else:
            failures.append(f"C36.6 {rule}: {v.get('ip_protocol')} {v.get('from_port')}-{v.get('to_port')} from {sorted(source)}, want tcp 3000 from the ALB or all from the tasks")
    if task_rules and seen != {"alb", "self"}:
        failures.append(f"C36.6 {tasks}: ingress sources {sorted(seen)}, want the ALB on 3000 and the tasks on all ports")

    return failures


def check_c36_7(plan: dict) -> list[str]:
    """Check the health checks: each JVM service probes readiness on the management port, the frontend
    has none (its target group checks "/"), and the task definitions write the check into the container."""
    failures = []

    services = services_output(plan)
    if services is None:
        return ["C36.7 no services output"]
    for name in sorted(C36_SERVICES - {"frontend"}):
        command = (services.get(name, {}).get("health_check") or {}).get("command") or []
        joined = " ".join(command)
        if not command or command[0] != "CMD" or "/dev/tcp/127.0.0.1/9090" not in joined or "/actuator/health/readiness" not in joined:
            failures.append(f"C36.7 {name}: health_check {command}, want CMD running the readiness probe on 9090")
    if services.get("frontend", {}).get("health_check") is not None:
        failures.append("C36.7 frontend: health_check set, want none (its target group checks /)")

    if not refers_to(config_refs(plan, "module.service.aws_ecs_task_definition.service", "container_definitions"), "var.service.health_check"):
        failures.append("C36.7 module service: container_definitions does not reference var.service.health_check")

    return failures


def check_c36_8(plan: dict, repo_root: Path) -> list[str]:
    """Check Service Connect: one namespace, each JVM service registered under its compose name and
    port, the module wired to the namespace and the environment values, every internal URL on a
    registered name and port, and every variable compose sets for the six services declared in
    services or on C36_COMPOSE_ONLY."""
    import yaml

    failures = []

    namespaces = [r for r in resources(plan) if r["type"] == "aws_service_discovery_http_namespace"]
    if len(namespaces) != 1:
        failures.append(f"C36.8 expected exactly 1 aws_service_discovery_http_namespace, got {len(namespaces)}")

    services = services_output(plan)
    if services is None:
        return failures + ["C36.8 no services output"]

    planned = {r["address"]: r["values"] for r in resources(plan)}
    registered = {}
    for name in sorted(C36_SERVICES - {"frontend"}):
        address = f'module.service["{name}"].aws_ecs_service.service'
        port = services.get(name, {}).get("port")
        if address not in planned:
            failures.append(f"C36.8 {address}: missing")
            continue
        config = (planned[address].get("service_connect_configuration") or [{}])[0]
        if config.get("enabled") is not True:
            failures.append(f"C36.8 {address}: service_connect_configuration enabled {config.get('enabled')}, want true")
        entries = config.get("service") or []
        if len(entries) != 1:
            failures.append(f"C36.8 {address}: {len(entries)} service blocks in service_connect_configuration, want 1")
            continue
        entry = entries[0]
        alias = (entry.get("client_alias") or [{}])[0]
        if entry.get("port_name") != name or entry.get("discovery_name") != name or alias.get("dns_name") != name:
            failures.append(f"C36.8 {address}: port_name {entry.get('port_name')}, discovery_name {entry.get('discovery_name')}, dns_name {alias.get('dns_name')}, want {name}")
        if alias.get("port") != port:
            failures.append(f"C36.8 {address}: client_alias port {alias.get('port')}, want {port}")
        registered[name] = port

    # The frontend calls the gateway by its compose name, so it is a client: enabled, registers nothing.
    frontend = planned.get('module.service["frontend"].aws_ecs_service.service')
    if frontend is None:
        failures.append('C36.8 module.service["frontend"].aws_ecs_service.service: missing')
    else:
        config = (frontend.get("service_connect_configuration") or [{}])[0]
        if config.get("enabled") is not True or config.get("service"):
            failures.append(f"C36.8 frontend: service_connect_configuration enabled {config.get('enabled')}, {len(config.get('service') or [])} service blocks, want enabled with none")

    # The namespace ARN is unknown in the plan: read its reference from the module call.
    if not refers_to(config_refs(plan, "module.service", "namespace_arn"), "aws_service_discovery_http_namespace.main"):
        failures.append("C36.8 module service: namespace_arn does not reference aws_service_discovery_http_namespace.main")
    if not refers_to(config_refs(plan, "module.service.aws_ecs_task_definition.service", "container_definitions"), "var.environment_values"):
        failures.append("C36.8 module service: container_definitions does not reference var.environment_values")

    urls = 0
    for name in sorted(services):
        for env, value in services[name].get("environment", {}).items():
            if not re.search(r"_SERVICE_URL$|^INTERNAL_.*_URL$|^JOB_SERVICE_KEY_SET_URL$|^BACKEND_API_URL$|_KEYSETURL$", env):
                continue
            urls += 1
            match = re.fullmatch(r"http://([^:/]+):(\d+)(/\S*)?", value)
            if not match or match.group(1) not in registered or int(match.group(2)) != registered[match.group(1)]:
                failures.append(f"C36.8 {name} {env}: {value}, want http://<registered name>:<its port>[/path]")
    if urls == 0:
        failures.append("C36.8 no internal URL in services")

    compose = yaml.safe_load((repo_root / "docker-compose.yml").read_text(encoding="utf-8"))
    for name in sorted(C36_SERVICES):
        if name not in compose.get("services", {}):
            failures.append(f"C36.8 {name}: not in docker-compose.yml")
            continue
        compose_env = compose["services"][name].get("environment", {})
        names = [item.split("=", 1)[0] for item in compose_env] if isinstance(compose_env, list) else list(compose_env)
        declared = {key for field in ("environment", "environment_from", "secrets") for key in services.get(name, {}).get(field, {})}
        for env in names:
            if env not in declared and not any(re.search(pattern, env) for pattern in C36_COMPOSE_ONLY):
                failures.append(f"C36.8 {name}: compose sets {env}, not in its services entry or C36_COMPOSE_ONLY")

    return failures


def check_c36_9(plan: dict) -> list[str]:
    """Check the variables that differ from compose: identity's cookie and links are on the domain,
    the migrations run only as one-off tasks, matching does not create the table and sets its profile
    cache window, and the gateway's trusted proxies match an address in each public subnet and in no
    private one."""
    failures = []

    services = services_output(plan)
    if services is None:
        return ["C36.9 no services output"]

    identity = services.get("identity-service", {}).get("environment", {})
    domain = plan.get("variables", {}).get("domain", {}).get("value")
    if domain is None:
        failures.append("C36.9 no domain variable in the plan")
    elif identity.get("APP_BASE_URL") != f"https://{domain}":
        failures.append(f"C36.9 identity-service APP_BASE_URL {identity.get('APP_BASE_URL')}, want https://{domain}")
    if identity.get("SESSION_COOKIE_SECURE") != "true":
        failures.append(f"C36.9 identity-service SESSION_COOKIE_SECURE {identity.get('SESSION_COOKIE_SECURE')}, want true")
    redirect = identity.get("GOOGLE_REDIRECT_URI")
    if redirect is not None and not redirect.startswith(identity.get("APP_BASE_URL", "") + "/"):
        failures.append(f"C36.9 identity-service GOOGLE_REDIRECT_URI {redirect}, want unset or under APP_BASE_URL")

    for name in ("identity-service", "application-service"):
        value = services.get(name, {}).get("environment", {}).get("MIGRATE_ON_START")
        if value != "false":
            failures.append(f"C36.9 {name} MIGRATE_ON_START {value}, want false")

    matching = services.get("matching-service", {}).get("environment", {})
    if "SCORES_CREATE_TABLE" in matching:
        failures.append("C36.9 matching-service: SCORES_CREATE_TABLE set, want unset (Terraform creates the table)")
    if not matching.get("PROFILE_CACHE_WINDOW"):
        failures.append(f"C36.9 matching-service PROFILE_CACHE_WINDOW {matching.get('PROFILE_CACHE_WINDOW')}, want a value")

    trusted = services.get("api-gateway", {}).get("environment", {}).get("GATEWAY_TRUSTED_PROXIES")
    if not trusted:
        return failures + ["C36.9 api-gateway: GATEWAY_TRUSTED_PROXIES not set"]
    public = [r["values"]["cidr_block"] for r in resources(plan) if r["address"].startswith("module.network.aws_subnet.public[")]
    private = [r["values"]["cidr_block"] for r in resources(plan) if r["address"].startswith("module.network.aws_subnet.private[")]
    if not public or not private:
        return failures + [f"C36.9 planned subnets: {len(public)} public and {len(private)} private, want at least one of each"]

    # Ten past a subnet's network address is inside it, whatever the subnet's size.
    for cidr in public:
        address = str(ipaddress.ip_network(cidr).network_address + 10)
        if not re.fullmatch(trusted, address):
            failures.append(f"C36.9 GATEWAY_TRUSTED_PROXIES does not match {address} in public subnet {cidr}")
    for cidr in private:
        address = str(ipaddress.ip_network(cidr).network_address + 10)
        if re.fullmatch(trusted, address):
            failures.append(f"C36.9 GATEWAY_TRUSTED_PROXIES matches {address} in private subnet {cidr}")

    return failures


def check_c36_10(plan: dict, repo_root: Path) -> list[str]:
    """Check the one-off tasks: three task definitions in module.task and no service for any of them,
    each migrate on its service's image with MIGRATE_ONLY, db-setup on its image with the master
    secret and every role's password, the module's master secret read from RDS, the Dockerfile's
    entrypoint, and the workflow that builds the image and runs its --help."""
    import yaml

    failures = []

    tasks = tasks_output(plan)
    if tasks is None:
        return ["C36.10 no tasks output"]
    if set(tasks) != C36_TASKS:
        failures.append(f"C36.10 tasks output {sorted(tasks)}, want {sorted(C36_TASKS)}")

    defs = [r for r in resources(plan) if r["type"] == "aws_ecs_task_definition" and r["address"].startswith("module.task[")]
    if len(defs) != len(C36_TASKS):
        failures.append(f"C36.10 expected {len(C36_TASKS)} aws_ecs_task_definition in module.task, got {len(defs)}")
    planned = {r["address"] for r in defs}
    for name in sorted(C36_TASKS):
        address = f'module.task["{name}"].aws_ecs_task_definition.task'
        if address not in planned:
            failures.append(f"C36.10 {address}: missing")

    for r in resources(plan):
        if r["type"] == "aws_ecs_service" and (r["address"].startswith("module.task[") or r["values"].get("name") in C36_TASKS):
            failures.append(f"C36.10 {r['address']}: a service for a one-off task, want none")

    for name in sorted(C36_TASKS - {"db-setup"}):
        service = name.removesuffix("-migrate")
        task = tasks.get(name, {})
        env = task.get("environment", {})
        if task.get("image") != service:
            failures.append(f"C36.10 {name} image {task.get('image')}, want {service}, its service's image")
        if env.get("MIGRATE_ONLY") != "true":
            failures.append(f"C36.10 {name} MIGRATE_ONLY {env.get('MIGRATE_ONLY')}, want true")

    setup = tasks.get("db-setup", {})
    secrets = setup.get("secrets", {})
    if setup.get("image") != "db-setup":
        failures.append(f"C36.10 db-setup image {setup.get('image')}, want db-setup")
    if setup.get("command") != ["--passwords-from-env"]:
        failures.append(f"C36.10 db-setup command {setup.get('command')}, want --passwords-from-env")
    if secrets.get("POSTGRES_PASSWORD") != "rds-master:password::":
        failures.append(f"C36.10 db-setup POSTGRES_PASSWORD {secrets.get('POSTGRES_PASSWORD')}, want rds-master:password::")
    for role in C36_DB_ROLES:
        env = f"DB_PASSWORD_{role.upper()}"
        if secrets.get(env) != f"db-password-{role}":
            failures.append(f"C36.10 db-setup {env} {secrets.get(env)}, want db-password-{role}")

    # The master secret is RDS's own, so the module's secret_arns_by_name must read it from the database module.
    module_call = plan.get("configuration", {}).get("root_module", {}).get("module_calls", {}).get("task")
    if not module_call:
        failures.append("C36.10 module task not in the configuration")
    else:
        secret_refs = module_call.get("expressions", {}).get("secret_arns_by_name", {}).get("references", [])
        if not refers_to(secret_refs, "module.database.master_user_secret_arn"):
            failures.append("C36.10 module task: secret_arns_by_name does not reference module.database.master_user_secret_arn")

    dockerfile = repo_root / "scripts" / "db-setup.Dockerfile"
    if not dockerfile.is_file():
        failures.append("C36.10 scripts/db-setup.Dockerfile: missing")
    elif not re.search(r'^ENTRYPOINT \[.*db-setup\.py"\]\s*$', dockerfile.read_text(encoding="utf-8"), re.M):
        failures.append("C36.10 scripts/db-setup.Dockerfile: ENTRYPOINT does not run db-setup.py")

    workflow_path = repo_root / ".github" / "workflows" / "db-setup-tests.yml"
    if not workflow_path.is_file():
        return failures + ["C36.10 .github/workflows/db-setup-tests.yml: missing"]
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
    # YAML 1.1 reads the bare key "on" as True.
    triggers = workflow.get("on") or workflow.get(True) or {}
    runs = [step.get("run", "") for job in workflow.get("jobs", {}).values() for step in job.get("steps", [])]
    if not any("-f scripts/db-setup.Dockerfile" in run for run in runs):
        failures.append("C36.10 db-setup-tests.yml: no step builds scripts/db-setup.Dockerfile")
    if not any(run.rstrip().endswith("--help") for run in runs):
        failures.append("C36.10 db-setup-tests.yml: no step runs --help")
    if "scripts/db-setup.Dockerfile" not in (triggers.get("pull_request") or {}).get("paths", []):
        failures.append("C36.10 db-setup-tests.yml: pull_request paths lack scripts/db-setup.Dockerfile")

    return failures


def check_c36_11(plan: dict) -> list[str]:
    """Check the scaling: the gateway's service has one task and no target, matching has the one target
    with a target-tracking policy on CPU, and the services output says the same."""
    failures = []

    gateway = 'module.service["api-gateway"].aws_ecs_service.service'
    service = next((r for r in resources(plan) if r["address"] == gateway), None)
    if service is None:
        failures.append(f"C36.11 {gateway}: missing")
    elif service["values"].get("desired_count") != 1:
        failures.append(f"C36.11 {gateway} desired_count {service['values'].get('desired_count')}, want 1")

    targets = [r for r in resources(plan) if r["type"] == "aws_appautoscaling_target"]
    if len(targets) != 1:
        failures.append(f"C36.11 expected exactly 1 aws_appautoscaling_target, got {len(targets)}")
    if any(r["address"].startswith('module.service["api-gateway"].') for r in targets):
        failures.append('C36.11 aws_appautoscaling_target under module.service["api-gateway"], want none: one task')
    matching = [r for r in targets if r["address"].startswith('module.service["matching-service"].')]
    if not matching:
        failures.append('C36.11 no aws_appautoscaling_target under module.service["matching-service"]')
    for target in matching:
        values = target["values"]
        if values.get("service_namespace") != "ecs" or values.get("scalable_dimension") != "ecs:service:DesiredCount":
            failures.append(f"C36.11 {target['address']}: service_namespace {values.get('service_namespace')}, "
                            f"scalable_dimension {values.get('scalable_dimension')}, want ecs and ecs:service:DesiredCount")

    policies = [r for r in resources(plan) if r["type"] == "aws_appautoscaling_policy"]
    if len(policies) != 1:
        failures.append(f"C36.11 expected exactly 1 aws_appautoscaling_policy, got {len(policies)}")
    for policy in policies:
        values = policy["values"]
        configured = (values.get("target_tracking_scaling_policy_configuration") or [{}])[0]
        metric = (configured.get("predefined_metric_specification") or [{}])[0].get("predefined_metric_type")
        if not policy["address"].startswith('module.service["matching-service"].'):
            failures.append(f"C36.11 {policy['address']}: want the policy on matching-service")
        if values.get("policy_type") != "TargetTrackingScaling":
            failures.append(f"C36.11 {policy['address']} policy_type {values.get('policy_type')}, want TargetTrackingScaling")
        if metric != "ECSServiceAverageCPUUtilization":
            failures.append(f"C36.11 {policy['address']} metric {metric}, want ECSServiceAverageCPUUtilization")

    services = services_output(plan)
    if services is None:
        failures.append("C36.11 no services output")
    else:
        if services.get("api-gateway", {}).get("scaling") is not None:
            failures.append("C36.11 api-gateway scaling is set, want none: one task")
        if not services.get("matching-service", {}).get("scaling"):
            failures.append("C36.11 matching-service scaling is not set, want min, max and cpu_target")

    return failures


def check_c35_1(plan: dict) -> list[str]:
    """Check the domain's record: one A record named by var.domain in var.route53_zone_id, whose alias
    refers to the ALB (read from the configuration, the plan shows only the resolved name and zone)."""
    failures = []

    records = [r for r in resources(plan) if r["type"] == "aws_route53_record"]
    a_records = [r for r in records if r["values"].get("type") == "A"]
    if not a_records:
        found = [r["address"] for r in records]
        return [f"C35.1 no aws_route53_record of type A; the plan's records: {found}"]
    if len(a_records) > 1:
        return [f"C35.1 {len(a_records)} aws_route53_record of type A, want one: {[r['address'] for r in a_records]}"]

    record = a_records[0]
    address = record["address"]
    domain = plan["variables"]["domain"]["value"]
    zone_id = plan["variables"]["route53_zone_id"]["value"]
    if record["values"].get("name") != domain:
        failures.append(f"C35.1 {address} name {record['values'].get('name')}, want {domain}")
    if record["values"].get("zone_id") != zone_id:
        failures.append(f"C35.1 {address} zone_id {record['values'].get('zone_id')}, want {zone_id}")

    alias = config_expressions(plan, address)[1].get("alias", [])
    if not alias:
        failures.append(f"C35.1 {address} has no alias block, want one to aws_lb.main")
        return failures
    for attr in ("name", "zone_id"):
        refs = alias[0].get(attr, {}).get("references", [])
        if not refers_to(refs, "aws_lb.main"):
            failures.append(f"C35.1 {address} alias {attr} refers to {refs or 'nothing'}, want aws_lb.main")

    return failures


def check_c35_2(plan: dict) -> list[str]:
    """Check every ECS service rolls a failed deployment back: deployment_circuit_breaker with enable
    and rollback both true."""
    failures = []

    services = [r for r in resources(plan) if r["type"] == "aws_ecs_service"]
    if not services:
        return ["C35.2 no aws_ecs_service in the plan"]
    for service in services:
        value = service["values"].get("deployment_circuit_breaker")
        on = [b for b in value or [] if b.get("enable") is True and b.get("rollback") is True]
        if not isinstance(value, list) or len(on) != 1:
            failures.append(f"C35.2 {service['address']} deployment_circuit_breaker {value}, want enable and rollback true")

    return failures


def check_c35_3(plan: dict, started_plan: dict | None) -> list[str]:
    """Check the default plan starts nothing (every desired count 0, no scaling), and the started plan
    runs each service at the count local.services declares."""
    failures = []

    services = [r for r in resources(plan) if r["type"] == "aws_ecs_service"]
    if not services:
        failures.append("C35.3 no aws_ecs_service on the default plan")
    for service in services:
        count = service["values"].get("desired_count")
        if count != 0:
            failures.append(f"C35.3 {service['address']} desired_count {count}, want 0 while start_services is false")

    for r in resources(plan):
        if r["type"] in ("aws_appautoscaling_target", "aws_appautoscaling_policy"):
            failures.append(f"C35.3 {r['address']}: want none while start_services is false")

    if started_plan is None:
        failures.append("C35.3 --started-plan not given")
        return failures

    started = {r["address"]: r for r in resources(started_plan) if r["type"] == "aws_ecs_service"}
    if set(started) != {s["address"] for s in services}:
        want = sorted(s["address"] for s in services)
        failures.append(f"C35.3 started plan services {sorted(started)}, want the default plan's {want}")

    declared = services_output(started_plan)
    if declared is None:
        failures.append("C35.3 no services output on the started plan")
        return failures
    for key, service in declared.items():
        address = f'module.service["{key}"].aws_ecs_service.service'
        if address not in started:
            failures.append(f"C35.3 {address}: missing on the started plan")
            continue
        got = started[address]["values"].get("desired_count")
        if got != service["desired_count"]:
            failures.append(f"C35.3 {address} desired_count {got}, want {service['desired_count']}")

    return failures


# The JVM services and the telemetry defaults each application.yaml sets. Compose overrides the
# traces endpoint to Tempo; the defaults stay off, so a service run outside compose exports nothing.
C34_JVM_SERVICES = ("api-gateway", "application-service", "identity-service", "job-service", "matching-service")
C34_OTEL_DEFAULTS = {
    "OTEL_TRACES_ENDPOINT": "http://localhost:4318/v1/traces",
    "OTEL_EXPORTER_OTLP_ENDPOINT": "http://localhost:4318",
    "TRACING_EXPORT_ENABLED": "false",
    "OTEL_METRICS_ENABLED": "false",
}


# The variables each JVM service sets for the sidecar. The endpoints stay unset: the defaults are
# the sidecar. Micrometer's OTLP registry sends cumulative counts, so the aggregation is delta.
C34_TELEMETRY_ENV = {
    "TRACING_EXPORT_ENABLED": "true",
    "OTEL_METRICS_ENABLED": "true",
    "TRACING_PROBABILITY": "1.0",
    "MANAGEMENT_OTLP_METRICS_EXPORT_AGGREGATIONTEMPORALITY": "delta",
}


# The one metric collector.yaml sends to JobMatch and its dimension sets; C34.7's dashboard check will reuse them.
C34_METRIC_SELECTOR = r"^http\.server\.requests$"
C34_DIMENSION_SETS = [["service.name"], ["service.name", "uri"], ["service.name", "outcome"]]


def check_c34_1(repo_root: Path) -> list[str]:
    """Check each JVM service's Dockerfile names its spans after the service, and the Grafana dashboard's uid."""
    failures = []
    env_line = re.compile(r"^\s*ENV\s+OTEL_SERVICE_NAME[= ](\S+)\s*$")

    for service in C34_JVM_SERVICES:
        rel = f"services/{service}/Dockerfile"
        path = repo_root / rel
        if not path.is_file():
            failures.append(f"C34.1 {rel}: missing")
            continue

        # Comments are skipped, so a commented-out ENV does not count as set.
        found = []
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.lstrip().startswith("#"):
                continue
            match = env_line.match(line)
            if match:
                found.append(match.group(1))
        want = f"jobmatch-{service}"
        if len(found) != 1:
            failures.append(f"C34.1 {rel}: {len(found)} ENV OTEL_SERVICE_NAME lines, want 1")
        elif found[0] != want:
            failures.append(f"C34.1 {rel}: OTEL_SERVICE_NAME is {found[0]}, want {want}")

    # The retired trace name must be gone from the Dockerfiles and from the observability files.
    # observability/ is asserted non-empty first, so the search cannot pass on nothing.
    obs = repo_root / "observability"
    obs_files = sorted(p for p in obs.rglob("*") if p.is_file()) if obs.is_dir() else []
    if not obs_files:
        failures.append("C34.1 observability/: missing or empty")
    service_dockerfiles = [repo_root / "services" / service / "Dockerfile" for service in C34_JVM_SERVICES]
    for path in service_dockerfiles + obs_files:
        if path.is_file() and "jobmatch-backend" in path.read_text(encoding="utf-8"):
            failures.append(f"C34.1 {path.relative_to(repo_root).as_posix()}: contains the retired trace name")

    # The uid is the dashboard's address in Grafana, /d/<uid>: it names the whole deployment now.
    dashboard = repo_root / "observability" / "grafana" / "jobmatch.json"
    if not dashboard.is_file():
        failures.append("C34.1 observability/grafana/jobmatch.json: missing")
    else:
        try:
            uid = json.loads(dashboard.read_text(encoding="utf-8")).get("uid")
        except json.JSONDecodeError as e:
            failures.append(f"C34.1 observability/grafana/jobmatch.json: not valid JSON: {e}")
        else:
            if uid != "jobmatch":
                failures.append(f"C34.1 observability/grafana/jobmatch.json: uid is {uid}, want jobmatch")

    return failures


def collector_pin(repo_root: Path) -> str | None:
    """The image collector.tf pins, or None if the file is missing or has no single collector_image line."""
    tf_path = repo_root / "infra/terraform/collector.tf"
    if not tf_path.is_file():
        return None
    pins = re.findall(r'^\s*collector_image\s*=\s*"([^"]+)"', tf_path.read_text(encoding="utf-8"), re.MULTILINE)
    return pins[0] if len(pins) == 1 else None


def check_c34_2(repo_root: Path) -> list[str]:
    """Check the collector's configuration file (receivers, pipelines, awsemf, metric declarations and
    the health_check extension), and the image pin in collector.tf."""
    import yaml

    failures = []
    rel = "infra/terraform/collector.yaml"
    path = repo_root / rel
    if not path.is_file():
        return [f"C34.2 {rel}: missing"]

    try:
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        return [f"C34.2 {rel}: not valid YAML: {e}"]
    if not isinstance(config, dict):
        return [f"C34.2 {rel}: not a mapping"]

    # A missing key or a node of the wrong type reads as None, so the comparison below reports it.
    def child(node: Any, key: str) -> Any:
        return node.get(key) if isinstance(node, dict) else None

    protocols = child(child(child(config, "receivers"), "otlp"), "protocols")
    for protocol, want in (("http", "localhost:4318"), ("grpc", "localhost:4317")):
        got = child(child(protocols, protocol), "endpoint")
        if got != want:
            failures.append(f"C34.2 {rel}: otlp {protocol} endpoint {got}, want {want}")

    pipelines = child(child(config, "service"), "pipelines")
    for signal, exporter in (("traces", "awsxray"), ("metrics", "awsemf")):
        pipeline = child(pipelines, signal)
        receivers = child(pipeline, "receivers")
        exporters = child(pipeline, "exporters")
        if receivers != ["otlp"]:
            failures.append(f"C34.2 {rel}: {signal} pipeline receivers {receivers}, want ['otlp']")
        if exporters != [exporter]:
            failures.append(f"C34.2 {rel}: {signal} pipeline exporters {exporters}, want ['{exporter}']")

    emf = child(child(config, "exporters"), "awsemf")
    for key, want in (("namespace", "JobMatch"), ("dimension_rollup_option", "NoDimensionRollup")):
        got = child(emf, key)
        if got != want:
            failures.append(f"C34.2 {rel}: awsemf {key} {got}, want {want}")
    if child(child(emf, "resource_to_telemetry_conversion"), "enabled") is not True:
        failures.append(f"C34.2 {rel}: awsemf resource_to_telemetry_conversion.enabled is not true")
    log_group = child(emf, "log_group_name")
    if not isinstance(log_group, str) or not log_group:
        failures.append(f"C34.2 {rel}: awsemf log_group_name {log_group!r}, want a name")

    declarations = child(emf, "metric_declarations")
    if not isinstance(declarations, list) or not declarations:
        failures.append(f"C34.2 {rel}: awsemf metric_declarations missing or empty")
    else:
        selectors = []
        dimension_sets = []
        for declaration in declarations:
            selector = child(declaration, "metric_name_selectors")
            selectors.extend(selector if isinstance(selector, list) else [selector])
            dimensions = child(declaration, "dimensions")
            if isinstance(dimensions, list):
                dimension_sets.extend(tuple(d) if isinstance(d, list) else d for d in dimensions)
        if not selectors or any(s != C34_METRIC_SELECTOR for s in selectors):
            failures.append(f"C34.2 {rel}: metric_name_selectors {selectors}, want only {C34_METRIC_SELECTOR}")
        want_sets = sorted((tuple(d) for d in C34_DIMENSION_SETS), key=repr)
        got_sets = sorted(dimension_sets, key=repr)
        if got_sets != want_sets:
            failures.append(f"C34.2 {rel}: dimension sets {got_sets}, want {want_sets}")

    service = child(config, "service")
    extensions = child(service, "extensions")
    if not isinstance(extensions, list) or "health_check" not in extensions:
        failures.append(f"C34.2 {rel}: service.extensions {extensions}, want health_check")
    top_extensions = child(config, "extensions")
    if not isinstance(top_extensions, dict) or "health_check" not in top_extensions:
        failures.append(f"C34.2 {rel}: no top-level extensions health_check")

    tf_rel = "infra/terraform/collector.tf"
    if not (repo_root / tf_rel).is_file():
        return failures + [f"C34.2 {tf_rel}: missing"]
    pin = collector_pin(repo_root)
    if pin is None:
        failures.append(f"C34.2 {tf_rel}: no single collector_image line")
    else:
        match = re.fullmatch(r"public\.ecr\.aws/aws-observability/aws-otel-collector:([^:/]+)", pin)
        if not match or match.group(1) == "latest":
            failures.append(f"C34.2 {tf_rel}: collector_image {pin}, want "
                            "public.ecr.aws/aws-observability/aws-otel-collector:<tag>, tag not latest")

    return failures


def check_c34_3(plan: dict, repo_root: Path) -> list[str]:
    """Check the collector each JVM service runs beside it (the pinned image, the container's settings,
    its configuration and log group), that only the five JVM services declare one, and the telemetry
    variables each of them sets with no endpoint."""
    failures = []
    services = services_output(plan) or {}
    tasks = tasks_output(plan)

    collector = plan.get("planned_values", {}).get("outputs", {}).get("collector", {}).get("value")
    if collector is None:
        failures.append("C34.3 no collector output")
    else:
        image = collector.get("image")
        pin = collector_pin(repo_root)
        if pin is None or image != pin or image.endswith(":latest"):
            failures.append(f"C34.3 collector image {image}, want the collector.tf pin {pin}, not latest")
        if collector.get("essential") is not False:
            failures.append(f"C34.3 collector essential {collector.get('essential')}, want false")
        if collector.get("restart") is not True:
            failures.append(f"C34.3 collector restart {collector.get('restart')}, want true")
        if collector.get("log_stream_prefix") != "collector":
            failures.append(f"C34.3 collector log_stream_prefix {collector.get('log_stream_prefix')}, want collector")

        memory = collector.get("memory")
        if type(memory) is not int or memory <= 0:
            failures.append(f"C34.3 collector memory {memory}, want an integer above 0")
        else:
            for name in C34_JVM_SERVICES:
                task_memory = services.get(name, {}).get("memory")
                if not isinstance(task_memory, int) or memory >= task_memory:
                    failures.append(f"C34.3 collector memory {memory} is not below {name}'s task memory {task_memory}")

        environment = collector.get("environment") or {}
        rel = "infra/terraform/collector.yaml"
        text_path = repo_root / rel
        want_text = text_path.read_text(encoding="utf-8").replace("\r\n", "\n") if text_path.is_file() else None
        got_text = environment.get("AOT_CONFIG_CONTENT")
        if not isinstance(got_text, str) or want_text is None or got_text.replace("\r\n", "\n") != want_text:
            failures.append(f"C34.3 collector AOT_CONFIG_CONTENT is not the text of {rel}")
        region = plan.get("variables", {}).get("region", {}).get("value")
        if environment.get("AWS_REGION") != region:
            failures.append(f"C34.3 collector AWS_REGION {environment.get('AWS_REGION')}, want {region}")

    # The task definitions are unknown in the plan, so the call must choose by each entry's flag:
    # local.collector passed to every service would give the frontend one too.
    collector_refs = config_refs(plan, "module.service", "collector")
    for ref in ("local.collector", "each.value.collector"):
        if ref not in collector_refs:
            failures.append(f"C34.3 module service: collector does not reference {ref}")
    container_refs = config_refs(plan, "module.service.aws_ecs_task_definition.service", "container_definitions")
    for address in ("var.collector", "aws_cloudwatch_log_group.service"):
        if not refers_to(container_refs, address):
            failures.append(f"C34.3 the task definition's container_definitions does not refer to {address}")

    if not services:
        failures.append("C34.3 no services output")
    else:
        with_collector = {name for name, entry in services.items() if entry.get("collector") is True}
        if with_collector != set(C34_JVM_SERVICES):
            failures.append(f"C34.3 services declaring a collector {sorted(with_collector)}, want the five JVM services")
        for name in C34_JVM_SERVICES:
            environment = services.get(name, {}).get("environment", {})
            for key, want in C34_TELEMETRY_ENV.items():
                if environment.get(key) != want:
                    failures.append(f"C34.3 {name} {key} {environment.get(key)}, want {want}")
            for key in environment:
                if re.search(r"^OTEL_.*ENDPOINT$", key):
                    failures.append(f"C34.3 {name} sets {key}, the default is the sidecar")

    if not tasks:
        failures.append("C34.3 no tasks output")
    else:
        failures.extend(f"C34.3 task {name} declares a collector" for name, entry in tasks.items() if "collector" in entry)
    if config_refs(plan, "module.task", "collector"):
        failures.append("C34.3 module task passes a collector")

    for key in C34_TELEMETRY_ENV:
        if any(re.search(pattern, key) for pattern in C36_COMPOSE_ONLY):
            failures.append(f"C34.3 C36_COMPOSE_ONLY matches {key}, which the services set")

    return failures


def check_c34_4(plan: dict, repo_root: Path) -> list[str]:
    """Check the telemetry policy each task role gets when its task runs a collector: the two X-Ray
    actions on "*", the log actions on the metrics group's streams, and that group is the one awsemf
    writes to. Only the services with a collector get it, so the frontend has none."""
    failures = []

    services = services_output(plan)
    if services is None:
        return ["C34.4 no services output"]
    with_collector = {name for name, entry in services.items() if entry.get("collector") is True}
    if with_collector != C36_SERVICES - {"frontend"}:
        failures.append(f"C34.4 services with a collector {sorted(with_collector)}, want all but frontend")

    policies = set()
    for r in resources(plan):
        match = re.fullmatch(r'module\.service\["([^"]+)"\]\.aws_iam_role_policy\.telemetry(\[\d+\])?', r["address"])
        if match:
            policies.add(match.group(1))
    if policies != with_collector:
        failures.append(f"C34.4 aws_iam_role_policy.telemetry for {sorted(policies)}, want {sorted(with_collector)}")

    # The roles and policy documents are unknown in the plan: read them from the configuration.
    config = {(r["type"], r["name"]): r for p, r in config_with_path(plan) if p == ("service",)}
    policy = config.get(("aws_iam_role_policy", "telemetry"), {}).get("expressions", {})
    if not refers_to(policy.get("role", {}).get("references", []), "aws_iam_role.task"):
        failures.append("C34.4 aws_iam_role_policy.telemetry: role does not reference aws_iam_role.task")
    if not refers_to(policy.get("policy", {}).get("references", []), "data.aws_iam_policy_document.telemetry"):
        failures.append("C34.4 aws_iam_role_policy.telemetry: policy does not reference data.aws_iam_policy_document.telemetry")
    if not refers_to(config.get(("aws_iam_role_policy", "telemetry"), {}).get("count_expression", {}).get("references", []), "var.collector"):
        failures.append("C34.4 aws_iam_role_policy.telemetry: count does not reference var.collector")

    statements = config.get(("aws_iam_policy_document", "telemetry"), {}).get("expressions", {}).get("statement", [])
    if len(statements) != 2:
        failures.append(f"C34.4 telemetry policy has {len(statements)} statements, want 2")
    xray_actions = sorted(["xray:PutTraceSegments", "xray:PutTelemetryRecords"])
    log_actions = sorted(["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"])
    kinds = []
    group_resolved = False
    for statement in statements:
        actions = statement.get("actions", {}).get("constant_value")
        if actions is None:
            failures.append("C34.4 telemetry policy: a statement's actions are not literal")
            continue
        if sorted(actions) == xray_actions:
            kinds.append("xray")
            if statement.get("resources", {}).get("constant_value") != ["*"]:
                failures.append(f"C34.4 telemetry policy: X-Ray actions on {statement.get('resources')}, want *")
        elif sorted(actions) == log_actions:
            kinds.append("logs")
            if "constant_value" in statement.get("resources", {}):
                failures.append("C34.4 telemetry policy: log resources are a constant, want the metrics group's streams")
            resolved = resolve(plan, ("service",), statement.get("resources", {}).get("references", []))
            group_resolved = resolved == {"aws_cloudwatch_log_group.metrics"}
            if not group_resolved:
                failures.append(f"C34.4 telemetry policy: log resources resolve to {sorted(resolved)}, want aws_cloudwatch_log_group.metrics")
        else:
            failures.append(f"C34.4 telemetry policy: statement with actions {sorted(actions)}, want the X-Ray or the log actions")
    if sorted(kinds) != ["logs", "xray"]:
        failures.append(f"C34.4 telemetry policy: statements {sorted(kinds)}, want one X-Ray and one log statement")

    if group_resolved:
        name, failure = emf_log_group_name(repo_root)
        group = next((r for r in resources(plan) if r["address"] == "aws_cloudwatch_log_group.metrics"), None)
        if group is None:
            failures.append("C34.4 aws_cloudwatch_log_group.metrics: missing from the plan")
        elif failure:
            failures.append(failure)
        elif group["values"].get("name") != name:
            failures.append(f"C34.4 aws_cloudwatch_log_group.metrics name {group['values'].get('name')}, want {name} from collector.yaml")

    main_text = (repo_root / "infra/terraform/modules/service/main.tf").read_text(encoding="utf-8")
    if '"${var.metrics_log_group_arn}:*"' not in main_text:
        failures.append('C34.4 modules/service/main.tf: no "${var.metrics_log_group_arn}:*" stream resource')

    return failures


def emf_log_group_name(repo_root: Path) -> tuple[str | None, str | None]:
    """The log group awsemf writes to, from collector.yaml: (name, None), or (None, failure message)."""
    import yaml

    rel = "infra/terraform/collector.yaml"
    path = repo_root / rel
    if not path.is_file():
        return None, f"C34.5 {rel}: missing"
    try:
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        return None, f"C34.5 {rel}: not valid YAML: {e}"

    exporters = config.get("exporters") if isinstance(config, dict) else None
    emf = exporters.get("awsemf") if isinstance(exporters, dict) else None
    name = emf.get("log_group_name") if isinstance(emf, dict) else None
    if not isinstance(name, str) or not name:
        return None, f"C34.5 {rel}: no exporters.awsemf.log_group_name"
    return name, None


def check_c34_5(plan: dict, repo_root: Path) -> list[str]:
    """Check that the log group awsemf writes to, named in collector.yaml, is one aws_cloudwatch_log_group
    in the plan with 14 days' retention."""
    name, failure = emf_log_group_name(repo_root)
    if failure:
        return [failure]

    groups = [r for r in resources(plan) if r["type"] == "aws_cloudwatch_log_group" and r["values"].get("name") == name]
    if len(groups) != 1:
        return [f"C34.5 {name}: {len(groups)} aws_cloudwatch_log_group resources with that name in the plan, want 1"]
    retention = groups[0]["values"].get("retention_in_days")
    if retention != 14:
        return [f"C34.5 {name}: retention_in_days {retention}, want 14"]
    return []


# The ALB's alarms (Day 34): the metric each one watches, its dimensions, its thresholds and how it
# treats missing data. All of them send to the bus module's alarm topic.
C34_6_ALARMS = {
    "HTTPCode_Target_5XX_Count": dict(dims={"LoadBalancer": "aws_lb.main.arn_suffix"}, statistic="Sum", period=300, evaluation_periods=1, threshold=0, comparison_operator="GreaterThanThreshold", treat_missing_data="notBreaching"),
    "HTTPCode_ELB_5XX_Count": dict(dims={"LoadBalancer": "aws_lb.main.arn_suffix"}, statistic="Sum", period=300, evaluation_periods=1, threshold=0, comparison_operator="GreaterThanThreshold", treat_missing_data="notBreaching"),
    "HealthyHostCount": dict(dims={"LoadBalancer": "aws_lb.main.arn_suffix", "TargetGroup": "aws_lb_target_group.frontend.arn_suffix"}, statistic="Minimum", period=60, evaluation_periods=2, threshold=1, comparison_operator="LessThanThreshold", treat_missing_data="breaching"),
}


def alarm_dimensions(repo_root: Path, address: str) -> dict[str, str] | None:
    """The dimensions = { Key = expression } of a root-module resource's block in infra/terraform/*.tf,
    or None when the block is not found once."""
    rtype, name = address.split(".")
    found = []
    for tf in sorted((repo_root / "infra" / "terraform").glob("*.tf")):
        content = tf.read_text(encoding="utf-8")
        for block in find_blocks(content, f'resource "{rtype}" "{name}"'):
            for dims in find_blocks(block, "dimensions ="):
                found.append(dict(re.findall(r"^\s*(\w+)\s*=\s*(\S+)\s*$", dims, re.MULTILINE)))
    return found[0] if len(found) == 1 else None


def check_c34_6(plan: dict, repo_root: Path) -> list[str]:
    """Check the alarms in AWS/ApplicationELB: one per metric in C34_6_ALARMS, with its statistic, period,
    thresholds, dimensions and missing-data treatment, and both actions sending to the bus module's topic."""
    failures = []

    alarms = [r for r in resources(plan) if r["type"] == "aws_cloudwatch_metric_alarm" and r["values"].get("namespace") == "AWS/ApplicationELB"]
    if not alarms:
        return ["C34.6 no aws_cloudwatch_metric_alarm in AWS/ApplicationELB in the plan"]

    by_metric = {}
    for alarm in alarms:
        metric = alarm["values"].get("metric_name")
        by_metric.setdefault(metric, []).append(alarm)
        if metric not in C34_6_ALARMS:
            failures.append(f"C34.6 {alarm['address']}: metric_name {metric}, want one of {sorted(C34_6_ALARMS)}")

    for metric, want in C34_6_ALARMS.items():
        found = by_metric.get(metric, [])
        if len(found) != 1:
            failures.append(f"C34.6 {metric}: {len(found)} alarms, want 1")
            continue
        alarm = found[0]
        address = alarm["address"]
        values = alarm["values"]

        for key in ("statistic", "period", "evaluation_periods", "comparison_operator", "treat_missing_data"):
            if values.get(key) != want[key]:
                failures.append(f"C34.6 {address}: {key} {values.get(key)}, want {want[key]}")
        threshold = values.get("threshold")
        if threshold is None or float(threshold) != want["threshold"]:
            failures.append(f"C34.6 {address}: threshold {threshold}, want {want['threshold']}")

        # The dimensions are the ARNs' suffixes, so the plan has the whole map unknown, keys included, and
        # the configuration gives the map one list of references. Which key holds which suffix is read from
        # the resource's block in the .tf files; that each suffix is a reference, from the configuration.
        dims = alarm_dimensions(repo_root, address)
        if dims != want["dims"]:
            failures.append(f"C34.6 {address}: dimensions {dims}, want {want['dims']}")
        dim_refs = config_refs(plan, address, "dimensions")
        for ref in sorted(want["dims"].values()):
            if ref not in dim_refs:
                failures.append(f"C34.6 {address}: dimensions do not reference {ref} in the configuration")

        topics = resolved_refs(plan, address, "alarm_actions")
        if topics != {"module.bus.aws_sns_topic.alarms"}:
            failures.append(f"C34.6 {address}: alarm_actions resolve to {sorted(topics)}, want the bus alarm topic")
        if "module.bus.alarm_topic_arn" not in config_refs(plan, address, "alarm_actions"):
            failures.append(f"C34.6 {address}: alarm_actions do not reference module.bus.alarm_topic_arn")

    return failures


def emf_declarations(repo_root: Path) -> list[dict] | None:
    """The awsemf metric_declarations in collector.yaml, or None if the file does not give a list."""
    import yaml

    path = repo_root / "infra/terraform/collector.yaml"
    if not path.is_file():
        return None
    try:
        config = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError:
        return None

    exporters = config.get("exporters") if isinstance(config, dict) else None
    emf = exporters.get("awsemf") if isinstance(exporters, dict) else None
    declarations = emf.get("metric_declarations") if isinstance(emf, dict) else None
    if not isinstance(declarations, list):
        return None
    return [d for d in declarations if isinstance(d, dict)]


def check_c34_7(plan: dict, repo_root: Path) -> list[str]:
    """Check the dashboard: one aws_cloudwatch_dashboard named jobmatch whose widgets (the dashboard output)
    refer to the local and the ALB's suffix, plot each namespace, plot only JobMatch metrics a metric_declarations
    entry selects with one of its dimension sets, and plot each service's rate, CPU, memory and DLQ depth."""
    failures = []

    dashboards = [r for r in resources(plan) if r["type"] == "aws_cloudwatch_dashboard"]
    if len(dashboards) != 1:
        return [f"C34.7 {len(dashboards)} aws_cloudwatch_dashboard resources in the plan, want 1"]
    dashboard = dashboards[0]
    address = dashboard["address"]
    if dashboard["values"].get("dashboard_name") != "jobmatch":
        failures.append(f"C34.7 {address}: dashboard_name {dashboard['values'].get('dashboard_name')}, want jobmatch")

    refs = config_refs(plan, address, "dashboard_body")
    for want in ("local.dashboard", "aws_lb.main.arn_suffix"):
        if want not in refs:
            failures.append(f"C34.7 {address}: dashboard_body does not reference {want} in the configuration")

    output = plan.get("planned_values", {}).get("outputs", {}).get("dashboard", {})
    widgets = output.get("value")
    if widgets is None:
        return failures + ["C34.7 the dashboard output has no value in planned_values (an unknown part)"]
    if not isinstance(widgets, list) or not widgets:
        return failures + ["C34.7 the dashboard output is not a non-empty list of widgets"]

    # A metric row is [namespace, name, key, value, ...]: the dimensions are the pairs after the name.
    def value_of(row: list, key: str) -> str | None:
        return dict(zip(row[2::2], row[3::2])).get(key)

    rows = [row for widget in widgets for row in widget["properties"]["metrics"]]
    for namespace in ("AWS/ApplicationELB", "AWS/ECS", "AWS/SQS", "JobMatch"):
        if not any(row[0] == namespace for row in rows):
            failures.append(f"C34.7 no widget plots a {namespace} metric")

    declarations = emf_declarations(repo_root)
    if declarations is None:
        return failures + ["C34.7 collector.yaml has no exporters.awsemf.metric_declarations list"]
    for row in rows:
        if row[0] != "JobMatch":
            continue
        name, keys = row[1], set(row[2::2])
        selected = [d for d in declarations if any(re.search(s, name) for s in d.get("metric_name_selectors", []))]
        if not any(set(s) == keys for d in selected for s in d.get("dimensions", [])):
            failures.append(f"C34.7 JobMatch {name} {sorted(keys)}: no metric_declarations entry selecting it has that dimension set")

    services = services_output(plan)
    if services is None:
        return failures + ["C34.7 no services output, so the dashboard cannot be checked per service"]
    jobmatch = [row for row in rows if row[0] == "JobMatch"]
    for name, service in services.items():
        if service.get("collector") and not any(value_of(row, "service.name") == f"jobmatch-{name}" for row in jobmatch):
            failures.append(f"C34.7 no JobMatch row for service.name jobmatch-{name}")
        for metric in ("CPUUtilization", "MemoryUtilization"):
            if not any(row[:2] == ["AWS/ECS", metric] and value_of(row, "ServiceName") == name and value_of(row, "ClusterName") == "jobmatch" for row in rows):
                failures.append(f"C34.7 no AWS/ECS {metric} row for ServiceName {name} in ClusterName jobmatch")

    queues = {r["values"].get("name") for r in resources(plan) if r["type"] == "aws_sqs_queue"}
    dlqs = {name for name in queues if isinstance(name, str) and name.endswith("-dlq")}
    plotted = {value_of(row, "QueueName") for row in rows if row[0] == "AWS/SQS"}
    if plotted != dlqs:
        failures.append(f"C34.7 AWS/SQS rows plot queues {sorted(plotted, key=str)}, want the plan's DLQs {sorted(dlqs)}")

    return failures


def check_c34_8(repo_root: Path) -> list[str]:
    """Check each JVM service's application.yaml telemetry defaults, and that compose sends traces to Tempo."""
    import yaml

    failures = []
    placeholder = re.compile(r"\$\{([A-Z_]+):([^}]*)\}")

    def collect(node: Any, found: dict[str, list[str]]):
        if isinstance(node, str):
            for name, default in placeholder.findall(node):
                found.setdefault(name, []).append(default)
        elif isinstance(node, dict):
            for value in node.values():
                collect(value, found)
        elif isinstance(node, list):
            for item in node:
                collect(item, found)

    for service in C34_JVM_SERVICES:
        if service == "identity-service":
            rel = "services/identity-service/app/src/main/resources/application.yaml"
        else:
            rel = f"services/{service}/src/main/resources/application.yaml"
        path = repo_root / rel
        if not path.is_file():
            failures.append(f"C34.8 {rel}: missing")
            continue

        # Parsed, not grepped: a commented-out default must not count as set.
        found: dict[str, list[str]] = {}
        collect(yaml.safe_load(path.read_text(encoding="utf-8")), found)
        for name, want in C34_OTEL_DEFAULTS.items():
            if name not in found:
                failures.append(f"C34.8 {service} application.yaml: no ${{{name}:...}} placeholder")
            for got in found.get(name, []):
                if got != want:
                    failures.append(f"C34.8 {service} application.yaml: {name} defaults to {got}, want {want}")

    compose_path = repo_root / "docker-compose.yml"
    if not compose_path.is_file():
        return failures + ["C34.8 docker-compose.yml: missing"]
    compose = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
    compose_services = compose.get("services", {})
    want = "${OTEL_TRACES_ENDPOINT:-http://tempo:4318/v1/traces}"
    for service in C34_JVM_SERVICES:
        if service not in compose_services:
            failures.append(f"C34.8 docker-compose.yml {service}: service missing")
            continue
        env = compose_services[service].get("environment")
        got = env.get("OTEL_TRACES_ENDPOINT") if isinstance(env, dict) else None
        if got != want:
            failures.append(f"C34.8 docker-compose.yml {service}: OTEL_TRACES_ENDPOINT {got}, want Tempo's {want}")

    return failures


def criterion_key(criterion: str) -> tuple[int, ...]:
    """Sort key for criterion IDs: C32.10 after C32.9."""
    return tuple(int(part) for part in criterion[1:].split("."))


def main():
    parser = argparse.ArgumentParser(
        description="Validate Terraform infrastructure against criteria"
    )
    parser.add_argument("--bootstrap-plan", help="Path to bootstrap plan JSON")
    parser.add_argument("--main-plan", help="Path to main root plan JSON")
    parser.add_argument("--started-plan", help="Path to main root plan made with -var start_services=true")
    parser.add_argument("--localstack", help="LocalStack endpoint URL (e.g., http://localhost:4566)")
    parser.add_argument("--compose-localstack", help="Compose LocalStack endpoint URL")
    parser.add_argument("--summary", help="Write the criteria that ran and failed to this path as JSON")

    args = parser.parse_args()

    failures = []

    bootstrap_plan: dict | None = None
    main_plan: dict | None = None
    started_plan: dict | None = None

    # Track which checks ran, and the criteria they cover
    checks_run = []
    criteria_ran = []

    # C32.2: Bootstrap plan checks
    if args.bootstrap_plan:
        checks_run.append("bootstrap plan")
        criteria_ran.append("C32.2")
        try:
            with open(args.bootstrap_plan, "r", encoding="utf-8") as f:
                bootstrap_plan = json.load(f)
            failures.extend(check_c32_2(bootstrap_plan))
        except Exception as e:
            failures.append(f"C32.2 error reading bootstrap plan: {e}")

    # C32.3: Text checks
    repo_root = Path(__file__).parent.parent
    bootstrap_dir = repo_root / "infra" / "terraform" / "bootstrap"
    main_dir = repo_root / "infra" / "terraform"
    localstack_dir = repo_root / "infra" / "terraform" / "localstack"

    if args.main_plan:
        checks_run.append("main plan")
        try:
            with open(args.main_plan, "r", encoding="utf-8") as f:
                main_plan = json.load(f)
        except Exception as e:
            failures.append(f"C32.3 error reading main plan: {e}")

    if args.started_plan:
        try:
            with open(args.started_plan, "r", encoding="utf-8") as f:
                started_plan = json.load(f)
        except Exception as e:
            failures.append(f"C35.3 error reading started plan: {e}")

    failures.extend(check_c32_3_text(
        str(bootstrap_dir),
        str(main_dir),
        str(localstack_dir),
        bootstrap_plan
    ))
    checks_run.append("backends")
    criteria_ran.append("C32.3")

    # C34.1: the five JVM services' trace names, and the Grafana dashboard's uid (no plan needed)
    failures.extend(check_c34_1(repo_root))
    checks_run.append("trace names")
    criteria_ran.append("C34.1")

    # C34.2: the collector's configuration and its image pin (no plan needed)
    failures.extend(check_c34_2(repo_root))
    checks_run.append("collector configuration")
    criteria_ran.append("C34.2")

    # C34.8: the telemetry defaults of the five JVM services, and compose's traces endpoint (no plan needed)
    failures.extend(check_c34_8(repo_root))
    checks_run.append("telemetry defaults")
    criteria_ran.append("C34.8")

    # C32.4: Main plan network and database checks
    if args.main_plan and main_plan:
        failures.extend(check_c32_4(main_plan))
        checks_run.append("database")
        criteria_ran.append("C32.4")

    # C32.8: Main plan alarm checks
    if args.main_plan and main_plan:
        failures.extend(check_c32_8(main_plan))
        checks_run.append("alarms")
        criteria_ran.append("C32.8")

    # C36.1, C36.2: Main plan ECR and ECS checks
    if args.main_plan and main_plan:
        failures.extend(check_c36_1(main_plan))
        failures.extend(check_c36_2(main_plan))
        failures.extend(check_c36_3(main_plan))
        failures.extend(check_c36_4(main_plan))
        failures.extend(check_c36_5(main_plan))
        failures.extend(check_c36_6(main_plan))
        failures.extend(check_c36_7(main_plan))
        failures.extend(check_c36_8(main_plan, repo_root))
        failures.extend(check_c36_9(main_plan))
        failures.extend(check_c36_10(main_plan, repo_root))
        checks_run.append("ECS")
        criteria_ran.extend(f"C36.{n}" for n in range(1, 11))

    # C35.1, C35.2: the domain's record and the services' rollback (main plan)
    if args.main_plan and main_plan:
        failures.extend(check_c35_1(main_plan))
        failures.extend(check_c35_2(main_plan))
        checks_run.append("domain record")
        checks_run.append("circuit breaker")
        criteria_ran.extend(["C35.1", "C35.2"])

    # C35.3: the default plan starts nothing, so the scaling is checked on the started plan
    if args.main_plan and main_plan:
        failures.extend(check_c35_3(main_plan, started_plan))
        checks_run.append("services stopped")
        criteria_ran.append("C35.3")

    # C36.11 reads the started plan because the default plan starts nothing
    if started_plan:
        failures.extend(check_c36_11(started_plan))
        checks_run.append("started plan")
        criteria_ran.append("C36.11")

    # C34.3, C34.4, C34.5: the collector sidecar, its telemetry policy and the metrics log group (main plan)
    if args.main_plan and main_plan:
        failures.extend(check_c34_3(main_plan, repo_root))
        failures.extend(check_c34_4(main_plan, repo_root))
        failures.extend(check_c34_5(main_plan, repo_root))
        checks_run.append("collector sidecar")
        criteria_ran.extend(["C34.3", "C34.4", "C34.5"])

    # C34.6: the ALB's alarms (main plan)
    if args.main_plan and main_plan:
        failures.extend(check_c34_6(main_plan, repo_root))
        checks_run.append("ALB alarms")
        criteria_ran.append("C34.6")

    # C34.7: the dashboard (main plan)
    if args.main_plan and main_plan:
        failures.extend(check_c34_7(main_plan, repo_root))
        checks_run.append("dashboard")
        criteria_ran.append("C34.7")

    # C32.5: Secret sweep on bootstrap plan
    if args.bootstrap_plan and bootstrap_plan:
        failures.extend(check_c32_5_sweep(bootstrap_plan, "bootstrap-plan"))
        criteria_ran.append("C32.5")

    # C32.5: Secret sweep on main plan
    if args.main_plan and main_plan:
        failures.extend(check_c32_5_sweep(main_plan, "main-plan"))
        criteria_ran.append("C32.5")

    # C32.5: Secret sweep on LocalStack state
    localstack_state = None
    if args.localstack:
        checks_run.append("LocalStack state")
        try:
            import boto3
        except ImportError:
            failures.append("C32.5 state: boto3 not available")
        else:
            # Get bucket and key from localstack backend config
            localstack_backends = parse_backends(str(localstack_dir))
            if not localstack_backends:
                failures.append("C32.5 state: no LocalStack backend found")
            else:
                ls_backend = localstack_backends[0]
                bucket = ls_backend.get("bucket")
                key = ls_backend.get("key")
                region = ls_backend.get("region", "eu-west-1")

                if not bucket or not key:
                    failures.append("C32.5 state: bucket or key not found in LocalStack backend")
                else:
                    s3_client = boto3.client(
                        "s3",
                        endpoint_url=args.localstack,
                        region_name=region,
                        aws_access_key_id="test",
                        aws_secret_access_key="test"
                    )
                    try:
                        response = s3_client.get_object(Bucket=bucket, Key=key)
                        state_content = response["Body"].read().decode("utf-8")
                        localstack_state = json.loads(state_content)
                        failures.extend(check_c32_5_sweep(localstack_state, f"state s3://{bucket}/{key}"))
                        criteria_ran.append("C32.5")

                        # C32.7: Check queue policies in state
                        failures.extend(check_c32_7_state(localstack_state, "terraform"))
                        criteria_ran.append("C32.7")
                    except s3_client.exceptions.NoSuchKey:
                        failures.append(f"C32.5 state s3://{bucket}/{key}: object not found")
                    except Exception as e:
                        failures.append(f"C32.5 state s3://{bucket}/{key}: {type(e).__name__}: {e}")

        # C32.9: the score table on LocalStack
        failures.extend(check_c32_9(args.localstack, repo_root))
        checks_run.append("score table")
        criteria_ran.append("C32.9")

    # C32.6: Check bus resources match between Terraform and compose LocalStack
    if args.localstack and args.compose_localstack:
        failures.extend(check_c32_6(args.localstack, args.compose_localstack, repo_root))
        checks_run.append("bus")
        criteria_ran.append("C32.6")
    elif args.localstack and not args.compose_localstack:
        failures.append("C32.6 --compose-localstack not given")

    # The summary (scripts/infra-summary.py) reads these before the exit below
    if args.summary:
        failed = sorted({m.group(1) for f in failures if (m := re.match(r"^(C\d+\.\d+)", f))}, key=criterion_key)
        ran = sorted(set(criteria_ran), key=criterion_key)
        with open(args.summary, "w", encoding="utf-8") as f:
            json.dump({"ran": ran, "failed": failed}, f)

    # Print results
    if failures:
        print(f"infra-checks: {len(failures)} failures")
        for failure in failures:
            print(failure)
        sys.exit(1)
    else:
        checks_str = ", ".join(checks_run) if checks_run else "backends"
        print(f"infra-checks: ok ({checks_str})")


if __name__ == "__main__":
    main()
