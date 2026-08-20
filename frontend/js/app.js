const API_BASE_URL = 'http://localhost:8080/api';

const registrationForm = document.getElementById('registration-form');
const registrationMessage = document.getElementById('registration-message');
const loginForm = document.getElementById('login-form');
const logoutButton = document.getElementById('logout-button');
const authMessage = document.getElementById('auth-message');
const workspacePage = document.getElementById('workspace-page');
const profileForm = document.getElementById('profile-form');
const avatarForm = document.getElementById('avatar-form');
const profileAvatar = document.getElementById('profile-avatar');

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
  document.getElementById('profile-name').value = user.name;
  document.getElementById('contact-information').value = user.contactInformation ?? '';
  document.getElementById('profile-details').value = user.profileDetails ?? '';
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
