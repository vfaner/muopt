/**
 * 侧边栏二级分组：点击组标题展开/收起，收起状态存 localStorage；
 * 含当前页（.menu-item.active）的组始终强制展开，忽略已存状态。
 */
(function () {
    const KEY = 'muopt-menu-groups';
    const load = () => {
        try { return JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) { return {}; }
    };
    const save = (s) => localStorage.setItem(KEY, JSON.stringify(s));

    document.addEventListener('DOMContentLoaded', () => {
        const state = load();
        document.querySelectorAll('.menu-group').forEach(group => {
            const title = group.querySelector('.menu-group-title');
            const name = group.dataset.group || '';
            const hasActive = !!group.querySelector('.menu-item.active');
            if (hasActive) {
                group.classList.remove('collapsed');
            } else if (state[name]) {
                group.classList.add('collapsed');
            }
            if (title) {
                title.addEventListener('click', () => {
                    group.classList.toggle('collapsed');
                    const s = load();
                    s[name] = group.classList.contains('collapsed');
                    save(s);
                });
            }
        });
    });
})();
