import axios from "axios";

//export default axios.create({
//    baseURL: import.meta.env.VITE_API_URL
//});

export default axios.create({

    baseURL:
        import.meta.env.VITE_API_URL ??
        `${window.location.protocol}//${window.location.hostname}:8080/api`,

    timeout: 5000

});
