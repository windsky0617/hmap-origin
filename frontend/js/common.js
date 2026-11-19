// let commonURL = "http://192.168.50.115:8081";
let commonURL = "/api";
// 设置后台服务地址
axios.defaults.baseURL = commonURL;
axios.defaults.timeout = 2000;
// request拦截器，将用户token放入头中
let token = sessionStorage.getItem("token");
axios.interceptors.request.use(
    config => {
      if(token) config.headers['authorization'] = token
      return config
    },
    error => {
      return Promise.reject(error)
    }
)

let loginRedirectPending = false;

function getResultErrorMessage(data, fallback) {
  if (
      data &&
      typeof data === "object" &&
      data.success === false &&
      typeof data.errorMsg === "string" &&
      data.errorMsg.length > 0
  ) {
    return data.errorMsg;
  }
  return fallback;
}

function getHttpErrorMessage(status) {
  switch (status) {
    case 400:
      return "请求参数错误";
    case 401:
      return "请先登录";
    case 403:
      return "没有访问权限";
    case 404:
      return "请求的资源不存在";
    case 405:
      return "请求方法不支持";
    case 413:
      return "上传内容过大";
    case 415:
      return "请求内容类型不支持";
    case 429:
      return "请求过于频繁，请稍后重试";
    default:
      return status >= 500 ? "服务器异常" : "请求失败";
  }
}

axios.interceptors.response.use(function (response) {
  const result = response.data;

  // 保留原有业务失败处理方式，页面 catch 继续收到字符串。
  if (!result || result.success !== true) {
    return Promise.reject(
        getResultErrorMessage(result, "服务器响应异常")
    );
  }

  return result;
}, function (error) {
  // 超时、断网等情况可能没有 error.response。
  if (!error || !error.response) {
    if (error && error.code === "ECONNABORTED") {
      return Promise.reject("请求超时，请稍后重试");
    }
    return Promise.reject("网络异常，请检查连接");
  }

  const response = error.response;
  const status = response.status;
  const message = getResultErrorMessage(
      response.data,
      getHttpErrorMessage(status)
  );

  if (status === 401 && !loginRedirectPending) {
    loginRedirectPending = true;
    setTimeout(() => {
      location.href = "/login.html";
    }, 200);
  }

  return Promise.reject(message);
});

axios.defaults.paramsSerializer = function(params) {
  const query = [];
  Object.keys(params).forEach(k => {
    const value = params[k];
    // 0 和 false 是有效参数，只跳过未提供的值和空字符串。
    if (value !== null && value !== undefined && value !== "") {
      query.push(encodeURIComponent(k) + "=" + encodeURIComponent(value));
    }
  });
  return query.join("&");
}
const util = {
  commonURL,
  getUrlParam(name) {
    let reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)", "i");
    let r = window.location.search.substr(1).match(reg);
    if (r != null) {
      return decodeURI(r[2]);
    }
    return "";
  },
  formatPrice(val) {
    if (val === null || val === undefined || val === "") {
      return null;
    }
    const cents = String(val);
    if (!/^-?\d+$/.test(cents)) {
      return null;
    }
    const negative = cents.startsWith("-");
    const digits = (negative ? cents.substring(1) : cents).padStart(3, "0");
    const amount = digits.substring(0, digits.length - 2)
        + "." + digits.substring(digits.length - 2);
    return negative ? "-" + amount : amount;
  }
}
