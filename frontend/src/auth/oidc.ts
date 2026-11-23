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

export function isOidcEnabled(): boolean {
  return oidcEnabled
}

export function initializeAuthentication(): Promise<boolean> {
  if (!keycloak) return Promise.resolve(false)
  if (!initialization) {
    initialization = keycloak.init({
      onLoad: 'check-sso',
      pkceMethod: 'S256',
      checkLoginIframe: false,
      redirectUri: import.meta.env.VITE_OIDC_REDIRECT_URI
    })
  }
  return initialization
}

export async function requireLogin(): Promise<boolean> {
  if (!keycloak) return true
  const authenticated = await initializeAuthentication()
  if (authenticated) return true
  await keycloak.login({ redirectUri: window.location.href })
  return false
}

export async function getAccessToken(): Promise<string | null> {
  if (!keycloak || !(await initializeAuthentication()) || !keycloak.token) return null
  try {
    await keycloak.updateToken(30)
    return keycloak.token ?? null
  } catch {
    await keycloak.login({ redirectUri: window.location.href })
    return null
  }
}

export async function logout(): Promise<void> {
  if (!keycloak) return
  await keycloak.logout({ redirectUri: window.location.origin })
}
