import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { requireLogin } from './auth/oidc'

import './assets/main.css'


router.beforeEach(async (to) => {
    if (import.meta.env.VITE_AUTH_MODE === 'oidc' && to.path === '/invite' && window.location.hash.startsWith('#token=')) {
        sessionStorage.setItem('streaming.pendingInvitationToken', decodeURIComponent(window.location.hash.slice('#token='.length)))
        window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}`)
    }
    if (import.meta.env.VITE_AUTH_MODE === 'oidc' && (to.path === '/' || to.path === '/analytics')) {
        await requireLogin()
        const pendingPath = sessionStorage.getItem('streaming.postLoginPath')
        if (pendingPath) {
            sessionStorage.removeItem('streaming.postLoginPath')
            return pendingPath
        }
        if (sessionStorage.getItem('streaming.pendingInvitationToken')) return '/invite'
        return '/app'
    }
    if (to.meta.streamingOnly && import.meta.env.VITE_AUTH_MODE !== 'oidc') return '/'
    if (to.meta.requiresAuth !== true) return true
    return requireLogin()
})

createApp(App)
    .use(createPinia())
    .use(router)
    .mount('#app')
