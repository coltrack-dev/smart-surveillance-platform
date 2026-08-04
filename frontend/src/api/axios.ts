import axios from "axios";

const apiUrl =
    import.meta.env.VITE_API_URL ??
    `${window.location.protocol}//${window.location.hostname}:8080/api`;

export default axios.create({

    baseURL: apiUrl,

    timeout: 5000

});
