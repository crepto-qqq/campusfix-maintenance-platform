# CampusFix Brevo Email Setup Guide

This guide explains how every CampusFix team member can run real registration
email verification on their own computer. CampusFix sends verification codes
from the Spring Boot back-end through Brevo's Transactional Email REST API.
The API key is never sent to the browser.

## 1. Shared and local responsibilities

The team uses one verified CampusFix sender and one shared Brevo API key.
The key is account-based, so it works on Windows, macOS, and Linux. However,
when Brevo IP restrictions are enabled, every network that calls the API must
also be authorized.

The Brevo account owner is responsible for:

1. Keeping the CampusFix sender verified.
2. Creating, revoking, and replacing the shared API key.
3. Adding each team member's current public IP address to Brevo's authorized
   IP list.
4. Deactivating the shared key no later than 7 November 2026.

Each team member is responsible for:

1. Receiving the API key and verified sender address through a trusted private
   channel.
2. Saving them only in `backend/.env.local` on their own computer.
3. Telling the account owner when their public IP changes or the API returns
   an authorization error.
4. Never committing, emailing, screenshotting, or pasting the key into Jira,
   chat, source code, or the front-end.

## 2. Brevo account owner setup

These steps are performed once for the shared Brevo account.

### 2.1 Verify the sender

1. Sign in to Brevo.
2. Open **Settings > Senders, Domains & Dedicated IPs > Senders**.
3. Add the dedicated CampusFix mailbox with the display name `CampusFix`.
4. Enter the verification code delivered to that mailbox.
5. Confirm that Brevo shows the sender as **Verified**.

Every member must configure the exact same verified sender address. Do not use
an unverified personal address as the sender.

### 2.2 Create the shared API key

1. Open **Settings > SMTP & API > API Keys & MCP**.
2. Generate a normal API key named `CampusFix shared demo`.
3. Do not create an MCP key for this application.
4. Copy the key when Brevo displays it; Brevo will not show the complete key
   again.
5. Share it only through the team's agreed private channel.

Do not add the real key to `.env.example`, `application.properties`, README
examples, screenshots, or Git history.

## 3. Authorize each team member's IP address

Brevo currently restricts API calls to approved public IP addresses. This rule
applies to each member even though everyone uses the same API key.

For each computer or network:

1. Ask the team member to run the setup or start the back-end and report that
   the Brevo key check returned `401 Unauthorized`, if applicable.
2. In the Brevo account, open **Settings > Security > Authorized IPs**. The
   direct page is `https://app.brevo.com/security/authorised_ips`.
3. Add the member's current public IP address to the authorized list.
4. Confirm that the address appears under **Authorized IP addresses** rather
   than **Unauthorized IP addresses**.
5. Ask the member to run the key check or send a verification email again.

A public IP belongs to the current internet connection, not permanently to a
computer. Home routers, mobile hotspots, university Wi-Fi, and VPNs may use
different or changing addresses. If a previously working setup starts returning
`401 Unauthorized`, check the IP list before replacing the API key.

Keep IP restrictions enabled for the shared key. Only the Brevo account owner
should change this security setting.

## 4. Configure a team member's computer

Run all commands from the repository root.

### 4.1 Windows PowerShell

Git Bash is not required. Run the guided setup:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-brevo-email.ps1
```

The wizard requests the verified sender, shared API key, and local administrator
password. Secret input is not displayed. It writes the result to the ignored
file `backend/.env.local` and checks the key through Brevo's account API.

### 4.2 macOS or Linux

Run the Bash setup wizard:

```bash
bash scripts/setup-brevo-email.sh
```

The wizard performs the same local configuration and API-key check.

### 4.3 Manual setup

If the wizard cannot be used, create the local file from the template.

Windows:

```powershell
Copy-Item .env.example backend/.env.local
```

macOS or Linux:

```bash
cp .env.example backend/.env.local
```

Edit `backend/.env.local` and replace the example values:

```properties
CAMPUSFIX_ADMIN_PASSWORD=choose-a-strong-local-password
CAMPUSFIX_BREVO_API_KEY=xkeysib-paste-the-private-key-here
CAMPUSFIX_EMAIL_FROM_NAME=CampusFix
CAMPUSFIX_EMAIL_FROM_ADDRESS=the-verified-sender@example.com
CAMPUSFIX_EMAIL_VERIFICATION_REQUIRED=true
```

The administrator password may be different on each computer. The Brevo key,
sender name, and sender address must match the team's shared configuration.

Verify that Git ignores the local file:

```powershell
git status --short --ignored backend/.env.local
```

The expected output begins with `!!`. Never use `git add -f` on this file.

## 5. Start CampusFix

CampusFix requires Java 21.

Start the back-end in one terminal:

```powershell
cd backend
.\gradlew.bat bootRun
```

On macOS or Linux, use `./gradlew bootRun`. Keep the process running at
`http://localhost:8080`.

Start the front-end from the repository root in another terminal.

Windows:

```powershell
py -m http.server 5500 --directory frontend
```

macOS or Linux:

```bash
python3 -m http.server 5500 --directory frontend
```

Open `http://localhost:5500/register.html`.

Restart the back-end after changing `backend/.env.local`; configuration changes
are not loaded into an already running process.

## 6. Verify real email delivery

1. Open the CampusFix registration page.
2. Enter a real mailbox that you can access.
3. Select **Send code**.
4. Confirm that the mailbox receives an email from `CampusFix` with the subject
   `Your CampusFix verification code`.
5. Enter the six-digit code and finish registration within 10 minutes.

The API enforces a 60-second resend cooldown. A verification code is locked
after five failed attempts. Check the Spam or Junk folder before requesting
another code.

For a final demonstration, test at least two different real recipient providers
when possible and capture evidence without showing the API key or a reusable
verification code.

## 7. Common problems

### `Email delivery is not configured`

Cause: `backend/.env.local` is missing, contains placeholder values, or the
back-end was started before the file was updated.

Fix:

1. Confirm the file is located at `backend/.env.local`.
2. Confirm all `CAMPUSFIX_BREVO_*` values are present.
3. Stop and restart the back-end.

### `401 Unauthorized` from Brevo

Likely causes:

- The current public IP is not in Brevo's authorized list.
- The API key was copied incorrectly.
- The key has expired or was revoked.

Check the authorized IP list first. Then ask the account owner to validate the
key from **SMTP & API > API Keys & MCP**. Do not post the key in a support ticket
or group chat.

### Brevo rejects the sender

Cause: `CAMPUSFIX_EMAIL_FROM_ADDRESS` does not exactly match a verified Brevo
sender, or the sender verification is no longer valid.

Fix: Copy the verified address from Brevo's **Senders** page, update the local
file, and restart the back-end.

### CampusFix returns `503 Service Unavailable`

The back-end could not complete delivery. Review the back-end log for the HTTP
status, then check the API key, sender verification, IP authorization, and Brevo
service status. CampusFix deliberately does not leave an active registration
challenge when delivery fails.

### No email arrives even though the request succeeded

1. Check Spam, Junk, Promotions, and mailbox rules.
2. Confirm the registration address has no typing error.
3. Check Brevo's transactional email logs for delivery, deferral, or rejection.
4. Wait for the 60-second cooldown before requesting another code.

### The key works for one member but not another

The key is not tied to an operating system or computer. This usually indicates
that the second member is using a different public IP that has not been
authorized, or their local file contains a copying error.

### Port 8080 or 5500 is already in use

Stop the existing CampusFix/HTTP-server process before starting another one.
All team members only need one back-end and one front-end server on their own
computer; multiple browser windows can share those processes.

## 8. Key rotation and incident response

If the key is exposed or committed:

1. Revoke it immediately in Brevo.
2. Generate a replacement normal API key.
3. Privately distribute the replacement to team members.
4. Update `backend/.env.local` on every computer and restart each back-end.
5. Review Brevo activity and transactional logs for unexpected use.
6. Remove the exposed value from any screenshot, ticket, or message. Removing
   it from only the latest Git commit is not sufficient if it entered history.

After the assessment, the account owner must deactivate the shared demo key and
remove IP entries that are no longer required.
