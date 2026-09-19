// Use the same hostname as the page so the session cookie (JSESSIONID) is
// always sent to the backend. This avoids the "login bounces back" issue that
// happens when the frontend is opened on 127.0.0.1 while the backend is on
// localhost (or vice versa): the two hostnames do not share cookies.
const API_BASE_URL = `http://${window.location.hostname}:8080/api`;

const registrationForm = document.getElementById('registration-form');
const registrationMessage = document.getElementById('registration-message');
const verificationEmailInput = document.getElementById('email');
const verificationCodeInput = document.getElementById('verification-code');
const sendVerificationCodeButton = document.getElementById('send-verification-code');
const verificationMessage = document.getElementById('verification-message');
const loginForm = document.getElementById('login-form');
const logoutButton = document.getElementById('logout-button');
const authMessage = document.getElementById('auth-message');
const workspacePage = document.getElementById('workspace-page');
const workspaceRole = document.getElementById('workspace-role');
const ticketForm = document.getElementById('ticket-form');
const ticketSubmitButton = document.getElementById('ticket-submit-button');
const ticketResetButton = document.getElementById('ticket-reset-button');
const ticketFormTitle = document.getElementById('request-form-title');
const ticketIdInput = document.getElementById('ticket-id');
const ticketMessage = document.getElementById('ticket-message');
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

function rolesFor(user) {
  return user?.roles ?? (user?.role ? [user.role] : []);
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

async function refreshTicketLists() {
  if (hasRole(currentWorkspaceUser, 'GENERAL_USER')) await loadTickets();
  if (hasOperationalRole(currentWorkspaceUser)) await loadAllTickets();
}
let availableTechnicians = [];

if (sendVerificationCodeButton) {
  sendVerificationCodeButton.addEventListener('click', async () => {
    if (!verificationEmailInput.checkValidity()) {
      verificationEmailInput.reportValidity();
      return;
    }

    sendVerificationCodeButton.disabled = true;
    verificationMessage.textContent = 'Sending verification code...';

    try {
      const response = await fetch(`${API_BASE_URL}/auth/verification-code`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: verificationEmailInput.value })
      });

      if (!response.ok) {
        throw new Error(await readApiError(response, `Sending code failed (${response.status}).`));
      }

      const delivery = await response.json();
      verificationMessage.textContent = `If this email can be registered, a code has been sent. It expires in ${delivery.expiresInMinutes} minutes.`;
      verificationCodeInput.focus();
      startVerificationCooldown(delivery.retryAfterSeconds);
    } catch (error) {
      verificationMessage.textContent = error.message;
      sendVerificationCodeButton.disabled = false;
    }
  });
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

async function readApiError(response, fallbackMessage) {
  const data = await response.json().catch(() => null);
  return data?.detail ?? data?.message ?? fallbackMessage;
}

if (registrationForm) {
  registrationForm.addEventListener('submit', async (event) => {
    event.preventDefault();

    const registrationData = {
      name: document.getElementById('name').value,
      email: document.getElementById('email').value,
      password: document.getElementById('password').value,
      verificationCode: document.getElementById('verification-code').value
    };

    registrationMessage.textContent = 'Registering...';

    try {
      const response = await fetch(`${API_BASE_URL}/auth/register`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(registrationData)
      });

      if (!response.ok) {
        if (response.status === 409) {
          throw new Error('This email is already registered.');
        }

        throw new Error(await readApiError(response, `Registration failed (${response.status}).`));
      }

      const user = await response.json();
      registrationMessage.textContent = `Registration successful. User ID: ${user.id}`;
      registrationForm.reset();
    } catch (error) {
      registrationMessage.textContent = error.message;
    }
  });
}

if (loginForm) {
  loginForm.addEventListener('submit', async (event) => {
    event.preventDefault();

    const loginData = {
      email: document.getElementById('login-email').value,
      password: document.getElementById('login-password').value
    };

    authMessage.textContent = 'Logging in...';

    try {
      const response = await fetch(`${API_BASE_URL}/auth/login`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify(loginData)
      });

      if (!response.ok) {
        if (response.status === 401) {
          throw new Error('Invalid email or password.');
        }

        throw new Error(`Login failed (${response.status}).`);
      }

      const user = await response.json();
      window.location.href = getLandingPage(user.role);
    } catch (error) {
      authMessage.textContent = error.message;
    }
  });
}

if (ticketForm) {
  initWorkspace();
  ticketForm.addEventListener('submit', handleTicketSubmit);
  ticketResetButton.addEventListener('click', resetTicketForm);
}

async function initWorkspace() {
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
  roleMessage.hidden = !hasOperationalRole(user);
  document.getElementById('role-workspace-title').textContent = 'Your Workspace';
  document.getElementById('role-workspace-description').textContent =
    `Your roles: ${rolesFor(user).map(formatRole).join(', ')}.`;

  if (hasOperationalRole(user)) {
    allRequestsSection.hidden = false;
    allRequestsTitle.textContent = canViewAllRequests(user)
      ? 'All Maintenance Requests' : 'My Assigned Requests';
    allRequestsDescription.textContent = hasRole(user, 'ADMINISTRATOR')
      ? 'Review all requests, set priority, and assign submitted work to a technician.'
      : canViewAllRequests(user)
        ? 'Review all requests. Technician actions are available only on your active assignments.'
        : 'Only requests actively assigned to your account are shown.';
    if (hasRole(user, 'ADMINISTRATOR')) await loadAssignableTechnicians();
    if (canViewAllRequests(user)) await loadPrioritySummary();
  }
  await refreshTicketLists();
}

function getLandingPage(role) {
  return './workspace.html';
}

async function loadCurrentUser() {
  try {
    const response = await fetch(`${API_BASE_URL}/users/me`, {
      credentials: 'include'
    });

    if (response.status === 401) {
      return null;
    }

    if (!response.ok) {
      return null;
    }

    return await response.json();
  } catch (error) {
    return null;
  }
}

function displayWorkspaceRole(user) {
  if (workspaceRole) {
    workspaceRole.textContent = rolesFor(user).map(formatRole).join(' · ');
    workspaceRole.hidden = false;
  }
  const adminLink = document.getElementById('admin-link');
  if (adminLink) adminLink.hidden = !hasRole(user, 'ADMINISTRATOR');
}

async function loadTickets() {
  ticketsMessage.textContent = 'Loading requests...';

  try {
    const response = await fetch(`${API_BASE_URL}/tickets`, {
      credentials: 'include'
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      throw new Error(`Loading requests failed (${response.status}).`);
    }

    const tickets = await response.json();
    renderTickets(tickets.filter((ticket) => ticket.submittedById === currentWorkspaceUser.id));
  } catch (error) {
    ticketsMessage.textContent = error.message;
  }
}

async function loadAllTickets() {
  allTicketsMessage.textContent = 'Loading requests...';

  try {
    const response = await fetch(`${API_BASE_URL}/tickets`, {
      credentials: 'include'
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      throw new Error(`Loading requests failed (${response.status}).`);
    }

    const tickets = await response.json();
    renderAllTickets(canViewAllRequests(currentWorkspaceUser) ? tickets
      : tickets.filter((ticket) => ticket.assignedTechnicianId === currentWorkspaceUser.id));
  } catch (error) {
    allTicketsMessage.textContent = error.message;
  }
}

function renderTickets(tickets) {
  if (!tickets || tickets.length === 0) {
    ticketsMessage.textContent = 'You have no maintenance requests yet.';
    ticketsList.innerHTML = '';
    return;
  }

  ticketsMessage.textContent = '';
  ticketsList.innerHTML = '';

  tickets.forEach((ticket) => {
    ticketsList.appendChild(renderTicket(ticket));
  });
}

function renderAllTickets(tickets) {
  if (!tickets || tickets.length === 0) {
    allTicketsMessage.textContent = !canViewAllRequests(currentWorkspaceUser)
      ? 'No maintenance requests are currently assigned to you.'
      : 'No maintenance requests have been submitted yet.';
    allTicketsList.innerHTML = '';
    return;
  }

  allTicketsMessage.textContent = '';
  allTicketsList.innerHTML = '';

  tickets.forEach((ticket) => {
    allTicketsList.appendChild(renderTicket(
      ticket,
      true,
      hasRole(currentWorkspaceUser, 'ADMINISTRATOR'),
      hasRole(currentWorkspaceUser, 'ADMINISTRATOR'),
      hasRole(currentWorkspaceUser, 'MAINTENANCE_STAFF')
        && ticket.assignedTechnicianId === currentWorkspaceUser.id
    ));
  });
}

function renderTicket(
  ticket,
  readOnly = false,
  canManagePriority = false,
  canAssignTicket = false,
  canUpdateStatus = false
) {
  const article = document.createElement('article');
  article.className = 'ticket-card';
  article.dataset.ticketId = ticket.id;

  const canEdit = !readOnly && ticket.status === 'SUBMITTED';
  const canCancel = !readOnly && ticket.status === 'SUBMITTED';

  article.innerHTML = `
    <div class="ticket-card-header">
      <h3>${escapeHtml(ticket.title)}</h3>
      <span class="status-badge status-${ticket.status.toLowerCase()}">${formatStatus(ticket.status)}</span>
    </div>
    <p class="ticket-meta">
      <span>${formatCategory(ticket.category)}</span>
      &middot;
      <span>${escapeHtml(ticket.location)}</span>
      &middot;
      <span class="urgency-${ticket.urgency.toLowerCase()}">Suggested urgency: ${formatUrgency(ticket.urgency)}</span>
    </p>
    <p class="ticket-priority">
      <strong>Operational priority:</strong>
      <span class="${ticket.operationalPriority ? `priority-${ticket.operationalPriority.toLowerCase()}` : 'priority-unset'}">
        ${formatPriority(ticket.operationalPriority)}
      </span>
    </p>
    <p class="ticket-assignment">
      <strong>Assigned technician:</strong>
      ${ticket.assignedTechnicianId
        ? `${escapeHtml(ticket.assignedTechnicianName)} (${escapeHtml(ticket.assignedTechnicianEmail)})`
        : '<span class="assignment-unassigned">Unassigned</span>'}
      ${ticket.assignedAt ? `<span class="assignment-time">Assigned: ${formatDate(ticket.assignedAt)}</span>` : ''}
    </p>
    ${readOnly ? `<p class="ticket-dates"><strong>Submitted by:</strong> ${escapeHtml(ticket.submittedByName)} (${escapeHtml(ticket.submittedByEmail)})</p>` : ''}
    <p class="ticket-description">${escapeHtml(ticket.description)}</p>
    <p class="ticket-dates">Submitted: ${formatDate(ticket.createdAt)}</p>
    <div class="ticket-images" data-ticket-id="${ticket.id}"></div>
    <div class="ticket-card-actions">
      ${canEdit ? `<button type="button" class="ticket-edit-button" data-ticket-id="${ticket.id}">Edit</button>` : ''}
      ${canCancel ? `<button type="button" class="ticket-cancel-button secondary-danger" data-ticket-id="${ticket.id}">Cancel Request</button>` : ''}
      <button type="button" class="ticket-history-button" data-ticket-id="${ticket.id}">View History</button>
    </div>
    <div class="ticket-history" data-ticket-id="${ticket.id}" hidden></div>
    ${canManagePriority ? `
      <div class="priority-management">
        <label for="priority-${ticket.id}">Set Operational Priority</label>
        <div class="priority-management-controls">
          <select id="priority-${ticket.id}" class="ticket-priority-select">
            <option value="">Select priority</option>
            ${createPriorityOptions(ticket.operationalPriority)}
          </select>
          <input
            class="ticket-priority-reason"
            type="text"
            maxlength="1000"
            placeholder="Reason (optional)"
            aria-label="Priority reason for ${escapeHtml(ticket.title)}">
          <button type="button" class="ticket-priority-save">Save Priority</button>
        </div>
        <p class="ticket-priority-message" role="status"></p>
      </div>
    ` : ''}
    ${canAssignTicket && ticket.status === 'SUBMITTED' && !ticket.assignedTechnicianId ? `
      <div class="assignment-management">
        <label for="technician-${ticket.id}">Assign Technician</label>
        <div class="assignment-management-controls">
          <select id="technician-${ticket.id}" class="ticket-technician-select">
            <option value="">Select active Maintenance Staff</option>
            ${createTechnicianOptions()}
          </select>
          <button type="button" class="ticket-assignment-save">Assign Request</button>
        </div>
        <p class="ticket-assignment-message" role="status"></p>
      </div>
    ` : ''}
    ${canAssignTicket
      && ticket.assignedTechnicianId
      && ['ASSIGNED', 'IN_PROGRESS', 'REOPENED'].includes(ticket.status) ? `
      <div class="assignment-management reassignment-management">
        <label for="reassignment-technician-${ticket.id}">Reassign Technician</label>
        <div class="assignment-management-controls">
          <select id="reassignment-technician-${ticket.id}" class="ticket-reassignment-select">
            <option value="">Select a different Maintenance Staff member</option>
            ${createTechnicianOptions(ticket.assignedTechnicianId)}
          </select>
          <input
            class="ticket-reassignment-reason"
            type="text"
            maxlength="1000"
            placeholder="Reason for reassignment"
            aria-label="Reassignment reason for ${escapeHtml(ticket.title)}">
          <button type="button" class="ticket-reassignment-save">Reassign Request</button>
        </div>
        <p class="ticket-reassignment-message" role="status"></p>
      </div>
    ` : ''}
    ${canUpdateStatus && ['ASSIGNED', 'IN_PROGRESS'].includes(ticket.status) ? `
      <div class="status-management">
        <button
          type="button"
          class="ticket-status-save"
          data-next-status="${ticket.status === 'ASSIGNED' ? 'IN_PROGRESS' : 'COMPLETED'}">
          ${ticket.status === 'ASSIGNED' ? 'Start Progress' : 'Mark Completed'}
        </button>
        <p class="ticket-status-message" role="status"></p>
      </div>
    ` : ''}
  `;

  article.querySelectorAll('.ticket-edit-button').forEach((button) => {
    button.addEventListener('click', () => startEditing(ticket));
  });

  article.querySelectorAll('.ticket-cancel-button').forEach((button) => {
    button.addEventListener('click', () => confirmCancelTicket(ticket.id));
  });

  article.querySelectorAll('.ticket-history-button').forEach((button) => {
    button.addEventListener('click', () => toggleTicketHistory(
      ticket.id,
      article.querySelector('.ticket-history'),
      button
    ));
  });

  const prioritySaveButton = article.querySelector('.ticket-priority-save');
  if (prioritySaveButton) {
    prioritySaveButton.addEventListener('click', () => updateTicketPriority(
      ticket.id,
      article.querySelector('.ticket-priority-select'),
      article.querySelector('.ticket-priority-reason'),
      article.querySelector('.ticket-priority-message'),
      prioritySaveButton
    ));
  }

  const assignmentSaveButton = article.querySelector('.ticket-assignment-save');
  if (assignmentSaveButton) {
    assignmentSaveButton.addEventListener('click', () => assignTicket(
      ticket.id,
      article.querySelector('.ticket-technician-select'),
      article.querySelector('.ticket-assignment-message'),
      assignmentSaveButton
    ));
  }

  const reassignmentSaveButton = article.querySelector('.ticket-reassignment-save');
  if (reassignmentSaveButton) {
    reassignmentSaveButton.addEventListener('click', () => reassignTicket(
      ticket.id,
      article.querySelector('.ticket-reassignment-select'),
      article.querySelector('.ticket-reassignment-reason'),
      article.querySelector('.ticket-reassignment-message'),
      reassignmentSaveButton
    ));
  }

  const statusSaveButton = article.querySelector('.ticket-status-save');
  if (statusSaveButton) {
    statusSaveButton.addEventListener('click', () => updateTicketStatus(
      ticket.id,
      statusSaveButton.dataset.nextStatus,
      article.querySelector('.ticket-status-message'),
      statusSaveButton
    ));
  }

  loadTicketImages(ticket.id, ticket.imageIds, article.querySelector('.ticket-images'));

  return article;
}

function createTechnicianOptions(excludedTechnicianId = null) {
  return availableTechnicians
    .filter((technician) => technician.id !== excludedTechnicianId)
    .map((technician) => `
    <option value="${technician.id}">
      ${escapeHtml(technician.name)} (${escapeHtml(technician.email)})
    </option>
  `).join('');
}

async function assignTicket(ticketId, select, message, button) {
  if (!select.value) {
    message.textContent = 'Please select an active Maintenance Staff member.';
    return;
  }

  message.textContent = 'Assigning request...';
  button.disabled = true;

  try {
    const response = await fetch(`${API_BASE_URL}/tickets/${ticketId}/assignment`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ technicianId: Number(select.value) })
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }
    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `Assignment failed (${response.status}).`);
    }

    await refreshTicketLists();
    allTicketsMessage.textContent = 'Request assigned successfully.';
  } catch (error) {
    message.textContent = error.message;
    button.disabled = false;
  }
}

async function reassignTicket(ticketId, select, reasonInput, message, button) {
  const reason = reasonInput.value.trim();
  if (!select.value) {
    message.textContent = 'Please select a different Maintenance Staff member.';
    return;
  }
  if (!reason) {
    message.textContent = 'Please provide a reason for reassignment.';
    return;
  }

  message.textContent = 'Reassigning request...';
  button.disabled = true;

  try {
    const response = await fetch(`${API_BASE_URL}/tickets/${ticketId}/reassignment`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({
        technicianId: Number(select.value),
        reason
      })
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }
    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `Reassignment failed (${response.status}).`);
    }

    await refreshTicketLists();
    allTicketsMessage.textContent = 'Request reassigned successfully.';
  } catch (error) {
    message.textContent = error.message;
    button.disabled = false;
  }
}

function createPriorityOptions(selectedPriority) {
  return ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map((priority) => `
    <option value="${priority}" ${priority === selectedPriority ? 'selected' : ''}>
      ${formatPriority(priority)}
    </option>
  `).join('');
}

async function updateTicketPriority(ticketId, select, reasonInput, message, button) {
  if (!select.value) {
    message.textContent = 'Please select an operational priority.';
    return;
  }

  message.textContent = 'Saving priority...';
  button.disabled = true;

  try {
    const response = await fetch(`${API_BASE_URL}/tickets/${ticketId}/priority`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({
        priority: select.value,
        reason: reasonInput.value.trim() || null
      })
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `Priority update failed (${response.status}).`);
    }

    await refreshTicketLists();
    await loadPrioritySummary();
    allTicketsMessage.textContent = 'Operational priority updated.';
  } catch (error) {
    message.textContent = error.message;
    button.disabled = false;
  }
}

async function updateTicketStatus(ticketId, status, message, button) {
  message.textContent = 'Updating status...';
  button.disabled = true;

  try {
    const response = await fetch(`${API_BASE_URL}/tickets/${ticketId}/status`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ status })
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `Status update failed (${response.status}).`);
    }

    await refreshTicketLists();
    allTicketsMessage.textContent = 'Request status updated.';
  } catch (error) {
    message.textContent = error.message;
    button.disabled = false;
  }
}

async function loadAssignableTechnicians() {
  const response = await fetch(`${API_BASE_URL}/tickets/technicians`, {
    credentials: 'include'
  });

  if (response.status === 401) {
    window.location.href = './index.html';
    return;
  }
  if (!response.ok) {
    throw new Error(`Loading technicians failed (${response.status}).`);
  }

  availableTechnicians = await response.json();
}

async function loadPrioritySummary() {
  prioritySummary.hidden = false;
  prioritySummaryMessage.textContent = 'Loading priority distribution...';

  try {
    const response = await fetch(`${API_BASE_URL}/tickets/priority-summary`, {
      credentials: 'include'
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      throw new Error(`Loading priority distribution failed (${response.status}).`);
    }

    const summary = await response.json();
    prioritySummaryValues.innerHTML = [
      ['Unclassified', summary.unclassified, 'priority-unset'],
      ['Low', summary.low, 'priority-low'],
      ['Medium', summary.medium, 'priority-medium'],
      ['High', summary.high, 'priority-high'],
      ['Critical', summary.critical, 'priority-critical']
    ].map(([label, count, className]) => `
      <div class="priority-summary-item">
        <span class="${className}">${label}</span>
        <strong>${count}</strong>
      </div>
    `).join('');
    prioritySummaryMessage.textContent = '';
  } catch (error) {
    prioritySummaryValues.innerHTML = '';
    prioritySummaryMessage.textContent = error.message;
  }
}

async function toggleTicketHistory(ticketId, container, button) {
  if (!container.hidden) {
    container.hidden = true;
    button.textContent = 'View History';
    return;
  }

  container.hidden = false;
  button.textContent = 'Hide History';

  if (container.dataset.loaded === 'true') {
    return;
  }

  container.innerHTML = '<p>Loading history...</p>';

  try {
    const response = await fetch(`${API_BASE_URL}/tickets/${ticketId}/history`, {
      credentials: 'include'
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      throw new Error(`Loading history failed (${response.status}).`);
    }

    renderTicketHistory(await response.json(), container);
    container.dataset.loaded = 'true';
  } catch (error) {
    container.innerHTML = `<p>${escapeHtml(error.message)}</p>`;
  }
}

function renderTicketHistory(historyItems, container) {
  if (!historyItems || historyItems.length === 0) {
    container.innerHTML = '<p>No history is available for this request.</p>';
    return;
  }

  container.innerHTML = `
    <h4>Request History</h4>
    <ol class="history-timeline">
      ${historyItems.map((item) => `
        <li>
          <div class="history-heading">
            <strong>${formatHistoryEvent(item.eventType)}</strong>
            <time>${formatDate(item.occurredAt)}</time>
          </div>
          <p>By ${escapeHtml(item.actorName)}</p>
          ${item.previousValue ? `<details><summary>Previous value</summary><pre>${escapeHtml(item.previousValue)}</pre></details>` : ''}
          ${item.newValue ? `<details><summary>New value</summary><pre>${escapeHtml(item.newValue)}</pre></details>` : ''}
          ${item.reason ? `<p>Reason: ${escapeHtml(item.reason)}</p>` : ''}
        </li>
      `).join('')}
    </ol>
  `;
}

function formatHistoryEvent(eventType) {
  const labels = {
    LEGACY_IMPORTED: 'Existing Request Imported',
    TICKET_CREATED: 'Request Created',
    TICKET_UPDATED: 'Request Updated',
    TICKET_CANCELLED: 'Request Cancelled',
    IMAGE_ADDED: 'Image Added',
    IMAGE_REMOVED: 'Image Removed',
    PRIORITY_CHANGED: 'Priority Changed',
    TICKET_ASSIGNED: 'Request Assigned',
    TICKET_REASSIGNED: 'Request Reassigned',
    STATUS_CHANGED: 'Status Changed',
    RESOLUTION_CONFIRMED: 'Resolution Confirmed',
    TICKET_REOPENED: 'Request Reopened'
  };
  return labels[eventType] ?? eventType;
}

async function loadTicketImages(ticketId, imageIds, container) {
  if (!imageIds || imageIds.length === 0) {
    container.innerHTML = '';
    return;
  }

  const images = await Promise.all(
    imageIds.map(async (imageId) => {
      try {
        const response = await fetch(
          `${API_BASE_URL}/tickets/${ticketId}/images/${imageId}`,
          { credentials: 'include' }
        );

        if (!response.ok) {
          return null;
        }

        const blob = await response.blob();
        return { imageId, url: URL.createObjectURL(blob) };
      } catch (error) {
        return null;
      }
    })
  );

  container.innerHTML = '';
  images.forEach((image) => {
    if (!image) {
      return;
    }

    const wrapper = document.createElement('div');
    wrapper.className = 'ticket-image-wrapper';
    wrapper.innerHTML = `<img src="${image.url}" alt="Issue image">`;
    container.appendChild(wrapper);
  });
}

function startEditing(ticket) {
  document.getElementById('ticket-title').value = ticket.title;
  document.getElementById('ticket-description').value = ticket.description;
  document.getElementById('ticket-category').value = ticket.category;
  document.getElementById('ticket-location').value = ticket.location;
  document.getElementById('ticket-urgency').value = ticket.urgency;
  ticketIdInput.value = ticket.id;

  ticketFormTitle.textContent = 'Edit Maintenance Request';
  ticketSubmitButton.textContent = 'Save Changes';
  ticketResetButton.hidden = false;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function resetTicketForm() {
  ticketForm.reset();
  ticketIdInput.value = '';
  ticketFormTitle.textContent = 'Submit Maintenance Request';
  ticketSubmitButton.textContent = 'Submit Request';
  ticketResetButton.hidden = true;
  ticketMessage.textContent = '';
}

async function handleTicketSubmit(event) {
  event.preventDefault();

  const ticketData = {
    title: document.getElementById('ticket-title').value.trim(),
    description: document.getElementById('ticket-description').value.trim(),
    category: document.getElementById('ticket-category').value,
    location: document.getElementById('ticket-location').value.trim(),
    urgency: document.getElementById('ticket-urgency').value
  };

  const images = document.getElementById('ticket-images').files;
  const editingId = ticketIdInput.value;

  try {
    let ticketId = editingId;

    if (editingId) {
      ticketMessage.textContent = 'Saving changes...';
      const response = await fetch(`${API_BASE_URL}/tickets/${editingId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(ticketData)
      });

      if (response.status === 401) {
        window.location.href = './index.html';
        return;
      }

      if (!response.ok) {
        const data = await response.json().catch(() => null);
        throw new Error(data?.message ?? `Update failed (${response.status}).`);
      }

      ticketMessage.textContent = 'Request updated.';
    } else {
      ticketMessage.textContent = 'Submitting request...';
      const response = await fetch(`${API_BASE_URL}/tickets`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(ticketData)
      });

      if (response.status === 401) {
        window.location.href = './index.html';
        return;
      }

      if (!response.ok) {
        const data = await response.json().catch(() => null);
        throw new Error(data?.message ?? `Submission failed (${response.status}).`);
      }

      const created = await response.json();
      ticketId = created.id;
      ticketMessage.textContent = 'Request submitted.';
    }

    if (images.length > 0) {
      await uploadTicketImages(ticketId, images);
    }

    resetTicketForm();
    await refreshTicketLists();
  } catch (error) {
    ticketMessage.textContent = error.message;
  }
}

async function uploadTicketImages(ticketId, files) {
  const maxSize = 15 * 1024 * 1024;

  for (const file of files) {
    if (file.size > maxSize) {
      throw new Error('Each image must not exceed 15 MB.');
    }

    const formData = new FormData();
    formData.append('image', file);

    const response = await fetch(`${API_BASE_URL}/tickets/${ticketId}/images`, {
      method: 'POST',
      credentials: 'include',
      body: formData
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `Image upload failed (${response.status}).`);
    }
  }
}

async function confirmCancelTicket(ticketId) {
  if (!window.confirm('Are you sure you want to cancel this maintenance request?')) {
    return;
  }

  try {
    const response = await fetch(`${API_BASE_URL}/tickets/${ticketId}/cancel`, {
      method: 'POST',
      credentials: 'include'
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `Cancellation failed (${response.status}).`);
    }

    await refreshTicketLists();
  } catch (error) {
    alert(error.message);
  }
}

function formatStatus(status) {
  const labels = {
    SUBMITTED: 'Submitted',
    ASSIGNED: 'Assigned',
    IN_PROGRESS: 'In Progress',
    COMPLETED: 'Completed',
    CLOSED: 'Closed',
    REOPENED: 'Reopened',
    CANCELLED: 'Cancelled'
  };
  return labels[status] ?? status;
}

function formatCategory(category) {
  const labels = {
    ELECTRICAL: 'Electrical',
    PLUMBING: 'Plumbing',
    HVAC: 'Heating / Air Conditioning',
    CARPENTRY: 'Carpentry',
    CLEANING: 'Cleaning',
    IT: 'IT',
    GROUNDS: 'Grounds',
    OTHER: 'Other'
  };
  return labels[category] ?? category;
}

function formatUrgency(urgency) {
  const labels = {
    LOW: 'Low',
    MEDIUM: 'Medium',
    HIGH: 'High',
    EMERGENCY: 'Emergency'
  };
  return labels[urgency] ?? urgency;
}

function formatPriority(priority) {
  const labels = {
    LOW: 'Low',
    MEDIUM: 'Medium',
    HIGH: 'High',
    CRITICAL: 'Critical'
  };
  return labels[priority] ?? 'Not set';
}

function formatDate(value) {
  if (!value) {
    return '';
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
}

function escapeHtml(value) {
  if (value === null || value === undefined) {
    return '';
  }

  const entities = {
    '&': '&' + 'amp;',
    '<': '&' + 'lt;',
    '>': '&' + 'gt;',
    '"': '&' + 'quot;',
    "'": '&' + '#039;'
  };

  return String(value).replace(/[&<>"']/g, (char) => entities[char]);
}

if (profileForm) {
  loadProfile();
  loadAvatar().catch((error) => {
    authMessage.textContent = error.message;
  });

  profileForm.addEventListener('submit', async (event) => {
    event.preventDefault();

    const profileData = {
      name: document.getElementById('profile-name').value,
      contactInformation: document.getElementById('contact-information').value,
      profileDetails: document.getElementById('profile-details').value
    };

    authMessage.textContent = 'Updating profile...';

    try {
      const response = await fetch(`${API_BASE_URL}/users/me`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify(profileData)
      });

      if (response.status === 401) {
        window.location.href = './index.html';
        return;
      }

      if (!response.ok) {
        throw new Error(`Profile update failed (${response.status}).`);
      }

      const user = await response.json();
      displayProfile(user);
      authMessage.textContent = 'Profile updated.';
    } catch (error) {
      authMessage.textContent = error.message;
    }
  });
}

if (avatarForm) {
  avatarForm.addEventListener('submit', async (event) => {
    event.preventDefault();

    const avatarFile = document.getElementById('avatar-file').files[0];
    if (!avatarFile) {
      authMessage.textContent = 'Please select an image.';
      return;
    }

    if (avatarFile.size > 15 * 1024 * 1024) {
      authMessage.textContent = 'Image must not exceed 15 MB.';
      return;
    }

    const avatarData = new FormData();
    avatarData.append('avatar', avatarFile);
    authMessage.textContent = 'Uploading picture...';

    try {
      const response = await fetch(`${API_BASE_URL}/users/me/avatar`, {
        method: 'POST',
        credentials: 'include',
        body: avatarData
      });

      if (response.status === 401) {
        window.location.href = './index.html';
        return;
      }

      if (!response.ok) {
        throw new Error(`Picture upload failed (${response.status}).`);
      }

      await loadAvatar();
      avatarForm.reset();
      authMessage.textContent = 'Profile picture uploaded.';
    } catch (error) {
      authMessage.textContent = error.message;
    }
  });
}

async function loadProfile() {
  authMessage.textContent = 'Loading profile...';

  try {
    const response = await fetch(`${API_BASE_URL}/users/me`, {
      credentials: 'include'
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (!response.ok) {
      throw new Error(`Profile loading failed (${response.status}).`);
    }

    const user = await response.json();
    displayProfile(user);
    authMessage.textContent = '';
  } catch (error) {
    authMessage.textContent = error.message;
  }
}

function displayProfile(user) {
  document.getElementById('profile-id').textContent = user.id;
  document.getElementById('profile-email').textContent = user.email;
  document.getElementById('profile-identity-type').textContent = formatIdentityType(user.identityType);
  document.getElementById('profile-role').textContent = rolesFor(user).map(formatRole).join(', ');
  document.getElementById('profile-name').value = user.name;
  document.getElementById('contact-information').value = user.contactInformation ?? '';
  document.getElementById('profile-details').value = user.profileDetails ?? '';
}

function formatRole(role) {
  const labels = {
    GENERAL_USER: 'Requester',
    MAINTENANCE_STAFF: 'Maintenance Staff',
    ADMINISTRATOR: 'Administrator',
    MANAGER: 'Manager'
  };
  return labels[role] ?? role ?? '';
}

function formatIdentityType(identityType) {
  const labels = {
    STUDENT: 'Student',
    CAMPUS_STAFF: 'Campus Staff'
  };
  return labels[identityType] ?? identityType ?? '';
}

if (adminPage) {
  initAdminPage();
}

async function initAdminPage() {
  const user = await loadCurrentUser();
  if (!user) {
    window.location.href = './index.html';
    return;
  }

  if (!hasRole(user, 'ADMINISTRATOR')) {
    window.location.href = getLandingPage(user.role);
    return;
  }

  await loadAdminUsers();
}

async function loadAdminUsers() {
  adminMessage.textContent = 'Loading users...';

  try {
    const response = await fetch(`${API_BASE_URL}/admin/users`, {
      credentials: 'include'
    });

    if (response.status === 401) {
      window.location.href = './index.html';
      return;
    }

    if (response.status === 403) {
      window.location.href = './workspace.html';
      return;
    }

    if (!response.ok) {
      throw new Error(`Loading users failed (${response.status}).`);
    }

    renderAdminUsers(await response.json());
    adminMessage.textContent = '';
  } catch (error) {
    adminMessage.textContent = error.message;
  }
}

function renderAdminUsers(users) {
  adminUsersBody.innerHTML = '';

  users.forEach((user) => {
    const row = document.createElement('tr');
    row.innerHTML = `
      <td>${user.id}</td>
      <td>${escapeHtml(user.name)}</td>
      <td>${escapeHtml(user.email)}</td>
      <td>
        <select class="admin-identity-select" aria-label="Identity for ${escapeHtml(user.name)}">
          ${createIdentityOptions(user.identityType)}
        </select>
      </td>
      <td>
        <fieldset class="admin-role-options">
          <legend>Roles for ${escapeHtml(user.name)}</legend>
          ${createRoleOptions(rolesFor(user))}
        </fieldset>
      </td>
      <td><button type="button" class="admin-save-role">Save</button></td>
    `;

    row.querySelector('.admin-save-role').addEventListener('click', async () => {
      const identityType = row.querySelector('.admin-identity-select').value;
      const roles = Array.from(row.querySelectorAll('.admin-role-checkbox:checked'), (input) => input.value);
      if (roles.length === 0) {
        adminMessage.textContent = 'Select at least one role before saving.';
        return;
      }
      const button = row.querySelector('.admin-save-role');
      button.disabled = true;
      try {
        await updateAdminUserAccess(user.id, identityType, roles);
      } finally {
        button.disabled = false;
      }
    });

    adminUsersBody.appendChild(row);
  });
}

function createIdentityOptions(selectedIdentityType) {
  const identityTypes = ['STUDENT', 'CAMPUS_STAFF'];
  return identityTypes.map((identityType) => `
    <option value="${identityType}" ${identityType === selectedIdentityType ? 'selected' : ''}>${formatIdentityType(identityType)}</option>
  `).join('');
}

function createRoleOptions(selectedRoles) {
  const roles = ['GENERAL_USER', 'MAINTENANCE_STAFF', 'ADMINISTRATOR', 'MANAGER'];
  return roles.map((role) => `
    <label><input class="admin-role-checkbox" type="checkbox" value="${role}"
      ${selectedRoles.includes(role) ? 'checked' : ''}> ${formatRole(role)}</label>
  `).join('');
}

async function updateAdminUserAccess(userId, identityType, roles) {
  adminMessage.textContent = 'Updating identity and roles...';

  try {
    const response = await fetch(`${API_BASE_URL}/admin/users/${userId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ identityType, roles })
    });

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `User access update failed (${response.status}).`);
    }

    await loadAdminUsers();
    adminMessage.textContent = 'Identity and roles updated.';
  } catch (error) {
    await loadAdminUsers();
    adminMessage.textContent = error.message;
  }
}

async function loadAvatar() {
  const response = await fetch(`${API_BASE_URL}/users/me/avatar?v=${Date.now()}`, {
    credentials: 'include'
  });

  if (response.status === 401) {
    window.location.href = './index.html';
    return;
  }

  if (response.status === 404) {
    profileAvatar.hidden = true;
    return;
  }

  if (!response.ok) {
    throw new Error(`Picture loading failed (${response.status}).`);
  }

  const avatarBlob = await response.blob();
  const previousAvatarUrl = profileAvatar.dataset.objectUrl;
  if (previousAvatarUrl) {
    URL.revokeObjectURL(previousAvatarUrl);
  }

  const avatarUrl = URL.createObjectURL(avatarBlob);
  profileAvatar.dataset.objectUrl = avatarUrl;
  profileAvatar.src = avatarUrl;
  profileAvatar.hidden = false;
}

if (logoutButton) {
  logoutButton.addEventListener('click', async () => {
    authMessage.textContent = 'Logging out...';

    try {
      const response = await fetch(`${API_BASE_URL}/auth/logout`, {
        method: 'POST',
        credentials: 'include'
      });

      if (!response.ok) {
        throw new Error(`Logout failed (${response.status}).`);
      }

      window.location.href = './index.html';
    } catch (error) {
      authMessage.textContent = error.message;
    }
  });
}
