// Adversarial probe for the remediated findings, against a running deployment.
//
//   node audit-probe/vv-remediation.mjs
//
//   PROBE_BASE=http://127.0.0.1:18080 node audit-probe/vv-remediation.mjs
//
// Requires the stack started with the committed dev tokens:
//   docker compose up -d --build app
//
// WHY THIS FILE EXISTS AND WHY THE OLD ONES DID NOT SURVIVE
//
// The previous probe set reported 8 failures across two files, all of them the probe's own fault:
// expectations contradicted by the OpenAPI document or by the probe's own constraints elsewhere in
// the same file. That is not a harmless result. A probe that reliably produces false failures is
// not evidence of anything, and a reviewer cannot tell which of its failures are real without
// re-deriving the contract by hand — so those files were archived (see ../audit-probe/README.md)
// rather than left in place to be misread.
//
// This probe is written to the corrected contract only, and it asserts the SPECIFIC behaviour each
// finding required rather than "the request fails". Every check below corresponds to a claim in
// docs/spec and to a regression test in the Maven suite; the probe's job is to confirm the claim
// holds over a real socket, not to be the only place it is checked.

import { createHmac } from 'node:crypto';
import net from 'node:net';

const BASE = process.env.PROBE_BASE || 'http://127.0.0.1:8080';
const OP = process.env.RECONCILE_OPERATOR_TOKEN || 'dev-operator-token';
const ADMIN = process.env.RECONCILE_ADMIN_TOKEN || 'dev-admin-token';
const SECRET = process.env.RECONCILE_PSP_WEBHOOK_SECRET || 'dev-webhook-secret';
const MAX_BODY = 256 * 1024;

let pass = 0;
let fail = 0;
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
  let parsed = null;
  const text = await res.text();
  try { parsed = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, body: parsed, text, headers: res.headers };
}

// The corrected scheme: HMAC over `timestamp + "." + eventId + "." + rawBody`.
function sign(ts, eventId, raw) {
  return createHmac('sha256', SECRET).update(`${ts}.${eventId}.${raw}`).digest('hex');
}

function uuid(prefix = 'px') { return `${prefix}-${RUN}-${Math.random().toString(36).slice(2, 10)}`; }

async function postWebhook(eventId, payload, { timestamp = nowTs(), signature } = {}) {
  return call('POST', '/api/v1/provider/webhooks', {
    token: null,
    raw: payload,
    headers: {
      'X-Provider-Event-Id': eventId,
      'X-Provider-Timestamp': timestamp,
      'X-Provider-Signature': signature ?? sign(timestamp, eventId, payload),
    },
  });
}

function nowTs() { return String(Math.floor(Date.now() / 1000)); }

/**
 * Registers, authorizes and captures a payment, returning {paymentId, providerTxId}.
 *
 * Order matters and is the documented contract (spec 06 §1, ProviderSimulatorController#register):
 * the SIMULATOR assigns the provider's view first and hands back `externalTransactionId`, and the
 * payment is then created against that id. An earlier version of this helper created the payment
 * first and tried to read `providerTransactionId` back off the payment afterwards - a field the
 * create response never carries - and posted the registration to `/api/v1/provider/simulator/
 * payments`, which is not a route: the controller is mapped at `/api/v1/provider`. Both mistakes
 * made the probe throw on its first call and report nothing about the findings it exists to probe,
 * which is the exact failure mode that got the previous probe set archived.
 */
async function capturedPayment(amountMinor, currency = 'EGP') {
  const reference = uuid('MERCH');

  const registered = await call('POST', '/api/v1/provider/payments', {
    body: { merchantReference: reference, grossAmountMinor: amountMinor, currency },
  });
  if (registered.status !== 201) {
    throw new Error(`simulator register failed: ${registered.status} ${registered.text}`);
  }
  const providerTxId = registered.body?.externalTransactionId;
  if (!providerTxId) {
    throw new Error(`no externalTransactionId in ${registered.text}`);
  }

  const created = await call('POST', '/api/v1/payments', {
    idem: uuid('idm'),
    body: {
      merchantReference: reference,
      amount: { amountMinor, currency },
      provider: 'SIMULATED_PSP',
      providerTransactionId: providerTxId,
    },
  });
  if (created.status !== 201) throw new Error(`payment create failed: ${created.status} ${created.text}`);
  const id = created.body.id;

  const authorized = await call('POST', `/api/v1/payments/${id}/authorize`, {});
  if (authorized.status !== 200 && authorized.status !== 201) {
    throw new Error(`authorize failed: ${authorized.status} ${authorized.text}`);
  }
  const captured = await call('POST', `/api/v1/payments/${id}/capture`, {});
  if (captured.status !== 200 && captured.status !== 201) {
    throw new Error(`capture failed: ${captured.status} ${captured.text}`);
  }
  return { paymentId: id, providerTxId, reference };
}

function settlementPayload({ id, currency, gross, fee, net, lines }) {
  return JSON.stringify({
    type: 'settlement.paid',
    occurredAt: new Date().toISOString(),
    settlement: {
      providerSettlementId: id,
      settlementDate: new Date().toISOString().slice(0, 10),
      currency,
      grossAmountMinor: gross,
      feeAmountMinor: fee,
      netAmountMinor: net,
      lines,
    },
  });
}

/**
 * Waits until `eventId` has reached a settled state, instead of assuming a fixed interval.
 *
 * This used to poll `/api/v1/provider/events/failed` and return as soon as it answered 200 - which
 * is true within milliseconds of startup, before the worker has claimed anything. So it drained
 * nothing, and every assertion made immediately afterwards was a race that the probe lost. A helper
 * whose name says "drain" and which does not is the most expensive kind of probe bug: it looks like
 * synchronisation and is really a coin flip.
 */
async function settleDrain(eventId, maxMs = 30000) {
  const deadline = Date.now() + maxMs;
  while (Date.now() < deadline) {
    const backlog = await call('GET', '/api/v1/provider/events/failed', { token: ADMIN });
    const row = backlog.body?.events?.find((e) => e.eventId === eventId);
    if (row) return row;
    await new Promise((r) => setTimeout(r, 500));
  }
  return null;
}

/** Waits until the batch is no longer RUNNING, so results are read after they are written. */
async function waitForBatch(batchId, maxMs = 60000) {
  const deadline = Date.now() + maxMs;
  let detail = null;
  while (Date.now() < deadline) {
    detail = await call('GET', `/api/v1/reconciliation/batches/${batchId}`, { token: ADMIN });
    if (detail.status === 200 && detail.body?.status !== 'RUNNING') return detail.body;
    await new Promise((r) => setTimeout(r, 500));
  }
  return detail?.body ?? null;
}

// ==================================================================== F-05: capture currency
section('F-05: a capture expectation in the wrong currency is refused');

{
  const created = await call('POST', '/api/v1/payments', {
    idem: uuid('idm'),
    body: { merchantReference: uuid('MERCH'), amount: { amountMinor: 20000, currency: 'EGP' } },
  });
  const id = created.body.id;
  await call('POST', `/api/v1/payments/${id}/authorize`, {});

  const wrong = await call('POST', `/api/v1/payments/${id}/capture`, {
    body: { expectedAmount: { amountMinor: 20000, currency: 'USD' } },
  });
  ok('USD expectation against an EGP authorisation is refused',
    wrong.status === 409 && wrong.text.includes('CAPTURE_AMOUNT_MISMATCH'),
    `status=${wrong.status}`);

  const state = await call('GET', `/api/v1/payments/${id}`);
  ok('the refused capture left the payment AUTHORIZED',
    state.body?.state === 'AUTHORIZED', `state=${state.body?.state}`);

  const right = await call('POST', `/api/v1/payments/${id}/capture`, {
    body: { expectedAmount: { amountMinor: 20000, currency: 'EGP' } },
  });
  ok('the same figure in the payment currency is accepted', right.status === 200,
    `status=${right.status}`);
}

// ==================================================================== F-01/F-02: settlement validity
section('F-01/F-02: an inconsistent settlement record must not move value or reconcile MATCHED');

{
  const egp = await capturedPayment(10000, 'EGP');

  // Cross-currency. Signed, fresh, correct shape, and every field internally consistent —
  // the ONLY thing wrong is that it claims USD for an EGP payment.
  const crossId = uuid('sset');
  const crossPayload = settlementPayload({
    id: crossId,
    currency: 'USD',
    gross: 10000, fee: 300, net: 9700,
    lines: [{ providerTransactionId: egp.providerTxId, grossAmountMinor: 10000, netAmountMinor: 9700 }],
  });

  const crossEventId = uuid('evt');
  const accepted = await postWebhook(crossEventId, crossPayload);
  ok('the cross-currency settlement is accepted for ingestion',
    accepted.status === 202, `status=${accepted.status}`);
  await settleDrain(crossEventId);

  const after = await call('GET', `/api/v1/payments/${egp.paymentId}`);
  ok('F-01: the EGP payment was NOT settled', after.body?.state === 'CAPTURED',
    `state=${after.body?.state} (SETTLED would mean the currency mismatch was ignored)`);

  const batch = await call('POST', '/api/v1/reconciliation/batches', {
    token: ADMIN,
    body: { provider: 'SIMULATED_PSP', windowStart: new Date(Date.now() - 86400000).toISOString(),
            windowEnd: new Date(Date.now() + 86400000).toISOString() },
  });
  ok('a reconciliation batch runs over the window', batch.status === 201 || batch.status === 202,
    `status=${batch.status}`);

  // Results live at their own endpoint: GET /batches/{id} returns the batch's summary counters and
  // has no `results` member at all. Reading `detail.body.results` therefore yielded undefined and
  // every assertion below it reported "no result row found" for a batch that had finished
  // normally.
  const finished = await waitForBatch(batch.body.id);
  const rows = await call('GET', `/api/v1/reconciliation/batches/${batch.body.id}/results`,
    { token: ADMIN });
  const result = (Array.isArray(rows.body) ? rows.body : [])
    .find((r) => r.providerTransactionId === egp.providerTxId || r.subjectKey?.endsWith(egp.providerTxId));
  ok('the batch reached a terminal status rather than being abandoned',
    finished !== null && finished.status !== 'RUNNING', `status=${finished?.status}`);
  ok('F-01/F-02: reconciliation examined the transaction', Boolean(result),
    result ? `outcome=${result.outcome}` : 'no result row found');
  ok('F-01: reconciliation did NOT report MATCHED',
    result && result.outcome !== 'MATCHED',
    `outcome=${result?.outcome}`);
  ok('F-02/F-01: the finding is SETTLEMENT_RECORD_INVALID',
    result?.outcome === 'SETTLEMENT_RECORD_INVALID', `outcome=${result?.outcome}`);
}

{
  // Aggregate mismatch: currency agrees, gross agrees, net does not. This is the exact record the
  // audit found — record net 9600 over a line whose net is 9700.
  const egp2 = await capturedPayment(10000, 'EGP');
  const aggId = uuid('sset');
  const aggPayload = settlementPayload({
    id: aggId,
    currency: 'EGP',
    gross: 10000, fee: 400, net: 9600,
    lines: [{ providerTransactionId: egp2.providerTxId, grossAmountMinor: 10000, netAmountMinor: 9700 }],
  });

  const aggEventId = uuid('evt');
  await postWebhook(aggEventId, aggPayload);
  await settleDrain(aggEventId);

  const after = await call('GET', `/api/v1/payments/${egp2.paymentId}`);
  ok('F-02: a record whose totals contradict its lines settles nothing',
    after.body?.state === 'CAPTURED', `state=${after.body?.state}`);
}

// ==================================================================== F-06: event identity
section('F-06: the event id is inside the signed material');

{
  const payload = JSON.stringify({
    type: 'payment.captured',
    occurredAt: new Date().toISOString(),
    providerTransactionId: 'psp_' + uuid(),
    merchantReference: uuid('MERCH'),
    gross: { amountMinor: 10000, currency: 'EGP' },
    net: { amountMinor: 9700, currency: 'EGP' },
    status: 'CAPTURED',
  });
  const ts = nowTs();
  const originalId = uuid('evt');
  const replayId = uuid('evt');

  const first = await postWebhook(originalId, payload, { timestamp: ts });
  ok('the genuine delivery is accepted', first.status === 202, `status=${first.status}`);

  const replay = await call('POST', '/api/v1/provider/webhooks', {
    token: null,
    raw: payload,
    headers: {
      'X-Provider-Event-Id': replayId,
      'X-Provider-Timestamp': ts,
      // The provider's own signature, replayed verbatim under a fresh identity.
      'X-Provider-Signature': sign(ts, originalId, payload),
    },
  });
  ok('F-06: replaying a valid signature under a new event id is refused',
    replay.status === 401 && replay.text.includes('WEBHOOK_REJECTED_SIGNATURE'),
    `status=${replay.status}`);

  const retry = await postWebhook(originalId, payload, { timestamp: ts });
  ok('the provider\'s own retry (same id, same bytes) is still accepted',
    retry.status === 202 || retry.status === 200, `status=${retry.status}`);
}

// ==================================================================== F-07: terminal events
section('F-07: a permanently unusable event is terminal, not retried forever');

{
  const eventId = uuid('evt');
  const payload = JSON.stringify({
    type: 'payment.teleported',
    occurredAt: new Date().toISOString(),
    providerTransactionId: 'psp_' + uuid(),
  });
  await postWebhook(eventId, payload);
  const row = await settleDrain(eventId, 30000);

  ok('the poison event is on the operations backlog', Boolean(row), JSON.stringify(row ?? null));
  ok('F-07: it is DEAD, not FAILED', row?.status === 'DEAD', `status=${row?.status}`);
  ok('F-07: it reached its answer on attempt 1, not 8', row?.attempts === 1,
    `attempts=${row?.attempts}`);
}

// ==================================================================== F-04: bounded body
section('F-04: an undeclared (chunked) body over the limit is refused and recorded');

{
  const eventId = uuid('evt');
  const padding = 'x'.repeat(MAX_BODY + 1024);
  const payload = JSON.stringify({
    type: 'payment.captured',
    occurredAt: new Date().toISOString(),
    providerTransactionId: 'psp_' + uuid(),
    merchantReference: padding,
    gross: { amountMinor: 10000, currency: 'EGP' },
    net: { amountMinor: 9700, currency: 'EGP' },
    status: 'CAPTURED',
  });

  const status = await chunkedPost(eventId, payload);
  ok('F-04: a chunked body over the limit is 413', status === 413, `status=${status}`);

  const under = await chunkedPost(uuid('evt'), JSON.stringify({
    type: 'payment.captured',
    occurredAt: new Date().toISOString(),
    providerTransactionId: 'psp_' + uuid(),
    merchantReference: 'small',
    gross: { amountMinor: 10000, currency: 'EGP' },
    net: { amountMinor: 9700, currency: 'EGP' },
    status: 'CAPTURED',
  }));
  ok('F-04: a chunked body under the limit is accepted', under === 202, `status=${under}`);
}

/**
 * Sends a webhook with `Transfer-Encoding: chunked` and no `Content-Length`.
 *
 * Written against a raw socket because `fetch` and `node:http` both set `Content-Length` for a body
 * they already hold, so neither can express the case that F-04 was about.
 */
function chunkedPost(eventId, body) {
  return new Promise((resolve, reject) => {
    const payload = Buffer.from(body, 'utf8');
    const ts = nowTs();
    const url = new URL(BASE);
    const port = url.port || (url.protocol === 'https:' ? 443 : 80);

    const socket = net.connect({ host: url.hostname, port: Number(port) }, () => {
      const head = [
        'POST /api/v1/provider/webhooks HTTP/1.1',
        `Host: ${url.hostname}:${port}`,
        'Content-Type: application/json',
        `X-Provider-Event-Id: ${eventId}`,
        `X-Provider-Timestamp: ${ts}`,
        `X-Provider-Signature: ${sign(ts, eventId, body)}`,
        'Transfer-Encoding: chunked',
        'Connection: close',
        '', '',
      ].join('\r\n');
      socket.write(head, 'ascii');

      const write = () => {
        try {
          const CHUNK = 8192;
          for (let off = 0; off < payload.length; off += CHUNK) {
            const size = Math.min(CHUNK, payload.length - off);
            socket.write(`${size.toString(16)}\r\n`, 'ascii');
            socket.write(payload.subarray(off, off + size));
            socket.write('\r\n', 'ascii');
          }
          socket.write('0\r\n\r\n', 'ascii');
        } catch { /* the server refused and closed; the response below is the evidence */ }
      };
      try { write(); } catch { /* connection already gone */ }
    });

    let buffer = Buffer.alloc(0);
    socket.setTimeout(20000, () => { socket.destroy(); reject(new Error('chunked post timed out')); });
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const marker = buffer.indexOf('\r\n');
      if (marker > 0) {
        const status = Number(buffer.subarray(0, marker).toString().split(' ')[1]);
        socket.destroy();
        resolve(status);
      }
    });
    socket.on('error', reject);
    socket.on('close', () => {
      const marker = buffer.indexOf('\r\n');
      if (marker > 0) resolve(Number(buffer.subarray(0, marker).toString().split(' ')[1]));
    });
  });
}

// ==================================================================== verdict
console.log(`\n== verdict ==`);
console.log(`${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);