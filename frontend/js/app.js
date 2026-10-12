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

function rolesFor(user) {
  const roles = Array.isArray(user?.roles) ? user.roles : (user?.role ? [user.role] : []);
  return [...new Set(roles.filter(Boolean))];
}

function hasRole(user, role) {
  return rolesFor(user).includes(role);
}

function canViewAllRequests(user) {
  return hasRole(user, 'ADMINISTRATOR') || hasRole(user, 'MANAGER');
}

function hasOperationalRole(user) {
  return canViewAllRequests(user) || hasRole(user, 'MAINTENANCE_STAFF');
}

function isTicketOwner(ticket, user = currentWorkspaceUser) {
  return Boolean(ticket && user && ticket.submittedById === user.id);
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
  if (hasRole(user, 'MANAGER')) return 'manager';
  if (hasRole(user, 'MAINTENANCE_STAFF')) return 'technician';
  return 'requester';
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
  const response = await fetch(`${API_BASE_URL}${path}`, {
    credentials: 'include',
    ...fetchOptions,
    headers: {
      ...(hasFormDataBody ? {} : fetchOptions.body ? { 'Content-Type': 'application/json' } : {}),
      ...(fetchOptions.headers ?? {})
    }
  });

  if (response.status === 401 && redirectOnUnauthorized) {
    window.location.href = './index.html';
    throw new Error('Your session has expired. Please log in again.');
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

async function loadCurrentUser() {
  try {
    const response = await fetch(`${API_BASE_URL}/users/me`, { credentials: 'include' });
    if (!response.ok) return null;
    return await response.json();
  } catch (_) {
    return null;
  }
}

async function requireCurrentUser() {
  const user = await loadCurrentUser();
  if (!user) {
    window.location.href = './index.html';
    return null;
  }
  currentWorkspaceUser = user;
  displayWorkspaceRole(user);
  applyRoleVisibility(user);
  return user;
}

function displayWorkspaceRole(user) {
  if (workspaceRole) {
    workspaceRole.textContent = rolesFor(user).map(formatRole).join(' · ');
    workspaceRole.hidden = false;
  }
  if (adminLink) adminLink.hidden = !hasRole(user, 'ADMINISTRATOR');

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
    setText(authMessage, 'Logging in...');
    try {
      await fetchJson('/auth/login', 'Login failed', {
        method: 'POST',
        redirectOnUnauthorized: false,
        body: JSON.stringify({
          email: document.getElementById('login-email').value,
          password: document.getElementById('login-password').value
        })
      });
      window.location.href = getLandingPage();
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
  if (mode === 'administrator' || mode === 'manager') return tickets;
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
  if (mode === 'manager') {
    return [
      ['Open requests', tickets.filter((ticket) => !isTerminalStatus(ticket.status)).length,
        'view=active', 'Across campus'],
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
  const message = elementByAnyId('dashboard-message');
  const list = elementByAnyId('dashboard-tickets', 'dashboard-focus-list', 'attention-requests-list');
  const metrics = elementByAnyId('dashboard-metrics', 'dashboard-stats');
  setText(message, 'Loading your workspace...');
  try {
    visibleTickets = await getVisibleTickets();
    const scopedTickets = scopeTicketsForDashboard(visibleTickets, user);
    renderDashboardMetrics(user, scopedTickets);
    renderRequestRows(focusTicketsFor(user, scopedTickets), list, message, user,
      'There are no active requests needing attention.');
    message?.classList.remove('error-state');
  } catch (error) {
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
  }
}

async function initDashboard() {
  const user = await requireCurrentUser();
  if (!user) return;
  await loadDashboardData(user);
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
  return `${API_BASE_URL}/tickets/${encodeURIComponent(ticketId)}/images/${encodeURIComponent(imageId)}`;
}

function evidenceImageUrl(ticketId, evidenceId) {
  return `${API_BASE_URL}/tickets/${encodeURIComponent(ticketId)}/completion-evidence/${encodeURIComponent(evidenceId)}/image`;
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
  return `
    <dl class="detail-meta-list">
      <div><dt>Status</dt><dd>${renderStatusBadge(ticket.status)}</dd></div>
      <div><dt>Suggested urgency</dt><dd><span class="urgency-badge urgency-${ticket.urgency.toLowerCase()}">${formatUrgency(ticket.urgency)}</span></dd></div>
      <div><dt>Operational priority</dt><dd>${ticket.operationalPriority
    ? `<span class="priority-badge priority-${ticket.operationalPriority.toLowerCase()}">${formatPriority(ticket.operationalPriority)}</span>`
    : '<span class="priority-badge priority-unclassified">Unclassified</span>'}</dd></div>
      <div><dt>Category</dt><dd>${escapeHtml(formatCategory(ticket.category))}</dd></div>
      <div><dt>Location</dt><dd>${escapeHtml(ticket.location)}</dd></div>
      <div><dt>Assigned technician</dt><dd>${ticket.assignedTechnicianId
    ? `${escapeHtml(ticket.assignedTechnicianName)}<small>${escapeHtml(ticket.assignedTechnicianEmail)}</small>`
    : 'Unassigned'}</dd></div>
      <div><dt>Submitted by</dt><dd>${escapeHtml(ticket.submittedByName)}<small>${escapeHtml(ticket.submittedByEmail)}</small></dd></div>
      <div><dt>Submitted</dt><dd>${formatDate(ticket.createdAt)}</dd></div>
      <div><dt>Last updated</dt><dd>${formatDate(ticket.updatedAt)}</dd></div>
    </dl>`;
}

function createTechnicianOptions(excludedTechnicianId = null) {
  return availableTechnicians
    .filter((technician) => technician.id !== excludedTechnicianId)
    .map((technician) => `<option value="${technician.id}">${escapeHtml(technician.name)} (${escapeHtml(technician.email)})</option>`)
    .join('');
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

function bindDetailInteractions(ticket) {
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

function populateTicketForm(ticket) {
  document.getElementById('ticket-title').value = ticket.title;
  document.getElementById('ticket-description').value = ticket.description;
  document.getElementById('ticket-category').value = ticket.category;
  document.getElementById('ticket-location').value = ticket.location;
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
  const ticketData = {
    title: document.getElementById('ticket-title').value.trim(),
    description: document.getElementById('ticket-description').value.trim(),
    category: document.getElementById('ticket-category').value,
    location: document.getElementById('ticket-location').value.trim(),
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
    ticketForm?.setAttribute('hidden', '');
    setText(ticketMessage, 'Requester access is required to create a maintenance request.');
    return;
  }
  const imageInput = document.getElementById('ticket-images');
  imageInput?.addEventListener('change', () => {
    addSelectedTicketFiles(imageInput.files);
    imageInput.value = '';
  });
  ticketForm?.addEventListener('submit', handleTicketSubmit);
  ticketResetButton?.addEventListener('click', resetTicketForm);
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
    GENERAL_USER: 'Requester', MAINTENANCE_STAFF: 'Maintenance Staff',
    ADMINISTRATOR: 'Administrator', MANAGER: 'Manager'
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
  } catch (error) {
    setText(authMessage, error.message);
  }
}

function displayProfile(user) {
  setText(document.getElementById('profile-id'), user.id);
  setText(document.getElementById('profile-email'), user.email);
  setText(document.getElementById('profile-identity-type'), formatIdentityType(user.identityType));
  setText(document.getElementById('profile-role'), rolesFor(user).map(formatRole).join(', '));
  const name = document.getElementById('profile-name');
  const contact = document.getElementById('contact-information');
  const details = document.getElementById('profile-details');
  if (name) name.value = user.name;
  if (contact) contact.value = user.contactInformation ?? '';
  if (details) details.value = user.profileDetails ?? '';
}

async function loadAvatar() {
  if (!profileAvatar) return;
  const response = await apiRequest(`/users/me/avatar?v=${Date.now()}`);
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
  await loadProfile();
  await loadAvatar().catch((error) => setText(authMessage, error.message));
}

async function initAdminPage() {
  const user = await requireCurrentUser();
  if (!user) return;
  if (!hasRole(user, 'ADMINISTRATOR')) {
    window.location.href = getLandingPage();
    return;
  }
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
  return ['GENERAL_USER', 'MAINTENANCE_STAFF', 'ADMINISTRATOR', 'MANAGER'].map((role) => `
    <label><input class="admin-role-checkbox" type="checkbox" value="${role}"
      ${selectedRoles.includes(role) ? 'checked' : ''}> ${formatRole(role)}</label>`).join('');
}

async function updateAdminUserAccess(userId, identityType, roles) {
  setText(adminMessage, 'Updating identity and roles...');
  try {
    await fetchJson(`/admin/users/${userId}`, 'User access update failed', {
      method: 'PUT', body: JSON.stringify({ identityType, roles })
    });
    await loadAdminUsers();
    setText(adminMessage, 'Identity and roles updated.');
  } catch (error) {
    await loadAdminUsers();
    setText(adminMessage, error.message);
  }
}

// Compatibility helpers for the previous single-page workspace.
async function initWorkspace() {
  if (pageName === 'dashboard' || elementByAnyId('dashboard-metrics', 'dashboard-stats')) {
    return initDashboard();
  }
  const user = await loadCurrentUser();
  if (!user) {
    window.location.href = './index.html';
    return;
  }
  currentWorkspaceUser = user;
  displayWorkspaceRole(user);
  document.querySelectorAll('.requester-content').forEach((element) => {
    element.hidden = !hasRole(user, 'GENERAL_USER');
  });
  const roleMessage = document.getElementById('role-workspace-message');
  if (roleMessage) roleMessage.hidden = !hasOperationalRole(user);
  setText(document.getElementById('role-workspace-title'), 'Your Workspace');
  setText(document.getElementById('role-workspace-description'),
    `Your roles: ${rolesFor(user).map(formatRole).join(', ')}.`);
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
      window.location.href = './index.html';
    } catch (error) {
      setText(authMessage, error.message);
    }
  });
}

setupAppShell();
if (pageName === 'dashboard') initDashboard();
else if (pageName === 'requests') initRequestsPage();
else if (['request-detail', 'detail'].includes(pageName)) initRequestDetailPage();
else if (['request-form', 'form'].includes(pageName)) initRequestFormPage();
else if (pageName === 'profile' && profileForm) initProfilePage();
else if (pageName === 'admin' && (adminPage || adminUsersBody)) initAdminPage();
else {
  if (workspacePage) initWorkspace();
  if (profileForm) initProfilePage();
  if (adminPage) initAdminPage();
  if (ticketForm && !workspacePage) initRequestFormPage();
}
