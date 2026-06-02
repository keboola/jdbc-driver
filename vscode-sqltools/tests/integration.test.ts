/**
 * Integration tests for Keboola SQLTools driver.
 *
 * These tests validate the full driver against live Keboola APIs using direct
 * HTTP calls (no VSCode/SQLTools dependency). They require a valid Keboola
 * token set in tests/.env.
 *
 * Run with: npm run test:integration
 */

import * as fs from 'fs';
import * as path from 'path';

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/** Polling intervals for query job status */
const POLLING = {
  INITIAL_INTERVAL_MS: 200,
  MAX_INTERVAL_MS: 2000,
  BACKOFF_MULTIPLIER: 1.5,
  TIMEOUT_MS: 60_000,
};

/** Result page size for query results */
const PAGE_SIZE = 100;

interface TestConfig {
  connectionUrl: string;
  token: string;
  branchId?: string;
  workspaceId?: string;
  testQuery: string;
}

// ---------------------------------------------------------------------------
// .env loader (no external dependencies)
// ---------------------------------------------------------------------------

function loadEnv(envPath: string): Record<string, string> {
  if (!fs.existsSync(envPath)) return {};
  const content = fs.readFileSync(envPath, 'utf-8');
  const env: Record<string, string> = {};
  for (const line of content.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eqIndex = trimmed.indexOf('=');
    if (eqIndex === -1) continue;
    const key = trimmed.slice(0, eqIndex).trim();
    const value = trimmed.slice(eqIndex + 1).trim();
    env[key] = value;
  }
  return env;
}

function loadConfig(): TestConfig | null {
  const envPath = path.join(__dirname, '.env');
  const env = loadEnv(envPath);

  const token = env.KEBOOLA_TOKEN || process.env.KEBOOLA_TOKEN || '';
  if (!token || token === 'your-token-here') {
    return null;
  }

  const stack = env.KEBOOLA_STACK || process.env.KEBOOLA_STACK || 'connection.keboola.com';
  const customUrl = env.KEBOOLA_CUSTOM_CONNECTION_URL || process.env.KEBOOLA_CUSTOM_CONNECTION_URL || '';
  const connectionUrl = stack === 'custom' ? customUrl : stack;

  if (!connectionUrl) {
    return null;
  }

  return {
    connectionUrl,
    token,
    branchId: env.KEBOOLA_BRANCH_ID || process.env.KEBOOLA_BRANCH_ID || undefined,
    workspaceId: env.KEBOOLA_WORKSPACE_ID || process.env.KEBOOLA_WORKSPACE_ID || undefined,
    testQuery: env.KEBOOLA_TEST_QUERY || process.env.KEBOOLA_TEST_QUERY || 'SELECT 1',
  };
}

// ---------------------------------------------------------------------------
// HTTP helper
// ---------------------------------------------------------------------------

const HTTP_TIMEOUT_MS = 30_000;
const MAX_RETRIES = 3;

async function apiRequest<T = any>(
  baseUrl: string,
  path: string,
  token: string,
  options?: { method?: string; body?: string }
): Promise<{ status: number; data: T }> {
  const url = `https://${baseUrl}${path}`;
  const method = options?.method || 'GET';

  let lastError: Error | null = null;

  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), HTTP_TIMEOUT_MS);

      let response: Response;
      try {
        response = await fetch(url, {
          method,
          headers: {
            'X-StorageApi-Token': token,
            'Content-Type': 'application/json',
          },
          body: options?.body,
          signal: controller.signal,
        });
      } finally {
        clearTimeout(timeoutId);
      }

      const text = await response.text();
      let data: any;
      try {
        data = text ? JSON.parse(text) : {};
      } catch {
        data = { rawText: text };
      }

      // Non-retryable client errors: return immediately
      if (response.status >= 400 && response.status < 500) {
        return { status: response.status, data };
      }

      // Success
      if (response.ok) {
        return { status: response.status, data };
      }

      // Retryable server errors
      if (attempt >= MAX_RETRIES) {
        return { status: response.status, data };
      }
      lastError = new Error(`HTTP ${response.status} from ${url}`);
    } catch (err: any) {
      if (attempt >= MAX_RETRIES) {
        throw new Error(`Network error connecting to ${baseUrl}${path}: ${err.message}`);
      }
      lastError = err;
    }

    // Exponential backoff
    const delay = POLLING.INITIAL_INTERVAL_MS * Math.pow(POLLING.BACKOFF_MULTIPLIER, attempt - 1);
    await sleep(delay);
  }

  throw lastError || new Error(`Request failed after ${MAX_RETRIES} attempts`);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ---------------------------------------------------------------------------
// Test runner
// ---------------------------------------------------------------------------

interface TestResult {
  name: string;
  status: 'pass' | 'fail' | 'skip';
  message?: string;
  durationMs?: number;
}

const results: TestResult[] = [];

async function runTest(
  name: string,
  fn: () => Promise<void>,
  skipReason?: string
): Promise<void> {
  if (skipReason) {
    results.push({ name, status: 'skip', message: skipReason });
    console.log(`  SKIP  ${name} -- ${skipReason}`);
    return;
  }

  const start = Date.now();
  try {
    await fn();
    const duration = Date.now() - start;
    results.push({ name, status: 'pass', durationMs: duration });
    console.log(`  PASS  ${name} (${duration}ms)`);
  } catch (err: any) {
    const duration = Date.now() - start;
    results.push({ name, status: 'fail', message: err.message, durationMs: duration });
    console.log(`  FAIL  ${name} (${duration}ms)`);
    console.log(`        ${err.message}`);
  }
}

function assert(condition: boolean, message: string): void {
  if (!condition) throw new Error(`Assertion failed: ${message}`);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

async function main(): Promise<void> {
  console.log('\nKeboola Integration Tests');
  console.log('========================\n');

  const config = loadConfig();
  if (!config) {
    console.log('  No valid credentials found. Skipping all integration tests.');
    console.log('  Copy tests/sample.env to tests/.env and fill in your token.\n');
    process.exit(0);
  }

  console.log(`  Stack: ${config.connectionUrl}`);
  console.log(`  Branch ID: ${config.branchId || '(auto-detect)'}`);
  console.log(`  Workspace ID: ${config.workspaceId || '(auto-detect)'}`);
  console.log(`  Test query: ${config.testQuery}\n`);

  // Shared state resolved during tests
  let queryServiceUrl = '';
  let branchId = config.branchId || '';
  let workspaceId = config.workspaceId || '';

  // ---------- 1. Storage API connectivity ----------

  await runTest('Storage API connectivity - GET /v2/storage/buckets', async () => {
    const { status, data } = await apiRequest<any[]>(
      config.connectionUrl,
      '/v2/storage/buckets',
      config.token
    );
    assert(status === 200, `Expected HTTP 200, got ${status}`);
    assert(Array.isArray(data), 'Expected response to be an array of buckets');
    console.log(`        Found ${data.length} bucket(s)`);
  });

  // ---------- 2. Auto-discovery of Query Service URL ----------

  await runTest('Auto-discovery - GET /v2/storage (services index)', async () => {
    const { status, data } = await apiRequest<{
      services: { id: string; url: string }[];
    }>(config.connectionUrl, '/v2/storage', config.token);
    assert(status === 200, `Expected HTTP 200, got ${status}`);
    assert(Array.isArray(data.services), 'Expected services array in response');

    const querySvc = data.services.find((s) => s.id === 'query');
    assert(!!querySvc, 'Expected to find "query" service in services array');
    assert(!!querySvc!.url, 'Expected query service to have a URL');

    // Extract hostname from full URL
    const url = new URL(querySvc!.url);
    queryServiceUrl = url.hostname;
    console.log(`        Query Service URL: ${queryServiceUrl}`);
  });

  // ---------- 3. Branch auto-detection ----------

  await runTest('Branch auto-detection - GET /v2/storage/dev-branches', async () => {
    const { status, data } = await apiRequest<any[]>(
      config.connectionUrl,
      '/v2/storage/dev-branches',
      config.token
    );
    assert(status === 200, `Expected HTTP 200, got ${status}`);
    assert(Array.isArray(data), 'Expected response to be an array of branches');
    assert(data.length > 0, 'Expected at least one branch');

    const defaultBranch = data.find((b: any) => b.isDefault);
    assert(!!defaultBranch, 'Expected to find a default branch (isDefault: true)');

    if (!branchId) {
      branchId = String(defaultBranch.id);
    }
    console.log(`        Default branch: id=${defaultBranch.id}, name="${defaultBranch.name}"`);
  });

  // ---------- 4. Workspace auto-selection ----------

  await runTest('Workspace auto-selection - GET /v2/storage/workspaces', async () => {
    const { status, data } = await apiRequest<any[]>(
      config.connectionUrl,
      '/v2/storage/workspaces',
      config.token
    );
    assert(status === 200, `Expected HTTP 200, got ${status}`);
    assert(Array.isArray(data), 'Expected response to be an array of workspaces');
    assert(data.length > 0, 'Expected at least one workspace');

    if (!workspaceId) {
      const newest = data[data.length - 1];
      workspaceId = String(newest.id);
    }
    console.log(`        Found ${data.length} workspace(s), using id=${workspaceId}`);
  });

  // ---------- 5. Query execution ----------

  const canRunQuery = !!queryServiceUrl && !!branchId && !!workspaceId;

  await runTest(
    'Query execution - SELECT 1 via Query Service',
    async () => {
      // Submit query
      const submitPath = `/api/v1/branches/${branchId}/workspaces/${workspaceId}/queries`;
      const { status: submitStatus, data: submitData } = await apiRequest<{
        queryJobId: string;
      }>(queryServiceUrl, submitPath, config.token, {
        method: 'POST',
        body: JSON.stringify({
          statements: [config.testQuery],
          wait: false,
        }),
      });
      assert(
        submitStatus === 200 || submitStatus === 201 || submitStatus === 202,
        `Expected HTTP 2xx on submit, got ${submitStatus}: ${JSON.stringify(submitData)}`
      );
      assert(!!submitData.queryJobId, 'Expected queryJobId in submit response');

      const queryJobId = submitData.queryJobId;
      console.log(`        Query job submitted: ${queryJobId}`);

      // Poll for completion
      const job = await pollQueryJob(queryServiceUrl, config.token, queryJobId);
      assert(job.status === 'completed', `Expected job status "completed", got "${job.status}"`);
      assert(Array.isArray(job.statements), 'Expected statements array in job response');
      assert(job.statements.length > 0, 'Expected at least one statement');

      const stmt = job.statements[0];
      assert(stmt.status === 'completed', `Expected statement status "completed", got "${stmt.status}"`);

      // Fetch results
      const resultPath = `/api/v1/queries/${queryJobId}/${stmt.id}/results?offset=0&pageSize=${PAGE_SIZE}`;
      const { status: resultStatus, data: resultData } = await apiRequest<any>(
        queryServiceUrl,
        resultPath,
        config.token
      );
      assert(resultStatus === 200, `Expected HTTP 200 for results, got ${resultStatus}`);
      assert(Array.isArray(resultData.columns), 'Expected columns array in result');
      assert(Array.isArray(resultData.data), 'Expected data array in result');
      assert(resultData.data.length >= 1, 'Expected at least 1 row of data');

      console.log(
        `        Result: ${resultData.data.length} row(s), ${resultData.columns.length} column(s)`
      );
    },
    canRunQuery ? undefined : 'Missing query service URL, branch ID, or workspace ID'
  );

  // ---------- 6. Pagination ----------

  await runTest(
    'Pagination - query with multiple result pages',
    async () => {
      // Generate enough rows to span multiple pages. Query Service enforces a
      // minimum pageSize of 100, so we ask for 250 rows -> 3 pages of 100.
      const submitPath = `/api/v1/branches/${branchId}/workspaces/${workspaceId}/queries`;
      const { status: submitStatus, data: submitData } = await apiRequest<{
        queryJobId: string;
      }>(queryServiceUrl, submitPath, config.token, {
        method: 'POST',
        body: JSON.stringify({
          statements: ['SELECT seq4() AS n FROM TABLE(GENERATOR(ROWCOUNT => 250))'],
          wait: false,
        }),
      });
      assert(
        submitStatus >= 200 && submitStatus < 300,
        `Expected HTTP 2xx on submit, got ${submitStatus}`
      );
      const queryJobId = submitData.queryJobId;

      const job = await pollQueryJob(queryServiceUrl, config.token, queryJobId);
      assert(job.status === 'completed', `Expected job status "completed", got "${job.status}"`);

      const stmt = job.statements[0];
      assert(stmt.status === 'completed', `Expected statement "completed", got "${stmt.status}"`);

      // Fetch page 1 -- minimum pageSize accepted by the API is 100.
      const pageSize = 100;
      const resultPath = `/api/v1/queries/${queryJobId}/${stmt.id}/results?offset=0&pageSize=${pageSize}`;
      const { status: resultStatus, data: page1 } = await apiRequest<any>(
        queryServiceUrl,
        resultPath,
        config.token
      );
      assert(resultStatus === 200, `Expected HTTP 200 for results, got ${resultStatus}`);
      assert(Array.isArray(page1.data), 'Expected data array in first page');

      const totalRows = page1.numberOfRows ?? page1.data.length;
      console.log(`        Total rows: ${totalRows}, first page: ${page1.data.length} rows`);

      if (totalRows > pageSize) {
        // Fetch page 2
        const page2Path = `/api/v1/queries/${queryJobId}/${stmt.id}/results?offset=${pageSize}&pageSize=${pageSize}`;
        const { status: page2Status, data: page2 } = await apiRequest<any>(
          queryServiceUrl,
          page2Path,
          config.token
        );
        assert(page2Status === 200, `Expected HTTP 200 for page 2, got ${page2Status}`);
        assert(Array.isArray(page2.data), 'Expected data array in second page');
        assert(page2.data.length > 0, 'Expected at least 1 row on second page');
        console.log(`        Second page: ${page2.data.length} rows`);
      } else {
        console.log(`        Only ${totalRows} row(s) -- pagination not needed, but API call succeeded`);
      }
    },
    canRunQuery ? undefined : 'Missing query service URL, branch ID, or workspace ID'
  );

  // ---------- 7. SHOW HISTORY ----------

  await runTest(
    'Query history - GET workspace queries',
    async () => {
      // Query Service enforces a minimum pageSize of 100.
      const historyPath = `/api/v1/branches/${branchId}/workspaces/${workspaceId}/queries?pageSize=100`;
      const { status, data } = await apiRequest<any>(
        queryServiceUrl,
        historyPath,
        config.token
      );
      assert(status === 200, `Expected HTTP 200, got ${status}`);

      // History can be an array or an object with a nested array
      const items = Array.isArray(data) ? data : data.statements || data.queries || [];
      assert(Array.isArray(items), 'Expected history items to be an array');
      console.log(`        History entries: ${items.length}`);

      // After running test queries above, there should be at least one entry
      if (items.length > 0) {
        const entry = items[0];
        assert(!!entry.queryJobId || !!entry.id, 'Expected history entry to have an identifier');
        assert(!!entry.status, 'Expected history entry to have a status');
        console.log(`        Latest entry: status=${entry.status}, id=${entry.queryJobId || entry.id}`);
      }
    },
    canRunQuery ? undefined : 'Missing query service URL, branch ID, or workspace ID'
  );

  // ---------- 8. Virtual tables - buckets format ----------

  await runTest('Virtual tables - buckets response matches expected format', async () => {
    const { status, data } = await apiRequest<any[]>(
      config.connectionUrl,
      '/v2/storage/buckets',
      config.token
    );
    assert(status === 200, `Expected HTTP 200, got ${status}`);
    assert(Array.isArray(data), 'Expected response to be an array');

    if (data.length > 0) {
      const bucket = data[0];
      assert(typeof bucket.id === 'string', 'Expected bucket.id to be a string');
      assert(typeof bucket.name === 'string', 'Expected bucket.name to be a string');
      assert(typeof bucket.stage === 'string', 'Expected bucket.stage to be a string');
      console.log(`        Sample bucket: id="${bucket.id}", stage="${bucket.stage}"`);
    } else {
      console.log('        No buckets found (empty project)');
    }
  });

  // ---------- 9. Error handling - invalid token ----------

  await runTest('Error handling - invalid token returns 401', async () => {
    const { status } = await apiRequest<any>(
      config.connectionUrl,
      '/v2/storage/buckets',
      'invalid-token-for-testing'
    );
    assert(status === 401, `Expected HTTP 401 for invalid token, got ${status}`);
  });

  // ---------- Summary ----------

  console.log('\n========================');
  const passed = results.filter((r) => r.status === 'pass').length;
  const failed = results.filter((r) => r.status === 'fail').length;
  const skipped = results.filter((r) => r.status === 'skip').length;
  console.log(`Results: ${passed} passed, ${failed} failed, ${skipped} skipped`);
  console.log('========================\n');

  if (failed > 0) {
    process.exit(1);
  }
}

// ---------------------------------------------------------------------------
// Query polling helper
// ---------------------------------------------------------------------------

async function pollQueryJob(
  queryServiceUrl: string,
  token: string,
  queryJobId: string
): Promise<{ status: string; statements: any[] }> {
  let interval = POLLING.INITIAL_INTERVAL_MS;
  const startTime = Date.now();
  const terminalStates = new Set(['completed', 'failed', 'canceled']);

  while (true) {
    const { status: httpStatus, data: job } = await apiRequest<any>(
      queryServiceUrl,
      `/api/v1/queries/${queryJobId}`,
      token
    );

    if (httpStatus !== 200) {
      throw new Error(`Failed to poll job ${queryJobId}: HTTP ${httpStatus}`);
    }

    if (terminalStates.has(job.status)) {
      return job;
    }

    if (Date.now() - startTime > POLLING.TIMEOUT_MS) {
      throw new Error(`Query job ${queryJobId} timed out after ${POLLING.TIMEOUT_MS}ms`);
    }

    await sleep(interval);
    interval = Math.min(interval * POLLING.BACKOFF_MULTIPLIER, POLLING.MAX_INTERVAL_MS);
  }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

main().catch((err) => {
  console.error('\nUnexpected error:', err.message);
  process.exit(1);
});
