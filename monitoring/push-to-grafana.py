"""Creates (or updates) the BookMySeat folder, dashboard and alert rules in a Grafana stack through its HTTP API.

    GRAFANA_URL=https://<stack>.grafana.net GRAFANA_SA_TOKEN=<service account token, Editor> python monitoring/push-to-grafana.py

The service account needs Edit on the BookMySeat folder once it exists (Folder actions -> Manage permissions).
alerts.yaml stays the source of truth: each Prometheus rule becomes a Grafana-managed rule whose query already
holds the threshold, so any returned series fires. Needs PyYAML (pip install pyyaml)."""
import json
import os
import re
import urllib.error
import urllib.request

import yaml  # PyYAML

HERE = os.path.dirname(os.path.abspath(__file__))
URL = os.environ["GRAFANA_URL"].rstrip("/")
HEADERS = {"Authorization": "Bearer " + os.environ["GRAFANA_SA_TOKEN"], "Content-Type": "application/json",
           "X-Disable-Provenance": "true"}  # rules stay editable in the UI
PROM = os.environ.get("GRAFANA_PROM_UID", "grafanacloud-prom")  # Grafana Cloud's Prometheus data source
FOLDER = "bookmyseat"
GROUP = "bookmyseat"


def api(method, path, body=None, ok=(200, 201, 202, 204)):
    req = urllib.request.Request(URL + path, method=method, headers=HEADERS,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        if e.code in ok:
            return e.code, None
        return e.code, e.read().decode()[:400]


# ---- folder (Grafana answers 403, not 404, for a folder that doesn't exist yet) ----
status, body = api("GET", f"/api/folders/{FOLDER}")
if status in (403, 404):
    status, body = api("POST", "/api/folders", {"uid": FOLDER, "title": "BookMySeat"})
print("folder:", status)

# ---- dashboard (data source preselected in the uploaded copy only) ----
dashboard = json.load(open(os.path.join(HERE, "bookmyseat-dashboard.json"), encoding="utf-8"))
dashboard["id"] = None
for var in dashboard["templating"]["list"]:
    if var["name"] == "datasource":
        var["current"] = {"text": PROM, "value": PROM, "selected": True}
status, body = api("POST", "/api/dashboards/db", {"dashboard": dashboard, "folderUid": FOLDER, "overwrite": True,
                                                   "message": "Imported from monitoring/bookmyseat-dashboard.json"})
print("dashboard:", status, body.get("url") if isinstance(body, dict) else body)

# ---- alert rules: Prometheus rule file -> Grafana-managed rules ----
rules = yaml.safe_load(open(os.path.join(HERE, "alerts.yaml"), encoding="utf-8"))["groups"][0]["rules"]


def grafana_template(text):
    # Prometheus' $value is the number; in Grafana-managed rules it is $values.<refId>.Value
    return re.sub(r"\$value\b", "$values.A.Value", text)


for rule in rules:
    uid = "bms-" + re.sub(r"(?<!^)(?=[A-Z])", "-", rule["alert"].removeprefix("BookMySeat")).lower()
    payload = {
        "uid": uid,
        "title": rule["alert"],
        "folderUID": FOLDER,
        "ruleGroup": GROUP,
        "condition": "B",
        "data": [
            {"refId": "A", "relativeTimeRange": {"from": 3600, "to": 0}, "datasourceUid": PROM,
             "model": {"refId": "A", "expr": rule["expr"].strip(), "instant": True, "range": False}},
            # The PromQL already holds the threshold: any returned series is a firing one
            {"refId": "B", "datasourceUid": "__expr__",
             "model": {"refId": "B", "type": "threshold", "expression": "A",
                       "conditions": [{"evaluator": {"type": "gt", "params": [0]}}]}},
        ],
        "noDataState": "OK",        # the app sleeps on Render's free plan: no data is normal
        "execErrState": "KeepLast",
        "for": rule.get("for", "0s"),
        "labels": rule.get("labels", {}),
        "annotations": {k: grafana_template(v) for k, v in rule.get("annotations", {}).items()},
    }
    status, body = api("GET", f"/api/v1/provisioning/alert-rules/{uid}")
    if status == 200:
        status, body = api("PUT", f"/api/v1/provisioning/alert-rules/{uid}", payload)
        action = "updated"
    else:
        status, body = api("POST", "/api/v1/provisioning/alert-rules", payload)
        action = "created"
    print(f"rule {rule['alert']}: {action} {status}", "" if status in (200, 201) else body)

status, group = api("GET", f"/api/v1/provisioning/folder/{FOLDER}/rule-groups/{GROUP}")
if status == 200:
    group["interval"] = 60
    status, body = api("PUT", f"/api/v1/provisioning/folder/{FOLDER}/rule-groups/{GROUP}", group)
    print("group interval 1m:", status, "" if status == 200 else body)
