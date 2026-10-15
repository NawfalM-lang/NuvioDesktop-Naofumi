# Simkl AUTH V2 in the custom desktop build

New Simkl sign-ins use AUTH V2. A V2 client ID does not work with the old
`/oauth/pin` implementation, which produced a generic sign-in error.

## Configure and build

1. Register a public AUTH V2 app in Simkl's developer settings. Either **Mobile,
   desktop & browser apps** or **TV, devices & command line** supports the desktop
   PIN flow. Server apps that require a client secret are not supported.
2. Add the following to the existing root `local.properties`, preserving other
   entries:

   ```properties
   SIMKL_CLIENT_ID=your_public_v2_client_id
   SIMKL_APP_NAME=Nuvio Naofumi
   SIMKL_REDIRECT_URI=nuvio://auth/simkl
   ```

   Desktop sign-in does not use a redirect URI. For browser sign-in on mobile,
   register that exact redirect URI with Simkl.
3. Build and install the MSI as usual:

   ```powershell
   $webview2 = Join-Path $env:LOCALAPPDATA "NuvioBuildDeps\Microsoft.Web.WebView2.1.0.4078.44"
   .\gradlew.bat :composeApp:packageReleaseMsi `
     "-Pnuvio.webview2.dir=$webview2" `
     --no-configuration-cache --console=plain
   ```

4. In **Settings → Tracking → Simkl**, click **Connect**. Open the approval page,
   sign into the same Simkl account used by Watchly, and allow access. Nuvio polls
   for approval and then starts its existing Simkl sync.

`local.properties` is ignored by Git. The public client ID is compiled into the
application; users of the MSI do not need to register their own app. Never add a
client secret or an access/refresh token to the build configuration or repository.

## Behavior

- Requests `media:read media:write` and checks the granted scopes. A read-only
  token is rejected because it cannot update watch history.
- Uses the complete approval URL when supplied. The private `device_code` is
  stored separately from displayed metadata and never placed in a URL or log.
- Honors polling intervals, adds five seconds after `slow_down`, and stops at the
  code's expiry. Temporary network/server failures preserve pending approval.
- Stores the refresh token with the existing platform credential storage and
  keeps expiry metadata. Access tokens refresh before expiry or once after a 401.
  Network/server errors retain the grant; a revoked grant requires reconnecting.
- Pending authorization and refresh credentials are scoped to the local profile.
  Tokens are not added to account sync: each computer authorizes separately.
- Disconnect clears local credentials and attempts to revoke the captured grant.
- Existing legacy access tokens remain usable. New sign-ins require a public V2
  client ID. Changing the configured V2 client ID requires reconnecting.
- Shared browser authentication also uses the V2 endpoint, PKCE, form-encoded
  token exchange, and exact `iss=https://simkl.com` callback validation.

## Validation

Common Kotlin tests cover form encoding, scopes, device responses, refresh,
revocation, cancellation, and the single retry after a 401. Run them with:

```powershell
.\gradlew.bat :composeApp:desktopTest --tests "com.nuvio.app.features.simkl.*" --console=plain
```

Live consent and playback tracking still require an installed build and a real
Simkl account. No user credentials are included in these tests.

Protocol references:

- <https://api.simkl.org/api-reference/oauth2-device.md>
- <https://api.simkl.org/api-reference/oauth2-tokens.md>
- <https://api.simkl.org/api-reference/oauth2-authorization-code.md>
- <https://api.simkl.org/api-reference/oauth2-scopes.md>
