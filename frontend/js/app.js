// Use the same hostname as the page so the session cookie (JSESSIONID) is
// always sent to the backend. This avoids the "login bounces back" issue that
// happens when the frontend is opened on 127.0.0.1 while the backend is on
// localhost (or vice versa): the two hostnames do not share cookies.
const API_BASE_URL = `http://${window.location.hostname}:8080/api`;

const registrationForm = document.getElementById('registration-form');
const registrationMessage = document.getElementById('registration-message');
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
const profileForm = document.getElementById('profile-form');
const avatarForm = document.getElementById('avatar-form');
const profileAvatar = document.getElementById('profile-avatar');
const adminPage = document.getElementById('admin-page');
const adminUsersBody = document.getElementById('admin-users-body');
const adminMessage = document.getElementById('admin-message');

if (registrationForm) {
  registrationForm.addEventListener('submit', async (event) => {
    event.preventDefault();

    const registrationData = {
      name: document.getElementById('name').value,
      email: document.getElementById('email').value,
      password: document.getElementById('password').value
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

        throw new Error(`Registration failed (${response.status}).`);
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

  displayWorkspaceRole(user);

  if (user.role === 'GENERAL_USER') {
    loadTickets();
    return;
  }

  document.querySelectorAll('.requester-content').forEach((element) => {
    element.hidden = true;
  });

  const roleMessage = document.getElementById('role-workspace-message');
  const roleTitle = document.getElementById('role-workspace-title');
  const roleDescription = document.getElementById('role-workspace-description');
  roleMessage.hidden = false;
  roleTitle.textContent = `${formatRole(user.role)} Workspace`;
  const descriptions = {
    MAINTENANCE_STAFF: 'Your account has Maintenance Staff access. Assigned-ticket tools will appear here when the assignment workflow is delivered.',
    ADMINISTRATOR: 'Use Manage Users to maintain university identities and operational roles.',
    MANAGER: 'Your account has Manager access. Management reporting tools will appear here when the reporting workflow is delivered.'
  };
  roleDescription.textContent = descriptions[user.role] ?? '';
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
  if (!workspaceRole || !user.role) {
    return;
  }

  const labels = {
    GENERAL_USER: 'Requester',
    MAINTENANCE_STAFF: 'Maintenance Staff',
    ADMINISTRATOR: 'Administrator',
    MANAGER: 'Manager'
  };

  workspaceRole.textContent = labels[user.role] ?? user.role;
  workspaceRole.classList.add(`role-${user.role.toLowerCase()}`);
  workspaceRole.hidden = false;

  const adminLink = document.getElementById('admin-link');
  if (adminLink) {
    adminLink.hidden = user.role !== 'ADMINISTRATOR';
  }
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
    renderTickets(tickets);
  } catch (error) {
    ticketsMessage.textContent = error.message;
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

function renderTicket(ticket) {
  const article = document.createElement('article');
  article.className = 'ticket-card';
  article.dataset.ticketId = ticket.id;

  const canEdit = ticket.status === 'PENDING';
  const canCancel = ticket.status === 'PENDING';

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
      <span class="urgency-${ticket.urgency.toLowerCase()}">${formatUrgency(ticket.urgency)}</span>
    </p>
    <p class="ticket-description">${escapeHtml(ticket.description)}</p>
    <p class="ticket-dates">Submitted: ${formatDate(ticket.createdAt)}</p>
    <div class="ticket-images" data-ticket-id="${ticket.id}"></div>
    <div class="ticket-card-actions">
      ${canEdit ? `<button type="button" class="ticket-edit-button" data-ticket-id="${ticket.id}">Edit</button>` : ''}
      ${canCancel ? `<button type="button" class="ticket-cancel-button secondary-danger" data-ticket-id="${ticket.id}">Cancel Request</button>` : ''}
    </div>
  `;

  article.querySelectorAll('.ticket-edit-button').forEach((button) => {
    button.addEventListener('click', () => startEditing(ticket));
  });

  article.querySelectorAll('.ticket-cancel-button').forEach((button) => {
    button.addEventListener('click', () => confirmCancelTicket(ticket.id));
  });

  loadTicketImages(ticket.id, ticket.imageIds, article.querySelector('.ticket-images'));

  return article;
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
    await loadTickets();
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

    await loadTickets();
  } catch (error) {
    alert(error.message);
  }
}

function formatStatus(status) {
  const labels = {
    PENDING: 'Pending',
    ASSIGNED: 'Assigned',
    IN_PROGRESS: 'In Progress',
    COMPLETED: 'Completed',
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
  document.getElementById('profile-role').textContent = formatRole(user.role);
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

  if (user.role !== 'ADMINISTRATOR') {
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
        <select class="admin-role-select" aria-label="Role for ${escapeHtml(user.name)}">
          ${createRoleOptions(user.role)}
        </select>
      </td>
      <td><button type="button" class="admin-save-role">Save</button></td>
    `;

    row.querySelector('.admin-save-role').addEventListener('click', async () => {
      const identityType = row.querySelector('.admin-identity-select').value;
      const role = row.querySelector('.admin-role-select').value;
      await updateAdminUserAccess(user.id, identityType, role);
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

function createRoleOptions(selectedRole) {
  const roles = ['GENERAL_USER', 'MAINTENANCE_STAFF', 'ADMINISTRATOR', 'MANAGER'];
  return roles.map((role) => `
    <option value="${role}" ${role === selectedRole ? 'selected' : ''}>${formatRole(role)}</option>
  `).join('');
}

async function updateAdminUserAccess(userId, identityType, role) {
  adminMessage.textContent = 'Updating identity and role...';

  try {
    const response = await fetch(`${API_BASE_URL}/admin/users/${userId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify({ identityType, role })
    });

    if (!response.ok) {
      const data = await response.json().catch(() => null);
      throw new Error(data?.message ?? `User access update failed (${response.status}).`);
    }

    adminMessage.textContent = 'Identity and role updated.';
    await loadAdminUsers();
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
