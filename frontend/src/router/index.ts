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
            component:Dashboard
        },
        {
            path:'/analytics',
            name: 'analytics',
            component: () => import('../views/Analytics.vue')
        }
    ]

});
