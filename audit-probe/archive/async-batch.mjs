// Independent audit probe: does the documented DEFAULT of
// POST /api/v1/reconciliation/batches (async defaults to true) ever run?
//
// Static reading says no:
//   ReconciliationController.createBatch:
//       boolean async = request.async() == null || request.async();   // default TRUE
//       if (!async) { worker.runBatch(batchId); }                       // only inline
//       return 202 ...
//   ReconciliationWorker.runBatch is called from nowhere else.
//   No @Scheduled method runs batches (only InboundEventWorker.poll and
//   IdempotencyRetentionSweeper are scheduled).
//   reconcile.reconciliation.poll-interval is bound into ReconcileProperties
//   and read by nothing.
//
// Prediction: the batch returns 202, stays RUNNING with processed_subjects = 0
// forever, no results are ever written, and after the 30-minute lease
// /api/v1/health turns DOWN and every load balancer probe starts failing.

const B = 'http://127.0.0.1:8080';
const OP = { Authorization: 'Bearer dev-operator-token' };
const AD = { Authorization: 'Bearer dev-admin-token' };
const J = { 'Content-Type': 'application/json' };

async function call(path, opts = {}) {
  const r = await fetch(B + path, opts);
  const t = await r.text();
  let j; try { j = JSON.parse(t); } catch { j = t; }
  return { status: r.status, json: j };
}

// First put a payment through capture so the window definitely has real work.
const MR = 'ASYNC-' + Math.random().toString(36).slice(2, 8);
const p = await call('/api/v1/payments', {
  method: 'POST', headers: { ...OP, ...J, 'Idempotency-Key': 'as-' + Math.random() },
  body: JSON.stringify({ merchantReference: MR, amount: { amountMinor: 10000, currency: 'EGP' } }),
});
const pid = p.json.id;
await call(`/api/v1/payments/${pid}/authorize`, { method: 'POST', headers: { ...OP, ...J } });
const cap = await call(`/api/v1/payments/${pid}/capture`, {
  method: 'POST', headers: { ...OP, ...J },
  body: JSON.stringify({ expectedAmount: { amountMinor: 10000, currency: 'EGP' } }),
});
console.log('payment', pid, 'state', cap.json.state);

const end = new Date(Date.now() + 3600_000).toISOString();
const start = new Date(Date.now() - 3600_000).toISOString();

// EXACTLY the body documented in docs/spec/06-api-contract.md
const body = {
  provider: 'SIMULATED_PSP', windowStart: start, windowEnd: end,
  subjectMode: 'BOTH', async: true,
};
console.log('\n>> POST /api/v1/reconciliation/batches  (the documented example body)');
console.log('   ', JSON.stringify(body));
const b = await call('/api/v1/reconciliation/batches', {
  method: 'POST', headers: { ...AD, ...J }, body: JSON.stringify(body),
});
console.log('   HTTP', b.status);
console.log('   ', JSON.stringify(b.json));
const bid = b.json.id;

console.log('\n>> polling the batch for 60s to see whether anything picks it up');
for (let i = 0; i < 6; i++) {
  await new Promise(r => setTimeout(r, 10_000));
  const s = await call(`/api/v1/reconciliation/batches/${bid}`, { headers: OP });
  const res = await call(`/api/v1/reconciliation/batches/${bid}/results`, { headers: OP });
  console.log(`   t+${(i + 1) * 10}s  status=${s.json.status}`
    + `  processed=${s.json.processedSubjects}/${s.json.totalSubjects}`
    + `  results=${Array.isArray(res.json) ? res.json.length : res.json}`);
}

const health = await call('/api/v1/health');
console.log('\n>> /api/v1/health now:', JSON.stringify(health.json));
