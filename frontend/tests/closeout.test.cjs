const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../js/app.js'), 'utf8');
function element(initial = {}) {
  return { hidden: false, innerHTML: '', textContent: '', value: '', disabled: false,
    setAttribute() {}, classList: { add() {}, remove() {} }, ...initial };
}
function page(elements = {}, fetchImpl = async () => { throw new Error('Unexpected request'); }) {
  const context = vm.createContext({ fetch: fetchImpl, URLSearchParams,
    window: { location: { hostname: 'localhost', search: '', href: '' } },
    document: { getElementById: id => elements[id] ?? null, querySelectorAll: () => [],
      querySelector: () => null, addEventListener() {} } });
  vm.runInContext(source, context);
  return { context, run: code => vm.runInContext(code, context) };
}
const student = { id: 10, roles: ['GENERAL_USER', 'MAINTENANCE_STAFF'], activeRole: 'GENERAL_USER' };
const tech = { id: 20, roles: ['GENERAL_USER', 'MAINTENANCE_STAFF'], activeRole: 'MAINTENANCE_STAFF' };
const admin = { id: 1, roles: ['ADMINISTRATOR', 'GENERAL_USER'], activeRole: 'ADMINISTRATOR' };
const ticket = { id: 7, submittedById: 10, assignedTechnicianId: 20, status: 'IN_PROGRESS' };

test('conversation allows only the owner or assigned technician in the correct workspace and freezes ended requests', () => {
  const p = page();
  Object.assign(p.context, { student, tech, admin, ticket });
  assert.match(p.run('renderTicketConversation(ticket, student)'), /id="ticket-comment-form"/);
  assert.match(p.run('renderTicketConversation(ticket, tech)'), /id="ticket-comment-form"/);
  assert.doesNotMatch(p.run('renderTicketConversation(ticket, admin)'), /id="ticket-comment-form"/);
  assert.doesNotMatch(p.run("renderTicketConversation(ticket, {...tech, activeRole: 'GENERAL_USER'})"), /id="ticket-comment-form"/);
  for (const status of ['COMPLETED', 'CLOSED', 'CANCELLED']) {
    p.context.status = status;
    assert.doesNotMatch(p.run('renderTicketConversation({...ticket, status}, student)'), /id="ticket-comment-form"/);
    assert.match(p.run('renderTicketConversation({...ticket, status}, student)'), /read-only/);
  }
});

test('messages escape author and body, preserving line breaks as text', () => {
  const list = element();
  const p = page();
  Object.assign(p.context, { list, comments: [{ authorName: '<img onerror=evil()>',
    authorRole: 'GENERAL_USER', body: '<script>evil()</script>\nPlease call first', createdAt: null }] });
  p.run('renderTicketComments(comments, list)');
  assert.doesNotMatch(list.innerHTML, /<script>|<img/);
  assert.match(list.innerHTML, /&lt;script&gt;/);
  assert.match(list.innerHTML, /\nPlease call first/);
});

test('message submission validates length, prevents duplicate submits and retains the draft on failure', async () => {
  const elements = { 'ticket-comment-body': element({ value: '   ' }),
    'ticket-comment-submit': element(), 'ticket-comment-status': element() };
  const requests = [];
  const p = page(elements, async (url, options) => {
    requests.push({ url, options });
    return { ok: false, status: 500, json: async () => ({ message: 'Temporary failure' }) };
  });
  p.context.student = student;
  p.run('currentWorkspaceUser = student');
  await p.run('submitTicketComment({preventDefault() {}}, 7)');
  elements['ticket-comment-body'].value = 'x'.repeat(2001);
  await p.run('submitTicketComment({preventDefault() {}}, 7)');
  assert.equal(requests.length, 0);
  elements['ticket-comment-body'].value = '  Keep my draft  ';
  await p.run('submitTicketComment({preventDefault() {}}, 7)');
  assert.equal(requests.length, 1);
  assert.equal(requests[0].options.headers['X-Active-Role'], 'GENERAL_USER');
  assert.deepEqual(JSON.parse(requests[0].options.body), { body: 'Keep my draft' });
  assert.equal(elements['ticket-comment-body'].value, '  Keep my draft  ');
  assert.match(elements['ticket-comment-status'].textContent, /Temporary failure/);
  elements['ticket-comment-submit'].disabled = true;
  await p.run('submitTicketComment({preventDefault() {}}, 7)');
  assert.equal(requests.length, 1);
});

test('a sent message clears the submitted draft and reloads the conversation', async () => {
  const elements = { 'ticket-comment-body': element({ value: '  Hello  ' }),
    'ticket-comment-submit': element(), 'ticket-comment-status': element(),
    'ticket-comments-list': element(), 'ticket-comments-message': element() };
  const calls = [];
  const p = page(elements, async (url, options) => {
    calls.push(options.method ?? 'GET');
    return { ok: true, status: options.method === 'POST' ? 201 : 200,
      json: async () => options.method === 'POST' ? {} : [{ body: 'Hello', authorName: 'Owner', authorRole: 'GENERAL_USER' }] };
  });
  p.context.student = student;
  p.run('currentWorkspaceUser = student');
  await p.run('submitTicketComment({preventDefault() {}}, 7)');
  assert.deepEqual(calls, ['POST', 'GET']);
  assert.equal(elements['ticket-comment-body'].value, '');
  assert.match(elements['ticket-comments-list'].innerHTML, /Hello/);
  assert.equal(elements['ticket-comment-submit'].disabled, false);
});

test('analytics shows missing samples without fabricated numbers and escapes location and staff labels', () => {
  const p = page();
  const content = element();
  Object.assign(p.context, { content, data: { totalRequests: 0, pendingRequests: 0, completedRequests: 0,
    completionRate: 0, averageCompletionHours: null, completionTimeSamples: 0, averageRating: null,
    ratingCount: 0, requestsByStatus: [], requestsByCategory: [],
    requestsByLocation: [{ label: '<img onerror=evil()>', count: 1 }],
    staffWorkload: [{ name: '<script>evil()</script>', openAssignments: 0 }] } });
  p.run('renderMaintenanceAnalytics(data, content)');
  assert.match(content.innerHTML, /No data yet/);
  assert.match(content.innerHTML, /No ratings yet/);
  assert.match(content.innerHTML, /0\.0%/);
  assert.doesNotMatch(content.innerHTML, /NaN|Infinity|<img|<script>/);
});

test('switching away from administrator clears analytics and ignores its late response', async () => {
  const elements = { 'maintenance-analytics': element(), 'analytics-content': element({ innerHTML: 'Old statistics' }),
    'analytics-message': element() };
  let release;
  let calls = 0;
  const p = page(elements, () => { calls++; return new Promise(resolve => { release = resolve; }); });
  Object.assign(p.context, { admin, student });
  p.run('currentWorkspaceUser = admin');
  const pending = p.run('loadMaintenanceAnalytics(admin)');
  p.run('currentWorkspaceUser = student');
  await p.run('loadMaintenanceAnalytics(student)');
  release({ ok: true, status: 200, json: async () => ({ secret: 'stale admin data' }) });
  await pending;
  assert.equal(calls, 1);
  assert.equal(elements['maintenance-analytics'].hidden, true);
  assert.equal(elements['analytics-content'].innerHTML, '');
});

test('analytics failure leaves a retryable message without blocking the dashboard', async () => {
  const elements = { 'maintenance-analytics': element(), 'analytics-content': element({ innerHTML: 'Old statistics' }),
    'analytics-message': element() };
  const p = page(elements, async () => { throw new Error('Statistics unavailable'); });
  p.context.admin = admin;
  p.run('currentWorkspaceUser = admin');
  await p.run('loadMaintenanceAnalytics(admin)');
  assert.equal(elements['analytics-content'].innerHTML, '');
  assert.match(elements['analytics-message'].textContent, /Statistics unavailable/);
});
