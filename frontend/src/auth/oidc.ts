import Keycloak from 'keycloak-js'

const oidcEnabled = import.meta.env.VITE_AUTH_MODE === 'oidc'

const keycloak = oidcEnabled
  ? new Keycloak({
      url: import.meta.env.VITE_OIDC_ISSUER_URI.replace(/\/realms\/[^/]+\/?$/, ''),
      realm: import.meta.env.VITE_OIDC_REALM,
      clientId: import.meta.env.VITE_OIDC_CLIENT_ID
    })
  : null

let initialization: Promise<boolean> | null = null

function authenticationRedirectUri(): string {
  // The public watch route may need to complete OIDC check-sso after an
  // anonymous playback grant is denied. Keep that callback on the watch page
  // so the player can retry with the authenticated viewer token.
  if (window.location.pathname.startsWith('/watch/')) {
    return `${window.location.origin}${window.location.pathname}${window.location.search}`
  }
  return import.meta.env.VITE_OIDC_REDIRECT_URI || `${window.location.origin}/`
}

export function isOidcEnabled(): boolean {
  return oidcEnabled
}

export function getCurrentIdentity() {
  if (!keycloak || !keycloak.authenticated) return null
  return {
    username: keycloak.tokenParsed?.preferred_username ?? keycloak.tokenParsed?.name ?? 'Account',
    email: keycloak.tokenParsed?.email ?? '',
    emailVerified: keycloak.tokenParsed?.email_verified === true
  }
}

export function initializeAuthentication(): Promise<boolean> {
  if (!keycloak) return Promise.resolve(false)
  if (!initialization) {
    initialization = keycloak.init({
      onLoad: 'check-sso',
      pkceMethod: 'S256',
      checkLoginIframe: false,
      redirectUri: authenticationRedirectUri()
    })
  }
  return initialization
}

async function beginLogin(): Promise<void> {
  if (!keycloak) return
  const postLoginPath = `${window.location.pathname}${window.location.search}`
  if (window.location.pathname !== '/') sessionStorage.setItem('streaming.postLoginPath', postLoginPath)
  const callbackUri = import.meta.env.VITE_OIDC_REDIRECT_URI || `${window.location.origin}/`
  await keycloak.login({ redirectUri: callbackUri })
}

export async function requireLogin(): Promise<boolean> {
  if (!keycloak) return true
  const authenticated = await initializeAuthentication()
  if (authenticated) return true
  await beginLogin()
  return false
}

export async function getAccessToken(): Promise<string | null> {
  if (!keycloak || !(await initializeAuthentication()) || !keycloak.token) return null
  try {
    await keycloak.updateToken(30)
    return keycloak.token ?? null
  } catch {
    await beginLogin()
    return null
  }
}

export async function logout(): Promise<void> {
  if (!keycloak) return
  await keycloak.logout({ redirectUri: window.location.origin })
}
