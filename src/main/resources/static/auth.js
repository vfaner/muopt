/**
 * 登录态辅助（所有带侧边栏的页面共享，用原生 fetch 避免依赖 axios）：
 * 1. 侧边栏「退出登录」：POST /api/auth/logout 后跳登录页；
 * 2. 拉取当前用户，填充侧边栏 [data-user-nickname] / [data-user-account]。
 */
(function () {
    function doLogout(e) {
        e.preventDefault();
        fetch('/api/auth/logout', { method: 'POST' })
            .catch(function () {})
            .finally(function () {
                window.location.href = '/login.html';
            });
    }

    function loadUser() {
        fetch('/api/auth/me')
            .then(function (r) { return r.json(); })
            .then(function (resp) {
                if (!resp || resp.code !== 200 || !resp.data) return;
                var u = resp.data;
                document.querySelectorAll('[data-user-nickname]').forEach(function (el) {
                    el.textContent = u.nickname;
                });
                document.querySelectorAll('[data-user-account]').forEach(function (el) {
                    el.textContent = u.username;
                });
            })
            .catch(function () {});
    }

    document.addEventListener('DOMContentLoaded', function () {
        document.querySelectorAll('.js-logout').forEach(function (el) {
            el.addEventListener('click', doLogout);
        });
        loadUser();
    });
})();