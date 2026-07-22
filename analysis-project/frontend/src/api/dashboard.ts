/**
 * Dashboard API client — fetches aggregated quality metrics.
 *
 * Endpoints:
 *  GET /v2/ai/verification/dashboard?windowHours=24  → DashboardSnapshot
 *  GET /v2/ai/verification/trends?windowHours=24      → HourlyBucket[]
 *  GET /actuator/prometheus                           → text/plain prometheus metrics
 */

export interface SloReport {
  windowHours: number;
  total: number;
  passRate: number;
  warnRate: number;
  failRate: number;
  fabricationRate: number;
  avgTrust: number;
  p95LatencyMs: number;
  breaches: string[];
  healthy: boolean;
}

export interface DashboardSnapshot {
  slo: SloReport;
  calibration: Record<string, number>;
  experiments: RuleExperiment[];
  criticStats: Record<string, number>;
}

export interface RuleExperiment {
  id: string;
  name: string;
  status: string;
  createdAt: string;
  trafficPct: number;
}

export interface HourlyBucket {
  hour: string;
  total: number;
  pass: number;
  warn: number;
  fail: number;
}

export interface PrometheusMetrics {
  verdictTotals: Record<string, number>;
  latencyCount: number;
  latencySum: number;
  httpCount: number;
  httpSum: number;
  cacheHit: number;
  cacheMiss: number;
}

/**
 * Fetch the aggregated dashboard snapshot.
 */
export async function fetchDashboard(windowHours = 24): Promise<DashboardSnapshot> {
  const res = await fetch(`/v2/ai/verification/dashboard?windowHours=${windowHours}`);
  if (!res.ok) throw new Error(`Dashboard fetch failed: ${res.status}`);
  return res.json();
}

/**
 * Fetch hourly trend buckets.
 */
export async function fetchTrends(windowHours = 24): Promise<HourlyBucket[]> {
  const res = await fetch(`/v2/ai/verification/trends?windowHours=${windowHours}`);
  if (!res.ok) throw new Error(`Trends fetch failed: ${res.status}`);
  return res.json();
}

/**
 * Fetch and parse Prometheus metrics from /actuator/prometheus.
 * Returns structured data for the dashboard charts.
 */
export async function fetchPrometheusMetrics(): Promise<PrometheusMetrics> {
  const res = await fetch('/actuator/prometheus');
  if (!res.ok) throw new Error(`Prometheus fetch failed: ${res.status}`);
  const text = await res.text();
  return parsePrometheusText(text);
}

function parsePrometheusText(text: string): PrometheusMetrics {
  const metrics: PrometheusMetrics = {
    verdictTotals: {},
    latencyCount: 0,
    latencySum: 0,
    httpCount: 0,
    httpSum: 0,
    cacheHit: 0,
    cacheMiss: 0,
  };

  // verification_verdict_total{verdict="pass",checkpoint="..."} 42
  const verdictRe = /^verification_verdict_total\{verdict="(\w+)"[^}]*\}\s+(\d+\.?\d*)/gm;
  let m: RegExpExecArray | null;
  while ((m = verdictRe.exec(text)) !== null) {
    metrics.verdictTotals[m[1]] = (metrics.verdictTotals[m[1]] || 0) + parseFloat(m[2]);
  }

  // verification_latency_seconds_sum / _count
  const latencySumRe = /^verification_latency_seconds_sum\{(.+?)\}\s+([\d.]+)/;
  const latencyCountRe = /^verification_latency_seconds_count\{(.+?)\}\s+(\d+)/;
  const sumMatch = text.match(latencySumRe);
  const cntMatch = text.match(latencyCountRe);
  if (sumMatch) metrics.latencySum = parseFloat(sumMatch[2]);
  if (cntMatch) metrics.latencyCount = parseInt(cntMatch[2]);

  // http_server_requests_seconds_sum / _count (aggregate across all status)
  const httpSumRe = /^http_server_requests_seconds_sum\{[^}]*\}\s+([\d.]+)/gm;
  const httpCountRe = /^http_server_requests_seconds_count\{[^}]*\}\s+(\d+)/gm;
  let s: RegExpExecArray | null;
  while ((s = httpSumRe.exec(text)) !== null) metrics.httpSum += parseFloat(s[1]);
  while ((s = httpCountRe.exec(text)) !== null) metrics.httpCount += parseInt(s[1]);

  // response_cache_hit_total / _miss_total
  const hitRe = /^response_cache_hit_total\s+(\d+)/m;
  const missRe = /^response_cache_miss_total\s+(\d+)/m;
  const hitMatch = text.match(hitRe);
  const missMatch = text.match(missRe);
  if (hitMatch) metrics.cacheHit = parseInt(hitMatch[1]);
  if (missMatch) metrics.cacheMiss = parseInt(missMatch[1]);

  return metrics;
}
