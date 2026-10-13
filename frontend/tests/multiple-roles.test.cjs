const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../js/app.js'), 'utf8');
function page({ extraIds = [], omitIds = [], fetchImpl = undefined, storage = undefined,
  sharedStorage = undefined, windowName = undefined } = {}) {
  const ids = ['workspace-role', 'admin-link', 'role-workspace-message', 'role-workspace-title',
    'role-workspace-description', 'all-requests-section', 'all-requests-title', 'all-requests-description',
    'tickets-message', 'tickets-list', 'all-tickets-message', 'all-tickets-list', 'admin-message',
    ...extraIds].filter(id => !omitIds.includes(id));
  const elements = Object.fromEntries(ids.map(id => [id, richElement({ hidden: true })]));
  const requesterSections = [{ hidden: true }, { hidden: true }];
  const windowListeners = {};
  const context = vm.createContext({
    window: {
      location: { hostname: '127.0.0.1', href: '' }, sessionStorage: storage,
      localStorage: sharedStorage, name: windowName,
      addEventListener(name, listener) { windowListeners[name] = listener; }
    },
    fetch: fetchImpl,
    document: {
      getElementById: id => elements[id] ?? null,
      querySelectorAll: () => requesterSections
    }
  });
  vm.runInContext(source, context);
  return { context, elements, requesterSections, windowListeners,
    run: code => vm.runInContext(code, context) };
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
    listeners: {},
    classList: {
      add: (...names) => names.forEach(name => classes.add(name)),
      remove: (...names) => names.forEach(name => classes.delete(name)),
      contains: name => classes.has(name)
    },
    appendChild(child) { this.children.push(child); },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    addEventListener(name, listener) { this.listeners[name] = listener; },
    querySelectorAll(selector) { return this.queryResults?.[selector] ?? []; },
    ...initial
  };
}

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key)
  };
}

test('student mode shows only owned work even when the account has technician access', async () => {
  const p = page();
  p.context.testUser = { id: 10, role: 'MAINTENANCE_STAFF',
    roles: ['GENERAL_USER', 'MAINTENANCE_STAFF'], activeRole: 'GENERAL_USER' };
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
  assert.equal(p.context.assignedIds, undefined);
  assert.ok(p.requesterSections.every(s => !s.hidden));
  assert.equal(p.elements['all-requests-section'].hidden, true);
  assert.equal(p.elements['admin-link'].hidden, true);
  assert.equal(p.elements['workspace-role'].textContent, 'Student');
});

test('administrator and technician modes never show combined ticket actions', () => {
  const p = page();
  p.context.user = { id: 10, roles: ['ADMINISTRATOR', 'MAINTENANCE_STAFF'], activeRole: 'ADMINISTRATOR' };
  p.run(`
    currentWorkspaceUser = user;
    renderTicket = (ticket, readOnly, priority, assign, status) => ({ id: ticket.id, priority, assign, status });
    displayWorkspaceRole(user);
    renderAllTickets([{id: 1, assignedTechnicianId: 10}, {id: 2, assignedTechnicianId: 20}]);
  `);
  const cards = p.elements['all-tickets-list'].children;
  assert.ok(cards.every(card => card.priority && card.assign));
  assert.equal(cards[0].status, false);
  assert.equal(cards[1].status, false);
  assert.equal(p.elements['admin-link'].hidden, false);
  assert.equal(p.elements['workspace-role'].textContent, 'Administrator');

  p.elements['all-tickets-list'].children = [];
  p.run(`
    user.activeRole = 'MAINTENANCE_STAFF';
    displayWorkspaceRole(user);
    renderAllTickets([{id: 1, assignedTechnicianId: 10}, {id: 2, assignedTechnicianId: 20}]);
  `);
  const technicianCards = p.elements['all-tickets-list'].children;
  assert.equal(technicianCards[0].priority, false);
  assert.equal(technicianCards[0].assign, false);
  assert.equal(technicianCards[0].status, true);
  assert.equal(technicianCards[1].status, false);
  assert.equal(p.elements['admin-link'].hidden, true);
  assert.equal(p.elements['workspace-role'].textContent, 'Maintenance Staff');
});

test('single student mode has no administrative or technician area', async () => {
  const p = page();
  p.context.user = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.run(`
    loadCurrentUser = async () => user;
    fetch = async () => ({ ok: true, status: 200, json: async () => [{id: 1, assignedTechnicianId: 10}] });
    renderTickets = tickets => { ownIds = tickets.map(ticket => ticket.id); };
  `);
  await p.run('initWorkspace()');
  assert.ok(p.requesterSections.every(s => !s.hidden));
  assert.equal(p.elements['all-requests-section'].hidden, true);
  assert.equal(p.context.assignedIds, undefined);
  assert.equal(p.elements['admin-link'].hidden, true);
});

test('login requires a role choice before opening a multi-role dashboard', async () => {
  const requests = [];
  const granted = { id: 10, name: 'Dual User', roles: ['GENERAL_USER', 'MAINTENANCE_STAFF'],
    activeRole: null };
  const fetchImpl = async (url, options = {}) => {
    requests.push({ url, options });
    if (url.endsWith('/users/me')) return { ok: false, status: 401 };
    if (url.endsWith('/auth/login')) return { ok: true, status: 200, json: async () => granted };
    if (url.endsWith('/auth/active-role')) {
      return { ok: true, status: 200, json: async () => ({ activeRole: 'GENERAL_USER', roles: granted.roles }) };
    }
    throw new Error(`Unexpected request: ${url}`);
  };
  const storage = memoryStorage({ 'campusfix.roleTabId': 'test-tab' });
  const p = page({
    omitIds: ['workspace-page'], fetchImpl, storage, windowName: 'test-tab',
    extraIds: ['login-form', 'login-section', 'login-email', 'login-password', 'auth-message',
      'role-selection-section', 'role-selection-options', 'role-selection-message']
  });
  const studentButton = richElement({ dataset: { role: 'GENERAL_USER' } });
  p.elements['role-selection-options'].queryResults = {
    'button[data-role]': [studentButton], button: [studentButton]
  };
  p.elements['login-email'].value = 'dual@test.local';
  p.elements['login-password'].value = 'password123';
  await p.elements['login-form'].listeners.submit({ preventDefault() {} });

  assert.equal(p.elements['login-section'].hidden, true);
  assert.equal(p.elements['role-selection-section'].hidden, false);
  assert.match(p.elements['role-selection-options'].innerHTML, /GENERAL_USER/);
  assert.match(p.elements['role-selection-options'].innerHTML, /MAINTENANCE_STAFF/);
  assert.doesNotMatch(p.elements['role-selection-options'].innerHTML, /MANAGER/);
  assert.equal(p.context.window.location.href, '');

  await studentButton.listeners.click();
  assert.equal(JSON.parse(storage.getItem('campusfix.activeRole')).role, 'GENERAL_USER');
  assert.equal(p.context.window.location.href, './workspace.html');
  assert.deepEqual(JSON.parse(requests.find(request => request.url.endsWith('/auth/active-role')).options.body),
    { role: 'GENERAL_USER' });
});

test('dashboard switcher changes one visible role and restores it across pages in the same tab', async () => {
  const requests = [];
  const storage = memoryStorage({ 'campusfix.roleTabId': 'same-tab' });
  const sharedStorage = memoryStorage();
  const user = { id: 10, name: 'Three Roles',
    roles: ['GENERAL_USER', 'MAINTENANCE_STAFF', 'ADMINISTRATOR'], activeRole: 'GENERAL_USER' };
  const fetchImpl = async (url, options = {}) => {
    requests.push({ url, options });
    if (url.endsWith('/auth/active-role')) {
      return { ok: true, status: 200,
        json: async () => ({ activeRole: JSON.parse(options.body).role, roles: user.roles }) };
    }
    if (url.endsWith('/users/me')) {
      return { ok: true, status: 200,
        json: async () => ({ ...user, activeRole: options.headers?.['X-Active-Role'] ?? null }) };
    }
    throw new Error(`Unexpected request: ${url}`);
  };
  const p = page({
    omitIds: ['workspace-page'], fetchImpl, storage, sharedStorage, windowName: 'same-tab',
    extraIds: ['dashboard-role-switcher', 'dashboard-role-select', 'dashboard-role-message',
      'dashboard-metrics', 'dashboard-tickets']
  });
  p.context.user = user;
  p.run(`
    loadedRoles = [];
    loadDashboardData = async selectedUser => { loadedRoles.push(selectedUser.activeRole); };
    currentWorkspaceUser = user;
    setupDashboardRoleSwitcher(user);
    displayWorkspaceRole(user);
  `);
  const selector = p.elements['dashboard-role-select'];
  assert.equal(p.elements['dashboard-role-switcher'].hidden, false);
  assert.equal(selector.value, 'GENERAL_USER');
  assert.doesNotMatch(selector.innerHTML, /MANAGER/);
  assert.equal(p.elements['workspace-role'].textContent, 'Student');

  selector.value = 'MAINTENANCE_STAFF';
  await selector.listeners.change();
  assert.equal(p.elements['workspace-role'].textContent, 'Maintenance Staff');
  assert.equal(p.elements['admin-link'].hidden, true);
  assert.equal(p.run("hasRole(currentWorkspaceUser, 'GENERAL_USER')"), false);
  assert.equal(p.run("hasRole(currentWorkspaceUser, 'MAINTENANCE_STAFF')"), true);

  selector.value = 'ADMINISTRATOR';
  await selector.listeners.change();
  assert.equal(p.elements['workspace-role'].textContent, 'Administrator');
  assert.equal(p.elements['admin-link'].hidden, false);
  assert.equal(p.run("hasRole(currentWorkspaceUser, 'MAINTENANCE_STAFF')"), false);
  assert.deepEqual(Array.from(p.context.loadedRoles), ['MAINTENANCE_STAFF', 'ADMINISTRATOR']);
  assert.equal(JSON.parse(storage.getItem('campusfix.activeRole')).role, 'ADMINISTRATOR');
  assert.equal(sharedStorage.getItem('campusfix.authEvent'), null);

  const nextPage = page({ omitIds: ['workspace-page'], fetchImpl, storage,
    sharedStorage, windowName: 'same-tab' });
  const restored = await nextPage.run('loadCurrentUser()');
  assert.equal(restored.activeRole, 'ADMINISTRATOR');
  assert.equal(requests.at(-1).options.headers['X-Active-Role'], 'ADMINISTRATOR');
});

test('a delayed focus check cannot overwrite a dashboard role switch', async () => {
  const storage = memoryStorage({
    'campusfix.roleTabId': 'focus-tab',
    'campusfix.activeRole': JSON.stringify({ userId: 10, role: 'GENERAL_USER' })
  });
  const user = { id: 10, roles: ['GENERAL_USER', 'MAINTENANCE_STAFF'],
    activeRole: 'GENERAL_USER' };
  let releaseProfile;
  const delayedProfile = new Promise(resolve => { releaseProfile = resolve; });
  const requests = [];
  const p = page({ omitIds: ['workspace-page'], storage, windowName: 'focus-tab',
    extraIds: ['dashboard-role-switcher', 'dashboard-role-select', 'dashboard-role-message',
      'dashboard-metrics', 'dashboard-tickets'],
    fetchImpl: async (url, options = {}) => {
      requests.push({ url, options });
      if (url.endsWith('/users/me')) return delayedProfile;
      if (url.endsWith('/auth/active-role')) return { ok: true, status: 200,
        json: async () => ({ activeRole: JSON.parse(options.body).role, roles: user.roles }) };
      throw new Error(`Unexpected request: ${url}`);
    } });
  p.context.user = user;
  p.run(`
    currentWorkspaceUser = user;
    loadDashboardData = async () => {};
    displayWorkspaceRole(user);
    setupDashboardRoleSwitcher(user);
  `);

  const focusCheck = p.windowListeners.focus();
  assert.equal(requests[0].options.headers['X-Active-Role'], 'GENERAL_USER');
  const selector = p.elements['dashboard-role-select'];
  selector.value = 'MAINTENANCE_STAFF';
  await selector.listeners.change();
  releaseProfile({ ok: true, status: 200, json: async () => user });
  await focusCheck;

  assert.equal(p.run('getActiveRole(currentWorkspaceUser)'), 'MAINTENANCE_STAFF');
  assert.equal(JSON.parse(storage.getItem('campusfix.activeRole')).role, 'MAINTENANCE_STAFF');
  assert.equal(p.elements['workspace-role'].textContent, 'Maintenance Staff');
  assert.equal(p.context.window.location.href, '');
});

test('a cloned new tab discards the other tab role choice', () => {
  const cloned = memoryStorage({
    'campusfix.roleTabId': 'original-tab',
    'campusfix.activeRole': JSON.stringify({ userId: 10, role: 'ADMINISTRATOR' })
  });
  const p = page({ omitIds: ['workspace-page'], storage: cloned, windowName: '' });
  assert.equal(p.run('readStoredRole()'), null);
  assert.equal(cloned.getItem('campusfix.activeRole'), null);
});

test('an account change in another tab clears the old workspace while a role switch stays local', async () => {
  const storage = memoryStorage({
    'campusfix.roleTabId': 'old-tab',
    'campusfix.activeRole': JSON.stringify({ userId: 10, role: 'GENERAL_USER' })
  });
  const sharedStorage = memoryStorage();
  const p = page({ omitIds: ['workspace-page'], storage, sharedStorage, windowName: 'old-tab',
    extraIds: ['main-content'] });
  p.context.oldUser = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.elements['main-content'].hidden = false;
  p.run('currentWorkspaceUser = oldUser; displayWorkspaceRole(oldUser);');
  p.windowListeners.storage({ key: 'unrelated', newValue: '{}' });
  assert.equal(p.elements['main-content'].hidden, false);

  p.windowListeners.storage({ key: 'campusfix.authEvent',
    newValue: JSON.stringify({ type: 'login', userId: 20, nonce: 'other-tab' }) });
  assert.equal(storage.getItem('campusfix.activeRole'), null);
  assert.equal(p.elements['main-content'].hidden, true);
  assert.equal(p.elements['workspace-role'].hidden, true);
  assert.equal(p.context.window.location.href, './index.html');

  const focusedStorage = memoryStorage({
    'campusfix.roleTabId': 'focused-tab',
    'campusfix.activeRole': JSON.stringify({ userId: 10, role: 'GENERAL_USER' })
  });
  const focused = page({ omitIds: ['workspace-page'], storage: focusedStorage,
    windowName: 'focused-tab', extraIds: ['main-content'],
    fetchImpl: async () => ({ ok: true, status: 200,
      json: async () => ({ id: 20, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' }) }) });
  focused.context.oldUser = { id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  focused.elements['main-content'].hidden = false;
  focused.run('currentWorkspaceUser = oldUser; displayWorkspaceRole(oldUser);');
  await focused.windowListeners.focus();
  assert.equal(focused.elements['main-content'].hidden, true);
  assert.equal(focusedStorage.getItem('campusfix.activeRole'), null);
  assert.equal(focused.context.window.location.href, './index.html');
});

test('revoking the active administrator role hides stale content and opens the sole remaining role', async () => {
  const storage = memoryStorage({
    'campusfix.roleTabId': 'revoked-tab',
    'campusfix.activeRole': JSON.stringify({ userId: 10, role: 'ADMINISTRATOR' })
  });
  const requests = [];
  const p = page({ omitIds: ['workspace-page'], storage, windowName: 'revoked-tab',
    extraIds: ['main-content'],
    fetchImpl: async (url, options = {}) => {
      requests.push({ url, options });
      if (url.endsWith('/tickets')) return { ok: false, status: 409 };
      if (url.endsWith('/users/me')) return { ok: true, status: 200,
        json: async () => ({ id: 10, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' }) };
      throw new Error(`Unexpected request: ${url}`);
    } });
  p.context.admin = { id: 10, roles: ['GENERAL_USER', 'ADMINISTRATOR'],
    activeRole: 'ADMINISTRATOR' };
  p.elements['main-content'].hidden = false;
  p.run('currentWorkspaceUser = admin; displayWorkspaceRole(admin);');

  await assert.rejects(p.run("apiRequest('/tickets')"), /active role changed/i);
  assert.equal(requests[0].options.headers['X-Active-Role'], 'ADMINISTRATOR');
  assert.equal(p.elements['main-content'].hidden, true);
  assert.equal(p.elements['workspace-role'].hidden, true);
  assert.equal(JSON.parse(storage.getItem('campusfix.activeRole')).role, 'GENERAL_USER');
  assert.equal(p.context.window.location.href, './workspace.html');
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

  p.context.student = { roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
  p.context.finishedTickets = [{ status: 'COMPLETED' }, { status: 'CLOSED' }];
  const metrics = p.run('dashboardMetricsFor(student, finishedTickets)');
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
  p.context.user = { id: 1, roles: ['GENERAL_USER'], activeRole: 'GENERAL_USER' };
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
