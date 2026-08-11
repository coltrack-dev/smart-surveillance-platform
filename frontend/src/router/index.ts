import {
    createRouter,
    createWebHistory
} from 'vue-router';


import Dashboard from '@/views/Dashboard.vue';
import Analytics from "@/views/Analytics.vue";


export default createRouter({

    history:createWebHistory(),

    routes:[
        {
            path:'/',
            component:Dashboard
        },
        {
            path:'/analytics',
            component:Analytics
        }
    ]

});
