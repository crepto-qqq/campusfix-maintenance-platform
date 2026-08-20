const API_BASE_URL = 'http://localhost:8080/api';

const registrationForm = document.getElementById('registration-form');
const registrationMessage = document.getElementById('registration-message');
const loginForm = document.getElementById('login-form');
const logoutButton = document.getElementById('logout-button');
const authMessage = document.getElementById('auth-message');
const workspacePage = document.getElementById('workspace-page');
const profileForm = document.getElementById('profile-form');

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

      window.location.href = './workspace.html';
    } catch (error) {
      authMessage.textContent = error.message;
    }
  });
}

if (workspacePage) {
  checkWorkspaceLogin();
}

async function checkWorkspaceLogin() {
  try {
    const response = await fetch(`${API_BASE_URL}/users/me`, {
      credentials: 'include'
    });

    if (!response.ok) {
      window.location.href = './index.html';
    }
  } catch (error) {
    window.location.href = './index.html';
  }
}

if (profileForm) {
  loadProfile();

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
  document.getElementById('profile-name').value = user.name;
  document.getElementById('contact-information').value = user.contactInformation ?? '';
  document.getElementById('profile-details').value = user.profileDetails ?? '';
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
