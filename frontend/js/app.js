const API_BASE_URL = 'http://localhost:8080/api';

const registrationForm = document.getElementById('registration-form');
const registrationMessage = document.getElementById('registration-message');
const loginForm = document.getElementById('login-form');
const logoutButton = document.getElementById('logout-button');
const authMessage = document.getElementById('auth-message');

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
      authMessage.textContent = `Logged in as ${user.name}.`;
      logoutButton.hidden = false;
      loginForm.reset();
    } catch (error) {
      authMessage.textContent = error.message;
    }
  });
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

      authMessage.textContent = 'Logged out.';
      logoutButton.hidden = true;
    } catch (error) {
      authMessage.textContent = error.message;
    }
  });
}
