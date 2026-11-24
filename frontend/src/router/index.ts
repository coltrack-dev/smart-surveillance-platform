import {
    createRouter,
    createWebHistory
} from 'vue-router';


import Dashboard from '../views/Dashboard.vue';


export default createRouter({

    history:createWebHistory(),

    routes:[
        {
            path:'/',
            component:Dashboard,
            meta: { requiresAuth: true }
        },
        {
            path:'/analytics',
            name: 'analytics',
            component: () => import('../views/Analytics.vue'),
            meta: { requiresAuth: true }
        },
        {
            path: '/watch/:publicId',
            name: 'broadcast-watch',
            component: () => import('../views/BroadcastPlayer.vue')
        },
        {
            path: '/app/:section?',
            name: 'streaming-app',
            component: () => import('../views/StreamingApp.vue'),
            meta: { requiresAuth: true, streamingOnly: true }
        },
        {
            path: '/invite',
            name: 'invitation-acceptance',
            component: () => import('../views/StreamingApp.vue'),
            meta: { requiresAuth: true, streamingOnly: true }
        }
    ]

});
