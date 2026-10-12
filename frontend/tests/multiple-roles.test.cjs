const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../js/app.js'), 'utf8');
function page() {
  const ids = ['workspace-role', 'admin-link', 'role-workspace-message', 'role-workspace-title',
    'role-workspace-description', 'all-requests-section', 'all-requests-title', 'all-requests-description',
    'tickets-message', 'tickets-list', 'all-tickets-message', 'all-tickets-list', 'admin-message'];
  const elements = Object.fromEntries(ids.map(id => [id, {
    hidden: true, textContent: '', innerHTML: '', children: [],
    appendChild(child) { this.children.push(child); }
  }]));
  const requesterSections = [{ hidden: true }, { hidden: true }];
  const context = vm.createContext({
    window: { location: { hostname: '127.0.0.1' } },
    document: {
      getElementById: id => elements[id] ?? null,
      querySelectorAll: () => requesterSections
    }
  });
  vm.runInContext(source, context);
  return { context, elements, requesterSections, run: code => vm.runInContext(code, context) };
}

function richElement(initial = {}) {
  const classes = new Set();
  return {
    hidden: false,
    textContent: '',
    innerHTML: '',
    dataset: {},
    children: [],
    attributes: {},
    classList: {
      add: (...names) => names.forEach(name => classes.add(name)),
      remove: (...names) => names.forEach(name => classes.delete(name)),
      contains: name => classes.has(name)
    },
    appendChild(child) { this.children.push(child); },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    ...initial
  };
}

test('requester + technician sees both work areas and only their tickets in each area', async () => {
  const p = page();
  p.context.testUser = { id: 10, role: 'MAINTENANCE_STAFF', roles: ['GENERAL_USER', 'MAINTENANCE_STAFF'] };
  p.context.testTickets = [
    { id: 1, submittedById: 10, assignedTechnicianId: null },
    { id: 2, submittedById: 20, assignedTechnicianId: 10 },
    { id: 3, submittedById: 10, assignedTechnicianId: 10 }
  ];
  p.run(`
    loadCurrentUser = async () => testUser;
    fetch = async () => ({ ok: true, status: 200, json: async () => testTickets });
    renderTickets = tickets => { ownIds = tickets.map(t => t.id); };
    renderAllTickets = tickets => { assignedIds = tickets.map(t => t.id); };
  `);
  await p.run('initWorkspace()');
  assert.deepEqual(Array.from(p.context.ownIds), [1, 3]);
  assert.deepEqual(Array.from(p.context.assignedIds), [2, 3]);
  assert.ok(p.requesterSections.every(s => !s.hidden));
  assert.equal(p.elements['all-requests-section'].hidden, false);
  assert.equal(p.elements['admin-link'].hidden, true);
  assert.match(p.elements['workspace-role'].textContent, /Requester/);
  assert.match(p.elements['workspace-role'].textContent, /Maintenance Staff/);
});

test('admin + technician can manage requests but only update status on their assigned tickets', () => {
  const p = page();
  p.context.user = { id: 10, roles: ['ADMINISTRATOR', 'MAINTENANCE_STAFF'] };
  p.run(`
    currentWorkspaceUser = user;
    renderTicket = (ticket, readOnly, priority, assign, status) => ({ id: ticket.id, priority, assign, status });
    displayWorkspaceRole(user);
    renderAllTickets([{id: 1, assignedTechnicianId: 10}, {id: 2, assignedTechnicianId: 20}]);
  `);
  const cards = p.elements['all-tickets-list'].children;
  assert.ok(cards.every(card => card.priority && card.assign));
  assert.equal(cards[0].status, true);
  assert.equal(cards[1].status, false);
  assert.equal(p.elements['admin-link'].hidden, false);
});

test('manager alone gets read-only cards, no requester form or administrator link', async () => {
  const p = page();
  p.context.user = { id: 10, roles: ['MANAGER'] };
  p.run(`
    loadCurrentUser = async () => user;
    loadPrioritySummary = async () => {};
    fetch = async () => ({ ok: true, status: 200, json: async () => [{id: 1, assignedTechnicianId: 10}] });
    renderTicket = (ticket, readOnly, priority, assign, status) => ({ readOnly, priority, assign, status });
  `);
  await p.run('initWorkspace()');
  assert.ok(p.requesterSections.every(s => s.hidden));
  const card = p.elements['all-tickets-list'].children[0];
  assert.equal(card.readOnly, true);
  assert.equal(card.priority, false);
  assert.equal(card.assign, false);
  assert.equal(card.status, false);
  assert.equal(p.elements['admin-link'].hidden, true);
});


test('administrator form preserves multiple checks and submits a roles array', async () => {
  const p = page();
  const html = p.run("createRoleOptions(['GENERAL_USER', 'MAINTENANCE_STAFF'])");
  assert.equal((html.match(/checked/g) ?? []).length, 2);
  p.run(`
    loadAdminUsers = async () => {};
    fetch = async (url, options) => {
      savedUrl = url;
      savedBody = JSON.parse(options.body);
      return { ok: true, status: 200 };
    };
  `);
  await p.run("updateAdminUserAccess(10, 'CAMPUS_STAFF', ['GENERAL_USER', 'MAINTENANCE_STAFF'])");
  assert.match(p.context.savedUrl, /admin\/users\/10$/);
  assert.deepEqual(Array.from(p.context.savedBody.roles), ['GENERAL_USER', 'MAINTENANCE_STAFF']);
  assert.equal(p.context.savedBody.role, undefined);
  assert.equal(p.elements['admin-message'].textContent, 'Identity and roles updated.');
});

test('reassignment excludes the current technician and sends the required reason', async () => {
  const p = page();
  p.run(`
    availableTechnicians = [
      { id: 11, name: 'Current Tech', email: 'current@test.local' },
      { id: 22, name: 'New Tech', email: 'new@test.local' }
    ];
    refreshTicketLists = async () => {};
    fetch = async (url, options) => {
      savedUrl = url;
      savedMethod = options.method;
      savedBody = JSON.parse(options.body);
      return { ok: true, status: 200 };
    };
  `);

  const options = p.run('createTechnicianOptions(11)');
  assert.doesNotMatch(options, /Current Tech/);
  assert.match(options, /New Tech/);

  p.context.select = { value: '22' };
  p.context.reasonInput = { value: '  Current technician is unavailable  ' };
  p.context.message = { textContent: '' };
  p.context.button = { disabled: false };
  await p.run('reassignTicket(7, select, reasonInput, message, button)');

  assert.match(p.context.savedUrl, /tickets\/7\/reassignment$/);
  assert.equal(p.context.savedMethod, 'POST');
  assert.equal(p.context.savedBody.technicianId, 22);
  assert.equal(p.context.savedBody.reason, 'Current technician is unavailable');
  assert.equal(p.elements['all-tickets-message'].textContent, 'Request reassigned successfully.');
});

test('reassignment requires both a different technician and a reason before calling the API', async () => {
  const p = page();
  p.run('fetch = async () => { fetchCalled = true; throw new Error("must not fetch"); };');
  p.context.select = { value: '' };
  p.context.reasonInput = { value: '' };
  p.context.message = { textContent: '' };
  p.context.button = { disabled: false };

  await p.run('reassignTicket(7, select, reasonInput, message, button)');
  assert.match(p.context.message.textContent, /different Maintenance Staff/);
  assert.equal(p.context.fetchCalled, undefined);

  p.context.select.value = '22';
  await p.run('reassignTicket(7, select, reasonInput, message, button)');
  assert.match(p.context.message.textContent, /reason/);
  assert.equal(p.context.fetchCalled, undefined);
});

test('completed and closed remain independent status filters and dashboard counts', () => {
  const p = page();
  assert.equal(p.run("matchesStatusFilter({status: 'COMPLETED'}, 'COMPLETED')"), true);
  assert.equal(p.run("matchesStatusFilter({status: 'CLOSED'}, 'COMPLETED')"), false);
  assert.equal(p.run("matchesStatusFilter({status: 'CLOSED'}, 'CLOSED')"), true);

  p.context.manager = { roles: ['MANAGER'] };
  p.context.finishedTickets = [{ status: 'COMPLETED' }, { status: 'CLOSED' }];
  const metrics = p.run('dashboardMetricsFor(manager, finishedTickets)');
  assert.equal(metrics.at(-1)[0], 'Completed');
  assert.equal(metrics.at(-1)[1], 1);
});

test('requester attention order follows visible suggested urgency', () => {
  const p = page();
  p.context.requester = { id: 7, roles: ['GENERAL_USER'] };
  p.context.attentionTickets = [
    {
      id: 1, status: 'IN_PROGRESS', urgency: 'LOW', operationalPriority: 'CRITICAL',
      createdAt: '2026-10-12T10:00:00'
    },
    {
      id: 2, status: 'IN_PROGRESS', urgency: 'EMERGENCY', operationalPriority: 'LOW',
      createdAt: '2026-10-12T09:00:00'
    }
  ];
  const orderedIds = p.run('focusTicketsFor(requester, attentionTickets).map(ticket => ticket.id)');
  assert.deepEqual(Array.from(orderedIds), [2, 1]);
});

test('detail renders when optional evidence and technician requests fail', async () => {
  const p = page();
  p.elements['request-detail-message'] = richElement();
  p.elements['request-detail-content'] = richElement({ hidden: true });
  p.context.admin = { id: 1, roles: ['ADMINISTRATOR'] };
  p.run(`
    currentWorkspaceUser = admin;
    availableTechnicians = [];
    getTicketIdFromUrl = () => 7;
    fetchJson = async path => {
      if (path === '/tickets/7') return { id: 7, title: 'Leaking pipe', status: 'SUBMITTED' };
      throw new Error(path.includes('completion-evidence') ? 'Evidence unavailable' : 'Technicians unavailable');
    };
    renderRequestDetail = (ticket, evidence) => {
      renderedTicketId = ticket.id;
      renderedEvidence = evidence;
    };
  `);

  await p.run('loadRequestDetail()');
  assert.equal(p.context.renderedTicketId, 7);
  assert.deepEqual(Array.from(p.context.renderedEvidence), []);
  assert.match(p.elements['request-detail-message'].textContent, /supporting information is unavailable/i);
  assert.equal(p.elements['request-detail-message'].classList.contains('error-state'), false);
});

test('complete request uploads optional evidence before one completed transition', async () => {
  const p = page();
  p.context.notes = { value: '  Replaced the damaged valve  ' };
  p.context.image = { value: 'selected', files: [{ type: 'image/png', size: 1024 }] };
  p.context.message = { textContent: '' };
  p.context.button = { disabled: false };
  p.run(`
    completionSteps = [];
    postCompletionEvidence = async (_ticketId, evidence) => {
      completionSteps.push('evidence:' + evidence.notes);
    };
    fetchJson = async (_path, _fallback, options) => {
      completionSteps.push('status:' + JSON.parse(options.body).status);
      return {};
    };
    refreshTicketLists = async () => { completionSteps.push('refresh'); };
  `);

  await p.run('completeRequest(7, notes, image, message, button)');
  assert.deepEqual(Array.from(p.context.completionSteps), [
    'evidence:Replaced the damaged valve',
    'status:COMPLETED',
    'refresh'
  ]);
  assert.equal(p.context.notes.value, '');
  assert.equal(p.context.image.value, '');
});

test('complete request rejects half-filled optional evidence without changing status', async () => {
  const p = page();
  p.context.notes = { value: 'Notes without a photo' };
  p.context.image = { value: '', files: [] };
  p.context.message = { textContent: '' };
  p.context.button = { disabled: false };
  p.run('fetchJson = async () => { statusChanged = true; };');

  await p.run('completeRequest(7, notes, image, message, button)');
  assert.match(p.context.message.textContent, /both repair notes and an evidence image/i);
  assert.equal(p.context.statusChanged, undefined);
  assert.equal(p.context.button.disabled, false);
});

test('dashboard and request failures clear loading placeholders', async () => {
  const p = page();
  p.elements['dashboard-message'] = richElement();
  p.elements['dashboard-tickets'] = richElement({ innerHTML: 'Loading requests' });
  p.elements['dashboard-metrics'] = richElement({ innerHTML: 'Skeleton cards' });
  p.elements['requests-message'] = richElement();
  p.elements['requests-list'] = richElement({ innerHTML: 'Loading requests' });
  p.elements['requests-count'] = richElement({ textContent: 'Loading…' });
  p.context.user = { id: 1, roles: ['MANAGER'] };
  p.run('getVisibleTickets = async () => { throw new Error("Network unavailable"); };');

  await p.run('loadDashboardData(user)');
  assert.equal(p.elements['dashboard-tickets'].innerHTML, '');
  assert.equal(p.elements['dashboard-metrics'].innerHTML, '');
  assert.equal(p.elements['dashboard-tickets'].attributes['aria-busy'], 'false');
  assert.equal(p.elements['dashboard-metrics'].attributes['aria-busy'], 'false');

  await p.run('loadRequestsPageData()');
  assert.equal(p.elements['requests-list'].innerHTML, '');
  assert.equal(p.elements['requests-count'].textContent, 'Unable to load requests');
  assert.equal(p.elements['requests-list'].attributes['aria-busy'], 'false');
});
