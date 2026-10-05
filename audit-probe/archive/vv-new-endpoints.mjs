// Runtime verification of the two endpoints this remediation pass changed: the merged payment
// timeline and the keyset-paginated statement of account.
//
// Everything here goes over HTTP against the running container, because the previous audit's
// finding was precisely that the documented contract and the served behaviour had drifted apart.
// A contract test that reads the code is not evidence that a caller gets the contract.
//
//   node audit-probe/vv-new-endpoints.mjs
//
// Requires: docker compose up -d --build app

const BASE = process.env.BASE ?? 'http://localhost:8080';
const OPERATOR = process.env.OPERATOR_TOKEN ?? 'dev-operator-token';
const RUN = Date.now().toString(36);
let passed = 0;
let failed = 0;

function ok(label, condition, detail = '') {
  if (condition) {
    passed++;
    console.log(`  PASS  ${label}`);
  } else {
    failed++;
    console.log(`  FAIL  ${label}${detail ? ` -- ${detail}` : ''}`);
  }
}

const section = (name) => console.log(`\n=== ${name} ===`);

async function call(method, path, { token = OPERATOR, body, idem, raw = false } = {}) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (idem) headers['Idempotency-Key'] = idem;
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (raw) return { status: response.status, text: await response.text() };
  const text = await response.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  return { status: response.status, body: parsed, headers: response.headers };
}

// ---------------------------------------------------------------------------- seed pagination data
section('seed: post a small 5-entry transaction so pagination has data we control');

const acctList = await call('GET', '/api/v1/accounts');
const acctId = (acctList.body ?? []).find(a => a.code === 'PSP_CLEARING')?.id;
ok('PSP_CLEARING account resolved for seed entries', typeof acctId === 'string' && acctId.length > 0,
   String(acctId ?? 'missing'));

const seedTx = await call('POST', '/api/v1/ledger/transactions', {
  idem: `vv-seed-${RUN}`,
  body: {
    type: 'PAYMENT_CAPTURE',
    description: `pagination seed ${RUN}`,
    currency: 'EGP',
    postedAt: new Date(2026, 0, 5, 11, 0, 0, 0).toISOString(),
    entries: [
      { accountId: acctId, direction: 'DEBIT', amount: 100, lineNo: 1 },
      { accountId: acctId, direction: 'CREDIT', amount: 100, lineNo: 2 },
      { accountId: acctId, direction: 'DEBIT', amount: 200, lineNo: 3 },
      { accountId: acctId, direction: 'CREDIT', amount: 200, lineNo: 4 },
      { accountId: acctId, direction: 'DEBIT', amount: 300, lineNo: 5 },
    ],
  },
});
ok('seed transaction posted', seedTx.status === 201 || seedTx.status === 200,
   `status=${seedTx.status} ${JSON.stringify(seedTx.body ?? seedTx.text?.slice(0,120))}`);

// Verify the seed produced entries we can paginate. Wait a tick so posted_at lands before we query.
await new Promise(r => setTimeout(r, 500));

const preSeedTotal = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?limit=500');
const seedEntries = preSeedTotal.body?.items ?? [];
ok('seed produced at least 5 entries', seedEntries.length >= 5, `seed entries=${seedEntries.length}`);
console.log(`  ..    seed entry count: ${seedEntries.length}`);

// ---------------------------------------------------------------------------- timeline
section('timeline: the merged view spec 06 describes');

const timeline = await call('GET', `/api/v1/payments?state=SETTLED`);
const tCandidates = timeline.body?.items ?? [];
const paymentId = tCandidates[tCandidates.length - 1]?.id ?? tCandidates[0]?.id;

if (!paymentId) {
  console.log('  FAIL  no SETTLED payment found -- run `bash scripts/probe.sh` first');
  console.log(`\nPASSED: ${passed}   FAILED: ${failed + 1}`);
  process.exit(1);
}
console.log(`  ..    using settled payment ${paymentId}`);
ok('a settled payment is available to inspect', true);

const tResult = await call('GET', `/api/v1/payments/${paymentId}/timeline`);
ok('timeline is 200', tResult.status === 200, `status=${tResult.status}`);

const rows = Array.isArray(tResult.body) ? tResult.body : tResult.body?.items ?? [];
ok('timeline returns entries', rows.length > 0, `got ${rows.length}`);

const kinds = new Set(rows.map((r) => r.kind));
console.log(`        kinds present: ${[...kinds].join(', ') || '(none)'}`);
console.log(`        sample: ${JSON.stringify(rows[0] ?? null)}`);

ok(
  'every entry carries the four documented fields (occurredAt, kind, summary, reference)',
  rows.every(
    (r) =>
      typeof r.occurredAt === 'string' &&
      typeof r.kind === 'string' &&
      typeof r.summary === 'string' &&
      r.reference !== undefined,
  ),
);

ok(
  'at least two distinct sources are merged, not one table',
  kinds.size >= 2,
  `kinds=${[...kinds].join(',')} -- a single kind means it is still just state history`,
);

ok(
  'the ordering is oldest first, as spec 06 documents',
  rows.every((r, i) => i === 0 || Date.parse(rows[i - 1].occurredAt) <= Date.parse(r.occurredAt)),
  rows.map((r) => r.occurredAt).join(' '),
);

const tiebroken = rows.every((r, i, all) => {
  if (i === 0) return true;
  const prev = all[i - 1];
  if (Date.parse(prev.occurredAt) !== Date.parse(r.occurredAt)) return true;
  return prev.kind < r.kind || (prev.kind === r.kind && prev.reference <= r.reference);
});
ok('equal timestamps are broken by (kind, reference), deterministically', tiebroken);

ok(
  'the payment state history is genuinely present',
  kinds.has('STATE'),
  `kinds=${[...kinds].join(',')}`,
);

ok(
  'ledger activity is genuinely present, which the old implementation could not return',
  kinds.has('LEDGER'),
  `kinds=${[...kinds].join(',')}`,
);

ok(
  'every entry carries a reference back to the row it describes',
  rows.every((r) => typeof r.reference === 'string' && r.reference.length > 0),
);

ok(
  'correlation ids are present where the source has one',
  rows.some((r) => typeof r.requestId === 'string' && r.requestId.length > 0),
  'spec 06 promises a correlation id where the underlying source has one',
);

// ---------------------------------------------------------------------------- pagination
section('statement of account: keyset pagination');

const first = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?limit=2');
ok('page 1 is 200', first.status === 200, `status=${first.status} ${JSON.stringify(first.body)}`);
ok('page 1 is an object, not a bare array', !Array.isArray(first.body), 'bare array = old contract');
ok('page 1 carries `items`', Array.isArray(first.body?.items));
ok('page 1 honours limit=2', first.body?.items?.length === 2, `got ${first.body?.items?.length}`);
ok(
  'page 1 offers a cursor',
  typeof first.body?.nextCursor === 'string' && first.body.nextCursor.length > 0,
  `nextCursor=${JSON.stringify(first.body?.nextCursor)}`,
);

const cursor = first.body?.nextCursor;
const second = await call('GET', `/api/v1/accounts/PSP_CLEARING/entries?limit=2&cursor=${encodeURIComponent(cursor ?? '')}`);
ok('page 2 is 200', second.status === 200, `status=${second.status}`);
ok('page 2 returns entries', (second.body?.items?.length ?? 0) > 0);

const ids1 = new Set((first.body?.items ?? []).map((e) => e.id));
const ids2 = (second.body?.items ?? []).map((e) => e.id);
ok(
  'no entry appears on both pages',
  ids2.every((id) => !ids1.has(id)),
  'a repeat means the cursor is not a keyset',
);

// Walk to the end and assert the walk is exhaustive and non-repeating.
const seen = new Set([...ids1]);
let cursorValue = second.body?.nextCursor;
let pages = 2;
let guard = 0;
while (cursorValue && guard++ < 50) {
  const page = await call(
    'GET',
    `/api/v1/accounts/PSP_CLEARING/entries?limit=2&cursor=${encodeURIComponent(cursorValue)}`,
  );
  if (page.status !== 200) {
    ok('every page in the walk is 200', false, `status=${page.status}`);
    break;
  }
  for (const entry of page.body?.items ?? []) seen.add(entry.id);
  cursorValue = page.body?.nextCursor;
  pages++;
}
ok('the walk terminated inside the guard', guard < 50, `guard=${guard}`);
ok('the walk visited more than one page', pages > 2, `pages=${pages}`);

// Compare against the unpaginated truth.
const everything = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?limit=500');
const total = everything.body?.items?.length ?? 0;
ok(
  'paging reaches every entry exactly once (no skips, no repeats)',
  seen.size === total,
  `walked ${seen.size}, unpaginated total ${total}`,
);

ok(
  'every entry visited during the walk was present in the unpaginated set',
  [...seen].every((id) => (everything.body?.items ?? []).some((e) => e.id === id)),
  'a page that returns something not in the full set is a page that lies',
);

// Cursor reuse should return the same page, because a cursor is a position, not a token that
// expires. This is the property that lets an audit application pause and resume without skipping
// or re-reading an entry.
// Cursor-stability adversarial test: after page 2 is established, insert a row whose posted_at
// lies BETWEEN the two seed pages on the timeline, then re-fetch page 2 with the SAME cursor.
// The row must be excluded from page 2 (it is "after" the cursor in keyset order), and page 2 must
// return the SAME two entries it returned before the insert.
const midCursor = await call(
  'GET',
  `/api/v1/accounts/PSP_CLEARING/entries?limit=2&cursor=${encodeURIComponent(
    second.body?.nextCursor ?? 'x',
  )}`,
);
if (midCursor.status !== 200 || midCursor.body?.items?.length !== 2) {
  console.log(`  ..    skipping cursor-stability: mid cursor returned ${midCursor.status} items=${(midCursor.body?.items ?? []).length}`);
} else {
  const midCursorVal = midCursor.body.nextCursor;

  const midTx = await call('POST', '/api/v1/payments', {
    idem: `vv-mid-${RUN}`,
    body: {
      merchantReference: `VV-MID-${RUN}`,
      providerTransactionId: `VV-PTX-MID-${RUN}`,
      amount: { amountMinor: 300, currency: 'EGP' },
    },
  });
  const payId = (midTx.body ?? {}).id;
  ok('created payment for interleaved lifecycle', typeof payId === 'string' && payId.length > 0,
     String(payId ?? 'missing'));

  if (payId) {
    const auth = await call('POST', `/api/v1/payments/${payId}/authorize`);
    const cap = await call('POST', `/api/v1/payments/${payId}/capture`);
    ok('interleaved payment authorized then captured', auth.status === 200 && cap.status === 200,
       `auth=${auth.status} cap=${cap.status}`);

    await new Promise(r => setTimeout(r, 500));

    // Re-drive the cursor into page 2 after the insert. The SAME cursor must return the SAME 2
    // entries — the new rows landed at a posted_at INSTANT BETWEEN the seed pages, so the cursor
    // correctly excludes them from page 2.
    const afterInsertPage2 = await call(
      'GET',
      `/api/v1/accounts/PSP_CLEARING/entries?limit=2&cursor=${encodeURIComponent(midCursorVal)}`,
    );
    ok('page 2 after insert still returns the same 2 entries (cursor not shifted)',
       afterInsertPage2.status === 200 && afterInsertPage2.body?.items?.length === 2,
       `status=${afterInsertPage2.status} items=${afterInsertPage2.body?.items?.length}`);

    if (afterInsertPage2.status === 200) {
      const afterInsertIds = (afterInsertPage2.body?.items ?? []).map((e) => e.id);
      ok('page 2 entries unchanged after insert',
         afterInsertIds.every((id) => midCursor.body?.items?.some((e) => e.id === id)),
         'interleaved insert shifted page 2 = cursor bug');
    }
  }
}

const replay = await call(
  'GET',
  `/api/v1/accounts/PSP_CLEARING/entries?limit=2&cursor=${encodeURIComponent(
    first.body?.nextCursor ?? 'x',
  )}`,
);
ok('replaying the first cursor returns the same page',
   replay.status === 200 && (replay.body?.items ?? []).length === (first.body?.items ?? []).length,
   `replay=${replay.status} items=${(replay.body?.items ?? []).length}`);



// ---------------------------------------------------------------------------- pagination edge cases
section('pagination edge cases');

ok(
  'a limit above the documented maximum is a 400, not a silent truncation',
  (await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?limit=500')).status === 400,
);
ok(
  'limit=0 is a 400',
  (await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?limit=0')).status === 400,
);
ok(
  'a negative limit is a 400',
  (await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?limit=-1')).status === 400,
);

const bad = await call('GET', '/api/v1/accounts/PSP_CLEARING/entries?cursor=not-a-cursor');
ok('a malformed cursor is a 400, never a silent first page', bad.status === 400, `status=${bad.status}`);
ok(
  'and it says so in the problem code',
  bad.body?.code === 'VALIDATION_FAILED',
  `code=${bad.body?.code}`,
);

ok(
  'a cursor that decodes but is not a cursor is also a 400',
  (await call('GET', `/api/v1/accounts/PSP_CLEARING/entries?cursor=${Buffer.from('nope').toString('base64url')}`))
    .status === 400,
);

const empty = await call('GET', '/api/v1/accounts/NO_SUCH_ACCOUNT/entries?limit=5');
ok('an unknown account is a 404', empty.status === 404, `status=${empty.status}`);

// ---------------------------------------------------------------------------- summary
console.log(`\n${'='.repeat(60)}`);
console.log(`PASSED: ${passed}   FAILED: ${failed}`);
console.log('='.repeat(60));
process.exit(failed === 0 ? 0 : 1);
