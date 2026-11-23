/// <reference types="vite/client" />

declare module "*.vue" {
    import type { DefineComponent } from "vue";

    const component: DefineComponent<{}, {}, any>;

    export default component;
}
interface ImportMetaEnv {
  readonly VITE_AUTH_MODE?: string
  readonly VITE_OIDC_ISSUER_URI: string
  readonly VITE_OIDC_CLIENT_ID: string
  readonly VITE_OIDC_REALM: string
  readonly VITE_OIDC_REDIRECT_URI: string
}
