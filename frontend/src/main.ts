import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import { requireLogin } from './auth/oidc'

import './assets/main.css'


router.beforeEach(async (to) => {
    if (to.meta.requiresAuth !== true) return true
    return requireLogin()
})

createApp(App)
    .use(createPinia())
    .use(router)
    .mount('#app')
