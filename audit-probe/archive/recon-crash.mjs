// Independent audit probe: does a payment that is never captured but has a
// provider_transactions row crash the reconciliation batch?
//
// Hypothesis (static review of ReconciliationService.expectedView):
//   loadSubject() calls expectedView() for EVERY payment carrying the subject's
//   provider_transaction_id, regardless of state. expectedView() runs
//     SELECT currency FROM ledger_transactions WHERE id =
//       (SELECT ledger_transaction_id FROM payment_ledger_transactions
//         WHERE payment_id = ? AND role = 'CAPTURE')
//   with .orElseThrow(IllegalStateException). A payment that never captured has
//   no CAPTURE link -> IllegalStateException -> the whole subject transaction dies.
//
// Reachability: upsertProviderTransaction() runs BEFORE the state decision, so a
// `payment.captured` webhook delivered against a FAILED payment writes the
// provider_transactions row and then decides NO_EFFECT. providerExternalIds()
// then includes that row in any window covering its captured_at.

const B = 'http://127.0.0.1:8080';
const OP = { Authorization: 'Bearer dev-operator-token' };
const AD = { Authorization: 'Bearer dev-admin-token' };
const J = { 'Content-Type': 'application/json' };

async function call(path, opts = {}) {
  const r = await fetch(B + path, opts);
  const text = await r.text();
  let json; try { json = JSON.parse(text); } catch { json = text; }
  return { status: r.status, json, text, headers: r.headers };
}

const nonce = Math.random().toString(36).slice(2, 10);
const MR = 'RECON-BOOM-' + nonce;

console.log('== 1. register with the simulated PSP ==');
const reg = await call('/api/v1/provider/payments', {
  method: 'POST', headers: { ...OP, ...J },
  body: JSON.stringify({ merchantReference: MR, grossAmountMinor: 10000, currency: 'EGP' }),
});
const ptx = reg.json.externalTransactionId;
console.log('   providerTransactionId =', ptx);

console.log('== 2. create OUR payment bound to that provider transaction ==');
const created = await call('/api/v1/payments', {
  method: 'POST', headers: { ...OP, ...J, 'Idempotency-Key': 'rc-' + nonce },
  body: JSON.stringify({
    merchantReference: MR, amount: { amountMinor: 10000, currency: 'EGP' },
    providerTransactionId: ptx,
  }),
});
const pid = created.json.id;
console.log('   payment =', pid, 'state =', created.json.state);

console.log('== 3. fail it (CREATED -> FAILED, legal) so it can never be captured ==');
const failed = await call(`/api/v1/payments/${pid}/fail`, {
  method: 'POST', headers: { ...AD, ...J }, body: JSON.stringify({ failureCode: 'DECLINED' }),
});
console.log('   fail status =', failed.status, '-> state', failed.json.state);

console.log('== 4. deliver the simulator\'s payment.captured webhook verbatim ==');
const evs = await call(`/api/v1/provider/events?providerTransactionId=${ptx}`, { headers: OP });
const ev = evs.json[0];
console.log('   event', ev.eventId, ev.type);

const wh = await call('/api/v1/provider/webhooks', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-Provider-Event-Id': ev.eventId,
    'X-Provider-Timestamp': ev.signTimestamp,
    'X-Provider-Signature': ev.signature,
  },
  body: ev.body,                       // exact raw bytes the provider signed
});
console.log('   webhook status =', wh.status);

console.log('== 5. wait for the inbound worker to apply it ==');
await new Promise(r => setTimeout(r, 9000));

console.log('== 6. run a reconciliation batch over a window that covers it ==');
const end = new Date(Date.now() + 3600_000).toISOString();
const start = new Date(Date.now() - 3600_000).toISOString();
const batch = await call('/api/v1/reconciliation/batches', {
  method: 'POST', headers: { ...AD, ...J },
  body: JSON.stringify({
    provider: 'SIMULATED_PSP', windowStart: start, windowEnd: end,
    subjectMode: 'BOTH', async: false,
  }),
});
console.log('   batch status =', batch.status);
console.log('   batch body   =', JSON.stringify(batch.json).slice(0, 700));

if (batch.json && batch.json.id) {
  const after = await call(`/api/v1/reconciliation/batches/${batch.json.id}`, { headers: OP });
  console.log('   batch after  =', JSON.stringify(after.json).slice(0, 700));
}

console.log('\n== 7. what the system now believes ==');
const results = await call(
  `/api/v1/reconciliation/batches/${batch.json?.id ?? 'x'}/results?limit=100`, { headers: OP });
console.log('   results status =', results.status);
console.log('   results =', JSON.stringify(results.json).slice(0, 900));
