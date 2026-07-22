# Sending mail through smtp.office365.com — settings and troubleshooting

How to make MemberRoll's outbound mail work through Microsoft 365 /
Exchange Online, and how to diagnose it when it doesn't. Written up from
a real onboarding (July 2026) that hit most of the failure modes below.

MemberRoll's sender speaks **plain SMTP with basic (password) AUTH** —
`smtp.office365.com:587`, STARTTLS (TLS 1.2/1.3, negotiated by the JVM;
nothing older is offered). The admin **Mail settings** page is where the
relay is configured, and its **Send test email** button shows the
relay's own reply verbatim — that reply is your primary diagnostic
throughout.

> **Shelf life.** Microsoft disables basic SMTP AUTH **by default at the
> end of December 2026** (existing tenants; an admin can re-enable it),
> with permanent removal to be announced for H2 2027 or later. If mail
> stops in January 2027, re-check setting 1 below first. The long-term
> options are OAuth (XOAUTH2 — a recorded CR-014 follow-up) or a plain
> transactional relay (Postmark, SES, Brevo…), which drops into the same
> Mail settings page with no code change.

## The Microsoft settings that must all be right

Every one of these can independently cause an authentication failure.
They are listed in the order worth checking.

### 1. Tenant-wide SMTP AUTH switch

Exchange admin center → **Settings → Mail flow** → the checkbox
**"Turn off SMTP AUTH protocol for your organization"** must be
**unchecked**. PowerShell equivalent:

```powershell
Get-TransportConfig | fl SmtpClientAuthenticationDisabled   # want: False
Set-TransportConfig -SmtpClientAuthenticationDisabled $false
```

### 2. Per-mailbox "Authenticated SMTP"

Microsoft 365 admin center → **Users → Active users → (the sending
mailbox) → Mail tab → Manage email apps** → **"Authenticated SMTP"**
ticked. An explicit mailbox-level *off* overrides the tenant-level *on*.
PowerShell (the portal checkbox occasionally lies — trust this):

```powershell
Get-CASMailbox <mailbox> | fl SmtpClientAuthenticationDisabled
# True  = explicitly disabled at mailbox level (the blocker)
# False = explicitly enabled
# blank = inherits the tenant setting
Set-CASMailbox <mailbox> -SmtpClientAuthenticationDisabled $false
```

Changes to 1 and 2 propagate in minutes, occasionally up to an hour.

### 3. Entra security defaults

Basic SMTP auth is a "legacy" protocol; **security defaults block it
outright regardless of settings 1 and 2**, with the same generic error.
Entra admin center (entra.microsoft.com) → **Identity → Overview →
Properties → Manage security defaults**. Turning it off is a tenant-wide
security decision: the least-bad pattern for a small organisation is to
turn it off, manually require MFA for the human/admin accounts, and
leave the sending account password-only with a long random password.

### 4. MFA on the sending account

Basic auth cannot answer an MFA prompt. If the sending account has
**per-user MFA**, its normal password will always be rejected over SMTP
— generate an **app password** (aka.ms/mfasetup → App passwords) and use
that on the Mail settings page. If the App-passwords menu doesn't exist,
the tenant is on security defaults (setting 3), where app passwords
aren't available and basic auth is blocked anyway.

Best practice: a dedicated sending account (`noreply@…` or a service
mailbox) with no MFA and a long random password used nowhere else.

### 5. Conditional Access (Entra ID P1 tenants only)

A Conditional Access policy can block legacy auth generally or by
location/IP — meaning a credential can work from a laptop and fail from
the server. The remedy is a policy exclusion for the sending account or
a named-location exception for the server's IP. Small tenants without
P1 licensing can skip this.

### 6. Username = the mailbox's email address

The Mail settings page's **login username must be the mailbox's full
email address** (the same string you'd sign in to portal.office.com
with) — **never a person's display name**. This one caused hours of
grief in the field: a name-shaped username gets the same generic
`535 5.7.3` as every other failure, and because Exchange can't match it
to any account, **nothing appears in the sign-in logs** — falsely
implicating settings 1–3. The page's Microsoft 365 preset now overwrites
an `@`-less username with the From address, and the test button refuses
one outright.

### 7. From address = the authenticated mailbox

Once auth succeeds, Exchange requires the **From** address to be the
authenticated mailbox itself, or one it holds **Send As** rights over.
Getting this wrong fails *later* than auth, with `5.7.60 SendAs denied`
— which is progress, not regression: it proves the login worked.

## Reading the errors

The test button shows the SMTP reply verbatim. The usual suspects:

| Reply | Meaning |
|---|---|
| `535 5.7.139 … SmtpClientAuthentication is disabled` | Setting 1 or 2 — Exchange names it outright. |
| `535 5.7.3 Authentication unsuccessful` | The generic one: wrong password, name-shaped username (setting 6), security defaults (3), or MFA (4). Use the sign-in log to tell them apart — see below. |
| `5.7.60 SendAs denied` | Auth succeeded; fix the From address (setting 7). |
| `Couldn't connect` / timeout / connection refused | Network-level — never Microsoft's answer. The app caps connect/read at 10 s. A reply containing an `…outlook.com` hostname proves the network path is fine. |

## The troubleshooting procedure

1. **Verify the password independently**: sign in at portal.office.com
   with the exact username/password. Note whether a second factor is
   prompted — if yes, setting 4 applies and the plain password can
   never work. (A working IMAP/Outlook client proves nothing: those use
   OAuth, not the password, since basic-auth IMAP died in 2022–23.)

2. **Beware the stored-password trap.** The page never echoes the saved
   password; a blank field means "keep the stored one", and the test
   button reuses it too. So a typo'd password saved once keeps failing
   through every later test even though the field looks empty.
   **Re-type the username and password before testing** (the Show
   button lets you eyeball what you typed), and Save with the password
   still in the field once the test passes.

3. **Read the Entra sign-in logs** — the step that names the blocker
   precisely. Entra admin center → **Monitoring & health → Sign-in
   logs**, check **both the interactive and non-interactive tabs**,
   filter around the test's timestamp for **Client app = "Authenticated
   SMTP"** (entries can lag a few minutes). Look tenant-wide, not just
   under one user. Then:
   - *"Blocked due to security defaults"* → setting 3.
   - Error **50126** (invalid credentials) → the password reaching
     Exchange is wrong → step 2's trap, or a paste artefact.
   - Error **50076** (MFA required) → setting 4.
   - Error **53003** (Conditional Access) → setting 5; compare where
     the attempt came from.
   - **No SMTP entry anywhere at all** → the attempt died inside
     Exchange before identity was consulted: settings 1/2 still
     propagating, or — the sneaky one — a username that matches no
     account (setting 6).

4. **Take the app out of the loop with curl** (same credentials, same
   mechanism — `AUTH LOGIN`):

   ```bash
   curl -v --ssl-reqd --url smtp://smtp.office365.com:587 \
     --user 'mailbox@example.org.au:PASSWORD' \
     --mail-from mailbox@example.org.au --mail-rcpt you@example.com \
     -T /dev/null
   ```

   Single-quote the `--user` argument (shells eat `$` and friends in
   double quotes). Success looks like `235 2.7.0 Authentication
   successful`. Run it **both** from a workstation **and from the
   server** — a split result (works at home, fails on the server) is
   the Conditional-Access-by-IP signature (setting 5).

   > **The trace discloses the credentials.** The two strings sent
   > after `AUTH LOGIN` are the username and password base64-encoded —
   > encoding, not encryption — and the password is also in your shell
   > history and briefly visible in the process list. After
   > troubleshooting with curl, rotate the mailbox password and re-save
   > it on the Mail settings page.

5. **Curl works but the page still fails with the password typed in?**
   That would be an application bug — at that point the tenant,
   network, and credentials are all proven. Capture what differs and
   raise it; the app sends stock jakarta-mail `AUTH LOGIN`, the same
   mechanism curl uses.

## Getting Exchange Online PowerShell

It's a module, not a website. Quickest path with nothing installed:
**Azure Cloud Shell** — https://shell.azure.com (or the `>_` icon in
portal.azure.com), sign in **as the tenant admin** (not the sending
mailbox), choose PowerShell (an "ephemeral session" is fine), then
`Connect-ExchangeOnline` — the module is preinstalled. Locally instead:
install PowerShell 7 (`pwsh` runs fine on Linux), then
`Install-Module ExchangeOnlineManagement -Scope CurrentUser` and
`Connect-ExchangeOnline` (browser login — works with MFA on the admin
account). `Disconnect-ExchangeOnline` when done.
