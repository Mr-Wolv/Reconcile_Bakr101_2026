// Adversarial V&V probe against the running deployment.
//
// Every scenario here is constructed to FALSIFY a claim made in docs/spec. It uses only the
// documented HTTP interface, so a pass means the claim holds for a caller, not just for a test
// that reaches into the database.
//
//   node audit-probe/vv-api.mjs
//
// Requires the stack started with the committed dev tokens:
//   docker compose up -d --build app

const BASE = process.env.PROBE_BASE || 'http://127.0.0.1:8080';
const OP = process.env.RECONCILE_OPERATOR_TOKEN || 'dev-operator-token';
const ADMIN = process.env.RECONCILE_ADMIN_TOKEN || 'dev-admin-token';
const SECRET = process.env.RECONCILE_PSP_WEBHOOK_SECRET || 'dev-webhook-secret';

let pass = 0, fail = 0;
const RUN = Date.now().toString(36);
function ok(name, cond, detail = '') {
  if (cond) { pass++; console.log(`PASS  ${name}${detail ? '   ' + detail : ''}`); }
  else { fail++; console.log(`FAIL  ${name}${detail ? '   ' + detail : ''}`); }
}
function section(s) { console.log(`\n== ${s} ==`); }

async function call(method, path, { token = OP, body, idem, headers = {}, raw } = {}) {
  const h = { ...headers };
  if (body !== undefined || raw !== undefined) h['Content-Type'] = 'application/json';
  if (token) h['Authorization'] = `Bearer ${token}`;
  if (idem) h['Idempotency-Key'] = idem;
  const res = await fetch(BASE + path, {
    method,
    headers: h,
    body: raw !== undefined ? raw : (body !== undefined ? JSON.stringify(body) : undefined),
  });
  let parsed = null, text = '';
  try { text = await res.text(); parsed = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, body: parsed, text, headers: res.headers };
}

// HMAC-SHA256 over `timestamp + "." + rawBody`, lowercase hex.
import { createHmac } from 'node:crypto';
function sign(ts, raw) {
  return createHmac('sha256', SECRET).update(`${ts}.${raw}`).digest('hex');
}

function uuid() { return `vv-${RUN}-${Math.random().toString(36).slice(2, 10)}`; }

// ---------------------------------------------------------------- A. authz matrix
section('A. authorisation matrix (every route, over HTTP)');
const adminOnly = [
  ['POST', '/api/v1/payments/PLACEHOLDER/fail'],
  ['POST', '/api/v1/payouts'],
  ['POST', '/api/v1/ledger/transactions/ltx_none/reverse'],
  ['POST', '/api/v1/reconciliation/batches'],
  ['POST', '/api/v1/reconciliation/cases/rcs_none/resolve'],
  ['POST', '/api/v1/reconciliation/cases/rcs_none/write-off'],
  ['POST', '/api/v1/provider/sync'],
];
for (const [method, pathTemplate] of adminOnly) {
  const path = pathTemplate.replace('PLACEHOLDER', 'pay_none');
  const r = await call(method, path, { token: OP, body: {} });
  ok(`operator refused: ${method} ${pathTemplate}`, r.status === 403, `got ${r.status}`);
  const unauth = await call(method, path, { token: null, body: {} });
  ok(`anonymous refused: ${method} ${pathTemplate}`, unauth.status === 401, `got ${unauth.status}`);
}

// ---------------------------------------------------------------- B. input validation
section('B. input validation and error model');
{
  const r = await call('POST', '/api/v1/payments', { body: { merchantReference: 'X', amount: { amountMinor: 10000, currency: 'EGP' } } });
  ok('missing Idempotency-Key is 400', r.status === 400 && r.body?.code === 'IDEMPOTENCY_KEY_REQUIRED', `got ${r.status} ${r.body?.code}`);
}
{
  const r = await call('POST', '/api/v1/payments', { idem: uuid(), raw: '{"merchantReference":"X","amount":{"amountMinor":10000,"currency":"EGP"},"surpriseField":1}' });
  ok('unknown JSON field is refused (never silently dropped)', r.status === 400, `got ${r.status} ${r.body?.code}`);
}
{
  const r = await call('POST', '/api/v1/payments', { idem: uuid(), raw: '{not json' });
  ok('malformed JSON is 400, not 500', r.status === 400, `got ${r.status} ${r.body?.code}`);
}
{
  const r = await call('POST', '/api/v1/payments', { idem: uuid(), body: { merchantReference: 'X', amount: { amountMinor: -500, currency: 'EGP' } } });
  ok('negative Money is refused', r.status >= 400 && r.status < 500, `got ${r.status} ${r.body?.code}`);
}
{
  const r = await call('POST', '/api/v1/payments', { idem: uuid(), body: { merchantReference: 'X', amount: { amountMinor: 10000, currency: 'JPY' } } });
  ok('unsupported currency (zero-decimal, excluded from v1) is refused', r.status >= 400 && r.status < 500, `got ${r.status} ${r.body?.code}`);
}
{
  const r = await call('POST', '/api/v1/payments', { idem: uuid(), body: { merchantReference: 'bad ref!', amount: { amountMinor: 10000, currency: 'EGP' } } });
  ok('merchantReference pattern enforced', r.status >= 400 && r.status < 500, `got ${r.status} ${r.body?.code}`);
}
{
  const r = await call('GET', '/api/v1/payments/pay_does_not_exist');
  ok('unknown payment is 404', r.status === 404 && r.body?.code === 'RESOURCE_NOT_FOUND', `got ${r.status} ${r.body?.code}`);
}
{
  const r = await call('POST', '/api/v1/payments/pay_does_not_exist/capture', { body: {} });
  ok('command on unknown payment is 404, not 500', r.status === 404, `got ${r.status} ${r.body?.code}`);
}
{
  // Spec 02 §2: an illegal transition on an EXISTING payment is 409, never 404.
  const created = await call('POST', '/api/v1/payments', {
    idem: uuid(),
    body: { merchantReference: `VV-${RUN}-a`, amount: { amountMinor: 10000, currency: 'EGP' }, description: 'vv' },
  });
  const id = created.body?.id;
  const r = await call('POST', `/api/v1/payments/${id}/capture`, { body: { expectedAmount: { amountMinor: 10000, currency: 'EGP' } } });
  ok('CREATED -> CAPTURED is 409 PAYMENT_INVALID_STATE (not 404/500)', r.status === 409 && r.body?.code === 'PAYMENT_INVALID_STATE', `got ${r.status} ${r.body?.code}`);
  const refund = await call('POST', `/api/v1/payments/${id}/refund`, { body: { reason: 'vv' } });
  ok('refund of a never-captured payment is 409', refund.status === 409, `got ${refund.status} ${refund.body?.code}`);
}
{
  const r = await call('GET', '/api/v1/payments?limit=500');
  ok('limit above the 200 cap is 400, not a silent truncation', r.status === 400, `got ${r.status} ${r.body?.code}`);
}

// ---------------------------------------------------------------- C. idempotency protocol
section('C. idempotency protocol (spec 03 A1-A6)');
let idemPaymentId = null;
{
  const key = uuid();
  const body = { merchantReference: `VV-${RUN}-idem`, amount: { amountMinor: 10000, currency: 'EGP' }, description: 'idem' };
  const first = await call('POST', '/api/v1/payments', { idem: key, body });
  const second = await call('POST', '/api/v1/payments', { idem: key, body });
  idemPaymentId = first.body?.id;
  ok('same key + same body replays identically', second.status === first.status && second.text === first.text,
     `status ${first.status}/${second.status}`);
  ok('replay is flagged with Idempotency-Replayed: true', second.headers.get('Idempotency-Replayed') === 'true',
     `got ${second.headers.get('Idempotency-Replayed')}`);

  const conflicting = await call('POST', '/api/v1/payments', { idem: key, body: { ...body, description: 'different' } });
  ok('same key + different body is 409 IDEMPOTENCY_KEY_CONFLICT',
     conflicting.status === 409 && conflicting.body?.code === 'IDEMPOTENCY_KEY_CONFLICT', `got ${conflicting.status} ${conflicting.body?.code}`);

  const list = await call('GET', `/api/v1/payments?merchantReference=VV-${RUN}-idem`);
  const created = (list.body?.items || []).filter(p => p.merchantReference === `VV-${RUN}-idem`).length;
  ok('exactly one payment exists after key replay + conflict', created === 1, `found ${created}`);
}
{
  // Keys are scoped to an endpoint, so the same key on two templates are separate namespaces.
  const key = uuid();
  const a = await call('POST', '/api/v1/payments', { idem: key, body: { merchantReference: `VV-${RUN}-ns`, amount: { amountMinor: 5000, currency: 'EGP' } } });
  const b = await call('POST', '/api/v1/payments', { idem: key, body: { merchantReference: `VV-${RUN}-ns2`, amount: { amountMinor: 6000, currency: 'EGP' } } });
  // Spec 03 A3: same key, different fingerprint => 409 IDEMPOTENCY_KEY_CONFLICT, no side effect.
  ok('same key + different fingerprint is 409, never a second payment',
     a.status === 201 && b.status === 409 && b.body?.code === 'IDEMPOTENCY_KEY_CONFLICT',
     `got ${a.status}/${b.status} ${b.body?.code}`);
  const conflicted = await call('GET', `/api/v1/payments?merchantReference=VV-${RUN}-ns2`);
  const ns2 = (conflicted.body?.items || []).length;
  ok('the conflicting request left no payment behind', ns2 === 0, `found ${ns2}`);
}

// ---------------------------------------------------------------- D. the worked example
section('D. the worked example: capture -> settle -> reconcile -> payout');
let demo = null;
{
  const reg = await call('POST', '/api/v1/provider/payments', {
    body: { merchantReference: `VV-${RUN}-demo`, grossAmountMinor: 10000, currency: 'EGP' },
  });
  const extId = reg.body?.externalTransactionId;
  ok('provider registration', reg.status === 201, `extId=${extId}`);

  const created = await call('POST', '/api/v1/payments', {
    idem: uuid(),
    body: {
      merchantReference: `VV-${RUN}-demo`,
      amount: { amountMinor: 10000, currency: 'EGP' },
      description: 'demo',
      providerTransactionId: extId,
    },
  });
  const id = created.body?.id;
  ok('payment created', created.status === 201, `id=${id}`);

  const auth = await call('POST', `/api/v1/payments/${id}/authorize`, { body: {} });
  ok('authorize', auth.status === 200, `${auth.status}`);

  // Wrong capture amount must be refused (spec 02 T3 guard).
  const badCapture = await call('POST', `/api/v1/payments/${id}/capture`, { body: { expectedAmount: { amountMinor: 9999, currency: 'EGP' } } });
  ok('capture with a wrong expectedAmount is refused', badCapture.status === 409, `got ${badCapture.status} ${badCapture.body?.code}`);

  const cap = await call('POST', `/api/v1/payments/${id}/capture`, { body: { expectedAmount: { amountMinor: 10000, currency: 'EGP' } } });
  ok('capture', cap.status === 200, `${cap.status}`);
  ok('fee is 300 bps of gross (3.00 EGP) and net is the remainder',
     cap.body?.platformFee?.amountMinor === 300 && cap.body?.netAmount?.amountMinor === 9700
       && cap.body?.amount?.amountMinor === cap.body?.netAmount?.amountMinor + cap.body?.platformFee?.amountMinor,
     `fee=${cap.body?.platformFee?.amountMinor} net=${cap.body?.netAmount?.amountMinor}`);

  // Double capture.
  const again = await call('POST', `/api/v1/payments/${id}/capture`, { body: { expectedAmount: { amountMinor: 10000, currency: 'EGP' } } });
  ok('double capture is 409', again.status === 409, `got ${again.status} ${again.body?.code}`);

  // Drain the simulator's webhook queue for this transaction so the payment settles.
  // Deliver every simulator-queued event for this transaction as a properly signed webhook.
  let delivered = 0;
  for (let page = 0; page < 40; page++) {
    const events = await call('GET', '/api/v1/provider/events?limit=200');
    const rows = (Array.isArray(events.body) ? events.body : []).filter(e =>
      typeof e.body === 'string' && e.body.includes(extId));
    if (rows.length === 0) break;
    for (const ev of rows) {
      const raw = ev.body;
      const ts = Math.floor(Date.now() / 1000).toString();
      const r = await call('POST', '/api/v1/provider/webhooks', {
        token: null, raw,
        headers: { 'X-Provider-Event-Id': ev.eventId, 'X-Provider-Timestamp': ts, 'X-Provider-Signature': sign(ts, raw) },
      });
      if (r.status === 202) delivered++;
    }
  }
  ok('the PSP events for this transaction were accepted as signed webhooks', delivered > 0, `accepted=${delivered}`);
  // Let the worker drain.
  let state = null;
  for (let i = 0; i < 40; i++) {
    const p = await call('GET', `/api/v1/payments/${id}`);
    state = p.body?.state;
    if (state === 'SETTLED') break;
    await new Promise(r => setTimeout(r, 500));
  }
  ok('settlement moved the payment to SETTLED', state === 'SETTLED', `state=${state}`);

  const batch = await call('POST', '/api/v1/reconciliation/batches', {
    token: ADMIN,
    body: { provider: 'SIMULATED_PSP', windowStart: new Date(Date.now() - 86400000).toISOString(), windowEnd: new Date(Date.now() + 86400000).toISOString(), subjectMode: 'BOTH', async: false },
  });
  ok('inline reconciliation batch completes', batch.status === 200 && batch.body?.status === 'COMPLETED', `${batch.status} ${batch.body?.status}`);

  const results = await call('GET', `/api/v1/reconciliation/batches/${batch.body?.id}/results?limit=200`);
  const rows = Array.isArray(results.body) ? results.body : (results.body?.items || []);
  const mine = rows.find(r => r.subjectKey === `SIMULATED_PSP:${extId}`);
  ok('this payment reconciles MATCHED', mine?.outcome === 'MATCHED', `outcome=${mine?.outcome} of ${rows.length} results`);

  const payout = await call('POST', '/api/v1/payouts', {
    token: ADMIN, idem: uuid(),
    body: { merchantReference: `VV-${RUN}-demo`, paymentIds: [id] },
  });
  ok('payout executes', payout.status === 201, `${payout.status} ${payout.text?.slice(0, 120)}`);

  const again2 = await call('POST', '/api/v1/payouts', { token: ADMIN, idem: uuid(), body: { merchantReference: `VV-${RUN}-demo`, paymentIds: [id] } });
  ok('a payment cannot be paid out twice (L11)', again2.status === 409, `got ${again2.status} ${again2.body?.code}`);

  const refund = await call('POST', `/api/v1/payments/${id}/refund`, { body: { reason: 'vv after payout' } });
  ok('refund after payout is refused (D10 / PAYMENT_ALREADY_PAID_OUT)', refund.status === 409, `got ${refund.status} ${refund.body?.code}`);

  demo = { id, extId, batchId: batch.body?.id };
}

// ---------------------------------------------------------------- E. concurrency
section('E. concurrency (spec 02 §3, ADR-0002)');
{
  const created = await call('POST', '/api/v1/payments', {
    idem: uuid(),
    body: { merchantReference: `VV-${RUN}-conc`, amount: { amountMinor: 10000, currency: 'EGP' } },
  });
  const id = created.body.id;
  await call('POST', `/api/v1/payments/${id}/authorize`, { body: {} });

  const results = await Promise.all(Array.from({ length: 16 }, () =>
    call('POST', `/api/v1/payments/${id}/capture`, { body: { expectedAmount: { amountMinor: 10000, currency: 'EGP' } } })));
  const successes = results.filter(r => r.status === 200).length;
  const conflicts = results.filter(r => r.status === 409).length;
  ok('16 concurrent captures yield exactly one success', successes === 1 && successes + conflicts === 16,
     `${successes}x200 ${conflicts}x409`);
}
{
  // 24 concurrent identical idempotent creates.
  const key = uuid();
  const body = { merchantReference: `VV-${RUN}-race`, amount: { amountMinor: 7000, currency: 'EGP' } };
  const results = await Promise.all(Array.from({ length: 24 }, () => call('POST', '/api/v1/payments', { idem: key, body })));
  const ids = new Set(results.map(r => r.body?.id).filter(Boolean));
  const list = await call('GET', `/api/v1/payments?merchantReference=VV-${RUN}-race`);
  const rows = (list.body?.items || []).filter(p => p.merchantReference === `VV-${RUN}-race`).length;
  ok('24 concurrent retries of one key create exactly one payment', rows === 1, `rows=${rows}, distinct ids=${ids.size}`);
}

// ---------------------------------------------------------------- F. webhook authenticity
section('F. webhook authenticity, replay and ordering (spec 03 B1-B5)');
{
  const raw = JSON.stringify({
    type: 'payment.captured',
    occurredAt: new Date().toISOString(),
    providerTransactionId: 'psp_vv_never_registered',
    merchantReference: 'VV-NONE',
    gross: { currency: 'EGP', amountMinor: 10000 },
    fee: { currency: 'EGP', amountMinor: 300 },
    net: { currency: 'EGP', amountMinor: 9700 },
    status: 'CAPTURED',
  });
  const ts = Math.floor(Date.now() / 1000).toString();

  const badSig = await call('POST', '/api/v1/provider/webhooks', {
    token: null, raw, headers: { 'X-Provider-Event-Id': 'vv-badsig', 'X-Provider-Timestamp': ts, 'X-Provider-Signature': 'a'.repeat(64) },
  });
  ok('invalid signature is 401', badSig.status === 401, `got ${badSig.status} ${badSig.body?.code}`);

  const old = (Math.floor(Date.now() / 1000) - 3600).toString();
  const stale = await call('POST', '/api/v1/provider/webhooks', {
    token: null, raw, headers: { 'X-Provider-Event-Id': 'vv-stale', 'X-Provider-Timestamp': old, 'X-Provider-Signature': sign(old, raw) },
  });
  ok('correctly signed but stale timestamp is 401 (replay window works)', stale.status === 401, `got ${stale.status} ${stale.body?.code}`);

  const noHeaders = await call('POST', '/api/v1/provider/webhooks', { token: null, raw });
  ok('missing signature headers is 4xx, not 500', noHeaders.status >= 400 && noHeaders.status < 500, `got ${noHeaders.status}`);

  const eventId = `vv-${RUN}-dup`;
  const s1 = Math.floor(Date.now() / 1000).toString();
  const r1 = await call('POST', '/api/v1/provider/webhooks', { token: null, raw, headers: { 'X-Provider-Event-Id': eventId, 'X-Provider-Timestamp': s1, 'X-Provider-Signature': sign(s1, raw) } });
  const s2 = Math.floor(Date.now() / 1000).toString();
  const r2 = await call('POST', '/api/v1/provider/webhooks', { token: null, raw, headers: { 'X-Provider-Event-Id': eventId, 'X-Provider-Timestamp': s2, 'X-Provider-Signature': sign(s2, raw) } });
  ok('first delivery is 202', r1.status === 202, `got ${r1.status}`);
  ok('duplicate delivery is 200 (a duplicate is a success)', r2.status === 200, `got ${r2.status}`);
}

// ---------------------------------------------------------------- G. ledger read models
section('G. ledger read models and L7 self-verification');
for (const code of ['PSP_CLEARING', 'PLATFORM_CASH', 'MERCHANT_PAYABLE', 'PLATFORM_FEE_REVENUE']) {
  const r = await call('GET', `/api/v1/accounts/${code}?currency=EGP`);
  ok(`GET /accounts/${code} reports verified=true`, r.status === 200 && r.body?.verified === true,
     `status=${r.status} verified=${r.body?.verified} balance=${r.body?.balance?.amountMinor}`);
}
{
  const r = await call('GET', '/api/v1/ledger/transactions/ltx_definitely_not_real');
  ok('unknown ledger transaction is 404, never an empty 200', r.status === 404, `got ${r.status}`);
}
{
  const acct = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?currency=EGP&limit=5');
  ok('statement of account returns entries', acct.status === 200 && Array.isArray(acct.body), `got ${acct.status}`);
  // Spec 06 section 5: "Keyset pagination everywhere ... { items, nextCursor }" and
  // "List responses are capped at 200 items; a larger request is a 400".
  const capped = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?currency=EGP&limit=500');
  ok('entries list is capped at 200 per spec 06 §5', capped.status === 400, `limit=500 -> ${capped.status}`);
  const p1 = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?currency=EGP&limit=2');
  const p2 = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?currency=EGP&limit=2&cursor=abc123');
  const p3 = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?currency=EGP&limit=2&cursor=zzzzzzzz');
  ok('entries list honours a cursor (keyset pagination, spec 06 §5)', p1.text !== p2.text, 'same page returned for any cursor value');
}

// ---------------------------------------------------------------- H. reconciliation cases
section('H. reconciliation case lifecycle (spec 04 §6)');
{
  const cases = await call('GET', '/api/v1/reconciliation/cases?status=OPEN');
  const list = cases.body?.items || [];
  ok('case listing works', cases.status === 200, `open cases=${list.length}`);
  if (list.length > 0) {
    const c = list[0];
    const noNote = await call('POST', `/api/v1/reconciliation/cases/${c.id}/resolve`, { token: ADMIN, body: { resolutionNote: 'too short' } });
    ok('resolve without a 20-char note is refused', noNote.status === 422, `got ${noNote.status} ${noNote.body?.code}`);
    const bogusAdj = await call('POST', `/api/v1/reconciliation/cases/${c.id}/resolve`, {
      token: ADMIN, body: { resolutionNote: 'A note that is comfortably longer than twenty characters.', adjustmentLedgerTransactionId: 'ltx_not_real' },
    });
    ok('resolve citing a nonexistent adjustment is refused', bogusAdj.status === 422, `got ${bogusAdj.status} ${bogusAdj.body?.code}`);
    const wo = await call('POST', `/api/v1/reconciliation/cases/${c.id}/write-off`, { token: ADMIN, body: { resolutionAction: 'PROVIDER_ERROR_CONFIRMED' } });
    ok('write-off without a note is refused', wo.status >= 400 && wo.status < 500, `got ${wo.status}`);
  } else {
    console.log('      (no open cases in this environment; case guards not exercised over HTTP)');
  }
}

// ---------------------------------------------------------------- I. audit trail
section('I. audit trail (spec 06 §1 operations)');
{
  if (demo?.id) {
    const tl = await call('GET', `/api/v1/payments/${demo.id}/timeline`);
    ok('timeline returns the payment state history', tl.status === 200 && Array.isArray(tl.body) && tl.body.length > 0,
       `entries=${Array.isArray(tl.body) ? tl.body.length : 'n/a'}`);
    // Spec 02 §7: the timeline "merges the state history, the ledger transactions for this
    // payment, the provider events that mention it, and the reconciliation results, ordered by
    // time, each with its correlation id."
    const hasLedger = tl.status === 200 && tl.body.some(e => /ltx_|ledger/i.test(JSON.stringify(e)));
    const hasCorrelation = tl.status === 200 && tl.body.some(e => 'correlationId' in e || 'requestId' in e);
    ok('timeline merges ledger transactions as spec 02 §7 claims', hasLedger, 'observed kinds: ' + [...new Set((tl.body || []).map(e => e.triggerType))].join(','));
    ok('timeline carries a correlation/request id as spec 02 §7 claims', hasCorrelation, `keys=${[...new Set((tl.body || []).flatMap(e => Object.keys(e)))].join(',')}`);
  }
  const audit = await call('GET', '/api/v1/audit/events?limit=5');
  ok('audit events are queryable', audit.status === 200, `got ${audit.status}`);
  const byEntity = await call('GET', `/api/v1/audit/entities/PAYMENT/${demo?.id}`);
  ok('entity-scoped audit works (uppercase type, as the route requires)', byEntity.status === 200, `got ${byEntity.status}`);
  const documented = await call('GET', `/api/v1/audit/entities/payments/${demo?.id}`);
  ok('the path printed in spec 06 §1 (lowercase "payments") works', documented.status === 200, `got ${documented.status}`);
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
