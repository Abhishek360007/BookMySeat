# Monitoring

The app pushes metrics (once a minute) and traces over OTLP when `OTLP_ENABLED=true`: to Grafana Cloud in
production, to a local Grafana in development. This folder holds what is built on top of them, as files, so they
are reviewed and versioned like code.

| File | What it is |
|---|---|
| [`bookmyseat-dashboard.json`](bookmyseat-dashboard.json) | Grafana dashboard: overview, traffic, bookings, payments and email, JVM |
| [`alerts.yaml`](alerts.yaml) | Alert rules (Prometheus rule format) |
| [`alerts.test.yaml`](alerts.test.yaml) | Unit tests for the rules: each alert fires on a bad case and stays quiet on a normal one (run in CI) |
| [`push-to-grafana.py`](push-to-grafana.py) | Creates or updates the folder, dashboard and alert rules in a Grafana stack through its API |

## Local Grafana

```bash
docker compose --profile monitoring up -d lgtm     # Grafana, Prometheus, Tempo and Loki in one container
OTLP_ENABLED=true ./mvnw spring-boot:run           # PowerShell: $env:OTLP_ENABLED="true"; .\mvnw spring-boot:run
```

Open http://localhost:13000 (admin / admin), **Dashboards → New → Import**, upload `bookmyseat-dashboard.json`
and pick the `Prometheus` data source. Traces: **Explore → Tempo**, service `BookMySeat`.

## Grafana Cloud

The production stack's dashboard and rules were created with the script, so a change to the files is one command away:

1. **Service account:** in the stack's Grafana, **Administration → Users and access → Service accounts**: add one
   with the Editor role and a token.
2. **Run:** `GRAFANA_URL=https://<stack>.grafana.net GRAFANA_SA_TOKEN=<token> python monitoring/push-to-grafana.py`.
   The first run creates the `BookMySeat` folder and the dashboard. Grafana gives a service account no rights on a
   folder it created, so the rules need Edit on that folder first (**Dashboards → BookMySeat → Folder actions →
   Manage permissions**); run it again and the 8 rules appear, evaluated every minute. Re-running updates in place.
3. **Email:** **Alerting → Contact points** (an Email contact point) and **Notification policies** (make it the
   default). The rules treat "no data" as normal, because the app sleeps.

Each rule's PromQL already holds its threshold, so any series it returns fires. Messages use Grafana's
`$values.A.Value` where Prometheus says `$value`.

The dashboard can also be imported by hand: **Dashboards → New → Import**, upload `bookmyseat-dashboard.json`, pick
the `grafanacloud-<stack>-prom` data source.

## Testing the rules

```bash
docker run --rm -v "$PWD/monitoring:/rules" -w /rules --entrypoint promtool prom/prometheus:v3.15.1 test rules alerts.test.yaml
```

## How the numbers are counted

- **Counters only move after commit.** A booking that loses the optimistic-lock race at commit is a `lock_lost`
  conflict, never a booking.
- **Restarts.** On Render's free plan the app sleeps after 15 idle minutes, and each wake-up is a new process whose
  counters start again at 0. The business counters exist from startup at 0, and the dashboard's totals use
  `(increase(x[range]) and x offset range) or last_over_time(x[range])`: a series that is new in the range counts
  from 0. Request counts per route are created on first use, so the per-minute charts can miss a new route's first
  minute after a wake-up; the totals don't.
- **Estimates.** `increase()` is extrapolated from per-minute samples, so totals over a range can be slightly off
  when the app was only up for part of it.
- **Latency** comes from fixed buckets (50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2 s, 5 s): percentiles are interpolated
  between them.
- **No "app is down" alert.** Missing metrics are normal while the app sleeps. An uptime check would keep it awake,
  and the scheduled jobs would then keep Neon's compute running all month. `BookMySeatRestartLoop` catches an app that
  crashes on start.
