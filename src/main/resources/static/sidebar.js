/**
 * 公共侧边栏：所有内页共享同一套渲染，菜单结构与版本号只在本文件维护。
 *
 * <p>历史上侧边栏 HTML 整段内联在 10 个页面里，版本号要改 10 处、极易漏改。
 * 现各页只保留 <aside id="sidebar" class="sidebar"></aside> 占位，由本文件在
 * DOMContentLoaded 时渲染；版本号构建期由 Maven filtering 注入 pom 的 version。
 *
 * <p>渲染必须早于 menu-groups.js（分组展开）与 auth.js（用户信息），页面引入顺序：
 * page-state → sidebar → menu-groups → auth。三者都监听 DOMContentLoaded，按注册顺序执行。
 */
(function () {
    // @...@ 是 spring-boot-starter-parent 约定的 filtering 占位符，构建时替换为 pom 版本
    var MUOPT_VERSION = '@project.version@';

    /** 路径 → 菜单项标识；当前路径对应的项加 active */
    var PAGES = {
        '/manual.html': 'manual',
        '/auto.html': 'auto',
        '/convert-manual.html': 'convert-manual',
        '/convert-auto.html': 'convert-auto',
        '/explain.html': 'explain',
        '/datasource.html': 'datasource',
        '/ai.html': 'ai',
        '/about.html': 'about',
        '/profile.html': 'profile'
    };

    function menuItem(href, icon, label, key) {
        var active = PAGES[location.pathname] === key ? ' active' : '';
        return '<a href="' + href + '" class="menu-item' + active + '">'
            + '<span class="menu-icon">' + icon + '</span><span>' + label + '</span></a>';
    }

    function template() {
        return '<div class="sidebar-header">'
            + '<h1>MuOpt <span style="font-size:14px;font-weight:normal;">v' + MUOPT_VERSION + '</span></h1>'
            + '<p>沐优 - SQL优化与信创转换</p>'
            + '</div>'
            + '<nav class="menu">'
            + '<div class="menu-group" data-group="optimize">'
            + '<div class="menu-group-title"><span class="menu-icon">🚀</span><span>SQL 优化</span>'
            + '<span class="menu-group-arrow">▾</span></div>'
            + '<div class="menu-group-items">'
            + menuItem('/manual.html', '✏️', '手工优化', 'manual')
            + menuItem('/auto.html', '🔍', '扫描优化', 'auto')
            + '</div></div>'
            + '<div class="menu-group" data-group="convert">'
            + '<div class="menu-group-title"><span class="menu-icon">🔄</span><span>SQL 转换</span>'
            + '<span class="menu-group-arrow">▾</span></div>'
            + '<div class="menu-group-items">'
            + menuItem('/convert-manual.html', '✏️', '手工转换', 'convert-manual')
            + menuItem('/convert-auto.html', '🔍', '扫描转换', 'convert-auto')
            + '</div></div>'
            + menuItem('/explain.html', '📊', 'SQL执行计划', 'explain')
            + menuItem('/datasource.html', '🔗', '数据源配置', 'datasource')
            + menuItem('/ai.html', '🤖', 'AI模型配置', 'ai')
            + menuItem('/about.html', 'ℹ️', '关于我们', 'about')
            + menuItem('/profile.html', '⚙️', '个人中心', 'profile')
            + '</nav>'
            + '<div class="sidebar-footer">'
            + '<div class="user-chip"><span class="user-avatar">👤</span>'
            + '<div class="user-chip-info">'
            + '<div class="user-nickname" data-user-nickname>加载中…</div>'
            + '<div class="user-account" data-user-account></div>'
            + '</div></div>'
            + '<a href="javascript:;" class="logout-link js-logout">🚪 退出登录</a>'
            + '</div>';
    }

    /** 移动端菜单联动：展开/收起按钮、遮罩、点击菜单项自动收起 */
    function bindMobileMenu() {
        var menuBtn = document.getElementById('mobileMenuBtn');
        var sidebar = document.querySelector('.sidebar');
        var overlay = document.getElementById('menuOverlay');
        if (!menuBtn || !sidebar || !overlay) {
            return;
        }
        var setMenu = function (open) {
            sidebar.classList.toggle('mobile-open', open);
            overlay.style.display = open ? 'block' : 'none';
            menuBtn.innerHTML = open ? '✕ 关闭' : '☰ 菜单';
        };
        menuBtn.addEventListener('click', function () {
            setMenu(!sidebar.classList.contains('mobile-open'));
        });
        overlay.addEventListener('click', function () { setMenu(false); });
        sidebar.querySelectorAll('.menu-item').forEach(function (a) {
            a.addEventListener('click', function () { setMenu(false); });
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        var aside = document.getElementById('sidebar');
        if (!aside) {
            return;
        }
        // 模板是固定可信常量（无任何用户输入），innerHTML 安全
        aside.innerHTML = template();
        bindMobileMenu();
    });
})();
