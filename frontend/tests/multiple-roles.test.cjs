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
