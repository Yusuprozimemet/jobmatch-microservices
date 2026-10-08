#!/usr/bin/env python3
"""
Reads plan JSON (terraform show -json) and validates infrastructure criteria.
With --localstack, also checks applied state and resources against specifications.
Fails on C32.2 (bootstrap plan), C32.3 (backend blocks and bootstrap bucket match),
C32.4 (network and database), C32.5 (secret sweep), C32.6 (bus resources), C32.7 (queue policies
in the LocalStack state), C32.8 (alarms), and C32.9 (score table on LocalStack).
Track B checks C32.4; Track C1 adds scores; Track C2 adds the bus.
Exits 1 with a list of failures, each prefixed with criterion ID.
"""

import argparse
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


def main():
    parser = argparse.ArgumentParser(
        description="Validate Terraform infrastructure against criteria"
    )
    parser.add_argument("--bootstrap-plan", help="Path to bootstrap plan JSON")
    parser.add_argument("--main-plan", help="Path to main root plan JSON")
    parser.add_argument("--localstack", help="LocalStack endpoint URL (e.g., http://localhost:4566)")
    parser.add_argument("--compose-localstack", help="Compose LocalStack endpoint URL")

    args = parser.parse_args()

    failures = []

    bootstrap_plan: dict | None = None
    main_plan: dict | None = None

    # Track which checks ran
    checks_run = []

    # C32.2: Bootstrap plan checks
    if args.bootstrap_plan:
        checks_run.append("bootstrap plan")
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

    failures.extend(check_c32_3_text(
        str(bootstrap_dir),
        str(main_dir),
        str(localstack_dir),
        bootstrap_plan
    ))
    checks_run.append("backends")

    # C32.4: Main plan network and database checks
    if args.main_plan and main_plan:
        failures.extend(check_c32_4(main_plan))
        checks_run.append("database")

    # C32.8: Main plan alarm checks
    if args.main_plan and main_plan:
        failures.extend(check_c32_8(main_plan))
        checks_run.append("alarms")

    # C32.5: Secret sweep on bootstrap plan
    if args.bootstrap_plan and bootstrap_plan:
        failures.extend(check_c32_5_sweep(bootstrap_plan, "bootstrap-plan"))

    # C32.5: Secret sweep on main plan
    if args.main_plan and main_plan:
        failures.extend(check_c32_5_sweep(main_plan, "main-plan"))

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

                        # C32.7: Check queue policies in state
                        failures.extend(check_c32_7_state(localstack_state, "terraform"))
                    except s3_client.exceptions.NoSuchKey:
                        failures.append(f"C32.5 state s3://{bucket}/{key}: object not found")
                    except Exception as e:
                        failures.append(f"C32.5 state s3://{bucket}/{key}: {type(e).__name__}: {e}")

        # C32.9: the score table on LocalStack
        failures.extend(check_c32_9(args.localstack, repo_root))
        checks_run.append("score table")

    # C32.6: Check bus resources match between Terraform and compose LocalStack
    if args.localstack and args.compose_localstack:
        failures.extend(check_c32_6(args.localstack, args.compose_localstack, repo_root))
        checks_run.append("bus")
    elif args.localstack and not args.compose_localstack:
        failures.append("C32.6 --compose-localstack not given")

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
