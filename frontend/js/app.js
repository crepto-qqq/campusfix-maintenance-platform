const API_BASE_URL = 'http://localhost:8080/api';

const registrationForm = document.getElementById('registration-form');
const registrationMessage = document.getElementById('registration-message');

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
