// Keep the API on the same hostname as the page so the session cookie is shared
// when the frontend is opened through either localhost or 127.0.0.1.
const API_BASE_URL = `http://${window.location.hostname}:8080/api`;
const pageName = document.body?.dataset?.page ?? '';

const registrationForm = document.getElementById('registration-form');
const registrationMessage = document.getElementById('registration-message');
const verificationEmailInput = document.getElementById('email');
const verificationCodeInput = document.getElementById('verification-code');
const sendVerificationCodeButton = document.getElementById('send-verification-code');
const verificationMessage = document.getElementById('verification-message');
const loginForm = document.getElementById('login-form');
const loginSection = document.getElementById('login-section');
const roleSelectionSection = document.getElementById('role-selection-section');
const roleSelectionOptions = document.getElementById('role-selection-options');
const roleSelectionMessage = document.getElementById('role-selection-message');
const roleSelectionLogout = document.getElementById('role-selection-logout');
const logoutButton = document.getElementById('logout-button');
const authMessage = document.getElementById('auth-message');
const workspaceRole = document.getElementById('workspace-role');
const adminLink = document.getElementById('admin-link');

const ticketForm = document.getElementById('ticket-form');
const ticketSubmitButton = document.getElementById('ticket-submit-button');
const ticketResetButton = document.getElementById('ticket-reset-button');
const ticketFormTitle = document.getElementById('request-form-title');
const ticketIdInput = document.getElementById('ticket-id');
const ticketMessage = document.getElementById('ticket-message');

// Legacy elements remain supported so existing role-flow tests and old links keep working.
const workspacePage = document.getElementById('workspace-page');
const ticketsList = document.getElementById('tickets-list');
const ticketsMessage = document.getElementById('tickets-message');
const allRequestsSection = document.getElementById('all-requests-section');
const allRequestsTitle = document.getElementById('all-requests-title');
const allTicketsList = document.getElementById('all-tickets-list');
const allTicketsMessage = document.getElementById('all-tickets-message');
const allRequestsDescription = document.getElementById('all-requests-description');
const prioritySummary = document.getElementById('priority-summary');
const prioritySummaryValues = document.getElementById('priority-summary-values');
const prioritySummaryMessage = document.getElementById('priority-summary-message');

const profileForm = document.getElementById('profile-form');
const avatarForm = document.getElementById('avatar-form');
const profileAvatar = document.getElementById('profile-avatar');
const adminPage = document.getElementById('admin-page');
const adminUsersBody = document.getElementById('admin-users-body');
const adminMessage = document.getElementById('admin-message');

let currentWorkspaceUser = null;
let availableTechnicians = [];
let visibleTickets = [];
let currentDetailTicket = null;
let selectedTicketFiles = [];
let existingTicketImages = [];
let previewObjectUrls = [];
let lastLightboxTrigger = null;
let loginSubmissionStarted = false;
let dashboardLoadVersion = 0;
let notificationCountVersion = 0;
const SELECTABLE_ROLES = ['GENERAL_USER', 'ADMINISTRATOR', 'MAINTENANCE_STAFF'];
const ACTIVE_ROLE_STORAGE_KEY = 'campusfix.activeRole';
const ROLE_TAB_ID_KEY = 'campusfix.roleTabId';
const AUTH_EVENT_STORAGE_KEY = 'campusfix.authEvent';

function roleStorage() {
  try { return window.sessionStorage ?? null; } catch (_) { return null; }
}

function ensureIndependentTab() {
  const storage = roleStorage();
  if (!storage || typeof window.name !== 'string') return;
  try {
    const savedTabId = storage.getItem(ROLE_TAB_ID_KEY);
    if (savedTabId && window.name === savedTabId) return;
    storage.removeItem(ACTIVE_ROLE_STORAGE_KEY);
    const tabId = `campusfix-role-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    window.name = tabId;
    storage.setItem(ROLE_TAB_ID_KEY, tabId);
  } catch (_) { /* Role selection will be requested again if storage is unavailable. */ }
}

ensureIndependentTab();

function readStoredRole() {
  try {
    const value = roleStorage()?.getItem(ACTIVE_ROLE_STORAGE_KEY);
    if (!value) return null;
    const selection = JSON.parse(value);
    return selection?.userId && SELECTABLE_ROLES.includes(selection.role) ? selection : null;
  } catch (_) { return null; }
}

function storeActiveRole(userId, role) {
  try { roleStorage()?.setItem(ACTIVE_ROLE_STORAGE_KEY, JSON.stringify({ userId, role })); } catch (_) { /* See above. */ }
}

function clearActiveRole() {
  try { roleStorage()?.removeItem(ACTIVE_ROLE_STORAGE_KEY); } catch (_) { /* See above. */ }
}

function broadcastAccountChange(type, userId = null) {
  try {
    window.localStorage?.setItem(AUTH_EVENT_STORAGE_KEY, JSON.stringify({
      type, userId, nonce: `${Date.now()}-${Math.random()}`
    }));
  } catch (_) { /* Other tabs also check the account when focused. */ }
}

function hideCurrentWorkspace() {
  dashboardLoadVersion += 1;
  const main = document.getElementById('main-content');
  if (main) main.hidden = true;
  if (workspaceRole) workspaceRole.hidden = true;
  if (adminLink) adminLink.hidden = true;
}

function handleAccountChange(event) {
  if (event?.key !== AUTH_EVENT_STORAGE_KEY || !event.newValue) return;
  clearActiveRole();
  currentWorkspaceUser = null;
  hideCurrentWorkspace();
  window.location.href = './index.html';
}

async function revalidateFocusedAccount() {
  const previous = currentWorkspaceUser;
  if (!previous) return;
  const previousRole = getActiveRole(previous);
  const stored = readStoredRole();
  const user = await readCurrentUserSnapshot(stored);
  const latestStored = readStoredRole();
  if (currentWorkspaceUser !== previous || getActiveRole(previous) !== previousRole
      || latestStored?.role !== stored?.role || String(latestStored?.userId) !== String(stored?.userId)) return;
  if (!user || String(user.id) !== String(previous.id)) {
    clearActiveRole();
    currentWorkspaceUser = null;
    hideCurrentWorkspace();
    window.location.href = './index.html';
    return;
  }
  const currentRole = getActiveRole(user);
  if (currentRole !== previousRole) {
    if (currentRole) storeActiveRole(user.id, currentRole);
    else clearActiveRole();
    currentWorkspaceUser = null;
    hideCurrentWorkspace();
    window.location.href = currentRole ? './workspace.html' : './index.html?selectRole=1';
  }
}

window.addEventListener?.('storage', handleAccountChange);
window.addEventListener?.('focus', revalidateFocusedAccount);
let ticketLocationMap = null;
let ticketLocationMarker = null;
let ticketPlaceAutocomplete = null;
let suppressLocationInputReset = false;

function rolesFor(user) {
  const roles = Array.isArray(user?.roles) ? user.roles : (user?.role ? [user.role] : []);
  return [...new Set(roles.filter(Boolean))];
}

function selectableRolesFor(user) {
  return SELECTABLE_ROLES.filter((role) => rolesFor(user).includes(role));
}

function getActiveRole(user) {
  const granted = selectableRolesFor(user);
  if (granted.includes(user?.activeRole)) return user.activeRole;
  return granted.length === 1 && rolesFor(user).length === 1 ? granted[0] : null;
}

function hasActiveRole(user, role) {
  return getActiveRole(user) === role;
}

function hasRole(user, role) {
  return hasActiveRole(user, role);
}

function canViewAllRequests(user) {
  return hasRole(user, 'ADMINISTRATOR');
}

function hasOperationalRole(user) {
  return canViewAllRequests(user) || hasRole(user, 'MAINTENANCE_STAFF');
}

function isTicketOwner(ticket, user = currentWorkspaceUser) {
  return Boolean(ticket && user && hasActiveRole(user, 'GENERAL_USER') && ticket.submittedById === user.id);
}

function isAssignedTechnician(ticket, user = currentWorkspaceUser) {
  return Boolean(ticket && user
    && hasRole(user, 'MAINTENANCE_STAFF')
    && ticket.assignedTechnicianId === user.id);
}

function isTerminalStatus(status) {
  return ['COMPLETED', 'CLOSED', 'CANCELLED'].includes(status);
}

function getRoleMode(user) {
  if (hasRole(user, 'ADMINISTRATOR')) return 'administrator';
  if (hasRole(user, 'MAINTENANCE_STAFF')) return 'technician';
  if (hasRole(user, 'GENERAL_USER')) return 'requester';
  return null;
}

function elementByAnyId(...ids) {
  for (const id of ids) {
    const element = document.getElementById(id);
    if (element) return element;
  }
  return null;
}

function setText(element, value) {
  if (element) element.textContent = value;
}

async function readApiError(response, fallbackMessage) {
  const data = await response.json().catch(() => null);
  return data?.detail ?? data?.message ?? fallbackMessage;
}

async function apiRequest(path, options = {}) {
  const { redirectOnUnauthorized = true, ...fetchOptions } = options;
  const hasFormDataBody = typeof FormData !== 'undefined' && fetchOptions.body instanceof FormData;
  const activeRole = currentWorkspaceUser ? getActiveRole(currentWorkspaceUser) : readStoredRole()?.role;
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include',
    ...fetchOptions,
    headers: {
      ...(hasFormDataBody ? {} : fetchOptions.body ? { 'Content-Type': 'application/json' } : {}),
      ...(!path.startsWith('/auth/') && activeRole ? { 'X-Active-Role': activeRole } : {}),
      ...(fetchOptions.headers ?? {})
    }
  });

  if (response.status === 401 && redirectOnUnauthorized) {
    clearActiveRole();
    window.location.href = './index.html';
    throw new Error('Your session has expired. Please log in again.');
  }
  if ([403, 409].includes(response.status) && !path.startsWith('/auth/')) {
    if (await recoverRoleSelection()) {
      throw new Error('Your active role changed. Choose a role to continue.');
    }
  }
  return response;
}

async function fetchJson(path, fallbackMessage, options = {}) {
  const response = await apiRequest(path, options);
  if (!response.ok) {
    throw new Error(await readApiError(response, `${fallbackMessage} (${response.status}).`));
  }
  return response.status === 204 || typeof response.json !== 'function' ? null : response.json();
}

async function readCurrentUserSnapshot(stored = readStoredRole()) {
  try {
    const requestProfile = (role) => fetch(`${API_BASE_URL}/users/me`, {
      credentials: 'include',
      headers: role ? { 'X-Active-Role': role } : {}
    });
    let response = await requestProfile(stored?.role);
    if (response.status === 409) {
      response = await requestProfile(null);
    }
    if (!response.ok) return null;
    let user = await response.json();
    if (stored && String(stored.userId) !== String(user.id)) {
      response = await requestProfile(null);
      if (!response.ok) return null;
      user = await response.json();
    }
    return user;
  } catch (_) {
    return null;
  }
}

async function loadCurrentUser() {
  const user = await readCurrentUserSnapshot();
  if (!user) return null;
  const activeRole = getActiveRole(user);
  if (activeRole) storeActiveRole(user.id, activeRole);
  else clearActiveRole();
  return user;
}

async function recoverRoleSelection() {
  const selected = readStoredRole();
  const selectedRole = selected?.role ?? getActiveRole(currentWorkspaceUser);
  const selectedUserId = selected?.userId ?? currentWorkspaceUser?.id;
  const check = await fetch(`${API_BASE_URL}/users/me`, {
    credentials: 'include',
    headers: selectedRole ? { 'X-Active-Role': selectedRole } : {}
  }).catch(() => null);
  if (check?.ok) {
    const user = await check.json().catch(() => null);
    if (user && getActiveRole(user) === selectedRole && String(user.id) === String(selectedUserId)) {
      return false;
    }
  }
  hideCurrentWorkspace();
  clearActiveRole();
  currentWorkspaceUser = null;
  const user = await loadCurrentUser();
  if (!user) {
    window.location.href = './index.html';
    return true;
  }
  const role = getActiveRole(user);
  if (role) {
    storeActiveRole(user.id, role);
    window.location.href = './workspace.html';
    return true;
  }
  window.location.href = './index.html?selectRole=1';
  return true;
}

async function requireCurrentUser() {
  const user = await loadCurrentUser();
  if (!user) {
    window.location.href = './index.html';
    return null;
  }
  if (!getActiveRole(user)) {
    window.location.href = './index.html?selectRole=1';
    return null;
  }
  currentWorkspaceUser = user;
  displayWorkspaceRole(user);
  applyRoleVisibility(user);
  if (document.getElementById('notifications-unread-count')) {
    loadUnreadCount().catch(() => {});
  }
  return user;
}

function displayWorkspaceRole(user) {
  if (workspaceRole) {
    const role = getActiveRole(user);
    workspaceRole.textContent = role ? formatRole(role) : '';
    workspaceRole.hidden = !role;
  }
  if (adminLink) adminLink.hidden = !hasActiveRole(user, 'ADMINISTRATOR');

  const userName = document.getElementById('workspace-user-name');
  if (userName) userName.textContent = user.name;
}

function applyRoleVisibility(user) {
  if (!pageName || typeof document.querySelectorAll !== 'function') return;
  document.querySelectorAll('.requester-only').forEach((element) => {
    element.hidden = !hasRole(user, 'GENERAL_USER');
  });
  document.querySelectorAll('[data-requires-role]').forEach((element) => {
    const requiredRoles = element.dataset.requiresRole.split(',').map((role) => role.trim());
    element.hidden = !requiredRoles.some((role) => hasRole(user, role));
  });
}

function setupAppShell() {
  const nav = document.querySelector?.('.app-nav');
  if (nav && !document.getElementById('notifications-link')) {
    const profileLink = nav.querySelector('a[href="./profile.html"]');
    const links = [
      ['reviews-link', './reviews.html', '★', 'Technician reviews', 'GENERAL_USER'],
      ['review-admin-link', './review-admin.html', '★', 'Review moderation', 'ADMINISTRATOR'],
      ['notifications-link', './notifications.html', '♧', 'Notifications', null]
    ];
    links.forEach(([id, href, icon, label, role]) => {
      const link = document.createElement('a');
      link.id = id;
      link.className = `app-nav-link${pageName === (id === 'reviews-link' ? 'reviews' : id === 'review-admin-link' ? 'review-admin' : 'notifications') ? ' is-active' : ''}`;
      link.href = href;
      if (role) {
        link.dataset.requiresRole = role;
        link.hidden = true;
      }
      if (link.classList.contains('is-active')) link.setAttribute('aria-current', 'page');
      link.innerHTML = `<span aria-hidden="true">${icon}</span><span>${label}</span>`;
      if (id === 'notifications-link') {
        const badge = document.createElement('span');
        badge.id = 'notifications-unread-count';
        badge.className = 'nav-count';
        badge.hidden = true;
        badge.setAttribute('aria-label', 'Unread notifications');
        link.appendChild(badge);
      }
      nav.insertBefore(link, profileLink);
    });
  }
  const sidebarToggle = document.getElementById('sidebar-toggle');
  const sidebar = document.getElementById('app-sidebar');
  const sidebarBackdrop = document.getElementById('sidebar-backdrop');
  if (!sidebarToggle || !document.body) return;

  const setOpen = (open) => {
    document.body.classList.toggle('sidebar-open', open);
    sidebar?.classList.toggle('is-open', open);
    sidebarToggle.setAttribute('aria-expanded', String(open));
    sidebarToggle.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
  };

  sidebarToggle.addEventListener('click', () => {
    setOpen(!document.body.classList.contains('sidebar-open'));
  });
  sidebarBackdrop?.addEventListener('click', () => setOpen(false));
  sidebar?.querySelectorAll('a').forEach((link) => link.addEventListener('click', () => setOpen(false)));
}

function getLandingPage() {
  return './workspace.html';
}

async function chooseActiveRole(user, role) {
  if (!selectableRolesFor(user).includes(role)) {
    throw new Error('This role is no longer available for your account.');
  }
  const result = await fetchJson('/auth/active-role', 'Role selection failed', {
    method: 'POST',
    body: JSON.stringify({ role })
  });
  if (result?.activeRole !== role) {
    throw new Error('Role selection could not be confirmed. Please try again.');
  }
  storeActiveRole(user.id, role);
  currentWorkspaceUser = { ...user, activeRole: role };
  return currentWorkspaceUser;
}

function renderRoleChooser(user) {
  if (!roleSelectionSection || !roleSelectionOptions) return;
  loginSection.hidden = true;
  roleSelectionSection.hidden = false;
  setText(authMessage, '');
  const roles = selectableRolesFor(user);
  if (!roles.length) {
    setText(roleSelectionMessage, 'No supported role is assigned to this account. Contact an administrator.');
    roleSelectionOptions.innerHTML = '';
    return;
  }
  setText(roleSelectionMessage, 'Choose the workspace you want to use.');
  roleSelectionOptions.innerHTML = roles.map((role) =>
    `<button type="button" class="role-choice" data-role="${role}">${formatRole(role)}</button>`
  ).join('');
  roleSelectionOptions.querySelectorAll('button[data-role]').forEach((button) => {
    button.addEventListener('click', async () => {
      roleSelectionOptions.querySelectorAll('button').forEach((choice) => { choice.disabled = true; });
      setText(roleSelectionMessage, 'Opening your workspace...');
      try {
        await chooseActiveRole(user, button.dataset.role);
        window.location.href = getLandingPage();
      } catch (error) {
        setText(roleSelectionMessage, error.message);
        roleSelectionOptions.querySelectorAll('button').forEach((choice) => { choice.disabled = false; });
      }
    });
  });
}

async function initLoginPage() {
  if (!loginForm) return;
  const user = await loadCurrentUser();
  if (loginSubmissionStarted) return;
  if (!user) return;
  if (getActiveRole(user)) {
    window.location.href = getLandingPage();
    return;
  }
  renderRoleChooser(user);
}

function startVerificationCooldown(seconds) {
  let remaining = Math.max(1, Number(seconds) || 60);
  sendVerificationCodeButton.disabled = true;
  sendVerificationCodeButton.textContent = `Resend in ${remaining}s`;
  const timer = window.setInterval(() => {
    remaining -= 1;
    if (remaining <= 0) {
      window.clearInterval(timer);
      sendVerificationCodeButton.disabled = false;
      sendVerificationCodeButton.textContent = 'Resend code';
      return;
    }
    sendVerificationCodeButton.textContent = `Resend in ${remaining}s`;
  }, 1000);
}

if (sendVerificationCodeButton) {
  sendVerificationCodeButton.addEventListener('click', async () => {
    if (!verificationEmailInput?.checkValidity()) {
      verificationEmailInput?.reportValidity();
      return;
    }
    sendVerificationCodeButton.disabled = true;
    setText(verificationMessage, 'Sending verification code...');
    try {
      const delivery = await fetchJson('/auth/verification-code', 'Sending code failed', {
        method: 'POST',
        body: JSON.stringify({ email: verificationEmailInput.value })
      });
      setText(verificationMessage,
        `If this email can be registered, a code has been sent. It expires in ${delivery.expiresInMinutes} minutes.`);
      verificationCodeInput?.focus();
      startVerificationCooldown(delivery.retryAfterSeconds);
    } catch (error) {
      setText(verificationMessage, error.message);
      sendVerificationCodeButton.disabled = false;
    }
  });
}

if (registrationForm) {
  registrationForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    setText(registrationMessage, 'Registering...');
    try {
      await fetchJson('/auth/register', 'Registration failed', {
        method: 'POST',
        body: JSON.stringify({
          name: document.getElementById('name').value,
          email: document.getElementById('email').value,
          password: document.getElementById('password').value,
          verificationCode: document.getElementById('verification-code').value
        })
      });
      setText(registrationMessage, 'Registration successful. You can now log in.');
      registrationForm.reset();
    } catch (error) {
      setText(registrationMessage, error.message);
    }
  });
}

if (loginForm) {
  loginForm.addEventListener('submit', async (event) => {
    event.preventDefault();
    loginSubmissionStarted = true;
    setText(authMessage, 'Logging in...');
    try {
      const user = await fetchJson('/auth/login', 'Login failed', {
        method: 'POST',
        redirectOnUnauthorized: false,
        body: JSON.stringify({
          email: document.getElementById('login-email').value,
          password: document.getElementById('login-password').value
        })
      });
      clearActiveRole();
      broadcastAccountChange('login', user.id);
      if (getActiveRole(user)) {
        storeActiveRole(user.id, getActiveRole(user));
        window.location.href = getLandingPage();
      } else {
        renderRoleChooser(user);
      }
    } catch (error) {
      setText(authMessage, error.message.includes('(401)') ? 'Invalid email or password.' : error.message);
    }
  });
}

async function getVisibleTickets() {
  return fetchJson('/tickets', 'Loading requests failed');
}

function scopeTicketsForDashboard(tickets, user) {
  const mode = getRoleMode(user);
  if (mode === 'administrator') return tickets;
  if (mode === 'technician') {
    return tickets.filter((ticket) => ticket.assignedTechnicianId === user.id);
  }
  return tickets.filter((ticket) => ticket.submittedById === user.id);
}

function needsAttention(ticket) {
  return !isTerminalStatus(ticket.status)
    && (ticket.status === 'SUBMITTED'
      || ticket.status === 'REOPENED'
      || !ticket.operationalPriority
      || ticket.operationalPriority === 'CRITICAL');
}

function dashboardMetricsFor(user, tickets) {
  const mode = getRoleMode(user);
  const completed = (ticket) => ticket.status === 'COMPLETED';
  if (mode === 'administrator') {
    return [
      ['Needs attention', tickets.filter(needsAttention).length, 'view=attention', 'Requests needing a decision'],
      ['Unassigned', tickets.filter((ticket) => !isTerminalStatus(ticket.status) && !ticket.assignedTechnicianId).length,
        'view=unassigned', 'Waiting for a technician'],
      ['In progress', tickets.filter((ticket) => ticket.status === 'IN_PROGRESS').length,
        'status=IN_PROGRESS', 'Work currently underway'],
      ['Completed', tickets.filter(completed).length, 'view=completed', 'Finished requests']
    ];
  }
  if (mode === 'technician') {
    return [
      ['To start', tickets.filter((ticket) => ticket.status === 'ASSIGNED').length,
        'status=ASSIGNED', 'Assigned to you'],
      ['In progress', tickets.filter((ticket) => ticket.status === 'IN_PROGRESS').length,
        'status=IN_PROGRESS', 'Your active work'],
      ['Completed', tickets.filter(completed).length, 'view=completed', 'Finished by you']
    ];
  }
  return [
    ['Active requests', tickets.filter((ticket) => !isTerminalStatus(ticket.status)).length,
      'view=active', 'Still being handled'],
    ['Awaiting assignment', tickets.filter((ticket) => ticket.status === 'SUBMITTED').length,
      'status=SUBMITTED', 'Submitted and unassigned'],
    ['Completed', tickets.filter(completed).length, 'view=completed', 'Finished requests']
  ];
}

function renderDashboardMetrics(user, tickets) {
  const container = elementByAnyId('dashboard-metrics', 'dashboard-stats');
  if (!container) return;
  container.innerHTML = dashboardMetricsFor(user, tickets).map(([label, count, query, note]) => `
    <a class="stat-card" href="./requests.html?${query}">
      <span class="stat-label">${escapeHtml(label)}</span>
      <strong class="stat-value">${count}</strong>
      <span class="stat-note">${escapeHtml(note)}</span>
    </a>
  `).join('');
  container.setAttribute('aria-busy', 'false');
}

const STATUS_ATTENTION_ORDER = {
  SUBMITTED: 0,
  REOPENED: 1,
  ASSIGNED: 2,
  IN_PROGRESS: 3,
  COMPLETED: 4,
  CLOSED: 5,
  CANCELLED: 6
};
const PRIORITY_ORDER = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
const URGENCY_ORDER = { EMERGENCY: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

function visibleSeverityRank(ticket, user = currentWorkspaceUser) {
  return hasOperationalRole(user)
    ? (PRIORITY_ORDER[ticket.operationalPriority] ?? 4)
    : (URGENCY_ORDER[ticket.urgency] ?? 4);
}

function compareByAttention(left, right, user = currentWorkspaceUser) {
  const leftTerminal = isTerminalStatus(left.status) ? 1 : 0;
  const rightTerminal = isTerminalStatus(right.status) ? 1 : 0;
  if (leftTerminal !== rightTerminal) return leftTerminal - rightTerminal;
  const leftPriority = visibleSeverityRank(left, user);
  const rightPriority = visibleSeverityRank(right, user);
  if (leftPriority !== rightPriority) return leftPriority - rightPriority;
  const statusDifference = (STATUS_ATTENTION_ORDER[left.status] ?? 99)
    - (STATUS_ATTENTION_ORDER[right.status] ?? 99);
  if (statusDifference !== 0) return statusDifference;
  return new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime();
}

function focusTicketsFor(user, tickets) {
  const active = tickets.filter((ticket) => !isTerminalStatus(ticket.status));
  const mode = getRoleMode(user);
  if (mode === 'administrator') {
    const attention = active.filter(needsAttention).sort((left, right) => compareByAttention(left, right, user));
    const remaining = active.filter((ticket) => !attention.includes(ticket))
      .sort((left, right) => compareByAttention(left, right, user));
    return [...attention, ...remaining].slice(0, 5);
  }
  return active.sort((left, right) => compareByAttention(left, right, user)).slice(0, 5);
}

function renderPriorityBadge(ticket, user) {
  if (hasOperationalRole(user)) {
    const priority = ticket.operationalPriority;
    return `<span class="priority-badge priority-${priority?.toLowerCase() ?? 'unclassified'}">
      ${escapeHtml(priority ? formatPriority(priority) : 'Unclassified')}
    </span>`;
  }
  const urgency = ticket.urgency ?? 'LOW';
  return `<span class="urgency-badge urgency-${urgency.toLowerCase()}">${escapeHtml(formatUrgency(urgency))}</span>`;
}

function renderStatusBadge(status) {
  const safeStatus = String(status ?? 'SUBMITTED').toLowerCase();
  return `<span class="status-badge status-${safeStatus}">${escapeHtml(formatStatus(status))}</span>`;
}

function createRequestRow(ticket, user = currentWorkspaceUser) {
  const link = document.createElement('a');
  link.className = 'request-row';
  link.href = `./request-detail.html?id=${encodeURIComponent(ticket.id)}`;
  link.dataset.ticketId = ticket.id;
  link.innerHTML = `
    <span class="request-row-main">
      <strong class="request-row-title">${escapeHtml(ticket.title)}</strong>
    </span>
    <span class="request-row-badges">
      ${renderPriorityBadge(ticket, user)}
      ${renderStatusBadge(ticket.status)}
    </span>
    <span class="request-row-arrow" aria-hidden="true">→</span>
  `;
  return link;
}

function renderRequestRows(tickets, container, messageElement, user = currentWorkspaceUser, emptyMessage = 'No requests match this view.') {
  if (!container) return;
  container.setAttribute('aria-busy', 'false');
  container.innerHTML = '';
  if (!tickets.length) {
    setText(messageElement, emptyMessage);
    const empty = document.createElement('div');
    empty.className = 'empty-state request-empty';
    empty.innerHTML = '<strong>Nothing here yet</strong><p>Try a different filter or check back later.</p>';
    container.appendChild(empty);
    return;
  }
  setText(messageElement, '');
  const fragment = document.createDocumentFragment();
  tickets.forEach((ticket) => fragment.appendChild(createRequestRow(ticket, user)));
  container.appendChild(fragment);
}

async function loadDashboardData(user = currentWorkspaceUser) {
  const analyticsLoad = loadMaintenanceAnalytics(user);
  const version = ++dashboardLoadVersion;
  const message = elementByAnyId('dashboard-message');
  const list = elementByAnyId('dashboard-tickets', 'dashboard-focus-list', 'attention-requests-list');
  const metrics = elementByAnyId('dashboard-metrics', 'dashboard-stats');
  setText(message, 'Loading your workspace...');
  try {
    const tickets = await getVisibleTickets();
    if (version !== dashboardLoadVersion
        || (currentWorkspaceUser && getActiveRole(user) !== getActiveRole(currentWorkspaceUser))) return;
    visibleTickets = tickets;
    const scopedTickets = scopeTicketsForDashboard(visibleTickets, user);
    renderDashboardMetrics(user, scopedTickets);
    renderRequestRows(focusTicketsFor(user, scopedTickets), list, message, user,
      'There are no active requests needing attention.');
    message?.classList.remove('error-state');
  } catch (error) {
    if (version !== dashboardLoadVersion) return;
    if (list) {
      list.innerHTML = '';
      list.setAttribute('aria-busy', 'false');
    }
    if (metrics) {
      metrics.innerHTML = '';
      metrics.setAttribute('aria-busy', 'false');
    }
    setText(message, error.message);
    message?.classList.add('error-state');
  } finally {
    await analyticsLoad;
  }
}

let analyticsLoadVersion = 0;

async function loadMaintenanceAnalytics(user = currentWorkspaceUser) {
  const version = ++analyticsLoadVersion;
  const section = document.getElementById('maintenance-analytics');
  const content = document.getElementById('analytics-content');
  const message = document.getElementById('analytics-message');
  if (!section || !content) return;
  const isAdmin = hasRole(user, 'ADMINISTRATOR');
  section.hidden = !isAdmin;
  content.innerHTML = '';
  setText(message, '');
  if (!isAdmin) return;
  content.setAttribute('aria-busy', 'true');
  setText(message, 'Loading statistics...');
  const stillCurrent = () => version === analyticsLoadVersion
    && String(currentWorkspaceUser?.id) === String(user?.id)
    && hasRole(currentWorkspaceUser, 'ADMINISTRATOR');
  try {
    const data = await fetchJson('/tickets/analytics', 'Loading statistics failed');
    if (!stillCurrent()) return;
    renderMaintenanceAnalytics(data, content);
    setText(message, '');
    message?.classList.remove('error-state');
  } catch (error) {
    if (!stillCurrent()) return;
    content.innerHTML = '';
    setText(message, error.message);
    message?.classList.add('error-state');
  } finally {
    if (version === analyticsLoadVersion) content.setAttribute('aria-busy', 'false');
  }
}

function renderMaintenanceAnalytics(data, content) {
  const cards = [
    ['Total requests', data.totalRequests, 'All requests, including cancelled'],
    ['Awaiting assignment', data.pendingRequests, 'Submitted requests'],
    ['Completed repairs', data.completedRequests, 'Completed and closed requests'],
    ['Completion rate', `${Number(data.completionRate).toFixed(1)}%`, 'Completed / all non-cancelled requests'],
    ['Average completion time', data.averageCompletionHours == null ? 'No data yet'
      : data.averageCompletionHours > 0 && data.averageCompletionHours < 0.1 ? '<0.1 h'
        : `${Number(data.averageCompletionHours).toFixed(1)} h`,
      `${data.completionTimeSamples} repair${data.completionTimeSamples === 1 ? '' : 's'} with a recorded completion time`],
    ['Average service rating', data.averageRating == null ? 'No ratings yet'
      : `${Number(data.averageRating).toFixed(1)} / 5`, `${data.ratingCount} published rating${data.ratingCount === 1 ? '' : 's'}`]
  ];
  const breakdown = (title, rows, formatter) => `<section class="surface analytics-breakdown"><h3>${title}</h3>
    ${rows.length ? `<ul class="analytics-distribution">${rows.map((row) => `<li>
      <div><span>${escapeHtml(formatter(row.label))}</span><strong>${Number(row.count)}</strong></div>
      <meter min="0" max="${Math.max(1, data.totalRequests)}" value="${Number(row.count)}"
        aria-label="${escapeHtml(formatter(row.label))}: ${Number(row.count)} requests"></meter></li>`).join('')}</ul>`
      : '<p class="muted-copy">No requests yet.</p>'}</section>`;
  content.innerHTML = `<div class="stat-grid analytics-stats">${cards.map(([label, value, note]) =>
    `<div class="stat-card"><span class="stat-label">${label}</span><strong class="stat-value">${escapeHtml(String(value))}</strong>
      <span class="stat-note">${escapeHtml(note)}</span></div>`).join('')}</div>
    <div class="analytics-breakdowns">
      ${breakdown('Requests by status', data.requestsByStatus, formatStatus)}
      ${breakdown('Requests by category', data.requestsByCategory, formatCategory)}
      ${breakdown('Requests by location', data.requestsByLocation, (label) => label)}
      <section class="surface analytics-breakdown"><h3>Technician workload</h3>
        <p class="muted-copy">Current unfinished assignments for active technicians.</p>
        ${data.staffWorkload.length ? `<ul class="analytics-workload">${data.staffWorkload.map((staff) =>
          `<li><span>${escapeHtml(staff.name)}</span><strong>${Number(staff.openAssignments)} open</strong></li>`).join('')}</ul>`
          : '<p class="muted-copy">No active technicians.</p>'}</section>
    </div>`;
}

function setupDashboardRoleSwitcher(user) {
  const container = document.getElementById('dashboard-role-switcher');
  const select = document.getElementById('dashboard-role-select');
  const message = document.getElementById('dashboard-role-message');
  if (!container || !select) return;
  const roles = selectableRolesFor(user);
  container.hidden = roles.length < 2;
  if (container.hidden) return;
  select.innerHTML = roles.map((role) =>
    `<option value="${role}">${formatRole(role)}</option>`
  ).join('');
  select.value = getActiveRole(user);
  select.addEventListener('change', async () => {
    const previous = getActiveRole(currentWorkspaceUser);
    const next = select.value;
    if (next === previous) return;
    select.disabled = true;
    setText(message, 'Switching workspace...');
    try {
      const selectedUser = await chooseActiveRole(currentWorkspaceUser, next);
      visibleTickets = [];
      const metrics = elementByAnyId('dashboard-metrics', 'dashboard-stats');
      const tickets = elementByAnyId('dashboard-tickets', 'dashboard-focus-list', 'attention-requests-list');
      if (metrics) {
        metrics.innerHTML = '';
        metrics.setAttribute('aria-busy', 'true');
      }
      if (tickets) {
        tickets.innerHTML = '';
        tickets.setAttribute('aria-busy', 'true');
      }
      displayWorkspaceRole(selectedUser);
      applyRoleVisibility(selectedUser);
      if (document.getElementById('notifications-unread-count')) {
        loadUnreadCount().catch(() => {});
      }
      setText(message, `Viewing as ${formatRole(next)}.`);
      const averageLoad = loadTechnicianAverage(selectedUser);
      await loadDashboardData(selectedUser);
      await averageLoad;
    } catch (error) {
      select.value = previous;
      setText(message, error.message);
    } finally {
      select.disabled = false;
    }
  });
}

async function initDashboard() {
  const user = await requireCurrentUser();
  if (!user) return;
  setupDashboardRoleSwitcher(user);
  document.getElementById('analytics-refresh')?.addEventListener('click', () => loadMaintenanceAnalytics());
  await loadDashboardData(user);
  await loadTechnicianAverage(user);
}

async function loadTechnicianAverage(user = currentWorkspaceUser) {
  const section = document.getElementById('technician-rating-section');
  const summary = document.getElementById('technician-rating-summary');
  if (!section || !summary) return;
  const isTechnician = hasRole(user, 'MAINTENANCE_STAFF');
  section.hidden = !isTechnician;
  summary.textContent = '';
  if (!isTechnician) return;
  setText(summary, 'Loading your average rating...');
  try {
    const rating = await fetchJson('/reviews/me/average', 'Loading your average rating failed');
    if (String(currentWorkspaceUser?.id) !== String(user?.id)
        || getActiveRole(currentWorkspaceUser) !== 'MAINTENANCE_STAFF') return;
    summary.textContent = rating.averageRating == null
      ? 'No ratings yet'
      : `${formatRating(rating.averageRating)} / 5`;
  } catch (error) {
    if (String(currentWorkspaceUser?.id) === String(user?.id)
        && getActiveRole(currentWorkspaceUser) === 'MAINTENANCE_STAFF') setText(summary, error.message);
  }
}

function getRequestFilters() {
  const search = elementByAnyId('requests-search', 'request-search');
  const status = elementByAnyId('requests-status', 'request-status-filter');
  const sort = elementByAnyId('requests-sort', 'request-sort');
  return {
    search: search?.value.trim().toLowerCase() ?? '',
    status: status?.value ?? 'ALL',
    sort: sort?.value ?? 'attention'
  };
}

function matchesStatusFilter(ticket, status) {
  if (!status || status === 'ALL') return true;
  if (status === 'ACTIVE') return !isTerminalStatus(ticket.status);
  if (status === 'UNASSIGNED') return !isTerminalStatus(ticket.status) && !ticket.assignedTechnicianId;
  if (status === 'ATTENTION') return needsAttention(ticket);
  return ticket.status === status;
}

function sortTickets(tickets, sort, user = currentWorkspaceUser) {
  const sorted = [...tickets];
  const normalizedSort = String(sort ?? 'attention').toLowerCase();
  if (normalizedSort === 'newest') {
    return sorted.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
  }
  if (normalizedSort === 'oldest') {
    return sorted.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
  }
  if (['priority', 'priority_high'].includes(normalizedSort)) {
    return sorted.sort((a, b) => visibleSeverityRank(a, user) - visibleSeverityRank(b, user));
  }
  if (normalizedSort === 'priority_low') {
    return sorted.sort((a, b) => {
      const leftRank = visibleSeverityRank(a, user);
      const rightRank = visibleSeverityRank(b, user);
      if (leftRank === 4 || rightRank === 4) return leftRank === rightRank ? 0 : (leftRank === 4 ? 1 : -1);
      return rightRank - leftRank;
    });
  }
  if (normalizedSort === 'status') {
    return sorted.sort((a, b) => (STATUS_ATTENTION_ORDER[a.status] ?? 99)
      - (STATUS_ATTENTION_ORDER[b.status] ?? 99));
  }
  return sorted.sort((left, right) => compareByAttention(left, right, user));
}

function updateRequestsUrl(filters) {
  if (!window.history?.replaceState) return;
  const params = new URLSearchParams();
  if (filters.search) params.set('q', filters.search);
  if (filters.status && filters.status !== 'ALL') params.set('status', filters.status);
  if (filters.sort && !['attention', 'ATTENTION'].includes(filters.sort)) params.set('sort', filters.sort);
  const query = params.toString();
  window.history.replaceState(null, '', `./requests.html${query ? `?${query}` : ''}`);
}

function renderFilteredRequests() {
  const filters = getRequestFilters();
  const list = elementByAnyId('requests-list');
  const message = elementByAnyId('requests-message');
  const count = elementByAnyId('requests-count', 'request-count');
  const filtered = visibleTickets.filter((ticket) => {
    const haystack = [ticket.title, ticket.location, ticket.category, ticket.id]
      .filter((value) => value !== null && value !== undefined)
      .join(' ')
      .toLowerCase();
    return (!filters.search || haystack.includes(filters.search))
      && matchesStatusFilter(ticket, filters.status);
  });
  const sorted = sortTickets(filtered, filters.sort, currentWorkspaceUser);
  if (count) count.textContent = `${sorted.length} request${sorted.length === 1 ? '' : 's'}`;
  renderRequestRows(sorted, list, message, currentWorkspaceUser);
  updateRequestsUrl(filters);
}

function initialiseRequestFiltersFromUrl() {
  const params = new URLSearchParams(window.location.search);
  const search = elementByAnyId('requests-search', 'request-search');
  const status = elementByAnyId('requests-status', 'request-status-filter');
  const sort = elementByAnyId('requests-sort', 'request-sort');
  if (search) search.value = params.get('q') ?? '';

  let statusValue = params.get('status')?.toUpperCase() ?? 'ALL';
  const view = params.get('view')?.toLowerCase();
  if (view === 'attention') statusValue = 'ATTENTION';
  if (view === 'unassigned') statusValue = 'UNASSIGNED';
  if (view === 'active') statusValue = 'ACTIVE';
  if (view === 'completed') statusValue = 'COMPLETED';
  if (status) {
    if (![...status.options].some((option) => option.value === statusValue)) {
      const option = document.createElement('option');
      option.value = statusValue;
      option.textContent = {
        ACTIVE: 'Active requests', ATTENTION: 'Needs attention', UNASSIGNED: 'Unassigned', COMPLETED: 'Completed'
      }[statusValue] ?? formatStatus(statusValue);
      status.appendChild(option);
    }
    status.value = statusValue;
  }
  if (sort) {
    const requestedSort = params.get('sort');
    sort.value = requestedSort && [...sort.options].some((option) => option.value === requestedSort)
      ? requestedSort
      : (sort.querySelector('option[value="ATTENTION"]') ? 'ATTENTION' : 'attention');
  }
}

function bindRequestFilters() {
  const search = elementByAnyId('requests-search', 'request-search');
  const status = elementByAnyId('requests-status', 'request-status-filter');
  const sort = elementByAnyId('requests-sort', 'request-sort');
  search?.addEventListener('input', renderFilteredRequests);
  status?.addEventListener('change', renderFilteredRequests);
  sort?.addEventListener('change', renderFilteredRequests);
  document.getElementById('request-controls')?.addEventListener('reset', () => {
    window.setTimeout(renderFilteredRequests, 0);
  });
  document.querySelectorAll?.('[data-status-filter]').forEach((button) => {
    button.addEventListener('click', () => {
      if (status) status.value = button.dataset.statusFilter;
      document.querySelectorAll('[data-status-filter]').forEach((item) => {
        item.classList.toggle('is-active', item === button);
      });
      renderFilteredRequests();
    });
  });
}

async function loadRequestsPageData() {
  const message = elementByAnyId('requests-message');
  const list = elementByAnyId('requests-list');
  const count = elementByAnyId('requests-count', 'request-count');
  setText(message, 'Loading requests...');
  try {
    visibleTickets = await getVisibleTickets();
    renderFilteredRequests();
    message?.classList.remove('error-state');
  } catch (error) {
    if (list) {
      list.innerHTML = '';
      list.setAttribute('aria-busy', 'false');
    }
    setText(count, 'Unable to load requests');
    setText(message, error.message);
    message?.classList.add('error-state');
  }
}

async function initRequestsPage() {
  const user = await requireCurrentUser();
  if (!user) return;
  initialiseRequestFiltersFromUrl();
  bindRequestFilters();
  await loadRequestsPageData();
}

function getTicketIdFromUrl() {
  const value = new URLSearchParams(window.location.search).get('id');
  if (!value || !/^\d+$/.test(value)) return null;
  return Number(value);
}

function imageUrl(ticketId, imageId) {
  const role = getActiveRole(currentWorkspaceUser);
  return `${API_BASE_URL}/tickets/${encodeURIComponent(ticketId)}/images/${encodeURIComponent(imageId)}`
    + (role ? `?activeRole=${encodeURIComponent(role)}` : '');
}

function evidenceImageUrl(ticketId, evidenceId) {
  const role = getActiveRole(currentWorkspaceUser);
  return `${API_BASE_URL}/tickets/${encodeURIComponent(ticketId)}/completion-evidence/${encodeURIComponent(evidenceId)}/image`
    + (role ? `?activeRole=${encodeURIComponent(role)}` : '');
}

function renderTicketGallery(ticket) {
  if (!ticket.imageIds?.length) {
    return '<div class="empty-state"><p>No issue images were attached.</p></div>';
  }
  const firstUrl = imageUrl(ticket.id, ticket.imageIds[0]);
  return `
    <div class="ticket-gallery" data-ticket-id="${ticket.id}">
      <button class="gallery-main-button" type="button" data-lightbox-src="${firstUrl}"
        aria-label="Open issue image full screen">
        <img class="gallery-main-image" src="${firstUrl}" alt="Issue image 1">
      </button>
      ${ticket.imageIds.length > 1 ? `
        <div class="gallery-thumbnails" aria-label="Issue image thumbnails">
          ${ticket.imageIds.map((imageId, index) => `
            <button class="gallery-thumbnail ${index === 0 ? 'is-active' : ''}" type="button"
              data-image-src="${imageUrl(ticket.id, imageId)}" data-image-alt="Issue image ${index + 1}"
              aria-label="Show issue image ${index + 1}">
              <img src="${imageUrl(ticket.id, imageId)}" alt="">
            </button>
          `).join('')}
        </div>` : ''}
    </div>`;
}

function renderCompletionEvidence(ticket, evidence) {
  if (!evidence.length) return '';
  return `
    <section class="detail-section" aria-labelledby="evidence-heading">
      <div class="detail-heading"><h2 id="evidence-heading">Completion evidence</h2></div>
      <div class="evidence-grid">
        ${evidence.map((item, index) => {
    const url = evidenceImageUrl(ticket.id, item.id);
    return `
          <article class="evidence-card">
            <button type="button" class="evidence-image-button" data-lightbox-src="${url}"
              aria-label="Open completion evidence image ${index + 1} full screen">
              <img src="${url}" alt="Completion evidence ${index + 1}">
            </button>
            <p>${escapeHtml(item.repairNotes)}</p>
            <small>Uploaded by ${escapeHtml(item.uploadedByName)} · ${formatDate(item.uploadedAt)}</small>
          </article>`;
  }).join('')}
      </div>
    </section>`;
}

function renderTicketMetadata(ticket) {
  const hasCoordinates = Number.isFinite(ticket.latitude) && Number.isFinite(ticket.longitude);
  const locationDetails = ticket.locationDetails
    ? `<small>${escapeHtml(ticket.locationDetails)}</small>`
    : '';
  const mapsLink = hasCoordinates
    ? `<a class="map-location-link" href="https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(`${ticket.latitude},${ticket.longitude}`)}" target="_blank" rel="noopener noreferrer">Open in Google Maps</a>`
    : '';
  return `
    <dl class="detail-meta-list">
      <div><dt>Status</dt><dd>${renderStatusBadge(ticket.status)}</dd></div>
      <div><dt>Suggested urgency</dt><dd><span class="urgency-badge urgency-${ticket.urgency.toLowerCase()}">${formatUrgency(ticket.urgency)}</span></dd></div>
      <div><dt>Operational priority</dt><dd>${ticket.operationalPriority
    ? `<span class="priority-badge priority-${ticket.operationalPriority.toLowerCase()}">${formatPriority(ticket.operationalPriority)}</span>`
    : '<span class="priority-badge priority-unclassified">Unclassified</span>'}</dd></div>
      <div><dt>Category</dt><dd>${escapeHtml(formatCategory(ticket.category))}</dd></div>
      <div><dt>Location</dt><dd>${escapeHtml(ticket.location)}${locationDetails}${mapsLink}</dd></div>
      <div><dt>Assigned technician</dt><dd>${ticket.assignedTechnicianId
    ? `${escapeHtml(ticket.assignedTechnicianName)}<small>${escapeHtml(ticket.assignedTechnicianEmail)}</small>`
    : 'Unassigned'}</dd></div>
      <div><dt>Submitted by</dt><dd>${escapeHtml(ticket.submittedByName)}<small>${escapeHtml(ticket.submittedByEmail)}</small></dd></div>
      <div><dt>Submitted</dt><dd>${formatDate(ticket.createdAt)}</dd></div>
      <div><dt>Last updated</dt><dd>${formatDate(ticket.updatedAt)}</dd></div>
    </dl>`;
}

function createTechnicianOptions(excludedTechnicianId = null) {
  return sortedTechnicianCandidates(excludedTechnicianId)
    .map((technician) => `<option value="${technician.id}">${escapeHtml(technician.name)} (${escapeHtml(technician.email)})</option>`)
    .join('');
}

function formatRating(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toFixed(1) : '—';
}

function technicianWorkload(technician) {
  return Math.max(0, Number(technician.openAssignmentCount) || 0);
}

function technicianRankingScore(technician) {
  const supplied = technician.rankingScore == null ? NaN : Number(technician.rankingScore);
  if (Number.isFinite(supplied)) return supplied;
  const count = Math.max(0, Number(technician.ratingCount) || 0);
  const average = Number(technician.averageRating) || 0;
  return (3.5 * 5 + average * count) / (5 + count);
}

function sortedTechnicianCandidates(excludedTechnicianId = null) {
  return availableTechnicians
    .filter((technician) => String(technician.id) !== String(excludedTechnicianId))
    .sort((left, right) => {
      const workloadDifference = (technicianWorkload(left) > 0 ? 1 : 0)
        - (technicianWorkload(right) > 0 ? 1 : 0);
      return workloadDifference || technicianRankingScore(right) - technicianRankingScore(left)
        || String(left.name).localeCompare(String(right.name)) || Number(left.id) - Number(right.id);
    });
}

function renderTechnicianCandidates(selectId, excludedTechnicianId = null) {
  const candidates = sortedTechnicianCandidates(excludedTechnicianId);
  if (!candidates.length) return '<p class="muted-copy">No eligible maintenance staff are available.</p>';
  return `<div class="technician-candidate-list" aria-label="Suggested maintenance staff">${candidates.map((technician) => {
    const count = Math.max(0, Number(technician.ratingCount) || 0);
    const workload = technicianWorkload(technician);
    return `<div class="technician-candidate">
      <button type="button" class="technician-candidate-select" data-select="${selectId}" data-technician-id="${technician.id}">
        <strong>${escapeHtml(technician.name)}</strong>
        <span>${workload === 0 ? 'No unfinished assignments' : `${workload} unfinished assignment${workload === 1 ? '' : 's'}`}</span>
        <small>${count ? `${formatRating(technician.averageRating)} / 5 · ${count} rating${count === 1 ? '' : 's'}` : 'No ratings yet'}</small>
      </button>
      <a class="text-link" href="./review-admin.html?technicianId=${encodeURIComponent(technician.id)}">Reviews</a>
    </div>`;
  }).join('')}</div>`;
}

function createPriorityOptions(selectedPriority) {
  return ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((priority) => `
    <option value="${priority}" ${priority === selectedPriority ? 'selected' : ''}>${formatPriority(priority)}</option>
  `).join('');
}

function renderDetailActions(ticket, user) {
  const panels = [];
  if (hasRole(user, 'ADMINISTRATOR') && ticket.status === 'SUBMITTED' && !ticket.assignedTechnicianId) {
    panels.push(`
      <section class="action-panel action-panel-primary">
        <h3>Assign technician</h3>
        <p>Choose who should handle this request.</p>
        <label for="detail-technician">Maintenance staff</label>
        <select id="detail-technician"><option value="">Select technician</option>${createTechnicianOptions()}</select>
        <p class="muted-copy">No unfinished assignments are listed first, followed by staff with active work. Ratings inform the order; you choose the assignee.</p>
        ${renderTechnicianCandidates('detail-technician')}
        <button id="detail-assign" type="button">Assign technician</button>
        <p id="detail-assignment-message" class="detail-message" role="status"></p>
      </section>`);
  }
  if (hasRole(user, 'ADMINISTRATOR') && ticket.assignedTechnicianId
      && ['ASSIGNED', 'IN_PROGRESS', 'REOPENED'].includes(ticket.status)) {
    panels.push(`
      <section class="action-panel">
        <h3>Reassign technician</h3>
        <label for="detail-reassignment-technician">New maintenance staff</label>
        <select id="detail-reassignment-technician"><option value="">Select a different technician</option>${createTechnicianOptions(ticket.assignedTechnicianId)}</select>
        ${renderTechnicianCandidates('detail-reassignment-technician', ticket.assignedTechnicianId)}
        <label for="detail-reassignment-reason">Reason</label>
        <textarea id="detail-reassignment-reason" maxlength="1000" rows="3" required></textarea>
        <button id="detail-reassign" type="button" class="secondary-button">Reassign</button>
        <p id="detail-reassignment-message" class="detail-message" role="status"></p>
      </section>`);
  }
  if (isAssignedTechnician(ticket, user) && ticket.status === 'ASSIGNED') {
    panels.push(`
      <section class="action-panel action-panel-primary">
        <h3>Ready to begin?</h3>
        <p>Start the request when you begin work on site.</p>
        <button id="detail-start-work" type="button">Start work</button>
        <p id="detail-status-message" class="detail-message" role="status"></p>
      </section>`);
  }
  if (isAssignedTechnician(ticket, user) && ticket.status === 'IN_PROGRESS') {
    panels.push(`
      <section class="action-panel action-panel-primary">
        <h3>Complete request</h3>
        <p>You may add repair notes and a photo as completion evidence. If you add one, both are required.</p>
        <label for="detail-completion-notes">Repair notes <span>(optional)</span></label>
        <textarea id="detail-completion-notes" maxlength="2000" rows="4"></textarea>
        <label for="detail-completion-image">Evidence image <span>(optional)</span></label>
        <input id="detail-completion-image" type="file" accept="image/*">
        <button id="detail-complete-work" type="button">Complete request</button>
        <p id="detail-evidence-message" class="detail-message" role="status"></p>
      </section>`);
  }
  if (hasRole(user, 'ADMINISTRATOR')) {
    panels.push(`
      <section class="action-panel">
        <h3>Operational priority</h3>
        <label for="detail-priority">Priority</label>
        <select id="detail-priority"><option value="">Select priority</option>${createPriorityOptions(ticket.operationalPriority)}</select>
        <label for="detail-priority-reason">Reason <span>(optional)</span></label>
        <textarea id="detail-priority-reason" maxlength="1000" rows="3"></textarea>
        <button id="detail-save-priority" type="button" class="secondary-button">Save priority</button>
        <p id="detail-priority-message" class="detail-message" role="status"></p>
      </section>`);
  }
  if (isTicketOwner(ticket, user) && ticket.status === 'SUBMITTED') {
    panels.push(`
      <section class="action-panel">
        <h3>Your request</h3>
        <a class="button-link secondary-button" href="./request-form.html?id=${ticket.id}">Edit request</a>
        <button id="detail-cancel-request" type="button" class="secondary-danger">Cancel request</button>
        <p id="detail-owner-message" class="detail-message" role="status"></p>
      </section>`);
  }
  if (isTicketOwner(ticket, user) && ticket.status === 'COMPLETED' && ticket.assignedTechnicianId
      && String(ticket.assignedTechnicianId) !== String(user.id)) {
    panels.push(`
      <section class="action-panel" aria-labelledby="ticket-review-title">
        <h3 id="ticket-review-title">Rate your repair</h3>
        <p>Your rating is for ${escapeHtml(ticket.assignedTechnicianName ?? 'the completing technician')}.</p>
        <a class="text-link" href="./reviews.html?technicianId=${encodeURIComponent(ticket.assignedTechnicianId)}">See this technician's public reviews</a>
        <form id="ticket-review-form" class="action-form">
          <label for="ticket-review-score">Rating</label>
          <select id="ticket-review-score" required>
            <option value="">Select 1–5 stars</option>
            ${[1, 2, 3, 4, 5].map((star) => `<option value="${star}">${star} star${star === 1 ? '' : 's'}</option>`).join('')}
          </select>
          <label for="ticket-review-comment">Comment <span>(optional, up to 1000 characters)</span></label>
          <textarea id="ticket-review-comment" rows="4" maxlength="1000" placeholder="Share your experience"></textarea>
          <button id="ticket-review-save" type="submit">Save rating</button>
        </form>
        <p id="ticket-review-message" class="detail-message" role="status" aria-live="polite">Loading your rating...</p>
      </section>`);
  } else if (isTicketOwner(ticket, user) && ticket.status === 'COMPLETED'
      && String(ticket.assignedTechnicianId) === String(user.id)) {
    panels.push('<section class="action-panel"><h3>Rating unavailable</h3><p>You cannot rate a repair you completed yourself.</p></section>');
  }
  if (!panels.length) {
    panels.push('<div class="empty-state"><p>No action is needed from you right now.</p></div>');
  }
  return panels.join('');
}

function renderRequestDetail(ticket, evidence, user) {
  const content = elementByAnyId('request-detail-content');
  if (!content) return;
  content.hidden = false;
  document.title = `${ticket.title} | CampusFix`;
  content.innerHTML = `
    <header class="detail-heading detail-page-heading">
      <div>
        <p class="eyebrow">Request #${ticket.id}</p>
        <h1 id="request-detail-title">${escapeHtml(ticket.title)}</h1>
      </div>
      ${renderStatusBadge(ticket.status)}
    </header>
    <div class="request-detail-layout">
      <div class="request-detail-main">
        <section class="detail-section" aria-labelledby="request-description-heading">
          <div class="detail-heading"><h2 id="request-description-heading">Issue details</h2></div>
          <p class="detail-description">${escapeHtml(ticket.description)}</p>
        </section>
        <section class="detail-section" aria-labelledby="request-images-heading">
          <div class="detail-heading"><h2 id="request-images-heading">Issue images</h2></div>
          ${renderTicketGallery(ticket)}
        </section>
        ${renderCompletionEvidence(ticket, evidence)}
        ${renderTicketConversation(ticket, user)}
        <section class="detail-section activity-section" aria-labelledby="activity-heading">
          <div class="detail-heading">
            <h2 id="activity-heading">Activity</h2>
            <button id="activity-toggle" class="activity-toggle secondary-button" type="button" aria-expanded="false">View activity</button>
          </div>
          <div id="activity-content" hidden></div>
        </section>
      </div>
      <aside class="request-detail-sidebar">
        <section class="detail-section">
          <div class="detail-heading"><h2>Request overview</h2></div>
          ${renderTicketMetadata(ticket)}
        </section>
        <div id="request-action-panel" class="detail-actions">${renderDetailActions(ticket, user)}</div>
      </aside>
    </div>`;
  bindDetailInteractions(ticket);
}

function canCommentOnTicket(ticket, user) {
  return !isTerminalStatus(ticket.status) && (
    (hasRole(user, 'GENERAL_USER') && ticket.submittedById === user.id)
    || isAssignedTechnician(ticket, user));
}

function renderTicketConversation(ticket, user) {
  const canComment = canCommentOnTicket(ticket, user);
  return `<section class="detail-section ticket-conversation" aria-labelledby="ticket-comments-title">
    <div class="detail-heading"><h2 id="ticket-comments-title">Conversation</h2>
      <button id="ticket-comments-refresh" class="secondary-button" type="button">Refresh messages</button></div>
    <p class="muted-copy">Messages are shared with the requester, the assigned technician and administrators.</p>
    <p id="ticket-comments-message" class="app-status-message" role="status" aria-live="polite"></p>
    <ol id="ticket-comments-list" class="ticket-comments-list" aria-live="polite"></ol>
    ${canComment ? `<form id="ticket-comment-form" class="ticket-comment-form">
      <label for="ticket-comment-body">Add a message</label>
      <textarea id="ticket-comment-body" name="body" rows="3" maxlength="2000" required
        aria-describedby="ticket-comment-help" placeholder="Share access details, ask a question or post a repair update"></textarea>
      <p id="ticket-comment-help" class="muted-copy">Up to 2,000 characters. Use the repair actions to change the request status.</p>
      <button id="ticket-comment-submit" class="primary-button" type="submit">Send message</button>
      <p id="ticket-comment-status" class="app-status-message" role="status" aria-live="polite"></p>
    </form>` : `<p class="muted-copy">${isTerminalStatus(ticket.status)
      ? 'This request has ended. The conversation is read-only.' : 'Administrators can read this conversation.'}</p>`}
  </section>`;
}

let commentLoadVersion = 0;

async function loadTicketComments(ticketId) {
  const list = document.getElementById('ticket-comments-list');
  const message = document.getElementById('ticket-comments-message');
  if (!list) return;
  const version = ++commentLoadVersion;
  const requestedUser = currentWorkspaceUser;
  const role = getActiveRole(requestedUser);
  const stillCurrent = () => version === commentLoadVersion
    && document.getElementById('ticket-comments-list') === list
    && String(currentWorkspaceUser?.id) === String(requestedUser?.id)
    && getActiveRole(currentWorkspaceUser) === role;
  list.setAttribute('aria-busy', 'true');
  setText(message, 'Loading messages...');
  try {
    const comments = await fetchJson(`/tickets/${ticketId}/comments`, 'Loading messages failed');
    if (!stillCurrent()) return;
    renderTicketComments(comments, list);
    setText(message, '');
  } catch (error) {
    if (!stillCurrent()) return;
    list.innerHTML = '';
    setText(message, error.message);
  } finally {
    if (stillCurrent()) list.setAttribute('aria-busy', 'false');
  }
}

function renderTicketComments(comments, list) {
  list.innerHTML = comments.length ? comments.map((comment) => `<li class="ticket-comment">
    <div class="review-entry-heading"><strong>${escapeHtml(comment.authorName)}</strong>
      <span class="muted-copy">${escapeHtml(formatRole(comment.authorRole))}</span><time>${formatDate(comment.createdAt)}</time></div>
    <p class="ticket-comment-body">${escapeHtml(comment.body)}</p></li>`).join('')
    : '<li class="muted-copy">No messages yet.</li>';
}

async function submitTicketComment(event, ticketId) {
  event.preventDefault();
  const input = document.getElementById('ticket-comment-body');
  const button = document.getElementById('ticket-comment-submit');
  const message = document.getElementById('ticket-comment-status');
  if (!input || !button || button.disabled) return;
  const body = input.value.trim();
  if (!body || body.length > 2000) {
    setText(message, 'Enter a message between 1 and 2,000 characters.');
    return;
  }
  button.disabled = true;
  setText(message, 'Sending message...');
  try {
    await fetchJson(`/tickets/${ticketId}/comments`, 'Sending message failed', {
      method: 'POST', body: JSON.stringify({ body })
    });
    if (document.getElementById('ticket-comment-body') !== input) return;
    if (input.value.trim() === body) input.value = '';
    setText(message, 'Message sent.');
    await loadTicketComments(ticketId);
  } catch (error) {
    setText(message, error.message);
  } finally {
    button.disabled = false;
  }
}

function bindDetailInteractions(ticket) {
  document.getElementById('ticket-comment-form')?.addEventListener('submit', (event) => submitTicketComment(event, ticket.id));
  document.getElementById('ticket-comments-refresh')?.addEventListener('click', () => loadTicketComments(ticket.id));
  if (document.getElementById('ticket-comments-list')) loadTicketComments(ticket.id);
  const gallery = document.querySelector('.ticket-gallery');
  const mainImage = gallery?.querySelector('.gallery-main-image');
  const mainButton = gallery?.querySelector('.gallery-main-button');
  gallery?.querySelectorAll('.gallery-thumbnail').forEach((button) => {
    button.addEventListener('click', () => {
      gallery.querySelectorAll('.gallery-thumbnail').forEach((item) => item.classList.toggle('is-active', item === button));
      if (mainImage) {
        mainImage.src = button.dataset.imageSrc;
        mainImage.alt = button.dataset.imageAlt;
      }
      if (mainButton) mainButton.dataset.lightboxSrc = button.dataset.imageSrc;
    });
  });
  document.querySelectorAll('[data-lightbox-src]').forEach((button) => {
    button.addEventListener('click', () => openImageLightbox(
      button.dataset.lightboxSrc,
      button.querySelector('img')?.alt || 'Request image',
      button
    ));
  });

  const activityToggle = document.getElementById('activity-toggle');
  const activityContent = document.getElementById('activity-content');
  activityToggle?.addEventListener('click', () => toggleTicketHistory(ticket.id, activityContent, activityToggle));

  const assignButton = document.getElementById('detail-assign');
  assignButton?.addEventListener('click', () => assignTicket(
    ticket.id,
    document.getElementById('detail-technician'),
    document.getElementById('detail-assignment-message'),
    assignButton
  ));
  const reassignButton = document.getElementById('detail-reassign');
  reassignButton?.addEventListener('click', () => reassignTicket(
    ticket.id,
    document.getElementById('detail-reassignment-technician'),
    document.getElementById('detail-reassignment-reason'),
    document.getElementById('detail-reassignment-message'),
    reassignButton
  ));
  const priorityButton = document.getElementById('detail-save-priority');
  priorityButton?.addEventListener('click', () => updateTicketPriority(
    ticket.id,
    document.getElementById('detail-priority'),
    document.getElementById('detail-priority-reason'),
    document.getElementById('detail-priority-message'),
    priorityButton
  ));
  const startButton = document.getElementById('detail-start-work');
  startButton?.addEventListener('click', () => updateTicketStatus(
    ticket.id, 'IN_PROGRESS', document.getElementById('detail-status-message'), startButton
  ));
  const completeButton = document.getElementById('detail-complete-work');
  completeButton?.addEventListener('click', () => completeRequest(
    ticket.id,
    document.getElementById('detail-completion-notes'),
    document.getElementById('detail-completion-image'),
    document.getElementById('detail-evidence-message'),
    completeButton
  ));
  document.getElementById('detail-cancel-request')?.addEventListener('click', () => confirmCancelTicket(ticket.id));
  const reviewForm = document.getElementById('ticket-review-form');
  reviewForm?.addEventListener('submit', (event) => saveTicketReview(event, ticket.id));
  if (reviewForm) loadTicketReview(ticket.id).catch((error) =>
    setText(document.getElementById('ticket-review-message'), error.message));

  document.querySelectorAll('.technician-candidate-select').forEach((button) => {
    button.addEventListener('click', () => {
      const select = document.getElementById(button.dataset.select);
      if (select) select.value = button.dataset.technicianId;
      document.querySelectorAll(`.technician-candidate-select[data-select="${button.dataset.select}"]`)
        .forEach((candidate) => candidate.classList.toggle('is-selected', candidate === button));
    });
  });
}

async function loadTicketReview(ticketId) {
  const message = document.getElementById('ticket-review-message');
  const form = document.getElementById('ticket-review-form');
  if (!form) return;
  const [reviewResponse, ban] = await Promise.all([
    apiRequest(`/reviews/tickets/${ticketId}/mine`),
    fetchJson('/reviews/me/ban', 'Loading review access failed')
  ]);
  if (reviewResponse.status !== 404 && !reviewResponse.ok) {
    throw new Error(await readApiError(reviewResponse, 'Loading your rating failed'));
  }
  const review = reviewResponse.ok ? await reviewResponse.json() : null;
  const score = document.getElementById('ticket-review-score');
  const comment = document.getElementById('ticket-review-comment');
  if (score) score.value = review?.visibility === 'REMOVED' ? '' : String(review?.score ?? '');
  if (comment) comment.value = review?.visibility === 'ACTIVE' ? review?.comment ?? '' : '';
  form.querySelectorAll('select, textarea, button').forEach((input) => { input.disabled = Boolean(ban.banned); });
  setText(message, ban.banned
    ? `Your rating access is suspended${ban.expiresAt ? ` until ${formatDate(ban.expiresAt)}` : ''}. ${ban.reason ?? ''}`
    : review?.visibility === 'REMOVED' ? 'The previous rating was removed. You can submit a new one.'
      : review ? 'You can edit your rating at any time.' : 'Your rating will be public after you save it.');
}

async function saveTicketReview(event, ticketId) {
  event.preventDefault();
  const score = Number(document.getElementById('ticket-review-score')?.value);
  const comment = document.getElementById('ticket-review-comment')?.value.trim() ?? '';
  const message = document.getElementById('ticket-review-message');
  const button = document.getElementById('ticket-review-save');
  if (!Number.isInteger(score) || score < 1 || score > 5) {
    setText(message, 'Choose a rating from 1 to 5 stars.');
    return;
  }
  if (comment.length > 1000) {
    setText(message, 'Comments must be 1000 characters or fewer.');
    return;
  }
  if (button) button.disabled = true;
  setText(message, 'Saving rating...');
  try {
    await fetchJson(`/reviews/tickets/${ticketId}/mine`, 'Saving rating failed', {
      method: 'PUT', body: JSON.stringify({ score, comment })
    });
    setText(message, 'Your rating is saved and can be edited at any time.');
  } catch (error) {
    setText(message, error.message);
  } finally {
    if (button) button.disabled = false;
  }
}

function openImageLightbox(source, alt, trigger) {
  closeImageLightbox();
  lastLightboxTrigger = trigger ?? document.activeElement ?? null;
  const lightbox = document.createElement('div');
  lightbox.id = 'image-lightbox';
  lightbox.className = 'image-lightbox';
  lightbox.innerHTML = `
    <div class="lightbox-backdrop" data-close-lightbox></div>
    <div class="lightbox-dialog" role="dialog" aria-modal="true" aria-label="Image preview" tabindex="-1">
      <button class="lightbox-close" type="button" data-close-lightbox aria-label="Close image preview">×</button>
      <img class="lightbox-image" src="${source}" alt="${escapeHtml(alt)}">
    </div>`;
  document.body.appendChild(lightbox);
  document.body.classList.add('lightbox-open');
  lightbox.querySelectorAll('[data-close-lightbox]').forEach((element) => {
    element.addEventListener('click', closeImageLightbox);
  });
  lightbox.querySelector('.lightbox-close')?.focus();
}

function closeImageLightbox() {
  const lightbox = document.getElementById('image-lightbox');
  if (!lightbox) return;
  lightbox.remove();
  document.body?.classList.remove('lightbox-open');
  if (lastLightboxTrigger?.isConnected !== false) lastLightboxTrigger?.focus();
  lastLightboxTrigger = null;
}

document.addEventListener?.('keydown', (event) => {
  const lightbox = document.getElementById('image-lightbox');
  if (!lightbox) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    closeImageLightbox();
    return;
  }
  if (event.key !== 'Tab') return;
  const focusable = [...lightbox.querySelectorAll('button, [href], [tabindex]:not([tabindex="-1"])')]
    .filter((element) => !element.disabled && !element.hidden);
  if (!focusable.length) {
    event.preventDefault();
    lightbox.querySelector('.lightbox-dialog')?.focus();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
});

async function loadRequestDetail(successMessage = '') {
  const ticketId = getTicketIdFromUrl();
  const message = elementByAnyId('request-detail-message');
  const content = elementByAnyId('request-detail-content');
  if (!ticketId) {
    setText(message, 'This request link is invalid.');
    message?.classList.add('error-state');
    return;
  }
  setText(message, successMessage || 'Loading request...');
  try {
    const ticket = await fetchJson(`/tickets/${ticketId}`, 'Loading request failed');
    currentDetailTicket = ticket;
    const warnings = [];
    const evidencePromise = fetchJson(
      `/tickets/${ticketId}/completion-evidence`,
      'Loading completion evidence failed'
    ).catch((error) => {
      warnings.push(error.message);
      return [];
    });
    let techniciansPromise = Promise.resolve(availableTechnicians);
    if (hasRole(currentWorkspaceUser, 'ADMINISTRATOR') && !availableTechnicians.length) {
      techniciansPromise = loadAssignableTechnicians().catch((error) => {
        availableTechnicians = [];
        warnings.push(error.message);
        return [];
      });
    }
    const [evidence] = await Promise.all([evidencePromise, techniciansPromise]);
    renderRequestDetail(ticket, evidence, currentWorkspaceUser);
    const warningMessage = warnings.length
      ? `Some supporting information is unavailable. ${warnings.join(' ')}`
      : '';
    setText(message, [successMessage, warningMessage].filter(Boolean).join(' '));
    message?.classList.remove('error-state');
  } catch (error) {
    if (content) {
      content.innerHTML = '';
      content.hidden = true;
    }
    setText(message, error.message);
    message?.classList.add('error-state');
  }
}

async function initRequestDetailPage() {
  const user = await requireCurrentUser();
  if (!user) return;
  await loadRequestDetail();
}

async function assignTicket(ticketId, select, message, button) {
  if (!select?.value) {
    setText(message, 'Please select an active Maintenance Staff member.');
    return;
  }
  setText(message, 'Assigning request...');
  if (button) button.disabled = true;
  try {
    await fetchJson(`/tickets/${ticketId}/assignment`, 'Assignment failed', {
      method: 'POST', body: JSON.stringify({ technicianId: Number(select.value) })
    });
    await refreshTicketLists('Request assigned successfully.');
    setText(allTicketsMessage, 'Request assigned successfully.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

async function reassignTicket(ticketId, select, reasonInput, message, button) {
  const reason = reasonInput?.value.trim() ?? '';
  if (!select?.value) {
    setText(message, 'Please select a different Maintenance Staff member.');
    return;
  }
  if (!reason) {
    setText(message, 'Please provide a reason for reassignment.');
    return;
  }
  setText(message, 'Reassigning request...');
  if (button) button.disabled = true;
  try {
    await fetchJson(`/tickets/${ticketId}/reassignment`, 'Reassignment failed', {
      method: 'POST', body: JSON.stringify({ technicianId: Number(select.value), reason })
    });
    await refreshTicketLists('Request reassigned successfully.');
    setText(allTicketsMessage, 'Request reassigned successfully.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

async function updateTicketPriority(ticketId, select, reasonInput, message, button) {
  if (!select?.value) {
    setText(message, 'Please select an operational priority.');
    return;
  }
  setText(message, 'Saving priority...');
  if (button) button.disabled = true;
  try {
    await fetchJson(`/tickets/${ticketId}/priority`, 'Priority update failed', {
      method: 'PUT',
      body: JSON.stringify({ priority: select.value, reason: reasonInput?.value.trim() || null })
    });
    await refreshTicketLists('Operational priority updated.');
    setText(allTicketsMessage, 'Operational priority updated.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

function getCompletionEvidenceInput(notesInput, imageInput, optional = false) {
  const notes = notesInput?.value.trim() ?? '';
  const image = imageInput?.files?.[0] ?? null;
  if (optional && !notes && !image) return null;
  if (!notes || !image) {
    throw new Error(optional
      ? 'Add both repair notes and an evidence image, or leave both blank.'
      : 'Repair notes and an evidence image are required.');
  }
  if (!image.type.startsWith('image/') || image.size > 15 * 1024 * 1024) {
    throw new Error('Choose an image no larger than 15 MB.');
  }
  return { notes, image };
}

async function postCompletionEvidence(ticketId, evidence) {
  const formData = new FormData();
  formData.append('repairNotes', evidence.notes);
  formData.append('image', evidence.image);
  const response = await apiRequest(`/tickets/${ticketId}/completion-evidence`, {
    method: 'POST', body: formData
  });
  if (!response.ok) {
    throw new Error(await readApiError(response, `Evidence upload failed (${response.status}).`));
  }
}

async function uploadCompletionEvidence(ticketId, notesInput, imageInput, message, button, list) {
  let evidence;
  try {
    evidence = getCompletionEvidenceInput(notesInput, imageInput);
  } catch (error) {
    setText(message, error.message);
    return;
  }
  if (button) button.disabled = true;
  setText(message, 'Uploading completion evidence...');
  try {
    await postCompletionEvidence(ticketId, evidence);
    notesInput.value = '';
    imageInput.value = '';
    if (list) await loadCompletionEvidence(ticketId, list);
    await refreshTicketLists('Completion evidence uploaded.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

async function completeRequest(ticketId, notesInput, imageInput, message, button) {
  let evidence;
  try {
    evidence = getCompletionEvidenceInput(notesInput, imageInput, true);
  } catch (error) {
    setText(message, error.message);
    return;
  }

  if (button) button.disabled = true;
  try {
    if (evidence) {
      setText(message, 'Uploading completion evidence...');
      await postCompletionEvidence(ticketId, evidence);
      notesInput.value = '';
      imageInput.value = '';
    }
    setText(message, 'Completing request...');
    await fetchJson(`/tickets/${ticketId}/status`, 'Status update failed', {
      method: 'PUT', body: JSON.stringify({ status: 'COMPLETED' })
    });
    await refreshTicketLists('Request marked completed.');
    setText(allTicketsMessage, 'Request status updated.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

async function updateTicketStatus(ticketId, status, message, button) {
  setText(message, 'Updating status...');
  if (button) button.disabled = true;
  try {
    await fetchJson(`/tickets/${ticketId}/status`, 'Status update failed', {
      method: 'PUT', body: JSON.stringify({ status })
    });
    await refreshTicketLists(status === 'COMPLETED' ? 'Request marked completed.' : 'Work started.');
    setText(allTicketsMessage, 'Request status updated.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

async function loadAssignableTechnicians() {
  availableTechnicians = await fetchJson('/tickets/technicians', 'Loading technicians failed');
  return availableTechnicians;
}

async function toggleTicketHistory(ticketId, container, button) {
  if (!container || !button) return;
  if (!container.hidden) {
    container.hidden = true;
    button.textContent = 'View activity';
    button.setAttribute('aria-expanded', 'false');
    return;
  }
  container.hidden = false;
  button.textContent = 'Hide activity';
  button.setAttribute('aria-expanded', 'true');
  if (container.dataset.loaded === 'true') return;
  container.innerHTML = '<p class="loading-state">Loading activity...</p>';
  try {
    const history = await fetchJson(`/tickets/${ticketId}/history`, 'Loading activity failed');
    renderTicketHistory(history, container);
    container.dataset.loaded = 'true';
  } catch (error) {
    container.innerHTML = `<p class="error-state">${escapeHtml(error.message)}</p>`;
  }
}

function renderTicketHistory(historyItems, container) {
  if (!historyItems?.length) {
    container.innerHTML = '<div class="empty-state"><p>No activity has been recorded.</p></div>';
    return;
  }
  container.innerHTML = `
    <ol class="activity-timeline">
      ${historyItems.map((item) => `
        <li class="activity-item">
          <span class="activity-marker" aria-hidden="true"></span>
          <div class="activity-content">
            <div class="activity-item-heading">
              <strong>${escapeHtml(formatHistoryEvent(item.eventType))}</strong>
              <time datetime="${escapeHtml(item.occurredAt)}">${formatDate(item.occurredAt)}</time>
            </div>
            <p>By ${escapeHtml(item.actorName)}</p>
            ${item.reason ? `<p class="activity-reason">${escapeHtml(item.reason)}</p>` : ''}
            ${(item.previousValue || item.newValue) ? `
              <details><summary>View recorded changes</summary>
                ${item.previousValue ? `<p><strong>Before:</strong> ${escapeHtml(item.previousValue)}</p>` : ''}
                ${item.newValue ? `<p><strong>After:</strong> ${escapeHtml(item.newValue)}</p>` : ''}
              </details>` : ''}
          </div>
        </li>`).join('')}
    </ol>`;
}

function formatHistoryEvent(eventType) {
  const labels = {
    LEGACY_IMPORTED: 'Existing request imported',
    TICKET_CREATED: 'Request created',
    TICKET_UPDATED: 'Request updated',
    TICKET_CANCELLED: 'Request cancelled',
    IMAGE_ADDED: 'Image added',
    IMAGE_REMOVED: 'Image removed',
    PRIORITY_CHANGED: 'Priority changed',
    TICKET_ASSIGNED: 'Request assigned',
    TICKET_REASSIGNED: 'Request reassigned',
    COMPLETION_EVIDENCE_ADDED: 'Completion evidence added',
    STATUS_CHANGED: 'Status changed',
    RESOLUTION_CONFIRMED: 'Resolution confirmed',
    TICKET_REOPENED: 'Request reopened'
  };
  return labels[eventType] ?? eventType;
}

async function loadCompletionEvidence(ticketId, container) {
  if (!container) return [];
  try {
    const evidence = await fetchJson(`/tickets/${ticketId}/completion-evidence`, 'Loading completion evidence failed');
    if (!evidence.length) {
      container.innerHTML = '';
      return evidence;
    }
    container.innerHTML = evidence.map((item) => `
      <article class="evidence-card">
        <img src="${evidenceImageUrl(ticketId, item.id)}" alt="Completion evidence">
        <p>${escapeHtml(item.repairNotes)}</p>
        <small>Uploaded by ${escapeHtml(item.uploadedByName)} · ${formatDate(item.uploadedAt)}</small>
      </article>`).join('');
    return evidence;
  } catch (_) {
    container.innerHTML = '';
    return [];
  }
}

function revokePreviewUrls() {
  previewObjectUrls.forEach((url) => URL.revokeObjectURL(url));
  previewObjectUrls = [];
}

function renderSelectedImagePreviews() {
  const container = elementByAnyId('selected-image-preview', 'image-preview');
  if (!container) return;
  revokePreviewUrls();
  container.innerHTML = '';
  selectedTicketFiles.forEach((file, index) => {
    const url = URL.createObjectURL(file);
    previewObjectUrls.push(url);
    const item = document.createElement('div');
    item.className = 'image-preview-item';
    item.innerHTML = `
      <img src="${url}" alt="Selected image ${index + 1}">
      <button class="image-preview-remove" type="button" data-file-index="${index}" aria-label="Remove ${escapeHtml(file.name)}">×</button>`;
    item.querySelector('button').addEventListener('click', () => {
      selectedTicketFiles.splice(index, 1);
      renderSelectedImagePreviews();
    });
    container.appendChild(item);
  });
  const section = document.getElementById('selected-images-section');
  if (section) section.hidden = selectedTicketFiles.length === 0;
}

function renderExistingImages(ticketId) {
  const container = document.getElementById('existing-images');
  if (!container) return;
  container.innerHTML = '';
  existingTicketImages.forEach((imageId, index) => {
    const item = document.createElement('div');
    item.className = 'image-preview-item';
    item.innerHTML = `
      <img src="${imageUrl(ticketId, imageId)}" alt="Existing issue image ${index + 1}">
      <button class="image-preview-remove" type="button" data-image-id="${imageId}" aria-label="Delete issue image ${index + 1}">×</button>`;
    item.querySelector('button').addEventListener('click', () => deleteExistingTicketImage(ticketId, imageId));
    container.appendChild(item);
  });
  const section = document.getElementById('existing-images-section');
  if (section) section.hidden = existingTicketImages.length === 0;
}

function addSelectedTicketFiles(fileList) {
  const incoming = Array.from(fileList ?? []);
  const valid = incoming.filter((file) => file.type.startsWith('image/') && file.size <= 15 * 1024 * 1024);
  if (valid.length !== incoming.length) {
    setText(ticketMessage, 'Each file must be an image no larger than 15 MB.');
  }
  valid.forEach((file) => {
    const duplicate = selectedTicketFiles.some((item) => item.name === file.name
      && item.size === file.size && item.lastModified === file.lastModified);
    if (!duplicate) selectedTicketFiles.push(file);
  });
  const remainingSlots = Math.max(0, 5 - existingTicketImages.length);
  if (selectedTicketFiles.length > remainingSlots) {
    selectedTicketFiles = selectedTicketFiles.slice(0, remainingSlots);
    setText(ticketMessage, 'A request can contain up to 5 images.');
  }
  renderSelectedImagePreviews();
}

async function deleteExistingTicketImage(ticketId, imageId) {
  if (!window.confirm('Remove this image from the request?')) return;
  setText(ticketMessage, 'Removing image...');
  try {
    const response = await apiRequest(`/tickets/${ticketId}/images/${imageId}`, { method: 'DELETE' });
    if (!response.ok) throw new Error(await readApiError(response, `Removing image failed (${response.status}).`));
    existingTicketImages = existingTicketImages.filter((id) => id !== imageId);
    renderExistingImages(ticketId);
    setText(ticketMessage, 'Image removed.');
  } catch (error) {
    setText(ticketMessage, error.message);
  }
}

function loadGoogleMapsApi(apiKey) {
  if (window.google?.maps?.Map && window.google?.maps?.places) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const existingScript = document.querySelector('script[data-campusfix-google-maps]');
    if (existingScript) {
      if (window.google?.maps?.Map) resolve();
      else existingScript.addEventListener('load', resolve, { once: true });
      existingScript.addEventListener('error', () => reject(new Error('Google Maps could not be loaded.')), { once: true });
      return;
    }
    const callbackName = '__campusfixGoogleMapsReady';
    const script = document.createElement('script');
    script.dataset.campusfixGoogleMaps = 'true';
    script.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(apiKey)}&loading=async&libraries=places&v=weekly&callback=${callbackName}`;
    script.async = true;
    window[callbackName] = () => {
      delete window[callbackName];
      resolve();
    };
    script.onerror = () => {
      delete window[callbackName];
      reject(new Error('Google Maps could not be loaded.'));
    };
    document.head.appendChild(script);
  });
}

function setLocationPickerMessage(message) {
  setText(document.getElementById('location-search-help'), message);
}

function clearGoogleLocationSelection() {
  const placeIdInput = document.getElementById('ticket-google-place-id');
  const latitudeInput = document.getElementById('ticket-latitude');
  const longitudeInput = document.getElementById('ticket-longitude');
  if (placeIdInput) placeIdInput.value = '';
  if (latitudeInput) latitudeInput.value = '';
  if (longitudeInput) longitudeInput.value = '';
  ticketLocationMarker?.setMap(null);
}

function updateMapSelection(position, centreMap = false) {
  if (!ticketLocationMap || !ticketLocationMarker || !position) return;
  const latitude = typeof position.lat === 'function' ? position.lat() : position.lat;
  const longitude = typeof position.lng === 'function' ? position.lng() : position.lng;
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return;
  document.getElementById('ticket-latitude').value = String(latitude);
  document.getElementById('ticket-longitude').value = String(longitude);
  ticketLocationMarker.setPosition({ lat: latitude, lng: longitude });
  ticketLocationMarker.setMap(ticketLocationMap);
  if (centreMap) {
    ticketLocationMap.setCenter({ lat: latitude, lng: longitude });
    ticketLocationMap.setZoom(17);
  }
}

async function initialiseGoogleLocationPicker() {
  const container = document.getElementById('place-autocomplete-container');
  const mapPanel = document.getElementById('ticket-map-panel');
  const mapElement = document.getElementById('ticket-map');
  if (!container || !mapPanel || !mapElement) return;

  try {
    const configuration = await fetchJson('/config/maps', 'Loading map configuration failed');
    if (!configuration.enabled || !configuration.apiKey) {
      setLocationPickerMessage('Enter the location manually. Google Maps is not configured for this environment.');
      return;
    }

    await loadGoogleMapsApi(configuration.apiKey);
    let MapConstructor = google.maps.Map;
    let PlaceAutocompleteConstructor = google.maps.places?.PlaceAutocompleteElement;
    if (typeof google.maps.importLibrary === 'function') {
      const [mapsLibrary, placesLibrary] = await Promise.all([
        google.maps.importLibrary('maps'),
        google.maps.importLibrary('places')
      ]);
      MapConstructor = mapsLibrary.Map;
      PlaceAutocompleteConstructor = placesLibrary.PlaceAutocompleteElement;
    }
    if (!MapConstructor || !PlaceAutocompleteConstructor) {
      throw new Error('The required Google Maps libraries are unavailable.');
    }

    mapPanel.hidden = false;
    ticketLocationMap = new MapConstructor(mapElement, {
      center: { lat: 20, lng: 0 },
      zoom: 2,
      streetViewControl: false,
      mapTypeControl: false,
      fullscreenControl: true
    });
    ticketLocationMarker = new google.maps.Marker({
      draggable: true,
      title: 'Selected maintenance location'
    });

    ticketLocationMap.addListener('click', ({ latLng }) => {
      document.getElementById('ticket-google-place-id').value = '';
      updateMapSelection(latLng);
      setLocationPickerMessage('Exact map position selected. Confirm the address and add any building details below.');
    });
    ticketLocationMarker.addListener('dragend', ({ latLng }) => {
      document.getElementById('ticket-google-place-id').value = '';
      updateMapSelection(latLng);
      setLocationPickerMessage('Marker position updated. Confirm the address and add any building details below.');
    });

    ticketPlaceAutocomplete = new PlaceAutocompleteConstructor();
    ticketPlaceAutocomplete.placeholder = 'Search Google Maps';
    ticketPlaceAutocomplete.addEventListener('gmp-select', async ({ placePrediction }) => {
      try {
        const place = placePrediction.toPlace();
        await place.fetchFields({
          fields: ['id', 'formattedAddress', 'location', 'viewport']
        });
        if (!place.location) throw new Error('The selected place does not have map coordinates.');

        const locationInput = document.getElementById('ticket-location');
        const selectedLatitude = place.location.lat();
        const selectedLongitude = place.location.lng();
        suppressLocationInputReset = true;
        locationInput.value = place.formattedAddress
          || `Pinned location (${selectedLatitude.toFixed(6)}, ${selectedLongitude.toFixed(6)})`;
        suppressLocationInputReset = false;
        document.getElementById('ticket-google-place-id').value = place.id ?? '';
        updateMapSelection(place.location);
        if (place.viewport) ticketLocationMap.fitBounds(place.viewport);
        else {
          ticketLocationMap.setCenter(place.location);
          ticketLocationMap.setZoom(17);
        }
        setLocationPickerMessage('Google location selected. Drag the marker if the maintenance issue is at a more precise position.');
      } catch (error) {
        suppressLocationInputReset = false;
        setLocationPickerMessage(error.message || 'The selected place could not be loaded. Enter the location manually.');
      }
    });
    container.replaceChildren(ticketPlaceAutocomplete);
    container.hidden = false;
    document.getElementById('ticket-location').placeholder = 'Selected address or manual location';
    setLocationPickerMessage('Search Google Maps, or enter a location manually below.');
  } catch (error) {
    container.hidden = true;
    mapPanel.hidden = true;
    setLocationPickerMessage(`${error.message} Enter the location manually.`);
  }
}

function populateTicketForm(ticket) {
  document.getElementById('ticket-title').value = ticket.title;
  document.getElementById('ticket-description').value = ticket.description;
  document.getElementById('ticket-category').value = ticket.category;
  document.getElementById('ticket-location').value = ticket.location;
  document.getElementById('ticket-google-place-id').value = ticket.googlePlaceId ?? '';
  document.getElementById('ticket-latitude').value = ticket.latitude ?? '';
  document.getElementById('ticket-longitude').value = ticket.longitude ?? '';
  document.getElementById('ticket-location-details').value = ticket.locationDetails ?? '';
  if (ticketPlaceAutocomplete) ticketPlaceAutocomplete.value = ticket.location;
  if (Number.isFinite(ticket.latitude) && Number.isFinite(ticket.longitude)) {
    updateMapSelection({ lat: ticket.latitude, lng: ticket.longitude }, true);
  }
  document.getElementById('ticket-urgency').value = ticket.urgency;
  if (ticketIdInput) ticketIdInput.value = ticket.id;
  document.title = 'Edit Request | CampusFix';
  setText(ticketFormTitle, 'Edit maintenance request');
  setText(ticketSubmitButton, 'Save changes');
  setText(document.getElementById('request-form-context'), 'Edit request');
  setText(document.getElementById('request-form-description'),
    'Update the issue details or remove photos while the request is still unassigned.');
  if (ticketResetButton) ticketResetButton.textContent = 'Cancel';
  existingTicketImages = [...(ticket.imageIds ?? [])];
  renderExistingImages(ticket.id);
}

async function loadTicketForForm(ticketId) {
  setText(ticketMessage, 'Loading request...');
  try {
    const ticket = await fetchJson(`/tickets/${ticketId}`, 'Loading request failed');
    if (!isTicketOwner(ticket) || ticket.status !== 'SUBMITTED') {
      throw new Error('Only your submitted requests can be edited.');
    }
    populateTicketForm(ticket);
    setText(ticketMessage, '');
  } catch (error) {
    ticketForm?.setAttribute('hidden', '');
    setText(ticketMessage, error.message);
  }
}

async function uploadTicketImages(ticketId, files) {
  for (const file of Array.from(files ?? [])) {
    if (!file.type.startsWith('image/') || file.size > 15 * 1024 * 1024) {
      throw new Error('Each image must be an image no larger than 15 MB.');
    }
    const formData = new FormData();
    formData.append('image', file);
    const response = await apiRequest(`/tickets/${ticketId}/images`, { method: 'POST', body: formData });
    if (!response.ok) {
      throw new Error(await readApiError(response, `Image upload failed (${response.status}).`));
    }
  }
}

async function handleTicketSubmit(event) {
  event.preventDefault();
  const editingId = ticketIdInput?.value || '';
  const latitudeValue = document.getElementById('ticket-latitude').value;
  const longitudeValue = document.getElementById('ticket-longitude').value;
  const ticketData = {
    title: document.getElementById('ticket-title').value.trim(),
    description: document.getElementById('ticket-description').value.trim(),
    category: document.getElementById('ticket-category').value,
    location: document.getElementById('ticket-location').value.trim(),
    googlePlaceId: document.getElementById('ticket-google-place-id').value || null,
    latitude: latitudeValue === '' ? null : Number(latitudeValue),
    longitude: longitudeValue === '' ? null : Number(longitudeValue),
    locationDetails: document.getElementById('ticket-location-details').value.trim() || null,
    urgency: document.getElementById('ticket-urgency').value
  };
  if (existingTicketImages.length + selectedTicketFiles.length > 5) {
    setText(ticketMessage, 'A request can contain up to 5 images.');
    return;
  }
  if (ticketSubmitButton) ticketSubmitButton.disabled = true;
  setText(ticketMessage, editingId ? 'Saving changes...' : 'Submitting request...');
  try {
    const ticket = await fetchJson(editingId ? `/tickets/${editingId}` : '/tickets',
      editingId ? 'Update failed' : 'Submission failed', {
        method: editingId ? 'PUT' : 'POST', body: JSON.stringify(ticketData)
      });
    await uploadTicketImages(ticket.id, selectedTicketFiles);
    selectedTicketFiles = [];
    revokePreviewUrls();
    window.location.href = `./request-detail.html?id=${encodeURIComponent(ticket.id)}`;
  } catch (error) {
    setText(ticketMessage, error.message);
    if (ticketSubmitButton) ticketSubmitButton.disabled = false;
  }
}

function resetTicketForm() {
  const editingId = ticketIdInput?.value;
  if (editingId) {
    window.location.href = `./request-detail.html?id=${encodeURIComponent(editingId)}`;
    return;
  }
  window.location.href = './requests.html';
}

function startEditing(ticket) {
  window.location.href = `./request-form.html?id=${encodeURIComponent(ticket.id)}`;
}

async function initRequestFormPage() {
  const user = await requireCurrentUser();
  if (!user) return;
  if (!hasRole(user, 'GENERAL_USER')) {
    window.location.href = getLandingPage();
    return;
  }
  const requestFormPage = document.getElementById('request-form-page');
  if (requestFormPage) requestFormPage.hidden = false;
  const imageInput = document.getElementById('ticket-images');
  imageInput?.addEventListener('change', () => {
    addSelectedTicketFiles(imageInput.files);
    imageInput.value = '';
  });
  ticketForm?.addEventListener('submit', handleTicketSubmit);
  ticketResetButton?.addEventListener('click', resetTicketForm);
  document.getElementById('ticket-location')?.addEventListener('input', () => {
    if (!suppressLocationInputReset) clearGoogleLocationSelection();
  });
  await initialiseGoogleLocationPicker();
  const ticketId = getTicketIdFromUrl();
  if (ticketId) await loadTicketForForm(ticketId);
}

async function confirmCancelTicket(ticketId) {
  if (!window.confirm('Cancel this maintenance request? This action cannot be undone.')) return;
  try {
    const response = await apiRequest(`/tickets/${ticketId}/cancel`, { method: 'POST' });
    if (!response.ok) throw new Error(await readApiError(response, `Cancellation failed (${response.status}).`));
    if (['request-detail', 'detail'].includes(pageName)) {
      window.location.href = './requests.html';
    } else {
      await refreshTicketLists('Request cancelled.');
    }
  } catch (error) {
    const message = document.getElementById('detail-owner-message') ?? ticketMessage;
    setText(message, error.message);
  }
}

async function refreshTicketLists(successMessage = '') {
  if (pageName === 'dashboard') return loadDashboardData();
  if (pageName === 'requests') return loadRequestsPageData();
  if (['request-detail', 'detail'].includes(pageName)) return loadRequestDetail(successMessage);
  if (hasRole(currentWorkspaceUser, 'GENERAL_USER')) await loadTickets();
  if (hasOperationalRole(currentWorkspaceUser)) await loadAllTickets();
}

function formatStatus(status) {
  const labels = {
    SUBMITTED: 'Unassigned',
    ASSIGNED: 'Assigned',
    IN_PROGRESS: 'In progress',
    COMPLETED: 'Completed',
    CLOSED: 'Closed',
    REOPENED: 'Reopened',
    CANCELLED: 'Cancelled',
    PENDING: 'Pending'
  };
  return labels[status] ?? status ?? '';
}

function formatCategory(category) {
  const labels = {
    ELECTRICAL: 'Electrical', PLUMBING: 'Plumbing', HVAC: 'Heating / Air Conditioning',
    CARPENTRY: 'Carpentry', CLEANING: 'Cleaning', IT: 'IT', GROUNDS: 'Grounds', OTHER: 'Other'
  };
  return labels[category] ?? category ?? '';
}

function formatUrgency(urgency) {
  const labels = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', EMERGENCY: 'Emergency' };
  return labels[urgency] ?? urgency ?? '';
}

function formatPriority(priority) {
  const labels = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' };
  return labels[priority] ?? 'Unclassified';
}

function formatDate(value) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('en-AU', {
    dateStyle: 'medium', timeStyle: 'short'
  }).format(date);
}

function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  const entities = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' };
  return String(value).replace(/[&<>"']/g, (character) => entities[character]);
}

function formatRole(role) {
  const labels = {
    GENERAL_USER: 'Student', MAINTENANCE_STAFF: 'Maintenance Staff',
    ADMINISTRATOR: 'Administrator'
  };
  return labels[role] ?? role ?? '';
}

function formatIdentityType(identityType) {
  const labels = { STUDENT: 'Student', CAMPUS_STAFF: 'Campus Staff' };
  return labels[identityType] ?? identityType ?? '';
}

async function loadProfile() {
  setText(authMessage, 'Loading profile...');
  try {
    const user = await requireCurrentUser();
    if (!user) return;
    displayProfile(user);
    setText(authMessage, '');
    return user;
  } catch (error) {
    setText(authMessage, error.message);
  }
}

function displayProfile(user) {
  setText(document.getElementById('profile-id'), user.id);
  setText(document.getElementById('profile-email'), user.email);
  setText(document.getElementById('profile-identity-type'), formatIdentityType(user.identityType));
  setText(document.getElementById('profile-active-role'), formatRole(getActiveRole(user)));
  setText(document.getElementById('profile-role'), selectableRolesFor(user).map(formatRole).join(', '));
  const name = document.getElementById('profile-name');
  const contact = document.getElementById('contact-information');
  const details = document.getElementById('profile-details');
  if (name) name.value = user.name;
  if (contact) contact.value = user.contactInformation ?? '';
  if (details) details.value = user.profileDetails ?? '';
}

async function loadAvatar() {
  if (!profileAvatar) return;
  const role = getActiveRole(currentWorkspaceUser);
  const response = await apiRequest(`/users/me/avatar?v=${Date.now()}${role ? `&activeRole=${encodeURIComponent(role)}` : ''}`);
  if (response.status === 404) {
    profileAvatar.hidden = true;
    return;
  }
  if (!response.ok) throw new Error(`Picture loading failed (${response.status}).`);
  const avatarBlob = await response.blob();
  const previousUrl = profileAvatar.dataset.objectUrl;
  if (previousUrl) URL.revokeObjectURL(previousUrl);
  const avatarUrl = URL.createObjectURL(avatarBlob);
  profileAvatar.dataset.objectUrl = avatarUrl;
  profileAvatar.src = avatarUrl;
  profileAvatar.hidden = false;
}

function bindProfileForms() {
  profileForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    setText(authMessage, 'Updating profile...');
    try {
      const user = await fetchJson('/users/me', 'Profile update failed', {
        method: 'PUT',
        body: JSON.stringify({
          name: document.getElementById('profile-name').value,
          contactInformation: document.getElementById('contact-information').value,
          profileDetails: document.getElementById('profile-details').value
        })
      });
      displayProfile(user);
      setText(authMessage, 'Profile updated.');
    } catch (error) {
      setText(authMessage, error.message);
    }
  });

  avatarForm?.addEventListener('submit', async (event) => {
    event.preventDefault();
    const file = document.getElementById('avatar-file')?.files?.[0];
    if (!file || !file.type.startsWith('image/') || file.size > 15 * 1024 * 1024) {
      setText(authMessage, 'Choose an image no larger than 15 MB.');
      return;
    }
    setText(authMessage, 'Uploading picture...');
    try {
      const body = new FormData();
      body.append('avatar', file);
      const response = await apiRequest('/users/me/avatar', { method: 'POST', body });
      if (!response.ok) throw new Error(await readApiError(response, `Picture upload failed (${response.status}).`));
      await loadAvatar();
      avatarForm.reset();
      setText(authMessage, 'Profile picture uploaded.');
    } catch (error) {
      setText(authMessage, error.message);
    }
  });
}

async function initProfilePage() {
  bindProfileForms();
  const user = await loadProfile();
  if (user) await loadAvatar().catch((error) => setText(authMessage, error.message));
}

async function initAdminPage() {
  const user = await requireCurrentUser();
  if (!user) return;
  if (!hasRole(user, 'ADMINISTRATOR')) {
    window.location.href = getLandingPage();
    return;
  }
  if (adminPage) adminPage.hidden = false;
  await loadAdminUsers();
}

async function loadAdminUsers() {
  setText(adminMessage, 'Loading users...');
  try {
    const users = await fetchJson('/admin/users', 'Loading users failed');
    renderAdminUsers(users);
    setText(adminMessage, '');
  } catch (error) {
    setText(adminMessage, error.message);
  }
}

function renderAdminUsers(users) {
  if (!adminUsersBody) return;
  adminUsersBody.innerHTML = '';
  users.forEach((user) => {
    const row = document.createElement('tr');
    row.innerHTML = `
      <td>${user.id}</td><td>${escapeHtml(user.name)}</td><td>${escapeHtml(user.email)}</td>
      <td><select class="admin-identity-select" aria-label="Identity for ${escapeHtml(user.name)}">${createIdentityOptions(user.identityType)}</select></td>
      <td><fieldset class="admin-role-options"><legend>Roles for ${escapeHtml(user.name)}</legend>${createRoleOptions(rolesFor(user))}</fieldset></td>
      <td><button type="button" class="admin-save-role">Save</button></td>`;
    row.querySelector('.admin-save-role').addEventListener('click', async () => {
      const roles = Array.from(row.querySelectorAll('.admin-role-checkbox:checked'), (input) => input.value);
      if (!roles.length) {
        setText(adminMessage, 'Select at least one role before saving.');
        return;
      }
      const button = row.querySelector('.admin-save-role');
      button.disabled = true;
      try {
        await updateAdminUserAccess(user.id, row.querySelector('.admin-identity-select').value, roles);
      } finally {
        button.disabled = false;
      }
    });
    adminUsersBody.appendChild(row);
  });
}

function createIdentityOptions(selectedIdentityType) {
  return ['STUDENT', 'CAMPUS_STAFF'].map((identityType) => `
    <option value="${identityType}" ${identityType === selectedIdentityType ? 'selected' : ''}>${formatIdentityType(identityType)}</option>`).join('');
}

function createRoleOptions(selectedRoles) {
  return SELECTABLE_ROLES.map((role) => `
    <label><input class="admin-role-checkbox" type="checkbox" value="${role}"
      ${selectedRoles.includes(role) ? 'checked' : ''}> ${formatRole(role)}</label>`).join('');
}

async function updateAdminUserAccess(userId, identityType, roles) {
  setText(adminMessage, 'Updating identity and roles...');
  try {
    await fetchJson(`/admin/users/${userId}`, 'User access update failed', {
      method: 'PUT', body: JSON.stringify({ identityType, roles })
    });
    if (String(userId) === String(currentWorkspaceUser?.id)) {
      if (!roles.includes('ADMINISTRATOR') && adminPage) adminPage.hidden = true;
      const updatedUser = await loadCurrentUser();
      if (!updatedUser) {
        window.location.href = './index.html';
        return;
      }
      currentWorkspaceUser = updatedUser;
      if (!hasActiveRole(updatedUser, 'ADMINISTRATOR')) {
        window.location.href = getActiveRole(updatedUser)
          ? getLandingPage()
          : './index.html?selectRole=1';
        return;
      }
      displayWorkspaceRole(updatedUser);
    }
    await loadAdminUsers();
    setText(adminMessage, 'Identity and roles updated.');
  } catch (error) {
    if (!currentWorkspaceUser || !hasActiveRole(currentWorkspaceUser, 'ADMINISTRATOR')) return;
    await loadAdminUsers();
    setText(adminMessage, error.message);
  }
}

function reviewStars(score) {
  const number = Number(score);
  return Number.isInteger(number) && number >= 1 && number <= 5
    ? `${number} / 5 ★` : 'Rating unavailable';
}

function reviewBanDescription(ban) {
  if (!ban?.banned) return 'Rating access is active.';
  return `Rating access is suspended${ban.expiresAt ? ` until ${formatDate(ban.expiresAt)}` : ' permanently'}.`
    + (ban.reason ? ` Reason: ${ban.reason}` : '');
}

async function initReviewsPage() {
  const user = await requireCurrentUser();
  if (!user) return;
  if (!hasRole(user, 'GENERAL_USER')) {
    window.location.href = getLandingPage();
    return;
  }
  const main = document.getElementById('main-content');
  if (main) main.hidden = false;
  await loadReviewDirectory();
}

async function loadReviewDirectory() {
  const message = document.getElementById('reviews-message');
  const container = document.getElementById('technician-review-directory');
  if (!container) return;
  setText(message, 'Loading reviews...');
  try {
    const requestedId = new URLSearchParams(window.location.search).get('technicianId');
    const path = requestedId && /^\d+$/.test(requestedId)
      ? `/reviews/technicians/${requestedId}` : '/reviews/technicians';
    const [directory, ban] = await Promise.all([
      fetchJson(path, 'Loading technician reviews failed'),
      fetchJson('/reviews/me/ban', 'Loading rating access failed')
    ]);
    const technicians = Array.isArray(directory) ? directory : [directory];
    renderReviewDirectory(technicians.filter(Boolean), container, Boolean(requestedId));
    setText(message, ban?.banned ? reviewBanDescription(ban) : '');
    message?.classList.remove('error-state');
  } catch (error) {
    container.innerHTML = '';
    setText(message, error.message);
    message?.classList.add('error-state');
  }
}

function renderReviewDirectory(technicians, container, filtered = false) {
  if (!technicians.length) {
    container.innerHTML = '<div class="empty-state"><p>No technician reviews are available yet.</p></div>';
    return;
  }
  container.innerHTML = `${filtered ? '<p><a class="text-link" href="./reviews.html">View all technicians</a></p>' : ''}`
    + technicians.map((technician) => {
      const count = Math.max(0, Number(technician.ratingCount) || 0);
      const reviews = Array.isArray(technician.reviews) ? technician.reviews : [];
      return `<section class="surface technician-review-card" aria-labelledby="technician-review-${technician.id}">
        <header class="surface-header"><div class="surface-heading"><h2 id="technician-review-${technician.id}">${escapeHtml(technician.name)}</h2>
          <p>${count ? `${formatRating(technician.averageRating)} / 5 · ${count} rating${count === 1 ? '' : 's'}` : 'No ratings yet'}</p></div></header>
        <div class="review-list">${reviews.length ? reviews.map((review) => `
          <article class="review-entry">
            <div class="review-entry-heading"><strong>${reviewStars(review.score)}</strong><time>${formatDate(review.createdAt)}</time></div>
            ${review.comment ? `<p class="review-comment">${escapeHtml(review.comment)}</p>` : '<p class="muted-copy">No public comment.</p>'}
            <details class="review-report"><summary>Report this review</summary>
              <form class="review-report-form" data-review-id="${review.id}">
                <label for="report-reason-${review.id}">Why are you reporting this review?</label>
                <textarea id="report-reason-${review.id}" name="reason" rows="3" maxlength="1000" required></textarea>
                <button type="submit" class="secondary-button">Submit report</button>
                <p class="app-status-message" role="status" aria-live="polite"></p>
              </form>
            </details>
          </article>`).join('') : '<p class="muted-copy">No published ratings yet.</p>'}</div>
      </section>`;
    }).join('');
  container.querySelectorAll('.review-report-form').forEach((form) => {
    form.addEventListener('submit', submitReviewReport);
  });
}

async function submitReviewReport(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const reason = form.querySelector('[name="reason"]')?.value.trim() ?? '';
  const message = form.querySelector('[role="status"]');
  if (!reason) {
    setText(message, 'Please explain your report.');
    return;
  }
  const button = form.querySelector('button[type="submit"]');
  if (button) button.disabled = true;
  try {
    await fetchJson(`/reviews/${encodeURIComponent(form.dataset.reviewId)}/reports`, 'Submitting report failed', {
      method: 'POST', body: JSON.stringify({ reason })
    });
    setText(message, 'Report received. You will receive a notification after review.');
    if (button) button.disabled = true;
    loadUnreadCount().catch(() => {});
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

async function loadUnreadCount() {
  const badge = document.getElementById('notifications-unread-count');
  if (!badge) return;
  const version = ++notificationCountVersion;
  const requestedUserId = currentWorkspaceUser?.id;
  const requestedRole = getActiveRole(currentWorkspaceUser);
  const data = await fetchJson('/notifications', 'Loading notifications failed');
  if (version !== notificationCountVersion || String(currentWorkspaceUser?.id) !== String(requestedUserId)
      || getActiveRole(currentWorkspaceUser) !== requestedRole) return;
  const unread = Math.max(0, Number(data?.unreadCount) || 0);
  badge.hidden = unread === 0;
  badge.textContent = unread ? String(unread) : '';
  badge.setAttribute('aria-label', `${unread} unread notifications`);
}

async function initNotificationsPage() {
  const user = await requireCurrentUser();
  if (!user) return;
  const main = document.getElementById('main-content');
  if (main) main.hidden = false;
  await loadNotifications();
}

async function loadNotifications() {
  const message = document.getElementById('notifications-message');
  const container = document.getElementById('notifications-list');
  if (!container) return;
  setText(message, 'Loading notifications...');
  try {
    const data = await fetchJson('/notifications', 'Loading notifications failed');
    renderNotifications(data?.notifications ?? [], container);
    setText(message, data?.unreadCount ? `${data.unreadCount} unread` : 'All caught up.');
    message?.classList.remove('error-state');
    const badge = document.getElementById('notifications-unread-count');
    if (badge) {
      notificationCountVersion += 1;
      badge.hidden = !data?.unreadCount;
      badge.textContent = data?.unreadCount ? String(data.unreadCount) : '';
    }
  } catch (error) {
    container.innerHTML = '';
    setText(message, error.message);
    message?.classList.add('error-state');
  }
}

function renderNotifications(notifications, container) {
  if (!notifications.length) {
    container.innerHTML = '<div class="empty-state"><p>No notifications yet.</p></div>';
    return;
  }
  container.innerHTML = notifications.map((item) => `
    <article class="surface notification-entry${item.readAt ? '' : ' is-unread'}" data-notification-id="${item.id}">
      <div><div class="review-entry-heading"><strong>${escapeHtml(formatNotificationType(item.type))}</strong>
        <time>${formatDate(item.createdAt)}</time></div>
        <p>${escapeHtml(item.message)}</p>
        ${item.ticketId ? `<a class="text-link" href="./request-detail.html?id=${encodeURIComponent(item.ticketId)}">Open request</a>` : ''}</div>
      ${item.readAt ? '' : '<button type="button" class="secondary-button notification-read">Mark read</button>'}
    </article>`).join('');
  container.querySelectorAll('.notification-read').forEach((button) => {
    button.addEventListener('click', () => markNotificationRead(button));
  });
}

function formatNotificationType(type) {
  const labels = {
    STATUS_CHANGED: 'Request status updated', COMMENT_ADDED: 'New request comment',
    TICKET_ASSIGNED: 'Request assigned', TICKET_REASSIGNED: 'Request reassigned',
    RESOLUTION_CONFIRMED: 'Resolution confirmed', TICKET_REOPENED: 'Request reopened',
    TICKET_COMPLETED: 'Request completed', REVIEW_REPORT_SUBMITTED: 'Report received',
    REVIEW_REPORT_RESOLVED: 'Report reviewed', REVIEW_REPORT_RECEIVED: 'Review reported',
    REVIEW_TEXT_REMOVED: 'Comment removed', REVIEW_REMOVED: 'Rating removed',
    REVIEW_RESTORED: 'Rating restored', REVIEW_BANNED: 'Rating access suspended',
    REVIEW_UNBANNED: 'Rating access restored'
  };
  return labels[type] ?? String(type ?? 'Update').replaceAll('_', ' ').toLowerCase();
}

async function markNotificationRead(button) {
  const entry = button.closest('[data-notification-id]');
  if (!entry) return;
  button.disabled = true;
  try {
    await fetchJson(`/notifications/${encodeURIComponent(entry.dataset.notificationId)}/read`,
      'Marking notification read failed', { method: 'PUT' });
    entry.classList.remove('is-unread');
    button.remove();
    await loadNotifications();
  } catch (error) {
    button.disabled = false;
    const message = document.getElementById('notifications-message');
    setText(message, error.message);
  }
}

async function initReviewAdminPage() {
  const user = await requireCurrentUser();
  if (!user) return;
  if (!hasRole(user, 'ADMINISTRATOR')) {
    window.location.href = getLandingPage();
    return;
  }
  const main = document.getElementById('main-content');
  if (main) main.hidden = false;
  const banForm = document.getElementById('rating-ban-form');
  banForm?.addEventListener('submit', banStudentRating);
  document.getElementById('rating-unban')?.addEventListener('click', unbanStudentRating);
  document.getElementById('rating-ban-student')?.addEventListener('change', loadSelectedStudentBan);
  await loadReviewModeration();
}

async function loadReviewModeration() {
  const message = document.getElementById('review-admin-message');
  setText(message, 'Loading reviews...');
  try {
    const [reviews, reports, users] = await Promise.all([
      fetchJson('/admin/reviews', 'Loading reviews failed'),
      fetchJson('/admin/reviews/reports', 'Loading reports failed'),
      fetchJson('/admin/users', 'Loading students failed')
    ]);
    renderAdminReviews(reviews ?? []);
    renderReviewReports(reports ?? [], reviews ?? []);
    populateRatingStudents(users ?? []);
    setText(message, '');
    message?.classList.remove('error-state');
  } catch (error) {
    setText(message, error.message);
    message?.classList.add('error-state');
  }
}

function renderAdminReviews(reviews) {
  const container = document.getElementById('admin-reviews-list');
  if (!container) return;
  const requestedId = new URLSearchParams(window.location.search).get('technicianId');
  const shown = requestedId && /^\d+$/.test(requestedId)
    ? reviews.filter((review) => String(review.technicianId) === requestedId) : reviews;
  container.innerHTML = `${requestedId ? '<p><a class="text-link" href="./review-admin.html">View all reviews</a></p>' : ''}`
    + (shown.length ? shown.map((review) => {
      const selfAuthored = String(review.authorId) === String(currentWorkspaceUser?.id);
      return `<article class="review-entry admin-review-entry" data-review-id="${review.id}">
        <div class="review-entry-heading"><strong>${reviewStars(review.score)}</strong>
          <span class="review-visibility">${escapeHtml(review.visibility)}</span><time>${formatDate(review.createdAt)}</time></div>
        <p><strong>${escapeHtml(review.technicianName)}</strong> · Request #${escapeHtml(review.ticketId)}</p>
        <p class="muted-copy">By ${escapeHtml(review.authorName)} (${escapeHtml(review.authorEmail)})</p>
        ${review.comment ? `<p class="review-comment">${escapeHtml(review.comment)}</p>` : '<p class="muted-copy">No written comment.</p>'}
        <details class="review-audit" data-review-id="${review.id}"><summary>View change and moderation history</summary>
          <div class="review-audit-list" role="status">Open to load history.</div></details>
        ${selfAuthored ? '<p class="muted-copy">Another administrator must moderate a review you wrote.</p>' : `
          <form class="review-moderation-form" data-review-id="${review.id}">
            <label for="review-action-${review.id}">Action</label>
            <select id="review-action-${review.id}" name="action" required>
              <option value="">Select action</option>
              ${review.visibility === 'ACTIVE' && review.comment ? '<option value="REMOVE_TEXT">Remove comment, retain rating</option>' : ''}
              ${review.visibility !== 'REMOVED' ? '<option value="REMOVE_ALL">Remove rating and comment</option>' : ''}
              ${review.visibility !== 'ACTIVE' ? '<option value="RESTORE">Restore removed content</option>' : ''}
            </select>
            <label for="review-reason-${review.id}">Reason</label>
            <textarea id="review-reason-${review.id}" name="reason" rows="2" maxlength="1000" required></textarea>
            <button type="submit" class="secondary-button">Apply action</button>
            <p class="app-status-message" role="status" aria-live="polite"></p>
          </form>`}
      </article>`;
    }).join('') : '<p class="muted-copy">No reviews match this view.</p>');
  container.querySelectorAll('.review-moderation-form').forEach((form) => {
    form.addEventListener('submit', submitReviewModeration);
  });
  container.querySelectorAll('.review-audit').forEach((details) => {
    details.addEventListener('toggle', () => {
      if (details.open && details.dataset.loaded !== 'true') loadReviewAudit(details);
    });
  });
}

async function loadReviewAudit(details) {
  const target = details.querySelector('.review-audit-list');
  if (!target) return;
  setText(target, 'Loading history...');
  try {
    const history = await fetchJson(`/admin/reviews/${encodeURIComponent(details.dataset.reviewId)}/audit`,
      'Loading review history failed');
    target.innerHTML = history.length ? `<ol class="review-audit-items">${history.map((item) => `
      <li><strong>${escapeHtml(item.action)}</strong> by ${escapeHtml(item.actorName)} · ${formatDate(item.occurredAt)}
        ${item.reason ? `<p>Reason: ${escapeHtml(item.reason)}</p>` : ''}
        ${(item.beforeScore != null || item.afterScore != null) ? `<p>Score: ${escapeHtml(item.beforeScore ?? 'none')} → ${escapeHtml(item.afterScore ?? 'none')}</p>` : ''}
        ${(item.beforeComment || item.afterComment) ? `<p>Comment: ${escapeHtml(item.beforeComment ?? 'none')} → ${escapeHtml(item.afterComment ?? 'none')}</p>` : ''}
      </li>`).join('')}</ol>` : '<p>No changes recorded.</p>';
    details.dataset.loaded = 'true';
  } catch (error) {
    setText(target, error.message);
  }
}

async function submitReviewModeration(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const action = form.querySelector('[name="action"]')?.value;
  const reason = form.querySelector('[name="reason"]')?.value.trim() ?? '';
  const message = form.querySelector('[role="status"]');
  if (!action || !reason) {
    setText(message, 'Select an action and enter a reason.');
    return;
  }
  const button = form.querySelector('button[type="submit"]');
  if (button) button.disabled = true;
  try {
    await fetchJson(`/admin/reviews/${encodeURIComponent(form.dataset.reviewId)}/moderation`,
      'Moderation failed', { method: 'POST', body: JSON.stringify({ action, reason }) });
    await loadReviewModeration();
    setText(document.getElementById('review-admin-message'), 'Review action saved. The author was notified.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

function renderReviewReports(reports, reviews) {
  const container = document.getElementById('review-reports-list');
  if (!container) return;
  const pending = reports.filter((report) => report.status === 'PENDING');
  container.innerHTML = pending.length ? pending.map((report) => {
    const review = reviews.find((item) => String(item.id) === String(report.reviewId));
    const selfInvolved = String(report.reporterId) === String(currentWorkspaceUser?.id)
      || String(report.authorId) === String(currentWorkspaceUser?.id);
    return `<article class="review-entry report-entry" data-report-id="${report.id}">
      <div class="review-entry-heading"><strong>Report #${escapeHtml(report.id)}</strong><time>${formatDate(report.createdAt)}</time></div>
      <p>Reported by ${escapeHtml(report.reporterName)} · ${review ? `Review of ${escapeHtml(review.technicianName)}` : `Review #${escapeHtml(report.reviewId)}`}</p>
      <p class="review-comment"><strong>Report reason:</strong> ${escapeHtml(report.reason)}</p>
      <div class="review-report-snapshot">
        <strong>Content when reported</strong>
        <p>Rating: ${reviewStars(report.reportedScore)}</p>
        <p class="review-comment">${report.reportedComment
          ? escapeHtml(report.reportedComment) : 'No written comment at the time of the report.'}</p>
      </div>
      ${review ? `<div class="review-report-current">
        <strong>Current review · ${escapeHtml(review.visibility)}</strong>
        <p>Rating: ${reviewStars(review.score)}</p>
        <p class="review-comment">${review.comment ? escapeHtml(review.comment) : 'No current written comment.'}</p>
      </div>` : ''}
      ${selfInvolved ? '<p class="muted-copy">Another administrator must handle a report involving you.</p>' : `
        <form class="report-resolution-form" data-report-id="${report.id}">
          <label for="report-action-${report.id}">Action</label>
          <select id="report-action-${report.id}" name="action" required>
            <option value="">Select action</option>
            <option value="DISMISS">Dismiss report</option>
            ${review?.visibility === 'ACTIVE' && review.comment ? '<option value="REMOVE_TEXT">Remove comment, retain rating</option>' : ''}
            ${review?.visibility !== 'REMOVED' ? '<option value="REMOVE_ALL">Remove rating and comment</option>' : ''}
            <option value="BAN_AUTHOR">Remove comment and suspend author's rating access</option>
          </select>
          <label for="report-resolution-reason-${report.id}">Reason</label>
          <textarea id="report-resolution-reason-${report.id}" name="reason" rows="2" maxlength="1000" required></textarea>
          <label for="report-expiry-${report.id}">Suspension end date <span>(optional; blank means permanent)</span></label>
          <input id="report-expiry-${report.id}" name="expiresAt" type="datetime-local">
          <button type="submit" class="secondary-button">Resolve report</button>
          <p class="app-status-message" role="status" aria-live="polite"></p>
        </form>`}
    </article>`;
  }).join('') : '<p class="muted-copy">No reports need action.</p>';
  container.querySelectorAll('.report-resolution-form').forEach((form) => {
    form.addEventListener('submit', submitReportResolution);
  });
}

async function submitReportResolution(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const action = form.querySelector('[name="action"]')?.value;
  const reason = form.querySelector('[name="reason"]')?.value.trim() ?? '';
  const message = form.querySelector('[role="status"]');
  if (!action || !reason) {
    setText(message, 'Select an action and enter a reason.');
    return;
  }
  const button = form.querySelector('button[type="submit"]');
  if (button) button.disabled = true;
  const expiresAt = form.querySelector('[name="expiresAt"]')?.value || null;
  try {
    await fetchJson(`/admin/reviews/reports/${encodeURIComponent(form.dataset.reportId)}/resolve`,
      'Resolving report failed', {
        method: 'POST', body: JSON.stringify({ action, reason, ...(action === 'BAN_AUTHOR' && expiresAt ? { expiresAt } : {}) })
      });
    await loadReviewModeration();
    setText(document.getElementById('review-admin-message'), 'Report resolved. The reporter was notified.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

function populateRatingStudents(users) {
  const select = document.getElementById('rating-ban-student');
  if (!select) return;
  const selected = select.value;
  const eligible = users.filter((user) => rolesFor(user).includes('GENERAL_USER'));
  select.innerHTML = '<option value="">Select a student</option>' + eligible.map((user) =>
    `<option value="${user.id}">${escapeHtml(user.name)} (${escapeHtml(user.email)})</option>`).join('');
  if (eligible.some((user) => String(user.id) === selected)) select.value = selected;
  if (select.value) loadSelectedStudentBan();
}

async function loadSelectedStudentBan() {
  const studentId = document.getElementById('rating-ban-student')?.value;
  const state = document.getElementById('rating-ban-state');
  const unbanButton = document.getElementById('rating-unban');
  if (!studentId) {
    setText(state, 'Select a student to see rating access.');
    if (unbanButton) unbanButton.disabled = true;
    return;
  }
  try {
    const ban = await fetchJson(`/admin/reviews/students/${encodeURIComponent(studentId)}/ban`,
      'Loading rating access failed');
    if (document.getElementById('rating-ban-student')?.value !== studentId) return;
    setText(state, reviewBanDescription(ban));
    if (unbanButton) unbanButton.disabled = !ban.banned || String(studentId) === String(currentWorkspaceUser?.id);
  } catch (error) {
    setText(state, error.message);
    if (unbanButton) unbanButton.disabled = true;
  }
}

async function banStudentRating(event) {
  event.preventDefault();
  const studentId = document.getElementById('rating-ban-student')?.value;
  const reason = document.getElementById('rating-ban-reason')?.value.trim() ?? '';
  const expiresAt = document.getElementById('rating-ban-expiry')?.value || null;
  const message = document.getElementById('rating-ban-message');
  if (!studentId || !reason) {
    setText(message, 'Select a student and enter a reason.');
    return;
  }
  if (String(studentId) === String(currentWorkspaceUser?.id)) {
    setText(message, 'Another administrator must manage your rating access.');
    return;
  }
  const button = document.getElementById('rating-ban-save');
  if (button) button.disabled = true;
  try {
    await fetchJson(`/admin/reviews/students/${encodeURIComponent(studentId)}/ban`,
      'Suspending rating access failed', {
        method: 'POST', body: JSON.stringify({ reason, ...(expiresAt ? { expiresAt } : {}) })
      });
    await loadSelectedStudentBan();
    setText(message, 'Rating access suspended. The student was notified.');
  } catch (error) {
    setText(message, error.message);
  } finally {
    if (button) button.disabled = false;
  }
}

async function unbanStudentRating() {
  const studentId = document.getElementById('rating-ban-student')?.value;
  const reason = document.getElementById('rating-ban-reason')?.value.trim() ?? '';
  const message = document.getElementById('rating-ban-message');
  if (!studentId || !reason) {
    setText(message, 'Select a student and enter a reason.');
    return;
  }
  if (String(studentId) === String(currentWorkspaceUser?.id)) {
    setText(message, 'Another administrator must manage your rating access.');
    return;
  }
  const button = document.getElementById('rating-unban');
  if (button) button.disabled = true;
  try {
    await fetchJson(`/admin/reviews/students/${encodeURIComponent(studentId)}/unban`,
      'Restoring rating access failed', { method: 'POST', body: JSON.stringify({ reason }) });
    await loadSelectedStudentBan();
    setText(message, 'Rating access restored. The student was notified.');
  } catch (error) {
    setText(message, error.message);
    if (button) button.disabled = false;
  }
}

// Compatibility helpers for the previous single-page workspace.
async function initWorkspace() {
  if (pageName === 'dashboard' || elementByAnyId('dashboard-metrics', 'dashboard-stats')) {
    return initDashboard();
  }
  const user = await requireCurrentUser();
  if (!user) return;
  displayWorkspaceRole(user);
  document.querySelectorAll('.requester-content').forEach((element) => {
    element.hidden = !hasRole(user, 'GENERAL_USER');
  });
  const roleMessage = document.getElementById('role-workspace-message');
  if (roleMessage) roleMessage.hidden = !hasOperationalRole(user);
  setText(document.getElementById('role-workspace-title'), 'Your Workspace');
  setText(document.getElementById('role-workspace-description'),
    `Current role: ${formatRole(getActiveRole(user))}.`);
  if (hasOperationalRole(user) && allRequestsSection) {
    allRequestsSection.hidden = false;
    setText(allRequestsTitle, canViewAllRequests(user) ? 'All Maintenance Requests' : 'My Assigned Requests');
    setText(allRequestsDescription, hasRole(user, 'ADMINISTRATOR')
      ? 'Review all requests, set priority, and assign submitted work to a technician.'
      : canViewAllRequests(user)
        ? 'Review all requests. Technician actions are available only on your active assignments.'
        : 'Only requests actively assigned to your account are shown.');
    if (hasRole(user, 'ADMINISTRATOR')) await loadAssignableTechnicians();
    if (canViewAllRequests(user)) await loadPrioritySummary();
  }
  await refreshTicketLists();
}

async function loadTickets() {
  setText(ticketsMessage, 'Loading requests...');
  try {
    const tickets = await getVisibleTickets();
    renderTickets(tickets.filter((ticket) => ticket.submittedById === currentWorkspaceUser.id));
  } catch (error) {
    setText(ticketsMessage, error.message);
  }
}

async function loadAllTickets() {
  setText(allTicketsMessage, 'Loading requests...');
  try {
    const tickets = await getVisibleTickets();
    renderAllTickets(canViewAllRequests(currentWorkspaceUser) ? tickets
      : tickets.filter((ticket) => ticket.assignedTechnicianId === currentWorkspaceUser.id));
  } catch (error) {
    setText(allTicketsMessage, error.message);
  }
}

function renderTickets(tickets) {
  renderRequestRows(tickets, ticketsList, ticketsMessage, currentWorkspaceUser,
    'You have no maintenance requests yet.');
}

function renderAllTickets(tickets) {
  if (!tickets?.length) {
    setText(allTicketsMessage, canViewAllRequests(currentWorkspaceUser)
      ? 'No maintenance requests have been submitted yet.'
      : 'No maintenance requests are currently assigned to you.');
    if (allTicketsList) allTicketsList.innerHTML = '';
    return;
  }
  setText(allTicketsMessage, '');
  if (allTicketsList) allTicketsList.innerHTML = '';
  tickets.forEach((ticket) => allTicketsList?.appendChild(renderTicket(
    ticket,
    true,
    hasRole(currentWorkspaceUser, 'ADMINISTRATOR'),
    hasRole(currentWorkspaceUser, 'ADMINISTRATOR'),
    hasRole(currentWorkspaceUser, 'MAINTENANCE_STAFF') && ticket.assignedTechnicianId === currentWorkspaceUser.id
  )));
}

function renderTicket(ticket) {
  return createRequestRow(ticket, currentWorkspaceUser);
}

async function loadPrioritySummary() {
  if (!prioritySummary || !prioritySummaryValues || !prioritySummaryMessage) return;
  prioritySummary.hidden = false;
  setText(prioritySummaryMessage, 'Loading priority distribution...');
  try {
    const summary = await fetchJson('/tickets/priority-summary', 'Loading priority distribution failed');
    prioritySummaryValues.innerHTML = [
      ['Unclassified', summary.unclassified, 'priority-unclassified'],
      ['Low', summary.low, 'priority-low'], ['Medium', summary.medium, 'priority-medium'],
      ['High', summary.high, 'priority-high'], ['Critical', summary.critical, 'priority-critical']
    ].map(([label, count, className]) => `<div class="priority-summary-item"><span class="${className}">${label}</span><strong>${count}</strong></div>`).join('');
    setText(prioritySummaryMessage, '');
  } catch (error) {
    prioritySummaryValues.innerHTML = '';
    setText(prioritySummaryMessage, error.message);
  }
}

async function loadTicketImages(ticketId, imageIds, container) {
  if (!container) return;
  container.innerHTML = (imageIds ?? []).map((imageId, index) => `
    <button type="button" class="ticket-image-wrapper" data-lightbox-src="${imageUrl(ticketId, imageId)}">
      <img src="${imageUrl(ticketId, imageId)}" alt="Issue image ${index + 1}">
    </button>`).join('');
}

if (logoutButton) {
  logoutButton.addEventListener('click', async () => {
    setText(authMessage, 'Logging out...');
    try {
      const response = await apiRequest('/auth/logout', { method: 'POST' });
      if (!response.ok) throw new Error(`Logout failed (${response.status}).`);
      clearActiveRole();
      broadcastAccountChange('logout');
      window.location.href = './index.html';
    } catch (error) {
      setText(authMessage, error.message);
    }
  });
}

roleSelectionLogout?.addEventListener('click', async () => {
  roleSelectionLogout.disabled = true;
  try {
    const response = await apiRequest('/auth/logout', { method: 'POST', redirectOnUnauthorized: false });
    if (!response.ok) throw new Error('Logout failed. Please try again.');
    clearActiveRole();
    broadcastAccountChange('logout');
    window.location.href = './index.html';
  } catch (error) {
    setText(roleSelectionMessage, error.message);
    roleSelectionLogout.disabled = false;
  }
});

setupAppShell();
if (loginForm) initLoginPage();
if (pageName === 'dashboard') initDashboard();
else if (pageName === 'requests') initRequestsPage();
else if (['request-detail', 'detail'].includes(pageName)) initRequestDetailPage();
else if (['request-form', 'form'].includes(pageName)) initRequestFormPage();
else if (pageName === 'profile' && profileForm) initProfilePage();
else if (pageName === 'admin' && (adminPage || adminUsersBody)) initAdminPage();
else if (pageName === 'reviews') initReviewsPage();
else if (pageName === 'review-admin') initReviewAdminPage();
else if (pageName === 'notifications') initNotificationsPage();
else {
  if (workspacePage) initWorkspace();
  if (profileForm) initProfilePage();
  if (adminPage) initAdminPage();
  if (ticketForm && !workspacePage) initRequestFormPage();
}
